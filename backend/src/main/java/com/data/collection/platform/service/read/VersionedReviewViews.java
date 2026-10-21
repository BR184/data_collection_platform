package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.DatasetReadSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;

/** 物理 VIEW 不继承调用者的 CTE；将既有评审视图展开后才能限定其全部关系输入。 */
final class VersionedReviewViews {
  private VersionedReviewViews() {}

  static String bind(JdbcTemplate jdbc, DatasetReadSelection selection, String sql) {
    String lower = sql.toLowerCase(Locale.ROOT);
    List<String> used = List.of("review_visible_records", "review_visible_problem_items").stream()
        .filter(name -> java.util.regex.Pattern.compile("\\b" + name + "\\b").matcher(lower).find()).toList();
    if (used.isEmpty()) { return sql; }
    Boolean compatibility = new VersionedDatasetSql(jdbc).queryForObject(selection,
        "select enabled and review_data_read_mode = 'compatibility' from code_review_match_mode_db_settings where id = 1", Boolean.class);
    List<String> views = new ArrayList<>();
    for (String name : used) {
      String definition = jdbc.queryForObject("select pg_get_viewdef(?::regclass, true)", String.class, name).strip();
      if (definition.endsWith(";")) { definition = definition.substring(0, definition.length() - 1); }
      // pg_get_viewdef 可能带本地 public 限定；它属于服务端已审核 DDL，去掉限定后由固定关系 CTE 接管。
      definition = definition.replaceAll("(?i)\\bpublic\\.", "");
      if (!Boolean.TRUE.equals(compatibility)) {
        int union = definition.toLowerCase(Locale.ROOT).indexOf("union all");
        if (union < 0) { throw new IllegalStateException("评审统一视图缺少已登记的正式/兼容边界"); }
        // 正式模式没有兼容输入；直接使用该视图的正式投影，不能用尚未认证的原始兼容表补齐。
        definition = formalProjection(jdbc, name);
      }
      views.add(name + " as (" + definition + ")");
    }
    String body = sql.stripLeading();
    boolean with = body.toLowerCase(Locale.ROOT).startsWith("with ") || body.toLowerCase(Locale.ROOT).startsWith("with\n");
    return "with " + String.join(", ", views) + (with ? ", " : " ") + (with ? body.substring(4).stripLeading() : body);
  }

  private static String formalProjection(JdbcTemplate jdbc, String view) {
    List<String> columns = jdbc.queryForList("select attname from pg_attribute where attrelid = ?::regclass"
        + " and attnum > 0 and not attisdropped order by attnum", String.class, view);
    columns.forEach(com.data.collection.platform.entity.read.DatasetRelationInput::requireIdentifier);
    String table = view.equals("review_visible_records") ? "review_records" : "review_problem_items";
    return "select " + String.join(", ", columns) + " from " + table;
  }
}
