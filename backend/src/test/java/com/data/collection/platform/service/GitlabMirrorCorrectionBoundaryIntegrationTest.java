package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceCursorStrategy;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.SourceTableSchema;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunTableTask;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.mapper.SyncRunTableTaskMapper;
import com.data.collection.platform.service.sync.SourceTableReader;
import com.data.collection.platform.service.sync.SyncRunCancellationService;
import com.data.collection.platform.service.sync.SyncRunCancellationService.SyncRunCancellationResult;
import com.data.collection.platform.service.sync.SyncRunTableTaskExecutor;
import com.data.collection.platform.service.sync.SyncRunWorkerService;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * R1 纠正写入提交边界回归。
 *
 * <p>测试在真实 PostgreSQL、真实表任务执行器与事务代理上运行，不使用 mock 写入或手写替代协议 SQL。
 * 旧写入器造成的错误镜像由可信旧数据夹具播种：物理 timestamptz 列整体比来源早 8 小时，
 * 与 696c 真实旧链演练的错位形态一致；每个场景都先断言待纠正行数大于 0。
 *
 * <p>边界通过两种确定性手段命中：读取完成后由测试 {@link SourceTableReader} 子类阻塞；
 * 纠正 DML 内部由数据库触发器记录进入并等待会话级 advisory lock。取消使用真实
 * {@link SyncRunCancellationService}，租约接管直接更新真实任务租约行，恢复统一重跑全量补偿。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = {
      "platform.background-jobs.enabled=false",
      "platform.gitlab-mirror.scheduler-enabled=false",
      "platform.gitlab-mirror.delete-reconciliation-enabled=false",
      "platform.gitlab-mirror.customer-issue-delay-pre-writeback-sync-enabled=false",
      "platform.gitlab-mirror.customer-issue-delay-writeback-worker-enabled=false",
      "platform.gitlab-mirror.code-review-metric-enrichment-enabled=false",
      "platform.review-data.search-index-backfill-enabled=false",
      "platform.auth.provider=local",
      "platform.auth.secure-config-required=false",
      "platform.gitlab-mirror.table-task-lease-seconds=60",
      "platform.gitlab-mirror.external-query-timeout-seconds=5",
      "logging.level.com.data.collection.platform.service.GitlabMirrorCorrectionBoundaryIntegrationTest=INFO",
      "logging.level.com.data.collection.platform.service.sync=INFO"
    })
@Import(GitlabMirrorCorrectionBoundaryIntegrationTest.BarrierReaderConfiguration.class)
class GitlabMirrorCorrectionBoundaryIntegrationTest {
  private static final Logger log =
      LoggerFactory.getLogger(GitlabMirrorCorrectionBoundaryIntegrationTest.class);

  private static final long PROJECT_ID = 325L;
  private static final String SOURCE_TABLE = "issues";
  private static final String MIRROR_TABLE = "ods_gitlab_issues";
  private static final String SOURCE_INSTANCE = "default";
  private static final Duration OLD_WRITER_SHIFT = Duration.ofHours(8);
  private static final LocalDateTime BASELINE_WATERMARK =
      LocalDateTime.of(2026, 8, 31, 0, 0);
  private static final Instant TAKEOVER_SOURCE_UPDATED_AT =
      Instant.parse("2026-09-01T13:00:00Z");
  private static final long WRITE_GATE_KEY = 732194650L;
  private static final List<SourceRow> SOURCE_ROWS =
      List.of(
          new SourceRow(
              101L,
              11L,
              Instant.parse("2026-09-01T08:00:00Z"),
              Instant.parse("2026-09-01T09:00:00Z"),
              Instant.parse("2026-09-02T10:00:00Z")),
          new SourceRow(
              102L,
              12L,
              Instant.parse("2026-09-01T08:10:00Z"),
              Instant.parse("2026-09-01T10:00:00Z"),
              null),
          new SourceRow(
              103L,
              13L,
              Instant.parse("2026-09-01T08:20:00Z"),
              Instant.parse("2026-09-01T11:00:00Z"),
              Instant.parse("2026-09-03T12:00:00Z")),
          new SourceRow(
              104L,
              14L,
              Instant.parse("2026-09-01T08:30:00Z"),
              Instant.parse("2026-09-01T12:00:00Z"),
              null));

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  @DynamicPropertySource
  static void registerIsolatedDatabase(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.schemas", () -> "public");
    registry.add("spring.flyway.default-schema", () -> "public");
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSourceProperties dataSourceProperties;
  @Autowired private GitlabConfigService configService;
  @Autowired private GitlabMirrorSchemaService mirrorSchemaService;
  @Autowired private GitlabMirrorTableStorageService storageService;
  @Autowired private SyncRunWorkerService runWorkerService;
  @Autowired private SyncRunTableTaskExecutor taskExecutor;
  @Autowired private SyncRunCancellationService cancellationService;
  @Autowired private SyncRunMapper syncRunMapper;
  @Autowired private SyncRunTableTaskMapper taskMapper;

  private GitlabSyncConfig config;
  private long stateId;

  @BeforeEach
  void setUp() {
    GitlabIssueMirrorFixture.ensureSchema(jdbcTemplate);
    cleanPlatformState();
    dropMirrorAndSource();
    CorrectionReadGate.disarm();
    createSourceTable();
    config = configService.saveConfig(sourceConfig());
    mirrorSchemaService.prepareMirrorTable(config, tableOption());
    stateId = insertTableState();
    insertSourceRows();
    insertOldWriterOdsRows();
    assertThat(pendingCorrectionRows())
        .as("可信旧数据夹具必须形成真实待纠正差异")
        .isEqualTo(SOURCE_ROWS.size());
  }

  @AfterEach
  void tearDown() {
    CorrectionReadGate.disarm();
    CorrectionReadGate.release();
    dropWriteGate();
    dropFailActiveWriteFunction();
    cleanPlatformState();
    dropMirrorAndSource();
  }

  @Test
  void cancelledAfterReadRejectsPageWithoutWritingOrAdvancingState() throws Exception {
    CorrectionReadGate.arm();
    long runId = insertCompensationRun();
    long taskId = insertCorrectionTask(runId, 2);
    ExecutorService pool = newWorkerPool("r1-cancel-after-read");
    Future<?> future =
        pool.submit(() -> runWorkerService.executeRun(syncRunMapper.selectById(runId)));
    try {
      assertThat(CorrectionReadGate.awaitRead())
          .as("纠正扫描必须在期限内完成读取并停在提交前屏障")
          .isTrue();
      String owner = taskOwner(taskId);
      log.info(
          "[R1] scenario=cancel-after-read boundary=read-completed-before-commit "
              + "runId={} taskId={} owner={} pendingRows={} runStatusBeforeCancel={}",
          runId,
          taskId,
          owner,
          pendingCorrectionRows(),
          runStatus(runId));

      SyncRunCancellationResult result =
          cancellationService.requestCancel(config.getId(), "r1-test", "读取后、提交前取消");
      log.info(
          "[R1] scenario=cancel-after-read cancelAccepted={} cancelStatus={} message={}",
          result.accepted(),
          result.status(),
          result.message());
      assertThat(result.accepted()).isTrue();
      assertThat(result.status()).isEqualTo(SyncRunStatus.CANCELLING);
      assertThat(pendingCorrectionRows()).isEqualTo(SOURCE_ROWS.size());
      assertThat(correctedRowIds()).isEmpty();
    } finally {
      CorrectionReadGate.release();
      join(future, pool);
    }

    assertThat(runStatus(runId)).isEqualTo("CANCELLED");
    assertThat(taskStatus(taskId)).isEqualTo("CANCELLED");
    assertThat(taskRowsScanned(taskId)).isZero();
    assertThat(taskCursorPk(taskId)).isNull();
    assertThat(tasksOfRun(runId)).hasSize(1);
    assertThat(correctedRowIds()).isEmpty();
    assertThat(pendingCorrectionRows()).isEqualTo(SOURCE_ROWS.size());
    assertThat(stateWatermark()).isEqualTo(BASELINE_WATERMARK);
    assertThat(factRefreshRunCount()).isZero();
    assertThat(factTargetCount(runId)).isZero();
    assertThat(publicationReadyCount()).isZero();
    logTaskAndOdsSummary("cancel-after-read", runId, taskId);

    long recoveryRunId = runFullCompensationToSuccess(10);
    assertThat(correctedRowIds()).containsExactly(101L, 102L, 103L, 104L);
    assertThat(pendingCorrectionRows()).isZero();
    assertThat(runStatus(recoveryRunId)).isEqualTo("SUCCESS");
    assertIdempotentSecondCompensation();
  }

  @Test
  void leaseTakeoverAfterReadRejectsOldWorkerAndKeepsNewOwnerResult() throws Exception {
    CorrectionReadGate.arm();
    long runId = insertCompensationRun();
    long taskId = insertCorrectionTask(runId, 10);
    ExecutorService pool = newWorkerPool("r1-lease-takeover");
    Future<?> future =
        pool.submit(() -> runWorkerService.executeRun(syncRunMapper.selectById(runId)));
    try {
      assertThat(CorrectionReadGate.awaitRead())
          .as("旧 worker 必须在期限内完成读取并停在提交前屏障")
          .isTrue();
      String oldOwner = taskOwner(taskId);
      int takeover =
          jdbcTemplate.update(
              """
              update sync_run_table_tasks
                 set lease_owner = 'r1-takeover-owner',
                     lease_until = current_timestamp + interval '1 hour',
                     updated_at = current_timestamp
               where id = ? and status = 'RUNNING' and lease_owner = ?
              """,
              taskId,
              oldOwner);
      assertThat(takeover)
          .as("另一连接必须成功使旧租约失效并接管任务")
          .isOne();
      // 旧 worker 已读取旧值并被屏障拦住；来源随后前进。若旧 worker 仍能提交，
      // 会把 ODS 写回旧值，下面的最终断言即可观测到这个陈旧覆盖。
      jdbcTemplate.update(
          "update public.issues set updated_at = ? where id = ?",
          toOffset(TAKEOVER_SOURCE_UPDATED_AT),
          101L);
      log.info(
          "[R1] scenario=lease-takeover boundary=read-completed-before-commit "
              + "runId={} taskId={} oldOwner={} newOwner=r1-takeover-owner "
              + "sourceRow101AdvancedTo={}",
          runId,
          taskId,
          oldOwner,
          TAKEOVER_SOURCE_UPDATED_AT);

      SyncRunTableTask takeoverTask = taskMapper.selectById(taskId);
      taskExecutor.executeTask(takeoverTask);
      assertThat(taskStatus(taskId)).isEqualTo("SUCCESS");
      assertThat(correctedRowIds()).containsExactly(101L, 102L, 103L, 104L);
      assertThat(odsUpdatedAtUtc(101L))
          .isEqualTo(LocalDateTime.ofInstant(TAKEOVER_SOURCE_UPDATED_AT, ZoneOffset.UTC));
      String cursorAfterTakeover = taskCursorPk(taskId);
      assertThat(cursorAfterTakeover).isNullOrEmpty();
      long scannedAfterTakeover = taskRowsScanned(taskId);
      assertThat(scannedAfterTakeover).isEqualTo(4L);
      assertThat(pendingCorrectionRows()).isZero();
      logTaskAndOdsSummary("lease-takeover-new-owner-committed", runId, taskId);
    } finally {
      CorrectionReadGate.release();
      join(future, pool);
    }

    assertThat(taskStatus(taskId)).isEqualTo("SUCCESS");
    assertThat(taskCursorPk(taskId)).isNullOrEmpty();
    assertThat(taskRowsScanned(taskId)).isEqualTo(4L);
    assertThat(correctedRowIds()).containsExactly(101L, 102L, 103L, 104L);
    assertThat(odsUpdatedAtUtc(101L))
        .as("旧 worker 放行后不得用读取时的陈旧值覆盖新 owner 的纠正结果")
        .isEqualTo(LocalDateTime.ofInstant(TAKEOVER_SOURCE_UPDATED_AT, ZoneOffset.UTC));
    assertThat(pendingCorrectionRows()).isZero();
    assertThat(runStatus(runId)).isEqualTo("SUCCESS");
    assertThat(taskOwner(taskId)).isNull();
    assertThat(stateWatermark()).isEqualTo(BASELINE_WATERMARK);
    logTaskAndOdsSummary("lease-takeover-old-worker-released", runId, taskId);
    assertIdempotentSecondCompensation();
  }

  @Test
  void cancellationDuringCorrectionWriteCommitsLegalPageAndRejectsContinuation() throws Exception {
    long runId = insertCompensationRun();
    long taskId = insertCorrectionTask(runId, 2);
    createAdvisoryWriteGate();
    ExecutorService pool = newWorkerPool("r1-cancel-during-write");
    Future<?> future =
        pool.submit(() -> runWorkerService.executeRun(syncRunMapper.selectById(runId)));
    Connection lockConnection = acquireSessionAdvisoryLock();
    try {
      waitForCorrectionWriteEntered();
      String owner = taskOwner(taskId);
      List<Map<String, Object>> lockHolders = advisoryLockHolders();
      log.info(
          "[R1] scenario=cancel-during-write boundary=inside-correction-dml "
              + "runId={} taskId={} owner={} pendingRows={} advisoryLocks={} "
              + "releaseOrder=cancel-then-unlock",
          runId,
          taskId,
          owner,
          pendingCorrectionRows(),
          lockHolders);

      SyncRunCancellationResult result =
          cancellationService.requestCancel(config.getId(), "r1-test", "纠正写入提交中取消");
      log.info(
          "[R1] scenario=cancel-during-write cancelAccepted={} cancelStatus={} message={} "
              + "correctedRowsAtCancel={}",
          result.accepted(),
          result.status(),
          result.message(),
          correctedRowIds());
      assertThat(result.accepted()).isTrue();
      assertThat(result.status()).isEqualTo(SyncRunStatus.CANCELLING);
    } finally {
      releaseSessionAdvisoryLock(lockConnection);
      join(future, pool);
    }

    assertThat(runStatus(runId)).isEqualTo("CANCELLED");
    List<TaskSnapshot> tasks = tasksOfRun(runId);
    assertThat(tasks).hasSize(2);
    TaskSnapshot committedPage = tasks.get(0);
    TaskSnapshot rejectedContinuation = tasks.get(1);
    assertThat(committedPage.stage()).isEqualTo("SCAN");
    assertThat(committedPage.status()).isEqualTo("SUCCESS");
    assertThat(committedPage.scanned()).isEqualTo(2L);
    assertThat(committedPage.cursorPk()).isNotBlank();
    assertThat(rejectedContinuation.stage()).isEqualTo("SCAN");
    assertThat(rejectedContinuation.status()).isEqualTo("CANCELLED");
    assertThat(rejectedContinuation.scanned()).isZero();
    assertThat(rejectedContinuation.cursorPk()).isEqualTo(committedPage.cursorPk());
    assertThat(correctedRowIds()).containsExactly(101L, 102L);
    assertThat(pendingCorrectionRows()).isEqualTo(2);
    assertThat(stateWatermark()).isEqualTo(BASELINE_WATERMARK);
    assertThat(factRefreshRunCount()).isZero();
    assertThat(factTargetCount(runId)).isZero();
    assertThat(publicationReadyCount()).isZero();
    logTaskAndOdsSummary("cancel-during-write", runId, taskId);

    long recoveryRunId = runFullCompensationToSuccess(10);
    assertThat(correctedRowIds()).containsExactly(101L, 102L, 103L, 104L);
    assertThat(pendingCorrectionRows()).isZero();
    assertThat(runStatus(recoveryRunId)).isEqualTo("SUCCESS");
    assertIdempotentSecondCompensation();
  }

  @Test
  void sourceReadFailureFailsTaskWithoutWritingAndRerunConverges() {
    long runId = insertCompensationRun();
    long taskId = insertCorrectionTask(runId, 10);
    jdbcTemplate.execute("alter table public.issues rename to issues_r1_unavailable");
    try {
      runWorkerService.executeRun(syncRunMapper.selectById(runId));
    } finally {
      jdbcTemplate.execute("alter table public.issues_r1_unavailable rename to issues");
    }

    assertThat(runStatus(runId)).isEqualTo("FAILED");
    assertThat(taskStatus(taskId)).isEqualTo("FAILED");
    assertThat(taskLastError(taskId)).contains("issues");
    assertThat(taskCursorPk(taskId)).isNull();
    assertThat(correctedRowIds()).isEmpty();
    assertThat(pendingCorrectionRows()).isEqualTo(SOURCE_ROWS.size());
    assertThat(tasksOfRun(runId)).hasSize(1);
    assertThat(stateWatermark()).isEqualTo(BASELINE_WATERMARK);
    assertThat(factRefreshRunCount()).isZero();
    assertThat(factTargetCount(runId)).isZero();
    assertThat(publicationReadyCount()).isZero();
    logTaskAndOdsSummary("source-read-failure", runId, taskId);

    long recoveryRunId = runFullCompensationToSuccess(10);
    assertThat(correctedRowIds()).containsExactly(101L, 102L, 103L, 104L);
    assertThat(pendingCorrectionRows()).isZero();
    assertThat(runStatus(recoveryRunId)).isEqualTo("SUCCESS");
    assertIdempotentSecondCompensation();
  }

  @Test
  void targetWriteFailureRollsBackWholePageAndRerunConverges() {
    createFailActiveWriteTrigger();
    long runId = insertCompensationRun();
    long taskId = insertCorrectionTask(runId, 2);
    try {
      runWorkerService.executeRun(syncRunMapper.selectById(runId));
    } finally {
      dropFailActiveWriteFunction();
    }

    assertThat(runStatus(runId)).isEqualTo("FAILED");
    assertThat(taskStatus(taskId)).isEqualTo("FAILED");
    assertThat(taskLastError(taskId)).contains("R1 target write failure injected");
    assertThat(taskCursorPk(taskId)).isNull();
    assertThat(correctedRowIds()).isEmpty();
    assertThat(pendingCorrectionRows()).isEqualTo(SOURCE_ROWS.size());
    assertThat(tasksOfRun(runId)).hasSize(1);
    assertThat(stateWatermark()).isEqualTo(BASELINE_WATERMARK);
    assertThat(factRefreshRunCount()).isZero();
    assertThat(factTargetCount(runId)).isZero();
    assertThat(publicationReadyCount()).isZero();
    logTaskAndOdsSummary("target-write-failure", runId, taskId);

    long recoveryRunId = runFullCompensationToSuccess(10);
    assertThat(correctedRowIds()).containsExactly(101L, 102L, 103L, 104L);
    assertThat(pendingCorrectionRows()).isZero();
    assertThat(runStatus(recoveryRunId)).isEqualTo("SUCCESS");
    assertIdempotentSecondCompensation();
  }

  @Test
  void authoritativeRangeReplacementFailureRollsBackDeleteAndWriteTogether() {
    createFailActiveWriteTrigger();
    SourceTableSchema schema =
        mirrorSchemaService.getPreparedMirrorTableForSync(config, tableOption()).mirrorSchema();
    Map<String, Object> lookupScope = Map.of("project_id", PROJECT_ID);
    List<Map<String, Object>> compensationRows =
        SOURCE_ROWS.subList(0, 3).stream().map(this::sourceRowMap).toList();
    try {
      assertThatThrownBy(
              () ->
                  storageService.replaceAuthoritativeScope(
                      schema, lookupScope, compensationRows, 990001L))
          .hasStackTraceContaining("R1 target write failure injected");
      assertThat(activeMirrorRowIds()).containsExactly(101L, 102L, 103L, 104L);
      assertThat(mirrorDeleted(104L)).isFalse();
      assertThat(correctedRowIds()).isEmpty();
      assertThat(pendingCorrectionRows()).isEqualTo(SOURCE_ROWS.size());
      log.info(
          "[R1] scenario=authoritative-range-replacement boundary=delete-then-write "
              + "scope={} activeRowsAfterFailure={} mirrorDeleted104={} correctedIds={} pendingRows={}",
          lookupScope,
          activeMirrorRowIds(),
          mirrorDeleted(104L),
          correctedRowIds(),
          pendingCorrectionRows());
    } finally {
      dropFailActiveWriteFunction();
    }

    storageService.replaceAuthoritativeScope(schema, lookupScope, compensationRows, 990002L);
    assertThat(mirrorDeleted(104L))
        .as("来源已删除的镜像行由范围替换正常标记 tombstone")
        .isTrue();
    assertThat(correctedRowIds()).containsExactly(101L, 102L, 103L);
    assertThat(pendingCorrectionRows()).isZero();
    log.info(
        "[R1] scenario=authoritative-range-replacement recovered activeRows={} "
            + "mirrorDeleted104={} correctedIds={} pendingRows={}",
        activeMirrorRowIds(),
        mirrorDeleted(104L),
        correctedRowIds(),
        pendingCorrectionRows());
  }

  private void assertIdempotentSecondCompensation() {
    Map<Long, LocalDateTime> before = mirrorUpdatedAtById();
    long secondRunId = runFullCompensationToSuccess(10);
    assertThat(runStatus(secondRunId)).isEqualTo("SUCCESS");
    assertThat(mirrorUpdatedAtById())
        .as("第二次全量补偿必须字节稳定，不重复改写已纠正行")
        .isEqualTo(before);
    assertThat(correctedRowIds()).containsExactly(101L, 102L, 103L, 104L);
  }

  private long runFullCompensationToSuccess(int batchSize) {
    long runId = insertCompensationRun();
    insertCorrectionTask(runId, batchSize);
    runWorkerService.executeRun(syncRunMapper.selectById(runId));
    assertThat(runStatus(runId)).isEqualTo("SUCCESS");
    return runId;
  }

  private long insertCompensationRun() {
    Long runId =
        jdbcTemplate.queryForObject(
            """
            insert into sync_runs(
                run_id, config_id, source_instance, run_type, trigger_type,
                status, priority, exclusive_scope, payload_json,
                resolved_worker_count, lease_owner, lease_until, started_at)
            values (?, ?, ?, 'FULL_COMPENSATION_SCAN', 'MANUAL', 'RUNNING', 100,
                    'gitlab:default', '{"sourceTables":["issues"]}',
                    1, 'r1-e2e', current_timestamp + interval '1 hour', current_timestamp)
            returning id
            """,
            Long.class,
            "r1-boundary-" + UUID.randomUUID(),
            config.getId(),
            SOURCE_INSTANCE);
    assertThat(runId).isNotNull();
    return runId;
  }

  private long insertCorrectionTask(long runId, int batchSize) {
    Long taskId =
        jdbcTemplate.queryForObject(
            """
            insert into sync_run_table_tasks(
                run_id, config_id, state_id, source_instance, source_table, mirror_table,
                task_type, status, row_strategy, task_stage, batch_size, run_after,
                retry_count, max_retry_count, rows_scanned, rows_applied)
            values (?, ?, ?, ?, ?, ?, 'FULL_COMPENSATION_SCAN', 'QUEUED',
                    'FULL_RECONCILE', 'SCAN', ?, current_timestamp, 0, 3, 0, 0)
            returning id
            """,
            Long.class,
            runId,
            config.getId(),
            stateId,
            SOURCE_INSTANCE,
            SOURCE_TABLE,
            MIRROR_TABLE,
            batchSize);
    assertThat(taskId).isNotNull();
    return taskId;
  }

  private long insertTableState() {
    Long id =
        jdbcTemplate.queryForObject(
            """
            insert into sync_run_table_states(
                config_id, source_instance, source_table, mirror_table,
                primary_key_columns, updated_at_column, row_strategy, cursor_strategy,
                sync_enabled, dirty_flag, last_watermark_at, last_cursor_pk,
                retry_count, created_at, updated_at)
            values (?, ?, ?, ?, 'id', 'updated_at', 'INCREMENTAL', 'TIMESTAMP_KEYSET',
                    true, false, ?, '[]', 0, current_timestamp, current_timestamp)
            returning id
            """,
            Long.class,
            config.getId(),
            SOURCE_INSTANCE,
            SOURCE_TABLE,
            MIRROR_TABLE,
            BASELINE_WATERMARK);
    assertThat(id).isNotNull();
    return id;
  }

  private void createSourceTable() {
    jdbcTemplate.execute(
        """
        create table public.issues (
          id bigint primary key,
          iid bigint,
          project_id bigint,
          title varchar(255),
          created_at timestamptz,
          updated_at timestamptz,
          closed_at timestamptz
        )
        """);
  }

  private void insertSourceRows() {
    for (SourceRow row : SOURCE_ROWS) {
      jdbcTemplate.update(
          """
          insert into public.issues(id, iid, project_id, title, created_at, updated_at, closed_at)
          values (?, ?, ?, ?, ?, ?, ?)
          """,
          row.id(),
          row.iid(),
          PROJECT_ID,
          "来源议题-" + row.id(),
          toOffset(row.createdAt()),
          toOffset(row.updatedAt()),
          row.closedAt() == null ? null : toOffset(row.closedAt()));
    }
  }

  private void insertOldWriterOdsRows() {
    for (SourceRow row : SOURCE_ROWS) {
      jdbcTemplate.update(
          """
          insert into ods_gitlab_issues(
              id, iid, project_id, title, created_at, updated_at, closed_at,
              source_updated_at, mirror_deleted)
          values (?, ?, ?, ?, ?, ?, ?, ?, false)
          """,
          row.id(),
          row.iid(),
          PROJECT_ID,
          "旧写入器镜像-" + row.id(),
          toOffset(row.createdAt().minus(OLD_WRITER_SHIFT)),
          toOffset(row.updatedAt().minus(OLD_WRITER_SHIFT)),
          row.closedAt() == null
              ? null
              : toOffset(row.closedAt().minus(OLD_WRITER_SHIFT)),
          LocalDateTime.ofInstant(row.updatedAt(), ZoneOffset.UTC));
    }
  }

  private Map<String, Object> sourceRowMap(SourceRow row) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("id", row.id());
    values.put("iid", row.iid());
    values.put("project_id", PROJECT_ID);
    values.put("title", "补偿来源-" + row.id());
    values.put("created_at", toOffset(row.createdAt()));
    values.put("updated_at", toOffset(row.updatedAt()));
    values.put("closed_at", row.closedAt() == null ? null : toOffset(row.closedAt()));
    return values;
  }

  private long pendingCorrectionRows() {
    Long count =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from ods_gitlab_issues ods
              join public.issues source on source.id = ods.id
             where ods.mirror_deleted = false
               and (ods.updated_at is distinct from source.updated_at
                    or ods.closed_at is distinct from source.closed_at)
            """,
            Long.class);
    return count == null ? 0L : count;
  }

  private List<Long> correctedRowIds() {
    return jdbcTemplate.queryForList(
        """
        select ods.id
          from ods_gitlab_issues ods
          join public.issues source on source.id = ods.id
         where ods.mirror_deleted = false
           and ods.updated_at = source.updated_at
           and ods.closed_at is not distinct from source.closed_at
           and ods.source_updated_at = (source.updated_at at time zone 'UTC')
         order by ods.id
        """,
        Long.class);
  }

  private List<Long> activeMirrorRowIds() {
    return jdbcTemplate.queryForList(
        "select id from ods_gitlab_issues where mirror_deleted = false order by id",
        Long.class);
  }

  private boolean mirrorDeleted(long id) {
    Boolean deleted =
        jdbcTemplate.queryForObject(
            "select mirror_deleted from ods_gitlab_issues where id = ?",
            Boolean.class,
            id);
    return Boolean.TRUE.equals(deleted);
  }

  private LocalDateTime odsUpdatedAtUtc(long id) {
    return jdbcTemplate.queryForObject(
        "select updated_at at time zone 'UTC' from ods_gitlab_issues where id = ?",
        LocalDateTime.class,
        id);
  }

  private Map<Long, LocalDateTime> mirrorUpdatedAtById() {
    return jdbcTemplate.query(
        "select id, mirror_updated_at from ods_gitlab_issues order by id",
        resultSet -> {
          Map<Long, LocalDateTime> values = new LinkedHashMap<>();
          while (resultSet.next()) {
            values.put(
                resultSet.getLong("id"),
                resultSet.getTimestamp("mirror_updated_at").toLocalDateTime());
          }
          return values;
        });
  }

  private String runStatus(long runId) {
    return jdbcTemplate.queryForObject(
        "select status from sync_runs where id = ?", String.class, runId);
  }

  private String taskStatus(long taskId) {
    return jdbcTemplate.queryForObject(
        "select status from sync_run_table_tasks where id = ?", String.class, taskId);
  }

  private long taskRowsScanned(long taskId) {
    Long rows =
        jdbcTemplate.queryForObject(
            "select rows_scanned from sync_run_table_tasks where id = ?", Long.class, taskId);
    return rows == null ? 0L : rows;
  }

  private String taskCursorPk(long taskId) {
    return jdbcTemplate.queryForObject(
        "select cursor_pk from sync_run_table_tasks where id = ?", String.class, taskId);
  }

  private String taskOwner(long taskId) {
    return jdbcTemplate.queryForObject(
        "select lease_owner from sync_run_table_tasks where id = ?", String.class, taskId);
  }

  private String taskLastError(long taskId) {
    return jdbcTemplate.queryForObject(
        "select last_error from sync_run_table_tasks where id = ?", String.class, taskId);
  }

  private List<TaskSnapshot> tasksOfRun(long runId) {
    return jdbcTemplate.query(
        """
        select id, task_stage, status, rows_scanned, rows_applied, cursor_pk, last_error
          from sync_run_table_tasks
         where run_id = ?
         order by id
        """,
        (resultSet, rowNumber) ->
            new TaskSnapshot(
                resultSet.getLong("id"),
                resultSet.getString("task_stage"),
                resultSet.getString("status"),
                resultSet.getLong("rows_scanned"),
                resultSet.getLong("rows_applied"),
                resultSet.getString("cursor_pk"),
                resultSet.getString("last_error")),
        runId);
  }

  private LocalDateTime stateWatermark() {
    return jdbcTemplate.queryForObject(
        "select last_watermark_at from sync_run_table_states where id = ?",
        LocalDateTime.class,
        stateId);
  }

  private long factRefreshRunCount() {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from sync_runs where run_type = 'FACT_REFRESH'", Long.class);
    return count == null ? 0L : count;
  }

  private long factTargetCount(long runId) {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from sync_run_fact_targets where mirror_run_id = ?",
            Long.class,
            runId);
    return count == null ? 0L : count;
  }

  private long publicationReadyCount() {
    Long count =
        jdbcTemplate.queryForObject(
            """
            select count(*)
              from source_fact_publication_states
             where config_id = ?
               and source_instance = ?
               and (readiness_status = 'READY' or full_publication_requested)
            """,
            Long.class,
            config.getId(),
            SOURCE_INSTANCE);
    return count == null ? 0L : count;
  }

  private void createAdvisoryWriteGate() {
    dropWriteGate();
    jdbcTemplate.execute("create sequence public.r1_write_gate_seq");
    jdbcTemplate.execute(
        """
        create function public.r1_write_gate_fn() returns trigger language plpgsql as $$
        begin
          perform nextval('public.r1_write_gate_seq');
          perform pg_advisory_xact_lock(732194650);
          return null;
        end
        $$
        """);
    jdbcTemplate.execute(
        """
        create trigger r1_write_gate
        before insert or update on ods_gitlab_issues
        for each statement execute function public.r1_write_gate_fn()
        """);
  }

  private void waitForCorrectionWriteEntered() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      Boolean entered =
          jdbcTemplate.queryForObject(
              "select is_called from public.r1_write_gate_seq", Boolean.class);
      if (Boolean.TRUE.equals(entered)) {
        return;
      }
      Thread.sleep(10L);
    }
    throw new IllegalStateException("纠正写入未在期限内进入数据库屏障");
  }

  private Connection acquireSessionAdvisoryLock() throws Exception {
    Connection connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    connection.setAutoCommit(true);
    try (PreparedStatement statement =
        connection.prepareStatement("select pg_advisory_lock(?)")) {
      statement.setLong(1, WRITE_GATE_KEY);
      statement.execute();
    }
    log.info("[R1] advisory session lock acquired, holderBackendPid={}", backendPid(connection));
    return connection;
  }

  private void releaseSessionAdvisoryLock(Connection connection) {
    if (connection == null) {
      return;
    }
    try (PreparedStatement statement =
        connection.prepareStatement("select pg_advisory_unlock(?)")) {
      statement.setLong(1, WRITE_GATE_KEY);
      statement.execute();
      log.info("[R1] advisory session lock released, holderBackendPid={}", backendPid(connection));
    } catch (Exception error) {
      log.warn("[R1] advisory session unlock failed; closing connection", error);
    } finally {
      try {
        connection.close();
      } catch (Exception ignored) {
        // 连接关闭失败不影响测试结论；会话级锁随连接关闭释放。
      }
    }
  }

  private long backendPid(Connection connection) {
    try (PreparedStatement statement =
            connection.prepareStatement("select pg_backend_pid()");
        var resultSet = statement.executeQuery()) {
      resultSet.next();
      return resultSet.getLong(1);
    } catch (Exception error) {
      return -1L;
    }
  }

  private List<Map<String, Object>> advisoryLockHolders() {
    return jdbcTemplate.queryForList(
        """
        select lock.pid, lock.granted, activity.state
          from pg_locks lock
          join pg_stat_activity activity on activity.pid = lock.pid
         where lock.locktype = 'advisory'
           and lock.objid = ?
         order by lock.pid
        """,
        WRITE_GATE_KEY);
  }

  private void dropWriteGate() {
    jdbcTemplate.execute("drop function if exists public.r1_write_gate_fn() cascade");
    jdbcTemplate.execute("drop sequence if exists public.r1_write_gate_seq");
  }

  private void createFailActiveWriteTrigger() {
    dropFailActiveWriteFunction();
    jdbcTemplate.execute(
        """
        create function public.r1_fail_active_write_fn() returns trigger language plpgsql as $$
        begin
          if new.mirror_deleted = false then
            raise exception 'R1 target write failure injected' using errcode = 'P0001';
          end if;
          return new;
        end
        $$
        """);
    jdbcTemplate.execute(
        """
        create trigger r1_fail_active_write
        before insert or update on ods_gitlab_issues
        for each row execute function public.r1_fail_active_write_fn()
        """);
  }

  private void dropFailActiveWriteFunction() {
    jdbcTemplate.execute(
        "drop function if exists public.r1_fail_active_write_fn() cascade");
  }

  private void logTaskAndOdsSummary(String scenario, long runId, long taskId) {
    log.info(
        "[R1] scenario={} summary runId={} runStatus={} tasks={} ods={} correctedIds={} "
            + "pendingRows={} watermark={} factRefreshRuns={}",
        scenario,
        runId,
        runStatus(runId),
        tasksOfRun(runId),
        activeOdsRows(),
        correctedRowIds(),
        pendingCorrectionRows(),
        stateWatermark(),
        factRefreshRunCount());
    if (taskId > 0L) {
      log.info(
          "[R1] scenario={} taskId={} owner={} status={} scanned={} applied={} cursorPk={} error={}",
          scenario,
          taskId,
          taskOwner(taskId),
          taskStatus(taskId),
          taskRowsScanned(taskId),
          taskApplied(taskId),
          taskCursorPk(taskId),
          taskLastError(taskId));
    }
  }

  private long taskApplied(long taskId) {
    Long rows =
        jdbcTemplate.queryForObject(
            "select rows_applied from sync_run_table_tasks where id = ?", Long.class, taskId);
    return rows == null ? 0L : rows;
  }

  private List<Map<String, Object>> activeOdsRows() {
    return jdbcTemplate.queryForList(
        """
        select id,
               updated_at at time zone 'UTC' as updated_at_utc,
               closed_at at time zone 'UTC' as closed_at_utc,
               source_updated_at,
               mirror_deleted
          from ods_gitlab_issues
         order by id
        """);
  }

  private void join(Future<?> future, ExecutorService pool) throws Exception {
    try {
      future.get(60, TimeUnit.SECONDS);
    } catch (Exception error) {
      future.cancel(true);
      throw error;
    } finally {
      pool.shutdownNow();
    }
  }

  private ExecutorService newWorkerPool(String name) {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, name);
          thread.setDaemon(true);
          return thread;
        });
  }

  private void cleanPlatformState() {
    jdbcTemplate.update("delete from sync_run_publication_fence_scopes");
    jdbcTemplate.update("delete from sync_run_publication_fences");
    jdbcTemplate.update("delete from fact_projection_refresh_tasks");
    jdbcTemplate.update("delete from fact_projection_generations");
    jdbcTemplate.update("delete from sync_run_fact_targets");
    jdbcTemplate.update("delete from fact_change_heads");
    jdbcTemplate.update("delete from fact_build_tasks");
    jdbcTemplate.update("delete from sync_run_authoritative_scopes");
    jdbcTemplate.update("delete from source_fact_publication_states");
    jdbcTemplate.update("delete from source_fact_dependency_states");
    jdbcTemplate.update("delete from sync_run_events");
    jdbcTemplate.update("delete from sync_run_table_tasks");
    jdbcTemplate.update("delete from sync_run_table_states");
    jdbcTemplate.update("delete from sync_runs");
    jdbcTemplate.update("delete from sys_table_registry");
    jdbcTemplate.update("delete from issue_fact_customer_members");
    jdbcTemplate.update("delete from integration_test_fact");
    jdbcTemplate.update("delete from issue_fact");
    jdbcTemplate.update("delete from module_dictionary");
    jdbcTemplate.update("delete from issue_scope_members");
    jdbcTemplate.update("delete from issue_scope_groups");
    jdbcTemplate.update("delete from issue_scope_catalogs");
    jdbcTemplate.update("delete from gitlab_sync_configs");
    jdbcTemplate.update("delete from ods_gitlab_label_links");
    jdbcTemplate.update("delete from ods_gitlab_labels");
    jdbcTemplate.update("delete from ods_gitlab_resource_label_events");
    jdbcTemplate.update("delete from ods_gitlab_issue_assignees");
    jdbcTemplate.update("delete from ods_gitlab_notes");
    jdbcTemplate.update("delete from ods_gitlab_issues");
    jdbcTemplate.update("delete from ods_gitlab_milestones");
    jdbcTemplate.update("delete from ods_gitlab_users");
    jdbcTemplate.update("delete from ods_gitlab_projects");
    jdbcTemplate.update("delete from ods_gitlab_merge_requests");
    jdbcTemplate.update("delete from ods_gitlab_merge_request_metrics");
    jdbcTemplate.update("delete from ods_gitlab_namespaces");
    jdbcTemplate.update("delete from ods_gitlab_merge_request_reviewers");
    jdbcTemplate.update("delete from ods_gitlab_merge_request_assignees");
    jdbcTemplate.update("delete from ods_gitlab_merge_request_diffs");
    jdbcTemplate.update("delete from ods_gitlab_merge_request_diff_commits");
  }

  private void dropMirrorAndSource() {
    jdbcTemplate.execute("drop table if exists ods_gitlab_issues");
    jdbcTemplate.execute("drop table if exists public.issues");
    jdbcTemplate.execute("drop table if exists public.issues_r1_unavailable");
  }

  private TableWhitelistOption tableOption() {
    return new TableWhitelistOption(
        SOURCE_TABLE,
        "议题",
        "id",
        "updated_at",
        SourceCursorStrategy.TIMESTAMP_KEYSET,
        true);
  }

  private GitlabSyncConfig sourceConfig() {
    DatabaseEndpoint endpoint = databaseEndpoint();
    GitlabSyncConfig syncConfig = new GitlabSyncConfig();
    syncConfig.setName("r1-correction-boundary");
    syncConfig.setEnabled(true);
    syncConfig.setSourceEnabled(true);
    syncConfig.setSourceInstance(SOURCE_INSTANCE);
    syncConfig.setAutoSyncEnabled(true);
    syncConfig.setSourceMode(SourceMode.DIRECT);
    syncConfig.setWhitelistMode(WhitelistMode.CUSTOM);
    syncConfig.setWhitelistTables(List.of(SOURCE_TABLE));
    syncConfig.setDbHost(endpoint.host());
    syncConfig.setDbPort(endpoint.port());
    syncConfig.setDbName(endpoint.database());
    syncConfig.setDbUsername(dataSourceProperties.determineUsername());
    syncConfig.setDbPassword(dataSourceProperties.determinePassword());
    syncConfig.setCompensationIntervalMinutes(60);
    return syncConfig;
  }

  private DatabaseEndpoint databaseEndpoint() {
    String jdbcUrl = dataSourceProperties.determineUrl();
    if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
      throw new IllegalStateException("纠正边界集成测试要求标准 PostgreSQL JDBC URL");
    }
    URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
    String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
    if (uri.getHost() == null || database.isBlank()) {
      throw new IllegalStateException("无法从测试 JDBC URL 解析 PostgreSQL 地址");
    }
    return new DatabaseEndpoint(uri.getHost(), uri.getPort() < 0 ? 5432 : uri.getPort(), database);
  }

  private OffsetDateTime toOffset(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  @TestConfiguration
  static class BarrierReaderConfiguration {
    @Bean
    @Primary
    SourceTableReader correctionBoundarySourceTableReader(GitlabExternalDbService externalDbService) {
      return new BarrierSourceTableReader(externalDbService);
    }
  }

  static final class BarrierSourceTableReader extends SourceTableReader {
    BarrierSourceTableReader(GitlabExternalDbService externalDbService) {
      super(externalDbService);
    }

    @Override
    public List<Map<String, Object>> readFullBatch(
        GitlabSyncConfig config,
        TableWhitelistOption option,
        SourceTableSchema mirrorSchema,
        String cursorPk,
        int batchSize) {
      List<Map<String, Object>> rows =
          super.readFullBatch(config, option, mirrorSchema, cursorPk, batchSize);
      CorrectionReadGate.afterRead();
      return rows;
    }
  }

  /** 只阻塞第一次全量读取后的提交；接管 worker 的后续读取直接通过。 */
  static final class CorrectionReadGate {
    private static final AtomicReference<Gate> CURRENT = new AtomicReference<>();

    static void arm() {
      CURRENT.set(new Gate());
    }

    static void disarm() {
      CURRENT.set(null);
    }

    static void release() {
      Gate gate = CURRENT.get();
      if (gate != null) {
        gate.release.countDown();
      }
    }

    static boolean awaitRead() throws InterruptedException {
      Gate gate = CURRENT.get();
      return gate != null && gate.readReached.await(30, TimeUnit.SECONDS);
    }

    static void afterRead() {
      Gate gate = CURRENT.get();
      if (gate == null || !gate.consumed.compareAndSet(false, true)) {
        return;
      }
      gate.readReached.countDown();
      try {
        if (!gate.release.await(60, TimeUnit.SECONDS)) {
          throw new IllegalStateException("R1 读取后屏障等待放行超时");
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("R1 读取后屏障等待被中断", error);
      }
    }

    private static final class Gate {
      private final CountDownLatch readReached = new CountDownLatch(1);
      private final CountDownLatch release = new CountDownLatch(1);
      private final AtomicBoolean consumed = new AtomicBoolean();
    }
  }

  private record SourceRow(
      long id, long iid, Instant createdAt, Instant updatedAt, Instant closedAt) {}

  private record TaskSnapshot(
      long id,
      String stage,
      String status,
      long scanned,
      long applied,
      String cursorPk,
      String lastError) {}

  private record DatabaseEndpoint(String host, int port, String database) {}
}
