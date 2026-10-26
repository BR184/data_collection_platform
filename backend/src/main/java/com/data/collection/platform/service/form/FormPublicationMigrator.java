package com.data.collection.platform.service.form;

import com.data.collection.platform.domain.form.FormFieldSpec;
import com.data.collection.platform.domain.form.FormHashSupport;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.domain.form.FormVersionSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 受控结构复制与守恒核验。
 *
 * <p>复制只按稳定字段名映射物理列：改名保值、新增列写 NULL、删除列不复制；类型转换仅接受可核验的
 * 无损/扩展转换。源与目标都按同一投影重读后逐行比较，任一差异即中止并回滚复制事务。
 */
@Service
public class FormPublicationMigrator {

  private static final Pattern TABLE_NAME = Pattern.compile("^form_s[0-9]+_v[0-9]+_(instances|records)$");
  private static final Pattern COLUMN_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");
  private static final List<String> SYSTEM_COLUMNS =
      List.of(
          "id",
          "instance_id",
          "origin_version_id",
          "template_version",
          "created_by",
          "request_key",
          "create_payload_hash",
          "record_version",
          "deleted",
          "legacy_incomplete",
          "legacy_missing_fields",
          "created_at",
          "updated_at");
  private static final List<String> INSTANCE_COLUMNS =
      List.of(
          "id",
          "scope_id",
          "source_config_id",
          "source_instance",
          "gitlab_base_url",
          "project_id",
          "resource_type",
          "resource_id",
          "template_key",
          "created_at",
          "updated_at");

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final FormVersionSchemaService schemaService;
  private final FormVersionStructureVerifier structureVerifier;

  public FormPublicationMigrator(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      FormVersionSchemaService schemaService,
      FormVersionStructureVerifier structureVerifier) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.schemaService = schemaService;
    this.structureVerifier = structureVerifier;
  }

  /**
   * 在调用方事务内复制目标表并核验。
   *
   * @param request 冻结结构、源版本与目标版本
   * @return 选取计数、复制校验和与核验摘要
   */
  public CopyResult copy(CopyRequest request) {
    requireTable(request.instanceTable());
    requireTable(request.recordTable());
    FormTemplateSpec targetSpec = parseSpec(request.frozenFieldSpecJson());
    FormTemplateSpec sourceSpec =
        request.fromVersionId() == null ? null : parseSpec(request.frozenSourceSpecJson());
    FormVersionSchema schema =
        schemaService.generate(
            request.scopeId(),
            request.toVersionId(),
            targetSpec,
            request.fromVersionId() != null);
    if (request.expectedDdlHash() != null && !request.expectedDdlHash().equals(schema.ddlHash())) {
      throw new IllegalStateException("冻结 DDL 与目标规格不一致，停止发布");
    }
    ensureTables(schema);
    jdbcTemplate.update(
        "truncate table " + request.recordTable() + ", " + request.instanceTable());

    if (sourceSpec == null) {
      long records = count(request.recordTable());
      long instances = count(request.instanceTable());
      if (records != 0 || instances != 0) {
        throw new IllegalStateException("初始空发布的目标表必须为空");
      }
      return result(0, 0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    String sourceInstanceTable = request.sourceInstanceTable();
    String sourceRecordTable = request.sourceRecordTable();
    requireTable(sourceInstanceTable);
    requireTable(sourceRecordTable);

    copyInstances(sourceInstanceTable, request.instanceTable());
    Projection projection = recordsProjection(sourceSpec, targetSpec, sourceRecordTable);
    copyRecords(projection, sourceRecordTable, request.recordTable());
    long instances = count(request.instanceTable());
    Checksum checksum =
        verify(projection, sourceRecordTable, request.recordTable(), sourceInstanceTable, request.instanceTable());
    ObjectNode counts = objectMapper.createObjectNode();
    counts.put("records", checksum.records());
    counts.put("instances", instances);
    counts.put("resources", instances);
    return new CopyResult(counts, checksum.checksum());
  }

  private void copyInstances(String source, String target) {
    jdbcTemplate.update(
        ("insert into %s(" + String.join(", ", INSTANCE_COLUMNS) + ")"
                + " select " + String.join(", ", INSTANCE_COLUMNS) + " from %s order by id")
            .formatted(target, source));
  }

  /**
   * 目标列与源投影表达式；复制与核验共用同一投影，避免两边列序或转换不一致。
   */
  private Projection recordsProjection(
      FormTemplateSpec sourceSpec, FormTemplateSpec targetSpec, String sourceTable) {
    Map<String, FormFieldSpec> sourceFields = new LinkedHashMap<>();
    sourceSpec.fields().forEach(field -> sourceFields.put(field.name(), field));
    Set<String> sourceSnapshots = new LinkedHashSet<>(sourceSpec.snapshotColumns());

    List<String> targetColumns = new ArrayList<>(SYSTEM_COLUMNS);
    List<String> sourceExpressions = new ArrayList<>(SYSTEM_COLUMNS);
    List<String> legacyConditions = new ArrayList<>();
    List<String> legacyNames = new ArrayList<>();
    Map<String, String> fieldExpressions = new LinkedHashMap<>();
    for (FormFieldSpec field : targetSpec.fields()) {
      if (field.storage() == null) {
        throw new IllegalStateException("字段缺少物理绑定：" + field.name());
      }
      FormFieldSpec source = sourceFields.get(field.name());
      String column = requireColumn(field.storage().resolvedColumn());
      String idExpression =
          source == null
              ? "null::" + field.storage().resolvedColumnType()
              : convert(
                  source.storage().resolvedColumn(),
                  source.storage().resolvedColumnType(),
                  field.storage().resolvedColumnType(),
                  sourceTable);
      targetColumns.add(column);
      sourceExpressions.add(idExpression);
      fieldExpressions.put(field.name(), idExpression);
      String nameExpression = null;
      if (field.storage().nameColumn() != null) {
        String nameColumn = requireColumn(field.storage().nameColumn());
        nameExpression =
            source == null || source.storage().nameColumn() == null
                ? "null::" + field.storage().resolvedNameColumnType()
                : convert(
                    source.storage().nameColumn(),
                    source.storage().resolvedNameColumnType(),
                    field.storage().resolvedNameColumnType(),
                    sourceTable);
        targetColumns.add(nameColumn);
        sourceExpressions.add(nameExpression);
      }
      if (field.required()) {
        legacyConditions.add(
            nameExpression == null
                ? "(" + idExpression + ") is null"
                : "((" + idExpression + ") is null or (" + nameExpression + ") is null)");
        legacyNames.add(field.name());
      }
    }
    for (String snapshot : targetSpec.snapshotColumns()) {
      requireColumn(snapshot);
      if (!sourceSnapshots.contains(snapshot)) {
        throw new IllegalStateException("新增条件快照列需要独立迁移契约：" + snapshot);
      }
      targetColumns.add(snapshot);
      sourceExpressions.add(snapshot);
    }
    appendConditionLegacy(targetSpec, fieldExpressions, legacyConditions, legacyNames);
    applyLegacyIncomplete(targetColumns, sourceExpressions, legacyConditions, legacyNames);
    return new Projection(List.copyOf(targetColumns), List.copyOf(sourceExpressions));
  }

  /**
   * 历史行不满足目标条件时标记待补齐，保留原值而不是让迁移失败。
   *
   * <p>条件变更可能收紧既有组合；迁移目标 DDL 以 {@code legacy_incomplete} 豁免，
   * 编辑保存后按新条件重新校验并清除标记。快照条件在其模板内保持不变，不走此豁免。
   */
  private void appendConditionLegacy(
      FormTemplateSpec targetSpec,
      Map<String, String> fieldExpressions,
      List<String> legacyConditions,
      List<String> legacyNames) {
    for (JsonNode condition : targetSpec.conditions()) {
      String type = condition.path("type").asText();
      if ("mutuallyExclusive".equals(type)) {
        String field = condition.path("field").asText();
        String expression = fieldExpressions.get(field);
        if (expression == null) {
          continue;
        }
        List<String> conflicts = new ArrayList<>();
        condition.path("withAnyOf").forEach(node -> conflicts.add(node.asText()));
        if (conflicts.isEmpty()) {
          continue;
        }
        String whenExpression =
            "coalesce(" + literal(condition.path("whenContains").asText())
                + " = any((" + expression + ")), false)";
        String conflictExpression =
            conflicts.stream()
                .map(
                    value ->
                        "coalesce("
                            + literal(value)
                            + " = any(("
                            + expression
                            + ")), false)")
                .reduce((left, right) -> left + " or " + right)
                .orElse("false");
        legacyConditions.add("(" + whenExpression + " and (" + conflictExpression + "))");
        legacyNames.add(field);
        continue;
      }
      if (!"conditionalRequired".equals(type)) {
        continue;
      }
      String snapshotColumn = condition.path("snapshotColumn").asText(null);
      if (snapshotColumn != null) {
        continue;
      }
      FormFieldSpec target = targetSpec.fieldByName(condition.path("target").asText()).orElse(null);
      if (target == null) {
        continue;
      }
      String targetExpression = fieldExpressions.get(target.name());
      if (targetExpression == null) {
        continue;
      }
      JsonNode requiredWhen = condition.path("requiredWhen");
      String requiredExpression = sourceConditionExpression(requiredWhen, fieldExpressions);
      if (requiredExpression != null) {
        legacyConditions.add(
            "(coalesce("
                + requiredExpression
                + ", false) and "
                + emptyExpression(target, targetExpression)
                + ")");
        legacyNames.add(target.name());
      }
      JsonNode clearWhen = condition.path("clearWhen");
      String clearExpression = sourceConditionExpression(clearWhen, fieldExpressions);
      if (clearExpression != null) {
        legacyConditions.add(
            "(coalesce("
                + clearExpression
                + ", false) and ("
                + targetExpression
                + ") is not null)");
        legacyNames.add(target.name());
      }
    }
  }

  private String sourceConditionExpression(
      JsonNode condition, Map<String, String> fieldExpressions) {
    if (condition == null || condition.isMissingNode() || !condition.isObject()) {
      return null;
    }
    if (condition.hasNonNull("resourceSeverityLevel")) {
      return null;
    }
    String expression = fieldExpressions.get(condition.path("field").asText());
    if (expression == null) {
      return null;
    }
    if (condition.hasNonNull("contains")) {
      return literal(condition.path("contains").asText()) + " = any((" + expression + "))";
    }
    if (condition.hasNonNull("notContains")) {
      return "not ("
          + literal(condition.path("notContains").asText())
          + " = any(("
          + expression
          + ")))";
    }
    if (condition.has("equals")) {
      JsonNode value = condition.path("equals");
      if (value.isBoolean()) {
        return value.asBoolean() ? "(" + expression + ")" : "not (" + expression + ")";
      }
      return "(" + expression + ") = " + literal(value.asText());
    }
    return null;
  }

  private String emptyExpression(FormFieldSpec field, String expression) {
    String columnType = field.storage() == null ? null : field.storage().resolvedColumnType();
    if ("enum-array".equals(field.type()) || (columnType != null && columnType.endsWith("[]"))) {
      return "coalesce(cardinality((" + expression + ")), 0) = 0";
    }
    boolean textual =
        "string".equals(field.type())
            || "enum".equals(field.type())
            || (columnType != null
                && (columnType.startsWith("varchar") || "text".equals(columnType)));
    return textual
        ? "coalesce(btrim((" + expression + ")), '') = ''"
        : "(" + expression + ") is null";
  }

  private String literal(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  /**
   * 新增/收紧必填字段时，历史 NULL 行标记 {@code legacy_incomplete} 并记录缺失稳定键。
   *
   * <p>缺失条件按源投影表达式判断（新增字段源侧恒为 NULL），插入时直接写入目标列，
   * 因此逐行守恒比较覆盖这两个系统列且条件始终在源表上可求值。
   */
  private void applyLegacyIncomplete(
      List<String> columns,
      List<String> expressions,
      List<String> conditions,
      List<String> names) {
    if (conditions.isEmpty()) {
      return;
    }
    int incompleteIndex = columns.indexOf("legacy_incomplete");
    int missingIndex = columns.indexOf("legacy_missing_fields");
    if (incompleteIndex < 0 || missingIndex < 0) {
      throw new IllegalStateException("系统列缺失，无法记录历史缺失字段");
    }
    List<String> additions = new ArrayList<>();
    for (int index = 0; index < conditions.size(); index++) {
      additions.add("case when " + conditions.get(index) + " then '" + names.get(index) + "' end");
    }
    expressions.set(
        incompleteIndex,
        "legacy_incomplete or " + String.join(" or ", conditions));
    expressions.set(
        missingIndex,
        "coalesce((select array_agg(distinct f order by f)"
            + " from unnest(legacy_missing_fields || array_remove(array["
            + String.join(", ", additions)
            + "], null)) f), '{}'::text[])");
  }

  private void copyRecords(Projection projection, String sourceTable, String targetTable) {
    String sql =
        ("insert into %s(" + String.join(", ", projection.columns()) + ")"
                + " select " + String.join(", ", projection.expressions()) + " from %s order by id")
            .formatted(targetTable, sourceTable);
    jdbcTemplate.update(sql);
  }

  private Checksum verify(
      Projection projection,
      String sourceRecords,
      String targetRecords,
      String sourceInstances,
      String targetInstances) {
    Map<Long, String> expected = projectedRows(sourceRecords, projection);
    Map<Long, String> actual = plainRows(targetRecords, projection.columns());
    compare("记录", expected, actual);
    Map<Long, String> expectedInstances = instanceRows(sourceInstances);
    Map<Long, String> actualInstances = instanceRows(targetInstances);
    compare("实例", expectedInstances, actualInstances);
    String sourceChecksum = FormHashSupport.sha256Hex(String.join("\n", expected.values()));
    String targetChecksum = FormHashSupport.sha256Hex(String.join("\n", actual.values()));
    if (!sourceChecksum.equals(targetChecksum)) {
      throw new IllegalStateException("复制校验和不一致，停止发布");
    }
    ObjectNode checksum = objectMapper.createObjectNode();
    checksum.put("sourceSha256", sourceChecksum);
    checksum.put("targetSha256", targetChecksum);
    checksum.put("records", expected.size());
    return new Checksum(expected.size(), checksum);
  }

  private Map<Long, String> projectedRows(String table, Projection projection) {
    List<String> select = new ArrayList<>();
    for (int index = 0; index < projection.columns().size(); index++) {
      select.add(projection.expressions().get(index) + " as " + projection.columns().get(index));
    }
    List<Map<String, Object>> raw =
        jdbcTemplate.queryForList(
            "select " + String.join(", ", select) + " from " + table + " order by id");
    return canonicalRows(projection.columns(), raw);
  }

  private Map<Long, String> plainRows(String table, List<String> columns) {
    List<Map<String, Object>> raw =
        jdbcTemplate.queryForList(
            "select " + String.join(", ", columns) + " from " + table + " order by id");
    return canonicalRows(columns, raw);
  }

  private Map<Long, String> canonicalRows(
      List<String> columns, List<Map<String, Object>> raw) {
    Map<Long, String> lines = new LinkedHashMap<>();
    for (Map<String, Object> row : raw) {
      long id = ((Number) row.get("id")).longValue();
      lines.put(id, canonicalRow(columns, row));
    }
    return lines;
  }

  private Map<Long, String> instanceRows(String table) {
    List<Map<String, Object>> raw =
        jdbcTemplate.queryForList(
            "select " + String.join(", ", INSTANCE_COLUMNS) + " from " + table + " order by id");
    Map<Long, String> lines = new LinkedHashMap<>();
    for (Map<String, Object> row : raw) {
      long id = ((Number) row.get("id")).longValue();
      lines.put(id, canonicalRow(INSTANCE_COLUMNS, row));
    }
    return lines;
  }

  private String canonicalRow(List<String> columns, Map<String, Object> row) {
    List<String> values = new ArrayList<>(columns.size());
    for (String column : columns) {
      values.add(canonical(row.get(column)));
    }
    return String.join("\u0001", values);
  }

  private void compare(String label, Map<Long, String> expected, Map<Long, String> actual) {
    if (!expected.keySet().equals(actual.keySet())) {
      Set<Long> missing = new java.util.TreeSet<>(expected.keySet());
      missing.removeAll(actual.keySet());
      Set<Long> extra = new java.util.TreeSet<>(actual.keySet());
      extra.removeAll(expected.keySet());
      throw new IllegalStateException(
          label + "ID 集合不守恒：缺 " + limit(missing) + "，多 " + limit(extra));
    }
    for (Map.Entry<Long, String> entry : expected.entrySet()) {
      if (!entry.getValue().equals(actual.get(entry.getKey()))) {
        throw new IllegalStateException(label + "逐行核验失败：id=" + entry.getKey());
      }
    }
  }

  private List<Long> limit(Set<Long> values) {
    return values.stream().limit(10).toList();
  }

  private String convert(String column, String from, String to, String sourceTable) {
    requireColumn(column);
    if (Objects.equals(from, to)) {
      return column;
    }
    if (from == null || to == null) {
      throw new IllegalStateException("字段类型缺失，无法迁移列 " + column);
    }
    if ("integer".equals(from) && ("text".equals(to) || to.startsWith("varchar("))) {
      requireLength(column, to, sourceTable, "(" + column + ")::text");
      return "(" + column + ")::" + to;
    }
    if ("text".equals(from) && to.startsWith("varchar(")) {
      requireLength(column, to, sourceTable, column);
      return "(" + column + ")::" + to;
    }
    if (from.startsWith("varchar(") && "text".equals(to)) {
      return column;
    }
    if (from.startsWith("varchar(") && to.startsWith("varchar(")) {
      int fromLength = varcharLength(from);
      int toLength = varcharLength(to);
      if (toLength >= fromLength) {
        return column;
      }
      requireLength(column, to, sourceTable, column);
      return "(" + column + ")::" + to;
    }
    throw new IllegalStateException("不可自动迁移的类型转换 " + from + " -> " + to + "（列 " + column + "）");
  }

  private void requireLength(String column, String targetType, String sourceTable, String expression) {
    Integer max = varcharLength(targetType);
    if (max == null) {
      return;
    }
    Long tooLong =
        jdbcTemplate.queryForObject(
            "select count(*) from " + sourceTable + " where " + expression + " is not null"
                + " and length(" + expression + ") > " + max,
            Long.class);
    if (tooLong != null && tooLong > 0) {
      throw new IllegalStateException(
          "列 " + column + " 有 " + tooLong + " 行超过目标 varchar(" + max + ")，停止发布");
    }
  }

  private int varcharLength(String type) {
    try {
      return Integer.parseInt(type.substring("varchar(".length(), type.length() - 1));
    } catch (RuntimeException error) {
      throw new IllegalStateException("非法 varchar 类型：" + type);
    }
  }

  private void ensureTables(FormVersionSchema schema) {
    String instance =
        jdbcTemplate.queryForObject("select to_regclass(?)", String.class, schema.instanceTable());
    String record =
        jdbcTemplate.queryForObject("select to_regclass(?)", String.class, schema.recordTable());
    if (instance == null && record == null) {
      schema.statements().forEach(jdbcTemplate::execute);
      return;
    }
    if (instance == null || record == null) {
      throw new IllegalStateException("目标版本表不完整，停止发布");
    }
    structureVerifier.verify(schema);
  }

  private FormTemplateSpec parseSpec(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalStateException("冻结结构为空");
    }
    try {
      return objectMapper.readValue(raw, FormTemplateSpec.class);
    } catch (Exception error) {
      throw new IllegalStateException("冻结结构无法解析", error);
    }
  }

  private String requireColumn(String column) {
    if (column == null || !COLUMN_NAME.matcher(column).matches()) {
      throw new IllegalStateException("非法列名：" + column);
    }
    return column;
  }

  private String requireTable(String table) {
    if (table == null || !TABLE_NAME.matcher(table).matches()) {
      throw new IllegalStateException("非法版本表名：" + table);
    }
    return table;
  }

  private long count(String table) {
    Long value = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
    return value == null ? 0 : value;
  }

  private CopyResult result(long records, long instances, String checksum) {
    ObjectNode counts = objectMapper.createObjectNode();
    counts.put("records", records);
    counts.put("instances", instances);
    counts.put("resources", instances);
    ObjectNode value = objectMapper.createObjectNode();
    value.put("sourceSha256", checksum);
    value.put("targetSha256", checksum);
    value.put("records", records);
    return new CopyResult(counts, value);
  }

  private String canonical(Object value) {
    if (value == null) {
      return "<null>";
    }
    if (value instanceof Array array) {
      try {
        Object raw = array.getArray();
        if (raw instanceof Object[] items) {
          List<String> values = new ArrayList<>(items.length);
          for (Object item : items) {
            values.add(item == null ? "<null>" : String.valueOf(item));
          }
          return "[" + String.join(",", values) + "]";
        }
        return String.valueOf(raw);
      } catch (java.sql.SQLException error) {
        throw new IllegalStateException(error);
      }
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime().toString();
    }
    if (value instanceof LocalDateTime dateTime) {
      return dateTime.toString();
    }
    if (value instanceof Boolean bool) {
      return bool ? "t" : "f";
    }
    if (value instanceof byte[] bytes) {
      return java.util.HexFormat.of().formatHex(bytes);
    }
    return String.valueOf(value);
  }

  /** 复制请求；源版本为空表示 INITIAL_EMPTY。 */
  public record CopyRequest(
      long scopeId,
      long toVersionId,
      Long fromVersionId,
      String sourceInstanceTable,
      String sourceRecordTable,
      String frozenFieldSpecJson,
      String frozenSourceSpecJson,
      String expectedDdlHash,
      String instanceTable,
      String recordTable) {}

  /** 复制结果；counts 与 checksum 写入任务与 attempt 证据。 */
  public record CopyResult(JsonNode counts, JsonNode checksum) {}

  private record Checksum(long records, JsonNode checksum) {}

  private record Projection(List<String> columns, List<String> expressions) {}
}
