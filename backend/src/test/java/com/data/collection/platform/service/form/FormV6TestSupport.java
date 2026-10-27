package com.data.collection.platform.service.form;

import com.data.collection.platform.config.GitlabMirrorProperties;
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
    FormV6RegistrationService registrationService =
        new FormV6RegistrationService(jdbc, objectMapper, contextService, scopeValidation);
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

  record FixtureTables(String instanceTable, String recordTable) {}

  private static final ObjectMapper SHARED_MAPPER = new ObjectMapper();

  static FixtureTables createRecordVersion(
      JdbcTemplate jdbc,
      long scopeId,
      String templateKey,
      String label,
      String fieldSpec,
      String versionStatus,
      boolean published) {
    Long versionId = jdbc.queryForObject(
        """
        insert into form_template_versions(
            scope_id, version_label, storage_kind, instance_table, record_table, field_spec,
            version_status, published_at)
        values (?, ?, 'RECORDS', ?, ?, ?::jsonb, ?, ?)
        returning id
        """,
        Long.class, scopeId, label, tableName(scopeId, label, "instances"),
        tableName(scopeId, label, "records"), fieldSpec, versionStatus,
        published ? Timestamp.valueOf(LocalDateTime.of(2026, 1, 1, 0, 0)) : null);
    String instanceTable = tableName(scopeId, label, "instances");
    String recordTable = tableName(scopeId, label, "records");
    jdbc.execute(
        "create table " + instanceTable + " ("
            + " id bigserial primary key,"
            + " scope_id bigint not null,"
            + " source_config_id bigint not null,"
            + " source_instance varchar(128) not null,"
            + " gitlab_base_url varchar(255) not null,"
            + " project_id bigint not null,"
            + " resource_type varchar(64) not null,"
            + " resource_id bigint not null,"
            + " template_key varchar(64) not null,"
            + " created_at timestamp not null default current_timestamp,"
            + " updated_at timestamp not null default current_timestamp,"
            + " unique (scope_id, source_config_id, project_id, resource_type, resource_id))");
    StringBuilder business = new StringBuilder();
    try {
      var spec = SHARED_MAPPER.readTree(fieldSpec);
      for (var field : spec.path("fields")) {
        String column = field.path("storage").path("column").asText(null);
        String type = field.path("storage").path("columnType").asText(null);
        if (column != null && type != null) {
          business.append(", ").append(column).append(" ").append(type);
        }
      }
    } catch (Exception error) {
      throw new IllegalStateException("测试规格无法解析", error);
    }
    jdbc.execute(
        "create table " + recordTable + " ("
            + " id bigserial primary key,"
            + " instance_id bigint not null,"
            + " origin_version_id bigint not null,"
            + " template_version varchar(64),"
            + " created_by varchar(128),"
            + " request_key varchar(64),"
            + " create_payload_hash char(64),"
            + " record_version integer not null default 1,"
            + " deleted boolean not null default false,"
            + " legacy_incomplete boolean not null default false,"
            + " legacy_missing_fields text[] not null default '{}',"
            + " created_at timestamp not null default current_timestamp,"
            + " updated_at timestamp not null default current_timestamp"
            + business
            + ", unique (request_key))");
    return new FixtureTables(instanceTable, recordTable);
  }

  static String tableName(long scopeId, String label, String kind) {
    String version = label.replaceAll("[^0-9]", "");
    return "form_s" + scopeId + "_v" + version + "_" + kind;
  }

  static long insertScope(
      JdbcTemplate jdbc,
      String templateKey,
      String scopeKey,
      long sourceConfigId,
      String canonicalUrl,
      long projectId,
      String status) {
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
        "[" + projectId + "]", projectId, status, bindingHash(templateKey, sourceConfigId,
            canonicalUrl, projectId));
  }

  /** 与 FormScopeValidationService.coverageBindingHash 相同的绑定哈希推导。 */
  private static String bindingHash(
      String templateKey, long sourceConfigId, String canonicalUrl, long projectId) {
    ObjectMapper mapper = new ObjectMapper();
    return com.data.collection.platform.domain.form.FormScopeBinding.hash(
        mapper,
        com.data.collection.platform.domain.form.FormScopeBinding.create(
            mapper, templateKey, sourceConfigId, "default", canonicalUrl,
            List.of(projectId), projectId, "merge_request"));
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
    return jdbc.queryForObject(
        "insert into " + instanceTable
            + "(scope_id, source_config_id, source_instance, gitlab_base_url, project_id,"
            + " resource_type, resource_id, template_key)"
            + " values (?, ?, 'default', ?, ?, 'merge_request', ?, 'code_review') returning id",
        Long.class, scopeId, sourceConfigId, canonicalUrl, projectId, resourceId);
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
}
