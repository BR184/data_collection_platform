package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.entity.statistics.StatisticBoardDefinition;
import com.data.collection.platform.entity.statistics.StatisticBoardMeta;
import com.data.collection.platform.entity.statistics.StatisticBoardResponse;
import com.data.collection.platform.service.statistics.StatisticBoardSnapshotService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 固定完整事实的读取不受当前生产资格影响；缓存缺失仍能从同一完整版本计算。 */
@SpringBootTest
class StatisticBoardSourceQualificationTest {
  private static final String PROBE_SOURCE = "s04qual";
  private static final String BOARD_KEY = "s04-qualification-probe";
  private static final long PROBE_CONFIG_ID = 990_401L;
  private static final long CUSTOMER_PROJECT_ID = 325L;

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private StatisticBoardSnapshotService snapshotService;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("delete from statistic_board_snapshots where board_key = ?", BOARD_KEY);
    jdbcTemplate.update("update read_publication_domains set current_publication_id = null where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update("delete from read_publications where domain_id in (select id from read_publication_domains where source_instance = ?)", PROBE_SOURCE);
    jdbcTemplate.update("delete from read_publication_domains where source_instance = ?", PROBE_SOURCE);
    cleanUp();
    ensureProbeRun();
    LabelEventHistoryTestSupport.markComplete(jdbcTemplate, PROBE_SOURCE);
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("delete from statistic_board_snapshots where board_key = ?", BOARD_KEY);
    jdbcTemplate.update("update read_publication_domains set current_publication_id = null where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update("delete from read_publications where domain_id in (select id from read_publication_domains where source_instance = ?)", PROBE_SOURCE);
    jdbcTemplate.update("delete from read_publication_domains where source_instance = ?", PROBE_SOURCE);
    cleanUp();
  }

  @Test
  void neverPublishedSourceIsRefusedEvenWhenItHasNoCurrentFacts() {
    AtomicInteger builds = new AtomicInteger();
    assertThatThrownBy(() -> read(builds))
        .isInstanceOf(com.data.collection.platform.service.read.ReadViewException.class)
        .hasMessageContaining("尚无完整产出");
    assertThat(builds).hasValue(0);
    assertThat(readySnapshotCount()).isZero();
  }

  @Test
  void currentProjectionOrReadyStateCannotSubstituteForACompleteReadPublication() {
    insertProjectionGeneration();
    upsertPublicationState("READY", false, null);
    AtomicInteger builds = new AtomicInteger();
    assertThatThrownBy(() -> read(builds))
        .isInstanceOf(com.data.collection.platform.service.read.ReadViewException.class);
    assertThat(builds).hasValue(0);
    establishCompleteFixture();
    read(builds);
    assertThat(builds).hasValue(1);
    assertThat(readySnapshotCount()).isOne();
  }

  @Test
  void unverifiedCurrentLabelHistoryKeepsPublishedFactsReadableWithColdCache() {
    establishCompleteFixture();
    upsertPublicationState("READY", false, null);
    LabelEventHistoryTestSupport.markIncomplete(jdbcTemplate, PROBE_SOURCE);
    AtomicInteger builds = new AtomicInteger();
    read(builds);
    assertThat(builds).hasValue(1);
    assertThat(readySnapshotCount()).isOne();
  }

  @Test
  void failedOrUnsettledProductionKeepsTheSameCompleteCacheReadable() {
    upsertPublicationState("READY", true, null);
    assertThatThrownBy(() -> read(new AtomicInteger()))
        .isInstanceOf(com.data.collection.platform.service.read.ReadViewException.class);
    establishCompleteFixture();
    read(new AtomicInteger());
    upsertPublicationState("BLOCKED", false, "镜像依赖运行失败");
    AtomicInteger builds = new AtomicInteger();
    read(builds, () -> { throw new AssertionError("已有同版本缓存不应重建"); });
    upsertPublicationState("READY", true, null);
    read(builds, () -> { throw new AssertionError("进行中的全量不能改写完整读取版本"); });
    assertThat(builds).hasValue(0);
    assertThat(readySnapshotCount()).isOne();
  }

  @Test
  void unpublishedChangesDiscloseFreshnessWithoutInvalidatingCompleteCache() {
    establishCompleteFixture();
    read(new AtomicInteger());
    upsertPublicationState("READY", false, null);
    insertUnpublishedTarget();
    AtomicInteger builds = new AtomicInteger();
    var response = read(builds, () -> { throw new AssertionError("同版本缓存仍可读"); });
    assertThat(response.pendingUpdates()).isEqualTo(1L);
    assertThat(response.dataAsOf()).isNotNull();
    assertThat(builds).hasValue(0);
    assertThat(readySnapshotCount()).isOne();
    deleteUnpublishedTarget();
    var converged = read(builds, () -> { throw new AssertionError("生产状态恢复不需要重算旧缓存"); });
    assertThat(converged.pendingUpdates()).isNull();
    assertThat(readySnapshotCount()).isOne();
  }

  @Test
  void coldCacheCanBeBuiltFromCompleteHistoricalFactsWhileNewChangesArePending() {
    establishCompleteFixture();
    upsertPublicationState("READY", false, null);
    insertUnpublishedTarget();
    AtomicInteger builds = new AtomicInteger();
    var response = read(builds);
    assertThat(builds).hasValue(1);
    assertThat(response.pendingUpdates()).isEqualTo(1L);
    assertThat(readySnapshotCount()).isOne();
  }

  /** 组件边界提供明确完成的空事实输入；生产初始接管的认证由默认适配器真库测试负责。 */
  private void establishCompleteFixture() {
    FactRevisionFixture.publishFacts(jdbcTemplate, com.data.collection.platform.entity.read.PublicationFactTable.ISSUE,
        PROBE_SOURCE, true, target -> new com.data.collection.platform.entity.FactBuildResponse("fixture", true, 0, "fixture"));
  }

  private com.data.collection.platform.entity.read.FactReadSelection selection() {
    var repository = new com.data.collection.platform.service.read.ReadPublicationRepository(jdbcTemplate);
    var key = com.data.collection.platform.entity.read.ReadPublicationDomainKey.gitlabFact(PROBE_SOURCE, FactType.ISSUE);
    var point = repository.findDomain(key).flatMap(domain -> repository.findCurrentPublication(domain.id()))
        .orElseThrow(() -> com.data.collection.platform.service.read.ReadViewException.unavailable("尚无完整产出"));
    return new com.data.collection.platform.entity.read.FactReadSelection(List.of(
        new com.data.collection.platform.entity.read.PublishedReadContext(PROBE_SOURCE,
            com.data.collection.platform.entity.read.PublicationFactTable.ISSUE, point.epochId(), point.sequenceNo(), point.id(), point.dataAsOf())),
        java.time.LocalDate.of(2026, 10, 13));
  }

  private StatisticBoardResponse read(AtomicInteger builds) {
    return read(builds, () -> {
      builds.incrementAndGet();
      return emptyBoardResponse();
    });
  }

  private StatisticBoardResponse read(
      AtomicInteger builds, java.util.function.Supplier<StatisticBoardResponse> supplier) {
    return snapshotService.readOrRefresh(selection(),
        new StatisticBoardSnapshotService.SnapshotRequest(
            BOARD_KEY,
            "project=" + CUSTOMER_PROJECT_ID,
            "s04-qualification@v1",
            StatisticBoardSnapshotService.SourceReadPlan.of(this::probeScopes),
            Map.of("projectId", String.valueOf(CUSTOMER_PROJECT_ID)),
            (StatisticBoardDefinition) null,
            null),
        sourceRead -> supplier.get());
  }

  private Set<FactProjectionScope> probeScopes() {
    return Set.of(
        new FactProjectionScope(
            PROBE_SOURCE,
            FactType.ISSUE,
            ProjectionScopeType.PROJECT,
            FactProjectionScopeKeyCodec.project(CUSTOMER_PROJECT_ID)));
  }

  private StatisticBoardResponse emptyBoardResponse() {
    return new StatisticBoardResponse(
        null,
        Map.of(),
        null,
        List.of(),
        new StatisticBoardMeta(LocalDateTime.now(), 0L, 0, 0, 0),
        null,
        null);
  }

  private long readySnapshotCount() {
    Long count =
        jdbcTemplate.queryForObject(
            """
            select count(*) from statistic_board_snapshots
             where board_key = ? and status = 'READY'
            """,
            Long.class,
            BOARD_KEY);
    return count == null ? 0L : count;
  }

  private void upsertPublicationState(
      String readinessStatus, boolean fullPublicationRequested, String errorMessage) {
    long runId = ensureProbeRun();
    jdbcTemplate.update(
        """
        insert into source_fact_publication_states(
          config_id, source_instance, fact_type, latest_mirror_run_id,
          readiness_status, full_publication_requested, error_message, updated_at)
        values (?, ?, 'ISSUE', ?, ?, ?, ?, current_timestamp)
        on conflict (config_id, source_instance, fact_type) do update
           set latest_mirror_run_id = excluded.latest_mirror_run_id,
               readiness_status = excluded.readiness_status,
               full_publication_requested = excluded.full_publication_requested,
               error_message = excluded.error_message,
               updated_at = current_timestamp
        """,
        PROBE_CONFIG_ID,
        PROBE_SOURCE,
        runId,
        readinessStatus,
        fullPublicationRequested,
        errorMessage);
  }

  /** 制造“该来源已经产生事实投影，但没有任何发布记录”的未知状态。 */
  private void insertProjectionGeneration() {
    jdbcTemplate.update(
        """
        insert into fact_projection_generations(
          source_instance, fact_type, scope_type, scope_key, generation)
        values (?, 'ISSUE', 'PROJECT', ?, 1)
        on conflict (source_instance, fact_type, scope_type, scope_key)
        do update set generation = excluded.generation, updated_at = current_timestamp
        """,
        PROBE_SOURCE,
        FactProjectionScopeKeyCodec.project(CUSTOMER_PROJECT_ID));
  }

  /** 制造“已登记变化但未发布到最新版本”的版本栅栏。 */
  private void insertUnpublishedTarget() {
    long runId = ensureProbeRun();
    jdbcTemplate.update(
        """
        insert into sync_run_fact_targets(
          mirror_run_id, source_instance, fact_type, root_id, change_version, publication_status)
        values (?, ?, 'ISSUE', 880001, 2, 'PENDING')
        """,
        runId,
        PROBE_SOURCE);
    jdbcTemplate.update(
        """
        insert into fact_change_heads(
          source_instance, fact_type, root_id, latest_change_version, published_version)
        values (?, 'ISSUE', 880001, 2, 1)
        """,
        PROBE_SOURCE);
  }

  private long ensureProbeRun() {
    jdbcTemplate.update(
        """
        insert into gitlab_sync_configs(
          id, name, source_instance, source_mode, db_name, db_username, db_password)
        values (?, 's04qual-probe', ?, 'DOCKER', 'gl_database', 'gl_user', 'gl_password')
        on conflict (id) do nothing
        """,
        PROBE_CONFIG_ID,
        PROBE_SOURCE);
    java.util.List<Long> existing =
        jdbcTemplate.queryForList(
            "select id from sync_runs where run_id = ?", Long.class, "s04qual-run");
    if (!existing.isEmpty()) {
      return existing.get(0);
    }
    return jdbcTemplate.queryForObject(
        """
        insert into sync_runs(
          run_id, config_id, source_instance, run_type, trigger_type, status, exclusive_scope)
        values (?, ?, ?, 'TABLE_REFRESH', 'MANUAL', 'SUCCESS', 's04qual')
        returning id
        """,
        Long.class,
        "s04qual-run",
        PROBE_CONFIG_ID,
        PROBE_SOURCE);
  }

  private void deleteUnpublishedTarget() {
    jdbcTemplate.update(
        "delete from fact_change_heads where source_instance = ? and root_id = 880001",
        PROBE_SOURCE);
    jdbcTemplate.update(
        "delete from sync_run_fact_targets where source_instance = ? and root_id = 880001",
        PROBE_SOURCE);
  }

  private void cleanUp() {
    jdbcTemplate.update(
        "delete from fact_projection_generations where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update(
        "delete from source_fact_publication_states where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update(
        "delete from fact_change_heads where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update(
        "delete from sync_run_fact_targets where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update("delete from sync_runs where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update("delete from gitlab_sync_configs where id = ?", PROBE_CONFIG_ID);
  }
}
