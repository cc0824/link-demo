package com.example.shortlink.link;

import com.example.shortlink.config.AppConfig.PublicAddress;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/links")
public class LinkController {
  private final LinkService links;
  private final PublicAddress address;

  public LinkController(LinkService links, PublicAddress address) {
    this.links = links;
    this.address = address;
  }

  @PostMapping
  public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateRequest request) {
    var result = links.create(request.productId(), request.sharerId(), request.channel());
    String key = result.link().key();
    return ResponseEntity.status(result.reused() ? 200 : 201)
        .body(
            new LinkResponse(
                key, address.url("/s/" + key), address.url("/c/" + key), result.reused()));
  }

  public record CreateRequest(
      @NotNull @Positive Long productId,
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String sharerId,
      @NotBlank @Pattern(regexp = "wechat|moments|other") String channel) {}

  public record LinkResponse(String key, String shortUrl, String shareUrl, boolean reused) {}
}
