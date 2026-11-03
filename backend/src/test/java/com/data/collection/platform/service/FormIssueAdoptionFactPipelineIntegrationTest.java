package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.config.PlatformAsyncConfiguration;
import com.data.collection.platform.domain.customerissue.CustomerIssueEfficiencyRules;
import com.data.collection.platform.domain.form.FormScopeBinding;
import com.data.collection.platform.domain.form.FormTemplateSpecCatalog;
import com.data.collection.platform.domain.form.FormVersionSchema;
import com.data.collection.platform.entity.CustomerIssueRecordRowResponse;
import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.form.FormActorContext;
import com.data.collection.platform.entity.form.FormCreateRequest;
import com.data.collection.platform.entity.form.FormCreateResponse;
import com.data.collection.platform.entity.form.FormRecordView;
import com.data.collection.platform.entity.form.FormUpdateRequest;
import com.data.collection.platform.entity.form.FormVoidRequest;
import com.data.collection.platform.entity.read.FactReadQueryContext;
import com.data.collection.platform.entity.read.FactReadSelection;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.entity.read.PublishedReadContext;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.entity.statistics.StatisticCellData;
import com.data.collection.platform.entity.statistics.StatisticRowData;
import com.data.collection.platform.security.PlatformPermissionCodes;
import com.data.collection.platform.service.form.FormRecordService;
import com.data.collection.platform.service.form.FormVersionSchemaService;
import com.data.collection.platform.service.read.BypassPublicationRequest;
import com.data.collection.platform.service.read.FactReadVisibility;
import com.data.collection.platform.service.read.InitialVersionVerification;
import com.data.collection.platform.service.read.ReadPublicationService;
import com.data.collection.platform.service.statistics.CustomerIssueDefectCauseBoardService;
import com.data.collection.platform.service.statistics.CustomerIssueDelayIssuesBoardService;
import com.data.collection.platform.service.statistics.CustomerIssueResponseEfficiencyBoardService;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
      "platform.form-directory-event.worker-enabled=false",
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
          // 一致性夹具不依赖后台维护时序；初始接管遇维护锁的 NOWAIT 让位另有显式锁回归。
          .withCommand("postgres", "-c", "autovacuum=off")
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
  @Autowired private FormRecordService formRecordService;
  @Autowired private CustomerIssueRecordService customerIssueRecordService;
  @Autowired private CustomerIssueDefectCauseBoardService defectCauseBoardService;
  @Autowired private CustomerIssueDelayIssuesBoardService delayIssuesBoardService;
  @Autowired private CustomerIssueResponseEfficiencyBoardService responseEfficiencyBoardService;
  @Autowired private BusinessDateProvider businessDateProvider;
  @Autowired private ApplicationEventPublisher applicationEventPublisher;
  @Autowired private PlatformTransactionManager transactionManager;

  @Autowired
  @Qualifier(PlatformAsyncConfiguration.PLATFORM_ASYNC_EXECUTOR)
  private ThreadPoolTaskExecutor platformAsyncExecutor;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private SyncFactPublicationStateService qualification;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private CustomerIssueDelayClosureOrchestrator orchestrator;

  private long responseScopeId;
  private long replyScopeId;
  private String responseSpec;
  private boolean delayRoundsSuppressed;

  private static final Set<String> FORM_RECORD_PERMISSIONS =
      Set.of(
          PlatformPermissionCodes.FORM_RECORD_CREATE,
          PlatformPermissionCodes.FORM_RECORD_EDIT_OWN,
          PlatformPermissionCodes.FORM_RECORD_DELETE_OWN);
  private final FormActorContext responseActor =
      new FormActorContext("u-response", "响应人", FORM_RECORD_PERMISSIONS);
  private final FormActorContext replyActor =
      new FormActorContext("u-jia", "甲", FORM_RECORD_PERMISSIONS);

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
    insertFormSourceConfig();
    cleanFormDirectory();
    jdbcTemplate.execute("truncate read_publications, read_publication_domains cascade");
    LabelEventHistoryTestSupport.markComplete(jdbcTemplate, SOURCE_INSTANCE);
    insertSourceProjectAndUsers();
    responseSpec = catalog.rawJson(FormTemplateSpecCatalog.ISSUE_RESPONSE);
    responseScopeId = createScope(FormTemplateSpecCatalog.ISSUE_RESPONSE, "issue-response-scope");
    replyScopeId = createScope(FormTemplateSpecCatalog.ISSUE_REPLY, "issue-reply-scope");
    // 默认不启动后台轮次：多数用例同步重算并断言发布状态，只有显式验证轮次编排的用例才恢复调度。
    suppressDelayRounds();
  }

  @AfterEach
  void awaitInFlightDelayRound() {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (platformAsyncExecutor.getActiveCount() == 0
          && platformAsyncExecutor.getThreadPoolExecutor().getQueue().isEmpty()) {
        return;
      }
      try {
        Thread.sleep(25L);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("等待异步轮次结束被中断", error);
      }
    }
    throw new AssertionError("平台异步任务未在期限内收尾：" + delayClosureDiagnostics());
  }

  /** 暂停表单公开后的后台轮次，避免异步轮次与测试线程竞争同一域候选。 */
  private void suppressDelayRounds() {
    delayRoundsSuppressed = true;
    org.mockito.Mockito.doNothing()
        .when(orchestrator)
        .requestSourceRound(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  /** 恢复真实轮次调度；仅供显式验证轮次编排的用例使用。 */
  private void resumeDelayRounds() {
    if (delayRoundsSuppressed) {
      delayRoundsSuppressed = false;
      org.mockito.Mockito.reset(orchestrator);
    }
  }

  @AfterEach
  void restoreDelayRounds() {
    resumeDelayRounds();
  }

  @Test
  void formPlanWinsOverStaleCommentPlanOnFirstFactAndPeriodicRefresh() {
    resumeDelayRounds();
    insertSourceIssue(93001L, 81001L, LocalDateTime.of(2026, 11, 1, 10, 0));
    insertResponseNote(93001L, "2026.11.09", LocalDateTime.of(2026, 11, 2, 9, 0));
    insertResponseRecord(
        93001L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 11, 5, 10, 0), LocalDate.of(2026, 11, 15), "CC2026R4");
    insertReplyRecord(93001L, LocalDateTime.of(2026, 11, 6, 10, 0), "u-jia");

    rebuildAllIssueFacts();
    awaitDelayClosureSettled();
    completeIssueProjections();

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
  void onlyNewFormConsumptionTriggersOneSourceRoundAndUnchangedRunConverges() {
    resumeDelayRounds();
    insertSourceIssue(93012L, 81012L, LocalDateTime.of(2026, 11, 1, 10, 0));
    insertResponseRecord(
        93012L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 11, 5, 10, 0), LocalDate.of(2026, 11, 15), "CC2026R4");

    rebuildAllIssueFacts();
    org.mockito.Mockito.verify(orchestrator, org.mockito.Mockito.times(1))
        .requestSourceRound(
            org.mockito.ArgumentMatchers.eq(SOURCE_INSTANCE),
            org.mockito.ArgumentMatchers.contains("表单输入已消费"));

    awaitDelayClosureSettled();
    completeIssueProjections();
    refreshDelayFacts();
    refreshDelayFacts();

    org.mockito.Mockito.verify(orchestrator, org.mockito.Mockito.times(1))
        .requestSourceRound(
            org.mockito.ArgumentMatchers.eq(SOURCE_INSTANCE),
            org.mockito.ArgumentMatchers.contains("表单输入已消费"));
  }

  @Test
  void formDrivenRoundConvergesToIdleAndSameInputAddsNoRequest() {
    resumeDelayRounds();
    insertSourceIssue(93014L, 81014L, LocalDateTime.of(2026, 11, 1, 10, 0));
    insertResponseRecord(
        93014L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 11, 5, 10, 0), LocalDate.of(2026, 11, 15), "CC2026R4");

    rebuildAllIssueFacts();

    Map<String, Object> converged = awaitDelayClosureSettled();
    long requested = ((Number) converged.get("request_revision")).longValue();
    assertThat(requested).as("一次表单变化只请求一轮").isEqualTo(1L);
    assertThat(converged.get("stage")).as("轮次必须收敛到 IDLE").isEqualTo("IDLE");
    assertThat(((Number) converged.get("completed_revision")).longValue())
        .as("完成修订等于请求修订")
        .isEqualTo(requested);
    assertThat(((Number) converged.get("failure_count")).intValue())
        .as("稳定输入检查不得拒绝轮次")
        .isZero();
    assertThat(String.valueOf(converged.get("last_error")))
        .doesNotContain("REPEATABLE READ")
        .doesNotContain("稳定输入");

    // 发布 worker 在测试中关闭，先完成轮次留下的投影候选再发起同输入周期公开。
    completeIssueProjections();
    refreshDelayFacts();

    Map<String, Object> after = delayClosureProgress();
    assertThat(((Number) after.get("request_revision")).longValue())
        .as("同一输入周期公开不新增请求")
        .isEqualTo(requested);
    assertThat(((Number) after.get("completed_revision")).longValue()).isEqualTo(requested);
    assertThat(((Number) after.get("failure_count")).intValue()).isZero();
  }

  @Test
  void rolledBackPublicationDoesNotRegisterDelayRound() {
    resumeDelayRounds();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              applicationEventPublisher.publishEvent(
                  new FormFactPublicationEvent(SOURCE_INSTANCE, UUID.randomUUID()));
              status.setRollbackOnly();
            });

    assertThat(delayClosureProgress())
        .as("事实发布回滚后不登记也不唤醒延期轮次")
        .isEmpty();
  }

  private Map<String, Object> awaitDelayClosureSettled() {
    long deadline =
        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    Map<String, Object> row = delayClosureProgress();
    while (System.nanoTime() < deadline) {
      row = delayClosureProgress();
      if (row.isEmpty()) {
        try {
          Thread.sleep(50L);
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("等待延期轮次登记被中断", error);
        }
        continue;
      }
      long requested = ((Number) row.get("request_revision")).longValue();
      long completed = ((Number) row.get("completed_revision")).longValue();
      int failures = ((Number) row.get("failure_count")).intValue();
      if (requested > 0 && completed == requested && "IDLE".equals(row.get("stage"))) {
        return row;
      }
      if (failures > 0) {
        return row;
      }
      try {
        Thread.sleep(50L);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("等待延期轮次终态被中断", error);
      }
    }
    throw new AssertionError("延期轮次未在期限内收敛：" + delayClosureDiagnostics());
  }

  /**
   * 超时诊断：冻结轮次进度、租约与执行器现场。
   *
   * <p>仅在断言失败路径读取，成功用例不额外查询或抓取线程栈。
   */
  private String delayClosureDiagnostics() {
    StringBuilder text = new StringBuilder();
    try {
      List<Map<String, Object>> rows =
          jdbcTemplate.queryForList(
              "select stage, request_revision, processed_revision, completed_revision,"
                  + " failure_count, last_error, next_check_at, lease_owner, lease_until, updated_at"
                  + " from customer_issue_delay_closure_requests where source_instance = ?",
              SOURCE_INSTANCE);
      text.append("轮次进度=").append(rows.isEmpty() ? "无" : rows.get(0));
    } catch (RuntimeException error) {
      text.append("轮次进度读取失败=").append(error.getMessage());
    }
    text.append(", 执行器active=")
        .append(platformAsyncExecutor.getActiveCount())
        .append(", queue=")
        .append(platformAsyncExecutor.getThreadPoolExecutor().getQueue().size())
        .append(", poolSize=")
        .append(platformAsyncExecutor.getPoolSize());
    ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    for (ThreadInfo info : threads.dumpAllThreads(false, false)) {
      if (!info.getThreadName().startsWith(PlatformAsyncConfiguration.THREAD_NAME_PREFIX)) {
        continue;
      }
      text.append(", 线程=")
          .append(info.getThreadName())
          .append('(')
          .append(info.getThreadState())
          .append(')');
      StackTraceElement[] frames = info.getStackTrace();
      for (int index = 0; index < Math.min(3, frames.length); index++) {
        text.append(" <- ").append(frames[index]);
      }
    }
    return text.toString();
  }

  /** 等待平台异步轮次真实收尾，再执行需要独占域候选的同步重算。 */
  private void awaitPlatformTasksSettled() {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (platformExecutorIdle()) {
        return;
      }
      try {
        Thread.sleep(25L);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("等待平台异步任务收尾被中断", error);
      }
    }
    throw new IllegalStateException("平台异步任务未在期限内收尾");
  }

  private boolean platformExecutorIdle() {
    return platformAsyncExecutor.getActiveCount() == 0
        && platformAsyncExecutor.getThreadPoolExecutor().getQueue().isEmpty();
  }

  private Map<String, Object> delayClosureProgress() {
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "select stage, request_revision, processed_revision, completed_revision,"
                + " failure_count, last_error"
                + " from customer_issue_delay_closure_requests where source_instance = ?",
            SOURCE_INSTANCE);
    return rows.isEmpty() ? Map.of() : rows.get(0);
  }

  @Test
  void consumedEvidenceSettlementLocksScopeBeforeHead() throws Exception {
    insertSourceIssue(93013L, 81013L, LocalDateTime.of(2026, 11, 1, 10, 0));
    insertResponseRecord(
        93013L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 11, 5, 10, 0), LocalDate.of(2026, 11, 15), "CC2026R4");
    rebuildAllIssueFacts();
    factBuildService.refreshCustomerIssueDelayFactsForConfig(config());
    FactRevisionFixture.prepareRequiredProjections(
        jdbcTemplate,
        ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE),
        null);
    UUID candidateId =
        jdbcTemplate.queryForObject(
            """
            select p.id from read_publications p join read_publication_domains d on d.id = p.domain_id
             where d.fact_type = 'ISSUE' and p.state in ('PROJECTING', 'READY_TO_PUBLISH')
             order by p.id desc limit 1
            """,
            UUID.class);
    Map<String, Object> evidence =
        jdbcTemplate.queryForMap(
            "select head_id, scope_id from form_publication_captures"
                + " where publication_id = ? and status = 'CONSUMED' limit 1",
            candidateId);
    long headId = ((Number) evidence.get("head_id")).longValue();
    long scopeId = ((Number) evidence.get("scope_id")).longValue();
    org.springframework.transaction.support.TransactionTemplate transaction =
        new org.springframework.transaction.support.TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                jdbcTemplate.getDataSource()));
    java.util.concurrent.CompletableFuture<Void> settled =
        new java.util.concurrent.CompletableFuture<>();
    java.util.concurrent.CompletableFuture<Integer> workerPid =
        new java.util.concurrent.CompletableFuture<>();
    Thread worker =
        new Thread(
            () -> {
              try {
                transaction.executeWithoutResult(
                    status -> {
                      workerPid.complete(
                          jdbcTemplate.queryForObject("select pg_backend_pid()", Integer.class));
                      formHeadCaptureService.settleForPublication(candidateId);
                    });
                settled.complete(null);
              } catch (Throwable error) {
                settled.completeExceptionally(error);
              }
            },
            "consumed-evidence-settle");
    worker.start();
    int pid = workerPid.get(10, java.util.concurrent.TimeUnit.SECONDS);
    transaction.executeWithoutResult(
        status -> {
          jdbcTemplate.execute("set local lock_timeout = '3s'");
          jdbcTemplate.queryForObject(
              "select id from form_template_scopes where id = ? for update",
              Long.class,
              scopeId);
          awaitWorkerWaitingOnLock(pid, settled);
          jdbcTemplate.queryForObject(
              "select id from form_change_heads where id = ? for update",
              Long.class,
              headId);
        });
    settled.get(10, java.util.concurrent.TimeUnit.SECONDS);
  }

  private void awaitWorkerWaitingOnLock(
      int pid, java.util.concurrent.CompletableFuture<Void> settled) {
    for (int attempt = 0; attempt < 250; attempt++) {
      if (settled.isDone()) {
        try {
          settled.get();
          throw new IllegalStateException("结算线程未在锁上等待即已完成");
        } catch (java.util.concurrent.ExecutionException error) {
          throw new IllegalStateException("结算线程在锁探测前失败", error.getCause());
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("等待结算结果被中断", error);
        }
      }
      Long waiting =
          jdbcTemplate.queryForObject(
              "select count(*) from pg_stat_activity"
                  + " where pid = ? and wait_event_type = 'Lock'",
              Long.class,
              pid);
      if (waiting != null && waiting > 0) {
        return;
      }
      try {
        Thread.sleep(20L);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("等待锁探测被中断", error);
      }
    }
    throw new IllegalStateException(
        "未观察到结算线程在锁上等待：" + jdbcTemplate.queryForList(
            "select pid, state, query, wait_event_type, wait_event"
                + " from pg_stat_activity where pid = ?",
            pid));
  }

  @Test
  void otherWorkerLeasePreventsUnEvidencedPublication() {
    insertSourceIssue(93010L, 81010L, LocalDateTime.of(2026, 11, 1, 10, 0));
    insertResponseRecord(
        93010L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 11, 5, 10, 0), LocalDate.of(2026, 11, 15), "CC2026R4");
    long headId =
        jdbcTemplate.queryForObject(
            "select id from form_change_heads where source_instance = ? and template_key = ?"
                + " and resource_id = ?",
            Long.class,
            SOURCE_INSTANCE,
            FormTemplateSpecCatalog.ISSUE_RESPONSE,
            81010L);

    assertThat(
            formHeadCaptureService.claimHead(headId, "other-worker", LocalDateTime.now()))
        .as("他人持有 head 租约")
        .isPresent();

    assertThatThrownBy(this::rebuildAllIssueFacts)
        .isInstanceOf(com.data.collection.platform.common.exception.FormConflictException.class)
        .hasMessageContaining("其他消费者");
    assertThat(revisionCount(93010L))
        .as("无证候选不得公开任何事实")
        .isZero();
  }

  @Test
  void formWriteAfterInputReadPreventsSettlementAndKeepsFrozenValues() {
    insertSourceIssue(93011L, 81011L, LocalDateTime.of(2026, 11, 1, 10, 0));
    insertResponseRecord(
        93011L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 11, 5, 10, 0), LocalDate.of(2026, 11, 15), "CC2026R4");
    rebuildAllIssueFacts();
    long revisionsBefore = liveRevisionCount(93011L);
    List<Long> roots =
        jdbcTemplate.queryForList("select id from ods_gitlab_issues order by id", Long.class);
    ensureCertifiedIssueBaseline();

    factTargetPublicationService.publishWithinCandidate(
            new BypassPublicationRequest(
                ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE),
                "GITLAB",
                SEAL,
                0L,
                "a-read-b-write-test",
                null,
                Instant.now(),
                true),
            target -> {
              FactBuildResponse response =
                  factBuildService.rebuildIssueFactsByRootIds(target, roots);
              insertResponseRecord(
                  93011L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
                  LocalDateTime.of(2026, 11, 6, 10, 0), LocalDate.of(2026, 12, 1), "CC2026R5");
              return response;
            });
    FactRevisionFixture.prepareRequiredProjections(
        jdbcTemplate,
        ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE),
        null);
    UUID candidateId =
        jdbcTemplate.queryForObject(
            """
            select p.id from read_publications p join read_publication_domains d on d.id = p.domain_id
             where d.fact_type = 'ISSUE' and p.state in ('PROJECTING', 'READY_TO_PUBLISH')
             order by p.id desc limit 1
            """,
            UUID.class);

    assertThatThrownBy(
            () -> factTargetPublicationService.publishPreparedCandidate(candidateId, null))
        .as("A 读取后 B 写入不得按 A 的证据结算")
        .isInstanceOf(com.data.collection.platform.common.exception.FormConflictException.class);
    assertThat(liveFact(93011L).get("planned_resolution_at"))
        .as("失败候选保留上一完整版本的业务值")
        .isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(liveRevisionCount(93011L)).isEqualTo(revisionsBefore);
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
    resumeDelayRounds();
    insertSourceIssue(93106L, 81106L, LocalDateTime.of(2026, 11, 2, 10, 0));
    insertResponseRecord(
        93106L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        LocalDateTime.of(2026, 11, 3, 10, 0), LocalDate.of(2026, 10, 20), "CC2026R3");

    rebuildAllIssueFacts();
    awaitDelayClosureSettled();
    completeIssueProjections();
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
    resumeDelayRounds();
    insertSourceIssue(93105L, 81105L, LocalDateTime.of(2026, 11, 1, 10, 0));
    insertResponseNote(93105L, "2026.11.09", LocalDateTime.of(2026, 11, 2, 9, 0));
    insertCommentFixNote(93105L, LocalDateTime.of(2026, 11, 2, 10, 0));
    long responseId =
        insertResponseRecord(
            93105L, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
            LocalDateTime.of(2026, 11, 5, 10, 0), LocalDate.of(2026, 11, 15), "CC2026R4");
    long replyId = insertReplyRecord(93105L, LocalDateTime.of(2026, 11, 6, 10, 0), "u-jia");

    rebuildAllIssueFacts();
    awaitDelayClosureSettled();
    completeIssueProjections();

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

  /**
   * 表单到页面贯通：真实记录入口保存/改期后，客户问题记录、筛选、详情、Excel、原因与延期统计同步换版。
   *
   * <p>同一批数据保留旧评论计划 11-09 与一条 COMMENT 权威对照；表单权威下的页面只认表单响应计划，
   * 改期到 11-15 后延期标记与延期统计同步收敛，周期重算不得回退旧评论。
   */
  @Test
  void formRecordSaveAndEditDriveCustomerIssuePagesStatisticsAndExport() throws Exception {
    long formIssueId = 93301L;
    long formIid = 81301L;
    long commentIssueId = 93302L;
    long commentIid = 81302L;
    insertSourceMilestone(701L, "CC2026 R5");
    insertSourceIssue(formIssueId, formIid, LocalDateTime.of(2026, 11, 1, 10, 0));
    attachIssueMilestone(formIssueId, 701L);
    addIssueLabel(formIssueId, 801L, "P1");
    addIssueLabel(formIssueId, 802L, "模块：草图");
    insertResponseNote(formIssueId, "2026.11.09", LocalDateTime.of(2026, 11, 1, 12, 0));
    insertSourceIssue(commentIssueId, commentIid, LocalDateTime.of(2026, 10, 1, 10, 0));
    attachIssueMilestone(commentIssueId, 701L);
    insertResponseNote(commentIssueId, "2026.11.09", LocalDateTime.of(2026, 10, 2, 9, 0));
    registerFormAuthority(formIid, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId);
    registerFormAuthority(formIid, FormTemplateSpecCatalog.ISSUE_REPLY, replyScopeId);

    FormCreateResponse response = createResponseRecord(formIid, "2026-10-20", "CC2026R4");
    FormCreateResponse reply = createReplyRecord(formIid);
    assertThat(response.outcome()).isEqualTo("CREATED");
    assertThat(reply.outcome()).isEqualTo("CREATED");

    rebuildAllIssueFacts();
    completeIssueProjections();

    LocalDateTime formResponseAt =
        formRecordCreatedAt(
            Long.parseLong(response.record().id()),
            FormTemplateSpecCatalog.ISSUE_RESPONSE,
            responseScopeId);
    Map<String, Object> firstFact = liveFact(formIssueId);
    assertThat(firstFact.get("planned_resolution_at"))
        .isEqualTo(LocalDateTime.of(2026, 10, 20, 0, 0));
    assertThat(firstFact.get("fix_user")).isEqualTo("甲");
    assertThat(firstFact.get("reason_category")).isEqualTo("编码规范错误");
    assertThat(firstFact.get("research_template_time"))
        .as("首次响应时间读取表单记录，而不是旧评论")
        .isEqualTo(formResponseAt);
    assertThat(firstFact.get("is_resolve_delayed")).isEqualTo(true);

    awaitPlatformTasksSettled();
    FactReadSelection beforeEdit = publishedIssueSelection();
    CustomerIssueRecordRowResponse overdueRow = ccProductRow(beforeEdit, formIid);
    assertThat(overdueRow.plannedResolutionAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 0, 0));
    assertThat(overdueRow.plannedResolutionText()).isEqualTo("2026-10-20");
    assertThat(overdueRow.plannedMergeVersionBranch()).isEqualTo("CC2026R4");
    assertThat(overdueRow.fixUser()).isEqualTo("甲");
    assertThat(overdueRow.reasonCategory()).isEqualTo("编码规范错误");
    assertThat(overdueRow.resolveDelayed()).isTrue();
    assertThat(ccProductRow(beforeEdit, commentIid).plannedResolutionAt())
        .as("COMMENT 对照保持旧评论计划")
        .isEqualTo(LocalDateTime.of(2026, 11, 9, 0, 0));
    assertThat(causeCount(beforeEdit, "standard_error")).isEqualTo(1L);
    assertThat(delayCount(beforeEdit, "fix_delay_sum")).isEqualTo(1L);
    assertThat(efficiencyHours(beforeEdit)).isEqualTo(expectedResponseHours(formIssueId, commentIssueId));

    FormRecordView edited =
        updateResponseRecord(formIid, response.record(), "2026-11-15", "CC2026R4");
    assertThat(edited.recordVersion()).isGreaterThan(response.record().recordVersion());
    awaitPlatformTasksSettled();
    refreshDelayFacts();

    Map<String, Object> refreshed = liveFact(formIssueId);
    assertThat(refreshed.get("planned_resolution_at"))
        .isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(refreshed.get("is_resolve_delayed")).isEqualTo(false);
    assertThat(refreshed.get("fix_user")).isEqualTo("甲");
    assertThat(refreshed.get("reason_category")).isEqualTo("编码规范错误");
    assertThat(liveFact(commentIssueId).get("planned_resolution_at"))
        .as("COMMENT 对照不随表单改期变化")
        .isEqualTo(LocalDateTime.of(2026, 11, 9, 0, 0));

    FactReadSelection afterEdit = publishedIssueSelection();
    CustomerIssueRecordRowResponse currentRow = ccProductRow(afterEdit, formIid);
    assertThat(currentRow.plannedResolutionAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(currentRow.plannedResolutionText()).isEqualTo("2026-11-15");
    assertThat(currentRow.plannedMergeVersionBranch()).isEqualTo("CC2026R4");
    assertThat(currentRow.fixUser()).isEqualTo("甲");
    assertThat(currentRow.reasonCategory()).isEqualTo("编码规范错误");
    assertThat(currentRow.resolveDelayed()).isFalse();
    assertThat(delayCount(afterEdit, "fix_delay_sum"))
        .as("改期后延期统计减少 1")
        .isZero();
    assertThat(causeCount(afterEdit, "standard_error")).isEqualTo(1L);
    assertThat(efficiencyHours(afterEdit)).isEqualTo(expectedResponseHours(formIssueId, commentIssueId));

    assertThat(filteredIids(afterEdit, new CustomerIssueRecordFilters(
            null, null, null, null, null, null, null,
            new CustomerIssueRecordFilters.CcProductFilters(
                null, "2026-11-15", "2026-11-15", null, null, null))))
        .contains(formIid)
        .doesNotContain(commentIid);
    assertThat(filteredIids(afterEdit, new CustomerIssueRecordFilters(
            null, null, null, null, null, "甲", null,
            CustomerIssueRecordFilters.CcProductFilters.empty())))
        .contains(formIid);
    assertThat(filteredIids(afterEdit, new CustomerIssueRecordFilters(
            "编码规范错误", null, null, null, null, null, null,
            CustomerIssueRecordFilters.CcProductFilters.empty())))
        .contains(formIid)
        .doesNotContain(commentIid);
    assertThat(filteredIids(afterEdit, new CustomerIssueRecordFilters(
            null, null, null, null, null, null, null,
            new CustomerIssueRecordFilters.CcProductFilters(
                null, null, null, "CC2026R4", null, null))))
        .contains(formIid);
    CustomerIssueRecordRowResponse detail = customerIssueRecordService.forRead(afterEdit)
        .findRecord(SOURCE_INSTANCE, PROJECT_ID, formIssueId);
    assertThat(detail.plannedResolutionAt()).isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(detail.fixUser()).isEqualTo("甲");
    assertThat(detail.reasonCategory()).isEqualTo("编码规范错误");

    List<String> exportRow = exportedIidRow(afterEdit, formIid);
    assertThat(exportRow.get(16)).isEqualTo("甲");
    assertThat(exportRow.get(17)).isEqualTo("2026-11-15");
    assertThat(exportRow.get(18)).isEqualTo("CC2026R4");

    awaitPlatformTasksSettled();
    assertThat(liveFact(formIssueId).get("planned_resolution_at"))
        .as("周期重算仍保持表单计划")
        .isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
  }

  /**
   * 作废真实保存的响应与回复后，页面、导出与统计同步清空，不得回退旧评论计划。
   */
  @Test
  void voidingFormResponseAndReplyClearsCustomerIssuePagesWithoutCommentFallback() throws Exception {
    long formIssueId = 93311L;
    long formIid = 81311L;
    long commentIssueId = 93312L;
    long commentIid = 81312L;
    insertSourceMilestone(711L, "CC2026 R6");
    insertSourceIssue(formIssueId, formIid, LocalDateTime.of(2026, 11, 1, 10, 0));
    attachIssueMilestone(formIssueId, 711L);
    addIssueLabel(formIssueId, 811L, "P1");
    addIssueLabel(formIssueId, 812L, "模块：工程图");
    insertResponseNote(formIssueId, "2026.11.09", LocalDateTime.of(2026, 11, 1, 12, 0));
    insertSourceIssue(commentIssueId, commentIid, LocalDateTime.of(2026, 10, 1, 10, 0));
    attachIssueMilestone(commentIssueId, 711L);
    insertResponseNote(commentIssueId, "2026.11.09", LocalDateTime.of(2026, 10, 2, 9, 0));
    registerFormAuthority(formIid, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId);
    registerFormAuthority(formIid, FormTemplateSpecCatalog.ISSUE_REPLY, replyScopeId);

    FormCreateResponse response = createResponseRecord(formIid, "2026-11-15", "CC2026R4");
    FormCreateResponse reply = createReplyRecord(formIid);
    rebuildAllIssueFacts();
    completeIssueProjections();

    awaitPlatformTasksSettled();
    FactReadSelection adopted = publishedIssueSelection();
    assertThat(ccProductRow(adopted, formIid).plannedResolutionAt())
        .isEqualTo(LocalDateTime.of(2026, 11, 15, 0, 0));
    assertThat(causeCount(adopted, "standard_error")).isEqualTo(1L);

    voidFormRecord(formIid, FormTemplateSpecCatalog.ISSUE_RESPONSE, responseScopeId,
        response.record(), responseActor);
    voidFormRecord(formIid, FormTemplateSpecCatalog.ISSUE_REPLY, replyScopeId,
        reply.record(), replyActor);
    awaitPlatformTasksSettled();
    refreshDelayFacts();

    Map<String, Object> voided = liveFact(formIssueId);
    assertThat(voided.get("planned_resolution_at")).isNull();
    assertThat(voided.get("research_template_time")).isNull();
    assertThat(voided.get("fix_user")).isNull();
    assertThat(voided.get("reason_category")).isEqualTo("");
    assertThat(voided.get("planned_resolution_text")).isEqualTo("");

    FactReadSelection afterVoid = publishedIssueSelection();
    CustomerIssueRecordRowResponse voidedRow = ccProductRow(afterVoid, formIid);
    assertThat(voidedRow.plannedResolutionAt()).isNull();
    assertThat(voidedRow.plannedResolutionText()).isEmpty();
    assertThat(voidedRow.fixUser()).isEmpty();
    assertThat(voidedRow.reasonCategory()).isEmpty();
    assertThat(causeCount(afterVoid, "standard_error")).isZero();
    assertThat(delayCount(afterVoid, "fix_delay_sum")).isZero();
    assertThat(efficiencyHours(afterVoid))
        .as("作废后样本不再贡献响应周期")
        .isEqualTo(expectedResponseHours(commentIssueId));

    assertThat(filteredIids(afterVoid, new CustomerIssueRecordFilters(
            null, null, null, null, null, null, null,
            new CustomerIssueRecordFilters.CcProductFilters(
                null, "2026-11-09", "2026-11-09", null, null, null))))
        .as("不回落旧评论计划，但 COMMENT 对照仍按旧评论出现")
        .contains(commentIid)
        .doesNotContain(formIid);
    assertThat(ccProductRow(afterVoid, commentIid).plannedResolutionAt())
        .isEqualTo(LocalDateTime.of(2026, 11, 9, 0, 0));

    List<String> exportRow = exportedIidRow(afterVoid, formIid);
    assertThat(exportRow.get(16)).isEmpty();
    assertThat(exportRow.get(17)).isEmpty();
    assertThat(exportRow.get(18)).isEmpty();
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

  private void insertSourceMilestone(long milestoneId, String title) {
    jdbcTemplate.update(
        "insert into ods_gitlab_milestones(id, title, project_id, mirror_deleted)"
            + " values (?, ?, ?, false)",
        milestoneId,
        title,
        PROJECT_ID);
  }

  private void attachIssueMilestone(long issueId, long milestoneId) {
    jdbcTemplate.update(
        "update ods_gitlab_issues set milestone_id = ? where id = ?", milestoneId, issueId);
  }

  private void addIssueLabel(long issueId, long labelId, String title) {
    jdbcTemplate.update(
        "insert into ods_gitlab_labels(id, title, mirror_deleted) values (?, ?, false)",
        labelId,
        title);
    jdbcTemplate.update(
        """
        insert into ods_gitlab_label_links(id, label_id, target_id, target_type, mirror_deleted)
        values (?, ?, ?, 'Issue', false)
        """,
        labelId + 9000L,
        labelId,
        issueId);
  }

  /** 复用既有夹具登记持久权威；真实记录入口只负责写入业务值。 */
  private void registerFormAuthority(long iid, String templateKey, long scopeId) {
    jdbcTemplate.update(
        """
        insert into form_resource_authorities(
            source_config_id, source_instance, canonical_gitlab_base_url, project_id,
            resource_type, resource_id, template_key, scope_id, authority, authority_revision,
            effective_at, created_at, updated_at)
        select s.source_config_id, s.source_instance, s.canonical_gitlab_base_url, ?, 'issue', ?,
               ?, s.id, 'FORM', 1, current_timestamp, current_timestamp, current_timestamp
          from form_template_scopes s where s.id = ?
        on conflict (source_config_id, project_id, resource_type, resource_id, template_key)
        do nothing
        """,
        PROJECT_ID,
        iid,
        templateKey,
        scopeId);
  }

  /** 真实写入入口会核验生效范围的来源配置；配置在标记标签事件历史前建立。 */
  private void insertFormSourceConfig() {
    jdbcTemplate.update(
        """
        insert into gitlab_sync_configs(
            id, name, db_name, db_username, db_password, web_base_url)
        values (?, 'form-page-source', 'qaflex', 'qaflex', 'secret', ?)
        """,
        SOURCE_CONFIG_ID,
        CANONICAL_URL);
  }

  private FormCreateResponse createResponseRecord(long iid, String plannedDate, String branch) {
    return formRecordService.create(
        new FormCreateRequest(
            CANONICAL_URL,
            PROJECT_ID,
            "issue",
            Long.toString(iid),
            FormTemplateSpecCatalog.ISSUE_RESPONSE,
            Long.toString(responseScopeId),
            Long.toString(currentVersionId(responseScopeId)),
            UUID.randomUUID().toString(),
            false,
            formFields(responseFields(plannedDate, branch))),
        responseActor,
        "127.0.0.1",
        "JUnit");
  }

  private FormCreateResponse createReplyRecord(long iid) {
    return formRecordService.create(
        new FormCreateRequest(
            CANONICAL_URL,
            PROJECT_ID,
            "issue",
            Long.toString(iid),
            FormTemplateSpecCatalog.ISSUE_REPLY,
            Long.toString(replyScopeId),
            Long.toString(currentVersionId(replyScopeId)),
            UUID.randomUUID().toString(),
            false,
            formFields(replyFields())),
        replyActor,
        "127.0.0.1",
        "JUnit");
  }

  private FormRecordView updateResponseRecord(
      long iid, FormRecordView current, String plannedDate, String branch) {
    long versionId = currentVersionId(responseScopeId);
    return formRecordService.update(
        current.id(),
        new FormUpdateRequest(
            CANONICAL_URL,
            PROJECT_ID,
            "issue",
            Long.toString(iid),
            FormTemplateSpecCatalog.ISSUE_RESPONSE,
            Long.toString(responseScopeId),
            Long.toString(versionId),
            Long.toString(versionId),
            current.recordVersion(),
            formFields(responseFields(plannedDate, branch))),
        responseActor,
        "127.0.0.1",
        "JUnit");
  }

  private FormRecordView voidFormRecord(
      long iid,
      String templateKey,
      long scopeId,
      FormRecordView current,
      FormActorContext actor) {
    long versionId = currentVersionId(scopeId);
    return formRecordService.voidRecord(
        current.id(),
        new FormVoidRequest(
            CANONICAL_URL,
            PROJECT_ID,
            "issue",
            Long.toString(iid),
            templateKey,
            Long.toString(scopeId),
            Long.toString(versionId),
            Long.toString(versionId),
            current.recordVersion(),
            true,
            "作废"),
        actor,
        "127.0.0.1",
        "JUnit");
  }

  private ObjectNode responseFields(String plannedDate, String branch) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("problemType", "DEFECT");
    node.put("problemCause", "部署后响应超时");
    node.put("modificationPlan", "调整超时阈值");
    node.putNull("ownerConfirmation");
    node.put("plannedResolutionDate", plannedDate);
    node.put("plannedMergeVersionBranch", branch);
    node.putNull("supplementNote");
    return node;
  }

  private ObjectNode replyFields() {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("fixStatus", "RESOLVED");
    node.set("causeKeys", array("standard_error"));
    node.put("specificReason", "编码规范错误");
    node.put("modificationPlan", "补充校验");
    node.put("causedByOtherDefect", false);
    node.set("affectedKinds", array("NONE"));
    node.putNull("knownAffectedFunctions");
    node.putNull("newAffectedFunctions");
    node.put("impactTestStatus", "NOT_APPLICABLE");
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

  private Map<String, JsonNode> formFields(ObjectNode node) {
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
    return fields;
  }

  private LocalDateTime formRecordCreatedAt(long recordId, String templateKey, long scopeId) {
    long versionId = currentVersionId(scopeId);
    Timestamp created =
        jdbcTemplate.queryForObject(
            "select created_at from " + recordTable(versionId, templateKey, scopeId) + " where id = ?",
            Timestamp.class,
            recordId);
    return created == null ? null : created.toLocalDateTime();
  }

  /** 页面读取必须绑定链路真实发布出来的版本，而不是另造发布身份。 */
  private FactReadSelection publishedIssueSelection() {
    List<FactReadQueryContext> contexts =
        jdbcTemplate.query(
            """
            select d.source_instance, p.epoch_id, p.sequence_no, p.id, p.data_as_of
              from read_publication_domains d
              join read_publications p on p.id = d.current_publication_id
             where d.fact_type = 'ISSUE' and p.state = 'PUBLISHED'
             order by d.source_instance
            """,
            (rs, row) ->
                new PublishedReadContext(
                    rs.getString(1),
                    PublicationFactTable.ISSUE,
                    rs.getObject(2, UUID.class),
                    rs.getLong(3),
                    rs.getObject(4, UUID.class),
                    rs.getTimestamp(5).toInstant()));
    assertThat(contexts).as("页面读取必须绑定真实已发布版本").isNotEmpty();
    return new FactReadSelection(contexts, businessDateProvider.businessDate());
  }

  private CustomerIssueRecordQueryRequest ccProductQuery(CustomerIssueRecordFilters filters) {
    return new CustomerIssueRecordQueryRequest(
        "cc-product",
        new IssueFactRecordListRequest(
            PROJECT_ID,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            1,
            50,
            "updatedAt",
            "desc"),
        filters,
        null);
  }

  private List<Long> filteredIids(
      FactReadSelection selection, CustomerIssueRecordFilters filters) {
    return customerIssueRecordService.forRead(selection)
        .listRecords(ccProductQuery(filters)).records().stream()
        .map(row -> row.issueIid() == null ? null : row.issueIid().longValue())
        .toList();
  }

  private CustomerIssueRecordRowResponse ccProductRow(FactReadSelection selection, long iid) {
    return customerIssueRecordService.forRead(selection)
        .listRecords(ccProductQuery(CustomerIssueRecordFilters.empty())).records().stream()
        .filter(row -> row.issueIid() != null && row.issueIid().longValue() == iid)
        .findFirst()
        .orElseThrow(() -> new AssertionError("客户问题列表缺少议题 " + iid));
  }

  private List<String> exportedIidRow(FactReadSelection selection, long iid) throws Exception {
    byte[] workbook =
        customerIssueRecordService.forRead(selection)
            .exportRecordsWorkbook(ccProductQuery(CustomerIssueRecordFilters.empty()));
    try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(workbook))) {
      var sheet = book.getSheet("CCProduct议题查询结果");
      for (int index = 1; index <= sheet.getLastRowNum(); index++) {
        var row = sheet.getRow(index);
        if (row == null || row.getCell(0) == null) {
          continue;
        }
        if (("#" + iid).equals(row.getCell(0).getStringCellValue())) {
          List<String> values = new ArrayList<>();
          for (int column = 0; column < row.getLastCellNum(); column++) {
            values.add(row.getCell(column) == null ? "" : row.getCell(column).getStringCellValue());
          }
          return values;
        }
      }
    }
    throw new AssertionError("导出文件缺少议题 " + iid);
  }

  private long causeCount(FactReadSelection selection, String metricKey) {
    StatisticBoardResponse board = defectCauseBoardService.forRead(selection).loadBoard(Map.of());
    return statisticCell(statisticRow(board, "__total__"), metricKey).numericValue();
  }

  private long delayCount(FactReadSelection selection, String metricKey) {
    StatisticBoardResponse board =
        delayIssuesBoardService.forRead(selection).loadBoard(Map.of());
    return statisticCell(statisticRow(board, "__total__"), metricKey).numericValue();
  }

  private long efficiencyHours(FactReadSelection selection) {
    StatisticBoardResponse board =
        responseEfficiencyBoardService.forRead(selection).loadBoard(Map.of());
    return statisticCell(statisticRow(board, "__total__"), "response_cycle_hours").numericValue();
  }

  private long expectedResponseHours(long... issueIds) {
    List<Long> hours = new ArrayList<>();
    for (long issueId : issueIds) {
      Map<String, Object> fact = liveFact(issueId);
      LocalDateTime research = (LocalDateTime) fact.get("research_template_time");
      if (research != null) {
        hours.add(
            CustomerIssueEfficiencyRules.responseCycleHours(
                (LocalDateTime) fact.get("created_at_source"), research));
      }
    }
    return CustomerIssueEfficiencyRules.averageResponseHours(hours);
  }

  private StatisticRowData statisticRow(StatisticBoardResponse board, String rowKey) {
    return board.rows().stream()
        .filter(row -> rowKey.equals(row.rowKey()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("看板缺少行 " + rowKey));
  }

  private StatisticCellData statisticCell(StatisticRowData row, String columnKey) {
    return row.cells().stream()
        .filter(cell -> columnKey.equals(cell.columnKey()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("看板缺少列 " + columnKey));
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

  private void completeIssueProjections() {
    FactRevisionFixture.completeRequiredProjections(
        jdbcTemplate,
        factTargetPublicationService,
        ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE),
        null);
  }

  private void refreshDelayFacts() {
    factBuildService.refreshCustomerIssueDelayFactsForConfig(config());
    completeIssueProjections();
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

  private long liveRevisionCount(long issueId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from issue_fact where source_instance = ? and issue_id = ?"
            + LIVE_REVISION,
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
    jdbcTemplate.update("delete from form_record_audit_logs");
    jdbcTemplate.update("delete from form_resource_authorities");
    jdbcTemplate.update("delete from form_directory_event_cursors");
    jdbcTemplate.update("delete from form_directory_events");
    jdbcTemplate.update(
        "update form_template_scopes set status = 'DRAFT', current_version_id = null");
    jdbcTemplate.update("delete from form_template_versions");
    jdbcTemplate.update("delete from form_template_scopes");
  }
}
