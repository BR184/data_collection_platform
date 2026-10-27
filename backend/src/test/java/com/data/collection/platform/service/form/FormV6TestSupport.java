package com.data.collection.platform.service.form;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.domain.form.FormVersionSchema;
import com.data.collection.platform.service.GitlabResourceLinkService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** N1 隔离真实 PG 测试支撑：容器迁移、服务手工装配与代表性夹具。 */
final class FormV6TestSupport {

  private FormV6TestSupport() {}

  static JdbcTemplate migrate(org.testcontainers.containers.PostgreSQLContainer<?> postgres) {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    return new JdbcTemplate(dataSource);
  }

  record WiredServices(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      FormRecordContextService contextService,
      FormFieldValueService fieldValueService,
      FormRecordStore store,
      FormRecordViewFactory viewFactory,
      FormRecordDirectoryService directoryService,
      FormRecordDirectoryReader reader,
      FormV6RegistrationService registrationService,
      FormComparableRegistrationService comparableService,
      FormRecordWriteService writeService,
      TransactionTemplate tx) {}

  static WiredServices wire(JdbcTemplate jdbc) {
    ObjectMapper objectMapper = new ObjectMapper();
    FormScopeValidationService scopeValidation =
        new FormScopeValidationService(jdbc, objectMapper);
    FormRecordContextService contextService =
        new FormRecordContextService(
            jdbc,
            objectMapper,
            scopeValidation,
            new GitlabResourceLinkService(jdbc, new GitlabMirrorProperties()));
    FormFieldValueService fieldValueService =
        new FormFieldValueService(objectMapper, new FormReviewerDirectoryService(jdbc));
    FormRecordStore store = new FormRecordStore(jdbc, objectMapper);
    FormRecordViewFactory viewFactory = new FormRecordViewFactory();
    FormRecordDirectoryService directoryService =
        new FormRecordDirectoryService(jdbc, contextService, fieldValueService, store, viewFactory);
    FormRecordDirectoryReader reader = new FormRecordDirectoryReader(jdbc, contextService);
    FormVersionSchemaService schemaService = new FormVersionSchemaService(objectMapper);
    FormV6RegistrationService registrationService =
        new FormV6RegistrationService(
            jdbc,
            objectMapper,
            contextService,
            scopeValidation,
            schemaService,
            new FormVersionStructureVerifier(jdbc),
            reader);
    FormComparableRegistrationService comparableService =
        new FormComparableRegistrationService(jdbc, contextService, objectMapper);
    FormRecordWriteService writeService =
        new FormRecordWriteService(
            jdbc, contextService, store, viewFactory, directoryService, ClockStub.SYSTEM);
    DataSourceTransactionManager transactionManager =
        new DataSourceTransactionManager(jdbc.getDataSource());
    return new WiredServices(
        jdbc,
        objectMapper,
        contextService,
        fieldValueService,
        store,
        viewFactory,
        directoryService,
        reader,
        registrationService,
        comparableService,
        writeService,
        new TransactionTemplate(transactionManager));
  }

  /** 固定时钟由各测试按需注入；此处提供可控 Clock 包装。 */
  static final class ClockStub extends java.time.Clock {
    static final ClockStub SYSTEM = new ClockStub();

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneId.systemDefault();
    }

    @Override
    public java.time.Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public java.time.Instant instant() {
      return java.time.Instant.now();
    }
  }

  static final String SPEC_V1 =
      """
      {"spec_version":1,"template_key":"code_review","conditions":[],"dictionaries":[],
       "fields":[
        {"name":"得分","apiKey":"score","type":"integer","required":false,
         "storage":{"column":"score","columnType":"integer"}},
        {"name":"补充","apiKey":"note","type":"string","required":false,
         "storage":{"column":"note_text","columnType":"varchar(200)"}}]}
      """;

  /** v2 规格删除 note 字段并调整得分语义，用于字段丢失/重放证据用例。 */
  static final String SPEC_V2 =
      """
      {"spec_version":1,"template_key":"code_review","conditions":[],"dictionaries":[],
       "fields":[
        {"name":"得分","apiKey":"score","type":"integer","required":false,
         "storage":{"column":"score","columnType":"integer"}}]}
      """;

  static long insertSourceConfig(JdbcTemplate jdbc, String webBaseUrl) {
    return jdbc.queryForObject(
        """
        insert into gitlab_sync_configs(name, db_name, db_username, db_password, web_base_url,
                                        source_instance)
        values ('n1-test-' || nextval('form_record_id_seq')::text, 'qaflex', 'qaflex', 'secret', ?,
                'n1-src-' || nextval('form_record_id_seq')::text)
        returning id
        """,
        Long.class, webBaseUrl);
  }

  record FixtureTables(
      long versionId, String instanceTable, String recordTable, String ddlHash) {}

  private static final ObjectMapper SHARED_MAPPER = new ObjectMapper();

  /**
   * 用正式生成器建版本物理结构，并用正式核验器确认零漂移后再登记夹具。
   *
   * <p>版本主键先取 {@code form_template_versions_id_seq}，因为表名由版本 ID 决定；夹具不再手写
   * 简化 DDL，避免用缺少真实约束的表伪装正式结构。
   */
  static FixtureTables createRecordVersion(
      JdbcTemplate jdbc,
      long scopeId,
      String templateKey,
      String label,
      String fieldSpec,
      String versionStatus,
      boolean published) {
    FormTemplateSpec spec = parseSpec(fieldSpec, templateKey);
    long versionId =
        jdbc.queryForObject(
            "select nextval(pg_get_serial_sequence('form_template_versions', 'id'))", Long.class);
    FormVersionSchema schema =
        new FormVersionSchemaService(SHARED_MAPPER)
            .generate(scopeId, versionId, spec, false);
    jdbc.update(
        """
        insert into form_template_versions(
            id, scope_id, version_label, storage_kind, instance_table, record_table, field_spec,
            version_status, published_at)
        values (?, ?, ?, 'RECORDS', ?, ?, ?::jsonb, ?, ?)
        """,
        versionId,
        scopeId,
        label,
        schema.instanceTable(),
        schema.recordTable(),
        fieldSpec,
        versionStatus,
        published ? Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0)) : null);
    schema.statements().forEach(jdbc::execute);
    new FormVersionStructureVerifier(jdbc).verify(schema);
    return new FixtureTables(
        versionId, schema.instanceTable(), schema.recordTable(), schema.ddlHash());
  }

  /** 直接按冻结规格解析，夹具与生产使用同一规格契约。 */
  static FormTemplateSpec parseSpec(String fieldSpec, String templateKey) {
    try {
      return SHARED_MAPPER.readValue(fieldSpec, FormTemplateSpec.class);
    } catch (Exception error) {
      throw new IllegalStateException("测试规格无法解析", error);
    }
  }

  /** 按库中冻结规格重建期望结构，供负例先自证正式核验器确实报漂移。 */
  static FormVersionSchema formalSchema(
      JdbcTemplate jdbc, long scopeId, long versionId, String templateKey) {
    Map<String, Object> version = jdbc.queryForMap(
        "select field_spec::text as field_spec from form_template_versions where id = ?",
        versionId);
    return new FormVersionSchemaService(SHARED_MAPPER)
        .generate(scopeId, versionId, parseSpec((String) version.get("field_spec"), templateKey),
            false);
  }

  static long insertScope(
      JdbcTemplate jdbc,
      String templateKey,
      String scopeKey,
      long sourceConfigId,
      String canonicalUrl,
      long projectId,
      String status) {
    return insertScope(
        jdbc, templateKey, scopeKey, sourceConfigId, canonicalUrl, List.of(projectId),
        projectId, status);
  }

  /** 建范围：项目集合与版本项目可不同，绑定哈希始终按真实绑定推导。 */
  static long insertScope(
      JdbcTemplate jdbc,
      String templateKey,
      String scopeKey,
      long sourceConfigId,
      String canonicalUrl,
      List<Long> projectIds,
      long versionProjectId,
      String status) {
    String projects =
        projectIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
    return jdbc.queryForObject(
        """
        insert into form_template_scopes(
            template_key, scope_key, source_config_id, source_instance,
            canonical_gitlab_base_url, project_ids, version_project_id, resource_type,
            status, binding_hash)
        values (?, ?, ?, 'default', ?, ?::jsonb, ?, 'merge_request', ?, ?)
        returning id
        """,
        Long.class, templateKey, scopeKey, sourceConfigId, canonicalUrl,
        "[" + projects + "]", versionProjectId, status,
        bindingHash(templateKey, sourceConfigId, canonicalUrl, projectIds, versionProjectId));
  }

  /** 与 FormScopeValidationService.coverageBindingHash 相同的绑定哈希推导。 */
  private static String bindingHash(
      String templateKey,
      long sourceConfigId,
      String canonicalUrl,
      List<Long> projectIds,
      long versionProjectId) {
    ObjectMapper mapper = new ObjectMapper();
    return com.data.collection.platform.domain.form.FormScopeBinding.hash(
        mapper,
        com.data.collection.platform.domain.form.FormScopeBinding.create(
            mapper, templateKey, sourceConfigId, "default", canonicalUrl,
            projectIds, versionProjectId, "merge_request"));
  }

  static void activateScope(JdbcTemplate jdbc, long scopeId, long currentVersionId) {
    jdbc.update(
        "update form_template_scopes set status = 'ACTIVE', current_version_id = ?"
            + " where id = ?",
        currentVersionId, scopeId);
  }

  static long insertInstance(
      JdbcTemplate jdbc,
      String instanceTable,
      long scopeId,
      long sourceConfigId,
      String canonicalUrl,
      long projectId,
      long resourceId) {
    return insertInstance(
        jdbc, instanceTable, scopeId, sourceConfigId, "default", canonicalUrl, projectId,
        resourceId);
  }

  /** 显式指定来源实例与地址，用于跨来源/跨项目的不一致实例负例。 */
  static long insertInstance(
      JdbcTemplate jdbc,
      String instanceTable,
      long scopeId,
      long sourceConfigId,
      String sourceInstance,
      String canonicalUrl,
      long projectId,
      long resourceId) {
    return jdbc.queryForObject(
        "insert into " + instanceTable
            + "(scope_id, source_config_id, source_instance, gitlab_base_url, project_id,"
            + " resource_type, resource_id, template_key, created_at, updated_at)"
            + " values (?, ?, ?, ?, ?, 'merge_request', ?, 'code_review', ?, ?) returning id",
        Long.class, scopeId, sourceConfigId, sourceInstance, canonicalUrl, projectId, resourceId,
        Timestamp.valueOf(LocalDateTime.of(2026, 2, 1, 8, 0)),
        Timestamp.valueOf(LocalDateTime.of(2026, 2, 1, 8, 0)));
  }

  static long insertRecord(
      JdbcTemplate jdbc,
      String recordTable,
      long instanceId,
      long originVersionId,
      String createdBy,
      String requestKey,
      String payloadHash,
      int recordVersion,
      boolean deleted,
      String score,
      String note) {
    List<String> columns = new java.util.ArrayList<>(List.of(
        "instance_id", "origin_version_id", "template_version", "created_by", "request_key",
        "create_payload_hash", "record_version", "deleted", "created_at", "updated_at"));
    List<Object> args = new java.util.ArrayList<>();
    java.util.Collections.addAll(args, instanceId, originVersionId, "v-test", createdBy,
        requestKey, payloadHash, recordVersion, deleted);
    args.add(Timestamp.valueOf(LocalDateTime.of(2026, 2, 1, 8, 0)));
    args.add(Timestamp.valueOf(LocalDateTime.of(2026, 2, 1, 8, 0)));
    if (score != null) {
      columns.add("score");
      args.add(Integer.valueOf(score));
    }
    if (note != null) {
      columns.add("note_text");
      args.add(note);
    }
    String placeholders = String.join(", ", java.util.Collections.nCopies(columns.size(), "?"));
    return jdbc.queryForObject(
        "insert into " + recordTable + " (" + String.join(", ", columns) + ")"
            + " values (" + placeholders + ") returning id",
        Long.class, args.toArray());
  }

  /** 直接从数据行构造版本上下文（绕过 resolve 的平台依赖），上下文数据与夹具一致。 */
  static FormVersionContext contextFor(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      FormRecordContextService contextService,
      long scopeId,
      long versionId,
      long projectId,
      long resourceId) {
    Map<String, Object> scope = jdbc.queryForMap(
        "select status, source_config_id, source_instance, canonical_gitlab_base_url"
            + " from form_template_scopes where id = ?", scopeId);
    Map<String, Object> version = jdbc.queryForMap(
        "select version_label, version_status, instance_table, record_table,"
            + " field_spec::text as field_spec from form_template_versions where id = ?", versionId);
    String raw = (String) version.get("field_spec");
    return new FormVersionContext(
        scopeId,
        (String) scope.get("status"),
        ((Number) scope.get("source_config_id")).longValue(),
        (String) scope.get("source_instance"),
        (String) scope.get("canonical_gitlab_base_url"),
        versionId,
        (String) version.get("version_label"),
        (String) version.get("version_status"),
        (String) version.get("instance_table"),
        (String) version.get("record_table"),
        contextService.parseSpec(raw, "code_review"),
        contextService.readFieldSpec(raw),
        projectId,
        "merge_request",
        resourceId,
        900L,
        null,
        "http://web/" + projectId + "/" + resourceId);
  }

  static List<Map<String, Object>> directoryRows(JdbcTemplate jdbc, long scopeId) {
    return jdbc.queryForList(
        "select * from form_record_locations where scope_id = ? order by record_id", scopeId);
  }

  /** 旧 Demo 表真实列夹具：值全部可指定，作废与时间轴参与守恒核验。 */
  static void insertLegacyRow(
      JdbcTemplate jdbc,
      long id,
      String gitlabBaseUrl,
      long projectId,
      long resourceId,
      String reviewer,
      int duration,
      int specification,
      int logic,
      int performance,
      int design,
      int other,
      String remark,
      boolean deleted) {
    jdbc.update(
        """
        insert into collect_form_records(
            id, gitlab_base_url, project_id, request_iid, resource_type, resource_id,
            template_code, reviewer, review_duration_minutes, specification_score, logic_score,
            performance_score, design_score, other_score, remark, deleted, created_at, updated_at)
        values (?, ?, ?, ?, 'merge_request', ?, 'code_review', ?, ?, ?, ?, ?, ?, ?, ?, ?,
                current_timestamp, current_timestamp)
        """,
        id, gitlabBaseUrl, projectId, resourceId, Long.toString(resourceId), reviewer, duration,
        specification, logic, performance, design, other, remark, deleted);
  }

  /** 两条代表性旧 Demo 记录：不同走查人与分值，供选集/守恒/读取用例复用。 */
  static void insertLegacyPair(
      JdbcTemplate jdbc, long firstId, long secondId, String gitlabBaseUrl, long projectId) {
    insertLegacyRow(jdbc, firstId, gitlabBaseUrl, projectId, firstId,
        "Alice", 30, 1, 2, 3, 4, 5, "legacy", false);
    insertLegacyRow(jdbc, secondId, gitlabBaseUrl, projectId, secondId,
        "Bob", 45, 6, 7, 8, 9, 10, "second", false);
  }
}
