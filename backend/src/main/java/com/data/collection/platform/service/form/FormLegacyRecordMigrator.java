package com.data.collection.platform.service.form;

import com.data.collection.platform.domain.form.FormFieldSpec;
import com.data.collection.platform.domain.form.FormHashSupport;
import com.data.collection.platform.domain.form.FormSourceUrlSupport;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 旧 {@code collect_form_records} 选集的受控复制。
 *
 * <p>只负责取数与映射，不拥有发布阶段、租约、栅栏或指针：调用方在通用发布任务内提供冻结
 * {@code field_spec}，本类按稳定列语义把旧值复制到目标版本并逐行核验。管理员已确认的规格是
 * 唯一目标结构，禁止回退到种子规格。
 */
@Service
public class FormLegacyRecordMigrator {

  static final String LEGACY_TABLE = "collect_form_records";

  private static final Pattern TABLE_NAME = Pattern.compile("^form_s[0-9]+_v[0-9]+_(instances|records)$");


  /** 目标物理列到旧表列的唯一语义映射；同名列不在此表时按新增字段处理。 */
  private static final Map<String, String> LEGACY_COLUMNS =
      Map.of(
          "reviewer_name", "reviewer",
          "review_duration_minutes", "review_duration_minutes",
          "specification_count", "specification_score",
          "logic_count", "logic_score",
          "performance_count", "performance_score",
          "design_count", "design_score",
          "other_count", "other_score",
          "supplement_note", "remark");

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final FormVersionSchemaService schemaService;
  private final FormVersionStructureVerifier structureVerifier;
  private final FormScopeValidationService scopeValidationService;

  public FormLegacyRecordMigrator(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      FormVersionSchemaService schemaService,
      FormVersionStructureVerifier structureVerifier,
      FormScopeValidationService scopeValidationService) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.schemaService = schemaService;
    this.structureVerifier = structureVerifier;
    this.scopeValidationService = scopeValidationService;
  }

  /**
   * 当前绑定范围是否存在旧表选集 S，用于首次发布路径判定。
   *
   * <p>只统计本来源规范地址与项目集合内的记录；其它范围或项目的旧行不触发
   * INITIAL_MIGRATION，本范围 S 为空时仍走 INITIAL_EMPTY。
   */
  public boolean hasScopedSelection(
      String templateKey, String canonicalUrl, List<Long> projectIds) {
    if (!"code_review".equals(templateKey)
        || canonicalUrl == null
        || projectIds == null
        || projectIds.isEmpty()
        || jdbcTemplate.queryForObject("select to_regclass(?)", String.class, LEGACY_TABLE) == null) {
      return false;
    }
    String projects =
        projectIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
    List<String> urls =
        jdbcTemplate.queryForList(
            "select gitlab_base_url from collect_form_records"
                + " where template_code = 'code_review' and resource_type = 'merge_request'"
                + " and project_id in (" + projects + ")",
            String.class);
    String expected = FormSourceUrlSupport.canonicalize(canonicalUrl);
    for (String raw : urls) {
      try {
        if (expected.equals(FormSourceUrlSupport.canonicalize(raw))) {
          return true;
        }
      } catch (IllegalArgumentException error) {
        throw new IllegalStateException("旧记录 GitLab 基址非法: " + raw, error);
      }
    }
    return false;
  }

  /**
   * 在复制事务内读取当前绑定范围的完整 S。
   *
   * <p>同一记录的重复归属或未覆盖行会阻止迁移，与既有覆盖预检一致；旧表写出不在本平台停写
   * 栅栏内，因此调用方必须已确认旧后端停写窗口后再执行发布。
   */
  public Selection select(
      String templateKey,
      String canonicalUrl,
      List<Long> projectIds,
      String bindingHash) {
    List<FormScopeValidationService.CoverageScope> coverageScopes =
        scopeValidationService.listCoverageScopes(templateKey, "merge_request");
    List<LegacyRow> all =
        jdbcTemplate.query(
            """
            select id, gitlab_base_url, project_id, request_iid, resource_type, resource_id,
                   template_code, reviewer, review_duration_minutes, specification_score,
                   logic_score, performance_score, design_score, other_score, remark, deleted,
                   created_at, updated_at
              from collect_form_records
             where template_code = 'code_review' and resource_type = 'merge_request'
             order by id
            """,
            (rs, rowNum) -> {
              Long requestIid = rs.getObject("request_iid", Long.class);
              long id = rs.getLong("id");
              String rawResourceId = rs.getString("resource_id");
              long resourceId;
              try {
                resourceId = Long.parseLong(rawResourceId);
              } catch (NumberFormatException error) {
                throw new IllegalStateException(
                    "旧记录 " + id + " 的 resource_id 不是正整数: " + rawResourceId, error);
              }
              return new LegacyRow(
                  id,
                  rs.getString("gitlab_base_url"),
                  rs.getLong("project_id"),
                  requestIid,
                  resourceId,
                  rs.getString("reviewer"),
                  rs.getInt("review_duration_minutes"),
                  rs.getInt("specification_score"),
                  rs.getInt("logic_score"),
                  rs.getInt("performance_score"),
                  rs.getInt("design_score"),
                  rs.getInt("other_score"),
                  rs.getString("remark"),
                  rs.getBoolean("deleted"),
                  rs.getTimestamp("created_at").toLocalDateTime(),
                  rs.getTimestamp("updated_at").toLocalDateTime());
            });
    Set<Long> projects = Set.copyOf(projectIds);
    List<LegacyRow> selected = new ArrayList<>();
    List<Long> uncovered = new ArrayList<>();
    List<Long> duplicateOwnership = new ArrayList<>();
    for (LegacyRow row : all) {
      String rowUrl = FormSourceUrlSupport.canonicalize(row.gitlabBaseUrl());
      boolean belongsToCurrent = rowUrl.equals(canonicalUrl) && projects.contains(row.projectId());
      long owners =
          coverageScopes.stream()
              .filter(scope -> scope.covers(rowUrl, row.projectId()))
              .mapToLong(FormScopeValidationService.CoverageScope::scopeId)
              .distinct()
              .count();
      boolean candidateAlreadyRegistered =
          coverageScopes.stream()
              .anyMatch(
                  scope ->
                      scope.bindingHash().equals(bindingHash)
                          && scope.covers(rowUrl, row.projectId()));
      if (belongsToCurrent && !candidateAlreadyRegistered) {
        owners++;
      }
      if (owners == 0) {
        uncovered.add(row.id());
      } else if (owners > 1) {
        duplicateOwnership.add(row.id());
      } else if (belongsToCurrent) {
        selected.add(validate(row));
      }
    }
    if (!duplicateOwnership.isEmpty()) {
      throw new IllegalStateException(
          "旧 code_review 记录存在重复归属，停止迁移；重复归属记录数="
              + duplicateOwnership.size()
              + "，前几条="
              + duplicateOwnership.stream().limit(10).toList());
    }
    if (!uncovered.isEmpty()) {
      throw new IllegalStateException(
          "旧 code_review 记录未被已登记范围覆盖，停止迁移；未覆盖记录数="
              + uncovered.size()
              + "，前几条="
              + uncovered.stream().limit(10).toList());
    }
    LinkedHashMap<InstanceKey, LegacyTimes> times = new LinkedHashMap<>();
    for (LegacyRow row : selected) {
      InstanceKey key = new InstanceKey(row.projectId(), row.resourceId());
      times.merge(
          key,
          new LegacyTimes(row.createdAt(), row.updatedAt()),
          (left, right) ->
              new LegacyTimes(
                  left.createdAt().isBefore(right.createdAt()) ? left.createdAt() : right.createdAt(),
                  left.updatedAt().isAfter(right.updatedAt()) ? left.updatedAt() : right.updatedAt()));
    }
    return new Selection(List.copyOf(selected), Map.copyOf(times));
  }

  /**
   * 在调用方事务内建表、复制并核验。
   *
   * @param request 冻结规格、目标版本与范围绑定
   * @return 记录/实例计数与源目标校验和
   */
  public CopyResult copy(CopyRequest request) {
    requireTable(request.instanceTable());
    requireTable(request.recordTable());
    FormTemplateSpec spec = parseSpec(request.frozenFieldSpecJson());
    FormVersionSchema schema =
        schemaService.generate(request.scopeId(), request.toVersionId(), spec, true);
    if (request.expectedDdlHash() != null && !request.expectedDdlHash().equals(schema.ddlHash())) {
      throw new IllegalStateException("冻结 DDL 与目标规格不一致，停止发布");
    }
    ensureTables(schema);
    Selection selection =
        select(request.templateKey(), request.canonicalUrl(), request.projectIds(), request.bindingHash());
    if (selection.rows().isEmpty()) {
      throw new IllegalStateException(
          "旧 code_review 选取集 S 为空；空平台应走 INITIAL_EMPTY，不得执行 INITIAL_MIGRATION");
    }
    jdbcTemplate.update(
        "truncate table " + request.recordTable() + ", " + request.instanceTable());
    Map<InstanceKey, Long> instanceIds = insertInstances(request, selection);
    LegacyProjection projection = legacyProjection(spec, request.instanceTable());
    Long[] selectedIds = selection.rows().stream().map(LegacyRow::id).toArray(Long[]::new);
    copyRecords(projection, request.recordTable(), request.legacyVersionId(), selectedIds);
    Checksum checksum =
        verify(projection, request, selection, request.legacyVersionId(), selectedIds);
    ObjectNode counts = objectMapper.createObjectNode();
    counts.put("records", selection.rows().size());
    counts.put("instances", instanceIds.size());
    counts.put("resources", instanceIds.size());
    counts.put("sourceRows", selection.rows().size());
    counts.put("fieldMismatches", 0);
    return new CopyResult(counts, checksum.checksum());
  }

  private LegacyRow validate(LegacyRow row) {
    if (row.resourceId() <= 0) {
      throw new IllegalStateException("旧记录 " + row.id() + " 的 resource_id 必须为正整数");
    }
    if (row.requestIid() != null && row.requestIid() != row.resourceId()) {
      throw new IllegalStateException("旧记录 " + row.id() + " 的 request_iid 与 resource_id 不一致");
    }
    if (row.reviewDurationMinutes() < 0
        || row.specificationScore() < 0
        || row.logicScore() < 0
        || row.performanceScore() < 0
        || row.designScore() < 0
        || row.otherScore() < 0) {
      throw new IllegalStateException("旧记录 " + row.id() + " 含负数值");
    }
    return row;
  }

  private Map<InstanceKey, Long> insertInstances(CopyRequest request, Selection selection) {
    Long[] ids = selection.rows().stream().map(LegacyRow::id).toArray(Long[]::new);
    List<InstanceRow> rows =
        jdbcTemplate.query(
            """
            insert into %s(
                scope_id, source_config_id, source_instance, gitlab_base_url, project_id,
                resource_type, resource_id, template_key, created_at, updated_at)
            select ?, ?, ?, ?, project_id, 'merge_request', resource_id::bigint, ?,
                   min(created_at), max(updated_at)
              from collect_form_records
             where id = any(?) and template_code = 'code_review'
               and resource_type = 'merge_request'
             group by project_id, resource_id
            returning id, project_id, resource_id
            """
                .formatted(request.instanceTable()),
            ps -> {
              ps.setLong(1, request.scopeId());
              ps.setLong(2, request.sourceConfigId());
              ps.setString(3, request.sourceInstance());
              ps.setString(4, request.canonicalUrl());
              ps.setString(5, request.templateKey());
              ps.setArray(6, ps.getConnection().createArrayOf("bigint", ids));
            },
            (rs, rowNum) ->
                new InstanceRow(
                    rs.getLong("id"), rs.getLong("project_id"), rs.getLong("resource_id")));
    Map<InstanceKey, Long> result = new LinkedHashMap<>();
    for (InstanceRow row : rows) {
      result.put(new InstanceKey(row.projectId(), row.resourceId()), row.id());
    }
    if (result.size() != selection.instances().size()) {
      throw new IllegalStateException(
          "目标实例数不守恒: expected="
              + selection.instances().size()
              + ", actual="
              + result.size());
    }
    return result;
  }

  /**
   * 目标列与旧表表达的投影；复制、legacy标记和核验共用，确保审计口径一致。
   */
  private LegacyProjection legacyProjection(FormTemplateSpec spec, String instanceTable) {
    List<String> columns = new ArrayList<>();
    List<String> expressions = new ArrayList<>();
    List<String> missingConditions = new ArrayList<>();
    List<String> missingNames = new ArrayList<>();
    columns.add("id");
    expressions.add("l.id");
    columns.add("instance_id");
    expressions.add("i.id");
    columns.add("origin_version_id");
    expressions.add("?::bigint");
    columns.add("template_version");
    expressions.add("null");
    columns.add("created_by");
    expressions.add("null");
    columns.add("request_key");
    expressions.add("null");
    columns.add("create_payload_hash");
    expressions.add("null");
    columns.add("record_version");
    expressions.add("1");
    columns.add("deleted");
    expressions.add("l.deleted");
    columns.add("legacy_incomplete");
    expressions.add("false");
    columns.add("legacy_missing_fields");
    expressions.add("'{}'::text[]");
    columns.add("created_at");
    expressions.add("l.created_at");
    columns.add("updated_at");
    expressions.add("l.updated_at");
    for (FormFieldSpec field : spec.fields()) {
      if (field.storage() == null) {
        throw new IllegalStateException("字段缺少物理绑定：" + field.name());
      }
      String idColumn = requireColumn(field.storage().resolvedColumn());
      String idExpression =
          legacyExpression(idColumn, field.storage().resolvedColumnType());
      if (idExpression == null) {
        idExpression = "null::" + field.storage().resolvedColumnType();
      }
      columns.add(idColumn);
      expressions.add(idExpression);
      String nameExpression = null;
      String nameColumn = null;
      if (field.storage().nameColumn() != null) {
        nameColumn = requireColumn(field.storage().nameColumn());
        nameExpression =
            legacyExpression(nameColumn, field.storage().resolvedNameColumnType());
        if (nameExpression == null) {
          nameExpression = "null::" + field.storage().resolvedNameColumnType();
        }
        columns.add(nameColumn);
        expressions.add(nameExpression);
      }
      if (field.required()) {
        boolean textual = isTextual(field.storage().resolvedColumnType());
        missingConditions.add(nullCondition(idExpression, textual));
        missingNames.add(field.name());
        if (field.storage().nameColumn() != null && nameExpression != null) {
          missingConditions.add(nullCondition(nameExpression, true));
          missingNames.add(nameColumn);
        }
      }
    }
    for (String snapshot : spec.snapshotColumns()) {
      requireColumn(snapshot);
      columns.add(snapshot);
      expressions.add("null::boolean");
    }
    int incompleteIndex = columns.indexOf("legacy_incomplete");
    int missingIndex = columns.indexOf("legacy_missing_fields");
    if (missingConditions.isEmpty()) {
      expressions.set(incompleteIndex, "false");
      expressions.set(missingIndex, "'{}'::text[]");
    } else {
      expressions.set(
          incompleteIndex, String.join(" or ", missingConditions));
      List<String> additions = new ArrayList<>();
      for (int index = 0; index < missingConditions.size(); index++) {
        additions.add(
            "case when " + missingConditions.get(index) + " then '" + missingNames.get(index) + "' end");
      }
      expressions.set(
          missingIndex,
          "coalesce((select array_agg(distinct f order by f)"
              + " from unnest(array_remove(array["
              + String.join(", ", additions)
              + "], null)) f), '{}'::text[])");
    }
    return new LegacyProjection(
        List.copyOf(columns), List.copyOf(expressions), instanceTable);
  }

  private String nullCondition(String expression, boolean textual) {
    return textual
        ? "(" + expression + " is null or btrim(" + expression + ") = '')"
        : expression + " is null";
  }

  /** 旧列表达到目标列的表达式；目标列没有旧来源语义时为 null，按新增字段处理。 */
  private String legacyExpression(String targetColumn, String targetType) {
    String source = LEGACY_COLUMNS.get(targetColumn);
    if (source == null) {
      return null;
    }
    if ("reviewer_name".equals(targetColumn)
        || "supplement_note".equals(targetColumn)) {
      if (targetType.startsWith("varchar(")) {
        requireLength("l." + source, targetType, LEGACY_TABLE, "l." + source);
        return "(l." + source + ")::" + targetType;
      }
      return "l." + source;
    }
    if ("integer".equals(targetType)) {
      return "l." + source;
    }
    if (targetType.startsWith("varchar(")) {
      requireLength("l." + source, targetType, LEGACY_TABLE, "l." + source + "::text");
      return "(l." + source + ")::text";
    }
    if ("text".equals(targetType)) {
      return "(l." + source + ")::text";
    }
    throw new IllegalStateException(
        "旧表列 " + source + " 不支持目标类型 " + targetType);
  }

  private void copyRecords(
      LegacyProjection projection, String targetTable, long legacyVersionId, Long[] selectedIds) {
    String sql =
        ("insert into %s(" + String.join(", ", projection.columns()) + ")"
                + " select " + String.join(", ", projection.expressions())
                + " from " + LEGACY_TABLE + " l"
                + " join " + projection.instanceTable()
                + " i on i.project_id = l.project_id and i.resource_id = l.resource_id::bigint"
                + " where l.id = any(?) and l.template_code = 'code_review'"
                + " and l.resource_type = 'merge_request'"
                + " order by l.id")
            .formatted(targetTable);
    jdbcTemplate.update(
        sql,
        ps -> {
          ps.setLong(1, legacyVersionId);
          ps.setArray(2, ps.getConnection().createArrayOf("bigint", selectedIds));
        });
  }

  private Checksum verify(
      LegacyProjection projection,
      CopyRequest request,
      Selection selection,
      long legacyVersionId,
      Long[] selectedIds) {
    String expectedSql =
        "select " + String.join(", ", projectedSelect(projection)) + " from " + request.instanceTable() + " i"
            + " join " + LEGACY_TABLE + " l"
            + " on i.project_id = l.project_id and i.resource_id = l.resource_id::bigint"
            + " where l.id = any(?) and l.template_code = 'code_review'"
            + " and l.resource_type = 'merge_request'"
            + " order by l.id";
    List<Map<String, Object>> expectedRaw =
        jdbcTemplate
            .query(
                expectedSql,
                ps -> {
                  ps.setLong(1, legacyVersionId);
                  ps.setArray(2, ps.getConnection().createArrayOf("bigint", selectedIds));
                },
                (rs, rowNum) -> {
                  Map<String, Object> row = new LinkedHashMap<>();
                  for (String column : projection.columns()) {
                    row.put(column, rs.getObject(column));
                  }
                  return row;
                })
            .stream()
            .toList();
    Map<Long, String> expected = canonicalRows(projection.columns(), expectedRaw);
    List<Map<String, Object>> actualRaw =
        jdbcTemplate.queryForList(
            "select " + String.join(", ", projection.columns()) + " from "
                + request.recordTable()
                + " order by id");
    Map<Long, String> actual = canonicalRows(projection.columns(), actualRaw);
    compare("记录", expected, actual);
    if (expected.size() != selection.rows().size()) {
      throw new IllegalStateException(
          "复制记录数不守恒: expected=" + selection.rows().size() + ", actual=" + expected.size());
    }
    verifyInstances(request, selection);
    String sourceChecksum = FormHashSupport.sha256Hex(String.join("\n", expected.values()));
    String targetChecksum = FormHashSupport.sha256Hex(String.join("\n", actual.values()));
    if (!sourceChecksum.equals(targetChecksum)) {
      throw new IllegalStateException("迁移复制校验和不一致，停止发布");
    }
    ObjectNode checksum = objectMapper.createObjectNode();
    checksum.put("sourceSha256", sourceChecksum);
    checksum.put("targetSha256", targetChecksum);
    checksum.put("records", expected.size());
    return new Checksum(expected.size(), checksum);
  }

  private List<String> projectedSelect(LegacyProjection projection) {
    List<String> select = new ArrayList<>();
    for (int index = 0; index < projection.columns().size(); index++) {
      select.add(projection.expressions().get(index) + " as " + projection.columns().get(index));
    }
    return select;
  }

  private void verifyInstances(CopyRequest request, Selection selection) {
    Map<InstanceKey, LegacyTimes> actual =
        jdbcTemplate
            .query(
                "select project_id, resource_id, created_at, updated_at from " + request.instanceTable(),
                (rs, rowNum) ->
                    Map.entry(
                        new InstanceKey(rs.getLong("project_id"), rs.getLong("resource_id")),
                        new LegacyTimes(
                            rs.getTimestamp("created_at").toLocalDateTime(),
                            rs.getTimestamp("updated_at").toLocalDateTime())))
            .stream()
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    for (Map.Entry<InstanceKey, LegacyTimes> entry : selection.instances().entrySet()) {
      LegacyTimes times = actual.get(entry.getKey());
      if (times == null
          || !times.createdAt().equals(entry.getValue().createdAt())
          || !times.updatedAt().equals(entry.getValue().updatedAt())) {
        throw new IllegalStateException("实例时间不守恒: " + entry.getKey());
      }
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

  private Map<Long, String> canonicalRows(
      List<String> columns, List<Map<String, Object>> raw) {
    Map<Long, String> lines = new LinkedHashMap<>();
    for (Map<String, Object> row : raw) {
      long id = ((Number) row.get("id")).longValue();
      List<String> values = new ArrayList<>(columns.size());
      for (String column : columns) {
        values.add(canonical(row.get(column)));
      }
      lines.put(id, String.join("\u0001", values));
    }
    return lines;
  }

  private void compare(String label, Map<Long, String> expected, Map<Long, String> actual) {
    if (!expected.keySet().equals(actual.keySet())) {
      Set<Long> missing = new java.util.TreeSet<>(expected.keySet());
      missing.removeAll(actual.keySet());
      Set<Long> extra = new java.util.TreeSet<>(actual.keySet());
      extra.removeAll(expected.keySet());
      throw new IllegalStateException(
          label + "ID集合不守恒：缺 " + limit(missing) + "，多 " + limit(extra));
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

  private void requireLength(
      String column, String targetType, String sourceTable, String expression) {
    int max = varcharLength(targetType);
    Long tooLong =
        jdbcTemplate.queryForObject(
            "select count(*) from " + sourceTable + " l where " + expression + " is not null"
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
    if (column == null || !column.matches("^[a-z][a-z0-9_]*$")) {
      throw new IllegalStateException("非法列名：" + column);
    }
    return column;
  }

  private void requireTable(String table) {
    if (table == null || !TABLE_NAME.matcher(table).matches()) {
      throw new IllegalStateException("非法版本表名：" + table);
    }
  }

  private boolean isTextual(String type) {
    return "text".equals(type) || type.startsWith("varchar(");
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
    return String.valueOf(value);
  }

  /** 复制请求；目标结构与映射均来自发布任务冻结内容。 */
  public record CopyRequest(
      long scopeId,
      long toVersionId,
      long legacyVersionId,
      String frozenFieldSpecJson,
      String expectedDdlHash,
      String instanceTable,
      String recordTable,
      long sourceConfigId,
      String sourceInstance,
      String canonicalUrl,
      String templateKey,
      List<Long> projectIds,
      String bindingHash) {}

  /** 复制结果；counts 与 checksum 写入任务与 attempt 证据。 */
  public record CopyResult(JsonNode counts, JsonNode checksum) {}

  public record LegacyRow(
      long id,
      String gitlabBaseUrl,
      long projectId,
      Long requestIid,
      long resourceId,
      String reviewer,
      int reviewDurationMinutes,
      int specificationScore,
      int logicScore,
      int performanceScore,
      int designScore,
      int otherScore,
      String remark,
      boolean deleted,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {}

  public record InstanceKey(long projectId, long resourceId) {}

  public record LegacyTimes(LocalDateTime createdAt, LocalDateTime updatedAt) {}

  public record Selection(List<LegacyRow> rows, Map<InstanceKey, LegacyTimes> instances) {}

  private record InstanceRow(long id, long projectId, long resourceId) {}

  private record LegacyProjection(
      List<String> columns, List<String> expressions, String instanceTable) {}

  private record Checksum(long records, JsonNode checksum) {}
}
