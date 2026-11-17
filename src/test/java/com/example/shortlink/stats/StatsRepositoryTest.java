package com.example.shortlink.stats;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.shortlink.web.AppException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

class StatsRepositoryTest {
  private JdbcDataSource dataSource;
  private JdbcTemplate jdbc;
  private StatsService service;
  private final LocalDate from = LocalDate.of(2026, 11, 13);

  @BeforeEach
  void setUp() {
    dataSource = new JdbcDataSource();
    dataSource.setURL(
        "jdbc:h2:mem:stats" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000");
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    jdbc = new JdbcTemplate(dataSource);
    service = new StatsService(new StatsRepository(dataSource));
    link("A", 1, "alice", "wechat");
    link("B", 1, "bob", "moments");
    link("C", 1, "alice", "other");
  }

  @Test
  void twentyConcurrentDuplicatesLeaveOneEventAndNextBucketCountsAgain() throws Exception {
    var executor = Executors.newFixedThreadPool(20);
    var ready = new CountDownLatch(20);
    var start = new CountDownLatch(1);
    try {
      var futures = new ArrayList<java.util.concurrent.Future<StatsService.RecordResult>>();
      for (int i = 0; i < 20; i++) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return service.record(visit("A", "V", "2026-11-13T00:00:00Z"));
                }));
      }
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      int recorded = 0;
      int duplicates = 0;
      for (var future : futures) {
        var result = future.get(10, TimeUnit.SECONDS);
        recorded += result == StatsService.RecordResult.RECORDED ? 1 : 0;
        duplicates += result == StatsService.RecordResult.DUPLICATE ? 1 : 0;
      }
      assertEquals(1, recorded);
      assertEquals(19, duplicates);
      assertEquals(
          StatsService.RecordResult.DUPLICATE,
          service.record(visit("A", "V", "2026-11-13T00:00:09Z")));
      assertEquals(
          StatsService.RecordResult.RECORDED,
          service.record(visit("A", "V", "2026-11-13T00:00:10Z")));
      var result = service.query(from, from.plusDays(1), null, null, null, null);
      assertEquals(2, result.total().pv());
      assertEquals(1, result.total().uv());
      assertTrue(result.groups().isEmpty());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void independentInsertCommitsEvenIfCallerTransactionRollsBack() {
    new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        .execute(
            status -> {
              assertEquals(
                  StatsService.RecordResult.RECORDED,
                  service.record(visit("A", "V", "2026-11-13T00:00:00Z")));
              status.setRollbackOnly();
              return null;
            });
    assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM visit_event", Long.class));
  }

  @Test
  void totalsDeduplicateAcrossDatesChannelsAndSharersWithHongKongBoundaries() {
    record("A", "V", "2026-11-12T15:59:59Z"); // before from
    record("A", "V", "2026-11-12T16:00:00Z"); // inclusive from
    record("B", "V", "2026-11-13T16:00:00Z"); // next business day
    record("C", "V", "2026-11-14T00:00:00Z");
    record("A", "W", "2026-11-14T15:59:59Z");
    record("A", "X", "2026-11-14T16:00:00Z"); // exclusive to
    var result = service.query(from, from.plusDays(2), "channel,sharerId", null, null, null);
    assertEquals("Asia/Hong_Kong", result.timezone());
    assertEquals("v1-effective-click", result.metricVersion());
    assertEquals(from, result.from());
    assertEquals(from.plusDays(2), result.to());
    assertEquals(4, result.total().pv());
    assertEquals(2, result.total().uv());
    assertEquals(3, result.groups().size());
    assertEquals("moments", result.groups().get(0).channel());
    assertEquals("other", result.groups().get(1).channel());
    assertEquals("wechat", result.groups().get(2).channel());
    assertEquals("alice", result.groups().get(2).sharerId());
    assertEquals(2, result.groups().get(2).pv());
    assertEquals(2, result.groups().get(2).uv());
    assertEquals(4, result.groups().stream().mapToLong(StatsGroup::uv).sum());
    var filtered = service.query(from, from.plusDays(2), "sharerId", "A", "wechat", "alice");
    assertEquals(2, filtered.total().pv());
    assertNull(filtered.groups().get(0).channel());
    assertEquals("alice", filtered.groups().get(0).sharerId());
    var empty = service.query(from, from.plusDays(2), "channel", "A", "moments", "alice");
    assertEquals(0, empty.total().pv());
    assertEquals(0, empty.total().uv());
    assertTrue(empty.groups().isEmpty());
  }

  @Test
  void validatesRangeDimensionsAndEveryFilterBeforeQuerying() {
    for (String group :
        new String[] {
          "sharerId,channel", "channel,channel", "CHANNEL", "channel;drop table visit_event"
        }) {
      assertBadRequest(() -> service.query(from, from.plusDays(1), group, null, null, null));
    }
    assertBadRequest(() -> service.query(null, from, null, null, null, null));
    assertBadRequest(() -> service.query(from, from, null, null, null, null));
    assertBadRequest(() -> service.query(from.plusDays(1), from, null, null, null, null));
    assertBadRequest(() -> service.query(from, from.plusDays(32), null, null, null, null));
    assertBadRequest(() -> service.query(from, from.plusDays(1), null, "bad!", null, null));
    assertBadRequest(() -> service.query(from, from.plusDays(1), null, "A".repeat(12), null, null));
    assertBadRequest(() -> service.query(from, from.plusDays(1), null, null, "Wechat", null));
    assertBadRequest(() -> service.query(from, from.plusDays(1), null, null, null, "bad space"));
    assertBadRequest(() -> service.query(from, from.plusDays(1), null, null, null, "a".repeat(65)));
    assertEquals(0, service.query(from, from.plusDays(31), "", null, null, null).total().pv());
  }

  @Test
  void refusesMoreThanOneThousandGroups() {
    for (int i = 0; i < 1001; i++) {
      String key = "L" + i;
      link(key, i + 10, "u" + i, "wechat");
      record(key, "V", "2026-11-13T00:00:00Z");
    }
    var error =
        assertThrows(
            AppException.class,
            () -> service.query(from, from.plusDays(1), "sharerId", null, null, null));
    assertEquals(422, error.status());
    assertEquals("TOO_MANY_GROUPS", error.code());
  }

  @Test
  void infrastructureFailureLogsRequestIdWithoutVisitorOrSqlAndReturnsFailed() {
    Logger logger = (Logger) LoggerFactory.getLogger(StatsService.class);
    var appender = new ListAppender<ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    jdbc.execute("DROP TABLE visit_event");
    MDC.put("requestId", "request-stats-failure");
    try {
      assertEquals(
          StatsService.RecordResult.FAILED,
          service.record(visit("A", "private-visitor", "2026-11-13T00:00:00Z")));
      var event = appender.list.get(0);
      assertTrue(event.getFormattedMessage().contains("stats_write_failed"));
      assertTrue(event.getFormattedMessage().contains("request-stats-failure"));
      assertFalse(event.getFormattedMessage().contains("private-visitor"));
      assertFalse(event.getFormattedMessage().contains("INSERT"));
      assertNull(event.getThrowableProxy());
    } finally {
      MDC.clear();
      logger.detachAppender(appender);
    }
  }

  private void link(String key, long product, String sharer, String channel) {
    jdbc.update(
        "INSERT INTO short_link(link_key, product_id, sharer_id, channel, created_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP)",
        key,
        product,
        sharer,
        channel);
  }

  private VisitCandidate visit(String key, String visitor, String instant) {
    return new StatsPolicy()
        .decide("GET", "browser", key, visitor, Instant.parse(instant))
        .orElseThrow();
  }

  private void record(String key, String visitor, String instant) {
    assertEquals(StatsService.RecordResult.RECORDED, service.record(visit(key, visitor, instant)));
  }

  private void assertBadRequest(org.junit.jupiter.api.function.Executable action) {
    assertEquals(400, assertThrows(AppException.class, action).status());
  }
}
