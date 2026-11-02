package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.domain.form.FormScopeBinding;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.domain.form.FormVersionSchema;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.service.form.FormVersionSchemaService;
import com.data.collection.platform.service.read.BypassPublicationRequest;
import com.data.collection.platform.service.read.FactReadVisibility;
import com.data.collection.platform.service.read.InitialVersionVerification;
import com.data.collection.platform.service.read.ReadPublicationService;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 表单接管 Issue 首次事实与周期 SLA 的真实 PG 回归。
 *
 * <p>来源 ODS、表单权威目录与事实发布链路全部走生产实现：启用点之后的议题按 {@code (createdAt, recordId)}
 * 读取表单响应/回复，启用点之前（含前 1 秒）仍按评论解析；周期重算复用同一输入，不因旧评论计划回退。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = {
      "platform.background-jobs.enabled=false",
      "platform.form-adoption.issue-enabled-at=2026-11-01T00:00:00",
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
class FormIssueAdoptionFactPipelineIntegrationTest {
  private static final long PROJECT_ID = 325L;
  private static final String PROJECT_NAME = "CC_PRODUCT";
  private static final String SOURCE_INSTANCE = "default";
  private static final String SEAL = "{\"seal\":\"form-adoption-pipeline-test\"}";
  private static final String CANONICAL_URL = "http://gitlab.test";
  private static final long SOURCE_CONFIG_ID = 601L;
  private static final LocalDateTime SWITCH_AT = LocalDateTime.of(2026, 11, 1, 0, 0);
  private static final String LIVE_REVISION =
      " and " + FactReadVisibility.liveRevisionPredicate("");
  private static final String RESPONSE_TEMPLATE =
      "# 问题调研情况说明\n## 计划解决时间：%s\n## 计划合并的版本分支：CC2026R3";
  private static final java.util.Set<String> TEXT_ARRAY_COLUMNS =
      java.util.Set.of("cause_keys", "affected_kinds");

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
  @Autowired private FormTemplateSpecCatalog catalog;
  @Autowired private FactBuildService factBuildService;
  @Autowired private FactTargetPublicationService factTargetPublicationService;
  @Autowired private ReadPublicationService readPublicationService;
  @Autowired private FormIssueInputResolver formIssueInputResolver;
  @Autowired private FormIssueAuthorityRegistrar formIssueAuthorityRegistrar;
  @Autowired private com.data.collection.platform.service.form.FormHeadCaptureService
      formHeadCaptureService;
  @Autowired private com.data.collection.platform.config.FormAdoptionProperties adoptionProperties;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private SyncFactPublicationStateService qualification;

  private long responseScopeId;
  private long replyScopeId;
  private String responseSpec;

  @BeforeEach
  void setUp() {
    adoptionProperties.setIssueEnabledAt(SWITCH_AT);
    org.mockito.Mockito.doReturn(true)
        .when(qualification)
        .canBuildReadCandidate(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(FactType.class));
    GitlabIssueMirrorFixture.ensureSchema(jdbcTemplate);
    cleanSourceAndFacts();
    cleanFormDirectory();
    jdbcTemplate.execute("truncate read_publications, read_publication_domains cascade");
    LabelEventHistoryTestSupport.markComplete(jdbcTemplate, SOURCE_INSTANCE);
    insertSourceProjectAndUsers();
    responseSpec = catalog.rawJson(FormTemplateSpecCatalog.ISSUE_RESPONSE);
    responseScopeId = createScope(FormTemplateSpecCatalog.ISSUE_RESPONSE, "issue-response-scope");
    replyScopeId = createScope(FormTemplateSpecCatalog.ISSUE_REPLY, "issue-reply-scope");
  }

  @Test
  void formPlanWinsOverStaleCommentPlanOnFirstFactAndPeriodicRefresh() {
    insertSourceIssue(93001L, 81001L, LocalDateTime.of(2026, 11, 1, 10, 0));
    insertResponseNote(93001L, "2026.11.09", LocalDateTime.of(2026, 11, 2, 9, 0));
    insertResponseRecord(
        93001L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 11, 5, 10, 0), LocalDate.of(2026, 11, 15), "CC2026R4");
    insertReplyRecord(93001L, LocalDateTime.of(2026, 11, 6, 10, 0), "u-jia");

    rebuildAllIssueFacts();

    Map<String, Object> fact = liveFact(93001L);
    assertThat(fact.get("planned_resolution_at")).isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(fact.get("resolve_deadline_at")).isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(fact.get("has_response")).isEqualTo(true);
    assertThat(fact.get("research_template_time"))
        .isEqualTo(LocalDateTime.of(2026, 11, 5, 10, 0));
    assertThat(fact.get("fix_user")).isEqualTo("甲");
    assertThat(fact.get("is_response_delayed")).isEqualTo(false);
    assertThat(fact.get("response_overdue")).isEqualTo(false);
    assertThat(fact.get("is_resolve_delayed")).isEqualTo(false);

    long revisionsBefore = revisionCount(93001L);
    factBuildService.refreshCustomerIssueDelayFactsForConfig(config());
    FactRevisionFixture.completeRequiredProjections(
        jdbcTemplate,
        factTargetPublicationService,
        ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE),
        null);

    assertThat(revisionCount(93001L))
        .as("判定未变化时周期重算不产生新修订")
        .isEqualTo(revisionsBefore);
    Map<String, Object> refreshed = liveFact(93001L);
    assertThat(refreshed.get("planned_resolution_at"))
        .as("周期重算仍以最新整份表单响应为准，不回退到旧评论计划")
        .isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(refreshed.get("resolve_deadline_at"))
        .isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(refreshed.get("is_resolve_delayed")).isEqualTo(false);
  }

  @Test
  void periodicRecomputeUsesLatestFormInputInsteadOfStalePlanOrComment() {
    insertSourceIssue(93005L, 81005L, LocalDateTime.of(2026, 11, 2, 10, 0));
    insertResponseNote(93005L, "2026.10.25", LocalDateTime.of(2026, 11, 3, 9, 0));
    insertResponseRecord(
        93005L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 11, 3, 10, 0), LocalDate.of(2026, 10, 20), "CC2026R3");

    rebuildAllIssueFacts();
    assertThat(liveFact(93005L).get("is_resolve_delayed"))
        .as("首次事实取表单旧计划，已过期限")
        .isEqualTo(true);

    insertResponseRecord(
        93005L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 11, 4, 10, 0), LocalDate.of(2026, 11, 15), "CC2026R4");
    factBuildService.refreshCustomerIssueDelayFactsForConfig(config());
    FactRevisionFixture.completeRequiredProjections(
        jdbcTemplate,
        factTargetPublicationService,
        ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE),
        null);

    Map<String, Object> refreshed = liveFact(93005L);
    assertThat(refreshed.get("is_resolve_delayed"))
        .as("周期重算读取最新表单计划 11-15，而不是旧事实期限或过期评论计划")
        .isEqualTo(false);
    assertThat(refreshed.get("planned_resolution_at"))
        .as("计划、期限与标记必须来自同一份最新响应")
        .isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(refreshed.get("resolve_deadline_at"))
        .isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(refreshed.get("research_template_time"))
        .isEqualTo(LocalDateTime.of(2026, 11, 3, 10, 0));
  }

  @Test
  void periodicPublishesBusinessChangeEvenWhenBothDelayFlagsStayTrue() {
    insertSourceIssue(93106L, 81106L, LocalDateTime.of(2026, 11, 2, 10, 0));
    insertResponseRecord(
        93106L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 11, 3, 10, 0), LocalDate.of(2026, 10, 20), "CC2026R3");

    rebuildAllIssueFacts();
    assertThat(liveFact(93106L).get("is_resolve_delayed")).isEqualTo(true);

    insertResponseRecord(
        93106L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 11, 4, 10, 0), LocalDate.of(2026, 10, 25), "CC2026R3");
    refreshDelayFacts();

    Map<String, Object> refreshed = liveFact(93106L);
    assertThat(refreshed.get("is_resolve_delayed"))
        .as("两次标记都为 true 时仍必须发布新的业务计划")
        .isEqualTo(true);
    assertThat(refreshed.get("planned_resolution_at"))
        .isEqualTo(LocalDateTime.of(2026, 10, 25, 0, 0));
    assertThat(refreshed.get("resolve_deadline_at"))
        .isEqualTo(LocalDateTime.of(2026, 10, 25, 0, 0));
  }

  @Test
  void historicalIssueKeepsCommentPathEvenWhenFormRecordsExist() {
    insertSourceIssue(93002L, 81002L, LocalDateTime.of(2026, 10, 1, 10, 0));
    insertResponseNote(93002L, "2026.11.09", LocalDateTime.of(2026, 10, 2, 9, 0));
    insertResponseRecord(
        93002L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 10, 5, 10, 0), LocalDate.of(2026, 11, 15), "CC2026R4");

    rebuildAllIssueFacts();

    Map<String, Object> fact = liveFact(93002L);
    assertThat(fact.get("planned_resolution_at"))
        .as("启用点之前的议题保持原评论路径")
        .isEqualTo(LocalDateTime.of(2026, 11, 9, 0, 0));
    assertThat(fact.get("resolve_deadline_at"))
        .as("评论计划距创建超过 18 天时按既有 18 天默认期限")
        .isEqualTo(LocalDateTime.of(2026, 10, 19, 10, 0));
    assertThat(fact.get("has_response")).isEqualTo(true);
    assertThat(fact.get("is_resolve_delayed")).isEqualTo(true);

    factBuildService.refreshCustomerIssueDelayFactsForConfig(config());
    FactRevisionFixture.completeRequiredProjections(
        jdbcTemplate,
        factTargetPublicationService,
        ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE),
        null);
    assertThat(liveFact(93002L).get("resolve_deadline_at"))
        .isEqualTo(LocalDateTime.of(2026, 10, 19, 10, 0));
  }

  @Test
  void issueCreatedOneSecondBeforeSwitchStillUsesComment() {
    insertSourceIssue(
        93003L, 81003L, SWITCH_AT.minusSeconds(1));
    insertResponseNote(93003L, "2026.11.09", LocalDateTime.of(2026, 10, 31, 9, 0));
    insertResponseRecord(
        93003L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 10, 31, 10, 0), LocalDate.of(2026, 11, 15), "CC2026R4");

    rebuildAllIssueFacts();

    assertThat(liveFact(93003L).get("planned_resolution_at"))
        .isEqualTo(LocalDateTime.of(2026, 11, 9, 0, 0));
  }

  @Test
  void newIssueWithoutFormRecordsDoesNotInheritCommentResponse() {
    insertSourceIssue(93004L, 81004L, LocalDateTime.of(2026, 11, 1, 10, 0));
    insertResponseNote(93004L, "2026.11.09", LocalDateTime.of(2026, 11, 2, 9, 0));

    rebuildAllIssueFacts();

    Map<String, Object> fact = liveFact(93004L);
    assertThat(fact.get("has_response"))
        .as("新资源以表单为准，空表表示尚未填写而不是回退评论")
        .isEqualTo(false);
    assertThat(fact.get("planned_resolution_at")).isNull();
    assertThat(fact.get("research_template_time")).isNull();
    assertThat(fact.get("fix_user")).isNull();
    assertThat(fact.get("resolve_deadline_at"))
        .isEqualTo(LocalDateTime.of(2026, 11, 19, 10, 0));
  }

  @Test
  void commentAuthorityKeepsCommentEvenWhenFormRecordsExist() {
    insertSourceIssue(93101L, 81101L, LocalDateTime.of(2026, 11, 1, 10, 0));
    insertResponseNote(93101L, "2026.11.09", LocalDateTime.of(2026, 11, 2, 9, 0));
    insertResponseRecord(
        93101L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 11, 5, 10, 0), LocalDate.of(2026, 11, 15), "CC2026R4");
    insertReplyRecord(93101L, LocalDateTime.of(2026, 11, 6, 10, 0), "u-jia");
    insertAuthority(
        responseScopeId, FormTemplateSpecCatalog.ISSUE_RESPONSE, 81101L, "COMMENT", CANONICAL_URL);
    insertAuthority(
        replyScopeId, FormTemplateSpecCatalog.ISSUE_REPLY, 81101L, "COMMENT", CANONICAL_URL);

    rebuildAllIssueFacts();

    Map<String, Object> fact = liveFact(93101L);
    assertThat(fact.get("planned_resolution_at"))
        .as("持久 COMMENT 权威即使存在表单记录也仍读评论")
        .isEqualTo(LocalDateTime.of(2026, 11, 9, 0, 0));
    assertThat(fact.get("has_response")).isEqualTo(true);
    assertThat(fact.get("fix_user")).isIn(null, "");
  }

  @Test
  void existingFormAuthorityIsConsumedEvenBeforeEnablePoint() {
    insertSourceIssue(93102L, 81102L, LocalDateTime.of(2026, 10, 1, 10, 0));
    insertResponseNote(93102L, "2026.11.09", LocalDateTime.of(2026, 10, 2, 9, 0));
    insertResponseRecord(
        93102L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 10, 5, 10, 0), LocalDate.of(2026, 11, 20), "CC2026R4");
    insertAuthority(
        responseScopeId, FormTemplateSpecCatalog.ISSUE_RESPONSE, 81102L, "FORM", CANONICAL_URL);

    rebuildAllIssueFacts();

    assertThat(liveFact(93102L).get("planned_resolution_at"))
        .as("启用点只参与首次登记；既有 FORM 权威在 T 之前创建的资源上仍然生效")
        .isEqualTo(LocalDateTime.of(2026, 11, 20, 0, 0));
  }

  @Test
  void authoritySourceIdentityConflictIsRejectedInsteadOfSilentlySwitching() {
    insertSourceIssue(93103L, 81103L, LocalDateTime.of(2026, 11, 1, 10, 0));
    insertResponseNote(93103L, "2026.11.09", LocalDateTime.of(2026, 11, 2, 9, 0));
    insertAuthority(
        responseScopeId,
        FormTemplateSpecCatalog.ISSUE_RESPONSE,
        81103L,
        "FORM",
        "http://conflict.test");

    assertThatThrownBy(this::rebuildAllIssueFacts)
        .as("权威与范围规范地址不一致必须拒绝，不能静默切源")
        .isInstanceOf(com.data.collection.platform.common.exception.FormConflictException.class);
  }

  @Test
  void registeredScopeWithoutPersistentAuthorityStaysPendingRegistration() {
    FormIssueInputResolver.Resolution resolution =
        formIssueInputResolver.resolveAll(
            SOURCE_INSTANCE, List.of(new FormIssueInputResolver.IssueRef(PROJECT_ID, 81104L)));

    assertThat(resolution.inputs()).isEmpty();
    assertThat(resolution.pendingRegistration())
        .containsExactly(new FormIssueInputResolver.IssueRef(PROJECT_ID, 81104L));
  }

  @Test
  void voidedFormResponseAndReplyClearBusinessValuesAndCommentTemplateReasons() {
    insertSourceIssue(93105L, 81105L, LocalDateTime.of(2026, 11, 1, 10, 0));
    insertResponseNote(93105L, "2026.11.09", LocalDateTime.of(2026, 11, 2, 9, 0));
    insertCommentFixNote(93105L, LocalDateTime.of(2026, 11, 2, 10, 0));
    long responseId =
        insertResponseRecord(
            93105L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
            LocalDateTime.of(2026, 11, 5, 10, 0), LocalDate.of(2026, 11, 15), "CC2026R4");
    long replyId = insertReplyRecord(93105L, LocalDateTime.of(2026, 11, 6, 10, 0), "u-jia");

    rebuildAllIssueFacts();

    Map<String, Object> adopted = liveFact(93105L);
    assertThat(adopted.get("planned_resolution_at"))
        .isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(adopted.get("fix_user")).isEqualTo("甲");
    assertThat(adopted.get("reason_category")).isEqualTo("编码规范错误");
    assertThat(adopted.get("illegal_reasons").toString())
        .as("表单回复原因替换旧评论修复模板原因")
        .doesNotContain("未按照模板回复", "缺陷原因不唯一");

    voidRecord(responseScopeId, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseId);
    voidRecord(replyScopeId, FormTemplateSpecCatalog.ISSUE_REPLY, replyId);
    refreshDelayFacts();

    Map<String, Object> voided = liveFact(93105L);
    assertThat(voided.get("planned_resolution_at")).isNull();
    assertThat(voided.get("research_template_time")).isNull();
    assertThat(voided.get("fix_user")).isNull();
    assertThat(voided.get("reason_category")).isEqualTo("");
    assertThat(voided.get("illegal_reasons").toString())
        .as("作废后不残留旧评论原因或模板合规结论")
        .doesNotContain("未按照模板回复", "缺陷原因不唯一", "未按照要求填写缺陷调研模板");
    assertThat(voided.get("resolve_deadline_at"))
        .isEqualTo(LocalDateTime.of(2026, 11, 19, 10, 0));
  }

  @Test
  void newIssueWithoutRegisteredScopeKeepsCommentPath() {
    jdbcTemplate.update(
        "update form_template_scopes set status = 'DRAFT', current_version_id = null");
    insertSourceIssue(93006L, 81006L, LocalDateTime.of(2026, 11, 1, 10, 0));
    insertResponseNote(93006L, "2026.11.09", LocalDateTime.of(2026, 11, 2, 9, 0));

    rebuildAllIssueFacts();

    Map<String, Object> fact = liveFact(93006L);
    assertThat(fact.get("planned_resolution_at"))
        .as("未登记表单范围的项目不因启用点配置而丢失评论输入")
        .isEqualTo(LocalDateTime.of(2026, 11, 9, 0, 0));
    assertThat(fact.get("has_response")).isEqualTo(true);
  }

  @Test
  void movingEnablePointEarlierDoesNotFlipHistoricalCommentAuthority() {
    insertSourceIssue(93110L, 81110L, LocalDateTime.of(2026, 10, 1, 10, 0));
    insertResponseNote(93110L, "2026.11.09", LocalDateTime.of(2026, 10, 2, 9, 0));
    insertResponseRecord(
        93110L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 10, 5, 10, 0), LocalDate.of(2026, 11, 15), "CC2026R4");

    rebuildAllIssueFacts();
    assertThat(authorityOf(81110L, FormTemplateSpecCatalog.ISSUE_RESPONSE))
        .as("首次判定即冻结 COMMENT，不能只留下未登记的临时评论结果")
        .isEqualTo("COMMENT");
    assertThat(liveFact(93110L).get("planned_resolution_at"))
        .isEqualTo(LocalDateTime.of(2026, 11, 9, 0, 0));

    adoptionProperties.setIssueEnabledAt(LocalDateTime.of(2026, 9, 1, 0, 0));
    rebuildAllIssueFacts();

    assertThat(authorityOf(81110L, FormTemplateSpecCatalog.ISSUE_RESPONSE))
        .as("启用点前移不得改写已冻结的历史权威")
        .isEqualTo("COMMENT");
    assertThat(liveFact(93110L).get("planned_resolution_at"))
        .as("历史评论计划保持，不因 T 前移变成空表单")
        .isEqualTo(LocalDateTime.of(2026, 11, 9, 0, 0));
  }

  @Test
  void repeatedRegistrationKeepsLeasedHeadAndConsumedWatermark() {
    insertSourceIssue(93111L, 81111L, LocalDateTime.of(2026, 11, 2, 10, 0));
    rebuildAllIssueFacts();
    Long headId = jdbcTemplate.queryForObject(
        "select id from form_change_heads where resource_id = 81111 and template_key = ?",
        Long.class, FormTemplateSpecCatalog.ISSUE_RESPONSE);
    assertThat(headId).isNotNull();

    jdbcTemplate.update(
        "update form_change_heads set status = 'LEASED', lease_owner = 'worker-b',"
            + " lease_until = now() + interval '5 minutes', fencing_version = 23,"
            + " latest_change_version = 2, consumed_change_version = 1 where id = ?",
        headId);
    registerAgain(81111L);

    Map<String, Object> leased = headRow(headId);
    assertThat(leased.get("status")).as("重复登记不得把他人 LEASED 重置为 PENDING").isEqualTo("LEASED");
    assertThat(leased.get("lease_owner")).isEqualTo("worker-b");
    assertThat(((Number) leased.get("fencing_version")).longValue()).isEqualTo(23);
    assertThat(((Number) leased.get("latest_change_version")).longValue()).isEqualTo(2);
    assertThat(((Number) leased.get("consumed_change_version")).longValue()).isEqualTo(1);

    jdbcTemplate.update(
        "update form_change_heads set status = 'CONSUMED', lease_owner = null,"
            + " lease_until = null, consumed_change_version = 2 where id = ?",
        headId);
    registerAgain(81111L);

    Map<String, Object> consumed = headRow(headId);
    assertThat(consumed.get("status")).as("已消费 head 不得因重复登记反复待消费").isEqualTo("CONSUMED");
    assertThat(((Number) consumed.get("latest_change_version")).longValue()).isEqualTo(2);
    assertThat(((Number) consumed.get("consumed_change_version")).longValue()).isEqualTo(2);
  }

  @Test
  void missingHeadIsInitializedFromPersistentFormAuthority() {
    long iid = 81112L;
    insertAuthority(
        responseScopeId, FormTemplateSpecCatalog.ISSUE_RESPONSE, iid, "FORM", CANONICAL_URL);
    insertAuthority(replyScopeId, FormTemplateSpecCatalog.ISSUE_REPLY, iid, "FORM", CANONICAL_URL);
    jdbcTemplate.update(
        "update form_resource_authorities set authority_revision = 7 where resource_id = ?", iid);

    registerAgain(iid);

    long responseHeadId = headId(iid, FormTemplateSpecCatalog.ISSUE_RESPONSE);
    Map<String, Object> responseHead = headRow(responseHeadId);
    assertThat(responseHead.get("status")).as("缺 head 时按真实权威修订初始化").isEqualTo("PENDING");
    assertThat(((Number) responseHead.get("latest_change_version")).longValue()).isEqualTo(1);
    assertThat(((Number) responseHead.get("consumed_change_version")).longValue()).isZero();
    assertThat(((Number) responseHead.get("pending_authority_revision")).longValue()).isEqualTo(7);
    assertThat(((Number) responseHead.get("pending_source_set_revision")).longValue()).isEqualTo(1);
    assertThat(responseHead.get("pending_source_set_hash")).isNotNull();
    assertThat(((Number) responseHead.get("pending_storage_version_id")).longValue())
        .isEqualTo(currentVersionId(responseScopeId));

    jdbcTemplate.update(
        "update form_change_heads set status = 'LEASED', lease_owner = 'worker-b',"
            + " lease_until = now() + interval '5 minutes', fencing_version = 11,"
            + " latest_change_version = 3, consumed_change_version = 2 where id = ?",
        responseHeadId);
    registerAgain(iid);

    Map<String, Object> untouched = headRow(responseHeadId);
    assertThat(untouched.get("status")).as("已有 head 只核验，不重置状态").isEqualTo("LEASED");
    assertThat(untouched.get("lease_owner")).isEqualTo("worker-b");
    assertThat(((Number) untouched.get("fencing_version")).longValue()).isEqualTo(11);
    assertThat(((Number) untouched.get("latest_change_version")).longValue()).isEqualTo(3);
    assertThat(((Number) untouched.get("consumed_change_version")).longValue()).isEqualTo(2);
  }

  @Test
  void unconsumedFormEvidenceIsDetectedOnlyForFormAuthority() {
    long iid = 81113L;
    insertSourceIssue(93113L, iid, LocalDateTime.of(2026, 11, 2, 10, 0));
    insertResponseRecord(
        93113L,
        FormTemplateSpecCatalog.ISSUE_RESPONSE,
        responseScopeId,
        LocalDateTime.of(2026, 11, 3, 10, 0),
        LocalDate.of(2026, 11, 15),
        "CC2026R4");
    rebuildAllIssueFacts();

    assertThat(
            formHeadCaptureService.hasUnconsumedFormEvidence(
                SOURCE_INSTANCE, PROJECT_ID, iid))
        .as("发布并结算后不应残留未消费表单证据")
        .isFalse();

    jdbcTemplate.update(
        "update form_change_heads set latest_change_version = latest_change_version + 1"
            + " where resource_id = ? and template_key = ?",
        iid,
        FormTemplateSpecCatalog.ISSUE_RESPONSE);
    assertThat(
            formHeadCaptureService.hasUnconsumedFormEvidence(
                SOURCE_INSTANCE, PROJECT_ID, iid))
        .as("表单head已推进但事实尚未刷新时必须拒发")
        .isTrue();

    jdbcTemplate.update(
        "update form_resource_authorities set authority = 'COMMENT'"
            + " where resource_id = ? and template_key = ?",
        iid,
        FormTemplateSpecCatalog.ISSUE_RESPONSE);
    assertThat(
            formHeadCaptureService.hasUnconsumedFormEvidence(
                SOURCE_INSTANCE, PROJECT_ID, iid))
        .as("COMMENT权威不得被表单head误判为待消费")
        .isFalse();
  }

  @Test
  void periodicRefreshSettlesOnlyInsideFinalPublication() {
    insertSourceIssue(93120L, 81120L, LocalDateTime.of(2026, 11, 1, 10, 0));
    insertResponseRecord(
        93120L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 11, 5, 10, 0), LocalDate.of(2026, 11, 15), "CC2026R4");
    rebuildAllIssueFacts();
    UUID pointerBefore = currentIssuePublication();
    insertResponseRecord(
        93120L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 11, 6, 10, 0), LocalDate.of(2026, 11, 16), "CC2026R5");

    factBuildService.refreshCustomerIssueDelayFactsForConfig(config());

    long headId = headId(81120L, FormTemplateSpecCatalog.ISSUE_RESPONSE);
    Map<String, Object> pending = headRow(headId);
    assertThat(pending.get("status")).as("捕获后等待最终公开，不能提前 CONSUMED").isEqualTo("LEASED");
    assertThat(((Number) pending.get("consumed_change_version")).longValue())
        .as("首次构建已结算旧水位，新一轮仍待公开")
        .isEqualTo(1);
    assertThat(currentIssuePublication()).as("PROJECTING 候选未公开，指针不得前进")
        .isEqualTo(pointerBefore);
    UUID candidateId = jdbcTemplate.queryForObject(
        "select publication_id from form_publication_captures where head_id = ?"
            + " order by captured_at desc limit 1",
        UUID.class, headId);
    assertThat(jdbcTemplate.queryForObject(
        "select state from read_publications where id = ?", String.class, candidateId))
        .isEqualTo("PROJECTING");
    assertThat(jdbcTemplate.queryForObject(
        "select status from form_publication_captures where publication_id = ? and head_id = ?",
        String.class, candidateId, headId)).isEqualTo("CAPTURED");

    FactRevisionFixture.completeRequiredProjections(
        jdbcTemplate,
        factTargetPublicationService,
        ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE),
        null);

    Map<String, Object> settled = headRow(headId);
    assertThat(settled.get("status")).isEqualTo("CONSUMED");
    assertThat(((Number) settled.get("consumed_change_version")).longValue())
        .isEqualTo(((Number) settled.get("latest_change_version")).longValue());
    assertThat(currentIssuePublication()).as("最终公开同时推进指针").isNotEqualTo(pointerBefore);
  }

  @Test
  void driftAfterBuildKeepsPublicationAndConsumptionAtZero() {
    insertSourceIssue(93121L, 81121L, LocalDateTime.of(2026, 11, 1, 10, 0));
    insertResponseRecord(
        93121L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 11, 5, 10, 0), LocalDate.of(2026, 11, 15), "CC2026R4");
    rebuildAllIssueFacts();
    UUID pointerBefore = currentIssuePublication();
    insertResponseRecord(
        93121L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 11, 6, 10, 0), LocalDate.of(2026, 11, 16), "CC2026R5");
    factBuildService.refreshCustomerIssueDelayFactsForConfig(config());
    long headId = headId(81121L, FormTemplateSpecCatalog.ISSUE_RESPONSE);

    jdbcTemplate.update(
        "update form_change_heads set latest_change_version = latest_change_version + 1"
            + " where id = ?", headId);

    assertThatThrownBy(() -> FactRevisionFixture.completeRequiredProjections(
            jdbcTemplate,
            factTargetPublicationService,
            ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE),
            null))
        .isInstanceOf(com.data.collection.platform.common.exception.FormConflictException.class);

    assertThat(currentIssuePublication()).isEqualTo(pointerBefore);
    Map<String, Object> head = headRow(headId);
    assertThat(((Number) head.get("consumed_change_version")).longValue())
        .as("旧候选未公开，新水位不得结算")
        .isEqualTo(1);
    assertThat(liveFact(93121L).get("planned_resolution_at"))
        .as("旧候选未公开，已发布事实保持不变")
        .isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
  }

  private UUID currentIssuePublication() {
    List<UUID> ids = jdbcTemplate.queryForList(
        "select current_publication_id from read_publication_domains"
            + " where source_instance = ? and fact_type = 'ISSUE'",
        UUID.class, SOURCE_INSTANCE);
    return ids.isEmpty() ? null : ids.getFirst();
  }

  private long headId(long iid, String templateKey) {
    return jdbcTemplate.queryForObject(
        "select id from form_change_heads where resource_id = ? and template_key = ?",
        Long.class, iid, templateKey);
  }

  private void registerAgain(long iid) {
    formIssueAuthorityRegistrar.registerMissing(
        SOURCE_INSTANCE,
        Map.of(new FormIssueInputResolver.IssueRef(PROJECT_ID, iid),
            LocalDateTime.of(2026, 11, 2, 10, 0)),
        SWITCH_AT,
        LocalDateTime.of(2026, 11, 3, 0, 0));
  }

  private Map<String, Object> headRow(long headId) {
    return jdbcTemplate.queryForMap("select * from form_change_heads where id = ?", headId);
  }

  private String authorityOf(long iid, String templateKey) {
    List<String> values = jdbcTemplate.queryForList(
        "select authority from form_resource_authorities where resource_id = ? and template_key = ?",
        String.class, iid, templateKey);
    return values.isEmpty() ? null : values.getFirst();
  }

  private void insertAuthority(
      long scopeId, String templateKey, long iid, String authority, String canonicalUrl) {
    jdbcTemplate.update(
        """
        insert into form_resource_authorities(
            source_config_id, source_instance, canonical_gitlab_base_url, project_id,
            resource_type, resource_id, template_key, scope_id, authority, authority_revision,
            effective_at, created_at, updated_at)
        values (?, ?, ?, ?, 'issue', ?, ?, ?, ?, 1, current_timestamp, current_timestamp,
                current_timestamp)
        on conflict (source_config_id, project_id, resource_type, resource_id, template_key)
        do update set scope_id = excluded.scope_id, canonical_gitlab_base_url =
            excluded.canonical_gitlab_base_url, authority = excluded.authority
        """,
        SOURCE_CONFIG_ID,
        SOURCE_INSTANCE,
        canonicalUrl,
        PROJECT_ID,
        iid,
        templateKey,
        scopeId,
        authority);
  }

  private long createScope(String templateKey, String scopeKey) {
    String bindingHash =
        FormScopeBinding.hash(
            objectMapper,
            FormScopeBinding.create(
                objectMapper,
                templateKey,
                SOURCE_CONFIG_ID,
                SOURCE_INSTANCE,
                CANONICAL_URL,
                List.of(PROJECT_ID),
                PROJECT_ID,
                "issue"));
    long scopeId =
        jdbcTemplate.queryForObject(
            """
            insert into form_template_scopes(
                template_key, scope_key, source_config_id, source_instance,
                canonical_gitlab_base_url, project_ids, version_project_id, resource_type,
                status, binding_hash)
            values (?, ?, ?, ?, ?, ?::jsonb, ?, 'issue', 'DRAFT', ?)
            returning id
            """,
            Long.class,
            templateKey,
            scopeKey,
            SOURCE_CONFIG_ID,
            SOURCE_INSTANCE,
            CANONICAL_URL,
            "[" + PROJECT_ID + "]",
            PROJECT_ID,
            bindingHash);
    long versionId =
        jdbcTemplate.queryForObject(
            "select nextval(pg_get_serial_sequence('form_template_versions', 'id'))", Long.class);
    FormVersionSchema schema =
        new FormVersionSchemaService(objectMapper)
            .generate(scopeId, versionId, catalog.require(templateKey), false);
    jdbcTemplate.update(
        """
        insert into form_template_versions(
            id, scope_id, version_label, storage_kind, instance_table, record_table, field_spec,
            version_status, published_at)
        values (?, ?, 'v1', 'RECORDS', ?, ?, ?::jsonb, 'PUBLISHED', ?)
        """,
        versionId,
        scopeId,
        schema.instanceTable(),
        schema.recordTable(),
        catalog.rawJson(templateKey),
        Timestamp.valueOf(LocalDateTime.of(2026, 10, 1, 0, 0)));
    schema.statements().forEach(jdbcTemplate::execute);
    jdbcTemplate.update(
        "update form_template_scopes set status = 'ACTIVE', current_version_id = ?,"
            + " source_set_revision = 1, source_set_hash = form_source_set_hash(id)"
            + " where id = ?",
        versionId,
        scopeId);
    return scopeId;
  }

  private long insertResponseRecord(
      long issueId,
      String templateKey,
      long scopeId,
      LocalDateTime createdAt,
      LocalDate plannedResolutionDate,
      String branch) {
    long iid = issueIid(issueId);
    long versionId = currentVersionId(scopeId);
    String instanceTable = instanceTable(versionId, templateKey, scopeId);
    long instanceId = ensureInstance(instanceTable, scopeId, templateKey, issueId, iid);
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("problem_type", "DEFECT");
    fields.put("problem_cause", "部署后响应超时");
    fields.put("modification_plan", "调整超时阈值");
    fields.put("planned_resolution_date", java.sql.Date.valueOf(plannedResolutionDate));
    fields.put("planned_merge_version_branch", branch);
    fields.put("owner_confirmation_applicable", false);
    long recordId =
        insertRecord(
            recordTable(versionId, templateKey, scopeId), instanceId, versionId, createdAt,
            "u-response", fields);
    insertLocation(recordId, scopeId, versionId, instanceId, issueId, iid, "u-response", createdAt);
    bumpHead(scopeId, templateKey, iid, recordId, versionId);
    return recordId;
  }

  private long insertReplyRecord(long issueId, LocalDateTime createdAt, String createdBy) {
    long iid = issueIid(issueId);
    long versionId = currentVersionId(replyScopeId);
    String instanceTable =
        instanceTable(versionId, FormTemplateSpecCatalog.ISSUE_REPLY, replyScopeId);
    long instanceId =
        ensureInstance(instanceTable, replyScopeId, FormTemplateSpecCatalog.ISSUE_REPLY, issueId, iid);
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("fix_status", "PARTIAL");
    fields.put("cause_keys", "{standard_error}");
    fields.put("specific_reason", "已部分修复");
    fields.put("modification_plan", "补充校验");
    fields.put("caused_by_other_defect", false);
    fields.put("affected_kinds", "{NONE}");
    fields.put("impact_test_status", "NOT_APPLICABLE");
    fields.put("has_residual_impact", false);
    fields.put("relation_table_updated", "NOT_APPLICABLE");
    long recordId =
        insertRecord(
            recordTable(versionId, FormTemplateSpecCatalog.ISSUE_REPLY, replyScopeId), instanceId,
            versionId, createdAt, createdBy, fields);
    insertLocation(recordId, replyScopeId, versionId, instanceId, issueId, iid, createdBy, createdAt);
    bumpHead(replyScopeId, FormTemplateSpecCatalog.ISSUE_REPLY, iid, recordId, versionId);
    return recordId;
  }

  /** 模拟真实写入对 head 的推进：新记录或作废都使最新修订前进并置待消费。 */
  private void bumpHead(long scopeId, String templateKey, long iid, long recordId, long versionId) {
    int recordVersion =
        jdbcTemplate.queryForObject(
            "select record_version from " + recordTable(versionId, templateKey, scopeId)
                + " where id = ?",
            Integer.class,
            recordId);
    jdbcTemplate.update(
        """
        insert into form_change_heads(
            source_config_id, source_instance, canonical_gitlab_base_url, project_id, resource_type,
            resource_id, template_key, scope_id, latest_change_version, consumed_change_version,
            pending_record_id, pending_record_version, pending_storage_version_id,
            pending_authority_revision, status, fencing_version, created_at, updated_at)
        values (?, ?, ?, ?, 'issue', ?, ?, ?, 1, 0, ?, ?, ?, 1, 'PENDING', 0,
                current_timestamp, current_timestamp)
        on conflict (source_config_id, project_id, resource_type, resource_id, template_key)
        do update set latest_change_version = form_change_heads.latest_change_version + 1,
                      pending_record_id = excluded.pending_record_id,
                      pending_record_version = excluded.pending_record_version,
                      pending_storage_version_id = excluded.pending_storage_version_id,
                      pending_authority_revision = 1, status = 'PENDING',
                      updated_at = current_timestamp
        """,
        SOURCE_CONFIG_ID,
        SOURCE_INSTANCE,
        CANONICAL_URL,
        PROJECT_ID,
        iid,
        templateKey,
        scopeId,
        recordId,
        recordVersion,
        versionId);
  }

  private void voidRecord(long scopeId, String templateKey, long recordId) {
    long versionId = currentVersionId(scopeId);
    long iid =
        jdbcTemplate.queryForObject(
            "select resource_id::bigint from form_record_locations where record_id = ?",
            Long.class,
            recordId);
    jdbcTemplate.update(
        "update "
            + recordTable(versionId, templateKey, scopeId)
            + " set deleted = true, record_version = record_version + 1 where id = ?",
        recordId);
    bumpHead(scopeId, templateKey, iid, recordId, versionId);
  }

  private long ensureInstance(
      String instanceTable, long scopeId, String templateKey, long issueId, long iid) {
    List<Long> existing =
        jdbcTemplate.queryForList(
            "select id from "
                + instanceTable
                + " where scope_id = ? and project_id = ? and resource_id = ? and template_key = ?",
            Long.class,
            scopeId,
            PROJECT_ID,
            iid,
            templateKey);
    if (!existing.isEmpty()) {
      return existing.getFirst();
    }
    return jdbcTemplate.queryForObject(
        """
        insert into %s(
            scope_id, source_config_id, source_instance, gitlab_base_url, project_id,
            resource_type, resource_id, template_key, created_at, updated_at)
        values (?, ?, ?, ?, ?, 'issue', ?, ?, current_timestamp, current_timestamp)
        returning id
        """
            .formatted(instanceTable),
        Long.class,
        scopeId,
        SOURCE_CONFIG_ID,
        SOURCE_INSTANCE,
        CANONICAL_URL,
        PROJECT_ID,
        iid,
        templateKey);
  }

  private long insertRecord(
      String recordTable,
      long instanceId,
      long versionId,
      LocalDateTime createdAt,
      String createdBy,
      Map<String, Object> fields) {
    List<String> columns =
        new ArrayList<>(
            List.of(
                "instance_id",
                "origin_version_id",
                "template_version",
                "created_by",
                "request_key",
                "create_payload_hash",
                "record_version",
                "deleted",
                "created_at",
                "updated_at"));
    List<Object> args =
        new ArrayList<>(
            List.of(
                instanceId,
                versionId,
                "v1",
                createdBy,
                "req-" + java.util.UUID.randomUUID(),
                "0".repeat(64),
                1,
                false,
                Timestamp.valueOf(createdAt),
                Timestamp.valueOf(createdAt)));
    for (Map.Entry<String, Object> entry : fields.entrySet()) {
      columns.add(entry.getKey());
      args.add(entry.getValue());
    }
    String placeholders =
        String.join(
            ", ",
            columns.stream()
                .map(column -> TEXT_ARRAY_COLUMNS.contains(column) ? "?::text[]" : "?")
                .toList());
    return jdbcTemplate.queryForObject(
        "insert into "
            + recordTable
            + " ("
            + String.join(", ", columns)
            + ") values ("
            + placeholders
            + ") returning id",
        Long.class,
        args.toArray());
  }

  private void insertLocation(
      long recordId,
      long scopeId,
      long versionId,
      long instanceId,
      long issueId,
      long iid,
      String createdBy,
      LocalDateTime createdAt) {
    String originSpecHash =
        jdbcTemplate.queryForObject(
            "select form_spec_hash(?::jsonb)", String.class, specOf(scopeId));
    jdbcTemplate.update(
        """
        insert into form_record_locations(
            record_id, scope_id, storage_version_id, origin_version_id, instance_mode, instance_id,
            source_config_id, source_instance, gitlab_base_url, project_id, resource_type,
            resource_id, request_key, created_by, origin_spec_hash, create_payload_hash, created_at)
        values (?, ?, ?, ?, 'MATERIALIZED', ?, ?, ?, ?, ?, 'issue', ?, null, ?, ?, null, ?)
        """,
        recordId,
        scopeId,
        versionId,
        versionId,
        instanceId,
        SOURCE_CONFIG_ID,
        SOURCE_INSTANCE,
        CANONICAL_URL,
        PROJECT_ID,
        String.valueOf(iid),
        createdBy,
        originSpecHash,
        Timestamp.valueOf(createdAt));
  }

  private String specOf(long scopeId) {
    return jdbcTemplate.queryForObject(
        """
        select v.field_spec::text
          from form_template_scopes s join form_template_versions v on v.id = s.current_version_id
         where s.id = ?
        """,
        String.class,
        scopeId);
  }

  private long currentVersionId(long scopeId) {
    return jdbcTemplate.queryForObject(
        "select current_version_id from form_template_scopes where id = ?", Long.class, scopeId);
  }

  private String instanceTable(long versionId, String templateKey, long scopeId) {
    return jdbcTemplate.queryForObject(
        "select instance_table from form_template_versions where id = ? and scope_id = ?",
        String.class,
        versionId,
        scopeId);
  }

  private String recordTable(long versionId, String templateKey, long scopeId) {
    return jdbcTemplate.queryForObject(
        "select record_table from form_template_versions where id = ? and scope_id = ?",
        String.class,
        versionId,
        scopeId);
  }

  private long issueIid(long issueId) {
    return jdbcTemplate.queryForObject(
        "select iid from ods_gitlab_issues where id = ?", Long.class, issueId);
  }

  private void insertSourceProjectAndUsers() {
    jdbcTemplate.update(
        "insert into ods_gitlab_projects(id, name, mirror_deleted) values (?, ?, false)",
        PROJECT_ID,
        PROJECT_NAME);
    jdbcTemplate.update(
        "insert into ods_gitlab_users(id, name, mirror_deleted) values (501, 'reporter', false)");
    jdbcTemplate.update(
        "insert into ods_gitlab_users(id, name, mirror_deleted) values (502, '甲', false)");
    jdbcTemplate.update(
        """
        insert into platform_ldap_users(user_id, real_name)
        values ('u-response', '响应人'), ('u-jia', '甲')
        on conflict (user_id) do update set real_name = excluded.real_name
        """);
  }

  private void insertSourceIssue(long issueId, long iid, LocalDateTime createdAt) {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issues(
            id, iid, project_id, title, author_id, created_at, updated_at, closed_at, state_id,
            milestone_id, mirror_deleted)
        values (?, ?, ?, ?, 501, ?, ?, null, ?, null, false)
        """,
        issueId,
        iid,
        PROJECT_ID,
        "表单接管议题 " + iid,
        Timestamp.valueOf(createdAt),
        Timestamp.valueOf(createdAt.plusHours(1)),
        1);
  }

  private void insertResponseNote(long issueId, String planDate, LocalDateTime createdAt) {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_notes(
            id, noteable_id, noteable_type, note, author_id, created_at, updated_at, mirror_deleted)
        values (?, ?, 'Issue', ?, 502, ?, ?, false)
        """,
        issueId + 500L,
        issueId,
        RESPONSE_TEMPLATE.formatted(planDate),
        Timestamp.valueOf(createdAt),
        Timestamp.valueOf(createdAt));
  }

  private void insertCommentFixNote(long issueId, LocalDateTime createdAt) {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_notes(
            id, noteable_id, noteable_type, note, author_id, created_at, updated_at, mirror_deleted)
        values (?, ?, 'Issue', ?, 502, ?, ?, false)
        """,
        issueId + 900L,
        issueId,
        "### 1、修复状态\n[x] 新增需求\n### 3、请描述具体原因：\n旧评论原因",
        Timestamp.valueOf(createdAt),
        Timestamp.valueOf(createdAt));
  }

  private FactBuildResponse rebuildAllIssueFacts() {
    List<Long> rootIds =
        jdbcTemplate.queryForList("select id from ods_gitlab_issues order by id", Long.class);
    return rebuildIssueFactsFromSource(rootIds);
  }

  private FactBuildResponse rebuildIssueFactsFromSource(List<Long> rootIds) {
    ensureCertifiedIssueBaseline();
    FactBuildResponse response =
        factTargetPublicationService.publishWithinCandidate(
            new BypassPublicationRequest(
                ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE),
                "GITLAB",
                SEAL,
                0L,
                "form-adoption-pipeline-test",
                null,
                Instant.now(),
                rootIds.equals(
                    jdbcTemplate.queryForList(
                        "select id from ods_gitlab_issues order by id", Long.class))),
            target -> factBuildService.rebuildIssueFactsByRootIds(target, rootIds));
    FactRevisionFixture.completeRequiredProjections(
        jdbcTemplate,
        factTargetPublicationService,
        ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE),
        null);
    return response;
  }

  private void ensureCertifiedIssueBaseline() {
    readPublicationService.bootstrapInitialVersion(
        ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE),
        "GITLAB",
        Instant.now(),
        SEAL,
        0L,
        key -> InitialVersionVerification.VerificationResult.accepted());
  }

  private void refreshDelayFacts() {
    factBuildService.refreshCustomerIssueDelayFactsForConfig(config());
    FactRevisionFixture.completeRequiredProjections(
        jdbcTemplate,
        factTargetPublicationService,
        ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE),
        null);
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance(SOURCE_INSTANCE);
    return config;
  }

  private Map<String, Object> liveFact(long issueId) {
    Map<String, Object> row =
        jdbcTemplate.queryForMap(
            "select * from issue_fact where source_instance = ? and issue_id = ?" + LIVE_REVISION,
            SOURCE_INSTANCE,
            issueId);
    row.replaceAll((key, value) -> value instanceof Timestamp timestamp
        ? timestamp.toLocalDateTime()
        : value);
    return row;
  }

  private long revisionCount(long issueId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from issue_fact where source_instance = ? and issue_id = ?",
        Long.class,
        SOURCE_INSTANCE,
        issueId);
  }

  private void cleanSourceAndFacts() {
    jdbcTemplate.update("delete from issue_fact_customer_members");
    jdbcTemplate.update("delete from issue_fact");
    jdbcTemplate.update("delete from module_dictionary");
    jdbcTemplate.update("delete from issue_scope_members");
    jdbcTemplate.update("delete from issue_scope_groups");
    jdbcTemplate.update("delete from issue_scope_catalogs");
    jdbcTemplate.update("delete from gitlab_sync_configs");
    jdbcTemplate.update("delete from ods_gitlab_label_links");
    jdbcTemplate.update("delete from ods_gitlab_issue_assignees");
    jdbcTemplate.update("delete from ods_gitlab_labels");
    jdbcTemplate.update("delete from ods_gitlab_notes");
    jdbcTemplate.update("delete from ods_gitlab_issues");
    jdbcTemplate.update("delete from ods_gitlab_milestones");
    jdbcTemplate.update("delete from ods_gitlab_users");
    jdbcTemplate.update("delete from ods_gitlab_projects");
    jdbcTemplate.update("delete from platform_ldap_users where user_id in ('u-response', 'u-jia')");
  }

  private void cleanFormDirectory() {
    List<Map<String, Object>> versions =
        jdbcTemplate.queryForList(
            "select instance_table, record_table from form_template_versions");
    for (Map<String, Object> version : versions) {
      if (version.get("record_table") != null) {
        jdbcTemplate.execute(
            "drop table if exists " + version.get("record_table") + " cascade");
      }
      if (version.get("instance_table") != null) {
        jdbcTemplate.execute(
            "drop table if exists " + version.get("instance_table") + " cascade");
      }
    }
    jdbcTemplate.update("delete from form_record_locations");
    jdbcTemplate.update("delete from form_publication_captures");
    jdbcTemplate.update("delete from form_head_captures");
    jdbcTemplate.update("delete from form_change_heads");
    jdbcTemplate.update("delete from form_resource_authorities");
    jdbcTemplate.update("delete from form_directory_event_cursors");
    jdbcTemplate.update("delete from form_directory_events");
    jdbcTemplate.update(
        "update form_template_scopes set status = 'DRAFT', current_version_id = null");
    jdbcTemplate.update("delete from form_template_versions");
    jdbcTemplate.update("delete from form_template_scopes");
  }
}
