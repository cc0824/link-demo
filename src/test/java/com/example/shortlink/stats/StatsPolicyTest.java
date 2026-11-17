package com.example.shortlink.stats;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class StatsPolicyTest {
  private final StatsPolicy policy = new StatsPolicy();

  @Test
  void onlyGetCountsAndWechatBrowserIsEligible() {
    assertTrue(policy.eligible("GET", "Mozilla/5.0 MicroMessenger/8.0"));
    assertTrue(policy.eligible("GET", null));
    assertFalse(policy.eligible("HEAD", "Mozilla/5.0"));
    assertFalse(policy.eligible("POST", "Mozilla/5.0"));
    for (String ua :
        new String[] {
          "facebookexternalhit/1.1",
          "Twitterbot",
          "Slackbot-LinkExpanding",
          "Discordbot",
          "WhatsApp/2",
          "TelegramBot",
          "Googlebot",
          "bingbot",
          "Bytespider"
        }) {
      assertFalse(policy.eligible("GET", ua), ua);
      assertTrue(policy.decide("GET", ua, "K", "V", Instant.EPOCH).isEmpty());
    }
  }

  @Test
  void usesFixedTenSecondBucketsAndPreservesExactVisitTime() {
    assertEquals(100, decide(100).bucketStart());
    assertEquals(100, decide(109).bucketStart());
    assertEquals(110, decide(110).bucketStart());
    assertEquals(-10, decide(-1).bucketStart());
    assertEquals(Instant.ofEpochSecond(109), decide(109).visitedAt());
    assertEquals("K", decide(109).linkKey());
    assertEquals("V", decide(109).visitorHash());
    assertTrue(policy.decide("HEAD", null, "K", "V", Instant.EPOCH).isEmpty());
  }

  private VisitCandidate decide(long second) {
    return policy
        .decide("GET", "MicroMessenger", "K", "V", Instant.ofEpochSecond(second))
        .orElseThrow();
  }
}
