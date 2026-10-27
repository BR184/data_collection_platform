package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.FormCreateResponse;
import com.data.collection.platform.entity.form.FormRecordView;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * v6 定位目录的读写与跨版重放核验。
 *
 * <p>目录是全局记录定位与跨版 key 唯一的唯一权威：创建事务写入目录行，重放按目录
 * {@code origin_version_id} 作为指纹版本、按冻结规格规范化首次请求字段后以 v5 算法复算，
 * 禁止用承载版本、当前行值或其它格式重算历史首次指纹。完整资源 C 落在目录行，
 * {@code listByResource} 走 {@code idx_frl_resource} 单索引查询，不扫版本实例表。
 */
@Service
public class FormRecordDirectoryService {

  private final JdbcTemplate jdbcTemplate;
  private final FormRecordContextService contextService;
  private final FormFieldValueService fieldValueService;
  private final FormRecordStore store;
  private final FormRecordViewFactory viewFactory;

  public FormRecordDirectoryService(
      JdbcTemplate jdbcTemplate,
      FormRecordContextService contextService,
      FormFieldValueService fieldValueService,
      FormRecordStore store,
      FormRecordViewFactory viewFactory) {
    this.jdbcTemplate = jdbcTemplate;
    this.contextService = contextService;
    this.fieldValueService = fieldValueService;
    this.store = store;
    this.viewFactory = viewFactory;
  }

  /** 目录行；业务值永远回表读取，目录只保存定位、完整资源 C 与首次身份证据。 */
  public record DirectoryRow(
      long recordId,
      long scopeId,
      long storageVersionId,
      long originVersionId,
      String instanceMode,
      Long instanceId,
      long sourceConfigId,
      String sourceInstance,
      String gitlabBaseUrl,
      long projectId,
      String resourceType,
      String resourceId,
      String requestKey,
      String createdBy,
      String originSpecHash,
      String createPayloadHash) {}

  public Optional<DirectoryRow> findByScopeAndRequestKey(long scopeId, String requestKey) {
    return queryOne(
        "select * from form_record_locations where scope_id = ? and request_key = ?",
        scopeId,
        requestKey);
  }

  public Optional<DirectoryRow> findByRecordId(long recordId) {
    return queryOne("select * from form_record_locations where record_id = ?", recordId, null);
  }

  /**
   * 创建事务内写入目录行；承载版本 = 创建版本，完整 C 取服务端上下文。
   *
   * <p>必须与记录插入、审计、head 同事务；失败整体回滚。
   */
  public void writeForCreatedRecord(FormVersionContext context, FormRecordRow row, long instanceId) {
    String originSpecHash =
        jdbcTemplate.queryForObject("select form_spec_hash(?::jsonb)", String.class,
            context.fieldSpecJson().toString());
    jdbcTemplate.update(
        """
        insert into form_record_locations(
            record_id, scope_id, storage_version_id, origin_version_id, instance_mode, instance_id,
            source_config_id, source_instance, gitlab_base_url, project_id, resource_type,
            resource_id, request_key, created_by, origin_spec_hash, create_payload_hash, created_at)
        values (?, ?, ?, ?, 'MATERIALIZED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        row.id(),
        context.scopeId(),
        context.storageVersionId(),
        context.storageVersionId(),
        instanceId,
        context.sourceConfigId(),
        context.sourceInstance(),
        context.canonicalGitlabBaseUrl(),
        context.projectId(),
        context.resourceType(),
        Long.toString(context.resourceId()),
        row.requestKey(),
        row.createdBy(),
        originSpecHash,
        row.createPayloadHash(),
        row.createdAt() == null ? null : Timestamp.valueOf(row.createdAt()));
  }

  /**
   * 跨版重放：目录命中时按唯一核验合同复算首次指纹，任何失配都以 409 拒绝。
   *
   * <p>请求版本只须属于该 scope（含 LEGACY_SNAPSHOT），不参与哈希分量；未命中返回
   * {@code null}，由调用方按当前版本继续创建流程。
   *
   * @return REPLAYED 响应；目录未命中时为 {@code null}
   */
  public FormCreateResponse replayIfPresent(
      FormVersionContext current,
      long requestVersionId,
      Map<String, JsonNode> rawFields,
      FormActorContext actor,
      String requestKey) {
    DirectoryRow directory = findByScopeAndRequestKey(current.scopeId(), requestKey).orElse(null);
    if (directory == null) {
      return null;
    }
    requireScopeMember(current.scopeId(), requestVersionId);
    if (directory.createdBy() == null || !directory.createdBy().equals(actor.userId())) {
      throw new FormConflictException("请求标识与首次创建账号不一致");
    }
    requireSameResource(directory, current);
    String originSpecHash =
        jdbcTemplate.queryForObject(
            "select form_spec_hash(field_spec)::char(64) from form_template_versions"
                + " where scope_id = ? and id = ?",
            String.class,
            current.scopeId(),
            directory.originVersionId());
    if (originSpecHash == null || !originSpecHash.equals(directory.originSpecHash())) {
      throw new FormConflictException("原版规格证据缺失（ORIGIN_EVIDENCE_MISSING），拒绝重放");
    }
    FormVersionContext originContext = contextService.replayContext(current, directory.originVersionId());
    var canonical =
        fieldValueService.validateForReplay(originContext, rawFields);
    String payloadHash =
        fieldValueService.fingerprint(originContext, actor.userId(), canonical.canonicalFields());
    if (directory.createPayloadHash() == null
        || !directory.createPayloadHash().equalsIgnoreCase(payloadHash)) {
      throw new FormConflictException("首次请求载荷与目录证据不一致（PAYLOAD_MISMATCH）");
    }
    FormVersionContext storageContext = storageVersionContext(current, directory.storageVersionId());
    FormRecordRow recordRow = store.findByIdAny(storageContext, directory.recordId());
    if (recordRow == null) {
      throw new FormConflictException("目录记录无法按承载版本回表");
    }
    if (recordRow.deleted()) {
      throw new FormConflictException("该请求对应的记录已作废，不能重放");
    }
    return new FormCreateResponse(
        "REPLAYED",
        viewFactory.toView(recordRow, actor, current.writable(), store.creatorName(recordRow.id())),
        null);
  }

  /**
   * 写事务内的目录判重：并发或登记行命中时同样按唯一核验合同处理，不写入新记录。
   */
  public void requireNoReplayInsideTransaction(
      FormVersionContext current,
      long requestVersionId,
      Map<String, JsonNode> rawFields,
      FormActorContext actor,
      String requestKey) {
    FormCreateResponse replay =
        replayIfPresent(current, requestVersionId, rawFields, actor, requestKey);
    if (replay != null) {
      throw new FormRecordWriteService.ReplaySignal(replay.record());
    }
  }

  private Optional<DirectoryRow> queryOne(String sql, long scopeId, String requestKey) {
    List<DirectoryRow> rows =
        jdbcTemplate.query(
            sql,
            (rs, rowNum) ->
                new DirectoryRow(
                    rs.getLong("record_id"),
                    rs.getLong("scope_id"),
                    rs.getLong("storage_version_id"),
                    rs.getLong("origin_version_id"),
                    rs.getString("instance_mode"),
                    rs.getObject("instance_id", Long.class),
                    rs.getLong("source_config_id"),
                    rs.getString("source_instance"),
                    rs.getString("gitlab_base_url"),
                    rs.getLong("project_id"),
                    rs.getString("resource_type"),
                    rs.getString("resource_id"),
                    rs.getString("request_key"),
                    rs.getString("created_by"),
                    rs.getString("origin_spec_hash"),
                    rs.getString("create_payload_hash")),
            requestKey == null ? new Object[] {scopeId} : new Object[] {scopeId, requestKey});
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  private void requireScopeMember(long scopeId, long versionId) {
    Boolean member =
        jdbcTemplate.queryForObject(
            "select exists(select 1 from form_template_versions where scope_id = ? and id = ?)",
            Boolean.class,
            scopeId,
            versionId);
    if (!Boolean.TRUE.equals(member)) {
      throw new BizException(ResultCode.BAD_REQUEST, "请求携带的结构版本不属于当前范围");
    }
  }

  private void requireSameResource(DirectoryRow directory, FormVersionContext current) {
    boolean same =
        directory.sourceConfigId() == current.sourceConfigId()
            && directory.sourceInstance().equals(current.sourceInstance())
            && directory.gitlabBaseUrl().equals(current.canonicalGitlabBaseUrl())
            && directory.projectId() == current.projectId()
            && directory.resourceType().equals(current.resourceType())
            && directory.resourceId().equals(Long.toString(current.resourceId()));
    if (!same) {
      throw new FormConflictException("请求完整资源与目录证据不一致");
    }
  }

  private FormVersionContext storageVersionContext(FormVersionContext current, long versionId) {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            """
            select version_label, version_status, storage_kind, instance_table, record_table,
                   field_spec::text as field_spec
              from form_template_versions
             where scope_id = ? and id = ?
            """,
            current.scopeId(),
            versionId);
    if (rows.isEmpty()) {
      throw new FormConflictException("目录承载版本不属于当前范围");
    }
    Map<String, Object> row = rows.getFirst();
    if (!"RECORDS".equals(row.get("storage_kind")) || row.get("record_table") == null) {
      throw new FormConflictException("目录承载版本不含记录表，无法回表");
    }
    String raw = (String) row.get("field_spec");
    return new FormVersionContext(
        current.scopeId(),
        current.scopeStatus(),
        current.sourceConfigId(),
        current.sourceInstance(),
        current.canonicalGitlabBaseUrl(),
        versionId,
        (String) row.get("version_label"),
        (String) row.get("version_status"),
        (String) row.get("instance_table"),
        (String) row.get("record_table"),
        contextService.parseSpec(raw, current.spec().templateKey()),
        contextService.readFieldSpec(raw),
        current.projectId(),
        current.resourceType(),
        current.resourceId(),
        current.rootId(),
        current.severityLevel(),
        current.webUrl());
  }
}
