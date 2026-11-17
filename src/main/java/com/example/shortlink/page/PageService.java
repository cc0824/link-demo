package com.example.shortlink.page;

import com.example.shortlink.config.AppConfig.PublicAddress;
import com.example.shortlink.link.*;
import com.example.shortlink.product.*;
import com.example.shortlink.web.AppException;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PageService {
  private final LinkService links;
  private final ProductGateway products;
  private final PublicAddress address;

  public PageService(LinkService links, ProductGateway products, PublicAddress address) {
    this.links = links;
    this.products = products;
    this.address = address;
  }

  public ShortLink requireLink(String key) {
    return links
        .resolve(key)
        .orElseThrow(() -> new AppException(404, "LINK_NOT_FOUND", "链接不存在，请确认分享地址是否完整"));
  }

  public Product requireActiveProduct(long id, boolean shared) {
    Product product =
        products
            .findById(id)
            .orElseThrow(() -> new AppException(shared ? 410 : 404, "PRODUCT_NOT_FOUND", "商品已不可用"));
    if (!product.active()) throw new AppException(410, "PRODUCT_OFF_SHELF", "商品暂不可购买");
    return product;
  }

  public Map<String, Object> card(String key) {
    ShortLink link = requireLink(key);
    Product product = products.findById(link.productId()).orElse(null);
    boolean active = product != null && product.active();
    String title =
        product == null ? "商品已不可用" : !active ? "商品暂不可购买" : fallback(product.title(), "精选商品");
    String description =
        active ? fallback(product.description(), "查看商品详情与最新售卖状态") : "这件商品暂时无法购买，看看其他好物吧。";
    return Map.of(
        "title",
        title,
        "description",
        description,
        "active",
        active,
        "imageUrl",
        address.url("/media/products/" + link.productId() + ".png"),
        "canonicalUrl",
        address.url("/c/" + key),
        "shortUrl",
        "/s/" + key,
        "productId",
        link.productId());
  }

  public Map<String, Object> product(long id) {
    Product product = requireActiveProduct(id, false);
    return Map.of(
        "title",
        fallback(product.title(), "精选商品"),
        "description",
        fallback(product.description(), "查看商品详情与最新售卖状态"),
        "active",
        true,
        "imageUrl",
        address.url("/media/products/" + id + ".png"),
        "canonicalUrl",
        address.url("/products/" + id),
        "productId",
        id);
  }

  private String fallback(String text, String replacement) {
    return text == null || text.isBlank() ? replacement : text;
  }
}
