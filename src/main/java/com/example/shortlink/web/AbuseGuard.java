package com.example.shortlink.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AbuseGuard {
  private final boolean enabled;
  private final Cache<String, TokenBucket> clients =
      Caffeine.newBuilder().maximumSize(20_000).expireAfterAccess(Duration.ofMinutes(10)).build();
  private final TokenBucket global = new TokenBucket(200, 100, System::nanoTime);
  private final TokenBucket create = new TokenBucket(5, 0.5, System::nanoTime);
  private final TokenBucket stats = new TokenBucket(10, 0.5, System::nanoTime);

  public AbuseGuard(@Value("${app.rate-limit-enabled:true}") boolean enabled) {
    this.enabled = enabled;
  }

  public int publicRequest(String remoteIp) {
    if (!enabled) return 0;
    int wait = global.acquire();
    return wait != 0
        ? wait
        : clients.get(remoteIp, key -> new TokenBucket(30, 2, System::nanoTime)).acquire();
  }

  public int apiRequest(String path) {
    if (!enabled) return 0;
    if (path.equals("/api/links")) return create.acquire();
    if (path.equals("/api/stats")) return stats.acquire();
    return 0;
  }
}
