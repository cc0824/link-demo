package com.example.shortlink.codec;

/** Canonical, case-sensitive Base62 for strictly positive signed long values. */
public final class Base62Codec {
  private static final String ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

  public String encode(long value) {
    if (value <= 0) throw new IllegalArgumentException("Value must be positive");
    var result = new StringBuilder(11);
    while (value > 0) {
      result.append(ALPHABET.charAt((int) (value % 62)));
      value /= 62;
    }
    return result.reverse().toString();
  }

  public long decode(String key) {
    if (key == null || key.isEmpty() || key.length() > 11 || key.charAt(0) == '0') {
      throw new IllegalArgumentException("Invalid Base62 key");
    }
    long value = 0;
    for (int i = 0; i < key.length(); i++) {
      int digit = ALPHABET.indexOf(key.charAt(i));
      if (digit < 0 || value > (Long.MAX_VALUE - digit) / 62) {
        throw new IllegalArgumentException("Invalid or overflowing Base62 key");
      }
      value = value * 62 + digit;
    }
    return value;
  }
}
