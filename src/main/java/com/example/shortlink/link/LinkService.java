package com.example.shortlink.link;

import com.example.shortlink.codec.Base62Codec;
import com.example.shortlink.product.ProductGateway;
import com.example.shortlink.web.AppException;
import com.github.benmanes.caffeine.cache.*;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class LinkService {
  private static final Logger log = LoggerFactory.getLogger(LinkService.class);
  private final LinkRepository repository;
  private final ProductGateway products;
  private final Clock clock;
  private final LongSupplier random;
  private final Base62Codec codec = new Base62Codec();
  private final Cache<String, Optional<ShortLink>> cache;

  @Autowired
  public LinkService(LinkRepository repository, ProductGateway products, Clock clock) {
    this(repository, products, clock, secureRandom(), System::nanoTime);
  }

  public LinkService(
      LinkRepository repository,
      ProductGateway products,
      Clock clock,
      LongSupplier random,
      LongSupplier tickerNanos) {
    this.repository = repository;
    this.products = products;
    this.clock = clock;
    this.random = random;
    this.cache =
        Caffeine.newBuilder()
            .maximumSize(10000)
            .ticker(tickerNanos::getAsLong)
            .expireAfter(
                new Expiry<String, Optional<ShortLink>>() {
                  private long ttl(Optional<ShortLink> value) {
                    return TimeUnit.SECONDS.toNanos(value.isPresent() ? 300 : 30);
                  }

                  @Override
                  public long expireAfterCreate(String key, Optional<ShortLink> value, long time) {
                    return ttl(value);
                  }

                  @Override
                  public long expireAfterUpdate(
                      String key, Optional<ShortLink> value, long time, long duration) {
                    return ttl(value);
                  }

                  @Override
                  public long expireAfterRead(
                      String key, Optional<ShortLink> value, long time, long duration) {
                    return duration;
                  }
                })
            .build();
  }

  public CreateResult create(long productId, String sharerId, String channel) {
    if (productId <= 0
        || sharerId == null
        || !sharerId.matches("[A-Za-z0-9_-]{1,64}")
        || channel == null
        || !Set.of("wechat", "moments", "other").contains(channel)) {
      throw new AppException(400, "INVALID_LINK_REQUEST", "商品、分享人或渠道参数无效");
    }
    var product =
        products
            .findById(productId)
            .orElseThrow(() -> new AppException(404, "PRODUCT_NOT_FOUND", "商品不存在"));
    if (!product.active()) throw new AppException(409, "PRODUCT_OFF_SHELF", "商品已下架");
    var existing = repository.findByBusiness(productId, sharerId, channel);
    if (existing.isPresent()) return publish(existing.get(), true);
    for (int attempt = 0; attempt < 3; attempt++) {
      var link =
          new ShortLink(
              codec.encode(random.getAsLong()), productId, sharerId, channel, clock.instant());
      try {
        repository.insert(link);
        return publish(link, false);
      } catch (DuplicateKeyException collision) {
        existing = repository.findByBusiness(productId, sharerId, channel);
        if (existing.isPresent()) return publish(existing.get(), true);
        if (repository.findByKey(link.key()).isEmpty()) throw collision;
        log.warn("short_link_key_collision attempt={}", attempt + 1);
      }
    }
    throw new AppException(503, "KEY_GENERATION_EXHAUSTED", "短链生成暂时失败，请稍后重试");
  }

  public Optional<ShortLink> resolve(String key) {
    try {
      codec.decode(key);
    } catch (IllegalArgumentException invalid) {
      return Optional.empty();
    }
    boolean[] loaded = {false};
    Optional<ShortLink> result =
        cache.get(
            key,
            candidate -> {
              loaded[0] = true;
              return repository.findByKey(candidate);
            });
    log.debug(
        "short_link_cache outcome={} found={}",
        loaded[0] ? "miss" : result.isPresent() ? "hit" : "negative",
        result.isPresent());
    return result;
  }

  public void clearCache() {
    cache.invalidateAll();
  }

  private CreateResult publish(ShortLink link, boolean reused) {
    // Atomic compute waits for a pending miss loader before replacing its result.
    cache.asMap().compute(link.key(), (key, old) -> Optional.of(link));
    return new CreateResult(link, reused);
  }

  private static LongSupplier secureRandom() {
    var random = new SecureRandom();
    return () -> random.nextLong(Long.MAX_VALUE) + 1;
  }

  public record CreateResult(ShortLink link, boolean reused) {}
}
