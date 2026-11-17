package com.example.shortlink.stats;

import com.example.shortlink.web.AppException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
public class StatsRepository {
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Hong_Kong");
  private static final Map<String, String> GROUP_COLUMNS =
      Map.of(
          "channel",
          "l.channel",
          "sharerId",
          "l.sharer_id",
          "channel,sharerId",
          "l.channel,l.sharer_id");
  private final DataSource dataSource;

  public StatsRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public void insert(VisitCandidate event) {
    // Direct connection ownership deliberately isolates this write from caller transactions.
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement statement =
          connection.prepareStatement(
              "INSERT INTO visit_event(link_key,visitor_hash,visited_at,bucket_start) VALUES (?,?,?,?)")) {
        statement.setQueryTimeout(1);
        statement.setString(1, event.linkKey());
        statement.setString(2, event.visitorHash());
        statement.setObject(3, event.visitedAt().atOffset(ZoneOffset.UTC));
        statement.setLong(4, event.bucketStart());
        statement.executeUpdate();
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        rollback(connection, e);
        throw e;
      }
    } catch (SQLException e) {
      throw translate(e);
    }
  }

  public StatsResponse query(
      LocalDate from, LocalDate to, String groupBy, String key, String channel, String sharerId) {
    String columns = groupBy.isEmpty() ? null : GROUP_COLUMNS.get(groupBy);
    if (!groupBy.isEmpty() && columns == null) {
      throw new IllegalArgumentException("Unsupported statistics dimensions");
    }
    StringBuilder source =
        new StringBuilder(
            " FROM visit_event v JOIN short_link l ON l.link_key=v.link_key WHERE v.visited_at>=? AND v.visited_at<?");
    List<Object> parameters = new ArrayList<>();
    parameters.add(from.atStartOfDay(BUSINESS_ZONE).toOffsetDateTime());
    parameters.add(to.atStartOfDay(BUSINESS_ZONE).toOffsetDateTime());
    filter(source, parameters, "l.link_key", key);
    filter(source, parameters, "l.channel", channel);
    filter(source, parameters, "l.sharer_id", sharerId);
    try (Connection connection = dataSource.getConnection()) {
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try {
        StatsResponse.Metric total;
        try (PreparedStatement statement =
                prepare(
                    connection,
                    "SELECT COUNT(*),COUNT(DISTINCT v.visitor_hash)" + source,
                    parameters);
            var rows = statement.executeQuery()) {
          rows.next();
          total = new StatsResponse.Metric(rows.getLong(1), rows.getLong(2));
        }
        List<StatsGroup> groups = new ArrayList<>();
        if (columns != null) {
          String sql =
              "SELECT "
                  + columns
                  + ",COUNT(*),COUNT(DISTINCT v.visitor_hash)"
                  + source
                  + " GROUP BY "
                  + columns
                  + " ORDER BY "
                  + columns
                  + " FETCH FIRST 1001 ROWS ONLY";
          try (PreparedStatement statement = prepare(connection, sql, parameters);
              var rows = statement.executeQuery()) {
            boolean hasChannel = groupBy.contains("channel");
            boolean hasSharer = groupBy.contains("sharerId");
            while (rows.next()) {
              int index = 1;
              String groupChannel = hasChannel ? rows.getString(index++) : null;
              String groupSharer = hasSharer ? rows.getString(index++) : null;
              groups.add(
                  new StatsGroup(
                      groupChannel, groupSharer, rows.getLong(index++), rows.getLong(index)));
            }
          }
          if (groups.size() > 1000) {
            throw new AppException(422, "TOO_MANY_GROUPS", "统计分组超过1000，请缩小查询范围");
          }
        }
        connection.commit();
        return new StatsResponse(
            BUSINESS_ZONE.getId(), from, to, "v1-effective-click", total, List.copyOf(groups));
      } catch (SQLException | RuntimeException e) {
        rollback(connection, e);
        throw e;
      }
    } catch (SQLException e) {
      throw translate(e);
    }
  }

  private void filter(StringBuilder sql, List<Object> parameters, String column, String value) {
    if (value != null) {
      sql.append(" AND ").append(column).append("=?");
      parameters.add(value);
    }
  }

  private PreparedStatement prepare(Connection connection, String sql, List<Object> parameters)
      throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    try {
      statement.setQueryTimeout(1);
      for (int i = 0; i < parameters.size(); i++) {
        statement.setObject(i + 1, parameters.get(i));
      }
      return statement;
    } catch (SQLException e) {
      statement.close();
      throw e;
    }
  }

  private void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      original.addSuppressed(rollbackFailure);
    }
  }

  private DataAccessException translate(SQLException exception) {
    if ("23505".equals(exception.getSQLState())) {
      return new DuplicateKeyException("Visit already recorded", exception);
    }
    return new DataAccessResourceFailureException("Statistics storage operation failed", exception);
  }
}
