package com.example.shortlink.config;

import com.example.shortlink.stats.VisitorIdentity;
import java.net.URI;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {
  @Bean
  org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
      strictJsonTypes() {
    return builder ->
        builder.postConfigurer(
            mapper -> {
              mapper
                  .coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual)
                  .setCoercion(
                      com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
                      com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                  .setCoercion(
                      com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
                      com.fasterxml.jackson.databind.cfg.CoercionAction.Fail)
                  .setCoercion(
                      com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean,
                      com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
              mapper
                  .coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Integer)
                  .setCoercion(
                      com.fasterxml.jackson.databind.cfg.CoercionInputShape.String,
                      com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
            });
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  VisitorIdentity visitorIdentity(@Value("${app.visitor-secret}") String secret) {
    if (secret.length() < 32)
      throw new IllegalArgumentException(
          "VISITOR_HMAC_SECRET must contain at least 32 characters; use demo profile for local demo");
    return new VisitorIdentity(secret);
  }

  @Bean
  PublicAddress publicAddress(@Value("${app.public-base-url}") String value) {
    URI uri = URI.create(value);
    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
      throw new IllegalArgumentException("PUBLIC_BASE_URL must be an absolute HTTP(S) origin");
    }
    return new PublicAddress(value.replaceAll("/+$", ""), "https".equals(uri.getScheme()));
  }

  public record PublicAddress(String baseUrl, boolean secure) {
    public String url(String path) {
      return baseUrl + path;
    }
  }
}
