package com.data.collection.platform.service.form;

import com.data.collection.platform.domain.form.FormFieldSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 版本实例/记录表的参数化读写。
 *
 * <p>表名来自已核验的元数据，列名来自已冻结规格；所有业务值参数化，文本数组显式按 text[] 绑定。
 */
@Service
public class FormRecordStore {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public FormRecordStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public Long findInstanceId(FormVersionContext context) {
    List<Long> ids =
        jdbcTemplate.queryForList(
            "select id from "
                + context.instanceTable()
                + " where scope_id = ? and source_config_id = ? and project_id = ?"
                + " and resource_type = ? and resource_id = ?",
            Long.class,
            context.scopeId(),
            context.sourceConfigId(),
            context.projectId(),
            context.resourceType(),
            context.resourceId());
    return ids.isEmpty() ? null : ids.getFirst();
  }

  /**
   * 原子归并实例并短暂锁定实例行。
   *
   * <p>并发首次创建先由唯一键收敛到同一实例，再由行锁串行化同人检查与记录插入。
   */
  public long ensureInstance(FormVersionContext context, LocalDateTime now) {
    jdbcTemplate.update(
        "insert into "
            + context.instanceTable()
            + "(scope_id, source_config_id, source_instance, gitlab_base_url, project_id,"
            + " resource_type, resource_id, template_key, created_at, updated_at)"
            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + " on conflict (scope_id, source_config_id, project_id, resource_type, resource_id)"
            + " do nothing",
        context.scopeId(),
        context.sourceConfigId(),
        context.sourceInstance(),
        context.canonicalGitlabBaseUrl(),
        context.projectId(),
        context.resourceType(),
        context.resourceId(),
        context.spec().templateKey(),
        Timestamp.valueOf(now),
        Timestamp.valueOf(now));
    List<Long> ids =
        jdbcTemplate.queryForList(
            "select id from "
                + context.instanceTable()
                + " where scope_id = ? and source_config_id = ? and project_id = ?"
                + " and resource_type = ? and resource_id = ? for update",
            Long.class,
            context.scopeId(),
            context.sourceConfigId(),
            context.projectId(),
            context.resourceType(),
            context.resourceId());
    if (ids.isEmpty()) {
      throw new IllegalStateException("表单实例未能建立");
    }
    return ids.getFirst();
  }

  public FormRecordRow findByRequestKey(FormVersionContext context, String requestKey) {
    return queryOne(
        "select * from " + context.recordTable() + " where request_key = ?",
        List.of(requestKey),
        context);
  }

  public FormRecordRow findById(
      FormVersionContext context, long id, long instanceId) {
    return queryOne(
        "select * from " + context.recordTable() + " where id = ? and instance_id = ?",
        List.of(id, instanceId),
        context);
  }

  public FormRecordRow findByIdAny(FormVersionContext context, long id) {
    return queryOne(
        "select * from " + context.recordTable() + " where id = ?", List.of(id), context);
  }

  public List<FormRecordRow> listActive(FormVersionContext context, long instanceId) {
    return jdbcTemplate.query(
        con -> {
          PreparedStatement statement =
              con.prepareStatement(
                  "select * from "
                      + context.recordTable()
                      + " where instance_id = ? and deleted = false"
                      + " order by created_at, id");
          statement.setLong(1, instanceId);
          return statement;
        },
        (rs, rowNum) -> mapRow(rs, context));
  }

  /**
   * 插入一条新记录；唯一 request_key 冲突时不返回行。
   *
   * @return 实际插入的权威行，冲突时 {@code null}
   */
  public FormRecordRow insertRecord(
      FormVersionContext context,
      long instanceId,
      ValidatedFormValues values,
      String actor,
      String requestKey,
      String payloadHash,
      LocalDateTime now) {
    List<String> columns = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    columns.add("instance_id");
    args.add(instanceId);
    columns.add("origin_version_id");
    args.add(context.storageVersionId());
    columns.add("template_version");
    args.add(context.versionLabel());
    columns.add("created_by");
    args.add(actor);
    columns.add("request_key");
    args.add(requestKey);
    columns.add("create_payload_hash");
    args.add(payloadHash);
    columns.add("record_version");
    args.add(1);
    columns.add("deleted");
    args.add(false);
    columns.add("legacy_incomplete");
    args.add(false);
    columns.add("created_at");
    args.add(now);
    columns.add("updated_at");
    args.add(now);
    for (Map.Entry<String, Object> entry : values.columnValues().entrySet()) {
      if (isSystemColumn(entry.getKey())) {
        continue;
      }
      columns.add(entry.getKey());
      args.add(entry.getValue());
    }
    String sql =
        "insert into "
            + context.recordTable()
            + " ("
            + String.join(", ", columns)
            + ") values ("
            + String.join(", ", Collections.nCopies(columns.size(), "?"))
            + ") on conflict (request_key) do nothing returning *";
    List<FormRecordRow> rows =
        jdbcTemplate.query(
            con -> {
              PreparedStatement statement = con.prepareStatement(sql);
              bind(statement, args);
              return statement;
            },
            (rs, rowNum) -> mapRow(rs, context));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public int updateRecord(
      FormVersionContext context,
      long id,
      long instanceId,
      String actor,
      int expectedVersion,
      ValidatedFormValues values,
      LocalDateTime now) {
    List<String> assignments = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    for (Map.Entry<String, Object> entry : values.columnValues().entrySet()) {
      if (isSystemColumn(entry.getKey())) {
        continue;
      }
      assignments.add(entry.getKey() + " = ?");
      args.add(entry.getValue());
    }
    assignments.add("record_version = record_version + 1");
    assignments.add("legacy_incomplete = false");
    assignments.add("legacy_missing_fields = '{}'");
    assignments.add("updated_at = ?");
    args.add(now);
    args.add(id);
    args.add(instanceId);
    args.add(actor);
    args.add(expectedVersion);
    String sql =
        "update "
            + context.recordTable()
            + " set "
            + String.join(", ", assignments)
            + " where id = ? and instance_id = ? and created_by = ?"
            + " and deleted = false and record_version = ?";
    return bindAndUpdate(sql, args);
  }

  public int voidRecord(
      FormVersionContext context,
      long id,
      long instanceId,
      String actor,
      int expectedVersion,
      LocalDateTime now) {
    String sql =
        "update "
            + context.recordTable()
            + " set deleted = true, record_version = record_version + 1, updated_at = ?"
            + " where id = ? and instance_id = ? and created_by = ?"
            + " and deleted = false and record_version = ?";
    return bindAndUpdate(sql, List.of(now, id, instanceId, actor, expectedVersion));
  }

  public boolean sameReviewerExists(
      FormVersionContext context, long instanceId, String reviewerUserId) {
    FormFieldSpec person =
        context.spec().fields().stream()
            .filter(field -> field.storage() != null && field.storage().nameColumn() != null)
            .findFirst()
            .orElse(null);
    if (person == null || reviewerUserId == null || reviewerUserId.isBlank()) {
      return false;
    }
    Boolean exists =
        jdbcTemplate.queryForObject(
            "select exists(select 1 from "
                + context.recordTable()
                + " where instance_id = ? and deleted = false and "
                + person.storage().resolvedColumn()
                + " = ?)",
            Boolean.class,
            instanceId,
            reviewerUserId);
    return Boolean.TRUE.equals(exists);
  }

  public long writeAudit(
      String operation,
      FormVersionContext context,
      FormRecordRow row,
      String actor,
      String actorName,
      String clientIp,
      String userAgent,
      LocalDateTime now) {
    Long id =
        jdbcTemplate.queryForObject(
            """
            insert into form_record_audit_logs(
                record_id, template_key, scope_id, storage_version_id, record_version, operation,
                snapshot_json, operator_id, operator_name, client_ip, user_agent, created_at)
            values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            returning id
            """,
            Long.class,
            row.id(),
            context.spec().templateKey(),
            context.scopeId(),
            context.storageVersionId(),
            row.recordVersion(),
            operation,
            auditSnapshot(context, row),
            actor,
            actorName,
            truncate(clientIp, 64),
            truncate(userAgent, 512),
            Timestamp.valueOf(now));
    if (id == null) {
      throw new IllegalStateException("表单审计写入未返回标识");
    }
    return id;
  }

  /**
   * 原子登记持久变更；已领取的 head 保留租约，由消费者结算本次捕获版本后继续处理新版本。
   */
  public void bumpHead(
      FormVersionContext context,
      long auditId,
      FormRecordRow row,
      Long authorityRevision,
      LocalDateTime now) {
    jdbcTemplate.update(
        """
        insert into form_change_heads(
            source_config_id, source_instance, canonical_gitlab_base_url, project_id, resource_type,
            resource_id, template_key, scope_id, latest_change_version, consumed_change_version,
            latest_audit_id, pending_record_id, pending_record_version, pending_storage_version_id,
            pending_authority_revision, status, fencing_version, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, ?, ?, 1, 0, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
        on conflict (source_config_id, project_id, resource_type, resource_id, template_key)
        do update set
            latest_change_version = form_change_heads.latest_change_version + 1,
            latest_audit_id = excluded.latest_audit_id,
            pending_record_id = excluded.pending_record_id,
            pending_record_version = excluded.pending_record_version,
            pending_storage_version_id = excluded.pending_storage_version_id,
            pending_authority_revision = excluded.pending_authority_revision,
            status = case when form_change_heads.status = 'LEASED'
                          then 'LEASED' else 'PENDING' end,
            lease_owner = case when form_change_heads.status = 'LEASED'
                               then form_change_heads.lease_owner else null end,
            lease_until = case when form_change_heads.status = 'LEASED'
                               then form_change_heads.lease_until else null end,
            next_attempt_at = case when form_change_heads.status = 'LEASED'
                                   then form_change_heads.next_attempt_at else null end,
            last_error = case when form_change_heads.status = 'LEASED'
                              then form_change_heads.last_error else null end,
            updated_at = excluded.updated_at
        """,
        context.sourceConfigId(),
        context.sourceInstance(),
        context.canonicalGitlabBaseUrl(),
        context.projectId(),
        context.resourceType(),
        context.resourceId(),
        context.spec().templateKey(),
        context.scopeId(),
        auditId,
        row.id(),
        row.recordVersion(),
        context.storageVersionId(),
        authorityRevision,
        Timestamp.valueOf(now),
        Timestamp.valueOf(now));
  }

  public Long authorityRevision(FormVersionContext context) {
    List<Long> values =
        jdbcTemplate.queryForList(
            """
            select authority_revision from form_resource_authorities
             where source_config_id = ? and project_id = ? and resource_type = ?
               and resource_id = ? and template_key = ?
            """,
            Long.class,
            context.sourceConfigId(),
            context.projectId(),
            context.resourceType(),
            context.resourceId(),
            context.spec().templateKey());
    return values.isEmpty() ? null : values.getFirst();
  }

  public Map<Long, String> creatorNames(List<Long> recordIds) {
    if (recordIds == null || recordIds.isEmpty()) {
      return Map.of();
    }
    String placeholders = String.join(", ", Collections.nCopies(recordIds.size(), "?"));
    List<Map.Entry<Long, String>> rows =
        jdbcTemplate.query(
            "select record_id, operator_name from form_record_audit_logs"
                + " where operation = 'CREATE' and record_id in ("
                + placeholders
                + ") order by id",
            (rs, rowNum) ->
                Map.entry(rs.getLong("record_id"), rs.getString("operator_name")),
            recordIds.toArray());
    Map<Long, String> names = new LinkedHashMap<>();
    for (Map.Entry<Long, String> row : rows) {
      names.putIfAbsent(row.getKey(), row.getValue());
    }
    return names;
  }

  public String creatorName(long recordId) {
    List<String> values =
        jdbcTemplate.queryForList(
            "select operator_name from form_record_audit_logs"
                + " where record_id = ? and operation = 'CREATE' order by id limit 1",
            String.class,
            recordId);
    return values.isEmpty() ? null : values.getFirst();
  }

  private FormRecordRow queryOne(
      String sql, List<Object> args, FormVersionContext context) {
    List<FormRecordRow> rows =
        jdbcTemplate.query(
            con -> {
              PreparedStatement statement = con.prepareStatement(sql);
              bind(statement, args);
              return statement;
            },
            (rs, rowNum) -> mapRow(rs, context));
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private int bindAndUpdate(String sql, List<Object> args) {
    return jdbcTemplate.update(
        con -> {
          PreparedStatement statement = con.prepareStatement(sql);
          bind(statement, args);
          return statement;
        });
  }

  private void bind(PreparedStatement statement, List<Object> args) throws SQLException {
    int index = 1;
    for (Object arg : args) {
      if (arg == null) {
        statement.setObject(index++, null);
      } else if (arg instanceof List<?> list) {
        statement.setArray(
            index++, statement.getConnection().createArrayOf("text", list.toArray(new String[0])));
      } else if (arg instanceof LocalDate date) {
        statement.setObject(index++, date);
      } else if (arg instanceof LocalDateTime dateTime) {
        statement.setTimestamp(index++, Timestamp.valueOf(dateTime));
      } else if (arg instanceof Integer integer) {
        statement.setInt(index++, integer);
      } else if (arg instanceof Long value) {
        statement.setLong(index++, value);
      } else if (arg instanceof Boolean value) {
        statement.setBoolean(index++, value);
      } else {
        statement.setObject(index++, arg);
      }
    }
  }

  private FormRecordRow mapRow(ResultSet rs, FormVersionContext context) throws SQLException {
    Map<String, Object> fields = new LinkedHashMap<>();
    Map<String, String> fieldNames = new LinkedHashMap<>();
    for (FormFieldSpec field : context.spec().fields()) {
      String column = field.storage().resolvedColumn();
      Object value =
          switch (field.type()) {
            case "integer" -> rs.getObject(column, Integer.class);
            case "boolean" -> rs.getObject(column, Boolean.class);
            case "date" -> {
              LocalDate date = rs.getObject(column, LocalDate.class);
              yield date == null ? null : date.toString();
            }
            case "enum-array" -> readArray(rs, column);
            default -> rs.getString(column);
          };
      fields.put(field.apiKey(), value);
      if (field.storage().nameColumn() != null) {
        fieldNames.put(field.apiKey(), rs.getString(field.storage().nameColumn()));
      }
    }
    Map<String, Boolean> snapshots = new LinkedHashMap<>();
    for (String snapshotColumn : context.spec().snapshotColumns()) {
      Boolean value = rs.getObject(snapshotColumn, Boolean.class);
      if (value != null) {
        snapshots.put(snapshotColumn, value);
      }
    }
    return new FormRecordRow(
        rs.getLong("id"),
        rs.getLong("instance_id"),
        rs.getLong("origin_version_id"),
        rs.getString("template_version"),
        rs.getString("created_by"),
        rs.getString("request_key"),
        rs.getString("create_payload_hash"),
        rs.getInt("record_version"),
        rs.getBoolean("deleted"),
        rs.getBoolean("legacy_incomplete"),
        readArray(rs, "legacy_missing_fields"),
        Collections.unmodifiableMap(fields),
        Collections.unmodifiableMap(fieldNames),
        Collections.unmodifiableMap(snapshots),
        rs.getObject("created_at", LocalDateTime.class),
        rs.getObject("updated_at", LocalDateTime.class));
  }

  private List<String> readArray(ResultSet rs, String column) throws SQLException {
    Array array = rs.getArray(column);
    if (array == null) {
      return List.of();
    }
    Object raw = array.getArray();
    if (raw instanceof String[] values) {
      return Collections.unmodifiableList(java.util.Arrays.asList(values));
    }
    Object[] values = (Object[]) raw;
    List<String> result = new ArrayList<>(values.length);
    for (Object value : values) {
      result.add(value == null ? null : String.valueOf(value));
    }
    return Collections.unmodifiableList(result);
  }

  private String auditSnapshot(FormVersionContext context, FormRecordRow row) {
    ObjectNode root = objectMapper.createObjectNode();
    ObjectNode record = root.putObject("record");
    record.put("id", row.id());
    record.put("instanceId", row.instanceId());
    record.put("originVersionId", row.originVersionId());
    record.put("templateVersion", row.templateVersion());
    record.put("createdBy", row.createdBy());
    record.put("requestKey", row.requestKey());
    record.put("recordVersion", row.recordVersion());
    record.put("deleted", row.deleted());
    record.put("legacyIncomplete", row.legacyIncomplete());
    record.set("fields", objectMapper.valueToTree(row.fields()));
    record.set("fieldNames", objectMapper.valueToTree(row.fieldNames()));
    record.set("snapshotValues", objectMapper.valueToTree(row.snapshotValues()));
    record.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
    record.put("updatedAt", row.updatedAt() == null ? null : row.updatedAt().toString());
    ObjectNode c = root.putObject("context");
    c.put("gitlabBaseUrl", context.canonicalGitlabBaseUrl());
    c.put("projectId", context.projectId());
    c.put("resourceType", context.resourceType());
    c.put("resourceId", context.resourceId());
    c.put("templateKey", context.spec().templateKey());
    ObjectNode instance = root.putObject("instance");
    instance.put("id", row.instanceId());
    instance.put("scopeId", context.scopeId());
    instance.put("sourceConfigId", context.sourceConfigId());
    instance.put("sourceInstance", context.sourceInstance());
    instance.put("gitlabBaseUrl", context.canonicalGitlabBaseUrl());
    instance.put("projectId", context.projectId());
    instance.put("resourceType", context.resourceType());
    instance.put("resourceId", context.resourceId());
    instance.put("templateKey", context.spec().templateKey());
    try {
      return objectMapper.writeValueAsString(root);
    } catch (Exception error) {
      throw new IllegalStateException("Failed to serialize form audit snapshot", error);
    }
  }

  private boolean isSystemColumn(String column) {
    return switch (column) {
      case "id",
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
          "updated_at" -> true;
      default -> false;
    };
  }

  private String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() > max ? value.substring(0, max) : value;
  }
}
