package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.domain.form.FormVersionSchema;
import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.FormCreateRequest;
import com.data.collection.platform.entity.form.FormCreateResponse;
import com.data.collection.platform.entity.form.FormDetailResponse;
import com.data.collection.platform.entity.form.FormRecordView;
import com.data.collection.platform.entity.form.FormVoidRequest;
import com.data.collection.platform.entity.form.admin.FormAdminViews;
import com.data.collection.platform.entity.form.admin.FormBindingRequest;
import com.data.collection.platform.entity.form.admin.FormDraftUpdateRequest;
import com.data.collection.platform.entity.form.admin.FormPublicationCreateRequest;
import com.data.collection.platform.entity.form.admin.FormScopeCreateRequest;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.service.GitlabResourceLinkService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = {
      "platform.background-jobs.enabled=false",
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
class FormTemplateAdminIntegrationTest {

  private static final String GITLAB_URL = "http://gitlab.w4.local";
  private static final long PROJECT_ID = 9L;
  private static final long ISSUE_IID = 21L;
  private static final long ISSUE_ROOT = 9101L;

  private static final Set<String> ALL_PERMISSIONS =
      Set.of(
          PlatformPermissionCodes.FORM_TEMPLATE_MANAGE,
          PlatformPermissionCodes.FORM_RECORD_CREATE,
          PlatformPermissionCodes.FORM_RECORD_EDIT_OWN,
          PlatformPermissionCodes.FORM_RECORD_DELETE_OWN);

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
  @Autowired private FormTemplateAdminService adminService;
  @Autowired private FormPublicationService publicationService;
  @Autowired private FormPublicationTxService publicationTxService;
  @Autowired private FormVersionSchemaService versionSchemaService;
  @Autowired private FormRecordService formRecordService;
  @Autowired private FormTemplateSpecCatalog specCatalog;
  @Autowired private GitlabResourceLinkService resourceLinkService;

  private final FormActorContext admin =
      new FormActorContext("w4-admin", "管理员", ALL_PERMISSIONS);

  private long sourceConfigId;

  @BeforeEach
  void setUp() {
    dropCopyFailure();
    dropDynamicTables();
    jdbcTemplate.execute("truncate table form_template_scopes restart identity cascade");
    jdbcTemplate.execute("delete from gitlab_sync_configs where name like 'w4-form-%'");
    jdbcTemplate.execute("delete from platform_ldap_users where user_id like 'w4-%'");
    jdbcTemplate.execute("select setval('form_record_id_seq', 1, false)");
    ensureOdsTables();
    jdbcTemplate.execute("delete from ods_gitlab_issues");
    jdbcTemplate.execute("delete from ods_gitlab_milestones");
    jdbcTemplate.update(
        "insert into ods_gitlab_namespaces(id, path, mirror_deleted)"
            + " values (1, 'team', false)"
            + " on conflict (id) do update set path = excluded.path, mirror_deleted = false");
    jdbcTemplate.update(
        "insert into ods_gitlab_projects(id, path, namespace_id, mirror_deleted)"
            + " values (?, 'example', 1, false)"
            + " on conflict (id) do update set path = excluded.path,"
            + " namespace_id = excluded.namespace_id, mirror_deleted = false",
        PROJECT_ID);
    jdbcTemplate.update(
        "insert into ods_gitlab_issues(id, project_id, iid, mirror_deleted) values (?, ?, ?, false)",
        ISSUE_ROOT,
        PROJECT_ID,
        ISSUE_IID);
    jdbcTemplate.update(
        "delete from issue_fact where issue_id = ?", ISSUE_ROOT);
    jdbcTemplate.update(
        """
        insert into issue_fact(
            source_system, source_instance, project_id, issue_id, issue_iid, title,
            severity_level, deleted)
        values ('GITLAB', 'default', ?, ?, ?, '', 'LEVEL1', false)
        """,
        PROJECT_ID,
        ISSUE_ROOT,
        ISSUE_IID);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_milestones(id, title, project_id, mirror_deleted)
        values (1001, 'CC2026R1', ?, false), (1002, 'CC2026R2', ?, false)
        """,
        PROJECT_ID,
        PROJECT_ID);
    resourceLinkService.clearCache();
    sourceConfigId =
        jdbcTemplate.queryForObject(
            """
            insert into gitlab_sync_configs(name, db_name, db_username, db_password, web_base_url)
            values ('w4-form-source', 'qaflex', 'qaflex', 'secret', ?)
            returning id
            """,
            Long.class,
            GITLAB_URL);
  }

  @Test
  void shouldActivateEmptyScopeAndCreateRecordsThroughPublishedVersion() {
    long scopeId = createScope("empty-activation");
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "CC2026R1");
    FormAdminViews.PreviewView preview = adminService.preview(scopeId, currentRevision(scopeId), admin);
    assertThat(preview.blockingErrors()).isEmpty();
    assertThat(preview.targetVersionId()).isPositive();

    FormPublicationCreateRequest request =
        new FormPublicationCreateRequest(
            currentRevision(scopeId), preview.id(), null, UUID.randomUUID().toString());
    FormAdminViews.PublicationView created =
        adminService.createPublication(scopeId, request, admin);
    assertThat(created.publicationKind()).isEqualTo("INITIAL_EMPTY");
    assertThat(created.stage()).isEqualTo("PREPARED");
    FormAdminViews.PublicationView replay =
        adminService.createPublication(scopeId, request, admin);
    assertThat(replay.id()).isEqualTo(created.id());

    assertThat(publicationService.executeOnce(created.id())).isTrue();
    FormAdminViews.PublicationView published = publicationService.publicationView(created.id());
    assertThat(published.stage()).isEqualTo("PUBLISHED");

    FormAdminViews.PublicationView afterPublished =
        adminService.createPublication(scopeId, request, admin);
    assertThat(afterPublished.id()).isEqualTo(created.id());
    assertThat(afterPublished.stage()).isEqualTo("PUBLISHED");
    assertThatThrownBy(
            () ->
                adminService.createPublication(
                    scopeId,
                    new FormPublicationCreateRequest(
                        request.draftRevision(),
                        request.previewId() + 1,
                        request.expectedCurrentVersionId(),
                        request.requestKey()),
                    admin))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("相同请求标识对应不同发布内容");

    FormAdminViews.ScopeDetail detail = adminService.scope(scopeId);
    assertThat(detail.scope().status()).isEqualTo("ACTIVE");
    assertThat(detail.scope().currentVersionId()).isEqualTo(created.toVersionId());
    assertThat(detail.scope().writeFenced()).isFalse();
    assertThat(detail.recordCount()).isZero();

    FormCreateResponse record = createIssueReply(scopeId, created.toVersionId());
    assertThat(record.outcome()).isEqualTo("CREATED");
    assertThat(adminService.scope(scopeId).recordCount()).isEqualTo(1);
  }

  @Test
  void shouldCASDraftAndFreezeBindingAfterActivation() {
    long scopeId = createScope("cas-binding");
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "CC2026R1");
    long revision = currentRevision(scopeId);
    assertThatThrownBy(
            () ->
                adminService.updateDraft(
                    scopeId,
                    new FormDraftUpdateRequest(
                        revision - 1,
                        seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY),
                        null,
                        "CC2026R1",
                        evidence(),
                        null),
                    admin))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("草稿已被他人修改");

    publish(scopeId, "CC2026R1");
    assertThat(adminService.scope(scopeId).scope().status()).isEqualTo("ACTIVE");

    assertThatThrownBy(
            () ->
                adminService.updateDraft(
                    scopeId,
                    new FormDraftUpdateRequest(
                        currentRevision(scopeId),
                        seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY),
                        new FormBindingRequest(sourceConfigId, List.of(PROJECT_ID + 1), PROJECT_ID + 1),
                        "CC2026R2",
                        evidence(),
                        null),
                    admin))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("绑定变更需独立迁移");
  }

  @Test
  void shouldTreatExplicitJsonNullEvidenceAsAbsent() {
    long scopeId = createScope("null-evidence");
    FormAdminViews.DraftView draft =
        adminService.updateDraft(
            scopeId,
            new FormDraftUpdateRequest(
                currentRevision(scopeId),
                seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY),
                null,
                null,
                NullNode.getInstance(),
                NullNode.getInstance()),
            admin);

    assertThat(draft.revision()).isEqualTo(2);
    assertThat(draft.versionEvidence()).isNull();
    assertThat(draft.versionLabel()).isNull();
  }

  @Test
  void shouldMigrateFieldChangesAndConvergeOldRequestKey() {
    long scopeId = createScope("field-migration");
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "CC2026R1");
    FormAdminViews.PublicationView first = publish(scopeId, "CC2026R1");
    long oldVersionId = first.toVersionId();
    String requestKey = UUID.randomUUID().toString();
    ObjectNode fields = issueReplyFields();
    FormCreateResponse created =
        formRecordService.create(
            issueReplyRequest(scopeId, oldVersionId, requestKey, fields), admin, "127.0.0.1", "JUnit");
    assertThat(created.outcome()).isEqualTo("CREATED");

    ObjectNode migrated = seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY);
    ArrayNode migratedFields = objectMapper.createArrayNode();
    for (JsonNode field : migrated.get("fields")) {
      String name = field.path("name").asText();
      if ("supplement_note".equals(name)) {
        continue;
      }
      ObjectNode copy = field.deepCopy();
      if ("fix_status".equals(name)) {
        copy.put("label", "修复状态（新）");
      }
      migratedFields.add(copy);
    }
    ObjectNode added = migratedFields.addObject();
    added.put("name", "review_comment");
    added.put("apiKey", "reviewComment");
    added.put("label", "走查意见");
    added.put("type", "string");
    added.put("control", "input");
    added.put("required", false);
    added.put("maxLength", 200);
    ObjectNode storage = added.putObject("storage");
    storage.put("column", "review_comment");
    storage.put("columnType", "varchar(200)");
    migrated.set("fields", migratedFields);
    boundDraft(scopeId, migrated, "CC2026R2");

    FormAdminViews.PreviewView preview = adminService.preview(scopeId, currentRevision(scopeId), admin);
    assertThat(preview.blockingErrors()).isEmpty();
    assertThat(preview.diff().path("added").asInt()).isEqualTo(1);
    assertThat(preview.diff().path("removed").asInt()).isEqualTo(1);

    FormAdminViews.PublicationView second =
        adminService.createPublication(
            scopeId,
            new FormPublicationCreateRequest(
                currentRevision(scopeId),
                preview.id(),
                oldVersionId,
                UUID.randomUUID().toString()),
            admin);
    assertThat(second.publicationKind()).isEqualTo("MIGRATION");
    assertThat(publicationService.executeOnce(second.id())).isTrue();

    FormAdminViews.ScopeDetail detail = adminService.scope(scopeId);
    assertThat(detail.scope().currentVersionId()).isEqualTo(second.toVersionId());
    assertThat(detail.recordCount()).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_template_migration_logs where scope_id = ?",
                Integer.class,
                scopeId))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select version_status from form_template_versions where id = ?",
                String.class,
                oldVersionId))
        .isEqualTo("RETIRED");

    Long migratedRecordId =
        jdbcTemplate.queryForObject(
            "select id from form_s" + scopeId + "_v" + second.toVersionId() + "_records",
            Long.class);
    assertThat(migratedRecordId).isEqualTo(Long.parseLong(created.record().id()));

    FormCreateResponse replayed =
        formRecordService.create(
            issueReplyRequest(scopeId, oldVersionId, requestKey, issueReplyFields()),
            admin,
            "127.0.0.1",
            "JUnit");
    assertThat(replayed.outcome()).isEqualTo("REPLAYED");
    assertThat(replayed.record().id()).isEqualTo(created.record().id());
    assertThat(replayed.record().fields()).doesNotContainKey("supplementNote");
    assertThat(replayed.record().fields()).containsKey("reviewComment");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_s" + scopeId + "_v" + second.toVersionId() + "_records",
                Integer.class))
        .isEqualTo(1);

    assertThatThrownBy(
            () ->
                formRecordService.create(
                    issueReplyRequest(
                        scopeId, oldVersionId, UUID.randomUUID().toString(), issueReplyFields()),
                    admin,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("结构版本已变化");
  }

  @Test
  void shouldMarkLegacyIncompleteWhenMigrationAddsRequiredField() {
    long scopeId = createScope("legacy-incomplete");
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "CC2026R1");
    FormAdminViews.PublicationView first = publish(scopeId, "CC2026R1");
    FormCreateResponse created = createIssueReply(scopeId, first.toVersionId());

    ObjectNode migrated = seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY);
    ArrayNode migratedFields = objectMapper.createArrayNode();
    migrated.get("fields").forEach(migratedFields::add);
    ObjectNode added = migratedFields.addObject();
    added.put("name", "review_comment");
    added.put("apiKey", "reviewComment");
    added.put("label", "走查意见");
    added.put("type", "string");
    added.put("control", "input");
    added.put("required", true);
    added.put("maxLength", 200);
    ObjectNode storage = added.putObject("storage");
    storage.put("column", "review_comment");
    storage.put("columnType", "varchar(200)");
    migrated.set("fields", migratedFields);
    boundDraft(scopeId, migrated, "CC2026R2");
    FormAdminViews.PublicationView second = publish(scopeId, "CC2026R2");

    String recordTable = "form_s" + scopeId + "_v" + second.toVersionId() + "_records";
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "select legacy_incomplete, legacy_missing_fields::text as missing,"
                + " review_comment, fix_status from " + recordTable + " where id = ?",
            Long.parseLong(created.record().id()));
    assertThat(row.get("legacy_incomplete")).isEqualTo(true);
    assertThat((String) row.get("missing")).contains("review_comment");
    assertThat(row.get("review_comment")).isNull();
    assertThat(row.get("fix_status")).isEqualTo("RESOLVED");

    FormDetailResponse detail =
        formRecordService.detail(
            FormTemplateSpecCatalog.ISSUE_REPLY,
            GITLAB_URL,
            PROJECT_ID,
            "issue",
            Long.toString(ISSUE_IID),
            admin);
    assertThat(detail.records()).singleElement().satisfies(
        record -> {
          assertThat(record.legacyIncomplete()).isTrue();
          assertThat(record.legacyMissingFields()).contains("review_comment");
          assertThat(record.fields().get("reviewComment")).isNull();
        });

    FormRecordView voided =
        formRecordService.voidRecord(
            created.record().id(),
            new FormVoidRequest(
                GITLAB_URL,
                PROJECT_ID,
                "issue",
                Long.toString(ISSUE_IID),
                FormTemplateSpecCatalog.ISSUE_REPLY,
                Long.toString(scopeId),
                Long.toString(second.toVersionId()),
                1,
                true,
                "作废"),
            admin,
            "127.0.0.1",
            "JUnit");
    assertThat(voided.deleted()).isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select deleted from " + recordTable + " where id = ?",
                Boolean.class,
                Long.parseLong(created.record().id())))
        .isTrue();
  }

  @Test
  void shouldRetireScopeAndKeepHistoryReadable() {
    long scopeId = createScope("retire");
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "CC2026R1");
    FormAdminViews.PublicationView publication = publish(scopeId, "CC2026R1");
    createIssueReply(scopeId, publication.toVersionId());

    adminService.retire(scopeId, publication.toVersionId(), admin);
    FormAdminViews.ScopeDetail detail = adminService.scope(scopeId);
    assertThat(detail.scope().status()).isEqualTo("RETIRED");

    FormDetailResponse readable =
        formRecordService.detail(
            FormTemplateSpecCatalog.ISSUE_REPLY,
            GITLAB_URL,
            PROJECT_ID,
            "issue",
            Long.toString(ISSUE_IID),
            admin);
    assertThat(readable.writable()).isFalse();
    assertThat(readable.records()).hasSize(1);

    assertThatThrownBy(() -> createIssueReply(scopeId, publication.toVersionId()))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("已停用");
  }

  @Test
  void shouldReportPreviewBlockersAndDeleteUnusedDraft() {
    long scopeId = createScope("preview-blockers");
    FormAdminViews.PreviewView unbound = adminService.preview(scopeId, currentRevision(scopeId), admin);
    assertThat(unbound.blockingErrors()).anyMatch(error -> error.contains("绑定"));

    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "CC2026R1");
    publish(scopeId, "CC2026R1");

    ObjectNode changed = seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY);
    for (JsonNode field : changed.get("fields")) {
      if ("fix_status".equals(field.path("name").asText())) {
        ((ObjectNode) field.get("storage")).put("column", "fix_status_new");
      }
    }
    adminService.updateDraft(
        scopeId,
        new FormDraftUpdateRequest(
            currentRevision(scopeId), changed, null, "CC2026R2", evidence(), null),
        admin);
    FormAdminViews.PreviewView blocked =
        adminService.preview(scopeId, currentRevision(scopeId), admin);
    assertThat(blocked.blockingErrors()).anyMatch(error -> error.contains("存储列不可变更"));

    long emptyScope = createScope("delete-me");
    adminService.deleteScope(emptyScope);
    assertThatThrownBy(() -> adminService.scope(emptyScope))
        .isInstanceOf(com.data.collection.platform.common.exception.BizException.class);
  }

  @Test
  void shouldFailCopyRecoverAndRecaptureRowsWrittenAfterFailure() {
    long scopeId = createScope("copy-recovery");
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "CC2026R1");
    FormAdminViews.PublicationView first = publish(scopeId, "CC2026R1");
    long oldVersionId = first.toVersionId();
    createIssueReply(scopeId, oldVersionId);

    ObjectNode migrated = seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY);
    migrated.get("fields").forEach(
        field -> {
          if ("fix_status".equals(field.path("name").asText())) {
            ((ObjectNode) field).put("label", "修复状态（新版）");
          }
        });
    boundDraft(scopeId, migrated, "CC2026R2");
    FormAdminViews.PreviewView preview =
        adminService.preview(scopeId, currentRevision(scopeId), admin);
    assertThat(preview.blockingErrors()).isEmpty();

    FormTemplateSpec migratedSpec;
    try {
      migratedSpec =
          objectMapper.readValue(objectMapper.writeValueAsString(migrated), FormTemplateSpec.class);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    FormVersionSchema schema =
        versionSchemaService.generate(scopeId, preview.targetVersionId(), migratedSpec, true);
    schema.statements().forEach(jdbcTemplate::execute);
    installCopyFailure(schema.recordTable());

    FormAdminViews.PublicationView publication =
        adminService.createPublication(
            scopeId,
            new FormPublicationCreateRequest(
                currentRevision(scopeId),
                preview.id(),
                oldVersionId,
                UUID.randomUUID().toString()),
            admin);
    assertThat(publicationService.executeOnce(publication.id())).isFalse();
    FormAdminViews.PublicationView failed = publicationService.publicationView(publication.id());
    assertThat(failed.stage()).isEqualTo("FAILED");
    assertThat(failed.lastError()).isNotBlank();
    assertThat(adminService.scope(scopeId).scope().writeFenced()).isFalse();
    assertThat(adminService.scope(scopeId).scope().currentVersionId()).isEqualTo(oldVersionId);

    createIssueReply(scopeId, oldVersionId);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_s" + scopeId + "_v" + oldVersionId + "_records",
                Integer.class))
        .isEqualTo(2);

    dropCopyFailure();
    FormAdminViews.PublicationView recovered =
        publicationService.recover(publication.id(), admin);
    assertThat(recovered.stage()).isEqualTo("PREPARED");
    assertThat(recovered.recoveryCycle()).isEqualTo(1);
    assertThat(publicationService.executeOnce(publication.id())).isTrue();
    FormAdminViews.PublicationView published = publicationService.publicationView(publication.id());
    assertThat(published.stage()).isEqualTo("PUBLISHED");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_s" + scopeId + "_v" + published.toVersionId() + "_records",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "select migrated_rows from form_template_migration_logs where to_version_id = ?",
                Long.class,
                published.toVersionId()))
        .isEqualTo(2L);
  }

  @Test
  void shouldRejectWritesWhileCopyingAndReapExpiredLease() {
    long scopeId = createScope("fence-lease");
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "CC2026R1");
    FormAdminViews.PublicationView first = publish(scopeId, "CC2026R1");
    long oldVersionId = first.toVersionId();

    ObjectNode migrated = seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY);
    migrated.get("fields").forEach(
        field -> {
          if ("fix_status".equals(field.path("name").asText())) {
            ((ObjectNode) field).put("label", "修复状态（锁测试）");
          }
        });
    boundDraft(scopeId, migrated, "CC2026R2");
    FormAdminViews.PreviewView preview =
        adminService.preview(scopeId, currentRevision(scopeId), admin);
    FormAdminViews.PublicationView publication =
        adminService.createPublication(
            scopeId,
            new FormPublicationCreateRequest(
                currentRevision(scopeId),
                preview.id(),
                oldVersionId,
                UUID.randomUUID().toString()),
            admin);
    assertThatThrownBy(
            () ->
                adminService.createPublication(
                    scopeId,
                    new FormPublicationCreateRequest(
                        currentRevision(scopeId),
                        preview.id(),
                        oldVersionId,
                        UUID.randomUUID().toString()),
                    admin))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("未结束");

    FormPublicationTxService.Claim claim =
        publicationTxService.claim(publication.id(), "w4-test-worker");
    assertThat(claim).isNotNull();
    assertThatThrownBy(() -> createIssueReply(scopeId, oldVersionId))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("停写");

    jdbcTemplate.update(
        "update form_template_publications set lease_until = clock_timestamp() - interval '5 minutes'"
            + " where id = ?",
        publication.id());
    assertThat(publicationTxService.reapExpired()).isEqualTo(1);
    assertThat(publicationService.publicationView(publication.id()).stage()).isEqualTo("FAILED");
    assertThat(adminService.scope(scopeId).scope().writeFenced()).isFalse();
    createIssueReply(scopeId, oldVersionId);
  }

  @Test
  void shouldIgnoreExpiredLeaseFailureAndLetReaperOwnRecovery() {
    long scopeId = createScope("expired-failure");
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "CC2026R1");
    FormAdminViews.PublicationView first = publish(scopeId, "CC2026R1");
    long oldVersionId = first.toVersionId();

    ObjectNode migrated = seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY);
    migrated.get("fields").forEach(
        field -> {
          if ("fix_status".equals(field.path("name").asText())) {
            ((ObjectNode) field).put("label", "修复状态（过期租约）");
          }
        });
    boundDraft(scopeId, migrated, "CC2026R2");
    FormAdminViews.PreviewView preview =
        adminService.preview(scopeId, currentRevision(scopeId), admin);
    FormAdminViews.PublicationView publication =
        adminService.createPublication(
            scopeId,
            new FormPublicationCreateRequest(
                currentRevision(scopeId),
                preview.id(),
                oldVersionId,
                UUID.randomUUID().toString()),
            admin);

    FormPublicationTxService.Claim claim =
        publicationTxService.claim(publication.id(), "r06-expired-owner");
    jdbcTemplate.update(
        "update form_template_publications set lease_until = clock_timestamp() - interval '5 minutes'"
            + " where id = ?",
        publication.id());

    assertThat(publicationTxService.fail(claim, "迟到的失败诊断")).isFalse();
    assertThat(publicationService.publicationView(publication.id()).stage()).isEqualTo("COPYING");
    assertThat(adminService.scope(scopeId).scope().writeFenced()).isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select phase from form_template_publication_attempts"
                    + " where publication_id = ? and attempt_no = 1",
                String.class,
                publication.id()))
        .isEqualTo("COPY");
    assertThat(
            jdbcTemplate.queryForObject(
                "select last_error from form_template_publications where id = ?",
                String.class,
                publication.id()))
        .isNull();

    assertThat(publicationTxService.reapExpired()).isEqualTo(1);
    FormAdminViews.PublicationView reaped =
        publicationService.publicationView(publication.id());
    assertThat(reaped.stage()).isEqualTo("FAILED");
    assertThat(reaped.lastError()).contains("租约过期");
    assertThat(adminService.scope(scopeId).scope().writeFenced()).isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "select phase from form_template_publication_attempts"
                    + " where publication_id = ? and attempt_no = 1",
                String.class,
                publication.id()))
        .isEqualTo("FAILED");

    assertThat(publicationTxService.fail(claim, "回收后的迟到诊断")).isFalse();
    assertThat(publicationService.publicationView(publication.id()).lastError())
        .contains("租约过期");

    assertThat(publicationService.recover(publication.id(), admin).stage()).isEqualTo("PREPARED");
    FormPublicationTxService.Claim second =
        publicationTxService.claim(publication.id(), "r06-new-owner");
    assertThat(second).isNotNull();
    assertThat(second.attemptNo()).isEqualTo(2);
    assertThat(publicationTxService.fail(second, "第二次尝试失败")).isTrue();
    assertThat(publicationService.publicationView(publication.id()).stage()).isEqualTo("FAILED");
    assertThat(
            jdbcTemplate.queryForObject(
                "select phase from form_template_publication_attempts"
                    + " where publication_id = ? and attempt_no = 2",
                String.class,
                publication.id()))
        .isEqualTo("FAILED");
  }

  @Test
  void shouldPreviewBusinessFieldChangesWithLabelsInsteadOfTechnicalKeys() {
    long scopeId = createScope("business-diff");
    ObjectNode changed = seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY);
    var fieldArray = (com.fasterxml.jackson.databind.node.ArrayNode) changed.get("fields");
    for (int index = fieldArray.size() - 1; index >= 0; index--) {
      JsonNode field = fieldArray.get(index);
      String name = field.path("name").asText();
      if ("supplement_note".equals(name)) {
        fieldArray.remove(index);
        continue;
      }
      if ("fix_status".equals(name)) {
        ((ObjectNode) field).put("label", "修复结果");
        ((ObjectNode) field).put("required", false);
        ((com.fasterxml.jackson.databind.node.ArrayNode) field.get("options"))
            .addObject()
            .put("value", "NEW")
            .put("label", "新增项");
      }
    }
    ObjectNode added = fieldArray.addObject();
    added.put("name", "new_field_1");
    added.put("apiKey", "newField1");
    added.put("label", "走查意见");
    added.put("type", "string");
    added.put("control", "textarea");
    added.put("required", true);
    added.put("maxLength", 200);
    added.putObject("storage").put("column", "new_field_1").put("columnType", "varchar(200)");

    boundDraft(scopeId, changed, "CC2026R1");
    FormAdminViews.PreviewView preview =
        adminService.preview(scopeId, currentRevision(scopeId), admin);
    assertThat(preview.blockingErrors()).isEmpty();
    assertThat(preview.diff().path("added").asInt()).isEqualTo(1);
    assertThat(preview.diff().path("removed").asInt()).isEqualTo(1);
    JsonNode fixStatus = null;
    JsonNode newField = null;
    JsonNode removed = null;
    for (JsonNode change : preview.diff().path("fields")) {
      String name = change.path("name").asText();
      if ("fix_status".equals(name)) {
        fixStatus = change;
      }
      if ("new_field_1".equals(name)) {
        newField = change;
      }
      if ("REMOVED".equals(change.path("change").asText())) {
        removed = change;
      }
    }
    assertThat(fixStatus).isNotNull();
    assertThat(fixStatus.path("oldLabel").asText()).isNotBlank();
    assertThat(fixStatus.path("newLabel").asText()).isEqualTo("修复结果");
    assertThat(fixStatus.path("changes").toString())
        .contains("LABEL")
        .contains("REQUIRED")
        .contains("OPTIONS");
    assertThat(newField).isNotNull();
    assertThat(newField.path("newLabel").asText()).isEqualTo("走查意见");
    assertThat(removed).isNotNull();
    assertThat(removed.path("oldLabel").asText()).isEqualTo("补充说明");
  }

  @Test
  void shouldListMilestonesFromOdsMirror() {
    FormAdminViews.MilestoneAvailability milestones =
        adminService.milestones(sourceConfigId, PROJECT_ID);
    assertThat(milestones.available()).isTrue();
    assertThat(milestones.items()).hasSize(2);
    assertThat(milestones.items().getFirst().title()).isEqualTo("CC2026R2");
  }

  // ------------------------------------------------------------------ helpers

  private long createScope(String scopeKey) {
    return adminService
        .createScope(
            new FormScopeCreateRequest(FormTemplateSpecCatalog.ISSUE_REPLY, scopeKey), admin)
        .scope()
        .id();
  }

  private FormAdminViews.DraftView boundDraft(long scopeId, ObjectNode spec, String label) {
    return adminService.updateDraft(
        scopeId,
        new FormDraftUpdateRequest(
            currentRevision(scopeId),
            spec,
            new FormBindingRequest(sourceConfigId, List.of(PROJECT_ID), PROJECT_ID),
            label,
            evidence(),
            null),
        admin);
  }

  private long currentRevision(long scopeId) {
    return adminService.scope(scopeId).draft().revision();
  }

  private FormAdminViews.PublicationView publish(long scopeId, String label) {
    FormAdminViews.PreviewView preview =
        adminService.preview(scopeId, currentRevision(scopeId), admin);
    assertThat(preview.blockingErrors()).isEmpty();
    FormAdminViews.PublicationView publication =
        adminService.createPublication(
            scopeId,
            new FormPublicationCreateRequest(
                currentRevision(scopeId),
                preview.id(),
                adminService.scope(scopeId).scope().currentVersionId(),
                UUID.randomUUID().toString()),
            admin);
    assertThat(publicationService.executeOnce(publication.id())).isTrue();
    return publicationService.publicationView(publication.id());
  }

  private ObjectNode seedSpec(String templateKey) {
    try {
      return (ObjectNode) objectMapper.readTree(specCatalog.rawJson(templateKey));
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private JsonNode evidence() {
    ObjectNode evidence = objectMapper.createObjectNode();
    evidence.put("milestoneId", 1001);
    evidence.put("milestoneTitle", "CC2026R1");
    evidence.put("projectId", PROJECT_ID);
    evidence.put("verifiedAt", "2026-10-23T00:00:00");
    return evidence;
  }

  private FormCreateResponse createIssueReply(long scopeId, long versionId) {
    return formRecordService.create(
        issueReplyRequest(scopeId, versionId, UUID.randomUUID().toString(), issueReplyFields()),
        admin,
        "127.0.0.1",
        "JUnit");
  }

  private FormCreateRequest issueReplyRequest(
      long scopeId, long versionId, String requestKey, ObjectNode fields) {
    return new FormCreateRequest(
        GITLAB_URL,
        PROJECT_ID,
        "issue",
        Long.toString(ISSUE_IID),
        FormTemplateSpecCatalog.ISSUE_REPLY,
        Long.toString(scopeId),
        Long.toString(versionId),
        requestKey,
        false,
        toFields(fields));
  }

  private ObjectNode issueReplyFields() {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("fixStatus", "RESOLVED");
    node.set("causeKeys", array("missing_requirement"));
    node.put("specificReason", "具体原因");
    node.put("modificationPlan", "修改方案");
    node.put("causedByOtherDefect", false);
    node.set("affectedKinds", array("NONE"));
    node.putNull("knownAffectedFunctions");
    node.putNull("newAffectedFunctions");
    node.put("impactTestStatus", "YES");
    node.put("hasResidualImpact", false);
    node.putNull("residualImpactDetail");
    node.put("relationTableUpdated", "NOT_APPLICABLE");
    node.putNull("supplementNote");
    return node;
  }

  private ArrayNode array(String... values) {
    ArrayNode node = objectMapper.createArrayNode();
    for (String value : values) {
      node.add(value);
    }
    return node;
  }

  private Map<String, JsonNode> toFields(ObjectNode node) {
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
    return fields;
  }

  private void installCopyFailure(String recordTable) {
    jdbcTemplate.execute(
        "create or replace function w4_fail_copy() returns trigger language plpgsql as $$"
            + " begin raise exception 'w4 copy failure'; end $$");
    jdbcTemplate.execute(
        "create trigger w4_fail_copy before insert on " + recordTable
            + " for each row execute function w4_fail_copy()");
  }

  private void dropCopyFailure() {
    List<String> tables =
        jdbcTemplate.queryForList(
            "select tablename from pg_tables where schemaname = current_schema()", String.class);
    for (String table : tables) {
      if (table.startsWith("form_s") && table.endsWith("_records")) {
        jdbcTemplate.execute("drop trigger if exists w4_fail_copy on " + table);
      }
    }
    jdbcTemplate.execute("drop function if exists w4_fail_copy()");
  }

  @Test
  void shouldRejectRecoveryForAnyStructureDrift() {
    FailedPublication failed = prepareFailedPublication("recover-drift");
    String recordTable = failed.schema().recordTable();
    FormVersionSchema schema = failed.schema();

    String checkName =
        jdbcTemplate.queryForObject(
            "select conname from pg_constraint where conrelid = ?::regclass"
                + " and contype = 'c' order by conname limit 1",
            String.class,
            recordTable);
    String checkDef =
        jdbcTemplate.queryForObject(
            "select pg_get_constraintdef(oid) from pg_constraint where conname = ? and conrelid = ?::regclass",
            String.class,
            checkName,
            recordTable);
    jdbcTemplate.execute("alter table " + recordTable + " drop constraint " + checkName);
    assertDriftRejected(failed.publication().id());
    jdbcTemplate.execute(
        "alter table " + recordTable + " add constraint " + checkName + " " + checkDef);

    String indexName =
        jdbcTemplate.queryForObject(
            "select indexname from pg_indexes where schemaname = current_schema()"
                + " and tablename = ? and indexname like 'idx_fr_%' order by indexname limit 1",
            String.class,
            recordTable);
    String indexDef =
        jdbcTemplate.queryForObject(
            "select indexdef from pg_indexes where schemaname = current_schema() and indexname = ?",
            String.class,
            indexName);
    jdbcTemplate.execute("drop index " + indexName);
    assertDriftRejected(failed.publication().id());
    jdbcTemplate.execute(indexDef);

    String foreignKey =
        jdbcTemplate.queryForObject(
            "select conname from pg_constraint where conrelid = ?::regclass"
                + " and contype = 'f' order by conname limit 1",
            String.class,
            recordTable);
    String foreignKeyDef =
        jdbcTemplate.queryForObject(
            "select pg_get_constraintdef(oid) from pg_constraint where conname = ? and conrelid = ?::regclass",
            String.class,
            foreignKey,
            recordTable);
    jdbcTemplate.execute("alter table " + recordTable + " drop constraint " + foreignKey);
    jdbcTemplate.execute(
        "alter table " + recordTable + " add constraint " + foreignKey
            + " foreign key (instance_id) references " + schema.instanceTable()
            + " (id) on delete cascade");
    assertDriftRejected(failed.publication().id());
    jdbcTemplate.execute("alter table " + recordTable + " drop constraint " + foreignKey);
    jdbcTemplate.execute(
        "alter table " + recordTable + " add constraint " + foreignKey + " " + foreignKeyDef);

    jdbcTemplate.execute("create sequence if not exists form_record_id_probe_seq");
    jdbcTemplate.execute(
        "alter table " + recordTable
            + " alter column id set default nextval('form_record_id_probe_seq')");
    assertDriftRejected(failed.publication().id());
    jdbcTemplate.execute(
        "alter table " + recordTable + " alter column id set default nextval('form_record_id_seq')");

    assertThat(
            jdbcTemplate.queryForObject(
                "select stage from form_template_publications where id = ?",
                String.class,
                failed.publication().id()))
        .isEqualTo("FAILED");
    assertThat(adminService.scope(failed.scopeId()).scope().currentVersionId())
        .isEqualTo(failed.oldVersionId());
    assertThat(adminService.scope(failed.scopeId()).scope().writeFenced()).isFalse();
  }

  @Test
  void shouldRejectRecoveryWhenTargetTablesPartiallyMissing() {
    FailedPublication failed = prepareFailedPublication("recover-partial");
    jdbcTemplate.execute(
        "drop table " + failed.schema().instanceTable() + " cascade");
    assertThatThrownBy(() -> publicationService.recover(failed.publication().id(), admin))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("结构不完整");
    assertThat(adminService.scope(failed.scopeId()).scope().currentVersionId())
        .isEqualTo(failed.oldVersionId());
  }

  @Test
  void shouldRejectCopyBeforeTruncatingWhenStructureDrifted() {
    long scopeId = createScope("copy-drift");
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "CC2026R1");
    FormAdminViews.PublicationView first = publish(scopeId, "CC2026R1");
    long oldVersionId = first.toVersionId();
    createIssueReply(scopeId, oldVersionId);

    ObjectNode migrated = seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY);
    for (JsonNode field : migrated.get("fields")) {
      if ("fix_status".equals(field.path("name").asText())) {
        ((ObjectNode) field).put("label", "修复状态（漂移）");
      }
    }
    boundDraft(scopeId, migrated, "CC2026R2");
    FormAdminViews.PreviewView preview =
        adminService.preview(scopeId, currentRevision(scopeId), admin);
    FormTemplateSpec migratedSpec;
    try {
      migratedSpec =
          objectMapper.readValue(objectMapper.writeValueAsString(migrated), FormTemplateSpec.class);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    FormVersionSchema schema =
        versionSchemaService.generate(scopeId, preview.targetVersionId(), migratedSpec, true);
    schema.statements().forEach(jdbcTemplate::execute);
    String checkName =
        jdbcTemplate.queryForObject(
            "select conname from pg_constraint where conrelid = ?::regclass"
                + " and contype = 'c' order by conname limit 1",
            String.class,
            schema.recordTable());
    jdbcTemplate.execute(
        "alter table " + schema.recordTable() + " drop constraint " + checkName);
    jdbcTemplate.update(
        "insert into " + schema.instanceTable()
            + " select * from form_s" + scopeId + "_v" + oldVersionId + "_instances");
    jdbcTemplate.update(
        "insert into " + schema.recordTable()
            + " select * from form_s" + scopeId + "_v" + oldVersionId + "_records");

    FormAdminViews.PublicationView publication =
        adminService.createPublication(
            scopeId,
            new FormPublicationCreateRequest(
                currentRevision(scopeId),
                preview.id(),
                oldVersionId,
                UUID.randomUUID().toString()),
            admin);
    assertThat(publicationService.executeOnce(publication.id())).isFalse();
    FormAdminViews.PublicationView failed = publicationService.publicationView(publication.id());
    assertThat(failed.stage()).isEqualTo("FAILED");
    assertThat(failed.lastError()).contains("结构漂移");
    assertThat(adminService.scope(scopeId).scope().currentVersionId()).isEqualTo(oldVersionId);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from " + schema.recordTable(), Integer.class))
        .isEqualTo(1);
  }

  private void assertDriftRejected(long publicationId) {
    assertThatThrownBy(() -> publicationService.recover(publicationId, admin))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("结构漂移");
  }

  private FailedPublication prepareFailedPublication(String scopeKey) {
    long scopeId = createScope(scopeKey);
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "CC2026R1");
    FormAdminViews.PublicationView first = publish(scopeId, "CC2026R1");
    long oldVersionId = first.toVersionId();
    createIssueReply(scopeId, oldVersionId);

    ObjectNode migrated = seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY);
    for (JsonNode field : migrated.get("fields")) {
      if ("fix_status".equals(field.path("name").asText())) {
        ((ObjectNode) field).put("label", "修复状态（漂移）");
      }
    }
    boundDraft(scopeId, migrated, "CC2026R2");
    FormAdminViews.PreviewView preview =
        adminService.preview(scopeId, currentRevision(scopeId), admin);
    FormTemplateSpec migratedSpec;
    try {
      migratedSpec =
          objectMapper.readValue(objectMapper.writeValueAsString(migrated), FormTemplateSpec.class);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    FormVersionSchema schema =
        versionSchemaService.generate(scopeId, preview.targetVersionId(), migratedSpec, true);
    schema.statements().forEach(jdbcTemplate::execute);
    installCopyFailure(schema.recordTable());
    FormAdminViews.PublicationView publication =
        adminService.createPublication(
            scopeId,
            new FormPublicationCreateRequest(
                currentRevision(scopeId),
                preview.id(),
                oldVersionId,
                UUID.randomUUID().toString()),
            admin);
    assertThat(publicationService.executeOnce(publication.id())).isFalse();
    dropCopyFailure();
    FormAdminViews.PublicationView failed = publicationService.publicationView(publication.id());
    assertThat(failed.stage()).isEqualTo("FAILED");
    return new FailedPublication(scopeId, oldVersionId, failed, schema);
  }

  private record FailedPublication(
      long scopeId,
      long oldVersionId,
      FormAdminViews.PublicationView publication,
      FormVersionSchema schema) {}

  private void dropDynamicTables() {
    List<String> tables =
        jdbcTemplate.queryForList(
            "select tablename from pg_tables where schemaname = current_schema()", String.class);
    for (String table : tables) {
      if (table.startsWith("form_s")
          && (table.endsWith("_instances") || table.endsWith("_records"))) {
        jdbcTemplate.execute("drop table if exists " + table + " cascade");
      }
    }
  }

  private void ensureOdsTables() {
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_issues(
            id bigint primary key,
            project_id bigint,
            iid bigint,
            mirror_deleted boolean default false)
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_projects(
            id bigint primary key,
            path varchar(255),
            namespace_id bigint,
            mirror_deleted boolean default false)
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_namespaces(
            id bigint primary key,
            path varchar(255),
            mirror_deleted boolean default false)
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_milestones(
            id bigint primary key,
            title varchar(255),
            project_id bigint,
            mirror_deleted boolean default false)
        """);
  }
}
