package com.example.shortlink.product;

import com.example.shortlink.link.LinkService;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("demo")
@RequestMapping("/api/demo")
public class DemoProductController {
  private final JdbcProductGateway products;
  private final JdbcTemplate jdbc;
  private final LinkService links;
  private final TransactionTemplate tx;

  public DemoProductController(
      JdbcProductGateway products,
      JdbcTemplate jdbc,
      LinkService links,
      PlatformTransactionManager manager) {
    this.products = products;
    this.jdbc = jdbc;
    this.links = links;
    this.tx = new TransactionTemplate(manager);
  }

  @PatchMapping("/products/{id}")
  public Product patch(@PathVariable long id, @RequestBody Map<String, Object> updates) {
    return products.patch(id, updates);
  }

  @DeleteMapping("/products/{id}")
  public ResponseEntity<Void> delete(@PathVariable long id) {
    products.delete(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/reset")
  public ResponseEntity<Void> reset() {
    tx.executeWithoutResult(
        status -> {
          jdbc.update("DELETE FROM visit_event");
          jdbc.update("DELETE FROM short_link");
          products.resetSeed();
        });
    links.clearCache();
    return ResponseEntity.noContent().build();
  }
}
