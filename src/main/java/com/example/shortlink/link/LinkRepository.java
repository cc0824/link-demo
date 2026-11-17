package com.example.shortlink.link;

import java.sql.*;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class LinkRepository {
  private static final RowMapper<ShortLink> MAPPER =
      (row, n) ->
          new ShortLink(
              row.getString("link_key"),
              row.getLong("product_id"),
              row.getString("sharer_id"),
              row.getString("channel"),
              row.getTimestamp("created_at").toInstant());
  private final JdbcTemplate jdbc;

  public LinkRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<ShortLink> findByKey(String key) {
    return jdbc.query("SELECT * FROM short_link WHERE link_key=?", MAPPER, key).stream()
        .findFirst();
  }

  public java.util.List<ShortLink> recent() {
    return jdbc.query(
        "SELECT * FROM short_link ORDER BY created_at DESC,link_key DESC FETCH FIRST 100 ROWS ONLY",
        MAPPER);
  }

  public Optional<ShortLink> findByBusiness(long productId, String sharerId, String channel) {
    return jdbc
        .query(
            "SELECT * FROM short_link WHERE product_id=? AND sharer_id=? AND channel=?",
            MAPPER,
            productId,
            sharerId,
            channel)
        .stream()
        .findFirst();
  }

  /** Independent autocommit makes constraint failures safe to recover from with a fresh read. */
  public void insert(ShortLink link) {
    String sql =
        "INSERT INTO short_link(link_key,product_id,sharer_id,channel,created_at) VALUES(?,?,?,?,?)";
    try (Connection connection = jdbc.getDataSource().getConnection()) {
      connection.setAutoCommit(true);
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setQueryTimeout(1);
        statement.setString(1, link.key());
        statement.setLong(2, link.productId());
        statement.setString(3, link.sharerId());
        statement.setString(4, link.channel());
        statement.setTimestamp(5, Timestamp.from(link.createdAt()));
        statement.executeUpdate();
      }
    } catch (SQLException exception) {
      DataAccessException translated =
          jdbc.getExceptionTranslator().translate("insert short link", sql, exception);
      throw translated != null
          ? translated
          : new UncategorizedSQLException("insert short link", sql, exception);
    }
  }
}
