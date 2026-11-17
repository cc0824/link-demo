package com.example.shortlink.link;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.shortlink.product.*;
import com.example.shortlink.web.AppException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.dao.*;

class LinkServiceTest {
  LinkRepository repository;
  ProductGateway products;
  AtomicLong ticker;
  final Clock clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC);
  final ShortLink first = new ShortLink("1", 1001, "alice", "wechat", clock.instant());

  @BeforeEach
  void setup() {
    repository = mock(LinkRepository.class);
    products = id -> Optional.of(new Product(id, "Title", "Description", "1001", "ACTIVE"));
    ticker = new AtomicLong();
    when(repository.findByBusiness(anyLong(), anyString(), anyString()))
        .thenReturn(Optional.empty());
    when(repository.findByKey(anyString())).thenReturn(Optional.empty());
  }

  LinkService service(long... values) {
    var index = new AtomicLong();
    return new LinkService(
        repository, products, clock, () -> values[(int) index.getAndIncrement()], ticker::get);
  }

  @Test
  void retriesKnownKeyCollisionsAndThirdAttemptSucceeds() {
    doThrow(new DuplicateKeyException("key"))
        .doThrow(new DuplicateKeyException("key"))
        .doNothing()
        .when(repository)
        .insert(any());
    when(repository.findByKey("1")).thenReturn(Optional.of(first));
    when(repository.findByKey("2"))
        .thenReturn(Optional.of(new ShortLink("2", 1001, "other", "other", clock.instant())));
    var result = service(1, 2, 3).create(1001, "bob", "other");
    assertEquals("3", result.link().key());
    assertFalse(result.reused());
  }

  @Test
  void threeKeyCollisionsReturn503() {
    doThrow(new DuplicateKeyException("key")).when(repository).insert(any());
    when(repository.findByKey("1")).thenReturn(Optional.of(first));
    var error =
        assertThrows(AppException.class, () -> service(1, 1, 1).create(1001, "bob", "other"));
    assertEquals(503, error.status());
    assertEquals("KEY_GENERATION_EXHAUSTED", error.code());
  }

  @Test
  void concurrentBusinessConflictReusesCommittedWinner() {
    when(repository.findByBusiness(1001, "alice", "wechat"))
        .thenReturn(Optional.empty(), Optional.of(first));
    doThrow(new DuplicateKeyException("business")).when(repository).insert(any());
    var result = service(2).create(1001, "alice", "wechat");
    assertTrue(result.reused());
    assertEquals("1", result.link().key());
  }

  @Test
  void storageErrorsAndUnknownUniquenessFailuresPropagateWithoutRetry() {
    doThrow(new DataAccessResourceFailureException("offline")).when(repository).insert(any());
    assertThrows(
        DataAccessResourceFailureException.class, () -> service(1).create(1001, "alice", "wechat"));
    doThrow(new DuplicateKeyException("unknown constraint")).when(repository).insert(any());
    assertThrows(DuplicateKeyException.class, () -> service(1).create(1001, "alice", "wechat"));
  }

  @Test
  void validatesInputsAndCurrentProductBeforeReusing() {
    for (String sharer : new String[] {null, "", "a b", "a".repeat(65), "用户"})
      assertEquals(
          400,
          assertThrows(AppException.class, () -> service(1).create(1001, sharer, "wechat"))
              .status());
    assertEquals(
        400, assertThrows(AppException.class, () -> service(1).create(0, "a", "wechat")).status());
    assertEquals(
        400,
        assertThrows(AppException.class, () -> service(1).create(1001, "a", "WeChat")).status());
    products = id -> Optional.empty();
    assertEquals(
        404,
        assertThrows(AppException.class, () -> service(1).create(1001, "a", "wechat")).status());
    products = id -> Optional.of(new Product(id, "", "", null, "OFF_SHELF"));
    assertEquals(
        409,
        assertThrows(AppException.class, () -> service(1).create(1001, "a", "wechat")).status());
  }

  @Test
  void negativeCacheExpiresAtThirtySecondsAndPositiveAtFiveMinutes() {
    var service = service(1);
    assertTrue(service.resolve("1").isEmpty());
    when(repository.findByKey("1")).thenReturn(Optional.of(first));
    ticker.set(TimeUnit.SECONDS.toNanos(29));
    assertTrue(service.resolve("1").isEmpty());
    ticker.set(TimeUnit.SECONDS.toNanos(30));
    assertEquals(first, service.resolve("1").orElseThrow());
    when(repository.findByKey("1")).thenReturn(Optional.empty());
    ticker.set(TimeUnit.SECONDS.toNanos(329));
    assertEquals(first, service.resolve("1").orElseThrow());
    ticker.set(TimeUnit.SECONDS.toNanos(330));
    assertTrue(service.resolve("1").isEmpty());
  }

  @Test
  void malformedKeysBypassDatabaseAndFailuresAreNeverCached() {
    var service = service(1);
    assertTrue(service.resolve("01").isEmpty());
    assertTrue(service.resolve(null).isEmpty());
    verifyNoInteractions(repository);
    when(repository.findByKey("1"))
        .thenThrow(new DataAccessResourceFailureException("offline"))
        .thenReturn(Optional.of(first));
    assertThrows(DataAccessResourceFailureException.class, () -> service.resolve("1"));
    assertEquals(first, service.resolve("1").orElseThrow());
  }

  @Test
  void creationReplacesNegativeCache() {
    var service = service(1);
    assertTrue(service.resolve("1").isEmpty());
    service.create(1001, "alice", "wechat");
    assertEquals(first, service.resolve("1").orElseThrow());
    service.clearCache();
    assertTrue(service.resolve("1").isEmpty());
  }

  @Test
  void pendingMissCannotOverwriteSuccessfulCreation() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var inserted = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              inserted.countDown();
              return null;
            })
        .when(repository)
        .insert(any());
    when(repository.findByKey("1"))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return Optional.empty();
            });
    var service = service(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      var load = pool.submit(() -> service.resolve("1"));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      var create = pool.submit(() -> service.create(1001, "alice", "wechat"));
      assertTrue(inserted.await(5, TimeUnit.SECONDS));
      release.countDown();
      load.get(5, TimeUnit.SECONDS);
      create.get(5, TimeUnit.SECONDS);
      assertEquals(first, service.resolve("1").orElseThrow());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentSameKeyMissLoadsRepositoryOnlyOnce() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicLong();
    when(repository.findByKey("1"))
        .thenAnswer(
            invocation -> {
              calls.incrementAndGet();
              entered.countDown();
              assertTrue(release.await(5, TimeUnit.SECONDS));
              return Optional.empty();
            });
    var service = service(1);
    var pool = Executors.newFixedThreadPool(8);
    try {
      var futures = new ArrayList<Future<Optional<ShortLink>>>();
      for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> service.resolve("1")));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      release.countDown();
      for (var result : futures) assertTrue(result.get(5, TimeUnit.SECONDS).isEmpty());
      assertEquals(1, calls.get());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }
}
