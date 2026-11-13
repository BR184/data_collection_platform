package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.common.exception.FormConflictException;
import com.data.collection.platform.common.exception.FormPayloadTooLargeException;
import com.data.collection.platform.domain.form.FormScopeBinding;
import com.data.collection.platform.domain.form.FormSourceUrlSupport;
import com.data.collection.platform.domain.form.FormTemplateSpec;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.domain.form.FormVersionSchema;
import com.data.collection.platform.entity.CollectFormNotificationPayloadResponse;
import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.FormCreateRequest;
import com.data.collection.platform.entity.form.FormCreateResponse;
import com.data.collection.platform.entity.form.FormDetailResponse;
import com.data.collection.platform.entity.form.FormRecordView;
import com.data.collection.platform.entity.form.FormReviewerCandidatesResponse;
import com.data.collection.platform.entity.form.FormUpdateRequest;
import com.data.collection.platform.entity.form.FormVoidRequest;
import com.data.collection.platform.entity.form.admin.FormAdminViews;
import com.data.collection.platform.entity.form.admin.FormPublicationCreateRequest;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.service.GitlabResourceLinkService;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = {
      "platform.background-jobs.enabled=false",
      "platform.form-directory-event.worker-enabled=false",
      "platform.gitlab-mirror.scheduler-enabled=false",
      "platform.gitlab-mirror.delete-reconciliation-enabled=false",
      "platform.gitlab-mirror.customer-issue-delay-pre-writeback-sync-enabled=false",
      "platform.gitlab-mirror.customer-issue-delay-writeback-worker-enabled=false",
      "platform.gitlab-mirror.code-review-metric-enrichment-enabled=false",
      "platform.review-data.search-index-backfill-enabled=false",
      "platform.auth.provider=local",
      "platform.auth.secure-config-required=false"
    })
class FormRecordServiceIntegrationTest {

  private static final String GITLAB_URL = "http://gitlab.w2.local";
  private static final long PROJECT_ID = 9L;
  private static final long MR_IID = 12L;
  private static final long ISSUE_IID = 21L;
  private static final long ISSUE_IID_LEVEL2 = 22L;
  private static final long MR_ROOT = 9001L;
  private static final long ISSUE_ROOT = 9101L;
  private static final long ISSUE_ROOT_LEVEL2 = 9102L;

  private static final Set<String> ALL_PERMISSIONS =
      Set.of(
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
  @Autowired private FormRecordService formRecordService;
  @Autowired private FormRecordContextService contextService;
  @Autowired private FormReviewerDirectoryService reviewerDirectory;
  @Autowired private FormTemplateSpecCatalog specCatalog;
  @Autowired private FormVersionSchemaService schemaService;
  @Autowired private GitlabResourceLinkService resourceLinkService;
  @Autowired private FormTemplateAdminService adminService;
  @Autowired private FormPublicationTxService publicationTxService;
  @Autowired private com.data.collection.platform.service.IssueFactPersistenceService factPersistence;
  @Autowired private com.data.collection.platform.mapper.IssueFactMapper factMapper;
  @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
  @Autowired private FormIssueCommentContentService commentContentService;
  @Autowired private FormIssueSettingsService issueSettings;
  @Autowired private com.data.collection.platform.service.FactBuildService factBuildService;
  @Autowired private com.data.collection.platform.service.FormIssueFactAdapter formFactAdapter;

  private long sourceConfigId;
  private long codeReviewScopeId;
  private long codeReviewVersionId;
  private String codeReviewRecordTable;
  private long issueResponseScopeId;
  private long issueResponseVersionId;
  private long issueReplyScopeId;
  private long issueReplyVersionId;

  private final FormActorContext actorA =
      new FormActorContext("w2-actor-a", "操作员甲", ALL_PERMISSIONS);
  private final FormActorContext actorB =
      new FormActorContext("w2-actor-b", "操作员乙", ALL_PERMISSIONS);

  @BeforeEach
  void setUp() {
    dropFailureTriggers();
    dropDynamicTables();
    jdbcTemplate.execute("truncate table form_template_scopes restart identity cascade");
    jdbcTemplate.execute("delete from gitlab_sync_configs where name like 'w2-form-%'");
    jdbcTemplate.execute("delete from platform_ldap_users where user_id like 'w2-%'");
    jdbcTemplate.execute("select setval('form_record_id_seq', 1, false)");
    com.data.collection.platform.service.GitlabIssueMirrorFixture.ensureSchema(jdbcTemplate);
    ensureOdsTables();
    jdbcTemplate.execute("delete from ods_gitlab_merge_requests");
    jdbcTemplate.execute("delete from ods_gitlab_issues");
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
    resourceLinkService.clearCache();
    jdbcTemplate.update(
        "delete from issue_fact where issue_id in (?, ?)", ISSUE_ROOT, ISSUE_ROOT_LEVEL2);

    sourceConfigId =
        jdbcTemplate.queryForObject(
            """
            insert into gitlab_sync_configs(name, db_name, db_username, db_password, web_base_url)
            values ('w2-form-source', 'qaflex', 'qaflex', 'secret', ?)
            returning id
            """,
            Long.class,
            GITLAB_URL);
    insertUser("w2-reviewer-1", "张三", "研发一部");
    insertUser("w2-reviewer-2", "李四", "研发二部");
    insertUser("w2-actor-a", "操作员甲", "质量部");
    insertUser("w2-actor-b", "操作员乙", "质量部");

    jdbcTemplate.update(
        "insert into ods_gitlab_merge_requests(id, target_project_id, iid, mirror_deleted)"
            + " values (?, ?, ?, false)",
        MR_ROOT,
        PROJECT_ID,
        MR_IID);
    jdbcTemplate.update(
        "insert into ods_gitlab_issues(id, project_id, iid, mirror_deleted)"
            + " values (?, ?, ?, false), (?, ?, ?, false)",
        ISSUE_ROOT,
        PROJECT_ID,
        ISSUE_IID,
        ISSUE_ROOT_LEVEL2,
        PROJECT_ID,
        ISSUE_IID_LEVEL2);
    insertIssueFact(ISSUE_ROOT, ISSUE_IID, "LEVEL1");
    insertIssueFact(ISSUE_ROOT_LEVEL2, ISSUE_IID_LEVEL2, "LEVEL2");

    codeReviewScopeId =
        createScope("w2-code-review", FormTemplateSpecCatalog.CODE_REVIEW, "merge_request");
    codeReviewVersionId = createVersionAndTables(codeReviewScopeId, FormTemplateSpecCatalog.CODE_REVIEW);
    codeReviewRecordTable = recordTable(codeReviewScopeId, codeReviewVersionId);

    issueResponseScopeId =
        createScope("w2-issue-response", FormTemplateSpecCatalog.ISSUE_RESPONSE, "issue");
    issueResponseVersionId =
        createVersionAndTables(issueResponseScopeId, FormTemplateSpecCatalog.ISSUE_RESPONSE);

    issueReplyScopeId =
        createScope("w2-issue-reply", FormTemplateSpecCatalog.ISSUE_REPLY, "issue");
    issueReplyVersionId =
        createVersionAndTables(issueReplyScopeId, FormTemplateSpecCatalog.ISSUE_REPLY);
  }

  @Test
  void issueFormWriteAdvancesIssueFactHeadEvenWhenOdsUnchanged() {
    ObjectNode fields =
        issueResponseFields("DEFECT", "负责人已确认", "2026-10-20", "CC2026R1");

    createIssueResponse(ISSUE_IID, fields, UUID.randomUUID().toString());

    Map<String, Object> head =
        jdbcTemplate.queryForMap(
            "select latest_change_version, published_version from fact_change_heads"
                + " where source_instance = 'default' and fact_type = 'ISSUE' and root_id = ?",
            ISSUE_ROOT);
    assertThat(((Number) head.get("latest_change_version")).longValue())
        .as("ODS 未变化时表单写入也必须登记议题事实待发布变化")
        .isGreaterThan(((Number) head.get("published_version")).longValue());
  }

  @Test
  void shouldCreateIndependentRecordsForParallelFirstSaves() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<FormCreateResponse> first =
          pool.submit(
              () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return createCodeReview("w2-reviewer-1", UUID.randomUUID().toString());
              });
      Future<FormCreateResponse> second =
          pool.submit(
              () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return createCodeReview("w2-reviewer-2", UUID.randomUUID().toString());
              });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      FormCreateResponse left = first.get(20, TimeUnit.SECONDS);
      FormCreateResponse right = second.get(20, TimeUnit.SECONDS);
      assertThat(left.outcome()).isEqualTo("CREATED");
      assertThat(right.outcome()).isEqualTo("CREATED");
      assertThat(left.record().id()).isNotEqualTo(right.record().id());
      assertThat(count(codeReviewRecordTable)).isEqualTo(2);
      assertThat(count("form_s" + codeReviewScopeId + "_v" + codeReviewVersionId + "_instances"))
          .isEqualTo(1);
      assertThat(count("form_record_audit_logs")).isEqualTo(2);
      Map<String, Object> head =
          jdbcTemplate.queryForMap(
              "select latest_change_version, pending_record_id from form_change_heads"
                  + " where template_key = 'code_review' and resource_id = ?",
              MR_IID);
      assertThat(((Number) head.get("latest_change_version")).longValue()).isEqualTo(2L);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void shouldConvergeConcurrentSameKeyRetriesToOneRecord() throws Exception {
    String key = UUID.randomUUID().toString();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<FormCreateResponse> left =
          pool.submit(
              () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return createCodeReview("w2-reviewer-1", key);
              });
      Future<FormCreateResponse> right =
          pool.submit(
              () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return createCodeReview("w2-reviewer-1", key);
              });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      FormCreateResponse first = left.get(20, TimeUnit.SECONDS);
      FormCreateResponse second = right.get(20, TimeUnit.SECONDS);
      assertThat(List.of(first.outcome(), second.outcome()))
          .containsExactlyInAnyOrder("CREATED", "REPLAYED");
      assertThat(first.record().id()).isEqualTo(second.record().id());
      assertThat(count(codeReviewRecordTable)).isEqualTo(1);
      assertThat(count("form_record_audit_logs")).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void shouldAllowOnlyOneConcurrentEditAtSameRecordVersion() throws Exception {
    FormRecordView created =
        createCodeReview("w2-reviewer-1", UUID.randomUUID().toString()).record();
    ObjectNode leftFields = codeReviewFields("w2-reviewer-1");
    leftFields.put("specificationCount", 3);
    ObjectNode rightFields = codeReviewFields("w2-reviewer-1");
    rightFields.put("specificationCount", 4);

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<FormRecordView> left =
          pool.submit(
              () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return formRecordService.update(
                    created.id(),
                    updateRequest(codeReviewScopeId, codeReviewVersionId, 1, leftFields),
                    actorA,
                    "127.0.0.1",
                    "JUnit");
              });
      Future<FormRecordView> right =
          pool.submit(
              () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return formRecordService.update(
                    created.id(),
                    updateRequest(codeReviewScopeId, codeReviewVersionId, 1, rightFields),
                    actorA,
                    "127.0.0.1",
                    "JUnit");
              });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      FormRecordView winner;
      try {
        winner = left.get(20, TimeUnit.SECONDS);
        assertThatThrownBy(() -> right.get(20, TimeUnit.SECONDS))
            .hasRootCauseInstanceOf(FormConflictException.class);
      } catch (java.util.concurrent.ExecutionException error) {
        assertThat(error.getCause()).isInstanceOf(FormConflictException.class);
        winner = right.get(20, TimeUnit.SECONDS);
      }
      assertThat(winner.recordVersion()).isEqualTo(2);
      assertThat(winner.fields().get("specificationCount")).isIn(3, 4);
      assertThat(
              jdbcTemplate.queryForObject(
                  "select record_version from " + codeReviewRecordTable + " where id = ?",
                  Integer.class,
                  Long.parseLong(created.id())))
          .isEqualTo(2);
      assertThat(
              jdbcTemplate.queryForObject(
                  "select count(*) from form_record_audit_logs"
                      + " where record_id = ? and operation = 'UPDATE'",
                  Integer.class,
                  Long.parseLong(created.id())))
          .isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void shouldEditDifferentRecordsInParallelWithoutLosingVersions() throws Exception {
    FormRecordView first =
        createCodeReview("w2-reviewer-1", UUID.randomUUID().toString()).record();
    FormRecordView second =
        createCodeReview("w2-reviewer-2", UUID.randomUUID().toString()).record();
    ObjectNode leftFields = codeReviewFields("w2-reviewer-1");
    leftFields.put("specificationCount", 3);
    ObjectNode rightFields = codeReviewFields("w2-reviewer-2");
    rightFields.put("specificationCount", 4);

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<FormRecordView> left =
          pool.submit(
              () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return formRecordService.update(
                    first.id(),
                    updateRequest(codeReviewScopeId, codeReviewVersionId, 1, leftFields),
                    actorA,
                    "127.0.0.1",
                    "JUnit");
              });
      Future<FormRecordView> right =
          pool.submit(
              () -> {
                ready.countDown();
                start.await(10, TimeUnit.SECONDS);
                return formRecordService.update(
                    second.id(),
                    updateRequest(codeReviewScopeId, codeReviewVersionId, 1, rightFields),
                    actorA,
                    "127.0.0.1",
                    "JUnit");
              });
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      FormRecordView leftResult = left.get(20, TimeUnit.SECONDS);
      FormRecordView rightResult = right.get(20, TimeUnit.SECONDS);
      assertThat(leftResult.recordVersion()).isEqualTo(2);
      assertThat(rightResult.recordVersion()).isEqualTo(2);
      assertThat(leftResult.fields().get("specificationCount")).isEqualTo(3);
      assertThat(rightResult.fields().get("specificationCount")).isEqualTo(4);
      assertThat(jdbcTemplate.queryForObject(
          "select count(*) from form_record_audit_logs where operation = 'UPDATE'",
          Integer.class))
          .isEqualTo(2);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void shouldNotResurrectVoidedRecordAndOnlyAuditVoidOnce() {
    FormRecordView created =
        createCodeReview("w2-reviewer-1", UUID.randomUUID().toString()).record();
    FormRecordView voided =
        formRecordService.voidRecord(
            created.id(),
            voidRequest(codeReviewScopeId, codeReviewVersionId, 1),
            actorA,
            "127.0.0.1",
            "JUnit");
    assertThat(voided.deleted()).isTrue();
    assertThat(voided.recordVersion()).isEqualTo(2);

    FormRecordView replayedVoid =
        formRecordService.voidRecord(
            created.id(),
            voidRequest(codeReviewScopeId, codeReviewVersionId, 2),
            actorA,
            "127.0.0.1",
            "JUnit");
    assertThat(replayedVoid.deleted()).isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_record_audit_logs"
                    + " where record_id = ? and operation = 'VOID'",
                Integer.class,
                Long.parseLong(created.id())))
        .isEqualTo(1);

    ObjectNode changed = codeReviewFields("w2-reviewer-1");
    assertThatThrownBy(
            () ->
                formRecordService.update(
                    created.id(),
                    updateRequest(codeReviewScopeId, codeReviewVersionId, 2, changed),
                    actorA,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("已作废");
    assertThat(
            jdbcTemplate.queryForObject(
                "select deleted from " + codeReviewRecordTable + " where id = ?",
                Boolean.class,
                Long.parseLong(created.id())))
        .isTrue();
  }

  @Test
  void shouldReplaySameRequestAndRejectSameKeyFromAnotherAccount() throws Exception {
    String key = UUID.randomUUID().toString();
    FormCreateResponse created = createCodeReview("w2-reviewer-1", key);
    FormCreateResponse replayed = createCodeReview("w2-reviewer-1", key);
    assertThat(created.outcome()).isEqualTo("CREATED");
    assertThat(replayed.outcome()).isEqualTo("REPLAYED");
    assertThat(replayed.record().id()).isEqualTo(created.record().id());
    assertThat(count(codeReviewRecordTable)).isEqualTo(1);
    assertThat(count("form_record_audit_logs")).isEqualTo(1);

    assertThatThrownBy(
            () ->
                formRecordService.create(
                    codeReviewRequest("w2-reviewer-1", key), actorB, "127.0.0.1", "JUnit"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("请求标识与首次创建账号不一致");
    assertThat(count(codeReviewRecordTable)).isEqualTo(1);
  }

  @Test
  void shouldCanonicalizeRequestKeyAndReplayAcrossCase() {
    String key = UUID.randomUUID().toString().toUpperCase();
    FormCreateResponse created = createCodeReview("w2-reviewer-1", key);
    FormCreateResponse replayed = createCodeReview("w2-reviewer-1", key.toLowerCase());
    assertThat(created.outcome()).isEqualTo("CREATED");
    assertThat(replayed.outcome()).isEqualTo("REPLAYED");
    assertThat(
            jdbcTemplate.queryForObject(
                "select request_key from " + codeReviewRecordTable, String.class))
        .isEqualTo(key.toLowerCase());
  }

  @Test
  void shouldRejectStaleEditAndLeaveOtherRecordsUntouched() {
    FormRecordView first = createCodeReview("w2-reviewer-1", UUID.randomUUID().toString()).record();
    FormRecordView second = createCodeReview("w2-reviewer-2", UUID.randomUUID().toString()).record();

    ObjectNode changed = codeReviewFields("w2-reviewer-1");
    changed.put("specificationCount", 7);
    FormRecordView updated =
        formRecordService.update(
            first.id(),
            updateRequest(codeReviewScopeId, codeReviewVersionId, 1, changed),
            actorA,
            "127.0.0.1",
            "JUnit");
    assertThat(updated.recordVersion()).isEqualTo(2);
    assertThat(updated.fields().get("specificationCount")).isEqualTo(7);

    assertThatThrownBy(
            () ->
                formRecordService.update(
                    first.id(),
                    updateRequest(codeReviewScopeId, codeReviewVersionId, 1, changed),
                    actorA,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("记录已被他人修改");

    FormDetailResponse detail =
        formRecordService.detail(
            FormTemplateSpecCatalog.CODE_REVIEW, GITLAB_URL, PROJECT_ID, "merge_request",
            Long.toString(MR_IID), actorA);
    FormRecordView reloadedFirst =
        detail.records().stream().filter(row -> row.id().equals(first.id())).findFirst().orElseThrow();
    FormRecordView reloadedSecond =
        detail.records().stream().filter(row -> row.id().equals(second.id())).findFirst().orElseThrow();
    assertThat(reloadedFirst.fields().get("specificationCount")).isEqualTo(7);
    assertThat(reloadedSecond.fields().get("specificationCount")).isEqualTo(1);
    assertThat(reloadedSecond.recordVersion()).isEqualTo(1);
  }

  @Test
  void shouldRequireConfirmForSameReviewerWithoutWriting() {
    String firstKey = UUID.randomUUID().toString();
    createCodeReview("w2-reviewer-1", firstKey);
    FormCreateResponse confirm =
        createCodeReview("w2-reviewer-1", UUID.randomUUID().toString());
    assertThat(confirm.outcome()).isEqualTo("CONFIRM_REQUIRED");
    assertThat(confirm.reviewerUserId()).isEqualTo("w2-reviewer-1");
    assertThat(confirm.record()).isNull();
    assertThat(count(codeReviewRecordTable)).isEqualTo(1);

    FormCreateRequest confirmedRequest =
        codeReviewRequest("w2-reviewer-1", UUID.randomUUID().toString(), true);
    FormCreateResponse confirmed =
        formRecordService.create(confirmedRequest, actorA, "127.0.0.1", "JUnit");
    assertThat(confirmed.outcome()).isEqualTo("CREATED");
    assertThat(count(codeReviewRecordTable)).isEqualTo(2);
  }

  @Test
  void shouldRollbackAllWritesWhenAuditFails() {
    installFailureTrigger("w2_fail_audit", "form_record_audit_logs");
    try {
      assertThatThrownBy(
              () -> createCodeReview("w2-reviewer-1", UUID.randomUUID().toString()))
          .isInstanceOf(RuntimeException.class);
      assertThat(count(codeReviewRecordTable)).isZero();
      assertThat(count("form_s" + codeReviewScopeId + "_v" + codeReviewVersionId + "_instances"))
          .isZero();
      assertThat(count("form_record_audit_logs")).isZero();
      assertThat(count("form_change_heads")).isZero();
    } finally {
      dropFailureTriggers();
    }
  }

  @Test
  void shouldRollbackRecordWhenHeadFails() {
    installFailureTrigger("w2_fail_head", "form_change_heads");
    try {
      assertThatThrownBy(
              () -> createCodeReview("w2-reviewer-1", UUID.randomUUID().toString()))
          .isInstanceOf(RuntimeException.class);
      assertThat(count(codeReviewRecordTable)).isZero();
      assertThat(count("form_s" + codeReviewScopeId + "_v" + codeReviewVersionId + "_instances"))
          .isZero();
      assertThat(count("form_record_audit_logs")).isZero();
    } finally {
      dropFailureTriggers();
    }
  }

  @Test
  void shouldRejectOtherUserAndUnownedHistory() {
    FormRecordView own = createCodeReview("w2-reviewer-1", UUID.randomUUID().toString()).record();
    ObjectNode changed = codeReviewFields("w2-reviewer-1");
    changed.put("specificationCount", 9);
    assertThatThrownBy(
            () ->
                formRecordService.update(
                    own.id(),
                    updateRequest(codeReviewScopeId, codeReviewVersionId, 1, changed),
                    actorB,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                formRecordService.voidRecord(
                    own.id(),
                    voidRequest(codeReviewScopeId, codeReviewVersionId, 1),
                    actorB,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(AccessDeniedException.class);

    Long instanceId =
        jdbcTemplate.queryForObject(
            "select instance_id from " + codeReviewRecordTable + " where id = ?",
            Long.class,
            Long.parseLong(own.id()));
    Long legacyId =
        jdbcTemplate.queryForObject(
            """
            insert into %s(
                instance_id, origin_version_id, template_version, created_by, request_key,
                create_payload_hash, record_version, deleted, legacy_incomplete,
                legacy_missing_fields, created_at, updated_at, reviewer_user_id, reviewer_name,
                review_duration_minutes, specification_count, logic_count, performance_count,
                design_count, other_count, supplement_note)
            values (?, ?, null, null, null, null, 1, false, false, '{}', ?, ?, 'w2-reviewer-1',
                    '张三', 10, 1, 0, 0, 0, 0, null)
            returning id
            """
                .formatted(codeReviewRecordTable),
            Long.class,
            instanceId,
            codeReviewVersionId,
            Timestamp.valueOf(LocalDateTime.now()),
            Timestamp.valueOf(LocalDateTime.now()));
    assertThatThrownBy(
            () ->
                formRecordService.update(
                    Long.toString(legacyId),
                    updateRequest(codeReviewScopeId, codeReviewVersionId, 1, changed),
                    actorA,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                formRecordService.voidRecord(
                    Long.toString(legacyId),
                    voidRequest(codeReviewScopeId, codeReviewVersionId, 1),
                    actorA,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void shouldRejectActorWithoutRecordPermissionsWithoutWriting() {
    FormRecordView own =
        createCodeReview("w2-reviewer-1", UUID.randomUUID().toString()).record();
    FormActorContext noPermission =
        new FormActorContext("w2-actor-none", "无权限账号", Set.of());
    ObjectNode changed = codeReviewFields("w2-reviewer-1");
    changed.put("specificationCount", 9);
    int before = count("form_record_audit_logs");

    assertThatThrownBy(
            () ->
                formRecordService.detail(
                    FormTemplateSpecCatalog.CODE_REVIEW, GITLAB_URL, PROJECT_ID, "merge_request",
                    Long.toString(MR_IID), noPermission))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                formRecordService.create(
                    codeReviewRequest("w2-reviewer-1", UUID.randomUUID().toString()),
                    noPermission,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                formRecordService.update(
                    own.id(),
                    updateRequest(codeReviewScopeId, codeReviewVersionId, 1, changed),
                    noPermission,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                formRecordService.voidRecord(
                    own.id(),
                    voidRequest(codeReviewScopeId, codeReviewVersionId, 1),
                    noPermission,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(count(codeReviewRecordTable)).isEqualTo(1);
    assertThat(count("form_record_audit_logs")).isEqualTo(before);
  }

  @Test
  void shouldValidateIssueResponseSnapshotDateAndPlanBranch() {
    ObjectNode level1 = issueResponseFields("DEFECT", null, "2026-10-20", "CC2026R1 & CC2027R2");
    assertThatThrownBy(() -> createIssueResponse(ISSUE_IID, level1, UUID.randomUUID().toString()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("模块负责人确认");

    ObjectNode confirmed = issueResponseFields("DEFECT", "负责人已确认", "2026-10-20", "CC2026R1 & CC2027R2");
    FormCreateResponse created =
        createIssueResponse(ISSUE_IID, confirmed, UUID.randomUUID().toString());
    assertThat(created.outcome()).isEqualTo("CREATED");
    assertThat(created.record().snapshotValues().get("owner_confirmation_applicable")).isTrue();

    ObjectNode level2WithConfirmation =
        issueResponseFields("DEFECT", "不应填写", "2026-10-20", "CC2026R1");
    assertThatThrownBy(
            () ->
                createIssueResponse(
                    ISSUE_IID_LEVEL2, level2WithConfirmation, UUID.randomUUID().toString()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("必须为空");

    ObjectNode level2 =
        issueResponseFields("REQUIREMENT", null, "2026-10-20", "CC2026R1");
    FormCreateResponse level2Created =
        createIssueResponse(ISSUE_IID_LEVEL2, level2, UUID.randomUUID().toString());
    assertThat(level2Created.record().snapshotValues().get("owner_confirmation_applicable"))
        .isFalse();

    ObjectNode oldDate = issueResponseFields("DEFECT", null, "2019-12-31", "CC2026R1");
    assertThatThrownBy(
            () -> createIssueResponse(ISSUE_IID_LEVEL2, oldDate, UUID.randomUUID().toString()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("年份");

    ObjectNode badBranch = issueResponseFields("DEFECT", null, "2026-10-20", "dev、CC2026R4SP1");
    assertThatThrownBy(
            () -> createIssueResponse(ISSUE_IID_LEVEL2, badBranch, UUID.randomUUID().toString()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("半角 &");
  }

  @Test
  void shouldPersistAndEditFreeBranchNamesWithAmpersandSeparators() {
    ObjectNode fields = issueResponseFields("DEFECT", null, "2026-10-20", "dev & CC2026R4SP1");
    FormRecordView created = createIssueResponse(ISSUE_IID_LEVEL2, fields, UUID.randomUUID().toString()).record();
    assertThat(created.fields().get("plannedMergeVersionBranch")).isEqualTo("dev & CC2026R4SP1");
    fields.put("plannedMergeVersionBranch", "release/2027&hotfix/login-error");
    FormRecordView updated = formRecordService.update(created.id(),
        new FormUpdateRequest(GITLAB_URL, PROJECT_ID, "issue", Long.toString(ISSUE_IID_LEVEL2),
            FormTemplateSpecCatalog.ISSUE_RESPONSE, Long.toString(issueResponseScopeId),
            Long.toString(issueResponseVersionId), Long.toString(issueResponseVersionId), 1,
            toFields(fields)), actorA, "127.0.0.1", "JUnit");
    assertThat(updated.fields().get("plannedMergeVersionBranch")).isEqualTo("release/2027&hotfix/login-error");
    assertThat(updated.recordVersion()).isEqualTo(2);
  }

  @Test
  void shouldValidateIssueReplyConditionsAndNormalizeDictionaryOrder() {
    ObjectNode noneAndKnown = issueReplyFields();
    noneAndKnown.set("affectedKinds", array("NONE", "KNOWN"));
    noneAndKnown.put("knownAffectedFunctions", "受影响功能");
    assertThatThrownBy(
            () -> createIssueReply(noneAndKnown, UUID.randomUUID().toString()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("互斥");

    ObjectNode knownWithoutText = issueReplyFields();
    knownWithoutText.set("affectedKinds", array("KNOWN"));
    assertThatThrownBy(
            () -> createIssueReply(knownWithoutText, UUID.randomUUID().toString()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("已知的受影响功能说明");

    ObjectNode residualWithoutDetail = issueReplyFields();
    residualWithoutDetail.put("hasResidualImpact", true);
    assertThatThrownBy(
            () -> createIssueReply(residualWithoutDetail, UUID.randomUUID().toString()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("遗留问题或潜在影响说明");

    ObjectNode residualWithDetail = issueReplyFields();
    residualWithDetail.put("hasResidualImpact", false);
    residualWithDetail.put("residualImpactDetail", "不应填写");
    assertThatThrownBy(
            () -> createIssueReply(residualWithDetail, UUID.randomUUID().toString()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("必须为空");

    ObjectNode valid = issueReplyFields();
    valid.set(
        "causeKeys",
        array("missing_requirement", "demand_misunderstand", "missing_requirement"));
    valid.set("affectedKinds", array("KNOWN"));
    valid.put("knownAffectedFunctions", "受影响功能");
    FormCreateResponse created =
        createIssueReply(valid, UUID.randomUUID().toString());
    assertThat(created.outcome()).isEqualTo("CREATED");
    assertThat(created.record().fields().get("causeKeys"))
        .isEqualTo(List.of("demand_misunderstand", "missing_requirement"));
    assertThat(created.record().fields().get("affectedKinds"))
        .isEqualTo(List.of("KNOWN"));
  }

  @Test
  void shouldReturnDetailCapabilitiesAndReviewerCandidates() {
    FormRecordView created =
        createCodeReview("w2-reviewer-1", UUID.randomUUID().toString()).record();
    FormDetailResponse ownDetail =
        formRecordService.detail(
            FormTemplateSpecCatalog.CODE_REVIEW, GITLAB_URL, PROJECT_ID, "merge_request",
            Long.toString(MR_IID), actorA);
    assertThat(ownDetail.records()).hasSize(1);
    assertThat(ownDetail.records().getFirst().id()).isEqualTo(created.id());
    assertThat(ownDetail.records().getFirst().canEdit()).isTrue();
    assertThat(ownDetail.records().getFirst().fieldNames().get("reviewerUserId")).isEqualTo("张三");
    assertThat(ownDetail.account().userId()).isEqualTo("w2-actor-a");
    assertThat(ownDetail.account().displayName()).isEqualTo("操作员甲");
    assertThat(ownDetail.scopeId()).isEqualTo(Long.toString(codeReviewScopeId));
    assertThat(ownDetail.storageVersionId()).isEqualTo(Long.toString(codeReviewVersionId));
    assertThat(ownDetail.instanceId()).isNotBlank();
    assertThat(ownDetail.resource().rootId()).isEqualTo(Long.toString(MR_ROOT));
    assertThat(ownDetail.capabilities().canCreate()).isTrue();

    FormDetailResponse otherDetail =
        formRecordService.detail(
            FormTemplateSpecCatalog.CODE_REVIEW, GITLAB_URL, PROJECT_ID, "merge_request",
            Long.toString(MR_IID), actorB);
    assertThat(otherDetail.records().getFirst().canEdit()).isFalse();
    assertThat(otherDetail.records().getFirst().canVoid()).isFalse();

    FormReviewerCandidatesResponse page =
        formRecordService.reviewerCandidates("reviewer", 1, null, actorA);
    assertThat(page.items()).hasSize(1);
    assertThat(page.nextCursor()).isNotBlank();
    assertThat(page.currentUserCandidate().userId()).isEqualTo("w2-actor-a");
    assertThat(page.currentUserCandidate().realName()).isEqualTo("操作员甲");
  }

  @Test
  void shouldRejectWritesOnRetiredScopeButKeepHistoryReadable() {
    FormRecordView created =
        createCodeReview("w2-reviewer-1", UUID.randomUUID().toString()).record();
    jdbcTemplate.update(
        "update form_template_scopes set status = 'RETIRED' where id = ?", codeReviewScopeId);
    FormDetailResponse detail =
        formRecordService.detail(
            FormTemplateSpecCatalog.CODE_REVIEW, GITLAB_URL, PROJECT_ID, "merge_request",
            Long.toString(MR_IID), actorA);
    assertThat(detail.writable()).isFalse();
    assertThat(detail.records()).hasSize(1);
    assertThat(detail.records().getFirst().canEdit()).isFalse();

    ObjectNode changed = codeReviewFields("w2-reviewer-1");
    assertThatThrownBy(
            () ->
                formRecordService.update(
                    created.id(),
                    updateRequest(codeReviewScopeId, codeReviewVersionId, 1, changed),
                    actorA,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("已停用");
  }

  @Test
  void shouldRejectUnmirroredResourceAndOversizedPayload() {
    ObjectNode fields = codeReviewFields("w2-reviewer-1");
    FormCreateRequest missingResource =
        new FormCreateRequest(
            GITLAB_URL,
            PROJECT_ID,
            "merge_request",
            "999",
            FormTemplateSpecCatalog.CODE_REVIEW,
            Long.toString(codeReviewScopeId),
            Long.toString(codeReviewVersionId),
            UUID.randomUUID().toString(),
            false,
            toFields(fields));
    assertThatThrownBy(
            () -> formRecordService.create(missingResource, actorA, "127.0.0.1", "JUnit"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("资源待同步");

    ObjectNode huge = codeReviewFields("w2-reviewer-1");
    huge.put("supplementNote", "x".repeat(1_100_000));
    assertThatThrownBy(
            () ->
                formRecordService.create(
                    new FormCreateRequest(
                        GITLAB_URL,
                        PROJECT_ID,
                        "merge_request",
                        Long.toString(MR_IID),
                        FormTemplateSpecCatalog.CODE_REVIEW,
                        Long.toString(codeReviewScopeId),
                        Long.toString(codeReviewVersionId),
                        UUID.randomUUID().toString(),
                        false,
                        toFields(huge)),
                    actorA,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(FormPayloadTooLargeException.class);
  }

  @Test
  void shouldRejectOutOfRangeRawIntegersWithoutWriting() throws Exception {
    ObjectNode huge = codeReviewFields("w2-reviewer-1");
    huge.set("reviewDurationMinutes", objectMapper.readTree("18446744073709551617"));
    assertThatThrownBy(
            () ->
                formRecordService.create(
                    new FormCreateRequest(
                        GITLAB_URL,
                        PROJECT_ID,
                        "merge_request",
                        Long.toString(MR_IID),
                        FormTemplateSpecCatalog.CODE_REVIEW,
                        Long.toString(codeReviewScopeId),
                        Long.toString(codeReviewVersionId),
                        UUID.randomUUID().toString(),
                        false,
                        toFields(huge)),
                    actorA,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("超出整数范围");
    assertThat(count(codeReviewRecordTable)).isZero();
    assertThat(count("form_s" + codeReviewScopeId + "_v" + codeReviewVersionId + "_instances"))
        .isZero();
    assertThat(count("form_record_audit_logs")).isZero();
    assertThat(count("form_change_heads")).isZero();

    FormRecordView created = createCodeReview("w2-reviewer-1", UUID.randomUUID().toString()).record();
    ObjectNode beyond = codeReviewFields("w2-reviewer-1");
    beyond.put("specificationCount", 2147483648L);
    assertThatThrownBy(
            () ->
                formRecordService.update(
                    created.id(),
                    updateRequest(codeReviewScopeId, codeReviewVersionId, 1, beyond),
                    actorA,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("超出整数范围");
    assertThat(count(codeReviewRecordTable)).isEqualTo(1);
    assertThat(count("form_record_audit_logs")).isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select record_version from " + codeReviewRecordTable, Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "select latest_change_version from form_change_heads"
                    + " where template_key = 'code_review' and resource_id = ?",
                Long.class,
                MR_IID))
        .isEqualTo(1L);

    ObjectNode atLimit = codeReviewFields("w2-reviewer-1");
    atLimit.put("specificationCount", 2147483647);
    FormRecordView updated =
        formRecordService.update(
            created.id(),
            updateRequest(codeReviewScopeId, codeReviewVersionId, 1, atLimit),
            actorA,
            "127.0.0.1",
            "JUnit");
    assertThat(updated.fields().get("specificationCount")).isEqualTo(2147483647);
  }

  @Test
  void shouldValidateNotificationPayloadExternalContract() {
    CollectFormNotificationPayloadResponse payload =
        formRecordService.notificationPayload(
            GITLAB_URL, PROJECT_ID, MR_IID, "merge_request", actorA);
    assertThat(payload.sourceAddress()).isEqualTo(GITLAB_URL);
    assertThat(payload.projectId()).isEqualTo(PROJECT_ID);
    assertThat(payload.requestIid()).isEqualTo(MR_IID);
    assertThat(payload.resourceType()).isEqualTo("merge_request");

    assertThatThrownBy(
            () ->
                formRecordService.notificationPayload(
                    "http://unknown.w2.local", PROJECT_ID, MR_IID, "merge_request", actorA))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("来源地址未登记");
  }

  @Test
  void shouldRejectCreateWhenScopeWriteFenced() {
    jdbcTemplate.update(
        "update form_template_scopes set write_fenced = true where id = ?", codeReviewScopeId);

    assertThatThrownBy(
            () -> createCodeReview("w2-reviewer-1", UUID.randomUUID().toString()))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("停写");

    assertThat(count(codeReviewRecordTable)).isZero();
    assertThat(count("form_s" + codeReviewScopeId + "_v" + codeReviewVersionId + "_instances"))
        .isZero();
    assertThat(count("form_record_audit_logs")).isZero();
    assertThat(count("form_change_heads")).isZero();
  }

  @Test
  void shouldRejectEditAndVoidWhenScopeWriteFencedButKeepReadableVersion() {
    FormRecordView created =
        createCodeReview("w2-reviewer-1", UUID.randomUUID().toString()).record();
    jdbcTemplate.update(
        "update form_template_scopes set write_fenced = true where id = ?", codeReviewScopeId);

    ObjectNode changed = codeReviewFields("w2-reviewer-1");
    changed.put("specificationCount", 9);
    assertThatThrownBy(
            () ->
                formRecordService.update(
                    created.id(),
                    updateRequest(codeReviewScopeId, codeReviewVersionId, 1, changed),
                    actorA,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("停写");
    assertThatThrownBy(
            () ->
                formRecordService.voidRecord(
                    created.id(),
                    voidRequest(codeReviewScopeId, codeReviewVersionId, 1),
                    actorA,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("停写");

    Map<String, Object> stored = storedRecord(created.id());
    assertThat(((Number) stored.get("record_version")).intValue()).isEqualTo(1);
    assertThat(stored.get("deleted")).isEqualTo(false);
    assertThat(((Number) stored.get("specification_count")).intValue()).isEqualTo(1);
    assertThat(count("form_record_audit_logs")).isEqualTo(1);
    assertThat(latestChangeVersion()).isEqualTo(1);

    FormDetailResponse detail =
        formRecordService.detail(
            FormTemplateSpecCatalog.CODE_REVIEW,
            GITLAB_URL,
            PROJECT_ID,
            "merge_request",
            Long.toString(MR_IID),
            actorA);
    assertThat(detail.records()).hasSize(1);
    assertThat(detail.records().getFirst().id()).isEqualTo(created.id());
    assertThat(detail.records().getFirst().recordVersion()).isEqualTo(1);
  }

  @Test
  void shouldRejectWriteThatAcquiresScopeLockAfterFenceIsSet() throws Exception {
    DataSourceTransactionManager transactions =
        new DataSourceTransactionManager(jdbcTemplate.getDataSource());
    TransactionTemplate transaction = new TransactionTemplate(transactions);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    java.util.concurrent.atomic.AtomicReference<Future<?>> writerRef =
        new java.util.concurrent.atomic.AtomicReference<>();
    try {
      transaction.executeWithoutResult(
          status -> {
            // 模拟发布任务持有范围行锁；写线程必须先等待，再在取得共享锁后看到栅栏。
            jdbcTemplate.queryForObject(
                "select id from form_template_scopes where id = ? for update",
                Long.class,
                codeReviewScopeId);
            long blockerPid = jdbcTemplate.queryForObject("select pg_backend_pid()", Long.class);
            writerRef.set(
                pool.submit(
                    () ->
                        assertThatThrownBy(
                                () ->
                                    createCodeReview(
                                        "w2-reviewer-1", UUID.randomUUID().toString()))
                            .isInstanceOf(FormConflictException.class)
                            .hasMessageContaining("停写")));
            awaitScopeLockWait(writerRef.get(), blockerPid);
            jdbcTemplate.update(
                "update form_template_scopes"
                    + " set write_fenced = true, write_fence_reason = 'W2 审核交错'"
                    + " where id = ?",
                codeReviewScopeId);
          });
      writerRef.get().get(20, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    assertThat(count(codeReviewRecordTable)).isZero();
    assertThat(count("form_record_audit_logs")).isZero();
    assertThat(count("form_change_heads")).isZero();
  }

  @Test
  void shouldCreateRecordOnlyAfterTakingSequenceLockFirst() throws Exception {
    DataSourceTransactionManager transactions =
        new DataSourceTransactionManager(jdbcTemplate.getDataSource());
    TransactionTemplate transaction = new TransactionTemplate(transactions);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Holder holder = startAdvisoryHolder(pool, transaction);
      Future<FormCreateResponse> writer =
          pool.submit(() -> createCodeReview("w2-reviewer-1", UUID.randomUUID().toString()));
      long waiterPid = awaitAdvisoryWait(holder.pid(), writer);
      assertNoScopeRowLock(waiterPid);
      holder.release().countDown();
      FormCreateResponse created = writer.get(20, TimeUnit.SECONDS);
      assertThat(created.outcome()).isEqualTo("CREATED");
      assertThat(count(codeReviewRecordTable)).isEqualTo(1);
      assertThat(count("form_record_audit_logs")).isEqualTo(1);
      assertThat(count("form_change_heads")).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void shouldSwitchVersionOnlyAfterTakingSequenceLockFirst() throws Exception {
    createCodeReview("w2-reviewer-1", UUID.randomUUID().toString());
    FormPublicationTxService.Claim claim = prepareVerifiedMigration();
    int auditsBefore = count("form_record_audit_logs");

    DataSourceTransactionManager transactions =
        new DataSourceTransactionManager(jdbcTemplate.getDataSource());
    TransactionTemplate transaction = new TransactionTemplate(transactions);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Holder holder = startAdvisoryHolder(pool, transaction);
      Future<?> switcher = pool.submit(() -> publicationTxService.applySwitch(claim));
      long waiterPid = awaitAdvisoryWait(holder.pid(), switcher);
      assertNoScopeRowLock(waiterPid);
      holder.release().countDown();
      switcher.get(30, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    assertThat(
            jdbcTemplate.queryForObject(
                "select current_version_id from form_template_scopes where id = ?",
                Long.class,
                codeReviewScopeId))
        .isEqualTo(claim.toVersionId());
    assertThat(
            jdbcTemplate.queryForObject(
                "select write_fenced from form_template_scopes where id = ?",
                Boolean.class,
                codeReviewScopeId))
        .isFalse();
    assertThat(
            jdbcTemplate.queryForObject(
                "select stage from form_template_publications where id = ?",
                String.class,
                claim.publicationId()))
        .isEqualTo("PUBLISHED");
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_change_heads where pending_storage_version_id = ?",
                Integer.class,
                claim.toVersionId()))
        .isEqualTo(1);
    assertThat(count("form_record_audit_logs")).isEqualTo(auditsBefore);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from " + recordTableForVersion(claim.toVersionId()),
                Integer.class))
        .isZero();
  }

  @Test
  void shouldCopyRecordMigrationWithoutDeadlockAgainstScopeThenTaskClaimPrefix() throws Exception {
    createCodeReview("w2-reviewer-1", UUID.randomUUID().toString());
    FormPublicationTxService.Claim claim = prepareClaimedMigration();
    DataSourceTransactionManager transactions =
        new DataSourceTransactionManager(jdbcTemplate.getDataSource());
    TransactionTemplate transaction = new TransactionTemplate(transactions);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    java.util.concurrent.atomic.AtomicReference<Future<?>> copyFuture =
        new java.util.concurrent.atomic.AtomicReference<>();
    try {
      Future<?> claimPrefix =
          pool.submit(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        jdbcTemplate.queryForObject(
                            "select id from form_template_scopes where id = ? for update",
                            Long.class,
                            codeReviewScopeId);
                        copyFuture.set(
                            pool.submit(() -> publicationTxService.build(claim)));
                        try {
                          Thread.sleep(800);
                        } catch (InterruptedException error) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(error);
                        }
                        jdbcTemplate.queryForObject(
                            "select id from form_template_publications where id = ? for update",
                            Long.class,
                            claim.publicationId());
                      }));
      claimPrefix.get(30, TimeUnit.SECONDS);
      copyFuture.get().get(60, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }

    assertThat(
            jdbcTemplate.queryForObject(
                "select stage from form_template_publications where id = ?",
                String.class,
                claim.publicationId()))
        .isEqualTo("BUILDING");
    assertThat(count(recordTableForVersion(claim.toVersionId()))).isZero();
  }
  @Test
  void shouldRunDifferentScopeCreateAndSwitchWithoutDeadlock() throws Exception {
    createCodeReview("w2-reviewer-1", UUID.randomUUID().toString());
    FormPublicationTxService.Claim claim = prepareVerifiedMigration();
    DataSourceTransactionManager transactions =
        new DataSourceTransactionManager(jdbcTemplate.getDataSource());
    TransactionTemplate transaction = new TransactionTemplate(transactions);
    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
      Holder holder = startAdvisoryHolder(pool, transaction);
      Future<?> switcher = pool.submit(() -> publicationTxService.applySwitch(claim));
      Future<FormCreateResponse> writer =
          pool.submit(() -> createIssueReply(issueReplyFields(), UUID.randomUUID().toString()));
      awaitAdvisoryWait(holder.pid(), switcher);
      awaitAdvisoryWait(holder.pid(), writer);
      holder.release().countDown();
      switcher.get(30, TimeUnit.SECONDS);
      FormCreateResponse created = writer.get(30, TimeUnit.SECONDS);
      assertThat(created.outcome()).isEqualTo("CREATED");
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * 构造已领取但尚未构建的 v2 任务，用于构建锁序交错验证。
   */
  private FormPublicationTxService.Claim prepareClaimedMigration() {
    Map<String, Object> scope =
        jdbcTemplate.queryForMap(
            """
            select source_config_id, source_instance, canonical_gitlab_base_url, project_ids::text,
                   version_project_id, binding_hash, current_version_id
              from form_template_scopes where id = ?
            """,
            codeReviewScopeId);
    ObjectNode spec;
    try {
      spec =
          (ObjectNode)
              objectMapper.readTree(specCatalog.rawJson(FormTemplateSpecCatalog.CODE_REVIEW));
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    for (JsonNode field : spec.withArray("fields")) {
      if ("reviewer_user_id".equals(field.path("name").asText())) {
        ((ObjectNode) field).put("label", "走查人（R01）");
      }
    }
    jdbcTemplate.update(
        """
        insert into form_template_scope_drafts(
            scope_id, draft_revision, field_spec, source_config_id, source_instance,
            canonical_gitlab_base_url, project_ids, version_project_id, version_label,
            version_evidence, binding_hash, migration_spec, updated_by, updated_at)
        values (?, 1, ?::jsonb, ?, ?, ?, ?::jsonb, ?, 'R01', ?::jsonb, ?, null, 'junit', ?)
        """,
        codeReviewScopeId,
        spec.toString(),
        scope.get("source_config_id"),
        scope.get("source_instance"),
        scope.get("canonical_gitlab_base_url"),
        scope.get("project_ids"),
        scope.get("version_project_id"),
        "{\"milestoneId\":1}",
        scope.get("binding_hash"),
        Timestamp.valueOf(LocalDateTime.now()));
    FormAdminViews.PreviewView preview =
        adminService.preview(codeReviewScopeId, 1, actorA);
    assertThat(preview.blockingErrors()).isEmpty();
    FormAdminViews.PublicationView publication =
        adminService.createPublication(
            codeReviewScopeId,
            new FormPublicationCreateRequest(
                1L,
                preview.id(),
                ((Number) scope.get("current_version_id")).longValue(),
                UUID.randomUUID().toString()),
            actorA);
    return publicationTxService.claim(publication.id(), "r01-lock-test");
  }

  /**
   * 构造已完成空结构构建并进入短切换的 v2 任务，用于真实切换锁序验证。
   */
  private FormPublicationTxService.Claim prepareVerifiedMigration() {
    FormPublicationTxService.Claim claim = prepareClaimedMigration();
    publicationTxService.build(claim);
    publicationTxService.enterSwitch(claim);
    return claim;
  }

  private String recordTableForVersion(long versionId) {
    return jdbcTemplate.queryForObject(
        "select record_table from form_template_versions where id = ?",
        String.class,
        versionId);
  }

  private Holder startAdvisoryHolder(
      ExecutorService pool, TransactionTemplate transaction) throws Exception {
    java.util.concurrent.atomic.AtomicLong pid =
        new java.util.concurrent.atomic.AtomicLong();
    CountDownLatch ready = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    pool.submit(
        () ->
            transaction.executeWithoutResult(
                status -> {
                  jdbcTemplate.execute(
                      "select pg_advisory_xact_lock(hashtext('form_record_id_seq')::bigint)");
                  pid.set(jdbcTemplate.queryForObject("select pg_backend_pid()", Long.class));
                  ready.countDown();
                  try {
                    if (!release.await(20, TimeUnit.SECONDS)) {
                      throw new IllegalStateException("等待释放序列锁超时");
                    }
                  } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                  }
                }));
    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
    return new Holder(pid.get(), release);
  }

  private long awaitAdvisoryWait(long holderPid, Future<?> worker) throws Exception {
    for (int attempt = 0; attempt < 400; attempt++) {
      List<Long> waiters =
          jdbcTemplate.queryForList(
              """
              select pid from pg_stat_activity
               where datname = current_database() and pid <> ?
                 and ? = any(pg_blocking_pids(pid))
                 and query like '%pg_advisory_xact_lock%'
              """,
              Long.class,
              holderPid,
              holderPid);
      if (!waiters.isEmpty()) {
        return waiters.getFirst();
      }
      if (worker.isDone()) {
        worker.get();
        throw new AssertionError("工作线程未等待序列锁即完成");
      }
      Thread.sleep(20);
    }
    throw new AssertionError(
        "工作线程未在超时内等待序列锁："
            + jdbcTemplate.queryForList(
                "select pid, state, wait_event_type, wait_event, left(query, 160) as query"
                    + " from pg_stat_activity where datname = current_database()"));
  }

  private void assertNoScopeRowLock(long pid) {
    Integer held =
        jdbcTemplate.queryForObject(
            """
            select count(*) from pg_locks l
              join pg_class c on c.oid = l.relation
             where l.pid = ? and l.granted and c.relname = 'form_template_scopes'
            """,
            Integer.class,
            pid);
    assertThat(held).isZero();
  }

  private record Holder(long pid, CountDownLatch release) {}

  @Test
  void shouldReplayExistingRequestAfterReviewerDirectoryNameChanges() {
    String key = UUID.randomUUID().toString();
    FormCreateResponse created = createCodeReview("w2-reviewer-1", key);
    assertThat(created.outcome()).isEqualTo("CREATED");
    int audits = count("form_record_audit_logs");
    long headVersion = latestChangeVersion();

    jdbcTemplate.update(
        "update platform_ldap_users set real_name = null where user_id = 'w2-reviewer-1'");
    FormCreateResponse replayWithBlankName = createCodeReview("w2-reviewer-1", key);
    assertThat(replayWithBlankName.outcome()).isEqualTo("REPLAYED");
    assertThat(replayWithBlankName.record().id()).isEqualTo(created.record().id());
    assertThat(replayWithBlankName.record().fieldNames().get("reviewerUserId")).isEqualTo("张三");
    assertThat(count("form_record_audit_logs")).isEqualTo(audits);
    assertThat(latestChangeVersion()).isEqualTo(headVersion);

    jdbcTemplate.update("delete from platform_ldap_users where user_id = 'w2-reviewer-1'");
    FormCreateResponse replayAfterDelete = createCodeReview("w2-reviewer-1", key);
    assertThat(replayAfterDelete.outcome()).isEqualTo("REPLAYED");
    assertThat(replayAfterDelete.record().id()).isEqualTo(created.record().id());
    assertThat(count("form_record_audit_logs")).isEqualTo(audits);
    assertThat(latestChangeVersion()).isEqualTo(headVersion);

    assertThatThrownBy(
            () -> createCodeReview("w2-reviewer-1", UUID.randomUUID().toString()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("走查人不存在或姓名为空");
  }

  @Test
  void shouldReplayOriginalRequestKeyAfterFieldMigrationUsingRequestVersion() {
    String key = UUID.randomUUID().toString();
    FormCreateResponse created = createCodeReview("w2-reviewer-1", key);
    assertThat(created.outcome()).isEqualTo("CREATED");
    String originalId = created.record().id();

    VersionMigration migration = migrateCodeReviewToSecondVersion();
    FormCreateRequest oldVersionReplay =
        new FormCreateRequest(
            GITLAB_URL,
            PROJECT_ID,
            "merge_request",
            Long.toString(MR_IID),
            FormTemplateSpecCatalog.CODE_REVIEW,
            Long.toString(codeReviewScopeId),
            Long.toString(codeReviewVersionId),
            key,
            false,
            toFields(codeReviewFields("w2-reviewer-1")));
    FormCreateResponse replayed =
        formRecordService.create(oldVersionReplay, actorA, "127.0.0.1", "JUnit");

    assertThat(replayed.outcome()).isEqualTo("REPLAYED");
    assertThat(replayed.record().id()).isEqualTo(originalId);
    assertThat(replayed.record().fields()).doesNotContainKey("otherCount");
    assertThat(replayed.record().fields().get("reviewerUserId")).isEqualTo("w2-reviewer-1");
    assertThat(count(migration.oldRecordTable())).isEqualTo(1);
    assertThat(count(migration.newRecordTable())).isEqualTo(1);
    assertThat(count("form_record_audit_logs")).isEqualTo(1);

    ObjectNode changed = codeReviewFields("w2-reviewer-1");
    changed.put("specificationCount", 9);
    assertThatThrownBy(
            () ->
                formRecordService.create(
                    new FormCreateRequest(
                        GITLAB_URL,
                        PROJECT_ID,
                        "merge_request",
                        Long.toString(MR_IID),
                        FormTemplateSpecCatalog.CODE_REVIEW,
                        Long.toString(codeReviewScopeId),
                        Long.toString(codeReviewVersionId),
                        key,
                        false,
                        toFields(changed)),
                    actorA,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("首次请求载荷与目录证据不一致");

    assertThatThrownBy(
            () ->
                formRecordService.create(
                    new FormCreateRequest(
                        GITLAB_URL,
                        PROJECT_ID,
                        "merge_request",
                        Long.toString(MR_IID),
                        FormTemplateSpecCatalog.CODE_REVIEW,
                        Long.toString(codeReviewScopeId),
                        Long.toString(codeReviewVersionId),
                        UUID.randomUUID().toString(),
                        false,
                        toFields(codeReviewFields("w2-reviewer-2"))),
                    actorA,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("结构版本已变化");
    assertThat(count(migration.newRecordTable())).isEqualTo(1);
  }

  @Test
  void shouldRejectReplayWhenDirectoryEvidenceIsMissing() {
    String key = UUID.randomUUID().toString();
    FormCreateResponse created = createCodeReview("w2-reviewer-1", key);
    assertThat(created.outcome()).isEqualTo("CREATED");
    int audits = count("form_record_audit_logs");
    long headVersion = latestChangeVersion();
    jdbcTemplate.update(
        "delete from form_record_locations where record_id = ?",
        Long.parseLong(created.record().id()));

    assertThatThrownBy(() -> createCodeReview("w2-reviewer-1", key))
        .isInstanceOf(FormConflictException.class);

    assertThat(count(codeReviewRecordTable)).isEqualTo(1);
    assertThat(count("form_record_audit_logs")).isEqualTo(audits);
    assertThat(latestChangeVersion()).isEqualTo(headVersion);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_record_locations where scope_id = ?",
                Integer.class,
                codeReviewScopeId))
        .isZero();
  }

  @Test
  void shouldReplayByDirectoryOriginWhenRequestCarriesCurrentStorageVersion() {
    String key = UUID.randomUUID().toString();
    FormCreateResponse created = createCodeReview("w2-reviewer-1", key);
    assertThat(created.outcome()).isEqualTo("CREATED");
    VersionMigration migration = migrateCodeReviewToSecondVersion();

    // 请求携带承载版本 v2，但首次载荷是 v1 形状：只能按目录 origin 冻结规格核验。
    FormCreateResponse replayed =
        formRecordService.create(
            new FormCreateRequest(
                GITLAB_URL,
                PROJECT_ID,
                "merge_request",
                Long.toString(MR_IID),
                FormTemplateSpecCatalog.CODE_REVIEW,
                Long.toString(codeReviewScopeId),
                Long.toString(migration.newVersionId()),
                key,
                false,
                toFields(codeReviewFields("w2-reviewer-1"))),
            actorA,
            "127.0.0.1",
            "JUnit");

    assertThat(replayed.outcome()).isEqualTo("REPLAYED");
    assertThat(replayed.record().id()).isEqualTo(created.record().id());
    assertThat(replayed.record().fields()).doesNotContainKey("otherCount");
    assertThat(count(migration.newRecordTable())).isEqualTo(1);
    assertThat(count("form_record_audit_logs")).isEqualTo(1);
  }

  @Test
  void shouldEditOldVersionRecordByItsOwnSpecAndExposeMultiVersionDetail() {
    FormRecordView created =
        createCodeReview("w2-reviewer-1", UUID.randomUUID().toString()).record();
    VersionMigration migration = migrateCodeReviewToSecondVersion();

    FormDetailResponse detail =
        formRecordService.detail(
            FormTemplateSpecCatalog.CODE_REVIEW, GITLAB_URL, PROJECT_ID, "merge_request",
            Long.toString(MR_IID), actorA);
    assertThat(detail.currentVersionId()).isEqualTo(Long.toString(migration.newVersionId()));
    assertThat(detail.readableVersions()).singleElement()
        .satisfies(version ->
            assertThat(version.versionId()).isEqualTo(Long.toString(migration.newVersionId())));
    assertThat(detail.versionSpecs())
        .containsKey(Long.toString(migration.newVersionId()))
        .doesNotContainKey(Long.toString(codeReviewVersionId));
    assertThat(detail.records()).singleElement().satisfies(record -> {
      assertThat(record.storageVersionId()).isEqualTo(Long.toString(migration.newVersionId()));
      assertThat(record.originVersionId()).isEqualTo(Long.toString(codeReviewVersionId));
    });
    assertThat(detail.sourceSetRevision()).isNotBlank();
    assertThat(detail.sourceSetHash()).hasSize(64);

    // 承载版本已迁移到 v2：编辑必须使用 v2 冻结规格与 expectedStorageVersionId。
    ObjectNode v2Fields = codeReviewFields("w2-reviewer-1");
    v2Fields.remove("otherCount");
    v2Fields.put("reviewNote", "迁移后说明");
    FormRecordView updated =
        formRecordService.update(
            created.id(),
            updateRequest(codeReviewScopeId, migration.newVersionId(), 1, v2Fields),
            actorA,
            "127.0.0.1",
            "JUnit");
    assertThat(updated.recordVersion()).isEqualTo(2);
    assertThat(updated.fields().get("reviewNote")).isEqualTo("迁移后说明");
    assertThat(
            jdbcTemplate.queryForObject(
                "select specification_count from " + migration.oldRecordTable() + " where id = ?",
                Integer.class,
                Long.parseLong(created.id())))
        .isEqualTo(1);
    // 错报承载版本必须拒绝。
    assertThatThrownBy(
            () ->
                formRecordService.update(
                    created.id(),
                    updateRequest(codeReviewScopeId, codeReviewVersionId, 2, v2Fields),
                    actorA,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("承载版本");

    // 旧版记录按自身承载版本作废，不搬表。
    FormRecordView voided =
        formRecordService.voidRecord(
            created.id(),
            voidRequest(codeReviewScopeId, migration.newVersionId(), 2),
            actorA,
            "127.0.0.1",
            "JUnit");
    assertThat(voided.deleted()).isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select deleted from " + migration.oldRecordTable() + " where id = ?",
                Boolean.class,
                Long.parseLong(created.id())))
        .isFalse();
  }

  @Test
  void shouldCreateNewRecordOnlyInCurrentVersionAndPromptSameReviewerAcrossVersions() {
    createCodeReview("w2-reviewer-1", UUID.randomUUID().toString());
    VersionMigration migration = migrateCodeReviewToSecondVersion();

    ObjectNode v2Fields = codeReviewFields("w2-reviewer-2");
    v2Fields.remove("otherCount");
    FormCreateResponse createdInV2 =
        formRecordService.create(
            new FormCreateRequest(
                GITLAB_URL,
                PROJECT_ID,
                "merge_request",
                Long.toString(MR_IID),
                FormTemplateSpecCatalog.CODE_REVIEW,
                Long.toString(codeReviewScopeId),
                Long.toString(migration.newVersionId()),
                UUID.randomUUID().toString(),
                false,
                toFields(v2Fields)),
            actorA,
            "127.0.0.1",
            "JUnit");
    assertThat(createdInV2.outcome()).isEqualTo("CREATED");
    assertThat(createdInV2.record().storageVersionId())
        .isEqualTo(Long.toString(migration.newVersionId()));

    ObjectNode sameReviewer = codeReviewFields("w2-reviewer-1");
    sameReviewer.remove("otherCount");
    FormCreateResponse confirm =
        formRecordService.create(
            new FormCreateRequest(
                GITLAB_URL,
                PROJECT_ID,
                "merge_request",
                Long.toString(MR_IID),
                FormTemplateSpecCatalog.CODE_REVIEW,
                Long.toString(codeReviewScopeId),
                Long.toString(migration.newVersionId()),
                UUID.randomUUID().toString(),
                false,
                toFields(sameReviewer)),
            actorA,
            "127.0.0.1",
            "JUnit");
    assertThat(confirm.outcome()).isEqualTo("CONFIRM_REQUIRED");
    assertThat(confirm.reviewerUserId()).isEqualTo("w2-reviewer-1");
  }

  @Test
  void shouldEditAndVoidOriginalVersionRecordWithoutMovingTablesWhenCurrentVersionAdvances() {
    FormRecordView original =
        createCodeReview("w2-reviewer-1", UUID.randomUUID().toString()).record();
    VersionMigration published = publishSecondVersionKeepingRecords();
    String v1 = Long.toString(codeReviewVersionId);
    String v2 = Long.toString(published.newVersionId());
    assertThat(count(published.oldRecordTable())).isEqualTo(1);
    assertThat(count(published.newRecordTable())).isZero();

    FormDetailResponse detail =
        formRecordService.detail(
            FormTemplateSpecCatalog.CODE_REVIEW, GITLAB_URL, PROJECT_ID, "merge_request",
            Long.toString(MR_IID), actorA);
    assertThat(detail.currentVersionId()).isEqualTo(v2);
    assertThat(detail.readableVersions())
        .extracting(version -> version.versionId())
        .containsExactly(v1, v2);
    assertThat(detail.versionSpecs()).containsKeys(v1, v2);
    assertThat(detail.records()).singleElement().satisfies(record -> {
      assertThat(record.originVersionId()).isEqualTo(v1);
      assertThat(record.storageVersionId()).isEqualTo(v1);
      assertThat(record.fields()).containsEntry("otherCount", 0).doesNotContainKey("reviewNote");
      assertThat(record.canEdit()).isTrue();
    });

    // 原版记录按 v1 冻结规格编辑，不搬表、不补 v2 新字段。
    ObjectNode changed = codeReviewFields("w2-reviewer-1");
    changed.put("specificationCount", 6);
    changed.put("supplementNote", "原版仍可编辑");
    FormRecordView updated =
        formRecordService.update(
            original.id(),
            updateRequest(codeReviewScopeId, codeReviewVersionId, 1, changed),
            actorA,
            "127.0.0.1",
            "JUnit");
    assertThat(updated.recordVersion()).isEqualTo(2);
    assertThat(updated.storageVersionId()).isEqualTo(v1);
    assertThat(updated.originVersionId()).isEqualTo(v1);
    assertThat(updated.fields()).containsEntry("specificationCount", 6);
    assertThat(jdbcTemplate.queryForObject(
        "select specification_count from " + published.oldRecordTable() + " where id = ?",
        Integer.class,
        Long.parseLong(original.id())))
        .isEqualTo(6);
    assertThat(count(published.newRecordTable())).isZero();

    // 新记录只进入当前 v2。
    ObjectNode v2Fields = codeReviewFields("w2-reviewer-2");
    v2Fields.remove("otherCount");
    v2Fields.put("reviewNote", "v2 新字段");
    FormCreateResponse createdInV2 =
        formRecordService.create(
            new FormCreateRequest(
                GITLAB_URL,
                PROJECT_ID,
                "merge_request",
                Long.toString(MR_IID),
                FormTemplateSpecCatalog.CODE_REVIEW,
                Long.toString(codeReviewScopeId),
                v2,
                UUID.randomUUID().toString(),
                false,
                toFields(v2Fields)),
            actorA,
            "127.0.0.1",
            "JUnit");
    assertThat(createdInV2.outcome()).isEqualTo("CREATED");
    assertThat(createdInV2.record().storageVersionId()).isEqualTo(v2);
    assertThat(count(published.oldRecordTable())).isEqualTo(1);
    assertThat(count(published.newRecordTable())).isEqualTo(1);

    // 同人提示覆盖全部有效权威版本：v1 已有走查人，v2 新建同样触发确认。
    ObjectNode sameReviewer = codeReviewFields("w2-reviewer-1");
    sameReviewer.remove("otherCount");
    FormCreateResponse confirm =
        formRecordService.create(
            new FormCreateRequest(
                GITLAB_URL,
                PROJECT_ID,
                "merge_request",
                Long.toString(MR_IID),
                FormTemplateSpecCatalog.CODE_REVIEW,
                Long.toString(codeReviewScopeId),
                v2,
                UUID.randomUUID().toString(),
                false,
                toFields(sameReviewer)),
            actorA,
            "127.0.0.1",
            "JUnit");
    assertThat(confirm.outcome()).isEqualTo("CONFIRM_REQUIRED");
    assertThat(confirm.reviewerUserId()).isEqualTo("w2-reviewer-1");

    // 原版记录按自身承载版本作废，v2 记录不受影响。
    FormRecordView voided =
        formRecordService.voidRecord(
            original.id(),
            voidRequest(codeReviewScopeId, codeReviewVersionId, 2),
            actorA,
            "127.0.0.1",
            "JUnit");
    assertThat(voided.deleted()).isTrue();
    assertThat(jdbcTemplate.queryForObject(
        "select deleted from " + published.oldRecordTable() + " where id = ?",
        Boolean.class,
        Long.parseLong(original.id())))
        .isTrue();
    FormDetailResponse afterVoid =
        formRecordService.detail(
            FormTemplateSpecCatalog.CODE_REVIEW, GITLAB_URL, PROJECT_ID, "merge_request",
            Long.toString(MR_IID), actorA);
    assertThat(afterVoid.records()).singleElement()
        .satisfies(record -> assertThat(record.storageVersionId()).isEqualTo(v2));
  }

  @Test
  void shouldReplayLostCreateAfterCurrentVersionAdvancesAndRejectUnpersistedOldVersionRequest() {
    String key = UUID.randomUUID().toString();
    FormCreateResponse created = createCodeReview("w2-reviewer-1", key);
    VersionMigration published = publishSecondVersionKeepingRecords();
    int audits = count("form_record_audit_logs");

    // 首次提交结果丢失后指针已切换：旧 key 仍按原版本证据收敛到同一记录。
    FormCreateResponse replayed =
        formRecordService.create(
            new FormCreateRequest(
                GITLAB_URL,
                PROJECT_ID,
                "merge_request",
                Long.toString(MR_IID),
                FormTemplateSpecCatalog.CODE_REVIEW,
                Long.toString(codeReviewScopeId),
                Long.toString(codeReviewVersionId),
                key,
                false,
                toFields(codeReviewFields("w2-reviewer-1"))),
            actorA,
            "127.0.0.1",
            "JUnit");
    assertThat(replayed.outcome()).isEqualTo("REPLAYED");
    assertThat(replayed.record().id()).isEqualTo(created.record().id());
    assertThat(replayed.record().storageVersionId()).isEqualTo(Long.toString(codeReviewVersionId));
    assertThat(count(published.oldRecordTable())).isEqualTo(1);
    assertThat(count(published.newRecordTable())).isZero();
    assertThat(count("form_record_audit_logs")).isEqualTo(audits);

    // 真正未写入的旧版本请求不能用新 key 冒充重放，也不产生任何写入。
    assertThatThrownBy(
            () ->
                formRecordService.create(
                    new FormCreateRequest(
                        GITLAB_URL,
                        PROJECT_ID,
                        "merge_request",
                        Long.toString(MR_IID),
                        FormTemplateSpecCatalog.CODE_REVIEW,
                        Long.toString(codeReviewScopeId),
                        Long.toString(codeReviewVersionId),
                        UUID.randomUUID().toString(),
                        false,
                        toFields(codeReviewFields("w2-reviewer-2"))),
                    actorA,
                    "127.0.0.1",
                    "JUnit"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("结构版本已变化");
    assertThat(count(published.oldRecordTable())).isEqualTo(1);
    assertThat(count(published.newRecordTable())).isZero();
    assertThat(count("form_record_audit_logs")).isEqualTo(audits);
  }

  @Test
  void shouldReturnBigResourceIdentifiersAsDecimalStrings() {
    long bigProject = 9007199254740993L;
    long bigIssueIid = 9007199254740995L;
    long bigRoot = bigIssueIid + 1000;
    jdbcTemplate.update(
        "insert into ods_gitlab_projects(id, path, namespace_id, mirror_deleted)"
            + " values (?, 'big-project', 1, false)",
        bigProject);
    jdbcTemplate.update(
        "insert into ods_gitlab_issues(id, project_id, iid, mirror_deleted)"
            + " values (?, ?, ?, false)",
        bigRoot,
        bigProject,
        bigIssueIid);
    insertIssueFact(bigRoot, bigIssueIid, null);
    long bigScopeId =
        createScope("w2-issue-reply-big", FormTemplateSpecCatalog.ISSUE_REPLY, "issue");
    long bigVersionId =
        createVersionAndTables(bigScopeId, FormTemplateSpecCatalog.ISSUE_REPLY, bigProject);
    registerFormAuthority("issue", bigIssueIid, FormTemplateSpecCatalog.ISSUE_REPLY, bigScopeId,
        bigProject);

    FormCreateResponse created =
        formRecordService.create(
            new FormCreateRequest(
                GITLAB_URL,
                bigProject,
                "issue",
                Long.toString(bigIssueIid),
                FormTemplateSpecCatalog.ISSUE_REPLY,
                Long.toString(bigScopeId),
                Long.toString(bigVersionId),
                UUID.randomUUID().toString(),
                false,
                toFields(issueReplyFields())),
            actorA,
            "127.0.0.1",
            "JUnit");
    assertThat(created.outcome()).isEqualTo("CREATED");
    assertThat(created.record().storageVersionId()).isEqualTo(Long.toString(bigVersionId));

    FormDetailResponse detail =
        formRecordService.detail(
            FormTemplateSpecCatalog.ISSUE_REPLY, GITLAB_URL, bigProject, "issue",
            Long.toString(bigIssueIid), actorA);
    assertThat(detail.resource().projectId()).isEqualTo("9007199254740993");
    assertThat(detail.resource().resourceId()).isEqualTo("9007199254740995");
    assertThat(detail.resource().rootId()).isEqualTo(Long.toString(bigRoot));
    assertThat(detail.scopeId()).isEqualTo(Long.toString(bigScopeId));
    assertThat(detail.currentVersionId()).isEqualTo(Long.toString(bigVersionId));
    assertThat(detail.storageVersionId()).isEqualTo(Long.toString(bigVersionId));
    assertThat(Long.parseLong(detail.resource().projectId())).isGreaterThan(1L << 53);
    assertThat(detail.records()).singleElement().satisfies(record -> {
      assertThat(record.id()).isEqualTo(created.record().id());
      assertThat(Long.parseLong(record.id())).isPositive();
      assertThat(record.recordVersion()).isEqualTo(1);
      assertThat(record.storageVersionId()).isEqualTo(Long.toString(bigVersionId));
    });
  }

  @Test
  void shouldBuildResourceLinksFromMirroredProjectPathAndAvoidFabricatingMissingPath() {
    FormDetailResponse mergeRequest =
        formRecordService.detail(
            FormTemplateSpecCatalog.CODE_REVIEW,
            GITLAB_URL,
            PROJECT_ID,
            "merge_request",
            Long.toString(MR_IID),
            actorA);
    assertThat(mergeRequest.resource().webUrl())
        .isEqualTo(GITLAB_URL + "/team/example/-/merge_requests/" + MR_IID);

    FormDetailResponse issue =
        formRecordService.detail(
            FormTemplateSpecCatalog.ISSUE_RESPONSE,
            GITLAB_URL,
            PROJECT_ID,
            "issue",
            Long.toString(ISSUE_IID),
            actorA);
    assertThat(issue.resource().webUrl())
        .isEqualTo(GITLAB_URL + "/team/example/-/issues/" + ISSUE_IID);

    jdbcTemplate.update(
        "insert into ods_gitlab_projects(id, path, namespace_id, mirror_deleted)"
            + " values (10, 'example-two', 1, false)"
            + " on conflict (id) do update set path = excluded.path,"
            + " namespace_id = excluded.namespace_id, mirror_deleted = false");
    jdbcTemplate.update(
        "insert into ods_gitlab_merge_requests(id, target_project_id, iid, mirror_deleted)"
            + " values (9002, 10, ?, false)"
            + " on conflict (id) do update set target_project_id = excluded.target_project_id,"
            + " iid = excluded.iid, mirror_deleted = false",
        MR_IID);
    long otherProjectScope =
        createScope("w2-code-review-project-10", FormTemplateSpecCatalog.CODE_REVIEW, "merge_request");
    createVersionAndTables(otherProjectScope, FormTemplateSpecCatalog.CODE_REVIEW, 10L);
    resourceLinkService.clearCache();
    FormDetailResponse sameIidOtherProject =
        formRecordService.detail(
            FormTemplateSpecCatalog.CODE_REVIEW,
            GITLAB_URL,
            10L,
            "merge_request",
            Long.toString(MR_IID),
            actorA);
    assertThat(sameIidOtherProject.resource().webUrl())
        .isEqualTo(GITLAB_URL + "/team/example-two/-/merge_requests/" + MR_IID);

    jdbcTemplate.update("delete from ods_gitlab_projects where id = ?", PROJECT_ID);
    resourceLinkService.clearCache();
    FormDetailResponse missingPath =
        formRecordService.detail(
            FormTemplateSpecCatalog.CODE_REVIEW,
            GITLAB_URL,
            PROJECT_ID,
            "merge_request",
            Long.toString(MR_IID),
            actorA);
    assertThat(missingPath.resource().webUrl()).isNull();
  }

  private Map<String, Object> storedRecord(String id) {
    return jdbcTemplate.queryForMap(
        "select record_version, deleted, specification_count from "
            + codeReviewRecordTable
            + " where id = ?",
        Long.parseLong(id));
  }

  private long latestChangeVersion() {
    return jdbcTemplate.queryForObject(
        "select latest_change_version from form_change_heads"
            + " where template_key = 'code_review' and resource_id = ?",
        Long.class,
        MR_IID);
  }

  /** 等待写线程真正阻塞在范围共享锁上，避免依赖固定延时。 */
  private void awaitScopeLockWait(Future<?> writer, long blockerPid) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Integer waiting =
          jdbcTemplate.queryForObject(
              """
              select count(*) from pg_stat_activity
               where datname = current_database() and pid <> ?
                 and ? = any(pg_blocking_pids(pid))
              """,
              Integer.class,
              blockerPid,
              blockerPid);
      if (waiting != null && waiting > 0) {
        return;
      }
      if (writer.isDone()) {
        try {
          writer.get();
          throw new AssertionError("写线程在取得范围锁前已成功结束");
        } catch (java.util.concurrent.ExecutionException error) {
          throw new AssertionError("写线程在取得范围锁前异常结束", error.getCause());
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new AssertionError("等待写线程状态被中断", error);
        }
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError("等待范围锁被中断", error);
      }
    }
    throw new AssertionError(
        "写线程未在超时内进入范围锁等待："
            + jdbcTemplate.queryForList(
                "select pid, state, wait_event_type, wait_event, left(query, 200) as query"
                    + " from pg_stat_activity where datname = current_database()"));
  }

  @Test
  void shouldRejectCreateWithoutPersistentFormAuthorityAndNeverSelfRegister() {
    jdbcTemplate.update(
        "insert into platform_ldap_users(user_id, real_name, dept_name)"
            + " values ('alice', 'Alice', '质量部') on conflict (user_id) do nothing");
    assertThatThrownBy(() -> formRecordService.create(
            codeReviewRequest("alice", UUID.randomUUID().toString(), false),
            actorA, "127.0.0.1", "JUnit"))
        .as("未启用 MR 的 FORM 新建必须拒绝，不能由填写动作自行登记权威")
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("持久表单权威登记");

    assertThatThrownBy(() -> formRecordService.create(
            new FormCreateRequest(
                GITLAB_URL, PROJECT_ID, "issue", Long.toString(ISSUE_IID),
                FormTemplateSpecCatalog.ISSUE_RESPONSE,
                Long.toString(issueResponseScopeId),
                Long.toString(issueResponseVersionId),
                UUID.randomUUID().toString(), false,
                toFields(issueResponseFields("DEFECT", "负责人已确认", "2026-10-20", "CC2026R1"))),
            actorA, "127.0.0.1", "JUnit"))
        .isInstanceOf(FormConflictException.class)
        .hasMessageContaining("持久表单权威登记");

    assertThat(count("form_resource_authorities")).isZero();
  }

  private FormCreateResponse createCodeReview(String reviewer, String key) {
    registerFormAuthority("merge_request", MR_IID, FormTemplateSpecCatalog.CODE_REVIEW,
        codeReviewScopeId);
    return formRecordService.create(
        codeReviewRequest(reviewer, key), actorA, "127.0.0.1", "JUnit");
  }

  /** 写入只核验持久权威；测试夹具显式登记，不用填写动作自行切源。 */
  private void registerFormAuthority(
      String resourceType, long resourceId, String templateKey, long scopeId) {
    registerFormAuthority(resourceType, resourceId, templateKey, scopeId, PROJECT_ID);
  }

  private void registerFormAuthority(
      String resourceType, long resourceId, String templateKey, long scopeId, long projectId) {
    jdbcTemplate.update(
        """
        insert into form_resource_authorities(
            source_config_id, source_instance, canonical_gitlab_base_url, project_id,
            resource_type, resource_id, template_key, scope_id, authority, authority_revision,
            effective_at, created_at, updated_at)
        select s.source_config_id, s.source_instance, s.canonical_gitlab_base_url, ?, ?, ?, ?,
               s.id, 'FORM', 1, now(), now(), now()
          from form_template_scopes s where s.id = ?
        on conflict (source_config_id, project_id, resource_type, resource_id, template_key)
        do nothing
        """,
        projectId, resourceType, resourceId, templateKey, scopeId);
  }

  private FormCreateRequest codeReviewRequest(String reviewer, String key) {
    return codeReviewRequest(reviewer, key, false);
  }

  private FormCreateRequest codeReviewRequest(
      String reviewer, String key, boolean confirmSameReviewer) {
    return new FormCreateRequest(
        GITLAB_URL,
        PROJECT_ID,
        "merge_request",
        Long.toString(MR_IID),
        FormTemplateSpecCatalog.CODE_REVIEW,
        Long.toString(codeReviewScopeId),
        Long.toString(codeReviewVersionId),
        key,
        confirmSameReviewer,
        toFields(codeReviewFields(reviewer)));
  }

  private FormCreateResponse createIssueResponse(long issueIid, ObjectNode fields, String key) {
    registerFormAuthority("issue", issueIid, FormTemplateSpecCatalog.ISSUE_RESPONSE,
        issueResponseScopeId);
    return formRecordService.create(
        new FormCreateRequest(
            GITLAB_URL,
            PROJECT_ID,
            "issue",
            Long.toString(issueIid),
            FormTemplateSpecCatalog.ISSUE_RESPONSE,
            Long.toString(issueResponseScopeId),
            Long.toString(issueResponseVersionId),
            key,
            false,
            toFields(fields)),
        actorA,
        "127.0.0.1",
        "JUnit");
  }

  @Test
  void periodicDelayRefreshKeepsConsumedFormPlanInsteadOfEmptyCommentInput() {
    long project = 325L;
    long root = 9301L;
    long iid = 31L;
    jdbcTemplate.update("insert into ods_gitlab_projects(id, name, path, namespace_id, mirror_deleted) values (?, 'CC_PRODUCT', 'cc', 1, false) on conflict (id) do nothing", project);
    jdbcTemplate.update("insert into ods_gitlab_issues(id, project_id, iid, mirror_deleted) values (?, ?, ?, false)", root, project, iid);
    long scope = createScope("periodic-response", FormTemplateSpecCatalog.ISSUE_RESPONSE, "issue");
    long version = createVersionAndTables(scope, FormTemplateSpecCatalog.ISSUE_RESPONSE, project);
    registerFormAuthority("issue", iid, FormTemplateSpecCatalog.ISSUE_RESPONSE, scope, project);
    formRecordService.create(new FormCreateRequest(GITLAB_URL, project, "issue", Long.toString(iid),
        FormTemplateSpecCatalog.ISSUE_RESPONSE, Long.toString(scope), Long.toString(version),
        UUID.randomUUID().toString(), false,
        toFields(issueResponseFields("DEFECT", null, "2039-12-20", "CC2026R1"))), actorA, "127.0.0.1", "JUnit");
    Long factId = jdbcTemplate.queryForObject("select id from issue_fact where issue_id = ?", Long.class, ISSUE_ROOT);
    var fact = factMapper.selectById(factId);
    fact.setId(null);
    fact.setProjectId(project);
    fact.setIssueId(root);
    fact.setIssueIid(iid);
    fact.setCreatedAtSource(LocalDateTime.of(2026, 6, 1, 0, 0));
    fact.setIssueState("opened");
    fact.setRawPayload("");
    factPersistence.upsertIssueFacts(List.of(fact));
    var config = new com.data.collection.platform.entity.GitlabSyncConfig();
    config.setSourceInstance("default");
    factBuildService.refreshCustomerIssueDelayFactsForConfig(config);
    Map<String, Object> stored = jdbcTemplate.queryForMap("select is_response_delayed, is_resolve_delayed, planned_resolution_at from issue_fact where issue_id = ?", root);
    assertThat(stored).containsEntry("is_response_delayed", false).containsEntry("is_resolve_delayed", false);
    assertThat(((Timestamp) stored.get("planned_resolution_at")).toLocalDateTime().toLocalDate()).isEqualTo(java.time.LocalDate.of(2039, 12, 20));
    jdbcTemplate.update("delete from issue_fact where issue_id = ?", root);
  }

  @Test
  void committedFormWriteDurablyQueuesLegacyFactRefresh() {
    createIssueResponse(ISSUE_IID, issueResponseFields("DEFECT", "负责人已确认", "2026-12-20", "CC2026R1"), UUID.randomUUID().toString());
    assertThat(jdbcTemplate.queryForObject("select count(*) from sync_runs where config_id = ? and run_type = 'FACT_REFRESH'", Long.class, sourceConfigId)).isEqualTo(1L);
  }

  @Test
  void formFactBatchAndRootReplacementUseTheSameLockOrder() throws Exception {
    createIssueResponse(ISSUE_IID, issueResponseFields("DEFECT", "负责人已确认", "2026-12-20", "CC2026R1"), UUID.randomUUID().toString());
    Long factId = jdbcTemplate.queryForObject("select id from issue_fact where issue_id = ?", Long.class, ISSUE_ROOT);
    var first = factMapper.selectById(factId);
    var replacement = factMapper.selectById(factId);
    var pool = Executors.newSingleThreadExecutor();
    var other = new java.util.concurrent.atomic.AtomicReference<Future<?>>();
    try {
      new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
        long blockerPid = jdbcTemplate.queryForObject("select pg_backend_pid()", Long.class);
        var prepared = formFactAdapter.prepare(List.of(first));
        Future<?> writer = pool.submit(() -> factPersistence.replaceRootFacts("GITLAB", "default", List.of(ISSUE_ROOT), List.of(replacement)));
        other.set(writer);
        awaitScopeLockWait(writer, blockerPid);
        factMapper.batchUpsert(List.of(first));
        formFactAdapter.complete(prepared);
      });
      other.get().get(15, TimeUnit.SECONDS);
      assertThat(jdbcTemplate.queryForObject("select count(*) from issue_fact where issue_id = ? and form_input_hash is not null", Long.class, ISSUE_ROOT)).isEqualTo(1L);
      assertThat(jdbcTemplate.queryForObject("select count(*) from form_change_heads where scope_id = ? and latest_change_version > consumed_change_version", Long.class, issueResponseScopeId)).isZero();
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void formWriteIsConsumedByLegacyOdsFactRebuild() {
    jdbcTemplate.update("insert into sync_run_table_states(config_id, source_instance, source_table, mirror_table, primary_key_columns, sync_enabled, dirty_flag, last_full_verified_at) values (?, 'default', 'resource_label_events', 'ods_gitlab_resource_label_events', 'id', true, false, current_timestamp)", sourceConfigId);
    jdbcTemplate.update("update ods_gitlab_issues set title = '表单事实链路', created_at = '2026-11-01', updated_at = '2026-11-01', state_id = 1 where id = ?", ISSUE_ROOT);
    createIssueResponse(ISSUE_IID, issueResponseFields("DEFECT", "负责人已确认", "2026-12-20", "CC2026R1"), UUID.randomUUID().toString());
    assertThat(factBuildService.rebuildIssueFactsByRootIds("default", List.of(ISSUE_ROOT)).affectedRows()).isEqualTo(1);
    Map<String, Object> stored = jdbcTemplate.queryForMap("select title, planned_resolution_at, form_input_hash from issue_fact where issue_id = ?", ISSUE_ROOT);
    assertThat(stored.get("title")).isEqualTo("表单事实链路");
    assertThat(((Timestamp) stored.get("planned_resolution_at")).toLocalDateTime().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 12, 20));
    assertThat(stored.get("form_input_hash")).isNotNull();
    assertThat(jdbcTemplate.queryForObject("select count(*) from form_change_heads where scope_id = ? and latest_change_version > consumed_change_version", Long.class, issueResponseScopeId)).isZero();
  }

  @Test
  void singleRowFactBatchRegistersAndDeliversBothLinksOnceWithSourceToken() throws Exception {
    issueSettings.save(issueResponseScopeId, PROJECT_ID,
        new FormIssueSettingsService.SaveRequest(true, java.time.Instant.parse("2026-01-01T00:00:00Z"),
            true, "http://platform.test", 0));
    Long factId = jdbcTemplate.queryForObject("select id from issue_fact where issue_id = ?", Long.class, ISSUE_ROOT);
    com.data.collection.platform.entity.IssueFact fact = factMapper.selectById(factId);
    fact.setCreatedAtSource(LocalDateTime.of(2026, 11, 1, 0, 0));
    factPersistence.upsertIssueFacts(List.of(fact));
    Long authorityId = jdbcTemplate.queryForObject("select id from form_resource_authorities where scope_id = ? and resource_id = ? and entry_registered_at is not null",
        Long.class, issueResponseScopeId, ISSUE_IID);
    assertThat(jdbcTemplate.queryForObject("select count(*) from form_resource_authorities where resource_id = ? and authority = 'FORM'", Long.class, ISSUE_IID)).isEqualTo(2L);
    var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var tokens = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v4/projects/9/issues/21/notes", exchange -> {
      requests.add(java.net.URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8));
      tokens.add(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
      byte[] response = "{\"id\":123}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(201, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      var configs = org.mockito.Mockito.mock(com.data.collection.platform.service.GitlabConfigService.class);
      var config = new com.data.collection.platform.entity.GitlabSyncConfig();
      config.setWebBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
      config.setApiToken("test-source-token");
      org.mockito.Mockito.when(configs.getConfigById(sourceConfigId)).thenReturn(config);
      var delivery = new FormIssueLinkService(jdbcTemplate, configs,
          new com.data.collection.platform.config.FormIssueLinkProperties(), issueSettings, commentContentService,
          objectMapper, Runnable::run, transactionManager);
      assertThat(delivery.sendOne(authorityId)).isTrue();
      factPersistence.upsertIssueFacts(List.of(fact));
      assertThat(delivery.sendOne(authorityId)).isFalse();
      assertThat(requests).singleElement().asString().contains("http://platform.test/#/external/forms", "templateKey=issue_response", "templateKey=issue_reply");
      assertThat(tokens).containsExactly("test-source-token");
      assertThat(jdbcTemplate.queryForObject("select entry_comment_id from form_resource_authorities where id = ?", Long.class, authorityId)).isEqualTo(123L);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void commentRefreshUsesSavedValuesAndKeepsFailedRevisionPending() throws Exception {
    issueSettings.save(issueResponseScopeId, PROJECT_ID,
        new FormIssueSettingsService.SaveRequest(true, java.time.Instant.parse("2026-01-01T00:00:00Z"),
            true, "http://platform.test", 0));
    Long factId = jdbcTemplate.queryForObject("select id from issue_fact where issue_id = ?", Long.class, ISSUE_ROOT);
    com.data.collection.platform.entity.IssueFact fact = factMapper.selectById(factId);
    fact.setCreatedAtSource(LocalDateTime.of(2026, 11, 1, 0, 0));
    factPersistence.upsertIssueFacts(List.of(fact));
    Long authorityId = jdbcTemplate.queryForObject("select id from form_resource_authorities where scope_id = ? and resource_id = ? and entry_registered_at is not null",
        Long.class, issueResponseScopeId, ISSUE_IID);
    assertThat(jdbcTemplate.queryForObject("select count(*) from form_resource_authorities where resource_id = ? and authority = 'FORM'", Long.class, ISSUE_IID)).isEqualTo(2L);
    var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var methods = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var block = new java.util.concurrent.atomic.AtomicBoolean(false);
    var fail = new java.util.concurrent.atomic.AtomicBoolean(false);
    var tokens = new java.util.concurrent.CopyOnWriteArrayList<String>();
    var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/v4/projects/9/issues/21/notes", exchange -> {
      methods.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
      requests.add(java.net.URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8));
      tokens.add(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
      byte[] response = "{\"id\":123}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      if (block.compareAndSet(true, false)) {
        entered.countDown();
        try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放HTTP"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
      }
      exchange.sendResponseHeaders(fail.get() ? 503 : 200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      var configs = org.mockito.Mockito.mock(com.data.collection.platform.service.GitlabConfigService.class);
      var config = new com.data.collection.platform.entity.GitlabSyncConfig();
      config.setWebBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
      config.setApiToken("test-source-token");
      org.mockito.Mockito.when(configs.getConfigById(sourceConfigId)).thenReturn(config);
      var delivery = new FormIssueLinkService(jdbcTemplate, configs,
          new com.data.collection.platform.config.FormIssueLinkProperties(), issueSettings, commentContentService,
          objectMapper, Runnable::run, transactionManager);
      assertThat(delivery.sendOne(authorityId)).isTrue();
      ObjectNode fields = issueResponseFields("DEFECT", "负责人已确认", "2026-12-20", "CC2026R4SP1&dev");
      var created = createIssueResponse(ISSUE_IID, fields, UUID.randomUUID().toString()).record();
      assertThat(jdbcTemplate.queryForObject("select entry_comment_revision > entry_comment_synced_revision from form_resource_authorities where id = ?", Boolean.class, authorityId)).isTrue();
      fail.set(true);
      assertThatThrownBy(() -> delivery.sendOne(authorityId)).isInstanceOf(IllegalStateException.class);
      assertThat(jdbcTemplate.queryForObject("select entry_comment_revision > entry_comment_synced_revision from form_resource_authorities where id = ?", Boolean.class, authorityId)).isTrue();
      fail.set(false);
      // 丢弃执行器内存状态，停用时不发送；恢复后只凭持久修订补偿原评论。
      var recoveredDelivery = new FormIssueLinkService(jdbcTemplate, configs,
          new com.data.collection.platform.config.FormIssueLinkProperties(), issueSettings, commentContentService,
          objectMapper, Runnable::run, transactionManager);
      jdbcTemplate.update("update gitlab_sync_configs set form_issue_settings=jsonb_set(form_issue_settings,array[?::text,'linkEnabled'],'false'::jsonb) where id=?",
          Long.toString(PROJECT_ID), sourceConfigId);
      assertThat(recoveredDelivery.dispatchPending(20)).isZero();
      jdbcTemplate.update("update gitlab_sync_configs set form_issue_settings=jsonb_set(form_issue_settings,array[?::text,'linkEnabled'],'true'::jsonb) where id=?",
          Long.toString(PROJECT_ID), sourceConfigId);
      assertThat(recoveredDelivery.dispatchPending(20)).isEqualTo(1);
      assertThat(requests.getLast()).contains("响应 1 条", "CC2026R4SP1&amp;dev", "<details>", "负责人已确认");
      fields.put("problemCause", "更新后的原因");
      formRecordService.update(created.id(), new FormUpdateRequest(GITLAB_URL, PROJECT_ID, "issue", Long.toString(ISSUE_IID),
          FormTemplateSpecCatalog.ISSUE_RESPONSE, Long.toString(issueResponseScopeId), Long.toString(issueResponseVersionId),
          Long.toString(issueResponseVersionId), 1, toFields(fields)), actorA, "127.0.0.1", "JUnit");
      block.set(true);
      try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
        Future<Boolean> sending = workers.submit(() -> delivery.sendOne(authorityId));
        try {
          assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
          fields.put("problemCause", "发送期间再次修改");
          Future<FormRecordView> saved = workers.submit(() -> formRecordService.update(created.id(),
              new FormUpdateRequest(GITLAB_URL, PROJECT_ID, "issue", Long.toString(ISSUE_IID),
                  FormTemplateSpecCatalog.ISSUE_RESPONSE, Long.toString(issueResponseScopeId), Long.toString(issueResponseVersionId),
                  Long.toString(issueResponseVersionId), 2, toFields(fields)), actorA, "127.0.0.1", "JUnit"));
          assertThat(saved.get(5, TimeUnit.SECONDS).recordVersion()).isEqualTo(3);
        } finally { release.countDown(); }
        assertThat(sending.get(5, TimeUnit.SECONDS)).isTrue();
      }
      assertThat(jdbcTemplate.queryForObject("select entry_comment_revision > entry_comment_synced_revision from form_resource_authorities where id = ?", Boolean.class, authorityId)).isTrue();
      assertThat(delivery.sendOne(authorityId)).isTrue();
      assertThat(requests.getLast()).contains("发送期间再次修改");
      formRecordService.voidRecord(created.id(), new FormVoidRequest(GITLAB_URL, PROJECT_ID, "issue", Long.toString(ISSUE_IID),
          FormTemplateSpecCatalog.ISSUE_RESPONSE, Long.toString(issueResponseScopeId), Long.toString(issueResponseVersionId),
          Long.toString(issueResponseVersionId), 3, true, "作废"), actorA, "127.0.0.1", "JUnit");
      assertThat(delivery.sendOne(authorityId)).isTrue();
      assertThat(requests.getLast()).contains("响应 0 条").doesNotContain("<details>");
      assertThat(delivery.sendOne(authorityId)).isFalse();
      assertThat(methods.getFirst()).isEqualTo("POST /api/v4/projects/9/issues/21/notes");
      assertThat(methods.subList(1, methods.size())).allMatch(m -> m.equals("PUT /api/v4/projects/9/issues/21/notes/123"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void commentIncludesValidRecordsFromEachFrozenVersionWithoutMovingOldRecords() throws Exception {
    long originalVersion = issueResponseVersionId;
    ObjectNode originalFields = issueResponseFields("DEFECT", "负责人已确认", "2026-12-20", "dev");
    originalFields.put("problemCause", "旧版原因内容");
    var original = createIssueResponse(ISSUE_IID, originalFields, UUID.randomUUID().toString()).record();
    ObjectNode originalSpec = (ObjectNode) objectMapper.readTree(specCatalog.rawJson(FormTemplateSpecCatalog.ISSUE_RESPONSE));
    for (JsonNode field : originalSpec.path("fields")) {
      if ("problemCause".equals(field.path("apiKey").asText())) ((ObjectNode) field).put("label", "历史问题原因");
    }
    jdbcTemplate.update("update form_template_versions set field_spec=?::jsonb, version_status='RETIRED', record_source_role='AUTHORITATIVE' where id=?",
        objectMapper.writeValueAsString(originalSpec), originalVersion);
    issueResponseVersionId = createVersionAndTables(issueResponseScopeId, FormTemplateSpecCatalog.ISSUE_RESPONSE);
    jdbcTemplate.update("update form_template_versions set version_label='CC2026R2',record_source_role='AUTHORITATIVE' where id=?", issueResponseVersionId);
    ObjectNode currentFields = issueResponseFields("DEFECT", "负责人已确认", "2027-01-20", "CC2027R1");
    currentFields.put("problemCause", "新版原因内容");
    var current = createIssueResponse(ISSUE_IID, currentFields, UUID.randomUUID().toString()).record();
    var authority = jdbcTemplate.queryForMap("select * from form_resource_authorities where scope_id=? and resource_id=?",
        issueResponseScopeId, ISSUE_IID);
    String comment = commentContentService.render(authority, "http://platform.test/response", "http://platform.test/reply");
    assertThat(comment).contains("响应 2 条", "历史问题原因", "旧版原因内容", "新版原因内容",
        "#" + original.id(), "#" + current.id());
    assertThat(count(recordTable(issueResponseScopeId, originalVersion))).isEqualTo(1);
    assertThat(count(recordTable(issueResponseScopeId, issueResponseVersionId))).isEqualTo(1);
  }

  @Test
  void commentRegistrationSeesSettingsEnabledInCurrentTransaction() {
    issueSettings.save(issueResponseScopeId, PROJECT_ID,
        new FormIssueSettingsService.SaveRequest(true, java.time.Instant.parse("2026-01-01T00:00:00Z"),
            false, "http://platform.test", 0));
    Long factId = jdbcTemplate.queryForObject("select id from issue_fact where issue_id = ?", Long.class, ISSUE_ROOT);
    com.data.collection.platform.entity.IssueFact fact = factMapper.selectById(factId);
    fact.setCreatedAtSource(LocalDateTime.of(2026, 11, 1, 0, 0));
    factPersistence.upsertIssueFacts(List.of(fact));
    var delivery = new FormIssueLinkService(jdbcTemplate,
        org.mockito.Mockito.mock(com.data.collection.platform.service.GitlabConfigService.class),
        new com.data.collection.platform.config.FormIssueLinkProperties(), issueSettings, commentContentService,
        objectMapper, Runnable::run, transactionManager);
    new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      jdbcTemplate.update("update gitlab_sync_configs set form_issue_settings = jsonb_set(form_issue_settings, array[?::text,'linkEnabled'], 'true'::jsonb) where id = ?",
          Long.toString(PROJECT_ID), sourceConfigId);
      assertThat(delivery.registerPublishedIssues(GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
          new Long[] {ISSUE_ROOT})).isEqualTo(1);
      status.setRollbackOnly();
    });
    assertThat(jdbcTemplate.queryForObject("select count(*) from form_resource_authorities where entry_registered_at is not null", Long.class)).isZero();
  }

  @Test
  void commentLockContentionTimesOutWithoutBlockingOtherDatabaseWork() throws Exception {
    issueSettings.save(issueResponseScopeId, PROJECT_ID,
        new FormIssueSettingsService.SaveRequest(true, java.time.Instant.parse("2026-01-01T00:00:00Z"),
            true, "http://platform.test", 0));
    var properties = new com.data.collection.platform.config.FormIssueLinkProperties();
    properties.setDatabaseTimeoutSeconds(1);
    var delivery = new FormIssueLinkService(jdbcTemplate,
        org.mockito.Mockito.mock(com.data.collection.platform.service.GitlabConfigService.class),
        properties, issueSettings, commentContentService, objectMapper, Runnable::run, transactionManager);
    try (var connection = java.util.Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection();
        ExecutorService worker = Executors.newSingleThreadExecutor()) {
      connection.setAutoCommit(false);
      try (var statement = connection.prepareStatement("select pg_advisory_xact_lock(hashtextextended(?,0))")) {
        statement.setString(1, "form-issue-comment:999999");
        statement.execute();
      }
      try {
        Future<?> pending = worker.submit(() -> delivery.sendOne(999999L));
        assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
            .isInstanceOf(java.util.concurrent.ExecutionException.class)
            .hasCauseInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbcTemplate.queryForObject("select 1", Integer.class)).isEqualTo(1);
      } finally {
        connection.rollback();
      }
      assertThat(delivery.sendOne(999999L)).isFalse();
    }
  }

  @Test
  void singleRowFactBatchPublishesFormPlanAndConsumesHeadTogether() {
    createIssueResponse(ISSUE_IID, issueResponseFields("DEFECT", "负责人已确认", "2026-12-20", "CC2026R1"), UUID.randomUUID().toString());
    Long factId = jdbcTemplate.queryForObject("select id from issue_fact where issue_id = ?", Long.class, ISSUE_ROOT);
    com.data.collection.platform.entity.IssueFact fact = factMapper.selectById(factId);
    fact.setCreatedAtSource(LocalDateTime.of(2026, 11, 1, 0, 0));
    fact.setIssueState("opened");
    factPersistence.upsertIssueFacts(List.of(fact));
    Map<String, Object> stored = jdbcTemplate.queryForMap("select planned_resolution_at, form_input_hash from issue_fact where id = ?", factId);
    assertThat(((Timestamp) stored.get("planned_resolution_at")).toLocalDateTime().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 12, 20));
    assertThat(stored.get("form_input_hash")).isNotNull();
    assertThat(jdbcTemplate.queryForObject("select count(*) from form_change_heads where scope_id = ? and latest_change_version > consumed_change_version", Long.class, issueResponseScopeId)).isZero();
    assertThat(jdbcTemplate.queryForObject("select to_regclass('read_publications')::text", String.class)).isNull();
  }

  @Test
  void singleRowFactBatchRollbackPreservesPendingFormInputAndOriginalFact() {
    createIssueResponse(ISSUE_IID, issueResponseFields("DEFECT", "负责人已确认", "2026-12-20", "CC2026R1"), UUID.randomUUID().toString());
    Long factId = jdbcTemplate.queryForObject("select id from issue_fact where issue_id = ?", Long.class, ISSUE_ROOT);
    Map<String, Object> before = jdbcTemplate.queryForMap("select * from issue_fact where id = ?", factId);
    List<Map<String, Object>> headsBefore = jdbcTemplate.queryForList("select * from form_change_heads order by id");
    com.data.collection.platform.entity.IssueFact fact = factMapper.selectById(factId);
    org.springframework.transaction.support.TransactionTemplate tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    assertThatThrownBy(() -> tx.execute(status -> {
      factPersistence.upsertIssueFacts(List.of(fact));
      throw new IllegalStateException("模拟事实批次后续失败");
    })).isInstanceOf(IllegalStateException.class).hasMessage("模拟事实批次后续失败");
    assertThat(jdbcTemplate.queryForMap("select * from issue_fact where id = ?", factId)).isEqualTo(before);
    assertThat(jdbcTemplate.queryForList("select * from form_change_heads order by id")).isEqualTo(headsBefore);
  }

  private FormCreateResponse createIssueReply(ObjectNode fields, String key) {
    registerFormAuthority("issue", ISSUE_IID, FormTemplateSpecCatalog.ISSUE_REPLY,
        issueReplyScopeId);
    return formRecordService.create(
        new FormCreateRequest(
            GITLAB_URL,
            PROJECT_ID,
            "issue",
            Long.toString(ISSUE_IID),
            FormTemplateSpecCatalog.ISSUE_REPLY,
            Long.toString(issueReplyScopeId),
            Long.toString(issueReplyVersionId),
            key,
            false,
            toFields(fields)),
        actorA,
        "127.0.0.1",
        "JUnit");
  }

  private FormUpdateRequest updateRequest(
      long scopeId, long versionId, int recordVersion, ObjectNode fields) {
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
        toFields(fields));
  }

  private FormVoidRequest voidRequest(long scopeId, long versionId, int recordVersion) {
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

  private ObjectNode codeReviewFields(String reviewer) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("reviewerUserId", reviewer);
    node.put("reviewDurationMinutes", 30);
    node.put("specificationCount", 1);
    node.put("logicCount", 0);
    node.put("performanceCount", 0);
    node.put("designCount", 0);
    node.put("otherCount", 0);
    node.putNull("supplementNote");
    return node;
  }

  private ObjectNode issueResponseFields(
      String problemType, String ownerConfirmation, String date, String branch) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("problemType", problemType);
    node.put("problemCause", "原因");
    node.put("modificationPlan", "方案");
    if (ownerConfirmation == null) {
      node.putNull("ownerConfirmation");
    } else {
      node.put("ownerConfirmation", ownerConfirmation);
    }
    node.put("plannedResolutionDate", date);
    node.put("plannedMergeVersionBranch", branch);
    node.putNull("supplementNote");
    return node;
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

  private long createScope(String scopeKey, String templateKey, String resourceType) {
    return jdbcTemplate.queryForObject(
        """
        insert into form_template_scopes(template_key, scope_key, resource_type, status)
        values (?, ?, ?, 'DRAFT')
        returning id
        """,
        Long.class,
        templateKey,
        scopeKey,
        resourceType);
  }

  private long createVersionAndTables(long scopeId, String templateKey) {
    return createVersionAndTables(scopeId, templateKey, PROJECT_ID);
  }

  private long createVersionAndTables(long scopeId, String templateKey, long projectId) {
    long versionId =
        jdbcTemplate.queryForObject(
            "select nextval('form_template_versions_id_seq')", Long.class);
    String instanceTable = "form_s" + scopeId + "_v" + versionId + "_instances";
    String recordTable = "form_s" + scopeId + "_v" + versionId + "_records";
    FormTemplateSpec spec = specCatalog.require(templateKey);
    FormVersionSchema schema = schemaService.generate(scopeId, versionId, spec, false);
    for (String statement : schema.statements()) {
      jdbcTemplate.execute(statement);
    }
    jdbcTemplate.update(
        """
        insert into form_template_versions(
            id, scope_id, version_label, storage_kind, instance_table, record_table,
            field_spec, version_status, published_at)
        values (?, ?, 'CC2026R1', 'RECORDS', ?, ?, ?::jsonb, 'PUBLISHED', ?)
        """,
        versionId,
        scopeId,
        instanceTable,
        recordTable,
        specCatalog.rawJson(templateKey),
        Timestamp.valueOf(LocalDateTime.now()));
    List<Long> projects = List.of(projectId);
    String canonical = FormSourceUrlSupport.canonicalize(GITLAB_URL);
    ObjectNode binding =
        FormScopeBinding.create(
            objectMapper,
            templateKey,
            sourceConfigId,
            GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
            canonical,
            projects,
            projectId,
            templateKey.equals(FormTemplateSpecCatalog.CODE_REVIEW) ? "merge_request" : "issue");
    String bindingHash = FormScopeBinding.hash(objectMapper, binding);
    jdbcTemplate.update(
        """
        update form_template_scopes
           set source_config_id = ?, source_instance = ?, canonical_gitlab_base_url = ?,
               project_ids = ?::jsonb, version_project_id = ?, current_version_id = ?,
               status = 'ACTIVE', binding_hash = ?, binding_revision = 1, updated_at = ?
         where id = ?
        """,
        sourceConfigId,
        GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
        canonical,
        "[" + projectId + "]",
        projectId,
        versionId,
        bindingHash,
        Timestamp.valueOf(LocalDateTime.now()),
        scopeId);
    jdbcTemplate.update(
        "update form_template_scopes set source_set_hash = form_source_set_hash(id) where id = ?",
        scopeId);
    return versionId;
  }

  /** 构造字段改名/新增/删除后的第二版并复制存量行，模拟受控结构迁移成功后的权威状态。 */
  private VersionMigration migrateCodeReviewToSecondVersion() {
    return createSecondVersion(true);
  }

  /** 发布第二版但不搬迁存量行：模拟 N3 指针切换后旧版记录仍原位可读可写。 */
  private VersionMigration publishSecondVersionKeepingRecords() {
    return createSecondVersion(false);
  }

  private VersionMigration createSecondVersion(boolean migrateRecords) {
    ObjectNode specNode;
    try {
      specNode = (ObjectNode) objectMapper.readTree(specCatalog.rawJson(FormTemplateSpecCatalog.CODE_REVIEW));
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    ArrayNode fields = (ArrayNode) specNode.get("fields");
    ArrayNode migratedFields = objectMapper.createArrayNode();
    for (JsonNode field : fields) {
      String name = field.path("name").asText();
      if ("other_count".equals(name)) {
        continue;
      }
      ObjectNode copy = field.deepCopy();
      if ("reviewer_user_id".equals(name)) {
        copy.put("label", "走查人（新）");
      }
      migratedFields.add(copy);
    }
    ObjectNode added = migratedFields.addObject();
    added.put("name", "reviewNote");
    added.put("apiKey", "reviewNote");
    added.put("label", "走查备注");
    added.put("type", "string");
    added.put("control", "input");
    added.put("required", false);
    added.put("maxLength", 200);
    ObjectNode storage = added.putObject("storage");
    storage.put("column", "review_note");
    storage.put("columnType", "varchar(200)");
    specNode.set("fields", migratedFields);

    FormTemplateSpec migratedSpec;
    String migratedSpecJson;
    try {
      migratedSpecJson = objectMapper.writeValueAsString(specNode);
      migratedSpec = objectMapper.readValue(migratedSpecJson, FormTemplateSpec.class);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
    long newVersionId =
        jdbcTemplate.queryForObject("select nextval('form_template_versions_id_seq')", Long.class);
    FormVersionSchema schema =
        schemaService.generate(codeReviewScopeId, newVersionId, migratedSpec, true);
    for (String statement : schema.statements()) {
      jdbcTemplate.execute(statement);
    }
    if (migrateRecords) {
      jdbcTemplate.update(
          ("insert into %s(id, scope_id, source_config_id, source_instance, gitlab_base_url,"
                  + " project_id, resource_type, resource_id, template_key, created_at, updated_at)"
                  + " select id, scope_id, source_config_id, source_instance, gitlab_base_url,"
                  + " project_id, resource_type, resource_id, template_key, created_at, updated_at"
                  + " from %s")
              .formatted(
                  schema.instanceTable(), instanceTable(codeReviewScopeId, codeReviewVersionId)));
      jdbcTemplate.update(
          ("insert into %s(id, instance_id, origin_version_id, template_version, created_by,"
                  + " request_key, create_payload_hash, record_version, deleted, legacy_incomplete,"
                  + " legacy_missing_fields, created_at, updated_at, reviewer_user_id, reviewer_name,"
                  + " review_duration_minutes, specification_count, logic_count, performance_count,"
                  + " design_count, supplement_note)"
                  + " select id, instance_id, origin_version_id, template_version, created_by,"
                  + " request_key, create_payload_hash, record_version, deleted, legacy_incomplete,"
                  + " legacy_missing_fields, created_at, updated_at, reviewer_user_id, reviewer_name,"
                  + " review_duration_minutes, specification_count, logic_count, performance_count,"
                  + " design_count, supplement_note"
                  + " from %s order by id")
              .formatted(schema.recordTable(), codeReviewRecordTable));
    }
    jdbcTemplate.update(
        "update form_template_versions set version_status = 'RETIRED' where id = ?",
        codeReviewVersionId);
    jdbcTemplate.update(
        """
        insert into form_template_versions(
            id, scope_id, version_label, storage_kind, instance_table, record_table,
            field_spec, version_status, published_at)
        values (?, ?, 'CC2026R2', 'RECORDS', ?, ?, ?::jsonb, 'PUBLISHED', ?)
        """,
        newVersionId,
        codeReviewScopeId,
        schema.instanceTable(),
        schema.recordTable(),
        migratedSpecJson,
        Timestamp.valueOf(LocalDateTime.now()));
    jdbcTemplate.update(
        "update form_template_versions set record_source_role = ? where id = ?",
        migrateRecords ? "LEGACY_SNAPSHOT" : "AUTHORITATIVE",
        codeReviewVersionId);
    jdbcTemplate.update(
        "update form_template_versions set record_source_role = 'AUTHORITATIVE' where id = ?",
        newVersionId);
    if (migrateRecords) {
      jdbcTemplate.update(
          "update form_record_locations set storage_version_id = ? where scope_id = ?",
          newVersionId,
          codeReviewScopeId);
    }
    jdbcTemplate.update(
        "update form_template_scopes set current_version_id = ? where id = ?",
        newVersionId,
        codeReviewScopeId);
    jdbcTemplate.update(
        "update form_template_scopes"
            + " set source_set_hash = form_source_set_hash(id),"
            + " source_set_revision = source_set_revision + 1 where id = ?",
        codeReviewScopeId);
    return new VersionMigration(codeReviewRecordTable, schema.recordTable(), newVersionId);
  }

  private String instanceTable(long scopeId, long versionId) {
    return "form_s" + scopeId + "_v" + versionId + "_instances";
  }

  private String recordTable(long scopeId, long versionId) {
    return "form_s" + scopeId + "_v" + versionId + "_records";
  }

  private record VersionMigration(
      String oldRecordTable, String newRecordTable, long newVersionId) {}

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
        create table if not exists ods_gitlab_merge_requests(
            id bigint primary key,
            target_project_id bigint,
            iid bigint,
            mirror_deleted boolean default false)
        """);
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
  }

  private void insertIssueFact(long rootId, long iid, String severity) {
    jdbcTemplate.update(
        """
        insert into issue_fact(
            source_system, source_instance, project_id, issue_id, issue_iid, title,
            severity_level, deleted)
        values ('GITLAB', 'default', ?, ?, ?, '', ?, false)
        """,
        PROJECT_ID,
        rootId,
        iid,
        severity);
  }

  private void insertUser(String userId, String realName, String deptName) {
    jdbcTemplate.update(
        """
        insert into platform_ldap_users(user_id, real_name, dept_name)
        values (?, ?, ?)
        """,
        userId,
        realName,
        deptName);
  }

  private int count(String table) {
    Integer value =
        jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    return value == null ? 0 : value;
  }

  private void installFailureTrigger(String name, String table) {
    jdbcTemplate.execute(
        "create or replace function " + name + "() returns trigger language plpgsql as $$"
            + " begin raise exception '" + name + "'; end $$");
    jdbcTemplate.execute(
        "create trigger " + name + " before insert or update on " + table
            + " for each row execute function " + name + "()");
  }

  private void dropFailureTriggers() {
    for (String table : List.of("form_record_audit_logs", "form_change_heads")) {
      jdbcTemplate.execute("drop trigger if exists w2_fail_audit on " + table);
      jdbcTemplate.execute("drop trigger if exists w2_fail_head on " + table);
    }
    jdbcTemplate.execute("drop function if exists w2_fail_audit()");
    jdbcTemplate.execute("drop function if exists w2_fail_head()");
  }
}
