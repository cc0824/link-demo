package com.example.shortlink.codec;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class Base62CodecTest {
  private final Base62Codec codec = new Base62Codec();

  @Test
  void knownValuesAndLongBoundary() {
    assertEquals("1", codec.encode(1));
    assertEquals("z", codec.encode(61));
    assertEquals("10", codec.encode(62));
    assertEquals("AzL8n0Y58m7", codec.encode(Long.MAX_VALUE));
    assertEquals(Long.MAX_VALUE, codec.decode("AzL8n0Y58m7"));
    assertEquals(36, codec.decode("a"));
    assertEquals(10, codec.decode("A"));
  }

  @Test
  void rejectsNonCanonicalAndOverflowInput() {
    for (String key :
        new String[] {
          null,
          "",
          "0",
          "01",
          " 1",
          "1 ",
          "+1",
          "-1",
          "中",
          "😀",
          "%31",
          "a_",
          "AzL8n0Y58m8",
          "zzzzzzzzzzz",
          "111111111111"
        }) {
      assertThrows(IllegalArgumentException.class, () -> codec.decode(key), String.valueOf(key));
    }
    assertThrows(IllegalArgumentException.class, () -> codec.encode(0));
    assertThrows(IllegalArgumentException.class, () -> codec.encode(-1));
  }
}
