package com.example.shortlink.stats;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VisitorIdentityTest {
  private static final String SECRET = "stable-test-secret-at-least-32-characters";

  @Test
  void signedIdentitySurvivesRestartAndSeparatesHashFromCookieSignature() {
    // Independent HMAC-SHA256 vector produced with .NET's cryptographic implementation.
    var known =
        new VisitorIdentity(SECRET)
            .identify(
                "123e4567-e89b-42d3-a456-426614174000.d3e40d53088e5e1b91d74fd2bb519f56158aad14ba325ab0e582c022ba55a2b5");
    assertFalse(known.fresh());
    assertEquals(
        "05e66cef39a0a5e8949de4c87198a34a1805ccc12f617e7c58c27a15b5818974", known.visitorHash());
    var first = new VisitorIdentity(SECRET).identify(null);
    assertTrue(first.fresh());
    assertTrue(first.cookieValue().matches("[0-9a-f-]{36}\\.[0-9a-f]{64}"));
    assertTrue(first.visitorHash().matches("[0-9a-f]{64}"));
    var next = new VisitorIdentity(SECRET).identify(first.cookieValue());
    assertFalse(next.fresh());
    assertEquals(first.cookieValue(), next.cookieValue());
    assertEquals(first.visitorHash(), next.visitorHash());
    assertNotEquals(first.cookieValue().substring(37), first.visitorHash());
    assertNotEquals(first.visitorHash(), new VisitorIdentity(SECRET).identify(null).visitorHash());
    assertTrue(new VisitorIdentity("a-different-secret").identify(first.cookieValue()).fresh());
  }

  @Test
  void malformedForgedAndOversizedCookiesBecomeNewIdentities() {
    var identity = new VisitorIdentity(SECRET);
    var cookie = identity.identify(null).cookieValue();
    for (String invalid :
        new String[] {
          "",
          " ",
          "a".repeat(10000),
          cookie + ".x",
          cookie.substring(0, 99),
          "z" + cookie.substring(1),
          cookie.substring(0, 37) + "0".repeat(64),
          cookie.substring(0, 37) + "A" + cookie.substring(38)
        }) {
      var next = identity.identify(invalid);
      assertTrue(next.fresh());
      assertNotEquals(cookie, next.cookieValue());
    }
  }
}
