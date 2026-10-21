package com.data.collection.platform.service.read;

import com.data.collection.platform.entity.read.DatasetReadSelection;
import com.data.collection.platform.entity.read.DatasetRelationInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/** 数据集版本限定的强类型 SELECT；JSON 只作为存储格式，数字/日期/NULL 按真实关系类型恢复。 */
@Service
public class VersionedDatasetSql {
  private final JdbcTemplate jdbcTemplate;

  public VersionedDatasetSql(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }

  /** 绑定已保护的数据集身份与原业务 SELECT；完整参数位于业务参数之前。 */
  public VersionedFactSql.Query bind(DatasetReadSelection selection, String sql, List<?> businessArguments) {
    sql = VersionedReviewViews.bind(jdbcTemplate, selection, sql);
    String body = sql.stripLeading();
    String lower = body.toLowerCase(Locale.ROOT);
    boolean with = lower.startsWith("with ") || lower.startsWith("with\n");
    if ((!lower.startsWith("select ") && !lower.startsWith("select\n") && !with) || lower.startsWith("with recursive")) {
      throw new IllegalArgumentException("数据集读取只接受服务端单条 SELECT");
    }
    Map<String, List<BoundRelation>> relations = new LinkedHashMap<>();
    for (var context : selection.datasets()) {
      List<RelationBinding> schemas = jdbcTemplate.query("""
          select table_key, relation_name, schema_payload::text from read_dataset_tables where publication_id = ? order by table_key
          """, (rs, row) -> new RelationBinding(rs.getString(1), rs.getString(2), columns(rs.getString(3))), context.publicationId());
      if (schemas.isEmpty()) { throw new IllegalStateException("数据集版本缺少完整模式清单"); }
      for (RelationBinding schema : schemas) {
        DatasetRelationInput.requireIdentifier(schema.relationName());
        if (java.util.regex.Pattern.compile("\"?[a-z_][a-z0-9_]*\"?\\s*\\.\\s*\"?"
            + schema.relationName().toLowerCase(Locale.ROOT) + "\\b").matcher(lower).find()) {
          throw new IllegalArgumentException("数据集查询不能以模式限定名绕过版本输入：" + schema.relationName());
        }
        if (java.util.regex.Pattern.compile("\\b" + schema.relationName().toLowerCase(Locale.ROOT) + "\\b").matcher(lower).find()) {
          relations.computeIfAbsent(schema.relationName(), ignored -> new ArrayList<>())
              .add(new BoundRelation(context.epochId(), context.sequence(), schema.tableKey(), schema.columns()));
        }
      }
    }
    for (String owned : List.of("review_records", "review_record_experts", "review_problem_items",
        "review_record_descriptions", "review_record_contents", "review_data_match_mode_edit_links",
        "review_data_match_mode_problem_edit_links", "code_review_external_metrics", "gitlab_mirror_records", "code_review_match_mode_db_settings",
        "code_review_match_mode_records", "review_data_match_mode_reports", "review_data_match_mode_problem_details",
        "review_data_match_mode_descriptions", "review_data_match_mode_contents",
        "review_data_match_mode_report_description_refs", "review_data_match_mode_report_problem_refs")) {
      if (java.util.regex.Pattern.compile("\\b(from|join)\\s+\"?" + owned + "\\b").matcher(lower).find()
          && !relations.containsKey(owned)) {
        throw new IllegalArgumentException("数据集查询未绑定完整来源关系：" + owned);
      }
      if (java.util.regex.Pattern.compile("\"?[a-z_][a-z0-9_]*\"?\\s*\\.\\s*\"?" + owned + "\\b").matcher(lower).find()) {
        throw new IllegalArgumentException("数据集查询不能以模式限定名绕过版本输入：" + owned);
      }
    }
    List<String> ctes = new ArrayList<>();
    List<Object> arguments = new ArrayList<>();
    for (var entry : relations.entrySet()) {
      List<String> inputs = new ArrayList<>();
      for (BoundRelation relation : entry.getValue()) {
        inputs.add("select restored.* from read_dataset_rows stored"
            + " cross join lateral jsonb_to_record(stored.row_payload) as restored(" + relation.columns() + ")"
            + " where stored.epoch_id = ? and stored.table_key = ? and stored.visible_from <= ?"
            + " and (stored.visible_to is null or ? < stored.visible_to)");
        arguments.add(relation.epoch());
        arguments.add(relation.tableKey());
        arguments.add(relation.sequence());
        arguments.add(relation.sequence());
      }
      // 冻结存储的索引顺序不是原业务行顺序；恢复稳定业务 ID，避免排序相同的记录/导出随修订重排。
      boolean hasBusinessId = entry.getValue().stream().allMatch(relation -> relation.columns().contains("\"id\" "));
      ctes.add(entry.getKey() + " as (" + String.join(" union all ", inputs)
          + (hasBusinessId ? " order by id" : "") + ")");
    }
    arguments.addAll(businessArguments);
    String bounded = ctes.isEmpty() ? sql : "with " + String.join(", ", ctes) + (with ? ", " : " ")
        + (with ? body.substring(4).stripLeading() : body);
    if (selection.facts() != null) {
      return VersionedFactSql.bind(selection.facts(), VersionedDefinitionSql.bind(selection, bounded), arguments);
    }
    return new VersionedFactSql.Query(VersionedDefinitionSql.bind(selection, bounded), arguments);
  }

  /** 原筛选、分页与排序共用固定关系，禁止降级到当前业务表或把查询异常改为空集合。 */
  public <T> List<T> query(DatasetReadSelection selection, String sql, RowMapper<T> mapper, Object... arguments) {
    VersionedFactSql.Query bounded = bind(selection, sql, java.util.Arrays.asList(arguments));
    return jdbcTemplate.query(bounded.sql(), mapper, bounded.arguments().toArray());
  }

  /** 读取单列集合，保留原 SQL 的数字/日期比较与排序语义。 */
  public <T> List<T> queryForList(DatasetReadSelection selection, String sql, Class<T> valueType, Object... arguments) {
    VersionedFactSql.Query bounded = bind(selection, sql, java.util.Arrays.asList(arguments));
    return jdbcTemplate.queryForList(bounded.sql(), valueType, bounded.arguments().toArray());
  }

  /** 聚合查询与其列表共用同一读取版本。 */
  public <T> T queryForObject(DatasetReadSelection selection, String sql, Class<T> valueType, Object... arguments) {
    VersionedFactSql.Query bounded = bind(selection, sql, java.util.Arrays.asList(arguments));
    return jdbcTemplate.queryForObject(bounded.sql(), valueType, bounded.arguments().toArray());
  }

  /** 保持既有 ResultSet 聚合器语义，输入仍严格限定到固定发布点。 */
  public <T> T query(DatasetReadSelection selection, String sql, org.springframework.jdbc.core.ResultSetExtractor<T> extractor,
      Object... arguments) {
    VersionedFactSql.Query bounded = bind(selection, sql, java.util.Arrays.asList(arguments));
    return jdbcTemplate.query(bounded.sql(), extractor, bounded.arguments().toArray());
  }

  /** 原单行映射器共用固定输入，未找到时沿用业务层既有异常边界。 */
  public <T> T queryForObject(DatasetReadSelection selection, String sql, RowMapper<T> mapper, Object... arguments) {
    VersionedFactSql.Query bounded = bind(selection, sql, java.util.Arrays.asList(arguments));
    return jdbcTemplate.queryForObject(bounded.sql(), mapper, bounded.arguments().toArray());
  }

  /** Map 聚合结果保持原 JDBC 数值类型，统一限定来源修订。 */
  public java.util.Map<String, Object> queryForMap(DatasetReadSelection selection, String sql, Object... arguments) {
    VersionedFactSql.Query bounded = bind(selection, sql, java.util.Arrays.asList(arguments));
    return jdbcTemplate.queryForMap(bounded.sql(), bounded.arguments().toArray());
  }

  /** 多列聚合结果保持原 JDBC 字段名与类型。 */
  public List<java.util.Map<String, Object>> queryForList(DatasetReadSelection selection, String sql, Object... arguments) {
    VersionedFactSql.Query bounded = bind(selection, sql, java.util.Arrays.asList(arguments));
    return jdbcTemplate.queryForList(bounded.sql(), bounded.arguments().toArray());
  }

  /** 生产冻结与读取共用类型校验，不能先发布一份读取器无法恢复的数据集。 */
  static String columns(String schema) {
    try {
      com.fasterxml.jackson.databind.JsonNode fields = new com.fasterxml.jackson.databind.ObjectMapper().readTree(schema);
      if (!fields.isArray() || fields.isEmpty()) { throw new IllegalStateException("数据集版本缺少字段模式"); }
      List<String> declarations = new ArrayList<>();
      for (var field : fields) {
        String name = field.path("name").asText();
        String type = field.path("type").asText();
        // 类型来自捕获事务的 PostgreSQL format_type；只接受可恢复的内置类型声明。
        if (!type.matches("(?i)(smallint|integer|bigint|real|double precision|boolean|text|bytea|uuid|jsonb?|date|numeric(?:\\(\\d+(?:,\\d+)?\\))?|character(?: varying)?(?:\\(\\d+\\))?|bit(?: varying)?(?:\\(\\d+\\))?|timestamp(?:\\(\\d+\\))?(?: with(?:out)? time zone)?|time(?:\\(\\d+\\))?(?: with(?:out)? time zone)?|interval|inet|cidr|macaddr8?)(?:\\[\\])*")) {
          throw new IllegalStateException("数据集版本含不支持的字段类型：" + type);
        }
        if (name.isBlank() || name.indexOf('\0') >= 0) { throw new IllegalStateException("数据集字段名无效"); }
        declarations.add("\"" + name.replace("\"", "\"\"") + "\" " + type);
      }
      return String.join(", ", declarations);
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalStateException("数据集版本模式无法解析", error);
    }
  }

  private record RelationBinding(String tableKey, String relationName, String columns) {}
  private record BoundRelation(UUID epoch, long sequence, String tableKey, String columns) {}
}
