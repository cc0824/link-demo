package com.example.shortlink.stats;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class VisitorIdentity {
  private static final int COOKIE_LENGTH = 101;
  private static final Pattern COOKIE_PATTERN =
      Pattern.compile(
          "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[0-9a-f]{64}");
  private final SecretKeySpec key;

  public VisitorIdentity(String secret) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("Visitor identity secret is required");
    }
    key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
  }

  public Identity identify(String cookieValue) {
    if (cookieValue != null
        && cookieValue.length() == COOKIE_LENGTH
        && COOKIE_PATTERN.matcher(cookieValue).matches()) {
      String uuid = cookieValue.substring(0, 36);
      byte[] supplied = HexFormat.of().parseHex(cookieValue.substring(37));
      if (MessageDigest.isEqual(hmac("sl_vid:v1:" + uuid), supplied)) {
        return new Identity(hash(uuid), cookieValue, false);
      }
    }
    String uuid = UUID.randomUUID().toString();
    String signedCookie = uuid + "." + HexFormat.of().formatHex(hmac("sl_vid:v1:" + uuid));
    return new Identity(hash(uuid), signedCookie, true);
  }

  private String hash(String uuid) {
    return HexFormat.of().formatHex(hmac(uuid));
  }

  private byte[] hmac(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(key);
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }

  public record Identity(String visitorHash, String cookieValue, boolean fresh) {}
}
