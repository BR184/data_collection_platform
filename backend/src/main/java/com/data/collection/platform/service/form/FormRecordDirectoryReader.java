package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.domain.form.FormFieldSpec;
import com.data.collection.platform.domain.form.FormSourceUrlSupport;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 统一内部多版权威读源（N0 合同 §5.1）：目录定位 → 版本冻结规格 → 实际承载表/旧源表回读。
 *
 * <p>只读 {@code AUTHORITATIVE} 且 {@code PUBLISHED/RETIRED} 的版本；TARGET 与 LEGACY_SNAPSHOT
 * 一律排除。业务读取一律排除作废记录并核验承载身份、完整资源 C 与创建人，缺行或证据不一致
 * 明确拒绝，不返回空载荷冒充有效数据。{@code instance_mode = NONE} 只表示没有实例表，
 * 不表示没有原始记录：这类记录按版本登记的 {@code source_table} 原位回读旧表，值不搬迁。
 * {@code listByResource} 是 {@code idx_frl_resource} 单索引查询，禁止按数字 ID 猜表或逐版扫表。
 *
 * <p>作废记录仍保留显式定位用途：{@link #findIncludingVoided(long)} 返回带作废状态的定位结果，
 * 只供幂等与证据核验，不作为有效业务读源。
 */
@Service
public class FormRecordDirectoryReader {

  /** 回读的物理表名只允许版本登记的既有旧表或小写蛇形标识符。 */
  private static final Pattern TABLE_NAME = Pattern.compile("^[a-z][a-z0-9_]{1,62}$");

  /** 批量按资源回读时单次绑定的资源数上限，避免超出驱动参数上限。 */
  private static final int RESOURCE_ID_CHUNK = 500;

  /**
   * 定位记录：定位证据来自目录，业务值按承载表或旧源表回读，作废状态显式可见。
   *
   * <p>{@code fields} 的键是承载版本冻结规格里的物理列名（含人员字段的姓名列），值为原始列值；
   * {@link #find(long)} 与 {@link #listByResource} 使用同一载荷契约，不因 NONE 或延迟回读而差异。
   */
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
      boolean deleted,
      java.time.LocalDateTime createdAt,
      java.time.LocalDateTime updatedAt,
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

  /** 按记录 ID 读取有效业务记录；承载版本非权威或未发布/未退役、记录已作废或证据不一致时为空/拒绝。 */
  public Optional<LocatedRecord> find(long recordId) {
    return locate(recordId, true);
  }

  /**
   * 显式定位用途：按记录 ID 定位并回读作废状态，供登记幂等与证据核验使用。
   *
   * <p>返回的记录可能已作废，调用方不得把它当作有效业务读源。
   */
  public Optional<LocatedRecord> findIncludingVoided(long recordId) {
    return locate(recordId, false);
  }

  /** 按完整资源 C 定位：单索引查询，跨版本各有一条有效记录，不按 C 去重，排除作废。 */
  public List<LocatedRecord> listByResource(
      long scopeId, String resourceType, String resourceId, long projectId) {
    List<LocatedRecord> located =
        jdbcTemplate.query(
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
            scopeId, resourceType, resourceId, projectId);
    List<LocatedRecord> resolved = new ArrayList<>(located.size());
    for (LocatedRecord record : located) {
      LocatedRecord read = readBack(record, true);
      if (read != null) {
        resolved.add(read);
      }
    }
    return List.copyOf(resolved);
  }

  /**
   * 按同一范围与项目批量回读多个资源 C 的有效记录。
   *
   * <p>与 {@link #listByResource} 使用同一索引与同一证据核验，只是把资源集合放进一次查询，
   * 供事实构建按批解析，避免逐议题重复建连；单批参数数量有上限，超出部分分片继续。
   *
   * @param scopeId 已登记范围
   * @param resourceType 资源类型，议题为 {@code issue}
   * @param resourceIds 资源 C 集合
   * @param projectId 项目 ID
   * @return 按创建时间与记录 ID 升序的有效记录
   */
  public List<LocatedRecord> listByResources(
      long scopeId, String resourceType, java.util.Collection<String> resourceIds, long projectId) {
    List<String> ids = resourceIds.stream().filter(Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) {
      return List.of();
    }
    List<LocatedRecord> resolved = new ArrayList<>();
    for (int start = 0; start < ids.size(); start += RESOURCE_ID_CHUNK) {
      List<String> chunk = ids.subList(start, Math.min(ids.size(), start + RESOURCE_ID_CHUNK));
      String placeholders = String.join(", ", Collections.nCopies(chunk.size(), "?"));
      List<Object> args = new ArrayList<>(chunk.size() + 3);
      args.add(scopeId);
      args.add(resourceType);
      args.addAll(chunk);
      args.add(projectId);
      List<LocatedRecord> located =
          jdbcTemplate.query(
              """
              select l.*, v.record_source_role, v.version_status
                from form_record_locations l
                join form_template_versions v on v.id = l.storage_version_id
               where l.scope_id = ? and l.resource_type = ?
                 and l.resource_id in (%s)
                 and l.project_id = ?
                 and v.record_source_role = 'AUTHORITATIVE'
                 and v.version_status in ('PUBLISHED', 'RETIRED')
               order by l.created_at, l.record_id
              """
                  .formatted(placeholders),
              (rs, rowNum) -> baseRecord(rs),
              args.toArray());
      for (LocatedRecord record : located) {
        LocatedRecord read = readBack(record, true);
        if (read != null) {
          resolved.add(read);
        }
      }
    }
    return List.copyOf(resolved);
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

  private Optional<LocatedRecord> locate(long recordId, boolean activeOnly) {
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
        || !("PUBLISHED".equals(record.versionStatus())
            || "RETIRED".equals(record.versionStatus()))) {
      return Optional.empty();
    }
    return Optional.ofNullable(readBack(record, activeOnly));
  }

  /**
   * 回表读取业务值并核验承载身份；作废记录在 {@code activeOnly} 下返回 {@code null}。
   *
   * <p>{@code MATERIALIZED} 走承载版本的记录表并联查实例表；{@code NONE} 走版本登记的旧源表。
   * 两条路径都从该版本冻结规格取列，保证读到的是原值而不是搬迁后的副本。
   */
  private LocatedRecord readBack(LocatedRecord record, boolean activeOnly) {
    StoredRow stored =
        "NONE".equals(record.instanceMode())
            ? readLegacyRow(record)
            : readMaterializedRow(record);
    if (stored.deleted() && activeOnly) {
      return null;
    }
    return withPayload(
        record, stored.deleted(), stored.createdAt(), stored.updatedAt(), stored.values());
  }

  private StoredRow readMaterializedRow(LocatedRecord record) {
    Map<String, Object> version = requireVersionRow(record);
    if (!"MATERIALIZED".equals(version.get("instance_mode"))
        || version.get("record_table") == null
        || version.get("instance_table") == null) {
      throw new FormConflictException("承载版本不含记录表与实例表，无法回表：version="
          + record.storageVersionId());
    }
    String recordTable = (String) version.get("record_table");
    String instanceTable = (String) version.get("instance_table");
    FormTemplateSpec spec = parseSpec((String) version.get("field_spec"), record.scopeId());
    List<String> columns = specColumns(spec);
    String sql =
        "select r.deleted, r.created_by, r.instance_id, r.created_at, r.updated_at,"
            + " i.source_config_id, i.source_instance, i.gitlab_base_url,"
            + " i.project_id, i.resource_type, i.resource_id, i.scope_id, i.template_key,"
            + String.join(", ", prefixed(columns))
            + " from " + recordTable + " r join " + instanceTable
            + " i on i.id = r.instance_id where r.id = ?";
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, record.recordId());
    if (rows.isEmpty()) {
      throw new FormConflictException(
          "目录记录无法回表：record=" + record.recordId() + ", table=" + recordTable);
    }
    Map<String, Object> row = rows.getFirst();
    requireSameInstanceIdentity(record, row);
    requireSameResource(record, row);
    requireSameCreator(record, row.get("created_by"));
    Map<String, Object> values = new LinkedHashMap<>();
    for (String column : columns) {
      values.put(column, row.get(column));
    }
    return new StoredRow(
        Boolean.TRUE.equals(row.get("deleted")),
        toLocalDateTime(row.get("created_at")),
        toLocalDateTime(row.get("updated_at")),
        Collections.unmodifiableMap(values));
  }

  private StoredRow readLegacyRow(LocatedRecord record) {
    Map<String, Object> version = requireVersionRow(record);
    if (!"NONE".equals(version.get("instance_mode"))) {
      throw new FormConflictException("目录实例模式与承载版本不一致：record=" + record.recordId());
    }
    Object rawSource = version.get("source_table");
    if (rawSource == null || !TABLE_NAME.matcher(rawSource.toString()).matches()) {
      throw new FormConflictException("旧源表名非法，无法回读原位记录：record=" + record.recordId());
    }
    String sourceTable = rawSource.toString();
    if (jdbcTemplate.queryForObject("select to_regclass(?)", String.class, sourceTable) == null) {
      throw new FormConflictException("旧源表不存在，无法回读原位记录：" + sourceTable);
    }
    FormTemplateSpec spec = parseSpec((String) version.get("field_spec"), record.scopeId());
    List<String> columns = specColumns(spec);
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "select deleted, created_at, updated_at, gitlab_base_url, project_id, resource_type,"
            + " resource_id, "
            + String.join(", ", columns)
            + " from " + sourceTable + " where id = ?",
        record.recordId());
    if (rows.isEmpty()) {
      throw new FormConflictException(
          "目录记录无法回表：record=" + record.recordId() + ", table=" + sourceTable);
    }
    Map<String, Object> row = rows.getFirst();
    if (record.createdBy() != null) {
      throw new FormConflictException("原位记录没有创建人证据，目录创建人不一致：record="
          + record.recordId());
    }
    String rowUrl;
    try {
      rowUrl = FormSourceUrlSupport.canonicalize(String.valueOf(row.get("gitlab_base_url")));
    } catch (IllegalArgumentException error) {
      throw new FormConflictException("原位记录来源地址非法：record=" + record.recordId());
    }
    if (!rowUrl.equals(record.gitlabBaseUrl())
        || ((Number) row.get("project_id")).longValue() != record.projectId()
        || !String.valueOf(row.get("resource_type")).equals(record.resourceType())
        || !String.valueOf(row.get("resource_id")).equals(record.resourceId())) {
      throw new FormConflictException(
          "原位记录完整资源与目录证据不一致：record=" + record.recordId());
    }
    Map<String, Object> values = new LinkedHashMap<>();
    for (String column : columns) {
      values.put(column, row.get(column));
    }
    return new StoredRow(
        Boolean.TRUE.equals(row.get("deleted")),
        toLocalDateTime(row.get("created_at")),
        toLocalDateTime(row.get("updated_at")),
        Collections.unmodifiableMap(values));
  }

  private Map<String, Object> requireVersionRow(LocatedRecord record) {
    List<Map<String, Object>> versions = jdbcTemplate.queryForList(
        "select v.instance_mode, v.record_table, v.instance_table, v.source_table,"
            + " v.field_spec::text as field_spec, s.template_key"
            + " from form_template_versions v join form_template_scopes s on s.id = v.scope_id"
            + " where v.id = ? and v.scope_id = ?",
        record.storageVersionId(), record.scopeId());
    if (versions.isEmpty()) {
      throw new FormConflictException("目录承载版本不属于当前范围：version=" + record.storageVersionId());
    }
    return versions.getFirst();
  }

  private FormTemplateSpec parseSpec(String raw, long scopeId) {
    try {
      return contextService.parseSpec(raw, requireTemplateKey(scopeId));
    } catch (RuntimeException error) {
      throw new FormConflictException("承载版本冻结规格无法解析：scope=" + scopeId);
    }
  }

  private String requireTemplateKey(long scopeId) {
    List<String> keys = jdbcTemplate.queryForList(
        "select template_key from form_template_scopes where id = ?", String.class, scopeId);
    if (keys.isEmpty()) {
      throw new FormConflictException("表单范围不存在");
    }
    return keys.getFirst();
  }

  private List<String> specColumns(FormTemplateSpec spec) {
    List<String> columns = new ArrayList<>();
    for (FormFieldSpec field : spec.fields()) {
      if (field.storage() == null) {
        throw new FormConflictException("冻结规格缺少物理绑定：字段=" + field.name());
      }
      columns.add(field.storage().resolvedColumn());
      if (field.storage().nameColumn() != null) {
        columns.add(field.storage().nameColumn());
      }
    }
    if (columns.isEmpty()) {
      throw new FormConflictException("冻结规格没有可读业务列");
    }
    return columns;
  }

  private List<String> prefixed(List<String> columns) {
    List<String> select = new ArrayList<>(columns.size());
    for (String column : columns) {
      select.add("r." + column);
    }
    return select;
  }

  /**
   * 承载身份核验：物理记录实例 ID 必须等于目录实例，实例所属范围与模板必须与目录一致。
   *
   * <p>完整资源 C 相同不代表身份相同：同 C 的另一个实例或另一个范围的实例都必须拒绝，
   * 不能沿用目录里的旧身份返回载荷。
   */
  private void requireSameInstanceIdentity(
      LocatedRecord record, Map<String, Object> instanceRow) {
    Object rawInstanceId = instanceRow.get("instance_id");
    long physicalInstanceId = rawInstanceId == null ? -1L : ((Number) rawInstanceId).longValue();
    if (record.instanceId() == null || physicalInstanceId != record.instanceId()) {
      throw new FormConflictException("承载行实例与目录实例不一致：record=" + record.recordId()
          + "，物理实例=" + physicalInstanceId + "，目录实例=" + record.instanceId());
    }
    Object rawScopeId = instanceRow.get("scope_id");
    long instanceScopeId = rawScopeId == null ? -1L : ((Number) rawScopeId).longValue();
    if (instanceScopeId != record.scopeId()) {
      throw new FormConflictException("承载实例所属范围与目录不一致：record=" + record.recordId()
          + "，实例范围=" + instanceScopeId + "，目录范围=" + record.scopeId());
    }
    String instanceTemplate = (String) instanceRow.get("template_key");
    String scopeTemplate = requireTemplateKey(record.scopeId());
    if (!Objects.equals(instanceTemplate, scopeTemplate)) {
      throw new FormConflictException("承载实例模板与目录范围不一致：record=" + record.recordId()
          + "，实例模板=" + instanceTemplate + "，范围模板=" + scopeTemplate);
    }
  }

  private void requireSameResource(LocatedRecord record, Map<String, Object> instanceRow) {
    boolean same =
        instanceRow.get("source_config_id") != null
            && ((Number) instanceRow.get("source_config_id")).longValue() == record.sourceConfigId()
            && String.valueOf(instanceRow.get("source_instance")).equals(record.sourceInstance())
            && canonicalOrSelf(String.valueOf(instanceRow.get("gitlab_base_url")))
                .equals(record.gitlabBaseUrl())
            && ((Number) instanceRow.get("project_id")).longValue() == record.projectId()
            && String.valueOf(instanceRow.get("resource_type")).equals(record.resourceType())
            && String.valueOf(instanceRow.get("resource_id")).equals(record.resourceId());
    if (!same) {
      throw new FormConflictException(
          "承载实例完整资源与目录证据不一致：record=" + record.recordId());
    }
  }

  private void requireSameCreator(LocatedRecord record, Object rowCreator) {
    if (record.createdBy() == null) {
      if (rowCreator != null) {
        throw new FormConflictException("承载行创建人与目录证据不一致：record=" + record.recordId());
      }
      return;
    }
    if (!record.createdBy().equals(rowCreator == null ? null : String.valueOf(rowCreator))) {
      throw new FormConflictException(
          "承载行创建人与目录证据不一致：record=" + record.recordId());
    }
  }

  private String canonicalOrSelf(String raw) {
    try {
      return FormSourceUrlSupport.canonicalize(raw);
    } catch (IllegalArgumentException error) {
      return raw;
    }
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
        false,
        null,
        null,
        Map.of());
  }

  private LocatedRecord withPayload(
      LocatedRecord record,
      boolean deleted,
      java.time.LocalDateTime createdAt,
      java.time.LocalDateTime updatedAt,
      Map<String, Object> fields) {
    return new LocatedRecord(
        record.recordId(), record.scopeId(), record.storageVersionId(), record.originVersionId(),
        record.instanceMode(), record.instanceId(), record.createdBy(), record.sourceConfigId(),
        record.sourceInstance(), record.gitlabBaseUrl(), record.projectId(), record.resourceType(),
        record.resourceId(), record.requestKey(), record.createPayloadHash(),
        record.recordSourceRole(), record.versionStatus(), deleted, createdAt, updatedAt, fields);
  }

  private java.time.LocalDateTime toLocalDateTime(Object raw) {
    if (raw instanceof java.sql.Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    if (raw instanceof java.time.LocalDateTime value) {
      return value;
    }
    return null;
  }

  private record StoredRow(
      boolean deleted,
      java.time.LocalDateTime createdAt,
      java.time.LocalDateTime updatedAt,
      Map<String, Object> values) {}
}
