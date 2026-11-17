package com.example.shortlink.codec;

import static org.junit.jupiter.api.Assertions.*;

import net.jqwik.api.*;
import net.jqwik.api.constraints.LongRange;

class Base62Properties {
  private final Base62Codec codec = new Base62Codec();

  @Property(tries = 10000, seed = "20261113")
  void roundTrip(@ForAll @LongRange(min = 1, max = Long.MAX_VALUE) long value) {
    assertEquals(value, codec.decode(codec.encode(value)));
  }

  @Provide
  Arbitrary<String> keys() {
    return Arbitraries.strings()
        .withChars("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz")
        .ofMinLength(0)
        .ofMaxLength(12);
  }

  @Property(tries = 10000, seed = "20261114")
  void independentlyGeneratedAcceptedKeysAreCanonical(@ForAll("keys") String key) {
    long value;
    try {
      value = codec.decode(key);
    } catch (IllegalArgumentException rejected) {
      return;
    }
    assertTrue(value > 0);
    assertEquals(key, codec.encode(value));
  }
}
