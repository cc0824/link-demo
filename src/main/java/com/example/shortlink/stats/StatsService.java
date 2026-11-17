package com.example.shortlink.stats;

import com.example.shortlink.web.AppException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class StatsService {
  private static final Logger LOG = LoggerFactory.getLogger(StatsService.class);
  private static final Set<String> GROUPS = Set.of("", "channel", "sharerId", "channel,sharerId");
  private static final Set<String> CHANNELS = Set.of("wechat", "moments", "other");
  private final StatsRepository repository;

  public StatsService(StatsRepository repository) {
    this.repository = repository;
  }

  public RecordResult record(VisitCandidate candidate) {
    try {
      repository.insert(candidate);
      return RecordResult.RECORDED;
    } catch (DuplicateKeyException e) {
      return RecordResult.DUPLICATE;
    } catch (RuntimeException e) {
      LOG.warn(
          "stats_write_failed requestId={} failureType={}",
          MDC.get("requestId"),
          e.getClass().getSimpleName());
      return RecordResult.FAILED;
    }
  }

  public StatsResponse query(
      LocalDate from, LocalDate to, String groupBy, String key, String channel, String sharerId) {
    if (from == null
        || to == null
        || !from.isBefore(to)
        || ChronoUnit.DAYS.between(from, to) > 31) {
      throw invalidQuery();
    }
    String dimensions = groupBy == null ? "" : groupBy;
    if (!GROUPS.contains(dimensions)
        || (key != null && !key.matches("[A-Za-z0-9]{1,11}"))
        || (channel != null && !CHANNELS.contains(channel))
        || (sharerId != null && !sharerId.matches("[A-Za-z0-9_-]{1,64}"))) {
      throw invalidQuery();
    }
    return repository.query(from, to, dimensions, key, channel, sharerId);
  }

  private AppException invalidQuery() {
    return new AppException(400, "INVALID_STATS_QUERY", "日期、分组维度或筛选条件不合法，日期跨度最多31天");
  }

  public enum RecordResult {
    RECORDED,
    DUPLICATE,
    FAILED
  }
}
