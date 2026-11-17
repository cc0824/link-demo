package com.example.shortlink.page;

import com.example.shortlink.config.AppConfig.PublicAddress;
import com.example.shortlink.stats.*;
import jakarta.servlet.http.*;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import org.slf4j.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class PageController {
  private static final Logger LOG = LoggerFactory.getLogger(PageController.class);
  private final PageService pages;
  private final StatsService stats;
  private final StatsPolicy policy;
  private final VisitorIdentity identity;
  private final Clock clock;
  private final PublicAddress address;
  private final org.springframework.core.env.Environment environment;

  public PageController(
      PageService pages,
      StatsService stats,
      StatsPolicy policy,
      VisitorIdentity identity,
      Clock clock,
      PublicAddress address,
      org.springframework.core.env.Environment environment) {
    this.pages = pages;
    this.stats = stats;
    this.policy = policy;
    this.identity = identity;
    this.clock = clock;
    this.address = address;
    this.environment = environment;
  }

  @GetMapping("/")
  public String index(Model model) {
    model.addAttribute("demoEnabled", environment.matchesProfiles("demo"));
    return "index";
  }

  @GetMapping("/c/{key}")
  public String card(@PathVariable String key, Model model) {
    model.addAllAttributes(pages.card(key));
    return "card";
  }

  @GetMapping("/products/{id}")
  public String product(@PathVariable long id, Model model) {
    model.addAllAttributes(pages.product(id));
    return "product";
  }

  @RequestMapping(
      value = "/s/{key}",
      method = {RequestMethod.GET, RequestMethod.HEAD})
  public ResponseEntity<Void> redirect(@PathVariable String key, HttpServletRequest request) {
    var link = pages.requireLink(key);
    pages.requireActiveProduct(link.productId(), true);
    var headers = new HttpHeaders();
    headers.setLocation(
        UriComponentsBuilder.fromPath("/products/{id}")
            .queryParam("channel", link.channel())
            .queryParam("sharerId", link.sharerId())
            .buildAndExpand(link.productId())
            .encode()
            .toUri());
    headers.setCacheControl("no-store");
    if (policy.eligible(request.getMethod(), request.getHeader("User-Agent"))) {
      String value =
          request.getCookies() == null
              ? null
              : Arrays.stream(request.getCookies())
                  .filter(cookie -> cookie.getName().equals("sl_vid"))
                  .map(Cookie::getValue)
                  .findFirst()
                  .orElse(null);
      var visitor = identity.identify(value);
      var candidate =
          policy
              .decide(
                  request.getMethod(),
                  request.getHeader("User-Agent"),
                  link.key(),
                  visitor.visitorHash(),
                  clock.instant())
              .orElseThrow();
      var result = stats.record(candidate);
      LOG.info("visit_record requestId={} outcome={}", MDC.get("requestId"), result);
      if (visitor.fresh()) {
        headers.add(
            HttpHeaders.SET_COOKIE,
            ResponseCookie.from("sl_vid", visitor.cookieValue())
                .httpOnly(true)
                .sameSite("Lax")
                .secure(address.secure())
                .path("/")
                .maxAge(Duration.ofDays(30))
                .build()
                .toString());
      }
    }
    return new ResponseEntity<>(headers, HttpStatus.FOUND);
  }
}
