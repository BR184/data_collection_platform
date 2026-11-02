package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.domain.form.FormScopeBinding;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.domain.form.FormVersionSchema;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.FormCreateRequest;
import com.data.collection.platform.entity.form.FormCreateResponse;
import com.data.collection.platform.entity.form.FormRecordView;
import com.data.collection.platform.entity.form.FormUpdateRequest;
import com.data.collection.platform.entity.form.FormVoidRequest;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.service.form.FormComparableRegistrationService;
import com.data.collection.platform.service.form.FormRecordService;
import com.data.collection.platform.service.form.FormVersionSchemaService;
import com.data.collection.platform.service.read.FactReadVisibility;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * R02：MR 表单写入自动事实链、逐记录投影与候选证据的交错回归。
 *
 * <p>来源 ODS 在表单新增/编辑/作废过程中保持不变；写入桥推进 MERGE_REQUEST 事实 head，
 * 构建在读取走查记录前冻结并绑定 FORM 证据，公开事务复核后结算。读后写入交错时旧候选零公开零结算。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = {
      "platform.background-jobs.enabled=false",
      "platform.form-directory-event.worker-enabled=false",
      "platform.form-publication.worker-enabled=false",
      "platform.gitlab-mirror.scheduler-enabled=false",
      "platform.gitlab-mirror.delete-reconciliation-enabled=false",
      "platform.gitlab-mirror.customer-issue-delay-pre-writeback-sync-enabled=false",
      "platform.gitlab-mirror.customer-issue-delay-writeback-worker-enabled=false",
      "platform.gitlab-mirror.code-review-metric-enrichment-enabled=false",
      "platform.review-data.search-index-backfill-enabled=false",
      "platform.auth.provider=local",
      "platform.auth.secure-config-required=false"
    })
class FormMergeRequestFactPipelineIntegrationTest {
  private static final String GITLAB_URL = "http://gitlab.mr-chain.local";
  private static final long PROJECT_ID = 9L;
  private static final long MR_ROOT = 9001L;
  private static final long MR_IID = 501L;
  private static final String SOURCE_INSTANCE = "default";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  @DynamicPropertySource
  static void registerIsolatedDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.schemas", () -> "public");
    registry.add("spring.flyway.default-schema", () -> "public");
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private FormTemplateSpecCatalog specCatalog;
  @Autowired private FormVersionSchemaService schemaService;
  @Autowired private FormRecordService formRecordService;
  @Autowired private FormComparableRegistrationService comparableRegistrationService;
  @Autowired private FactBuildService factBuildService;
  @Autowired private FactTargetPublicationService factTargetPublicationService;
  @Autowired private GitlabConfigService configService;
  @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private SyncFactPublicationStateService qualification;

  private long sourceConfigId;
  private long scopeId;
  private long versionId;

  private final FormActorContext actor =
      new FormActorContext(
          "r02-actor",
          "R02",
          Set.of(
              PlatformPermissionCodes.FORM_RECORD_CREATE,
              PlatformPermissionCodes.FORM_RECORD_EDIT_OWN,
              PlatformPermissionCodes.FORM_RECORD_DELETE_OWN));

  @BeforeEach
  void setUp() {
    org.mockito.Mockito.doReturn(true)
        .when(qualification)
        .canBuildReadCandidate(anyString(), any(FactType.class));
    GitlabIssueMirrorFixture.ensureSchema(jdbcTemplate);
    jdbcTemplate.execute("truncate read_publications, read_publication_domains cascade");
    jdbcTemplate.execute("delete from form_review_fact");
    jdbcTemplate.execute("delete from merge_request_fact");
    jdbcTemplate.execute("delete from fact_change_heads");
    jdbcTemplate.execute("delete from form_publication_captures");
    jdbcTemplate.execute("delete from form_head_captures");
    jdbcTemplate.execute("delete from form_record_locations");
    jdbcTemplate.execute("delete from form_change_heads");
    jdbcTemplate.execute("delete from form_record_audit_logs");
    jdbcTemplate.execute("delete from form_field_comparable_registrations");
    jdbcTemplate.execute("delete from form_resource_authorities");
    dropFormDynamicTables();
    jdbcTemplate.execute("update form_template_scopes set status = 'DRAFT', current_version_id = null");
    jdbcTemplate.execute("delete from form_template_versions");
    jdbcTemplate.execute("delete from form_template_scopes");
    jdbcTemplate.execute("delete from ods_gitlab_merge_request_metrics");
    jdbcTemplate.execute("delete from ods_gitlab_merge_requests");
    jdbcTemplate.execute("delete from ods_gitlab_merge_request_reviewers");
    jdbcTemplate.execute("delete from ods_gitlab_merge_request_assignees");
    jdbcTemplate.execute("delete from ods_gitlab_label_links");
    jdbcTemplate.execute("delete from ods_gitlab_labels");
    jdbcTemplate.execute("delete from ods_gitlab_users");
    jdbcTemplate.execute("delete from ods_gitlab_projects");
    jdbcTemplate.execute("delete from ods_gitlab_namespaces");
    jdbcTemplate.execute("delete from gitlab_sync_configs where name like 'r02-%'");
    jdbcTemplate.update(
        "insert into platform_ldap_users(user_id, real_name) values ('r02-reviewer', '走查人')"
            + " on conflict (user_id) do update set real_name = excluded.real_name");
    seedSource();
    seedFormScope();
    FactRevisionFixture.adoptSeededFacts(
        jdbcTemplate, com.data.collection.platform.entity.read.PublicationFactTable.MERGE_REQUEST);
  }

  @Test
  void formAddEditVoidDrivesMergeRequestFactAndProjectionWithoutChangingOds() {
    LocalDateTime odsUpdatedAt =
        jdbcTemplate.queryForObject(
            "select updated_at from ods_gitlab_merge_requests where id = ?",
            LocalDateTime.class,
            MR_ROOT);

    FormRecordView created =
        formRecordService
            .create(codeReviewRequest(UUID.randomUUID().toString(), 10, 2, true), actor, "127.0.0.1", "JUnit")
            .record();
    assertMergeRequestFactHeadPending();
    rebuildAndPublish();
    assertFact(10, 2, 2);
    assertThat(formReviewFactCount()).isEqualTo(1);

    FormRecordView edited =
        formRecordService.update(
            created.id(),
            updateRequest(created.recordVersion(), 20, 3),
            actor,
            "127.0.0.1",
            "JUnit");
    rebuildAndPublish();
    assertFact(20, 3, 3);
    assertThat(formReviewFactCount()).isEqualTo(1);

    formRecordService.voidRecord(
        edited.id(),
        voidRequest(edited.recordVersion()),
        actor,
        "127.0.0.1",
        "JUnit");
    rebuildAndPublish();
    assertThat(formReviewFactCount()).isZero();
    assertFormAuthority("FORM");
    Map<String, Object> voided = liveFact();
    assertThat(voided.get("review_duration_minutes")).isNull();
    assertThat(voided.get("code_specification_count")).isNull();
    assertThat(voided.get("defect_count")).isNull();

    assertThat(
            jdbcTemplate.queryForObject(
                "select updated_at from ods_gitlab_merge_requests where id = ?",
                LocalDateTime.class,
                MR_ROOT))
        .as("表单新增/编辑/作废不修改来源 ODS")
        .isEqualTo(odsUpdatedAt);
  }

  @Test
  void writeAfterEvidenceCapturePublishesAndSettlesNothing() {
    FormRecordView created =
        formRecordService
            .create(codeReviewRequest(UUID.randomUUID().toString(), 10, 2, true), actor, "127.0.0.1", "JUnit")
            .record();
    factBuildService.rebuildMergeRequestFactByIid(SOURCE_INSTANCE, PROJECT_ID, MR_IID);

    formRecordService.update(
        created.id(),
        updateRequest(created.recordVersion(), 20, 3),
        actor,
        "127.0.0.1",
        "JUnit");

    assertThatThrownBy(this::publishPreparedCandidate)
        .as("读后写入交错必须让旧候选零公开零结算")
        .isInstanceOf(FormConflictException.class);

    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from merge_request_fact f where f.source_instance = ?"
                    + " and f.project_id = ? and f.merge_request_iid = ? and "
                    + FactReadVisibility.liveRevisionPredicate("f"),
                Long.class,
                SOURCE_INSTANCE,
                PROJECT_ID,
                MR_IID))
        .as("源集合没有已公开的 MR 事实修订")
        .isZero();
    Map<String, Object> head =
        jdbcTemplate.queryForMap(
            "select latest_change_version, consumed_change_version from form_change_heads"
                + " where resource_type = 'merge_request' and resource_id = ?",
            MR_IID);
    assertThat(((Number) head.get("consumed_change_version")).longValue())
        .as("旧候选失败不得推进表单消费水位")
        .isLessThan(((Number) head.get("latest_change_version")).longValue());
  }

  @Test
  void rootIncrementalAndFullEntriesFreezeAndBindMrEvidence() {
    formRecordService.create(
        codeReviewRequest(UUID.randomUUID().toString(), 10, 2, true), actor, "127.0.0.1", "JUnit");

    buildInStableTransaction(
        target ->
            factBuildService.rebuildMergeRequestFactsByRootIds(target, List.of(MR_ROOT)));
    assertActiveCandidateCaptured();
    publishPreparedCandidate();

    jdbcTemplate.update(
        "update ods_gitlab_merge_requests set updated_at = now() where id = ?", MR_ROOT);
    buildInStableTransaction(
        target ->
            factBuildService.rebuildMergeRequestFactsForQueuedTask(
                configService.getConfig(), target, false, FactBuildProgress.NO_OP));
    assertActiveCandidateCaptured();
    publishPreparedCandidate();

    buildInStableTransaction(
        target ->
            factBuildService.rebuildMergeRequestFactsForQueuedTask(
                configService.getConfig(), target, true, FactBuildProgress.NO_OP));
    assertActiveCandidateCaptured();
    publishPreparedCandidate();
  }

  private void buildInStableTransaction(
      java.util.function.Function<
              com.data.collection.platform.entity.read.FactRevisionTarget,
              com.data.collection.platform.entity.FactBuildResponse>
          body) {
    org.springframework.transaction.support.TransactionTemplate transaction =
        new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    transaction.setIsolationLevel(
        org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    transaction.executeWithoutResult(
        status ->
            factTargetPublicationService.publishWithinCandidate(
                new com.data.collection.platform.service.read.BypassPublicationRequest(
                    ReadPublicationDomainKey.gitlabFact(
                        SOURCE_INSTANCE, FactType.MERGE_REQUEST),
                    "GITLAB",
                    "{\"seal\":\"r02-mr-chain\"}",
                    0,
                    null,
                    null,
                    java.time.Instant.now(),
                    false),
                body::apply));
  }

  private void assertActiveCandidateCaptured() {
    Long captures =
        jdbcTemplate.queryForObject(
            """
            select count(*) from form_publication_captures c
             join read_publications p on p.id = c.publication_id
             join read_publication_domains d on d.id = p.domain_id
             where d.fact_type = 'MERGE_REQUEST' and d.source_instance = ?
               and p.state in ('BUILDING', 'PROJECTING', 'READY_TO_PUBLISH')
               and c.status in ('CAPTURED', 'CONSUMED')
            """,
            Long.class,
            SOURCE_INSTANCE);
    assertThat(captures).as("MR 构建读取记录前必须绑定 FORM 证据").isPositive();
  }

  private void rebuildAndPublish() {
    factBuildService.rebuildMergeRequestFactByIid(SOURCE_INSTANCE, PROJECT_ID, MR_IID);
    publishPreparedCandidate();
  }

  private void publishPreparedCandidate() {
    FactRevisionFixture.completeRequiredProjections(
        jdbcTemplate,
        factTargetPublicationService,
        ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.MERGE_REQUEST),
        null);
  }

  private void assertMergeRequestFactHeadPending() {
    Map<String, Object> head =
        jdbcTemplate.queryForMap(
            "select latest_change_version, published_version from fact_change_heads"
                + " where source_instance = ? and fact_type = 'MERGE_REQUEST' and root_id = ?",
            SOURCE_INSTANCE,
            MR_ROOT);
    assertThat(((Number) head.get("latest_change_version")).longValue())
        .as("ODS 未变化时表单写入也必须登记 MR 事实待发布变化")
        .isGreaterThan(((Number) head.get("published_version")).longValue());
  }

  private void assertFact(int duration, int specification, int defect) {
    Map<String, Object> fact = liveFact();
    assertThat(fact.get("review_duration_minutes")).isEqualTo(duration);
    assertThat(fact.get("code_specification_count")).isEqualTo(specification);
    assertThat(fact.get("defect_count")).isEqualTo(defect);
    assertThat(fact.get("review_status")).isEqualTo("COMPLETED");
    assertThat(fact.get("reviewer_names")).isEqualTo("走查人");
    assertFormAuthority("FORM");
  }

  private void assertFormAuthority(String expected) {
    assertThat(
            jdbcTemplate.queryForObject(
                "select authority.form_authority from merge_request_fact_authority authority"
                    + " join merge_request_fact f on f.id = authority.merge_request_revision_id"
                    + " where f.source_instance = ? and f.project_id = ?"
                    + " and f.merge_request_iid = ? and "
                    + FactReadVisibility.liveRevisionPredicate("f"),
                String.class,
                SOURCE_INSTANCE,
                PROJECT_ID,
                MR_IID))
        .isEqualTo(expected);
  }

  private Map<String, Object> liveFact() {
    return jdbcTemplate.queryForMap(
        "select f.review_duration_minutes, f.code_specification_count, f.defect_count,"
            + " f.review_status, f.reviewer_names, f.reviewer_names"
            + " from merge_request_fact f where f.source_instance = ? and f.project_id = ?"
            + " and f.merge_request_iid = ? and "
            + FactReadVisibility.liveRevisionPredicate("f"),
        SOURCE_INSTANCE,
        PROJECT_ID,
        MR_IID);
  }

  private long formReviewFactCount() {
    return jdbcTemplate.queryForObject(
        "select count(*) from form_review_fact r"
            + " join merge_request_fact f on f.id = r.merge_request_revision_id"
            + " where f.source_instance = ? and f.project_id = ? and f.merge_request_iid = ?"
            + " and " + FactReadVisibility.liveRevisionPredicate("f"),
        Long.class,
        SOURCE_INSTANCE,
        PROJECT_ID,
        MR_IID);
  }

  private void dropFormDynamicTables() {
    List<String> tables =
        jdbcTemplate.queryForList(
            "select tablename from pg_tables where schemaname = 'public'"
                + " and (tablename like 'form\\_s%\\_records' or tablename like 'form\\_s%\\_instances')",
            String.class);
    for (String table : tables) {
      jdbcTemplate.execute("drop table if exists " + table + " cascade");
    }
  }

  private FormCreateRequest codeReviewRequest(
      String key, int duration, int specification, boolean confirmSameReviewer) {
    return new FormCreateRequest(
        GITLAB_URL,
        PROJECT_ID,
        "merge_request",
        Long.toString(MR_IID),
        FormTemplateSpecCatalog.CODE_REVIEW,
        Long.toString(scopeId),
        Long.toString(versionId),
        key,
        confirmSameReviewer,
        toFields(codeReviewFields(duration, specification)));
  }

  private FormUpdateRequest updateRequest(int recordVersion, int duration, int specification) {
    return new FormUpdateRequest(
        GITLAB_URL,
        PROJECT_ID,
        "merge_request",
        Long.toString(MR_IID),
        FormTemplateSpecCatalog.CODE_REVIEW,
        Long.toString(scopeId),
        Long.toString(versionId),
        Long.toString(versionId),
        recordVersion,
        toFields(codeReviewFields(duration, specification)));
  }

  private FormVoidRequest voidRequest(int recordVersion) {
    return new FormVoidRequest(
        GITLAB_URL,
        PROJECT_ID,
        "merge_request",
        Long.toString(MR_IID),
        FormTemplateSpecCatalog.CODE_REVIEW,
        Long.toString(scopeId),
        Long.toString(versionId),
        Long.toString(versionId),
        recordVersion,
        true,
        "作废");
  }

  private ObjectNode codeReviewFields(int duration, int specification) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("reviewerUserId", "r02-reviewer");
    node.put("reviewDurationMinutes", duration);
    node.put("specificationCount", specification);
    node.put("logicCount", 0);
    node.put("performanceCount", 0);
    node.put("designCount", 0);
    node.put("otherCount", 0);
    node.put("supplementNote", "");
    return node;
  }

  private void seedSource() {
    jdbcTemplate.update(
        "insert into ods_gitlab_namespaces(id, path, mirror_deleted) values (1, 'team', false)");
    jdbcTemplate.update(
        "insert into ods_gitlab_projects(id, name, path, namespace_id, mirror_deleted)"
            + " values (?, 'project', 'project', 1, false)",
        PROJECT_ID);
    jdbcTemplate.update(
        "insert into ods_gitlab_users(id, name, mirror_deleted) values (1, '作者', false)");
    LocalDateTime updatedAt = LocalDateTime.of(2026, 10, 20, 10, 0);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_merge_requests(
            id, iid, target_project_id, title, author_id, merge_user_id, target_branch,
            source_branch, state_id, created_at, updated_at, mirror_deleted)
        values (?, ?, ?, 'form review', 1, 1, 'main', 'feature', 3, ?, ?, false)
        """,
        MR_ROOT,
        MR_IID,
        PROJECT_ID,
        Timestamp.valueOf(updatedAt.minusDays(1)),
        Timestamp.valueOf(updatedAt));
    jdbcTemplate.update(
        "insert into ods_gitlab_merge_request_metrics(merge_request_id, merged_at,"
            + " merged_by_id, added_lines, mirror_deleted) values (?, ?, 1, 100, false)",
        MR_ROOT,
        Timestamp.valueOf(updatedAt));
    sourceConfigId =
        jdbcTemplate.queryForObject(
            """
            insert into gitlab_sync_configs(
                name, db_name, db_username, db_password, web_base_url, whitelist_mode,
                whitelist_tables)
            values ('r02-mr-chain', 'qaflex', 'qaflex', 'secret', ?, 'CUSTOM', '["merge_requests"]')
            returning id
            """,
            Long.class,
            GITLAB_URL);
  }

  private void seedFormScope() {
    scopeId =
        jdbcTemplate.queryForObject(
            """
            insert into form_template_scopes(template_key, scope_key, resource_type, status)
            values ('code_review', 'r02-mr-chain', 'merge_request', 'DRAFT')
            returning id
            """,
            Long.class);
    versionId = createVersionAndTables();
    String canonical = com.data.collection.platform.domain.form.FormSourceUrlSupport.canonicalize(GITLAB_URL);
    ObjectNode binding =
        FormScopeBinding.create(
            objectMapper,
            FormTemplateSpecCatalog.CODE_REVIEW,
            sourceConfigId,
            SOURCE_INSTANCE,
            canonical,
            List.of(PROJECT_ID),
            PROJECT_ID,
            "merge_request");
    jdbcTemplate.update(
        """
        update form_template_scopes
           set source_config_id = ?, source_instance = ?, canonical_gitlab_base_url = ?,
               project_ids = ?::jsonb, version_project_id = ?, current_version_id = ?,
               status = 'ACTIVE', binding_hash = ?, binding_revision = 1,
               source_set_revision = 1, source_set_hash = form_source_set_hash(id), updated_at = ?
         where id = ?
        """,
        sourceConfigId,
        SOURCE_INSTANCE,
        canonical,
        "[" + PROJECT_ID + "]",
        PROJECT_ID,
        versionId,
        FormScopeBinding.hash(objectMapper, binding),
        Timestamp.valueOf(LocalDateTime.now()),
        scopeId);
    jdbcTemplate.update(
        """
        insert into form_resource_authorities(
            source_config_id, source_instance, canonical_gitlab_base_url, project_id,
            resource_type, resource_id, template_key, scope_id, authority, authority_revision,
            effective_at, created_at, updated_at)
        values (?, ?, ?, ?, 'merge_request', ?, 'code_review', ?, 'FORM', 1, now(), now(), now())
        """,
        sourceConfigId,
        SOURCE_INSTANCE,
        canonical,
        PROJECT_ID,
        MR_IID,
        scopeId);
    comparableRegistrationService.ensureVersionDeclarations(scopeId, versionId, null);
  }

  private long createVersionAndTables() {
    long newVersionId =
        jdbcTemplate.queryForObject("select nextval('form_template_versions_id_seq')", Long.class);
    String instanceTable = "form_s" + scopeId + "_v" + newVersionId + "_instances";
    String recordTable = "form_s" + scopeId + "_v" + newVersionId + "_records";
    FormTemplateSpec spec = specCatalog.require(FormTemplateSpecCatalog.CODE_REVIEW);
    FormVersionSchema schema = schemaService.generate(scopeId, newVersionId, spec, false);
    for (String statement : schema.statements()) {
      jdbcTemplate.execute(statement);
    }
    jdbcTemplate.update(
        """
        insert into form_template_versions(
            id, scope_id, version_label, storage_kind, instance_table, record_table,
            field_spec, version_status, published_at)
        values (?, ?, 'MR-CHAIN-V1', 'RECORDS', ?, ?, ?::jsonb, 'PUBLISHED', ?)
        """,
        newVersionId,
        scopeId,
        instanceTable,
        recordTable,
        specCatalog.rawJson(FormTemplateSpecCatalog.CODE_REVIEW),
        Timestamp.valueOf(LocalDateTime.now()));
    return newVersionId;
  }

  private static Map<String, com.fasterxml.jackson.databind.JsonNode> toFields(ObjectNode node) {
    Map<String, com.fasterxml.jackson.databind.JsonNode> fields = new java.util.LinkedHashMap<>();
    node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
    return fields;
  }
}
