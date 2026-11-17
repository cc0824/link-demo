package com.example.shortlink.link;

import static org.junit.jupiter.api.Assertions.*;

import com.example.shortlink.product.*;
import com.example.shortlink.web.AppException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class LinkRepositoryTest {
  JdbcTemplate jdbc;
  LinkRepository repository;
  JdbcProductGateway products;
  final Clock clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC);

  @BeforeEach
  void database() {
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    jdbc = new JdbcTemplate(dataSource);
    repository = new LinkRepository(jdbc);
    products = new JdbcProductGateway(jdbc, clock);
    products.initializeSeed();
  }

  @Test
  void persistsCaseSensitiveKeysAndIndependentBusinessMappings() {
    repository.insert(new ShortLink("A", 1001, "Alice", "wechat", clock.instant()));
    repository.insert(new ShortLink("a", 1001, "alice", "wechat", clock.instant()));
    assertEquals("Alice", repository.findByKey("A").orElseThrow().sharerId());
    assertEquals("a", repository.findByBusiness(1001, "alice", "wechat").orElseThrow().key());
  }

  @Test
  void insertIsAutocommitAndUniquenessFailureDoesNotPoisonCallerTransaction() {
    var transactions =
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    transactions.executeWithoutResult(
        status -> {
          repository.insert(new ShortLink("1", 1001, "alice", "wechat", clock.instant()));
          assertThrows(
              DuplicateKeyException.class,
              () ->
                  repository.insert(new ShortLink("2", 1001, "alice", "wechat", clock.instant())));
          assertEquals("1", repository.findByBusiness(1001, "alice", "wechat").orElseThrow().key());
          status.setRollbackOnly();
        });
    assertTrue(repository.findByKey("1").isPresent());
    assertTrue(repository.findByKey("2").isEmpty());
  }

  @Test
  void concurrentSameBusinessCreatesExactlyOneRowAndOneKey() throws Exception {
    var sequence = new AtomicLong(100);
    var service =
        new LinkService(repository, products, clock, sequence::incrementAndGet, System::nanoTime);
    var pool = Executors.newFixedThreadPool(12);
    try {
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<LinkService.CreateResult>>();
      for (int i = 0; i < 30; i++)
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return service.create(1001, "same", "wechat");
                }));
      start.countDown();
      var keys = new HashSet<String>();
      int fresh = 0;
      for (var result : futures) {
        var value = result.get(10, TimeUnit.SECONDS);
        keys.add(value.link().key());
        if (!value.reused()) fresh++;
      }
      assertEquals(1, keys.size());
      assertEquals(1, fresh);
      assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM short_link", Integer.class));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void seedMarkerPreservesChangesAndEvenDeletionOfEveryProduct() {
    products.patch(1001, Map.of("status", "OFF_SHELF", "title", "changed"));
    products.initializeSeed();
    assertFalse(products.findById(1001).orElseThrow().active());
    assertEquals("changed", products.findById(1001).orElseThrow().title());
    products.delete(1001);
    products.delete(1002);
    products.initializeSeed();
    assertTrue(products.findById(1001).isEmpty());
    products.resetSeed();
    assertTrue(products.findById(1001).orElseThrow().active());
    assertEquals("", products.findById(1002).orElseThrow().title());
  }

  @Test
  void patchRejectsUnknownFieldsTypesAndStatuses() {
    assertThrows(RuntimeException.class, () -> products.patch(1001, Map.of("status", "active")));
    assertThrows(RuntimeException.class, () -> products.patch(1001, Map.of("title", 123)));
    assertThrows(RuntimeException.class, () -> products.patch(1001, Map.of("id", 2000)));
    assertTrue(products.findById(1001).orElseThrow().active());
  }

  @Test
  void explicitNullStatusIsAClientError() {
    var patch = new HashMap<String, Object>();
    patch.put("status", null);
    assertEquals(400, assertThrows(AppException.class, () -> products.patch(1001, patch)).status());
  }

  @Test
  void titleOnlyPatchCannotRestoreStatusFromAnEarlierRead() throws Exception {
    var titleRead = new CountDownLatch(1);
    var releaseTitle = new CountDownLatch(1);
    var firstRead = new AtomicBoolean(true);
    var delayedTitleGateway =
        new JdbcProductGateway(jdbc, clock) {
          @Override
          public Optional<Product> findById(long id) {
            Optional<Product> product = super.findById(id);
            if (firstRead.compareAndSet(true, false)) {
              titleRead.countDown();
              try {
                assertTrue(releaseTitle.await(5, TimeUnit.SECONDS));
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
              }
            }
            return product;
          }
        };
    var transactions =
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    var pool = Executors.newSingleThreadExecutor();
    try {
      var titlePatch =
          pool.submit(
              () ->
                  transactions.execute(
                      status -> delayedTitleGateway.patch(1001, Map.of("title", "updated title"))));
      assertTrue(titleRead.await(5, TimeUnit.SECONDS));
      transactions.executeWithoutResult(
          status -> products.patch(1001, Map.of("status", "OFF_SHELF")));
      assertEquals("OFF_SHELF", products.findById(1001).orElseThrow().status());
      releaseTitle.countDown();
      Product result = titlePatch.get(5, TimeUnit.SECONDS);
      assertEquals("OFF_SHELF", result.status());
      Product persisted = products.findById(1001).orElseThrow();
      assertEquals("updated title", persisted.title());
      assertEquals("OFF_SHELF", persisted.status());
    } finally {
      releaseTitle.countDown();
      pool.shutdownNow();
    }
  }
}
