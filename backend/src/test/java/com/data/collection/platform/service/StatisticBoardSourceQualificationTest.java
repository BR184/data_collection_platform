package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
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

/**
 * S04 / T18：来源不完整时不得产出新的 READY 结果，命中缓存也必须校验发布资格。
 *
 * <p>覆盖两个必须拒绝的窗口（依赖未就绪、全量重建已请求但未结算）与一个降级可读窗口（仍有未发布变化）：
 * 降级可读退回上一个完整发布点并披露待更新数量，且只读不写，避免降级结果被当成该来源版本的完整产出。
 *
 * <p>“没有发布记录”本身不是可读理由：来源从未产生任何事实投影时可以证明它不贡献数据，因此可读；
 * 已经有事实投影却没有发布记录，属于完整性无从证实的未知状态，必须拒绝。
 */
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
    cleanUp();
    jdbcTemplate.update("delete from statistic_board_snapshots where board_key = ?", BOARD_KEY);
    ensureProbeRun();
    LabelEventHistoryTestSupport.markComplete(jdbcTemplate, PROBE_SOURCE);
  }

  @AfterEach
  void tearDown() {
    cleanUp();
    jdbcTemplate.update("delete from statistic_board_snapshots where board_key = ?", BOARD_KEY);
  }

  @Test
  void sourceWithoutPublicationRecordAndWithoutProjectionsStaysReadable() {
    AtomicInteger builds = new AtomicInteger();

    StatisticBoardResponse response = read(builds);

    assertThat(response.rows()).isEmpty();
    assertThat(builds).as("首次读取必须真实构建一次").hasValue(1);
    assertThat(readySnapshotCount()).isOne();

    read(builds, () -> {
      throw new AssertionError("命中 READY 快照时不得再次重建");
    });
    assertThat(builds).hasValue(1);
  }

  @Test
  void sourceWithProjectionsButWithoutPublicationRecordIsRejected() {
    insertProjectionGeneration();

    AtomicInteger builds = new AtomicInteger();
    assertThatThrownBy(() -> read(builds))
        .as("有事实投影却没有发布记录＝完整性无从证实，不得当作可读")
        .isInstanceOf(BizException.class)
        .hasMessageContaining("无法确认完整性");
    assertThat(builds)
        .as("拒绝必须发生在读取动作之前，不得先产出结果再解释")
        .hasValue(0);
    assertThat(readySnapshotCount()).isZero();

    upsertPublicationState("READY", false, null);
    read(builds);
    assertThat(readySnapshotCount()).as("补上发布记录后即可正常产出").isOne();
  }

  @Test
  void readyIssuePublicationWithoutVerifiedLabelEventHistoryIsRejected() {
    upsertPublicationState("READY", false, null);
    LabelEventHistoryTestSupport.markIncomplete(jdbcTemplate, PROBE_SOURCE);

    AtomicInteger builds = new AtomicInteger();
    assertThatThrownBy(() -> read(builds))
        .as("READY发布不能替代标签事件历史全量核验")
        .isInstanceOf(BizException.class)
        .hasMessageContaining("resource_label_events")
        .hasMessageContaining("完整全量核验");
    assertThat(builds).as("来源不完整时不得开始计算").hasValue(0);
    assertThat(readySnapshotCount()).isZero();
  }

  @Test
  void unsettledPublicationFallsBackToLastCompleteSnapshotAndRefusesWithoutOne() {
    upsertPublicationState("READY", true, null);
    assertThatThrownBy(() -> read(new AtomicInteger()))
        .as("全量重建未结算且没有可退回的完整发布点时必须拒绝")
        .isInstanceOf(BizException.class)
        .hasMessageContaining("全量事实重建尚未结算");
    assertThat(readySnapshotCount()).isZero();

    upsertPublicationState("READY", false, null);
    read(new AtomicInteger());
    assertThat(readySnapshotCount()).isOne();

    upsertPublicationState("BLOCKED", false, "镜像依赖运行失败");
    assertThatThrownBy(() -> read(new AtomicInteger()))
        .as("依赖未就绪必须明确拒绝，不得返回旧 READY 或全零新结果")
        .isInstanceOf(BizException.class)
        .hasMessageContaining("镜像依赖运行失败");
    assertThat(readySnapshotCount()).as("拒绝时不得写入新的 READY").isOne();

    upsertPublicationState("READY", true, null);
    AtomicInteger fallbackBuilds = new AtomicInteger();
    read(
        fallbackBuilds,
        () -> {
          throw new AssertionError("全量重建未结算时必须退回上一完整发布点，不得重新构建");
        });
    assertThat(fallbackBuilds).as("全量未结算时不得按当前事实重新计算").hasValue(0);
    assertThat(readySnapshotCount()).as("回退不得改写既有完整快照").isOne();
  }

  @Test
  void unpublishedChangesServeLastCompleteSnapshotWithFreshnessHint() {
    read(new AtomicInteger());
    assertThat(readySnapshotCount()).isOne();

    upsertPublicationState("READY", false, null);
    insertUnpublishedTarget();
    AtomicInteger degradedBuilds = new AtomicInteger();
    StatisticBoardResponse degraded =
        read(
            degradedBuilds,
            () -> {
              throw new AssertionError("降级可读必须退回上一个完整发布点，不得重新构建");
            });
    assertThat(degraded.pendingUpdates()).as("未收敛的来源必须披露待更新数量").isEqualTo(1L);
    assertThat(degraded.dataAsOf()).as("降级产出必须披露数据时刻").isNotNull();
    assertThat(degradedBuilds).hasValue(0);
    assertThat(readySnapshotCount()).as("降级读取不得改写既有完整快照").isOne();

    deleteUnpublishedTarget();
    StatisticBoardResponse converged =
        read(
            new AtomicInteger(),
            () -> {
              throw new AssertionError("收敛完成后应重新命中完整快照");
            });
    assertThat(converged.pendingUpdates()).as("收敛后不得再携带新鲜度提示").isNull();
    assertThat(converged.dataAsOf()).isNull();
  }

  @Test
  void degradedReadWithoutAnyCompleteSnapshotIsRefusedInsteadOfServingMixedView() {
    upsertPublicationState("READY", false, null);
    insertUnpublishedTarget();

    AtomicInteger degradedBuilds = new AtomicInteger();
    assertThatThrownBy(() -> read(degradedBuilds))
        .as("没有可退回的完整发布点时不得以进行中的混合视图冒充完整产出")
        .isInstanceOf(BizException.class)
        .hasMessageContaining("尚未收敛到最新变化版本")
        .hasMessageContaining("1 项待更新");
    assertThat(degradedBuilds).as("拒绝时不得开始计算").hasValue(0);
    assertThat(readySnapshotCount()).isZero();

    deleteUnpublishedTarget();
    AtomicInteger convergedBuilds = new AtomicInteger();
    StatisticBoardResponse converged = read(convergedBuilds);
    assertThat(converged.pendingUpdates()).isNull();
    assertThat(convergedBuilds).hasValue(1);
    assertThat(readySnapshotCount()).isOne();
  }

  private StatisticBoardResponse read(AtomicInteger builds) {
    return read(builds, () -> {
      builds.incrementAndGet();
      return emptyBoardResponse();
    });
  }

  private StatisticBoardResponse read(
      AtomicInteger builds, java.util.function.Supplier<StatisticBoardResponse> supplier) {
    return snapshotService.readOrRefresh(
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
