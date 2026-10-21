package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.FactPublicationContext;
import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.QueuedFactProjectionTask;
import com.data.collection.platform.entity.read.PublicationFactTable;
import com.data.collection.platform.entity.read.ReadPublicationDomainKey;
import com.data.collection.platform.entity.statistics.StatisticBoardDefinition;
import com.data.collection.platform.entity.statistics.StatisticBoardMeta;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.service.FactProjectionExecutionContext;
import com.data.collection.platform.service.FactProjectionPublicationGuardService;
import com.data.collection.platform.service.FactProjectionTaskService;
import com.data.collection.platform.service.FactProjectionVersionService;
import com.data.collection.platform.service.ProjectionTaskLeaseLostException;
import com.data.collection.platform.service.read.CandidateCacheArtifactStore;
import com.data.collection.platform.service.read.CandidateCacheArtifactStore.CandidateCacheArtifact;
import com.data.collection.platform.service.read.CandidateProjectionArtifactService;
import com.data.collection.platform.service.read.FactReadSelectionService;
import com.data.collection.platform.service.read.ReadPublicationRepository;
import com.data.collection.platform.service.read.ReadPublicationService;
import com.data.collection.platform.service.statistics.StatisticBoardSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 候选预热产物写入归属的交错证据。
 *
 * <p>锁定三条边界：证明只能来自本任务写入时读回的内容快照（跨范围异义覆盖必被拒绝，且拒绝发生在证明阶段而
 * 不是把覆盖后的内容当成自身产物）；相同内容的重复写入保持稳定；发布核验要求当前每条候选缓存行都有证明。
 * 交错由调用顺序控制，不使用等待或重试。
 */
class CandidateArtifactWriteOwnershipIntegrationTest {
  private static final String SOURCE_INSTANCE = "alpha";
  private static final java.time.LocalDate BUSINESS_DATE = java.time.LocalDate.of(2026, 10, 13);

  private static PostgresIntegrationTestDatabase database;
  private static DataSource dataSource;

  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate transactionTemplate;
  private FactProjectionExecutionContext executionContext;
  private StatisticBoardSnapshotService snapshotService;
  private CandidateProjectionArtifactService artifacts;
  private ReadPublicationRepository publications;
  private ReadPublicationService publisher;
  private FactProjectionTaskService tasks;
  private UUID candidateId;

  @BeforeAll
  static void openDatabase() {
    database = PostgresIntegrationTestDatabase.open("candidate_artifact_ownership_test");
    dataSource = database.dataSource();
    org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure().dataSource(dataSource)
        .locations("classpath:db/migration").cleanDisabled(false).load();
    flyway.clean();
    flyway.migrate();
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeEach
  void resetSchema() {
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("truncate table read_publication_domains, read_publications, read_definition_snapshots, "
        + "read_projection_artifacts, fact_projection_refresh_tasks, fact_projection_generations, fact_build_tasks, "
        + "sync_runs, gitlab_sync_configs, statistic_board_snapshots, page_record_snapshots restart identity cascade");
    DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
    transactionTemplate = new TransactionTemplate(transactionManager);
    executionContext = new FactProjectionExecutionContext();
    CandidateCacheArtifactStore store = new CandidateCacheArtifactStore(jdbcTemplate);
    publications = new ReadPublicationRepository(jdbcTemplate);
    publisher = new ReadPublicationService(publications, new com.data.collection.platform.config.ReadFallbackProperties());
    JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());
    snapshotService = new StatisticBoardSnapshotService(jdbcTemplate, jsonUtils,
        new FactProjectionVersionService(jdbcTemplate), mock(SyncFactPublicationStateService.class),
        new FactProjectionPublicationGuardService(jdbcTemplate, executionContext, store, transactionManager),
        transactionManager);
    tasks = new FactProjectionTaskService(jdbcTemplate, mock(SyncRunPublicationFenceService.class));
    FactReadSelectionService selections =
        new FactReadSelectionService(jdbcTemplate, publications, publisher, () -> BUSINESS_DATE);
    artifacts = new CandidateProjectionArtifactService(jdbcTemplate, jsonUtils, publications, selections,
        new FactProjectionPublicationGuardService(jdbcTemplate, executionContext, store, transactionManager),
        tasks, store);
    prepareCandidate();
  }

  /** 交错反例：A 写入内容 a 后尚未证明，B 把同一行覆盖为 b；A 不能把 b 当成自身产物。 */
  @Test
  void other_scope_overwrite_before_completion_cannot_be_certified_as_own_product() {
    QueuedFactProjectionTask first = task(1L, "scope-1", "token-1");
    QueuedFactProjectionTask second = task(2L, "scope-2", "token-2");
    List<CandidateCacheArtifact> firstWrites;
    try (FactProjectionExecutionContext.Scope ignored = executionContext.open(first)) {
      snapshotService.save(selection(), boardRequest("board-1"), boardResponse("内容甲"));
      firstWrites = executionContext.writtenArtifacts();
    }
    assertThat(firstWrites).hasSize(1);

    try (FactProjectionExecutionContext.Scope ignored = executionContext.open(second)) {
      snapshotService.save(selection(), boardRequest("board-1"), boardResponse("内容乙"));
      inTransaction(() -> {
        artifacts.complete(second, context(second), List.of("BoardRefresher"), executionContext.writtenArtifacts());
        return null;
      });
    }

    try (FactProjectionExecutionContext.Scope ignored = executionContext.open(first)) {
      assertThatThrownBy(() -> inTransaction(() -> {
        artifacts.complete(first, context(first), List.of("BoardRefresher"), firstWrites);
        return null;
      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("被改写")
          .hasMessageContaining("scope-1")
          .hasMessageContaining("预期")
          .hasMessageContaining("实际");
    }
    assertThat(scopeProofCount("scope-1")).as("失败证明不得留下清单").isZero();
    assertThat(candidateState()).isEqualTo("PROJECTING");
  }

  /** 证明之后的异义覆盖必须让发布核验失败，并给出行身份与预期/实际校验和。 */
  @Test
  void overwrite_after_proof_blocks_publication_with_row_evidence() {
    QueuedFactProjectionTask first = task(1L, "scope-1", "token-1");
    QueuedFactProjectionTask second = task(2L, "scope-2", "token-2");
    try (FactProjectionExecutionContext.Scope ignored = executionContext.open(first)) {
      snapshotService.save(selection(), boardRequest("board-1"), boardResponse("内容甲"));
      inTransaction(() -> {
        artifacts.complete(first, context(first), List.of("BoardRefresher"), executionContext.writtenArtifacts());
        return null;
      });
    }
    try (FactProjectionExecutionContext.Scope ignored = executionContext.open(second)) {
      snapshotService.save(selection(), boardRequest("board-1"), boardResponse("内容丙"));
      inTransaction(() -> {
        artifacts.complete(second, context(second), List.of("BoardRefresher"), executionContext.writtenArtifacts());
        return null;
      });
    }

    assertThatThrownBy(() -> artifacts.verifyForPublication(candidateId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("内容已变化")
        .hasMessageContaining("board-1")
        .hasMessageContaining("scope-1");
  }

  /** 相同内容的重复写入是幂等的：两个范围各自证明同一行仍可发布。 */
  @Test
  void identical_rewrite_by_another_scope_stays_attestable() {
    QueuedFactProjectionTask first = task(1L, "scope-1", "token-1");
    QueuedFactProjectionTask second = task(2L, "scope-2", "token-2");
    for (QueuedFactProjectionTask owner : List.of(first, second)) {
      try (FactProjectionExecutionContext.Scope ignored = executionContext.open(owner)) {
        snapshotService.save(selection(), boardRequest("board-1"), boardResponse("同一内容"));
        inTransaction(() -> {
          artifacts.complete(owner, context(owner), List.of("BoardRefresher"), executionContext.writtenArtifacts());
          return null;
        });
      }
    }

    assertThat(jdbcTemplate.queryForObject("select count(*) from statistic_board_snapshots where publication_id = ?",
        Long.class, candidateId)).as("幂等重写不产生第二行").isOne();
    artifacts.verifyForPublication(candidateId);
  }

  /** 没有被任何完成任务证明的候选行不得被提升为公开内容。 */
  @Test
  void candidate_row_without_any_proof_blocks_publication() {
    QueuedFactProjectionTask first = task(1L, "scope-1", "token-1");
    QueuedFactProjectionTask second = task(2L, "scope-2", "token-2");
    try (FactProjectionExecutionContext.Scope ignored = executionContext.open(first)) {
      snapshotService.save(selection(), boardRequest("board-1"), boardResponse("已证明"));
      inTransaction(() -> {
        artifacts.complete(first, context(first), List.of("BoardRefresher"), executionContext.writtenArtifacts());
        return null;
      });
    }
    try (FactProjectionExecutionContext.Scope ignored = executionContext.open(second)) {
      snapshotService.save(selection(), boardRequest("board-2"), boardResponse("无人证明"));
    }

    assertThatThrownBy(() -> artifacts.verifyForPublication(candidateId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("缺少完成证明")
        .hasMessageContaining("board-2");
  }

  /** 已证明的行被清理后，发布核验以“已缺失”拒绝，不能静默放行。 */
  @Test
  void deleted_proven_row_blocks_publication() {
    QueuedFactProjectionTask first = task(1L, "scope-1", "token-1");
    try (FactProjectionExecutionContext.Scope ignored = executionContext.open(first)) {
      snapshotService.save(selection(), boardRequest("board-1"), boardResponse("内容甲"));
      inTransaction(() -> {
        artifacts.complete(first, context(first), List.of("BoardRefresher"), executionContext.writtenArtifacts());
        return null;
      });
    }
    jdbcTemplate.update("delete from statistic_board_snapshots where publication_id = ?", candidateId);

    assertThatThrownBy(() -> artifacts.verifyForPublication(candidateId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("已缺失")
        .hasMessageContaining("board-1");
  }

  /** 失租后的候选写入不产生快照，也不得污染本任务的写入清单。 */
  @Test
  void lease_loss_produces_no_artifact_snapshot() {
    QueuedFactProjectionTask first = task(1L, "scope-1", "token-1");
    try (FactProjectionExecutionContext.Scope ignored = executionContext.open(first)) {
      assertThat(executionContext.writtenArtifacts()).isEmpty();
      jdbcTemplate.update("update fact_projection_refresh_tasks set lease_owner = 'replacement' where id = 1");
      assertThatThrownBy(
          () -> snapshotService.save(selection(), boardRequest("board-1"), boardResponse("内容甲")))
          .isInstanceOf(ProjectionTaskLeaseLostException.class);
      assertThat(executionContext.writtenArtifacts()).as("失败的写入不得登记").isEmpty();
    }
    assertThat(boardRowCount()).isZero();
  }

  /** 写入清单只在任务作用域内存在，关闭后不得泄漏到下一次执行。 */
  @Test
  void written_journal_is_scoped_to_one_execution() {
    QueuedFactProjectionTask first = task(1L, "scope-1", "token-1");
    try (FactProjectionExecutionContext.Scope ignored = executionContext.open(first)) {
      snapshotService.save(selection(), boardRequest("board-1"), boardResponse("内容甲"));
      assertThat(executionContext.writtenArtifacts()).hasSize(1);
    }
    assertThatThrownBy(() -> executionContext.writtenArtifacts()).isInstanceOf(IllegalStateException.class);
    try (FactProjectionExecutionContext.Scope ignored = executionContext.open(first)) {
      assertThat(executionContext.writtenArtifacts()).as("下一次执行从空清单开始").isEmpty();
    }
  }

  private FactPublicationContext context(QueuedFactProjectionTask task) {
    return artifacts.contextForTask(task);
  }

  private void prepareCandidate() {
    candidateId = null;
    jdbcTemplate.update("insert into gitlab_sync_configs(id, db_name, db_username, db_password)"
        + " values (1, 'fixture', 'fixture', 'fixture')");
    jdbcTemplate.update("""
        insert into sync_runs(id, run_id, config_id, source_instance, run_type, trigger_type, status,
            exclusive_scope, lease_owner, lease_until, cancel_requested)
        values (900, 'ownership-fixture', 1, 'alpha', 'FACT_REFRESH', 'SCHEDULE', 'RUNNING',
            'ownership-fixture', 'run-lease-token', clock_timestamp() + interval '1 hour', false)
        """);
    jdbcTemplate.update("insert into fact_build_tasks(id, run_id, scope, status, source_instance, fact_type)"
        + " values (2, '900', 'issue', 'SUCCESS', 'alpha', 'ISSUE')");
    candidateId = inTransaction(() -> {
      UUID definition = publications.freezeCurrentFactDefinitions(SOURCE_INSTANCE);
      var candidate = publisher.openCandidate(new com.data.collection.platform.service.read.CandidateOpenRequest(
          ReadPublicationDomainKey.gitlabFact(SOURCE_INSTANCE, FactType.ISSUE), UUID.randomUUID(), null,
          "{\"fixture\":true}", 0, definition, "900", 2L, "run-lease-token", java.time.Instant.now()));
      publisher.recordScopeGeneration(candidate.id(), ProjectionScopeType.PROJECT, "scope-1", 1);
      publisher.recordScopeGeneration(candidate.id(), ProjectionScopeType.PROJECT, "scope-2", 1);
      publisher.markProjecting(candidate.id(), "run-lease-token");
      return candidate.id();
    });
    for (long id : new long[] {1L, 2L}) {
      jdbcTemplate.update("""
          insert into fact_projection_refresh_tasks(id, fact_run_id, fact_build_task_id, publication_id,
              source_instance, fact_type, scope_type, scope_key, target_generation, status, lease_owner,
              lease_until, heartbeat_at, retry_count, max_retry_count, recovery_count, run_after, updated_at)
          values (?, 900, 2, ?, 'alpha', 'ISSUE', 'PROJECT', ?, 1, 'RUNNING', ?,
              clock_timestamp() + interval '1 hour', current_timestamp, 0, 3, 0,
              current_timestamp, current_timestamp)
          """, id, candidateId, id == 1L ? "scope-1" : "scope-2", id == 1L ? "token-1" : "token-2");
    }
  }

  private QueuedFactProjectionTask task(long id, String scopeKey, String leaseToken) {
    return new QueuedFactProjectionTask(id, 900L, "run-lease-token", 2L,
        new FactProjectionScope(SOURCE_INSTANCE, FactType.ISSUE, ProjectionScopeType.PROJECT, scopeKey),
        1L, 0, 3, leaseToken, null, candidateId);
  }

  private com.data.collection.platform.entity.read.FactReadSelection selection() {
    return new com.data.collection.platform.entity.read.FactReadSelection(
        List.of(publisher.candidateContext(candidateId)), BUSINESS_DATE);
  }

  private StatisticBoardSnapshotService.SnapshotRequest boardRequest(String boardKey) {
    return new StatisticBoardSnapshotService.SnapshotRequest(boardKey, "scope-1", "rule-1",
        StatisticBoardSnapshotService.SourceReadPlan.of(
            () -> Set.of(new FactProjectionScope(SOURCE_INSTANCE, FactType.ISSUE,
                ProjectionScopeType.PROJECT, "scope-1"))),
        Map.of("stage", "ownership"), boardDefinition(boardKey + "-标题"), null);
  }

  private StatisticBoardResponse boardResponse(String marker) {
    return new StatisticBoardResponse(boardDefinition(marker), Map.of(), null, List.of(),
        new StatisticBoardMeta(LocalDateTime.now(), 1L, 0, 0, 0), null, null);
  }

  private StatisticBoardDefinition boardDefinition(String title) {
    return new StatisticBoardDefinition("board-1", title, "说明", "查询", "查询说明", "行", List.of(),
        List.of(), List.of(), 20, "无数据");
  }

  private long scopeProofCount(String scopeKey) {
    return jdbcTemplate.queryForObject(
        "select count(*) from read_projection_artifacts where publication_id = ? and scope_key = ?",
        Long.class, candidateId, scopeKey);
  }

  private String candidateState() {
    return jdbcTemplate.queryForObject("select state from read_publications where id = ?", String.class, candidateId);
  }

  private long boardRowCount() {
    return jdbcTemplate.queryForObject("select count(*) from statistic_board_snapshots", Long.class);
  }

  private <T> T inTransaction(java.util.function.Supplier<T> action) {
    return transactionTemplate.execute(status -> action.get());
  }
}
