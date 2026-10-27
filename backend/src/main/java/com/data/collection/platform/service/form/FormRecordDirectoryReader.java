package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.domain.form.FormFieldSpec;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 统一内部多版权威读源（N0 合同 §5.1）：目录定位 → 版本规格 → 回表读取。
 *
 * <p>只读 {@code AUTHORITATIVE} 且 {@code PUBLISHED/RETIRED} 的版本；TARGET 与
 * LEGACY_SNAPSHOT 一律排除，缺失/退役范围返回显式空结果。{@code listByResource} 是
 * {@code idx_frl_resource} 单索引查询，不扫版本实例表，也不因 LEGACY/NONE 无实例退化。
 * 禁止按数字ID猜表或逐版扫表。事实入口（N4）统一接入本 reader，不保留长期旧表专用路径。
 */
@Service
public class FormRecordDirectoryReader {

  /** 定位记录：定位证据来自目录，业务值回表；NONE 记录无回表行，值为空映射。 */
  public record LocatedRecord(
      long recordId,
      long scopeId,
      long storageVersionId,
      long originVersionId,
      String instanceMode,
      Long instanceId,
      String createdBy,
      Long sourceConfigId,
      String sourceInstance,
      String gitlabBaseUrl,
      long projectId,
      String resourceType,
      String resourceId,
      String requestKey,
      String createPayloadHash,
      String recordSourceRole,
      String versionStatus,
      Map<String, Object> fields) {}

  /** 源集合捕获快照：修订、哈希与版本清单，供消费构建冻结。 */
  public record SourceSet(
      long scopeId, long revision, String hash, List<VersionEntry> versions) {}

  public record VersionEntry(
      long versionId, String recordSourceRole, String fieldSpecHash, String instanceMode,
      String sourceTable, String recordTable, String versionStatus) {}

  private final JdbcTemplate jdbcTemplate;
  private final FormRecordContextService contextService;

  public FormRecordDirectoryReader(
      JdbcTemplate jdbcTemplate, FormRecordContextService contextService) {
    this.jdbcTemplate = jdbcTemplate;
    this.contextService = contextService;
  }

  /** 按记录ID定位；承载版本非 AUTHORITATIVE 或未发布/未退役时返回空。 */
  public Optional<LocatedRecord> find(long recordId) {
    List<LocatedRecord> rows =
        jdbcTemplate.query(
            """
            select l.*, v.record_source_role, v.version_status
              from form_record_locations l
              join form_template_versions v on v.id = l.storage_version_id
             where l.record_id = ?
            """,
            (rs, rowNum) -> baseRecord(rs),
            recordId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    LocatedRecord record = rows.getFirst();
    if (!"AUTHORITATIVE".equals(record.recordSourceRole())
        || !"PUBLISHED".equals(record.versionStatus()) && !"RETIRED".equals(record.versionStatus())) {
      return Optional.empty();
    }
    return Optional.of(record);
  }

  /** 按完整资源C定位：单索引查询，跨版本各有一条有效记录，不按C去重。 */
  public List<LocatedRecord> listByResource(
      long scopeId, String resourceType, String resourceId, long projectId) {
    return jdbcTemplate.query(
        """
        select l.*, v.record_source_role, v.version_status
          from form_record_locations l
          join form_template_versions v on v.id = l.storage_version_id
         where l.scope_id = ? and l.resource_type = ? and l.resource_id = ? and l.project_id = ?
           and v.record_source_role = 'AUTHORITATIVE'
           and v.version_status in ('PUBLISHED', 'RETIRED')
         order by l.created_at, l.record_id
        """,
        (rs, rowNum) -> baseRecord(rs),
        scopeId, resourceType, resourceId, projectId).stream().map(this::withFields).toList();
  }

  /** 捕获当前源集合：修订、哈希与 AUTHORITATIVE 版本清单。 */
  public SourceSet captureSourceSet(long scopeId) {
    List<Map<String, Object>> scopes = jdbcTemplate.queryForList(
        "select source_set_revision, source_set_hash from form_template_scopes where id = ?",
        scopeId);
    if (scopes.isEmpty()) {
      throw new FormConflictException("表单范围不存在");
    }
    long revision = ((Number) scopes.getFirst().get("source_set_revision")).longValue();
    String hash = (String) scopes.getFirst().get("source_set_hash");
    List<VersionEntry> versions = jdbcTemplate.query(
        """
        select id, record_source_role, form_spec_hash(field_spec)::char(64) as spec_hash,
               instance_mode, source_table, record_table, version_status
          from form_template_versions
         where scope_id = ? and record_source_role = 'AUTHORITATIVE'
           and version_status in ('PUBLISHED', 'RETIRED')
         order by id
        """,
        (rs, rowNum) ->
            new VersionEntry(
                rs.getLong("id"),
                rs.getString("record_source_role"),
                rs.getString("spec_hash"),
                rs.getString("instance_mode"),
                rs.getString("source_table"),
                rs.getString("record_table"),
                rs.getString("version_status")),
        scopeId);
    return new SourceSet(scopeId, revision, hash, List.copyOf(versions));
  }

  /** 先定位并做角色过滤，再回表读业务值，避免为被排除角色付出回表开销。 */
  private LocatedRecord baseRecord(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new LocatedRecord(
        rs.getLong("record_id"),
        rs.getLong("scope_id"),
        rs.getLong("storage_version_id"),
        rs.getLong("origin_version_id"),
        rs.getString("instance_mode"),
        rs.getObject("instance_id", Long.class),
        rs.getString("created_by"),
        rs.getLong("source_config_id"),
        rs.getString("source_instance"),
        rs.getString("gitlab_base_url"),
        rs.getLong("project_id"),
        rs.getString("resource_type"),
        rs.getString("resource_id"),
        rs.getString("request_key"),
        rs.getString("create_payload_hash"),
        rs.getString("record_source_role"),
        rs.getString("version_status"),
        Map.of());
  }

  private LocatedRecord withFields(LocatedRecord record) {
    Map<String, Object> fields =
        "MATERIALIZED".equals(record.instanceMode())
            ? readRecordFields(record.storageVersionId(), record.recordId())
            : Map.of();
    return new LocatedRecord(
        record.recordId(), record.scopeId(), record.storageVersionId(), record.originVersionId(),
        record.instanceMode(), record.instanceId(), record.createdBy(), record.sourceConfigId(),
        record.sourceInstance(), record.gitlabBaseUrl(), record.projectId(), record.resourceType(),
        record.resourceId(), record.requestKey(), record.createPayloadHash(),
        record.recordSourceRole(), record.versionStatus(),
        java.util.Collections.unmodifiableMap(fields));
  }

  /** 回表读取业务值；表名/列名只来自已核验元数据，业务值参数化。 */
  private Map<String, Object> readRecordFields(long versionId, long recordId) {
    List<Map<String, Object>> versions = jdbcTemplate.queryForList(
        "select v.field_spec::text as field_spec, v.record_table, s.template_key"
            + " from form_template_versions v join form_template_scopes s on s.id = v.scope_id"
            + " where v.id = ?",
        versionId);
    if (versions.isEmpty() || versions.getFirst().get("record_table") == null) {
      throw new FormConflictException("承载版本不含记录表，无法回表");
    }
    FormTemplateSpec spec = contextService.parseSpec(
        (String) versions.getFirst().get("field_spec"),
        (String) versions.getFirst().get("template_key"));
    String recordTable = (String) versions.getFirst().get("record_table");
    List<String> columns = new java.util.ArrayList<>();
    for (FormFieldSpec field : spec.fields()) {
      columns.add(field.storage().resolvedColumn());
      if (field.storage().nameColumn() != null) {
        columns.add(field.storage().nameColumn());
      }
    }
    Map<String, Object> row = jdbcTemplate.queryForMap(
        "select " + String.join(", ", columns) + " from " + recordTable + " where id = ?",
        recordId);
    Map<String, Object> values = new java.util.LinkedHashMap<>();
    for (String column : columns) {
      values.put(column, row.get(column));
    }
    return java.util.Collections.unmodifiableMap(values);
  }
}
