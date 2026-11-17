package com.example.shortlink.web;

import java.util.function.LongSupplier;

/** Thread-safe single-process token bucket. acquire returns zero or retry delay in seconds. */
public final class TokenBucket {
  private final double capacity;
  private final double perSecond;
  private final LongSupplier ticker;
  private double tokens;
  private long last;

  public TokenBucket(int capacity, double perSecond, LongSupplier ticker) {
    if (capacity < 1 || perSecond <= 0)
      throw new IllegalArgumentException("Invalid bucket settings");
    this.capacity = capacity;
    this.tokens = capacity;
    this.perSecond = perSecond;
    this.ticker = ticker;
    this.last = ticker.getAsLong();
  }

  public synchronized int acquire() {
    long now = ticker.getAsLong();
    double elapsed = Math.max(0, now - last) / 1_000_000_000.0;
    tokens = Math.min(capacity, tokens + elapsed * perSecond);
    last = now;
    if (tokens >= 1) {
      tokens--;
      return 0;
    }
    return Math.max(1, (int) Math.ceil((1 - tokens) / perSecond));
  }
}
