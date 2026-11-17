package com.example.shortlink.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AbuseGuardTest {
  @Test
  void bucketRejectsBurstThenRefillsUsingMonotonicClock() {
    var time = new AtomicLong();
    var bucket = new TokenBucket(2, 1.0, time::get);
    assertEquals(0, bucket.acquire());
    assertEquals(0, bucket.acquire());
    assertEquals(1, bucket.acquire());
    time.set(1_000_000_000L);
    assertEquals(0, bucket.acquire());
    assertEquals(1, bucket.acquire());
  }

  @Test
  void concurrentConsumersCannotOverspendBucket() throws Exception {
    var bucket = new TokenBucket(5, 1.0, () -> 0L);
    var pool = Executors.newFixedThreadPool(10);
    try {
      var results = new java.util.ArrayList<Future<Integer>>();
      for (int i = 0; i < 40; i++) results.add(pool.submit(bucket::acquire));
      int allowed = 0;
      for (var result : results) if (result.get() == 0) allowed++;
      assertEquals(5, allowed);
    } finally {
      pool.shutdownNow();
    }
  }
}
