package com.data.collection.platform.service.form;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.common.response.ResultCode;
import com.data.collection.platform.domain.form.FormFieldSpec;
import com.data.collection.platform.domain.form.FormHashSupport;
import com.data.collection.platform.domain.form.FormSourceUrlSupport;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
 * <p>登记只复制历史值，不改写原业务行、原 {@code field_spec}、origin/首次指纹/创建归属/
 * 时间；重复归属、未覆盖、证据缺失、副本值冲突或结构漂移整体拒绝，提交后纠正只追加
 * {@code REGISTRATION_CORRECTED} 修订。precheck 与 register 使用同一选集与行哈希实现，
 * 源在两阶段之间变化即中止。本服务不自动执行：调用方必须先确认旧写已停止且选集稳定，
 * 并只对本任务隔离库调用。
 */
@Service
public class FormV6RegistrationService {

  /** 旧 Demo 固定语义规格：唯一语义来源是旧列；登记后冻结，不随后续规格变化。 */
  public static final String LEGACY_DEMO_FIELD_SPEC =
      """
      {"spec_version":1,"template_key":"code_review","conditions":[],"dictionaries":[],
       "fields":[
        {"name":"走查人","apiKey":"reviewerUserId","type":"string","required":false,
         "storage":{"column":"reviewer_name","columnType":"varchar(128)"}},
        {"name":"走查时间","apiKey":"reviewDurationMinutes","type":"integer","required":false,
         "storage":{"column":"review_duration_minutes","columnType":"integer"}},
        {"name":"规范问题数","apiKey":"specificationCount","type":"integer","required":false,
         "storage":{"column":"specification_count","columnType":"integer"}},
        {"name":"逻辑问题数","apiKey":"logicCount","type":"integer","required":false,
         "storage":{"column":"logic_count","columnType":"integer"}},
        {"name":"性能问题数","apiKey":"performanceCount","type":"integer","required":false,
         "storage":{"column":"performance_count","columnType":"integer"}},
        {"name":"设计问题数","apiKey":"designCount","type":"integer","required":false,
         "storage":{"column":"design_count","columnType":"integer"}},
        {"name":"其他问题数","apiKey":"otherCount","type":"integer","required":false,
         "storage":{"column":"other_count","columnType":"integer"}},
        {"name":"补充说明","apiKey":"supplementNote","type":"string","required":false,
         "storage":{"column":"supplement_note","columnType":"text"}}]}
      """;

  private static final String SEQ_LOCK = "select pg_advisory_xact_lock(hashtext('form_record_id_seq')::bigint)";

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final FormRecordContextService contextService;
  private final FormScopeValidationService scopeValidationService;

  public FormV6RegistrationService(
      JdbcTemplate jdbcTemplate,
      ObjectMapper objectMapper,
      FormRecordContextService contextService,
      FormScopeValidationService scopeValidationService) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.contextService = contextService;
    this.scopeValidationService = scopeValidationService;
  }

  /** 路径 A precheck：只读选集、行哈希与既有登记指纹。 */
  public PrecheckReport precheckLegacyDemo(long scopeId) {
    ScopeInfo scope = requireScope(scopeId);
    List<LegacyRow> selection = selectLegacyRows(scope);
    String rowHash = legacyRowHash(selection);
    String existingFingerprint = existingLegacyFingerprint(scopeId, rowHash, selection.size());
    return new PrecheckReport(selection.size(), rowHash, null, null,
        existingFingerprint != null, existingFingerprint);
  }

  /**
   * 路径 A 登记：新建 LEGACY/NONE 版本并按源行原位登记目录，不伪造实例/账号/修订。
   *
   * <p>单事务失败全回滚；同源同哈希重入返回既有结果且不增修订，源变化或证据漂移 409。
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
    jdbcTemplate.execute("lock table collect_form_records in share mode");
    lockScopeRow(scopeId);
    List<LegacyRow> selection = selectLegacyRows(scope);
    String rowHash = legacyRowHash(selection);
    requirePrecheckMatch(selection.size(), rowHash, expectedCount, expectedRowHash);
    String existingFingerprint = existingLegacyFingerprint(scopeId, rowHash, selection.size());
    if (existingFingerprint != null) {
      if (!"MATCH".equals(existingFingerprint)) {
        throw new FormConflictException("已存在不一致的旧Demo登记指纹，停止重复登记");
      }
      Long existingVersion = firstOrNull(jdbcTemplate.queryForList(
          "select id from form_template_versions where scope_id = ? and instance_mode = 'NONE'"
              + " and source_table = 'collect_form_records'",
          Long.class, scopeId));
      return new RegistrationResult(existingVersion, selection.size(),
          scopeSourceSetRevision(scopeId), "ALREADY_REGISTERED");
    }
    if (selection.isEmpty()) {
      throw new BizException(ResultCode.BAD_REQUEST, "旧Demo选集为空，无登记对象");
    }
    long versionId = insertLegacyVersion(scopeId, versionLabel);
    long sourceConfigId = requireSourceConfigId(scope.canonicalUrl());
    String originSpecHash = jdbcTemplate.queryForObject(
        "select form_spec_hash(?::jsonb)::char(64)", String.class, LEGACY_DEMO_FIELD_SPEC);
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
        scopeId, versionId, versionId, sourceConfigId, GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
        scope.canonicalUrl(), originSpecHash,
        selection.stream().map(LegacyRow::id).toArray(Long[]::new));
    if (inserted != selection.size()) {
      throw new FormConflictException("目录登记数不守恒：expected=" + selection.size()
          + ", inserted=" + inserted);
    }
    assertBackTableHits(scopeId, versionId, selection.size());
    int revision = bumpSourceSet(scopeId);
    insertRegistrationEvent(scopeId, revision, "LEGACY_REGISTERED", operatorId,
        registrationPayload(selection.size(), rowHash, "LEGACY_DEMO_IN_PLACE",
            objectMapper.createArrayNode()));
    long highWater = jdbcTemplate.queryForObject(
        "select coalesce(max(id), 0) from collect_form_records", Long.class);
    jdbcTemplate.queryForObject("select form_advance_record_sequence(?)", Long.class, highWater);
    upsertWatermark("collect_form_records", highWater);
    if (scope.currentVersionId() == null) {
      jdbcTemplate.update(
          "update form_template_scopes set current_version_id = ? where id = ? and"
              + " current_version_id is null",
          versionId, scopeId);
    }
    return new RegistrationResult(versionId, selection.size(), revision, "REGISTERED");
  }

  /** 路径 B precheck：只读全部副本分组、承载判定与证据完整性报告。 */
  public PrecheckReport precheckCopiedRecords(long scopeId) {
    ScopeInfo scope = requireScope(scopeId);
    CarrierAnalysis analysis = analyzeCopies(scope);
    return new PrecheckReport(analysis.carrierRecords().size(), analysis.fingerprint(),
        analysis.carrierReport(), analysis.fieldLossReport(), analysis.alreadyRegistered(), null);
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
    ScopeInfo fresh = requireScope(scopeId);
    CarrierAnalysis analysis = analyzeCopies(fresh);
    requirePrecheckMatch(analysis.carrierRecords().size(), analysis.fingerprint(),
        expectedCount, expectedFingerprint);
    if (analysis.alreadyRegistered()) {
      return new RegistrationResult(analysis.carrierVersionId(), analysis.carrierRecords().size(),
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
    for (CarrierRecord carrier : analysis.carrierRecords()) {
      if (carrier.requestKey() != null
          && (carrier.createPayloadHash() == null || carrier.createdBy() == null)) {
        throw new FormConflictException("记录 " + carrier.recordId()
            + " 存在request_key但首次指纹或账号证据缺失，按409阻断");
      }
    }
    for (Long recordId : analysis.duplicateRequestKeys()) {
      throw new FormConflictException("范围内部存在重复request_key：" + recordId);
    }
    jdbcTemplate.update(
        "update form_template_versions set record_source_role = 'LEGACY_SNAPSHOT'"
            + " where scope_id = ? and id <> ?",
        scopeId, analysis.carrierVersionId());
    String originSpecHashExpr =
        "select form_spec_hash(v.field_spec)::char(64) from form_template_versions v where v.id =";
    int inserted = 0;
    for (CarrierRecord carrier : analysis.carrierRecords()) {
      String originSpecHash = jdbcTemplate.queryForObject(
          originSpecHashExpr + " ?", String.class, carrier.originVersionId());
      inserted += jdbcTemplate.update(
          """
          insert into form_record_locations(
              record_id, scope_id, storage_version_id, origin_version_id, instance_mode, instance_id,
              source_config_id, source_instance, gitlab_base_url, project_id, resource_type,
              resource_id, request_key, created_by, origin_spec_hash, create_payload_hash, created_at)
          values (?, ?, ?, ?, 'MATERIALIZED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          on conflict (record_id) do nothing
          """,
          carrier.recordId(), scopeId, analysis.carrierVersionId(), carrier.originVersionId(),
          carrier.instanceId(), fresh.sourceConfigId(), fresh.sourceInstance(), fresh.canonicalUrl(),
          carrier.projectId(), carrier.resourceType(), carrier.resourceId(),
          carrier.requestKey(), carrier.createdBy(), originSpecHash,
          carrier.createPayloadHash(), Timestamp.valueOf(carrier.createdAt()));
    }
    if (inserted != analysis.carrierRecords().size()) {
      throw new FormConflictException("目录登记数不守恒：expected=" + analysis.carrierRecords().size()
          + ", inserted=" + inserted);
    }
    assertBackTableHits(scopeId, analysis.carrierVersionId(), analysis.carrierRecords().size());
    int revision = bumpSourceSet(scopeId);
    insertRegistrationEvent(scopeId, revision, "LEGACY_REGISTERED", operatorId,
        registrationPayload(analysis.carrierRecords().size(), analysis.fingerprint(),
            "V5_CARRIER_IN_PLACE", analysis.fieldLossReport()));
    long highWater = jdbcTemplate.queryForObject(
        "select coalesce(max(record_id), 0) from form_record_locations where scope_id = ?",
        Long.class, scopeId);
    jdbcTemplate.queryForObject("select form_advance_record_sequence(?)", Long.class, highWater);
    upsertWatermark("form_scope_" + scopeId, highWater);
    return new RegistrationResult(analysis.carrierVersionId(), analysis.carrierRecords().size(),
        revision, "REGISTERED");
  }

  /** 幂等重入许可：当前版本已是本范围的 LEGACY/NONE 登记版本。 */
  private boolean isRegisteredLegacyVersion(ScopeInfo scope) {
    return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
        "select exists(select 1 from form_template_versions where scope_id = ? and id = ?"
            + " and instance_mode = 'NONE' and source_table = 'collect_form_records')",
        Boolean.class, scope.scopeId(), scope.currentVersionId()));
  }

  /** precheck 与 register 之间源变化即中止（§3.2 发布前后业务值变化即中止）。 */
  private void requirePrecheckMatch(
      int actualCount, String actualHash, long expectedCount, String expectedHash) {
    if (actualCount != expectedCount || !actualHash.equals(expectedHash)) {
      throw new FormConflictException("precheck与register之间源已变化，停止登记：count="
          + actualCount + "/" + expectedCount);
    }
  }

  // ===== 路径 A 选集与哈希 =====

  private List<LegacyRow> selectLegacyRows(ScopeInfo scope) {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        """
        select id, gitlab_base_url, project_id, resource_type, resource_id, reviewer,
               review_duration_minutes, specification_score, logic_score, performance_score,
               design_score, other_score, remark, created_at
          from collect_form_records
         order by id
        """);
    List<Long> projects = parseProjects(scope.projectIds());
    List<LegacyRow> selected = new ArrayList<>();
    List<Long> uncovered = new ArrayList<>();
    List<Long> duplicated = new ArrayList<>();
    for (Map<String, Object> raw : rows) {
      long id = ((Number) raw.get("id")).longValue();
      String rawUrl = (String) raw.get("gitlab_base_url");
      long projectId = ((Number) raw.get("project_id")).longValue();
      long otherOwners = scopeOwners(scope.scopeId(), rawUrl, projectId);
      boolean belongsToCurrent = belongsTo(scope, rawUrl, projectId, projects);
      if (otherOwners > 0) {
        duplicated.add(id);
      } else if (!belongsToCurrent) {
        uncovered.add(id);
      } else {
        selected.add(toLegacyRow(id, raw, rawUrl, projectId));
      }
    }
    if (!duplicated.isEmpty()) {
      throw new FormConflictException("旧Demo记录存在重复归属，停止登记；前几条="
          + duplicated.stream().limit(10).toList());
    }
    if (!uncovered.isEmpty()) {
      throw new FormConflictException("旧Demo记录未被已登记范围覆盖，停止登记；前几条="
          + uncovered.stream().limit(10).toList());
    }
    return selected;
  }

  private boolean belongsTo(ScopeInfo scope, String rawUrl, long projectId, List<Long> projects) {
    String canonical = FormSourceUrlSupport.canonicalize(rawUrl);
    return canonical.equals(scope.canonicalUrl()) && projects.contains(projectId);
  }

  /** 当前范围之外的覆盖方数量；归属冲突按整体拒绝处理。 */
  private long scopeOwners(long selfScopeId, String rawUrl, long projectId) {
    long owners = 0;
    String canonical;
    try {
      canonical = FormSourceUrlSupport.canonicalize(rawUrl);
    } catch (IllegalArgumentException error) {
      throw new FormConflictException("旧记录 GitLab 基址非法：" + rawUrl);
    }
    for (FormScopeValidationService.CoverageScope scope
        : scopeValidationService.listCoverageScopes("code_review", "merge_request")) {
      if (scope.scopeId() != selfScopeId && scope.covers(canonical, projectId)) {
        owners++;
      }
    }
    return owners;
  }

  private LegacyRow toLegacyRow(
      long id, Map<String, Object> raw, String rawUrl, long projectId) {
    Timestamp createdAt = (Timestamp) raw.get("created_at");
    return new LegacyRow(
        id,
        FormSourceUrlSupport.canonicalize(rawUrl),
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
        createdAt.toLocalDateTime());
  }

  private String legacyRowHash(List<LegacyRow> rows) {
    List<String> lines = new ArrayList<>(rows.size());
    for (LegacyRow row : rows) {
      lines.add(String.join("\u0001",
          Long.toString(row.id()), row.gitlabBaseUrl(), Long.toString(row.projectId()),
          row.resourceType(), row.resourceId(), nullToEmpty(row.reviewer()),
          Integer.toString(row.reviewDurationMinutes()), Integer.toString(row.specificationCount()),
          Integer.toString(row.logicCount()), Integer.toString(row.performanceCount()),
          Integer.toString(row.designCount()), Integer.toString(row.otherCount()),
          nullToEmpty(row.supplementNote()), row.createdAt().toString()));
    }
    return FormHashSupport.sha256Hex(String.join("\n", lines));
  }

  /** 已有登记指纹：MATCH=完全一致可幂等重入；其它值=存在登记但证据不同。 */
  private String existingLegacyFingerprint(long scopeId, String rowHash, int count) {
    Long existingVersion = firstOrNull(jdbcTemplate.queryForList(
        "select id from form_template_versions where scope_id = ? and instance_mode = 'NONE'"
            + " and source_table = 'collect_form_records'",
        Long.class, scopeId));
    if (existingVersion == null) {
      Long rows = jdbcTemplate.queryForObject(
          "select count(*) from form_record_locations where scope_id = ? and instance_mode = 'NONE'",
          Long.class, scopeId);
      if (rows != null && rows > 0) {
        throw new FormConflictException("存在无版本归属的NONE目录行，停止登记");
      }
      return null;
    }
    Long directoryCount = jdbcTemplate.queryForObject(
        "select count(*) from form_record_locations where scope_id = ? and storage_version_id = ?",
        Long.class, scopeId, existingVersion);
    List<String> hashes = jdbcTemplate.queryForList(
        """
        select payload->>'rowHash' from form_directory_events
         where scope_id = ? and event_type = 'LEGACY_REGISTERED'
         order by source_set_revision desc limit 1
        """,
        String.class, scopeId);
    String existingHash = hashes.isEmpty() ? null : hashes.getFirst();
    boolean same = directoryCount != null && directoryCount == count
        && rowHash.equals(existingHash);
    return same ? "MATCH" : "DIFFERENT";
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
                'AUTHORITATIVE', 'NONE', 'collect_form_records')
        returning id
        """,
        Long.class, scopeId, versionLabel, LEGACY_DEMO_FIELD_SPEC);
  }

  // ===== 路径 B 副本分析 =====

  private CarrierAnalysis analyzeCopies(ScopeInfo scope) {
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
    Map<Long, String> specByVersion = new LinkedHashMap<>();
    Map<Long, List<String>> columnsByVersion = new LinkedHashMap<>();
    for (Map<String, Object> version : versions) {
      long versionId = ((Number) version.get("id")).longValue();
      String recordTable = (String) version.get("record_table");
      String instanceTable = (String) version.get("instance_table");
      String specRaw = (String) version.get("field_spec");
      specByVersion.put(versionId, specRaw);
      FormTemplateSpec spec = contextService.parseSpec(specRaw, scope.templateKey());
      List<String> columns = new ArrayList<>();
      for (FormFieldSpec field : spec.fields()) {
        columns.add(field.storage().resolvedColumn());
        if (field.storage().nameColumn() != null) {
          columns.add(field.storage().nameColumn());
        }
      }
      columnsByVersion.put(versionId, columns);
      List<CopyRow> copies = jdbcTemplate.query(
          "select r.id, r.instance_id, r.origin_version_id, r.record_version, r.deleted,"
              + " r.created_by, r.request_key, r.create_payload_hash, r.created_at, r.updated_at,"
              + " i.project_id, i.resource_type, i.resource_id,"
              + columns.stream().map(c -> "r." + c).reduce((a, b) -> a + ", " + b).orElse("null as placeholder")
              + " from " + recordTable + " r join " + instanceTable
              + " i on i.id = r.instance_id order by r.id",
          (rs, rowNum) -> {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String column : columns) {
              Object value = rs.getObject(column);
              values.put(column, value == null ? null : String.valueOf(value));
            }
            return new CopyRow(versionId, rs.getLong("id"), rs.getLong("instance_id"),
                rs.getLong("origin_version_id"), rs.getInt("record_version"),
                rs.getBoolean("deleted"), rs.getString("created_by"), rs.getString("request_key"),
                rs.getString("create_payload_hash"),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime(),
                rs.getLong("project_id"), rs.getString("resource_type"),
                rs.getString("resource_id"), values);
          });
      for (CopyRow copy : copies) {
        byRecord.computeIfAbsent(copy.recordId(), key -> new ArrayList<>()).add(copy);
      }
    }
    ArrayNode carrierReport = objectMapper.createArrayNode();
    ArrayNode fieldLossReport = objectMapper.createArrayNode();
    List<CarrierRecord> carriers = new ArrayList<>();
    Long carrierVersionId = null;
    Map<String, List<Long>> requestKeyOwners = new LinkedHashMap<>();
    for (Map.Entry<Long, List<CopyRow>> entry : byRecord.entrySet()) {
      List<CopyRow> copies = entry.getValue();
      copies.sort(Comparator.comparingInt(CopyRow::recordVersion).reversed()
          .thenComparing(Comparator.comparing(CopyRow::updatedAt).reversed())
          .thenComparing(Comparator.comparingLong(CopyRow::versionId).reversed()));
      CopyRow carrier = copies.getFirst();
      Set<Long> originVersions = copies.stream().map(CopyRow::originVersionId)
          .collect(java.util.stream.Collectors.toSet());
      if (originVersions.size() > 1) {
        throw new FormConflictException("记录 " + entry.getKey() + " 的副本origin_version不一致，停止登记");
      }
      for (CopyRow other : copies.stream().skip(1).toList()) {
        if (other.recordVersion() == carrier.recordVersion()
            && !sameBusinessValues(other, carrier, columnsByVersion)) {
          throw new FormConflictException("记录 " + entry.getKey()
              + " 的副本record_version相同但业务值不同，停止登记");
        }
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
      carriers.add(new CarrierRecord(entry.getKey(), carrier.versionId(),
          carrier.originVersionId(), carrier.instanceId(), carrier.projectId(),
          carrier.resourceType(), carrier.resourceId(), carrier.requestKey(),
          carrier.createdBy(), carrier.createPayloadHash(), carrier.createdAt()));
      if (carrierVersionId == null) {
        carrierVersionId = carrier.versionId();
      } else if (carrierVersionId != carrier.versionId()) {
        carrierVersionId = -1L;
      }
    }
    List<Long> duplicateRequestKeys = new ArrayList<>();
    for (Map.Entry<String, List<Long>> owners : requestKeyOwners.entrySet()) {
      if (owners.getValue().size() > 1) {
        duplicateRequestKeys.addAll(owners.getValue());
      }
    }
    Long registered = jdbcTemplate.queryForObject(
        "select count(*) from form_record_locations where scope_id = ? and instance_mode = 'MATERIALIZED'",
        Long.class, scope.scopeId());
    boolean alreadyRegistered = registered != null && registered > 0;
    List<CarrierRecord> fingerprintSource = carriers.stream()
        .sorted(Comparator.comparingLong(CarrierRecord::recordId)).toList();
    String fingerprint = carrierFingerprint(fingerprintSource, columnsByVersion);
    return new CarrierAnalysis(carrierVersionId == null || carrierVersionId == -1L
            ? null : carrierVersionId,
        carriers, duplicateRequestKeys, carrierReport, fieldLossReport,
        alreadyRegistered, fingerprint);
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

  private String carrierFingerprint(
      List<CarrierRecord> carriers, Map<Long, List<String>> columnsByVersion) {
    List<String> lines = new ArrayList<>(carriers.size());
    for (CarrierRecord carrier : carriers) {
      lines.add(String.join("\u0001",
          Long.toString(carrier.recordId()), Long.toString(carrier.originVersionId()),
          Long.toString(carrier.versionId()), nullToEmpty(carrier.requestKey()),
          nullToEmpty(carrier.createdBy()), nullToEmpty(carrier.createPayloadHash()),
          carrier.createdAt().toString()));
    }
    return FormHashSupport.sha256Hex(String.join("\n", lines));
  }

  // ===== 共用事务步骤 =====

  private void assertBackTableHits(long scopeId, long versionId, long expected) {
    Long directoryRows = jdbcTemplate.queryForObject(
        "select count(*) from form_record_locations where scope_id = ? and storage_version_id = ?",
        Long.class, scopeId, versionId);
    if (directoryRows == null || directoryRows != expected) {
      throw new FormConflictException("目录行数与登记选集不守恒：directory=" + directoryRows
          + ", expected=" + expected);
    }
  }

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
        insert into form_directory_events(scope_id, event_type, source_set_revision, payload, operator_id)
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
               project_ids::text as project_ids, current_version_id
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
        row.get("current_version_id") == null
            ? null : ((Number) row.get("current_version_id")).longValue());
  }

  private long requireSourceConfigId(String canonicalUrl) {
    List<Map<String, Object>> configs = jdbcTemplate.queryForList(
        "select id, web_base_url from gitlab_sync_configs order by id");
    for (Map<String, Object> config : configs) {
      Object webBase_url = config.get("web_base_url");
      if (webBase_url == null) {
        continue;
      }
      try {
        if (canonicalUrl.equals(FormSourceUrlSupport.canonicalize(webBase_url.toString()))) {
          return ((Number) config.get("id")).longValue();
        }
      } catch (IllegalArgumentException ignored) {
        // 非法来源配置不参与匹配。
      }
    }
    throw new BizException(ResultCode.BAD_REQUEST, "来源地址未登记，无法登记旧Demo");
  }

  private int scopeSourceSetRevision(long scopeId) {
    Integer revision = jdbcTemplate.queryForObject(
        "select source_set_revision from form_template_scopes where id = ?", Integer.class, scopeId);
    return revision == null ? 0 : revision;
  }

  private List<Long> parseProjects(String projectIdsJson) {
    try {
      JsonNode node = objectMapper.readTree(projectIdsJson);
      List<Long> projects = new ArrayList<>();
      node.forEach(item -> {
        if (item.canConvertToLong()) {
          projects.add(item.asLong());
        }
      });
      return projects;
    } catch (Exception error) {
      throw new FormConflictException("范围项目集合无法解析");
    }
  }

  private Long firstOrNull(List<Long> values) {
    return values.isEmpty() ? null : values.getFirst();
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
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
      LocalDateTime createdAt) {}

  private record ScopeInfo(
      long scopeId, String templateKey, Long sourceConfigId, String sourceInstance,
      String canonicalUrl, String projectIds, Long currentVersionId) {}

  private record CopyRow(
      long versionId, long recordId, long instanceId, long originVersionId, int recordVersion,
      boolean deleted, String createdBy, String requestKey, String createPayloadHash,
      LocalDateTime createdAt, LocalDateTime updatedAt, long projectId, String resourceType,
      String resourceId, Map<String, Object> values) {}

  public record CarrierRecord(
      long recordId, long versionId, long originVersionId, long instanceId, long projectId,
      String resourceType, String resourceId, String requestKey, String createdBy,
      String createPayloadHash, LocalDateTime createdAt) {}

  private record CarrierAnalysis(
      Long carrierVersionId, List<CarrierRecord> carrierRecords, List<Long> duplicateRequestKeys,
      ArrayNode carrierReport, ArrayNode fieldLossReport, boolean alreadyRegistered,
      String fingerprint) {}
}
