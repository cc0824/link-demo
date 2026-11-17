package com.example.shortlink.stats;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class StatsPolicy {
  private static final List<String> PREVIEW_AGENTS =
      List.of(
          "facebookexternalhit",
          "twitterbot",
          "slackbot",
          "discordbot",
          "whatsapp",
          "telegrambot",
          "googlebot",
          "bingbot",
          "bytespider",
          "linkedinbot",
          "pinterestbot");

  public boolean eligible(String method, String userAgent) {
    if (!"GET".equals(method)) {
      return false;
    }
    String agent = userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);
    return PREVIEW_AGENTS.stream().noneMatch(agent::contains);
  }

  public Optional<VisitCandidate> decide(
      String method, String userAgent, String linkKey, String visitorHash, Instant instant) {
    if (!eligible(method, userAgent)) {
      return Optional.empty();
    }
    return Optional.of(
        new VisitCandidate(
            linkKey, visitorHash, instant, Math.floorDiv(instant.getEpochSecond(), 10) * 10));
  }
}
