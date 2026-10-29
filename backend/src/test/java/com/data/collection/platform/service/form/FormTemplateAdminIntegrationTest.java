package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
  private static final long MERGE_REQUEST_IID = 22L;

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
    assertThat(created.stage()).isEqualTo("VALIDATING");
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
  void shouldPublishTypedConditionsAndEnforceFrozenMutualSet() {
    long scopeId = createScope("typed-conditions");
    ObjectNode spec = customConditionSpec("B");
    boundDraft(scopeId, spec, "CC2026R1");
    FormAdminViews.PublicationView publication = publish(scopeId, "CC2026R1");
    long versionId = publication.toVersionId();

    ObjectNode allowedCombination = customReplyFields("A", "C");
    FormCreateResponse allowed =
        createCustomReply(scopeId, versionId, allowedCombination, UUID.randomUUID().toString());
    assertThat(allowed.outcome()).isEqualTo("CREATED");
    assertThat(allowed.record().fields().get("customA")).isEqualTo(List.of("A", "C"));

    ObjectNode otherAllowed = customReplyFields("B", "C");
    assertThat(
            createCustomReply(scopeId, versionId, otherAllowed, UUID.randomUUID().toString())
                .outcome())
        .isEqualTo("CREATED");

    ObjectNode conflict = customReplyFields("A", "B");
    assertThatThrownBy(
            () ->
                createCustomReply(scopeId, versionId, conflict, UUID.randomUUID().toString()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("互斥");

    ObjectNode numericAllowed = issueReplyFields();
    numericAllowed.set("customA", array("B", "C"));
    numericAllowed.put("hasResidualImpact", true);
    numericAllowed.put("residualImpactDetail", "已处理");
    numericAllowed.put("customCount", 0);
    numericAllowed.put("customFlag", false);
    FormCreateResponse numeric =
        createCustomReply(scopeId, versionId, numericAllowed, UUID.randomUUID().toString());
    assertThat(numeric.outcome()).isEqualTo("CREATED");
    assertThat(numeric.record().fields().get("customCount")).isEqualTo(0);

    ObjectNode numericMissing = numericAllowed.deepCopy();
    numericMissing.putNull("customCount");
    assertThatThrownBy(
            () ->
                createCustomReply(
                    scopeId, versionId, numericMissing, UUID.randomUUID().toString()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("自定义数量");

    ObjectNode booleanMissing = customReplyFields("B", "C");
    booleanMissing.putNull("customFlag");
    assertThatThrownBy(
            () ->
                createCustomReply(
                    scopeId, versionId, booleanMissing, UUID.randomUUID().toString()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("自定义开关");

    ObjectNode booleanFalse = customReplyFields("B", "C");
    booleanFalse.put("customFlag", false);
    assertThat(
            createCustomReply(scopeId, versionId, booleanFalse, UUID.randomUUID().toString())
                .outcome())
        .isEqualTo("CREATED");
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

  // ------------------------------------------------------------------ N3 空结构发布

  /** 三模板各自首次启用与 V1→V2 空发布：新版只建空结构，旧记录与目录位置原位保留。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        FormTemplateSpecCatalog.CODE_REVIEW,
        FormTemplateSpecCatalog.ISSUE_RESPONSE,
        FormTemplateSpecCatalog.ISSUE_REPLY
      })
  void shouldEnableAndPublishNextEmptyVersionForEveryTemplate(String templateKey) {
    long scopeId = createScope(templateKey, "n3-template-" + templateKey);
    boundDraft(scopeId, seedSpec(templateKey), "N3-T1");
    FormAdminViews.PublicationView first = publish(scopeId, "N3-T1");

    assertThat(publicationKind(first.id())).isEqualTo("INITIAL_EMPTY");
    assertThat(first.stage()).isEqualTo("PUBLISHED");
    assertThat(versionStatus(first.toVersionId())).isEqualTo("PUBLISHED");
    assertThat(versionRole(first.toVersionId())).isEqualTo("AUTHORITATIVE");
    assertThat(countTable(recordTable(first.toVersionId()))).isZero();
    assertThat(countTable(instanceTable(first.toVersionId()))).isZero();
    assertThat(currentVersionId(scopeId)).isEqualTo(first.toVersionId());
    assertThat(sourceSetRevision(scopeId)).isEqualTo(1);
    assertThat(pendingHeadCount(scopeId, first.toVersionId(), 1))
        .isEqualTo(totalHeadCount(scopeId));

    FormCreateResponse firstRecord = createRecord(templateKey, scopeId, first.toVersionId());
    assertThat(firstRecord.outcome()).isEqualTo("CREATED");
    long recordId = Long.parseLong(firstRecord.record().id());
    Map<String, Object> locationBefore = recordLocation(recordId);
    assertThat(totalHeadCount(scopeId)).isPositive();

    boundDraft(scopeId, relabeledSpec(templateKey, "N3 新版"), "N3-T2");
    FormAdminViews.PublicationView second = publish(scopeId, "N3-T2");

    assertThat(publicationKind(second.id())).isEqualTo("MIGRATION");
    assertThat(second.stage()).isEqualTo("PUBLISHED");
    assertThat(versionStatus(first.toVersionId())).isEqualTo("RETIRED");
    assertThat(versionRole(first.toVersionId())).isEqualTo("AUTHORITATIVE");
    assertThat(versionStatus(second.toVersionId())).isEqualTo("PUBLISHED");
    assertThat(versionRole(second.toVersionId())).isEqualTo("AUTHORITATIVE");
    assertThat(countTable(recordTable(second.toVersionId()))).isZero();
    assertThat(countTable(instanceTable(second.toVersionId()))).isZero();
    assertThat(countTable(recordTable(first.toVersionId()))).isEqualTo(1);
    assertThat(currentVersionId(scopeId)).isEqualTo(second.toVersionId());
    assertThat(sourceSetRevision(scopeId)).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "select source_set_hash from form_template_scopes where id = ?",
                String.class,
                scopeId))
        .isNotBlank();
    assertThat(recordLocation(recordId)).isEqualTo(locationBefore);
    assertThat(directoryEventCount(scopeId, 2)).isEqualTo(2);
    assertThat(pendingHeadCount(scopeId, second.toVersionId(), 2))
        .isPositive()
        .isEqualTo(totalHeadCount(scopeId));

    FormCreateResponse secondRecord = createRecord(templateKey, scopeId, second.toVersionId());
    assertThat(secondRecord.outcome()).isEqualTo("CREATED");
    assertThat(recordLocation(Long.parseLong(secondRecord.record().id())))
        .containsEntry("storage_version_id", second.toVersionId());
  }

  @Test
  void shouldPublishEmptyVersionAndKeepOldRecordInPlace() {
    long scopeId = createScope("n3-empty-publish");
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "N3R1");
    FormAdminViews.PublicationView v1 = publish(scopeId, "N3R1");
    FormCreateResponse created = createIssueReply(scopeId, v1.toVersionId());
    long recordId = Long.parseLong(created.record().id());
    Map<String, Object> locationBefore =
        jdbcTemplate.queryForMap(
            "select storage_version_id, origin_version_id, instance_id, request_key,"
                + " create_payload_hash, created_at from form_record_locations where record_id = ?",
            recordId);
    long revisionBefore =
        jdbcTemplate.queryForObject(
            "select source_set_revision from form_template_scopes where id = ?", Long.class, scopeId);

    boundDraft(scopeId, relabeledSpec(FormTemplateSpecCatalog.ISSUE_REPLY, "N3 新版"), "N3R2");
    FormAdminViews.PublicationView v2 = publish(scopeId, "N3R2");

    assertThat(v2.stage()).isEqualTo("PUBLISHED");
    assertThat(versionStatus(v1.toVersionId())).isEqualTo("RETIRED");
    assertThat(versionRole(v1.toVersionId())).isEqualTo("AUTHORITATIVE");
    assertThat(versionStatus(v2.toVersionId())).isEqualTo("PUBLISHED");
    assertThat(versionRole(v2.toVersionId())).isEqualTo("AUTHORITATIVE");
    assertThat(countTable(recordTable(v2.toVersionId()))).isZero();
    assertThat(countTable(instanceTable(v2.toVersionId()))).isZero();
    assertThat(countTable(recordTable(v1.toVersionId()))).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select current_version_id from form_template_scopes where id = ?",
                Long.class,
                scopeId))
        .isEqualTo(v2.toVersionId());
    assertThat(
            jdbcTemplate.queryForObject(
                "select source_set_revision from form_template_scopes where id = ?", Long.class, scopeId))
        .isEqualTo(revisionBefore + 1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select source_set_hash from form_template_scopes where id = ?", String.class, scopeId))
        .isNotBlank();
    assertThat(
            jdbcTemplate.queryForMap(
                "select storage_version_id, origin_version_id, instance_id, request_key,"
                    + " create_payload_hash, created_at from form_record_locations where record_id = ?",
                recordId))
        .isEqualTo(locationBefore);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_directory_events where scope_id = ?"
                    + " and source_set_revision = ? and event_type in ('VERSION_ADDED','CURRENT_CHANGED')",
                Integer.class,
                scopeId,
                revisionBefore + 1))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_change_heads where scope_id = ?"
                    + " and pending_storage_version_id = ? and pending_source_set_revision = ?",
                Integer.class,
                scopeId,
                v2.toVersionId(),
                revisionBefore + 1))
        .isEqualTo(
            jdbcTemplate.queryForObject(
                "select count(*) from form_change_heads where scope_id = ?", Integer.class, scopeId));

    FormRecordView edited =
        formRecordService.update(
            created.record().id(),
            new com.data.collection.platform.entity.form.FormUpdateRequest(
                GITLAB_URL,
                PROJECT_ID,
                "issue",
                Long.toString(ISSUE_IID),
                FormTemplateSpecCatalog.ISSUE_REPLY,
                Long.toString(scopeId),
                Long.toString(v1.toVersionId()),
                Long.toString(v1.toVersionId()),
                created.record().recordVersion(),
                toFields(issueReplyFields())),
            admin,
            "127.0.0.1",
            "JUnit");
    assertThat(edited.recordVersion()).isEqualTo(created.record().recordVersion() + 1);

    FormCreateResponse v2Record = createIssueReply(scopeId, v2.toVersionId());
    assertThat(v2Record.outcome()).isEqualTo("CREATED");
    assertThat(
            jdbcTemplate.queryForObject(
                "select storage_version_id from form_record_locations where record_id = ?",
                Long.class,
                Long.parseLong(v2Record.record().id())))
        .isEqualTo(v2.toVersionId());
  }

  @Test
  void shouldReplayOldRequestKeyAcrossEmptyPublishAndRejectUnwrittenOldVersion() {
    long scopeId = createScope("n3-old-key");
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "N3R1");
    FormAdminViews.PublicationView v1 = publish(scopeId, "N3R1");
    String key = UUID.randomUUID().toString();
    FormCreateResponse first =
        formRecordService.create(
            issueReplyRequest(scopeId, v1.toVersionId(), key, issueReplyFields()),
            admin,
            "127.0.0.1",
            "JUnit");
    assertThat(first.outcome()).isEqualTo("CREATED");

    boundDraft(scopeId, relabeledSpec(FormTemplateSpecCatalog.ISSUE_REPLY, "N3 新版"), "N3R2");
    FormAdminViews.PublicationView v2 = publish(scopeId, "N3R2");

    FormCreateResponse replay =
        formRecordService.create(
            issueReplyRequest(scopeId, v1.toVersionId(), key, issueReplyFields()),
            admin,
            "127.0.0.1",
            "JUnit");
    assertThat(replay.outcome()).isEqualTo("REPLAYED");
    assertThat(replay.record().id()).isEqualTo(first.record().id());

    String unwrittenKey = UUID.randomUUID().toString();
    assertThatThrownBy(
            () ->
                formRecordService.create(
                    issueReplyRequest(scopeId, v1.toVersionId(), unwrittenKey, issueReplyFields()),
                    admin,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(FormConflictException.class);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_record_locations where scope_id = ? and request_key = ?",
                Integer.class,
                scopeId,
                unwrittenKey))
        .isZero();

    FormCreateResponse newVersion =
        formRecordService.create(
            issueReplyRequest(scopeId, v2.toVersionId(), UUID.randomUUID().toString(), issueReplyFields()),
            admin,
            "127.0.0.1",
            "JUnit");
    assertThat(newVersion.outcome()).isEqualTo("CREATED");
  }

  @Test
  void shouldAllowWritesDuringBuildAndFenceOnlyDuringSwitch() {
    long scopeId = createScope("n3-short-fence");
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "N3R1");
    FormAdminViews.PublicationView v1 = publish(scopeId, "N3R1");
    createIssueReply(scopeId, v1.toVersionId());

    boundDraft(scopeId, relabeledSpec(FormTemplateSpecCatalog.ISSUE_REPLY, "N3 新版"), "N3R2");
    FormAdminViews.PublicationView task = preparePublication(scopeId);
    FormPublicationTxService.Claim claim = publicationTxService.claim(task.id(), "n3-owner");
    assertThat(claim).isNotNull();
    assertThat(claim.stage()).isEqualTo("BUILDING");
    assertThat(scopeFenced(scopeId)).isFalse();
    assertThat(createIssueReply(scopeId, v1.toVersionId()).outcome()).isEqualTo("CREATED");

    publicationTxService.build(claim);
    publicationTxService.enterSwitch(claim);
    assertThat(scopeFenced(scopeId)).isTrue();
    assertThatThrownBy(() -> createIssueReply(scopeId, v1.toVersionId()))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("停写");
    assertThatThrownBy(() -> createIssueReply(scopeId, claim.toVersionId()))
        .isInstanceOf(FormConflictException.class);

    publicationTxService.applySwitch(claim);
    assertThat(scopeFenced(scopeId)).isFalse();
    FormAdminViews.PublicationView published = publicationService.publicationView(task.id());
    assertThat(published.stage()).isEqualTo("PUBLISHED");
    assertThat(createIssueReply(scopeId, claim.toVersionId()).outcome()).isEqualTo("CREATED");
    assertThatThrownBy(() -> createIssueReply(scopeId, v1.toVersionId()))
        .isInstanceOf(FormConflictException.class);
  }

  @Test
  void shouldTakeOverExpiredBuildAndSwitch() {
    long scopeId = createScope("n3-takeover");
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "N3R1");
    FormAdminViews.PublicationView v1 = publish(scopeId, "N3R1");
    createIssueReply(scopeId, v1.toVersionId());

    boundDraft(scopeId, relabeledSpec(FormTemplateSpecCatalog.ISSUE_REPLY, "N3 新版A"), "N3R2");
    FormAdminViews.PublicationView task = preparePublication(scopeId);
    FormPublicationTxService.Claim first = publicationTxService.claim(task.id(), "n3-owner-a");
    assertThat(first).isNotNull();
    expireLease(task.id());
    FormPublicationTxService.Claim takeover = publicationTxService.claim(task.id(), "n3-owner-b");
    assertThat(takeover).isNotNull();
    assertThat(takeover.attemptNo()).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "select phase from form_template_publication_attempts"
                    + " where publication_id = ? and attempt_no = 2",
                String.class,
                task.id()))
        .isEqualTo("BUILD_TAKEOVER");
    assertThatThrownBy(() -> publicationTxService.build(first))
        .isInstanceOf(IllegalStateException.class);
    publicationTxService.build(takeover);
    publicationTxService.enterSwitch(takeover);
    expireLease(task.id());
    FormPublicationTxService.Claim switchTakeover =
        publicationTxService.claim(task.id(), "n3-owner-c");
    assertThat(switchTakeover).isNotNull();
    assertThat(switchTakeover.stage()).isEqualTo("SWITCHING");
    assertThat(
            jdbcTemplate.queryForObject(
                "select phase from form_template_publication_attempts"
                    + " where publication_id = ? and attempt_no = 3",
                String.class,
                task.id()))
        .isEqualTo("SWITCH_TAKEOVER");
    assertThatThrownBy(() -> publicationTxService.applySwitch(takeover))
        .isInstanceOf(IllegalStateException.class);
    publicationTxService.applySwitch(switchTakeover);
    assertThat(publicationService.publicationView(task.id()).stage()).isEqualTo("PUBLISHED");
    assertThat(scopeFenced(scopeId)).isFalse();
    assertThat(countTable(recordTable(v1.toVersionId()))).isEqualTo(1);
  }

  @Test
  void shouldRollbackSwitchAtomicallyWhenApplyTransactionAborts() {
    long scopeId = createScope("n3-crash");
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "N3R1");
    FormAdminViews.PublicationView v1 = publish(scopeId, "N3R1");
    createIssueReply(scopeId, v1.toVersionId());

    boundDraft(scopeId, relabeledSpec(FormTemplateSpecCatalog.ISSUE_REPLY, "N3 新版"), "N3R2");
    FormAdminViews.PublicationView task = preparePublication(scopeId);
    FormPublicationTxService.Claim claim = publicationTxService.claim(task.id(), "n3-crash-owner");
    publicationTxService.build(claim);
    publicationTxService.enterSwitch(claim);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(jdbcTemplate.getDataSource()));

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      publicationTxService.applySwitch(claim);
                      status.setRollbackOnly();
                      throw new IllegalStateException("n3 simulated crash");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated crash");

    assertThat(
            jdbcTemplate.queryForObject(
                "select current_version_id from form_template_scopes where id = ?",
                Long.class,
                scopeId))
        .isEqualTo(v1.toVersionId());
    assertThat(versionStatus(claim.toVersionId())).isEqualTo("INSTALLED");
    assertThat(versionRole(claim.toVersionId())).isEqualTo("TARGET");
    assertThat(publicationService.publicationView(task.id()).stage()).isEqualTo("SWITCHING");
    assertThat(scopeFenced(scopeId)).isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_directory_events where scope_id = ?"
                    + " and source_set_revision > (select source_set_revision from form_template_scopes where id = ?)",
                Integer.class,
                scopeId,
                scopeId))
        .isZero();

    publicationTxService.applySwitch(claim);
    assertThat(publicationService.publicationView(task.id()).stage()).isEqualTo("PUBLISHED");
    assertThat(scopeFenced(scopeId)).isFalse();
    assertThat(countTable(recordTable(v1.toVersionId()))).isEqualTo(1);
  }

  @Test
  void shouldRejectDriftedTargetAndRecoverOnlyWithUnchangedInput() {
    long scopeId = createScope("n3-drift");
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "N3R1");
    FormAdminViews.PublicationView v1 = publish(scopeId, "N3R1");
    createIssueReply(scopeId, v1.toVersionId());

    boundDraft(scopeId, relabeledSpec(FormTemplateSpecCatalog.ISSUE_REPLY, "N3 新版"), "N3R2");
    FormAdminViews.PublicationView task = preparePublication(scopeId);
    FormPublicationTxService.Claim claim = publicationTxService.claim(task.id(), "n3-drift-owner");
    publicationTxService.build(claim);
    jdbcTemplate.execute(
        "alter table " + recordTable(claim.toVersionId()) + " add column n3_drift_probe text");
    assertThatThrownBy(() -> publicationTxService.enterSwitch(claim))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("漂移");
    assertThat(publicationTxService.fail(claim, "目标结构漂移")).isTrue();
    assertThat(scopeFenced(scopeId)).isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "select current_version_id from form_template_scopes where id = ?",
                Long.class,
                scopeId))
        .isEqualTo(v1.toVersionId());
    assertThatThrownBy(() -> publicationService.recover(task.id(), admin))
        .isInstanceOf(FormConflictException.class);
  }

  @Test
  void shouldRejectSourceSetDriftBeforeSwitchAndRequireNewPreview() {
    long scopeId = createScope("n3-source-drift");
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "N3R1");
    FormAdminViews.PublicationView v1 = publish(scopeId, "N3R1");
    createIssueReply(scopeId, v1.toVersionId());

    boundDraft(scopeId, relabeledSpec(FormTemplateSpecCatalog.ISSUE_REPLY, "N3 新版"), "N3R2");
    FormAdminViews.PublicationView task = preparePublication(scopeId);
    FormPublicationTxService.Claim claim = publicationTxService.claim(task.id(), "n3-source-owner");
    publicationTxService.build(claim);
    jdbcTemplate.update(
        "update form_template_scopes set source_set_revision = source_set_revision + 1,"
            + " source_set_hash = repeat('f', 64) where id = ?",
        scopeId);
    assertThatThrownBy(() -> publicationTxService.enterSwitch(claim))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("源集合已变化");
    assertThat(publicationTxService.fail(claim, "源集合已变化")).isTrue();
    assertThatThrownBy(() -> publicationService.recover(task.id(), admin))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("源集合已变化");
    assertThat(scopeFenced(scopeId)).isFalse();
  }

  @Test
  void shouldRefuseLegacyProtocolTasksAndCloseOnlyWithoutLiveLease() {
    long scopeId = createScope("n3-legacy-task");
    boundDraft(scopeId, seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY), "N3R1");
    FormAdminViews.PublicationView v1 = publish(scopeId, "N3R1");

    boundDraft(scopeId, relabeledSpec(FormTemplateSpecCatalog.ISSUE_REPLY, "N3 新版"), "N3R2");
    FormAdminViews.PublicationView task = preparePublication(scopeId);
    jdbcTemplate.update(
        "update form_template_publications set protocol_version = 1, stage = 'PREPARED',"
            + " lease_owner = null, lease_until = null, fence_token = null where id = ?",
        task.id());

    assertThat(publicationTxService.claimablePublicationId()).isNull();
    assertThat(publicationTxService.claim(task.id(), "n3-v2-worker")).isNull();
    assertThat(publicationTxService.listLegacyPublications())
        .extracting(FormAdminViews.PublicationView::id)
        .containsExactly(task.id());

    jdbcTemplate.update(
        "update form_template_publications set lease_owner = 'legacy-owner',"
            + " lease_until = clock_timestamp() + interval '5 minutes' where id = ?",
        task.id());
    assertThat(publicationTxService.closeLegacyPublication(task.id(), "n3-operator")).isFalse();
    jdbcTemplate.update(
        "update form_template_publications set lease_until = clock_timestamp() - interval '1 minute'"
            + " where id = ?",
        task.id());
    assertThat(publicationTxService.closeLegacyPublication(task.id(), "n3-operator")).isTrue();
    FormAdminViews.PublicationView closed = publicationService.publicationView(task.id());
    assertThat(closed.stage()).isEqualTo("FAILED");
    assertThat(closed.lastError()).isEqualTo("v6_no_copy_protocol");
    assertThat(
            jdbcTemplate.queryForObject(
                "select protocol_version from form_template_publication_attempts"
                    + " where publication_id = ? and phase = 'FAILED' order by id desc limit 1",
                Integer.class,
                task.id()))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select current_version_id from form_template_scopes where id = ?",
                Long.class,
                scopeId))
        .isEqualTo(v1.toVersionId());
  }

  // ------------------------------------------------------------------ helpers

  private FormAdminViews.PublicationView preparePublication(long scopeId) {
    FormAdminViews.PreviewView preview =
        adminService.preview(scopeId, currentRevision(scopeId), admin);
    assertThat(preview.blockingErrors()).isEmpty();
    return adminService.createPublication(
        scopeId,
        new FormPublicationCreateRequest(
            currentRevision(scopeId),
            preview.id(),
            adminService.scope(scopeId).scope().currentVersionId(),
            UUID.randomUUID().toString()),
        admin);
  }

  private ObjectNode relabeledSpec(String templateKey, String label) {
    ObjectNode spec = seedSpec(templateKey);
    ((ObjectNode) ((ArrayNode) spec.get("fields")).get(0)).put("label", label);
    return spec;
  }

  private String recordTable(long versionId) {
    return jdbcTemplate.queryForObject(
        "select record_table from form_template_versions where id = ?", String.class, versionId);
  }

  private String instanceTable(long versionId) {
    return jdbcTemplate.queryForObject(
        "select instance_table from form_template_versions where id = ?", String.class, versionId);
  }

  private String versionStatus(long versionId) {
    return jdbcTemplate.queryForObject(
        "select version_status from form_template_versions where id = ?", String.class, versionId);
  }

  private String versionRole(long versionId) {
    return jdbcTemplate.queryForObject(
        "select record_source_role from form_template_versions where id = ?",
        String.class,
        versionId);
  }

  private int countTable(String table) {
    return jdbcTemplate.queryForObject("select count(*) from \"" + table + "\"", Integer.class);
  }

  private boolean scopeFenced(long scopeId) {
    return Boolean.TRUE.equals(
        jdbcTemplate.queryForObject(
            "select write_fenced from form_template_scopes where id = ?", Boolean.class, scopeId));
  }

  private void expireLease(long publicationId) {
    jdbcTemplate.update(
        "update form_template_publications set lease_until = clock_timestamp() - interval '1 minute'"
            + " where id = ?",
        publicationId);
  }

  private long createScope(String scopeKey) {
    return createScope(FormTemplateSpecCatalog.ISSUE_REPLY, scopeKey);
  }

  private long createScope(String templateKey, String scopeKey) {
    return adminService
        .createScope(new FormScopeCreateRequest(templateKey, scopeKey), admin)
        .scope()
        .id();
  }

  private String publicationKind(long publicationId) {
    return jdbcTemplate.queryForObject(
        "select publication_kind from form_template_publications where id = ?",
        String.class,
        publicationId);
  }

  private long currentVersionId(long scopeId) {
    Long current =
        jdbcTemplate.queryForObject(
            "select current_version_id from form_template_scopes where id = ?",
            Long.class,
            scopeId);
    return current == null ? -1 : current;
  }

  private long sourceSetRevision(long scopeId) {
    return jdbcTemplate.queryForObject(
        "select source_set_revision from form_template_scopes where id = ?", Long.class, scopeId);
  }

  private int directoryEventCount(long scopeId, long revision) {
    return jdbcTemplate.queryForObject(
        "select count(*) from form_directory_events where scope_id = ?"
            + " and source_set_revision = ? and event_type in ('VERSION_ADDED','CURRENT_CHANGED')",
        Integer.class,
        scopeId,
        revision);
  }

  private int totalHeadCount(long scopeId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from form_change_heads where scope_id = ?", Integer.class, scopeId);
  }

  private int pendingHeadCount(long scopeId, long versionId, long revision) {
    return jdbcTemplate.queryForObject(
        "select count(*) from form_change_heads where scope_id = ?"
            + " and pending_storage_version_id = ? and pending_source_set_revision = ?",
        Integer.class,
        scopeId,
        versionId,
        revision);
  }

  private Map<String, Object> recordLocation(long recordId) {
    return jdbcTemplate.queryForMap(
        "select storage_version_id, origin_version_id, instance_id, request_key,"
            + " create_payload_hash, created_at from form_record_locations where record_id = ?",
        recordId);
  }

  private FormCreateResponse createRecord(String templateKey, long scopeId, long versionId) {
    boolean mergeRequest = FormTemplateSpecCatalog.CODE_REVIEW.equals(templateKey);
    if (mergeRequest) {
      jdbcTemplate.update(
          "insert into platform_ldap_users(user_id, real_name) values ('w4-admin', '管理员')"
              + " on conflict (user_id) do update set real_name = excluded.real_name");
      seedMergeRequest(MERGE_REQUEST_IID);
    }    ObjectNode fields =
        switch (templateKey) {
          case FormTemplateSpecCatalog.CODE_REVIEW -> codeReviewFields();
          case FormTemplateSpecCatalog.ISSUE_RESPONSE -> issueResponseFields();
          default -> issueReplyFields();
        };
    return formRecordService.create(
        new FormCreateRequest(
            GITLAB_URL,
            PROJECT_ID,
            mergeRequest ? "merge_request" : "issue",
            Long.toString(mergeRequest ? MERGE_REQUEST_IID : ISSUE_IID),
            templateKey,
            Long.toString(scopeId),
            Long.toString(versionId),
            UUID.randomUUID().toString(),
            mergeRequest,
            toFields(fields)),
        admin,
        "127.0.0.1",
        "JUnit");
  }

  private void seedMergeRequest(long iid) {
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_merge_requests(
            id bigint primary key,
            target_project_id bigint,
            iid bigint,
            mirror_deleted boolean default false)
        """);
    jdbcTemplate.update(
        "delete from ods_gitlab_merge_requests where target_project_id = ? and iid = ?",
        PROJECT_ID,
        iid);
    jdbcTemplate.update(
        "insert into ods_gitlab_merge_requests(id, target_project_id, iid, mirror_deleted)"
            + " values (?, ?, ?, false)",
        9000L + iid,
        PROJECT_ID,
        iid);
  }

  private ObjectNode codeReviewFields() {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("reviewerUserId", "w4-admin");
    node.put("reviewDurationMinutes", 30);
    node.put("specificationCount", 1);
    node.put("logicCount", 0);
    node.put("performanceCount", 0);
    node.put("designCount", 0);
    node.put("otherCount", 0);
    node.putNull("supplementNote");
    return node;
  }

  private ObjectNode issueResponseFields() {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("problemType", "DEFECT");
    node.put("problemCause", "原因");
    node.put("modificationPlan", "方案");
    node.put("ownerConfirmation", "已确认");
    node.put("plannedResolutionDate", "2026-12-01");
    node.put("plannedMergeVersionBranch", "CC2026R1");
    node.putNull("supplementNote");
    return node;
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

  private ObjectNode customConditionSpec(String mutualConflict) {
    ObjectNode spec = seedSpec(FormTemplateSpecCatalog.ISSUE_REPLY);
    ArrayNode fields = (ArrayNode) spec.get("fields");

    ObjectNode tags = fields.addObject();
    tags.put("name", "custom_a");
    tags.put("apiKey", "customA");
    tags.put("label", "自定义多选");
    tags.put("type", "enum-array");
    tags.put("control", "checkbox");
    tags.put("required", false);
    ArrayNode tagOptions = tags.putArray("options");
    for (String value : List.of("A", "B", "C")) {
      ObjectNode option = tagOptions.addObject();
      option.put("value", value);
      option.put("label", "选项" + value);
    }
    tags.putObject("storage").put("column", "custom_a").put("columnType", "text[]");

    ObjectNode count = fields.addObject();
    count.put("name", "custom_count");
    count.put("apiKey", "customCount");
    count.put("label", "自定义数量");
    count.put("type", "integer");
    count.put("control", "number");
    count.put("required", false);
    count.put("min", 0);
    count.put("max", 100);
    count.putObject("storage").put("column", "custom_count").put("columnType", "integer");

    ObjectNode flag = fields.addObject();
    flag.put("name", "custom_flag");
    flag.put("apiKey", "customFlag");
    flag.put("label", "自定义开关");
    flag.put("type", "boolean");
    flag.put("control", "switch");
    flag.put("required", false);
    flag.putObject("storage").put("column", "custom_flag").put("columnType", "boolean");

    ArrayNode conditions = (ArrayNode) spec.get("conditions");
    ObjectNode mutual = conditions.addObject();
    mutual.put("type", "mutuallyExclusive");
    mutual.put("field", "custom_a");
    mutual.put("whenContains", "A");
    mutual.putArray("withAnyOf").add(mutualConflict);

    ObjectNode countCondition = conditions.addObject();
    countCondition.put("type", "conditionalRequired");
    countCondition.put("target", "custom_count");
    countCondition.putObject("requiredWhen").put("field", "has_residual_impact").put("equals", true);
    countCondition.putObject("clearWhen").put("field", "has_residual_impact").put("equals", false);

    ObjectNode flagCondition = conditions.addObject();
    flagCondition.put("type", "conditionalRequired");
    flagCondition.put("target", "custom_flag");
    flagCondition
        .putObject("requiredWhen")
        .put("field", "fix_status")
        .put("equals", "RESOLVED");
    return spec;
  }

  private ObjectNode customReplyFields(String... tags) {
    ObjectNode fields = issueReplyFields();
    fields.set("customA", array(tags));
    fields.putNull("customCount");
    fields.put("customFlag", false);
    return fields;
  }

  private FormCreateResponse createCustomReply(
      long scopeId, long versionId, ObjectNode fields, String requestKey) {
    return formRecordService.create(
        new FormCreateRequest(
            GITLAB_URL,
            PROJECT_ID,
            "issue",
            Long.toString(ISSUE_IID),
            FormTemplateSpecCatalog.ISSUE_REPLY,
            Long.toString(scopeId),
            Long.toString(versionId),
            requestKey,
            false,
            toFields(fields)),
        admin,
        "127.0.0.1",
        "JUnit");
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
