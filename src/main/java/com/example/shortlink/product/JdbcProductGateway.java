package com.example.shortlink.product;

import com.example.shortlink.web.AppException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProductGateway implements ProductGateway {
  private final JdbcTemplate jdbc;
  private final Clock clock;

  public JdbcProductGateway(JdbcTemplate jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  @Override
  public Optional<Product> findById(long id) {
    return jdbc
        .query(
            "SELECT id,title,description,image_ref,status FROM product WHERE id=?",
            (row, n) ->
                new Product(
                    row.getLong("id"),
                    row.getString("title"),
                    row.getString("description"),
                    row.getString("image_ref"),
                    row.getString("status")),
            id)
        .stream()
        .findFirst();
  }

  @Transactional
  public Product patch(long id, Map<String, Object> updates) {
    if (updates == null
        || updates.isEmpty()
        || !Set.of("title", "status").containsAll(updates.keySet())) {
      throw new AppException(400, "INVALID_PRODUCT_PATCH", "仅支持修改标题和状态");
    }
    if (updates.containsKey("title")
        && (!(updates.get("title") instanceof String title) || title.length() > 200)) {
      throw new AppException(400, "INVALID_PRODUCT_PATCH", "标题必须为不超过 200 字符的字符串");
    }
    if (updates.containsKey("status")
        && (!(updates.get("status") instanceof String status)
            || !Set.of("ACTIVE", "OFF_SHELF").contains(status))) {
      throw new AppException(400, "INVALID_PRODUCT_PATCH", "商品状态无效");
    }
    findById(id).orElseThrow(JdbcProductGateway::notFound);
    // Write only explicitly supplied fields: a title edit must not restore an earlier status.
    var assignments = new ArrayList<String>();
    var values = new ArrayList<Object>();
    for (String field : List.of("title", "status")) {
      if (updates.containsKey(field)) {
        assignments.add(field + "=?");
        values.add(updates.get(field));
      }
    }
    assignments.add("updated_at=?");
    values.add(Timestamp.from(clock.instant()));
    values.add(id);
    if (jdbc.update(
            "UPDATE product SET " + String.join(",", assignments) + " WHERE id=?", values.toArray())
        == 0) throw notFound();
    return findById(id).orElseThrow(JdbcProductGateway::notFound);
  }

  public void delete(long id) {
    if (jdbc.update("DELETE FROM product WHERE id=?", id) == 0) throw notFound();
  }

  public List<Product> demoProducts() {
    return List.of(1001L, 1002L).stream()
        .map(id -> findById(id).orElse(new Product(id, "", "商品已删除，可单独恢复。", null, "DELETED")))
        .toList();
  }

  public Product restore(long id) {
    if (id != 1001 && id != 1002) throw notFound();
    // Atomic insert-only merge: repeated restores never overwrite an existing product edit.
    jdbc.update(
        "MERGE INTO product p USING (VALUES (?, ?, ?, ?, ?, ?)) s(id,title,description,image_ref,status,updated_at) ON p.id=s.id "
            + "WHEN NOT MATCHED THEN INSERT (id,title,description,image_ref,status,updated_at) VALUES(s.id,s.title,s.description,s.image_ref,s.status,s.updated_at)",
        id,
        id == 1001 ? "轻量随行保温杯" : "",
        id == 1001 ? "本地演示商品，随时随地分享好物。" : "用于演示缺失标题与图片的回退行为。",
        id == 1001 ? "1001" : null,
        "ACTIVE",
        Timestamp.from(clock.instant()));
    return findById(id).orElseThrow(JdbcProductGateway::notFound);
  }

  @Transactional
  public void initializeSeed() {
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM app_meta WHERE meta_key='product_seed_v1'", Integer.class)
        > 0) return;
    if (jdbc.queryForObject("SELECT COUNT(*) FROM product", Integer.class) == 0) insertSeed();
    jdbc.update(
        "INSERT INTO app_meta(meta_key,meta_value) VALUES('product_seed_v1','initialized')");
  }

  @Transactional
  public void resetSeed() {
    jdbc.update("DELETE FROM product");
    insertSeed();
    jdbc.update(
        "MERGE INTO app_meta(meta_key,meta_value) KEY(meta_key) VALUES('product_seed_v1','initialized')");
  }

  private void insertSeed() {
    jdbc.update(
        "INSERT INTO product(id,title,description,image_ref,status,updated_at) VALUES(?,?,?,?,?,?)",
        1001L,
        "轻量随行保温杯",
        "本地演示商品，随时随地分享好物。",
        "1001",
        "ACTIVE",
        Timestamp.from(clock.instant()));
    jdbc.update(
        "INSERT INTO product(id,title,description,image_ref,status,updated_at) VALUES(?,?,?,?,?,?)",
        1002L,
        "",
        "用于演示缺失标题与图片的回退行为。",
        null,
        "ACTIVE",
        Timestamp.from(clock.instant()));
  }

  private static AppException notFound() {
    return new AppException(404, "PRODUCT_NOT_FOUND", "商品不存在");
  }
}
