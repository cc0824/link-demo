package com.example.shortlink.page;

import com.example.shortlink.config.AppConfig.PublicAddress;
import com.example.shortlink.link.LinkRepository;
import com.example.shortlink.product.*;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

@Controller
@Profile("demo")
public class WorkbenchController {
  private final JdbcProductGateway products;
  private final LinkRepository links;
  private final PublicAddress address;

  public WorkbenchController(
      JdbcProductGateway products, LinkRepository links, PublicAddress address) {
    this.products = products;
    this.links = links;
    this.address = address;
  }

  @GetMapping("/workbench")
  public String page() {
    return "workbench";
  }

  @GetMapping("/api/demo/products")
  @ResponseBody
  public List<Product> products() {
    return products.demoProducts();
  }

  @PostMapping("/api/demo/products/{id}/restore")
  @ResponseBody
  public Product restore(@PathVariable long id) {
    return products.restore(id);
  }

  @GetMapping("/api/demo/links")
  @ResponseBody
  public List<LinkRow> links() {
    var catalog =
        products.demoProducts().stream()
            .collect(java.util.stream.Collectors.toMap(Product::id, p -> p));
    return links.recent().stream()
        .map(
            link -> {
              var product = catalog.get(link.productId());
              return new LinkRow(
                  link.key(),
                  link.productId(),
                  product == null ? "" : product.title(),
                  product == null ? "DELETED" : product.status(),
                  link.sharerId(),
                  link.channel(),
                  link.createdAt(),
                  address.url("/s/" + link.key()),
                  address.url("/c/" + link.key()));
            })
        .toList();
  }

  public record LinkRow(
      String key,
      long productId,
      String productTitle,
      String productStatus,
      String sharerId,
      String channel,
      Instant createdAt,
      String shortUrl,
      String shareUrl) {}
}
