package com.example.shortlink.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(-100)
public class RequestIdFilter extends OncePerRequestFilter {
  private static final Logger LOG = LoggerFactory.getLogger(RequestIdFilter.class);

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String id = UUID.randomUUID().toString();
    request.setAttribute("requestId", id);
    response.setHeader("X-Request-Id", id);
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader(
        "Content-Security-Policy",
        "default-src 'none'; img-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
    long start = System.nanoTime();
    MDC.put("requestId", id);
    try {
      chain.doFilter(request, response);
    } finally {
      // Log route pattern, never query strings, cookies, authorization or the client address.
      Object route =
          request.getAttribute(
              "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern");
      LOG.info(
          "http_request requestId={} method={} route={} status={} durationMs={}",
          id,
          request.getMethod(),
          route == null ? "unmatched" : route,
          response.getStatus(),
          (System.nanoTime() - start) / 1_000_000);
      MDC.remove("requestId");
    }
  }
}
