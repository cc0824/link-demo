package com.example.shortlink.page;

import com.example.shortlink.product.ProductGateway;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class MediaController {
  private final ProductGateway products;
  private final byte[] fallback;
  private final Map<String, byte[]> images;

  public MediaController(ProductGateway products) throws IOException {
    this.products = products;
    fallback = readImage(new ClassPathResource("static/images/default-product.png"));
    var resource = new ClassPathResource("static/images/product-1001.png");
    images = resource.exists() ? Map.of("1001", readImage(resource)) : Map.of();
  }

  private static byte[] readImage(ClassPathResource resource) throws IOException {
    try (var stream = resource.getInputStream()) {
      return stream.readAllBytes();
    }
  }

  @GetMapping(value = "/media/products/{id}.png", produces = MediaType.IMAGE_PNG_VALUE)
  public ResponseEntity<byte[]> image(@PathVariable long id) {
    byte[] image =
        products
            .findById(id)
            .filter(p -> p.active() && p.imageRef() != null)
            .map(p -> images.getOrDefault(p.imageRef(), fallback))
            .orElse(fallback);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.IMAGE_PNG)
        .body(image);
  }
}
