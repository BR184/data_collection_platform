package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.domain.form.FormFieldSpec;
import com.data.collection.platform.domain.form.FormSourceUrlSupport;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.domain.form.FormVersionSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * v6 受控原位登记（N0 合同 §3）：旧 Demo {@code collect_form_records} 原位 LEGACY/NONE
 * 登记与 v5 已复制记录唯一承载登记。
 *
 * <p>登记只复制历史定位与身份证据，不改写原业务行、原 {@code field_spec}、origin/首次指纹/
 * 创建归属/时间。判定规则：
 *
 * <ul>
 *   <li>守恒证据是确定性的<b>全值</b>快照：业务值、修订号、作废状态、更新时间、完整资源 C、
 *       实例身份与规格哈希任一分量变化都会使预检证据失效；持锁后重算并与预检一致才继续。
 *   <li>幂等重入必须逐条比对既有目录行并<b>实际回表</b>核验；部分目录、同数量错身份或证据
 *       不符都按 409 输出差异，绝不把“已存在部分目录”当作已完成登记，也不自动补认历史。
 *   <li>归属判定复用同一范围绑定校验实现：唯一属于其它范围的旧行不进入本次选集，真正多重
 *       覆盖与未被覆盖才整体拒绝。
 *   <li>登记前复用完整冻结结构核验（v5 版本表与旧 Demo 物理表契约），漂移即拒绝，不写目录
 *       也不切角色。
 * </ul>
 *
 * <p>precheck 与 register 使用同一选集与证据实现。本服务不自动执行：调用方必须先确认旧写已
 * 停止且选集稳定，并只对已隔离的数据库调用。
 */
@Service
public class FormV6RegistrationService {

  /**
   * 旧 Demo 固定语义规格：物理绑定只能引用旧表真实列。
   *
   * <p>原位登记不搬迁值，读取时按本规格直接回 {@code collect_form_records}，因此列名与类型必须
   * 与旧表 DDL 逐一对应；列名或类型漂移由 {@link #verifyLegacyPhysicalContract()} 在预检与
   * 登记时拒绝。
   */
  public static final String LEGACY_DEMO_FIELD_SPEC =
      """
      {"spec_version":1,"template_key":"code_review","conditions":[],"dictionaries":[],
       "fields":[
        {"name":"走查人","apiKey":"reviewerUserId","type":"string","required":false,
         "storage":{"column":"reviewer","columnType":"varchar(128)"}},
        {"name":"走查时间","apiKey":"reviewDurationMinutes","type":"integer","required":false,
         "storage":{"column":"review_duration_minutes","columnType":"integer"}},
        {"name":"规范问题数","apiKey":"specificationCount","type":"integer","required":false,
         "storage":{"column":"specification_score","columnType":"integer"}},
        {"name":"逻辑问题数","apiKey":"logicCount","type":"integer","required":false,
         "storage":{"column":"logic_score","columnType":"integer"}},
        {"name":"性能问题数","apiKey":"performanceCount","type":"integer","required":false,
         "storage":{"column":"performance_score","columnType":"integer"}},
        {"name":"设计问题数","apiKey":"designCount","type":"integer","required":false,
         "storage":{"column":"design_score","columnType":"integer"}},
        {"name":"其他问题数","apiKey":"otherCount","type":"integer","required":false,
         "storage":{"column":"other_score","columnType":"integer"}},
        {"name":"补充说明","apiKey":"supplementNote","type":"string","required":false,
         "storage":{"column":"remark","columnType":"text"}}]}
      """;

  private static final String LEGACY_SOURCE_TABLE = "collect_form_records";
  private static final String CARRIER_KIND_LEGACY = "LEGACY_DEMO_IN_PLACE";
  private static final String CARRIER_KIND_COPIED = "V5_CARRIER_IN_PLACE";
  private static final int EVIDENCE_VERSION = 2;
  private static final String SEQ_LOCK =
      "select pg_advisory_xact_lock(hashtext('form_record_id_seq')::bigint)";

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final FormRecordContextService contextService;
  private final FormScopeValidationService scopeValidationService;
  private final FormVersionSchemaService schemaService;
  private final FormVersionStructureVerifier structureVerifier;
  private final FormRecordDirectoryReader reader;
  private final FormEvidenceCodec evidence;

  public FormV6RegistrationService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      FormRecordContextService contextService,
      FormScopeValidationService scopeValidationService,
      FormVersionSchemaService schemaService,
      FormVersionStructureVerifier structureVerifier,
      FormRecordDirectoryReader reader) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.contextService = contextService;
    this.scopeValidationService = scopeValidationService;
    this.schemaService = schemaService;
    this.structureVerifier = structureVerifier;
    this.reader = reader;
    this.evidence = new FormEvidenceCodec(objectMapper);
  }

  /** 路径 A precheck：核验旧物理表契约、归属与全值证据；已有登记时逐条回表核验。 */
  public PrecheckReport precheckLegacyDemo(long scopeId) {
    ScopeInfo scope = requireScope(scopeId);
    requireLegacySourceTable();
    verifyLegacyPhysicalContract();
    FormScopeValidationService.CoverageScope bound = requireScopeBinding(scope);
    List<LegacyRow> selection = selectLegacyRows(scope, bound);
    String fingerprint = legacyEvidenceFingerprint(scope, selection);
    Long registeredVersion = registeredLegacyVersionId(scopeId);
    if (registeredVersion == null) {
      requireNoOrphanDirectoryRows(scopeId);
      return new PrecheckReport(selection.size(), fingerprint, null, null, false, null);
    }
    requireCompletionEvidence(scope, fingerprint, CARRIER_KIND_LEGACY);
    requireLegacyEvidence(scope, registeredVersion, selection);
    return new PrecheckReport(selection.size(), fingerprint, null, null, true, fingerprint);
  }

  /**
   * 路径 A 登记：新建 LEGACY/NONE 版本并按源行原位登记目录，不伪造实例/账号/修订。
   *
   * <p>单事务失败全回滚；完整登记的同源重入返回既有结果且不增修订，源变化、部分目录或
   * 证据不符一律 409 并输出差异。
   */
  @Transactional
  public RegistrationResult registerLegacyDemo(
      long scopeId, String operatorId, String versionLabel,
      long expectedCount, String expectedRowHash) {
    ScopeInfo scope = requireScope(scopeId);
    if (!"code_review".equals(scope.templateKey())) {
      throw new BizException(ResultCode.BAD_REQUEST, "旧Demo登记仅适用于code_review模板范围");
    }
    if (scope.currentVersionId() != null && !isRegisteredLegacyVersion(scope)) {
      throw new FormConflictException("范围已有当前版本，旧Demo登记仅适用于未启用范围");
    }
    jdbcTemplate.execute(SEQ_LOCK);
    requireLegacySourceTable();
    jdbcTemplate.execute("lock table " + LEGACY_SOURCE_TABLE + " in share mode");
    lockScopeRow(scopeId);
    // 锁后重新读取并核对当前完整绑定：不能沿用取锁前的旧范围对象。
    ScopeInfo fresh = requireScope(scopeId);
    if (fresh.currentVersionId() != null && !isRegisteredLegacyVersion(fresh)) {
      throw new FormConflictException("范围已有当前版本，旧Demo登记仅适用于未启用范围");
    }
    verifyLegacyPhysicalContract();
    FormScopeValidationService.CoverageScope bound = requireScopeBinding(fresh);
    List<LegacyRow> selection = selectLegacyRows(fresh, bound);
    String rowHash = legacyEvidenceFingerprint(fresh, selection);
    requirePrecheckMatch(selection.size(), rowHash, expectedCount, expectedRowHash);
    Long registeredVersion = registeredLegacyVersionId(scopeId);
    if (registeredVersion != null) {
      requireCompletionEvidence(fresh, rowHash, CARRIER_KIND_LEGACY);
      requireLegacyEvidence(fresh, registeredVersion, selection);
      return new RegistrationResult(registeredVersion, directoryRowCount(scopeId),
          scopeSourceSetRevision(scopeId), "ALREADY_REGISTERED");
    }
    requireNoOrphanDirectoryRows(scopeId);
    if (selection.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "旧Demo选集为空，无登记对象");
    }
    long versionId = insertLegacyVersion(scopeId, versionLabel);
    // 范围已绑定来源配置；同 URL 可能存在多个配置，不能按地址反推。
    long sourceConfigId = fresh.sourceConfigId();
    String originSpecHash = specHashOfVersion(versionId);
    int inserted = jdbcTemplate.update(
        """
        insert into form_record_locations(
            record_id, scope_id, storage_version_id, origin_version_id, instance_mode, instance_id,
            source_config_id, source_instance, gitlab_base_url, project_id, resource_type,
            resource_id, request_key, created_by, origin_spec_hash, create_payload_hash, created_at)
        select l.id, ?, ?, ?, 'NONE', null, ?, ?, ?, l.project_id, l.resource_type,
               l.resource_id, null, null, ?::char(64), null, l.created_at
          from collect_form_records l
         where l.id = any(?)
        on conflict (record_id) do nothing
        """,
        scopeId, versionId, versionId, sourceConfigId, fresh.sourceInstance(),
        fresh.canonicalUrl(), originSpecHash,
        selection.stream().map(LegacyRow::id).toArray(Long[]::new));
    if (inserted != selection.size()) {
      throw new FormConflictException("目录登记数不守恒：expected=" + selection.size()
          + ", inserted=" + inserted);
    }
    requireLegacyEvidence(fresh, versionId, selection);
    int revision = bumpSourceSet(scopeId);
    insertRegistrationEvent(scopeId, revision, "LEGACY_REGISTERED", operatorId,
        registrationPayload(selection.size(), rowHash, CARRIER_KIND_LEGACY,
            objectMapper.createArrayNode()));
    long highWater = jdbcTemplate.queryForObject(
        "select coalesce(max(id), 0) from " + LEGACY_SOURCE_TABLE, Long.class);
    jdbcTemplate.queryForObject("select form_advance_record_sequence(?)", Long.class, highWater);
    upsertWatermark(LEGACY_SOURCE_TABLE, highWater);
    if (fresh.currentVersionId() == null) {
      jdbcTemplate.update(
          "update form_template_scopes set current_version_id = ? where id = ? and"
              + " current_version_id is null",
          versionId, scopeId);
    }
    return new RegistrationResult(versionId, directoryRowCount(scopeId), revision, "REGISTERED");
  }

  /** 路径 B precheck：结构核验、归属核验、副本承载判定与全值证据。 */
  public PrecheckReport precheckCopiedRecords(long scopeId) {
    ScopeInfo scope = requireScope(scopeId);
    FormScopeValidationService.CoverageScope bound = requireScopeBinding(scope);
    CarrierAnalysis analysis = analyzeCopies(scope, bound);
    if (analysis.directoryRowCount() > 0) {
      requireCompletionEvidence(scope, analysis.fingerprint(), CARRIER_KIND_COPIED);
      requireCopiedEvidence(scope, analysis);
    }
    return new PrecheckReport(analysis.carrierRecords().size(), analysis.fingerprint(),
        analysis.carrierReport(), analysis.fieldLossReport(),
        analysis.directoryRowCount() > 0, null);
  }

  /**
   * 路径 B 登记：v5 已复制记录按唯一承载副本原位登记；承载版本保持 AUTHORITATIVE，
   * 其余版本置 LEGACY_SNAPSHOT。首次指纹/账号/完整C按历史值原样复制，不重算。
   */
  @Transactional
  public RegistrationResult registerCopiedRecords(
      long scopeId, String operatorId, long expectedCount, String expectedFingerprint) {
    ScopeInfo scope = requireScope(scopeId);
    jdbcTemplate.execute(SEQ_LOCK);
    lockScopeRow(scopeId);
    lockScopeVersionTables(scopeId);
    // 锁后重新读取并核对当前完整绑定：绑定在锁等待期间变化必须按当前证据拒绝。
    ScopeInfo fresh = requireScope(scopeId);
    FormScopeValidationService.CoverageScope bound = requireScopeBinding(fresh);
    CarrierAnalysis analysis = analyzeCopies(fresh, bound);
    requirePrecheckMatch(analysis.carrierRecords().size(), analysis.fingerprint(),
        expectedCount, expectedFingerprint);
    if (analysis.directoryRowCount() > 0) {
      requireCompletionEvidence(fresh, analysis.fingerprint(), CARRIER_KIND_COPIED);
      requireCopiedEvidence(fresh, analysis);
      return new RegistrationResult(analysis.carrierVersionId(), directoryRowCount(scopeId),
          scopeSourceSetRevision(scopeId), "ALREADY_REGISTERED");
    }
    if (analysis.carrierRecords().isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "路径B空选集不适用：无v5已复制记录");
    }
    if (!analysis.fieldLossReport().isEmpty()) {
      throw new FormConflictException("副本含承载规格已删除字段的非空值，输出field_loss_report并阻断");
    }
    if (analysis.carrierVersionId() == null
        || !Objects.equals(analysis.carrierVersionId(), fresh.currentVersionId())) {
      throw new FormConflictException("承载副本未全部位于当前版本，停止登记");
    }
    jdbcTemplate.update(
        "update form_template_versions set record_source_role = 'LEGACY_SNAPSHOT'"
            + " where scope_id = ? and id <> ?",
        scopeId, analysis.carrierVersionId());
    int inserted = 0;
    for (CarrierRecord carrier : analysis.carrierRecords()) {
      inserted += jdbcTemplate.update(
          """
          insert into form_record_locations(
              record_id, scope_id, storage_version_id, origin_version_id, instance_mode, instance_id,
              source_config_id, source_instance, gitlab_base_url, project_id, resource_type,
              resource_id, request_key, created_by, origin_spec_hash, create_payload_hash, created_at)
          values (?, ?, ?, ?, 'MATERIALIZED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          on conflict (record_id) do nothing
          """,
          carrier.recordId(), scopeId, carrier.versionId(), carrier.originVersionId(),
          carrier.instanceId(), carrier.sourceConfigId(), carrier.sourceInstance(),
          carrier.gitlabBaseUrl(), carrier.projectId(), carrier.resourceType(),
          carrier.resourceId(), carrier.requestKey(), carrier.createdBy(),
          carrier.originSpecHash(), carrier.createPayloadHash(),
          Timestamp.valueOf(carrier.createdAt()));
    }
    if (inserted != analysis.carrierRecords().size()) {
      throw new FormConflictException("目录登记数不守恒：expected=" + analysis.carrierRecords().size()
          + ", inserted=" + inserted);
    }
    requireCopiedEvidence(fresh, analysis);
    int revision = bumpSourceSet(scopeId);
    insertRegistrationEvent(scopeId, revision, "LEGACY_REGISTERED", operatorId,
        registrationPayload(analysis.carrierRecords().size(), analysis.fingerprint(),
            CARRIER_KIND_COPIED, analysis.fieldLossReport()));
    long highWater = jdbcTemplate.queryForObject(
        "select coalesce(max(record_id), 0) from form_record_locations where scope_id = ?",
        Long.class, scopeId);
    jdbcTemplate.queryForObject("select form_advance_record_sequence(?)", Long.class, highWater);
    upsertWatermark("form_scope_" + scopeId, highWater);
    return new RegistrationResult(analysis.carrierVersionId(), directoryRowCount(scopeId),
        revision, "REGISTERED");
  }

  // ===== 完整登记证据核验（N1-R01/R03）=====

  /** 路径 A 完整登记核验：目录行数、逐行身份与旧表实际回读三者一致，否则输出差异并 409。 */
  private void requireLegacyEvidence(
      ScopeInfo scope, long versionId, List<LegacyRow> selection) {
    List<String> diffs = new ArrayList<>();
    Map<Long, Map<String, Object>> rows = directoryRowsByRecord(scope.scopeId());
    long expected = selection.size();
    if ((long) rows.size() != expected) {
      diffs.add("目录行数=" + rows.size() + "，选集行数=" + expected);
    }
    String originSpecHash = specHashOfVersion(versionId);
    Set<Long> selectedIds = new LinkedHashSet<>();
    for (LegacyRow row : selection) {
      selectedIds.add(row.id());
      Map<String, Object> directory = rows.get(row.id());
      if (directory == null) {
        diffs.add("记录 " + row.id() + " 缺少目录行");
        continue;
      }
      diffs.addAll(legacyRowDiffs(row, directory, scope, versionId, originSpecHash));
    }
    for (Long recordId : rows.keySet()) {
      if (!selectedIds.contains(recordId)) {
        diffs.add("目录行 " + recordId + " 不属于本次选集");
      }
    }
    Long backTableHits = jdbcTemplate.queryForObject(
        """
        select count(*) from collect_form_records l
          join form_record_locations d on d.record_id = l.id
         where d.scope_id = ? and d.storage_version_id = ?
        """,
        Long.class, scope.scopeId(), versionId);
    if (backTableHits == null || backTableHits != expected) {
      diffs.add("旧表实际回读=" + backTableHits + "，选集行数=" + expected);
    }
    diffs.addAll(locatabilityDiffs(selection.stream().map(LegacyRow::id).toList()));
    throwIfDiffs("旧Demo", diffs);
  }

  private List<String> legacyRowDiffs(
      LegacyRow row,
      Map<String, Object> directory,
      ScopeInfo scope,
      long versionId,
      String originSpecHash) {
    List<String> diffs = new ArrayList<>();
    requireEqual(diffs, row.id(), "scope_id", scope.scopeId(), directory.get("scope_id"));
    requireEqual(diffs, row.id(), "storage_version_id", versionId,
        directory.get("storage_version_id"));
    requireEqual(diffs, row.id(), "origin_version_id", versionId,
        directory.get("origin_version_id"));
    requireEqual(diffs, row.id(), "instance_mode", "NONE", directory.get("instance_mode"));
    requireEqual(diffs, row.id(), "instance_id", null, directory.get("instance_id"));
    requireEqual(diffs, row.id(), "source_config_id", scope.sourceConfigId(),
        directory.get("source_config_id"));
    requireEqual(diffs, row.id(), "source_instance", scope.sourceInstance(),
        directory.get("source_instance"));
    requireEqual(diffs, row.id(), "gitlab_base_url", scope.canonicalUrl(),
        directory.get("gitlab_base_url"));
    requireEqual(diffs, row.id(), "project_id", row.projectId(), directory.get("project_id"));
    requireEqual(diffs, row.id(), "resource_type", row.resourceType(),
        directory.get("resource_type"));
    requireEqual(diffs, row.id(), "resource_id", row.resourceId(), directory.get("resource_id"));
    requireEqual(diffs, row.id(), "request_key", null, directory.get("request_key"));
    requireEqual(diffs, row.id(), "created_by", null, directory.get("created_by"));
    requireEqual(diffs, row.id(), "create_payload_hash", null,
        directory.get("create_payload_hash"));
    requireEqual(diffs, row.id(), "origin_spec_hash", originSpecHash,
        directory.get("origin_spec_hash"));
    requireEqual(diffs, row.id(), "created_at", row.createdAt(), directory.get("created_at"));
    return diffs;
  }

  /** 路径 B 完整登记核验：目录逐行与承载副本证据一致，且每条都能实际回表。 */
  private void requireCopiedEvidence(ScopeInfo scope, CarrierAnalysis analysis) {
    List<String> diffs = new ArrayList<>();
    Map<Long, Map<String, Object>> rows = directoryRowsByRecord(scope.scopeId());
    Set<Long> carriers = new LinkedHashSet<>();
    for (CarrierRecord carrier : analysis.carrierRecords()) {
      carriers.add(carrier.recordId());
      Map<String, Object> directory = rows.get(carrier.recordId());
      if (directory == null) {
        diffs.add("记录 " + carrier.recordId() + " 缺少目录行");
        continue;
      }
      requireEqual(diffs, carrier.recordId(), "scope_id", scope.scopeId(),
          directory.get("scope_id"));
      requireEqual(diffs, carrier.recordId(), "storage_version_id", carrier.versionId(),
          directory.get("storage_version_id"));
      requireEqual(diffs, carrier.recordId(), "origin_version_id", carrier.originVersionId(),
          directory.get("origin_version_id"));
      requireEqual(diffs, carrier.recordId(), "instance_mode", "MATERIALIZED",
          directory.get("instance_mode"));
      requireEqual(diffs, carrier.recordId(), "instance_id", carrier.instanceId(),
          directory.get("instance_id"));
      requireEqual(diffs, carrier.recordId(), "source_config_id", carrier.sourceConfigId(),
          directory.get("source_config_id"));
      requireEqual(diffs, carrier.recordId(), "source_instance", carrier.sourceInstance(),
          directory.get("source_instance"));
      requireEqual(diffs, carrier.recordId(), "gitlab_base_url", carrier.gitlabBaseUrl(),
          directory.get("gitlab_base_url"));
      requireEqual(diffs, carrier.recordId(), "project_id", carrier.projectId(),
          directory.get("project_id"));
      requireEqual(diffs, carrier.recordId(), "resource_type", carrier.resourceType(),
          directory.get("resource_type"));
      requireEqual(diffs, carrier.recordId(), "resource_id", carrier.resourceId(),
          directory.get("resource_id"));
      requireEqual(diffs, carrier.recordId(), "request_key", carrier.requestKey(),
          directory.get("request_key"));
      requireEqual(diffs, carrier.recordId(), "created_by", carrier.createdBy(),
          directory.get("created_by"));
      requireEqual(diffs, carrier.recordId(), "create_payload_hash", carrier.createPayloadHash(),
          directory.get("create_payload_hash"));
      requireEqual(diffs, carrier.recordId(), "origin_spec_hash", carrier.originSpecHash(),
          directory.get("origin_spec_hash"));
      requireEqual(diffs, carrier.recordId(), "created_at", carrier.createdAt(),
          directory.get("created_at"));
    }
    for (Long recordId : rows.keySet()) {
      if (!carriers.contains(recordId)) {
        diffs.add("目录行 " + recordId + " 不属于本次承载选集");
      }
    }
    diffs.addAll(locatabilityDiffs(
        analysis.carrierRecords().stream().map(CarrierRecord::recordId).toList()));
    throwIfDiffs("v5承载登记", diffs);
  }

  /** 逐条按统一读源回表定位：缺行、承载版本非权威或证据失配都作为差异输出。 */
  private List<String> locatabilityDiffs(List<Long> recordIds) {
    List<String> diffs = new ArrayList<>();
    for (Long recordId : recordIds) {
      try {
        if (reader.findIncludingVoided(recordId).isEmpty()) {
          diffs.add("记录 " + recordId + " 无法按统一读源定位");
        }
      } catch (FormConflictException error) {
        diffs.add("记录 " + recordId + " 回表证据不一致：" + error.getMessage());
      }
    }
    return diffs;
  }

  private void throwIfDiffs(String label, List<String> diffs) {
    if (diffs.isEmpty()) {
      return;
    }
    boolean partial =
        diffs.stream()
            .anyMatch(diff -> diff.contains("缺少目录行")
                || diff.contains("目录行数") || diff.contains("实际回读"));
    String kind = partial ? "登记不完整" : "登记证据不一致";
    throw new FormConflictException(label + kind + "，停止登记：差异数=" + diffs.size()
        + "，前几条=" + diffs.stream().limit(10).toList());
  }

  private void requireEqual(
      List<String> diffs, long recordId, String field, Object expected, Object actual) {
    Object left = normalizeEvidenceValue(expected);
    Object right = normalizeEvidenceValue(actual);
    if (!Objects.equals(left, right)) {
      diffs.add("记录 " + recordId + " 的 " + field + " 目录=" + right + "，证据=" + left);
    }
  }

  private Object normalizeEvidenceValue(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof Timestamp timestamp) {
      return timestamp.toLocalDateTime();
    }
    return value;
  }

  private Map<Long, Map<String, Object>> directoryRowsByRecord(long scopeId) {
    Map<Long, Map<String, Object>> rows = new LinkedHashMap<>();
    for (Map<String, Object> row : jdbcTemplate.queryForList(
        "select * from form_record_locations where scope_id = ? order by record_id", scopeId)) {
      rows.put(((Number) row.get("record_id")).longValue(), row);
    }
    return rows;
  }

  private long directoryRowCount(long scopeId) {
    Long count = jdbcTemplate.queryForObject(
        "select count(*) from form_record_locations where scope_id = ?", Long.class, scopeId);
    return count == null ? 0 : count;
  }

  private void requireNoOrphanDirectoryRows(long scopeId) {
    long rows = directoryRowCount(scopeId);
    if (rows > 0) {
      throw new FormConflictException("旧Demo登记不完整，停止登记：范围已有 " + rows
          + " 条目录行但没有已登记的LEGACY版本");
    }
  }

  private String specHashOfVersion(long versionId) {
    List<String> hashes = jdbcTemplate.queryForList(
        "select form_spec_hash(field_spec)::char(64) from form_template_versions where id = ?",
        String.class, versionId);
    if (hashes.isEmpty()) {
      throw new FormConflictException("登记版本不存在，无法核验规格哈希：version=" + versionId);
    }
    return hashes.getFirst();
  }

  /** precheck 与 register 之间源变化即中止（§3.2 发布前后业务值变化即中止）。 */
  private void requirePrecheckMatch(
      int actualCount, String actualHash, long expectedCount, String expectedHash) {
    if (actualCount != expectedCount || !actualHash.equals(expectedHash)) {
      throw new FormConflictException("precheck与register之间源已变化，停止登记：count="
          + actualCount + "/" + expectedCount);
    }
  }


  // ===== 路径 A 选集与全值哈希 =====

  private List<LegacyRow> selectLegacyRows(
      ScopeInfo scope, FormScopeValidationService.CoverageScope self) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        """
        select id, gitlab_base_url, project_id, resource_type, resource_id, reviewer,
               review_duration_minutes, specification_score, logic_score, performance_score,
               design_score, other_score, remark, deleted, created_at, updated_at
          from collect_form_records
         where template_code = ? and resource_type = ?
         order by id
        """,
        scope.templateKey(), scope.resourceType());
    List<FormScopeValidationService.CoverageScope> coverage =
        scopeValidationService.listCoverageScopes(scope.templateKey(), scope.resourceType());
    List<LegacyRow> selected = new ArrayList<>();
    List<Long> uncovered = new ArrayList<>();
    List<Long> duplicated = new ArrayList<>();
    for (Map<String, Object> raw : rows) {
      long id = ((Number) raw.get("id")).longValue();
      String canonical = canonicalRowUrl(id, raw.get("gitlab_base_url"));
      long projectId = ((Number) raw.get("project_id")).longValue();
      boolean belongsToCurrent = self.covers(canonical, projectId);
      boolean ownedByOther = coverage.stream()
          .anyMatch(candidate ->
              candidate.scopeId() != scope.scopeId() && candidate.covers(canonical, projectId));
      if (belongsToCurrent && ownedByOther) {
        duplicated.add(id);
      } else if (belongsToCurrent) {
        selected.add(toLegacyRow(id, raw, canonical, projectId));
      } else if (!ownedByOther) {
        uncovered.add(id);
      }
      // 唯一属于其它范围的旧行既不进入本次选集，也不构成冲突。
    }
    if (!duplicated.isEmpty()) {
      throw new FormConflictException("旧Demo记录存在重复归属，停止登记；重复归属记录数="
          + duplicated.size() + "，前几条=" + duplicated.stream().limit(10).toList());
    }
    if (!uncovered.isEmpty()) {
      throw new FormConflictException("旧Demo记录未被已登记范围覆盖，停止登记；未覆盖记录数="
          + uncovered.size() + "，前几条=" + uncovered.stream().limit(10).toList());
    }
    return selected;
  }


  private String canonicalRowUrl(long id, Object rawUrl) {
    try {
      return FormSourceUrlSupport.canonicalize(String.valueOf(rawUrl));
    } catch (IllegalArgumentException error) {
      throw new FormConflictException("旧记录 GitLab 基址非法：record=" + id);
    }
  }

  private LegacyRow toLegacyRow(
      long id, Map<String, Object> raw, String canonical, long projectId) {
    Timestamp createdAt = (Timestamp) raw.get("created_at");
    Timestamp updatedAt = (Timestamp) raw.get("updated_at");
    return new LegacyRow(
        id,
        canonical,
        projectId,
        (String) raw.get("resource_type"),
        String.valueOf(raw.get("resource_id")),
        (String) raw.get("reviewer"),
        ((Number) raw.get("review_duration_minutes")).intValue(),
        ((Number) raw.get("specification_score")).intValue(),
        ((Number) raw.get("logic_score")).intValue(),
        ((Number) raw.get("performance_score")).intValue(),
        ((Number) raw.get("design_score")).intValue(),
        ((Number) raw.get("other_score")).intValue(),
        (String) raw.get("remark"),
        Boolean.TRUE.equals(raw.get("deleted")),
        createdAt.toLocalDateTime(),
        updatedAt.toLocalDateTime());
  }

  /** 路径 A 全值证据：范围身份 + 逐行类型化原值，NULL/空串与含分隔字符的文本都可区分。 */
  private String legacyEvidenceFingerprint(ScopeInfo scope, List<LegacyRow> rows) {
    ObjectNode envelope = evidenceEnvelope(CARRIER_KIND_LEGACY, scope);
    ArrayNode list = envelope.putArray("rows");
    for (LegacyRow row : rows) {
      ObjectNode node = evidence.row();
      evidence.putNumber(node, "recordId", row.id());
      evidence.putText(node, "gitlabBaseUrl", row.gitlabBaseUrl());
      evidence.putNumber(node, "projectId", row.projectId());
      evidence.putText(node, "resourceType", row.resourceType());
      evidence.putText(node, "resourceId", row.resourceId());
      evidence.putText(node, "reviewer", row.reviewer());
      evidence.putInteger(node, "reviewDurationMinutes", row.reviewDurationMinutes());
      evidence.putInteger(node, "specificationCount", row.specificationCount());
      evidence.putInteger(node, "logicCount", row.logicCount());
      evidence.putInteger(node, "performanceCount", row.performanceCount());
      evidence.putInteger(node, "designCount", row.designCount());
      evidence.putInteger(node, "otherCount", row.otherCount());
      evidence.putText(node, "supplementNote", row.supplementNote());
      evidence.putBoolean(node, "deleted", row.deleted());
      evidence.putTime(node, "createdAt", row.createdAt());
      evidence.putTime(node, "updatedAt", row.updatedAt());
      list.add(node);
    }
    return evidence.fingerprint(envelope);
  }

  private ObjectNode evidenceEnvelope(String kind, ScopeInfo scope) {
    ObjectNode envelope = evidence.row();
    evidence.putText(envelope, "kind", kind);
    evidence.putInteger(envelope, "evidenceVersion", EVIDENCE_VERSION);
    evidence.putText(envelope, "templateKey", scope.templateKey());
    evidence.putText(envelope, "resourceType", scope.resourceType());
    evidence.putNumber(envelope, "sourceConfigId", scope.sourceConfigId());
    evidence.putText(envelope, "sourceInstance", scope.sourceInstance());
    evidence.putText(envelope, "canonicalUrl", scope.canonicalUrl());
    return envelope;
  }

  private Long registeredLegacyVersionId(long scopeId) {
    List<Long> ids = jdbcTemplate.queryForList(
        "select id from form_template_versions where scope_id = ? and instance_mode = 'NONE'"
            + " and source_table = ?",
        Long.class, scopeId, LEGACY_SOURCE_TABLE);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  /** 幂等重入许可：当前版本已是本范围的 LEGACY/NONE 登记版本。 */
  private boolean isRegisteredLegacyVersion(ScopeInfo scope) {
    return scope.currentVersionId() != null
        && scope.currentVersionId().equals(registeredLegacyVersionId(scope.scopeId()));
  }

  private long insertLegacyVersion(long scopeId, String versionLabel) {
    if (versionLabel == null || versionLabel.isBlank() || versionLabel.length() > 64) {
      throw new BizException(ResultCode.BAD_REQUEST, "登记版本标签必须为1-64个字符");
    }
    return jdbcTemplate.queryForObject(
        """
        insert into form_template_versions(
            scope_id, version_label, storage_kind, instance_table, record_table, field_spec,
            version_status, published_at, record_source_role, instance_mode, source_table)
        values (?, ?, 'LEGACY', null, null, ?::jsonb, 'PUBLISHED', current_timestamp,
                'AUTHORITATIVE', 'NONE', ?)
        returning id
        """,
        Long.class, scopeId, versionLabel, LEGACY_DEMO_FIELD_SPEC, LEGACY_SOURCE_TABLE);
  }


  private void requireLegacySourceTable() {
    if (jdbcTemplate.queryForObject("select to_regclass(?)", String.class, LEGACY_SOURCE_TABLE)
        == null) {
      throw new BizException(ResultCode.BAD_REQUEST, "旧Demo表不存在，无原位登记对象");
    }
  }

  /** 旧 Demo 物理表契约：冻结规格的每个物理列都必须真实存在且类型一致。 */
  private void verifyLegacyPhysicalContract() {
    FormTemplateSpec spec = contextService.parseSpec(LEGACY_DEMO_FIELD_SPEC, "code_review");
    Map<String, String> actual = new LinkedHashMap<>();
    jdbcTemplate.query(
        """
        select a.attname as column_name, format_type(a.atttypid, a.atttypmod) as column_type
          from pg_attribute a
         where a.attrelid = to_regclass(?) and a.attnum > 0 and not a.attisdropped
        """,
        rs -> {
          actual.put(rs.getString("column_name"), rs.getString("column_type"));
        },
        LEGACY_SOURCE_TABLE);
    if (actual.isEmpty()) {
      throw new FormConflictException("旧Demo物理列契约无法读取：" + LEGACY_SOURCE_TABLE);
    }
    List<String> mismatches = new ArrayList<>();
    for (FormFieldSpec field : spec.fields()) {
      if (field.storage() == null) {
        throw new FormConflictException("旧Demo规格缺少物理绑定：字段=" + field.name());
      }
      String column = field.storage().resolvedColumn();
      String declared = normalizeColumnType(field.storage().resolvedColumnType());
      String found = actual.get(column);
      if (found == null) {
        mismatches.add(column + " 不存在");
      } else if (!found.equals(declared)) {
        mismatches.add(column + " 实际=" + found + " 声明=" + declared);
      }
    }
    if (!mismatches.isEmpty()) {
      throw new FormConflictException("旧Demo物理列契约不一致，停止登记：" + mismatches);
    }
  }

  private String normalizeColumnType(String declared) {
    String value = declared == null ? "" : declared.trim().toLowerCase(Locale.ROOT);
    if (value.startsWith("varchar(")) {
      return "character varying(" + value.substring("varchar(".length(), value.length() - 1) + ")";
    }
    return switch (value) {
      case "int", "int4", "integer" -> "integer";
      case "int8", "bigint" -> "bigint";
      case "bool", "boolean" -> "boolean";
      default -> value;
    };
  }

  /** 登记前复用完整冻结结构核验：本范围任一新版本表漂移都整体拒绝。 */
  private void verifyVersionStructures(ScopeInfo scope) {
    List<Map<String, Object>> versions = jdbcTemplate.queryForList(
        """
        select id, field_spec::text as field_spec, migration_spec is not null as migration_target
          from form_template_versions
         where scope_id = ? and storage_kind = 'RECORDS' and record_table is not null
         order by id
        """,
        scope.scopeId());
    for (Map<String, Object> version : versions) {
      long versionId = ((Number) version.get("id")).longValue();
      FormTemplateSpec spec =
          contextService.parseSpec((String) version.get("field_spec"), scope.templateKey());
      FormVersionSchema schema =
          schemaService.generate(
              scope.scopeId(), versionId, spec,
              Boolean.TRUE.equals(version.get("migration_target")));
      try {
        structureVerifier.verify(schema);
      } catch (IllegalStateException drift) {
        throw new FormConflictException(
            "版本结构漂移，停止登记：version=" + versionId + "，" + drift.getMessage());
      }
    }
  }


  // ===== 路径 B 副本分析 =====

  private CarrierAnalysis analyzeCopies(
      ScopeInfo scope, FormScopeValidationService.CoverageScope bound) {
    verifyVersionStructures(scope);
    List<Map<String, Object>> versions = jdbcTemplate.queryForList(
        """
        select id, instance_table, record_table, field_spec::text as field_spec
          from form_template_versions
         where scope_id = ? and storage_kind = 'RECORDS' and record_table is not null
           and version_status in ('PUBLISHED', 'RETIRED')
         order by id
        """,
        scope.scopeId());
    Map<Long, List<CopyRow>> byRecord = new LinkedHashMap<>();
    Map<Long, List<String>> columnsByVersion = new LinkedHashMap<>();
    Map<Long, String> specHashByVersion = new LinkedHashMap<>();
    for (Map<String, Object> version : versions) {
      long versionId = ((Number) version.get("id")).longValue();
      String recordTable = (String) version.get("record_table");
      String instanceTable = (String) version.get("instance_table");
      FormTemplateSpec spec =
          contextService.parseSpec((String) version.get("field_spec"), scope.templateKey());
      specHashByVersion.put(versionId, specHashOfVersion(versionId));
      List<String> columns = new ArrayList<>();
      for (FormFieldSpec field : spec.fields()) {
        columns.add(field.storage().resolvedColumn());
        if (field.storage().nameColumn() != null) {
          columns.add(field.storage().nameColumn());
        }
      }
      columnsByVersion.put(versionId, columns);
      String business =
          columns.stream().map(c -> "r." + c).reduce((a, b) -> a + ", " + b).orElse("null as placeholder");
      List<CopyRow> copies = jdbcTemplate.query(
          "select r.id, r.instance_id, r.origin_version_id, r.record_version, r.deleted,"
              + " r.created_by, r.request_key, r.create_payload_hash, r.created_at, r.updated_at,"
              + " i.source_config_id, i.source_instance, i.gitlab_base_url,"
              + " i.project_id, i.resource_type, i.resource_id, i.template_key,"
              + business + " from " + recordTable + " r join " + instanceTable
              + " i on i.id = r.instance_id order by r.id",
          (rs, rowNum) -> {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String column : columns) {
              values.put(column, FormEvidenceCodec.canonical(rs.getObject(column)));
            }
            long recordId = rs.getLong("id");
            return new CopyRow(versionId, recordId, rs.getLong("instance_id"),
                rs.getLong("origin_version_id"), rs.getInt("record_version"),
                rs.getBoolean("deleted"), rs.getString("created_by"), rs.getString("request_key"),
                rs.getString("create_payload_hash"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getLong("source_config_id"), rs.getString("source_instance"),
                canonicalRowUrl(recordId, rs.getString("gitlab_base_url")),
                rs.getLong("project_id"), rs.getString("resource_type"),
                rs.getString("resource_id"), rs.getString("template_key"), values);
          });
      for (CopyRow copy : copies) {
        byRecord.computeIfAbsent(copy.recordId(), key -> new ArrayList<>()).add(copy);
      }
    }
    return assembleAnalysis(scope, bound, byRecord, columnsByVersion, specHashByVersion);
  }


  private CarrierAnalysis assembleAnalysis(
      ScopeInfo scope,
      FormScopeValidationService.CoverageScope bound,
      Map<Long, List<CopyRow>> byRecord,
      Map<Long, List<String>> columnsByVersion,
      Map<Long, String> specHashByVersion) {
    ArrayNode carrierReport = objectMapper.createArrayNode();
    ArrayNode fieldLossReport = objectMapper.createArrayNode();
    List<CarrierRecord> carriers = new ArrayList<>();
    List<String> ownershipViolations = new ArrayList<>();
    List<String> evidenceViolations = new ArrayList<>();
    Long carrierVersionId = null;
    Map<String, List<Long>> requestKeyOwners = new LinkedHashMap<>();
    List<Long> projects = bound.projectIds();
    for (Map.Entry<Long, List<CopyRow>> entry : byRecord.entrySet()) {
      List<CopyRow> copies = entry.getValue();
      copies.sort(Comparator.comparingInt(CopyRow::recordVersion).reversed()
          .thenComparing(Comparator.comparing(CopyRow::updatedAt).reversed())
          .thenComparing(Comparator.comparingLong(CopyRow::versionId).reversed()));
      CopyRow carrier = copies.getFirst();
      Set<Long> originVersions = copies.stream().map(CopyRow::originVersionId)
          .collect(java.util.stream.Collectors.toSet());
      if (originVersions.size() > 1) {
        throw new FormConflictException(
            "记录 " + entry.getKey() + " 的副本origin_version不一致，停止登记");
      }
      for (CopyRow other : copies.stream().skip(1).toList()) {
        if (other.recordVersion() == carrier.recordVersion()
            && !sameBusinessValues(other, carrier, columnsByVersion)) {
          throw new FormConflictException("记录 " + entry.getKey()
              + " 的副本record_version相同但业务值不同，停止登记");
        }
      }
      requireInstanceBinding(carrier, scope, bound, ownershipViolations);
      if (carrier.requestKey() != null
          && (carrier.createPayloadHash() == null || carrier.createdBy() == null)) {
        evidenceViolations.add("记录 " + carrier.recordId()
            + " 存在request_key但首次指纹或账号证据缺失");
      }
      if (carrier.requestKey() != null) {
        requestKeyOwners.computeIfAbsent(carrier.requestKey(), key -> new ArrayList<>())
            .add(carrier.recordId());
      }
      List<String> loss = fieldLoss(carrier, copies, columnsByVersion);
      if (!loss.isEmpty()) {
        ObjectNode lossNode = fieldLossReport.addObject();
        lossNode.put("recordId", entry.getKey());
        lossNode.set("fields", objectMapper.valueToTree(loss));
      }
      ObjectNode reportNode = carrierReport.addObject();
      reportNode.put("recordId", entry.getKey());
      reportNode.put("carrierVersionId", carrier.versionId());
      reportNode.put("originVersionId", carrier.originVersionId());
      reportNode.put("copies", copies.size());
      carriers.add(toCarrierRecord(carrier, specHashByVersion));
      if (carrierVersionId == null) {
        carrierVersionId = carrier.versionId();
      } else if (carrierVersionId != carrier.versionId()) {
        carrierVersionId = -1L;
      }
    }
    if (!ownershipViolations.isEmpty()) {
      throw new FormConflictException("承载实例完整资源与范围绑定不一致，停止登记：差异数="
          + ownershipViolations.size()
          + "，前几条=" + ownershipViolations.stream().limit(10).toList());
    }
    if (!evidenceViolations.isEmpty()) {
      throw new FormConflictException("承载记录首次证据不完整，按409阻断：差异数="
          + evidenceViolations.size()
          + "，前几条=" + evidenceViolations.stream().limit(10).toList());
    }
    List<Long> duplicateRequestKeys = new ArrayList<>();
    for (Map.Entry<String, List<Long>> owners : requestKeyOwners.entrySet()) {
      if (owners.getValue().size() > 1) {
        duplicateRequestKeys.addAll(owners.getValue());
      }
    }
    if (!duplicateRequestKeys.isEmpty()) {
      throw new FormConflictException(
          "范围内部存在重复request_key：" + duplicateRequestKeys.stream().limit(10).toList());
    }
    long directoryRows = directoryRowCount(scope.scopeId());
    List<CarrierRecord> sorted = carriers.stream()
        .sorted(Comparator.comparingLong(CarrierRecord::recordId)).toList();
    return new CarrierAnalysis(
        carrierVersionId == null || carrierVersionId == -1L ? null : carrierVersionId,
        sorted, carrierReport, fieldLossReport, directoryRows,
        copiedEvidenceFingerprint(scope, allCopies(byRecord), specHashByVersion));
  }


  /** 实例的完整资源与来源必须落在本范围的真实绑定内，不允许由范围伪造。 */
  private void requireInstanceBinding(
      CopyRow carrier,
      ScopeInfo scope,
      FormScopeValidationService.CoverageScope bound,
      List<String> violations) {
    List<Long> projects = bound.projectIds();
    Long scopeConfigId = scope.sourceConfigId();
    if (scopeConfigId == null || carrier.sourceConfigId() != scopeConfigId) {
      violations.add("记录 " + carrier.recordId() + " 实例来源配置=" + carrier.sourceConfigId()
          + "，范围绑定=" + scopeConfigId);
    }
    if (!Objects.equals(carrier.sourceInstance(), scope.sourceInstance())) {
      violations.add("记录 " + carrier.recordId() + " 实例来源实例=" + carrier.sourceInstance()
          + "，范围绑定=" + scope.sourceInstance());
    }
    if (!Objects.equals(carrier.gitlabBaseUrl(), bound.canonicalUrl())) {
      violations.add("记录 " + carrier.recordId() + " 实例来源地址=" + carrier.gitlabBaseUrl()
          + "，范围绑定=" + bound.canonicalUrl());
    }
    if (!projects.contains(carrier.projectId())) {
      violations.add("记录 " + carrier.recordId() + " 实例项目=" + carrier.projectId()
          + "，范围项目集合=" + projects);
    }
    if (!Objects.equals(carrier.resourceType(), scope.resourceType())) {
      violations.add("记录 " + carrier.recordId() + " 实例资源类型=" + carrier.resourceType()
          + "，范围资源类型=" + scope.resourceType());
    }
    if (!Objects.equals(carrier.instanceTemplateKey(), scope.templateKey())) {
      violations.add("记录 " + carrier.recordId() + " 实例模板=" + carrier.instanceTemplateKey()
          + "，范围模板=" + scope.templateKey());
    }
  }

  private CarrierRecord toCarrierRecord(CopyRow carrier, Map<Long, String> specHashByVersion) {
    String originSpecHash = specHashByVersion.get(carrier.originVersionId());
    if (originSpecHash == null) {
      throw new FormConflictException("记录 " + carrier.recordId()
          + " 的origin版本规格证据缺失（ORIGIN_EVIDENCE_MISSING）");
    }
    return new CarrierRecord(
        carrier.recordId(), carrier.versionId(), carrier.originVersionId(), originSpecHash,
        carrier.instanceId(), carrier.sourceConfigId(), carrier.sourceInstance(),
        carrier.gitlabBaseUrl(), carrier.projectId(), carrier.resourceType(), carrier.resourceId(),
        carrier.requestKey(), carrier.createdBy(), carrier.createPayloadHash(),
        carrier.recordVersion(), carrier.deleted(), carrier.createdAt(), carrier.updatedAt(),
        carrier.values());
  }

  private boolean sameBusinessValues(
      CopyRow left, CopyRow right, Map<Long, List<String>> columnsByVersion) {
    List<String> columns = columnsByVersion.get(left.versionId());
    for (String column : columns) {
      if (!Objects.equals(left.values().get(column), right.values().get(column))) {
        return false;
      }
    }
    return true;
  }

  private List<String> fieldLoss(
      CopyRow carrier, List<CopyRow> copies, Map<Long, List<String>> columnsByVersion) {
    List<String> loss = new ArrayList<>();
    for (CopyRow copy : copies) {
      if (copy.versionId() == carrier.versionId()) {
        continue;
      }
      for (String column : columnsByVersion.get(copy.versionId())) {
        if (!columnsByVersion.get(carrier.versionId()).contains(column)
            && copy.values().get(column) != null) {
          loss.add(copy.versionId() + ":" + column);
        }
      }
    }
    return loss;
  }

  /** 路径 B 全值证据：覆盖每个参与承载/丢失判定的副本（含非承载旧副本）与其冻结规格。 */
  private String copiedEvidenceFingerprint(
      ScopeInfo scope, List<CopyRow> copies, Map<Long, String> specHashByVersion) {
    ObjectNode envelope = evidenceEnvelope(CARRIER_KIND_COPIED, scope);
    ArrayNode list = envelope.putArray("rows");
    List<CopyRow> sorted = new ArrayList<>(copies);
    sorted.sort(Comparator.comparingLong(CopyRow::recordId)
        .thenComparingLong(CopyRow::versionId));
    for (CopyRow copy : sorted) {
      ObjectNode node = evidence.row();
      evidence.putNumber(node, "recordId", copy.recordId());
      evidence.putNumber(node, "versionId", copy.versionId());
      evidence.putNumber(node, "instanceId", copy.instanceId());
      evidence.putNumber(node, "sourceConfigId", copy.sourceConfigId());
      evidence.putText(node, "sourceInstance", copy.sourceInstance());
      evidence.putText(node, "gitlabBaseUrl", copy.gitlabBaseUrl());
      evidence.putNumber(node, "projectId", copy.projectId());
      evidence.putText(node, "resourceType", copy.resourceType());
      evidence.putText(node, "resourceId", copy.resourceId());
      evidence.putText(node, "instanceTemplateKey", copy.instanceTemplateKey());
      evidence.putNumber(node, "originVersionId", copy.originVersionId());
      evidence.putText(node, "originSpecHash", specHashByVersion.get(copy.originVersionId()));
      evidence.putText(node, "storageSpecHash", specHashByVersion.get(copy.versionId()));
      evidence.putInteger(node, "recordVersion", copy.recordVersion());
      evidence.putBoolean(node, "deleted", copy.deleted());
      evidence.putText(node, "createdBy", copy.createdBy());
      evidence.putText(node, "requestKey", copy.requestKey());
      evidence.putText(node, "createPayloadHash", copy.createPayloadHash());
      evidence.putTime(node, "createdAt", copy.createdAt());
      evidence.putTime(node, "updatedAt", copy.updatedAt());
      ObjectNode values = node.putObject("values");
      copy.values().keySet().stream().sorted()
          .forEach(column -> evidence.putValue(values, column, copy.values().get(column)));
      list.add(node);
    }
    return evidence.fingerprint(envelope);
  }

  private List<CopyRow> allCopies(Map<Long, List<CopyRow>> byRecord) {
    List<CopyRow> copies = new ArrayList<>();
    byRecord.values().forEach(copies::addAll);
    return copies;
  }

  // ===== 统一范围绑定与持久完成证据（N1-R02/R01）=====

  /**
   * 复用统一范围校验判定本范围绑定是否合法。
   *
   * <p>ACTIVE 范围的绑定哈希、来源配置、项目集合、版本项目与当前指针，以及完整 DRAFT 范围的
   * 绑定哈希与来源，都由 {@link FormScopeValidationService} 同一实现判定；逐字段相等不能替代
   * 绑定合法性，本服务不另立一套平行规则。
   */
  private FormScopeValidationService.CoverageScope requireScopeBinding(ScopeInfo scope) {
    try {
      scopeValidationService.validateActiveScopes();
      return scopeValidationService
          .listCoverageScopes(scope.templateKey(), scope.resourceType())
          .stream()
          .filter(candidate -> candidate.scopeId() == scope.scopeId())
          .findFirst()
          .orElseThrow(() -> new FormConflictException(
              "范围绑定校验未通过（SCOPE_BINDING_INVALID）：scope=" + scope.scopeId()));
    } catch (IllegalStateException error) {
      throw new FormConflictException(
          "范围绑定校验未通过（SCOPE_BINDING_INVALID）：" + error.getMessage());
    }
  }

  /**
   * 核对首次登记的持久完成证据。
   *
   * <p>幂等只认可“当前源证据 == 首次登记时保存的证据”；源在登记后被编辑必须 409 并给出差异，
   * 不能用当前源哈希冒充历史登记哈希，也不补算或重新认定已登记。
   */
  private void requireCompletionEvidence(
      ScopeInfo scope, String currentFingerprint, String carrierKind) {
    String persisted = persistedCompletionFingerprint(scope.scopeId(), carrierKind);
    if (!persisted.equals(currentFingerprint)) {
      throw new FormConflictException("登记后源证据已变化（COMPLETION_EVIDENCE_CHANGED），"
          + "停止重入：持久完成哈希=" + shorten(persisted)
          + "，当前源证据哈希=" + shorten(currentFingerprint));
    }
  }

  private String persistedCompletionFingerprint(long scopeId, String carrierKind) {
    List<String> values = jdbcTemplate.queryForList(
        "select payload->>'rowHash' from form_directory_events"
            + " where scope_id = ? and event_type = 'LEGACY_REGISTERED'"
            + " and payload->>'carrierKind' = ? and payload->>'evidenceVersion' = ?"
            + " order by source_set_revision desc, id desc limit 1",
        String.class, scopeId, carrierKind, Integer.toString(EVIDENCE_VERSION));
    String persisted = values.isEmpty() ? null : values.getFirst();
    if (persisted == null || persisted.isBlank()) {
      throw new FormConflictException("登记完成证据缺失（COMPLETION_EVIDENCE_MISSING），停止重入");
    }
    return persisted;
  }

  private String shorten(String hash) {
    return hash == null || hash.length() <= 12 ? String.valueOf(hash) : hash.substring(0, 12);
  }

  // ===== 共用事务步骤 =====

  private int bumpSourceSet(long scopeId) {
    jdbcTemplate.update(
        "update form_template_scopes set source_set_revision = source_set_revision + 1,"
            + " source_set_hash = form_source_set_hash(id), updated_at = current_timestamp"
            + " where id = ?",
        scopeId);
    return scopeSourceSetRevision(scopeId);
  }

  private void insertRegistrationEvent(
      long scopeId, int revision, String eventType, String operatorId, String payloadJson) {
    jdbcTemplate.update(
        """
        insert into form_directory_events(
            scope_id, event_type, source_set_revision, payload, operator_id)
        values (?, ?, ?, ?::jsonb, ?)
        """,
        scopeId, eventType, revision, payloadJson, operatorId);
  }

  private String registrationPayload(
      long count, String rowHash, String carrierKind, JsonNode fieldLossReport) {
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("count", count);
    payload.put("rowHash", rowHash);
    payload.put("carrierKind", carrierKind);
    payload.put("evidenceVersion", EVIDENCE_VERSION);
    payload.set("fieldLossReport", fieldLossReport);
    return payload.toString();
  }

  private void upsertWatermark(String sourceRef, long highWater) {
    jdbcTemplate.update(
        """
        insert into form_record_id_watermarks(source_ref, high_water, updated_at)
        values (?, ?, current_timestamp)
        on conflict (source_ref) do update
          set high_water = greatest(form_record_id_watermarks.high_water, excluded.high_water),
              updated_at = current_timestamp
        """,
        sourceRef, highWater);
  }

  private void lockScopeRow(long scopeId) {
    jdbcTemplate.queryForList("select id from form_template_scopes where id = ? for update", scopeId);
  }

  private void lockScopeVersionTables(long scopeId) {
    List<String> tables = jdbcTemplate.queryForList(
        "select instance_table || ', ' || record_table from form_template_versions"
            + " where scope_id = ? and storage_kind = 'RECORDS' and record_table is not null",
        String.class, scopeId);
    if (tables.isEmpty()) {
      return;
    }
    jdbcTemplate.execute("lock table " + String.join(", ", tables) + " in share mode");
  }

  private ScopeInfo requireScope(long scopeId) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        """
        select id, template_key, source_config_id, source_instance, canonical_gitlab_base_url,
               project_ids::text as project_ids, resource_type, current_version_id
          from form_template_scopes where id = ?
        """,
        scopeId);
    if (rows.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "表单范围不存在");
    }
    Map<String, Object> row = rows.getFirst();
    return new ScopeInfo(
        scopeId,
        (String) row.get("template_key"),
        row.get("source_config_id") == null
            ? null : ((Number) row.get("source_config_id")).longValue(),
        (String) row.get("source_instance"),
        (String) row.get("canonical_gitlab_base_url"),
        (String) row.get("project_ids"),
        (String) row.get("resource_type"),
        row.get("current_version_id") == null
            ? null : ((Number) row.get("current_version_id")).longValue());
  }

  private int scopeSourceSetRevision(long scopeId) {
    Integer revision = jdbcTemplate.queryForObject(
        "select source_set_revision from form_template_scopes where id = ?", Integer.class, scopeId);
    return revision == null ? 0 : revision;
  }

  // ===== 数据载体 =====

  public record PrecheckReport(
      long count, String rowHash, JsonNode carrierReport, JsonNode fieldLossReport,
      boolean alreadyRegistered, String existingFingerprint) {}

  public record RegistrationResult(
      long versionId, long directoryRows, int sourceSetRevision, String status) {}

  public record LegacyRow(
      long id, String gitlabBaseUrl, long projectId, String resourceType, String resourceId,
      String reviewer, int reviewDurationMinutes, int specificationCount, int logicCount,
      int performanceCount, int designCount, int otherCount, String supplementNote,
      boolean deleted, LocalDateTime createdAt, LocalDateTime updatedAt) {}

  public record CarrierRecord(
      long recordId, long versionId, long originVersionId, String originSpecHash, long instanceId,
      long sourceConfigId, String sourceInstance, String gitlabBaseUrl, long projectId,
      String resourceType, String resourceId, String requestKey, String createdBy,
      String createPayloadHash, int recordVersion, boolean deleted, LocalDateTime createdAt,
      LocalDateTime updatedAt, Map<String, Object> values) {}

  private record ScopeInfo(
      long scopeId, String templateKey, Long sourceConfigId, String sourceInstance,
      String canonicalUrl, String projectIds, String resourceType, Long currentVersionId) {}

  private record CopyRow(
      long versionId, long recordId, long instanceId, long originVersionId, int recordVersion,
      boolean deleted, String createdBy, String requestKey, String createPayloadHash,
      LocalDateTime createdAt, LocalDateTime updatedAt, long sourceConfigId, String sourceInstance,
      String gitlabBaseUrl, long projectId, String resourceType, String resourceId,
      String instanceTemplateKey, Map<String, Object> values) {}

  private record CarrierAnalysis(
      Long carrierVersionId, List<CarrierRecord> carrierRecords, ArrayNode carrierReport,
      ArrayNode fieldLossReport, long directoryRowCount, String fingerprint) {}
}
