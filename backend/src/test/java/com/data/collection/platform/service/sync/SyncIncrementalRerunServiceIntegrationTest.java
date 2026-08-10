package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.data.collection.platform.entity.SyncTriggerType;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SyncIncrementalRerunServiceIntegrationTest {
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneId.of("Asia/Shanghai"));
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate transactionTemplate;
  private SyncRunMapper syncRunMapper;
  private SyncIncrementalRerunService service;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("incremental_rerun_test");
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeEach
  void setUp() {
    DataSource dataSource = database.dataSource();
    jdbcTemplate = new JdbcTemplate(dataSource);
    transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    resetSchema();
    syncRunMapper = mock(SyncRunMapper.class);
    AtomicLong ids = new AtomicLong(900L);
    org.mockito.Mockito.when(syncRunMapper.insert(any(SyncRun.class)))
        .thenAnswer(
            invocation -> {
              SyncRun run = invocation.getArgument(0);
              run.setId(ids.incrementAndGet());
              return 1;
            });
    service = new SyncIncrementalRerunService(jdbcTemplate, syncRunMapper, FIXED_CLOCK);
  }

  @Test
  void test_multiple_triggers_merge_into_exactly_one_tail_rerun() {
    SyncRun activeRun = activeRun();
    transactionTemplate.executeWithoutResult(
        ignored -> {
          service.requestRerun(activeRun, SyncTriggerType.SCHEDULE, "first due trigger");
          service.requestRerun(activeRun, SyncTriggerType.MANUAL, "manual trigger");
        });

    assertThat(rerunTriggerCount()).isEqualTo(2);
    assertThat(rerunRequestedAt()).isNotNull();
    assertThat(
            jdbcTemplate.queryForList(
                """
                select payload_json ->> 'triggerCount' as trigger_count,
                       payload_json ->> 'triggerType' as trigger_type
                  from sync_run_events
                 where event_type = 'RERUN_REQUESTED'
                 order by id
                """))
        .extracting(
            row -> row.get("trigger_count"),
            row -> row.get("trigger_type"))
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("1", "SCHEDULE"),
            org.assertj.core.groups.Tuple.tuple("2", "MANUAL"));

    SyncRun completedRun = activeRun();
    completedRun.setStatus(SyncRunStatus.FAILED);
    SyncRun rerun =
        transactionTemplate.execute(ignored -> service.enqueuePendingRerun(completedRun));

    assertThat(rerun).isNotNull();
    assertThat(rerun.getStatus()).isEqualTo(SyncRunStatus.QUEUED);
    assertThat(rerun.getRunAfter()).isEqualTo(LocalDateTime.of(2026, 8, 5, 20, 0));
    assertThat(rerunTriggerCount()).isZero();
    assertThat(rerunRequestedAt()).isNull();
    SyncRun noSecondRerun =
        transactionTemplate.execute(
            ignored -> service.enqueuePendingRerun(completedRun));
    assertThat(noSecondRerun).isNull();
    verify(syncRunMapper, times(1)).insert(any(SyncRun.class));
  }

  @Test
  void test_failed_completion_transaction_keeps_pending_for_recovery() {
    SyncRun run = activeRun();
    transactionTemplate.executeWithoutResult(
        ignored -> service.requestRerun(run, SyncTriggerType.SCHEDULE, "scheduled"));

    assertThatThrownBy(
            () ->
                transactionTemplate.executeWithoutResult(
                    ignored -> {
                      service.enqueuePendingRerun(run);
                      throw new IllegalStateException("simulated completion rollback");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated completion rollback");

    assertThat(rerunTriggerCount()).isEqualTo(1);
    assertThat(rerunRequestedAt()).isNotNull();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from sync_run_events where event_type = 'RERUN_QUEUED'",
                Integer.class))
        .isZero();
  }

  @Test
  void test_new_incremental_adopts_orphan_pending_without_queuing_another_run() {
    SyncRun timedOutRun = activeRun();
    transactionTemplate.executeWithoutResult(
        ignored -> service.requestRerun(timedOutRun, SyncTriggerType.SCHEDULE, "scheduled"));
    SyncRun queuedRun = activeRun();
    queuedRun.setId(202L);
    queuedRun.setStatus(SyncRunStatus.QUEUED);

    Boolean adopted =
        transactionTemplate.execute(ignored -> service.adoptPendingRerun(queuedRun));

    assertThat(adopted).isTrue();
    assertThat(rerunTriggerCount()).isZero();
    assertThat(rerunRequestedAt()).isNull();
    assertThat(
            jdbcTemplate.queryForMap(
                """
                select run_id, payload_json ->> 'rerunId' as rerun_id,
                       payload_json ->> 'recovered' as recovered
                  from sync_run_events
                 where event_type = 'RERUN_QUEUED'
                """))
        .containsEntry("run_id", 202L)
        .containsEntry("rerun_id", "202")
        .containsEntry("recovered", "true");
    verify(syncRunMapper, times(0)).insert(any(SyncRun.class));
  }

  private SyncRun activeRun() {
    SyncRun run = new SyncRun();
    run.setId(101L);
    run.setConfigId(1L);
    run.setSourceInstance("default");
    run.setRunType(SyncRunType.INCREMENTAL_SYNC);
    run.setStatus(SyncRunStatus.RUNNING);
    run.setPriority(90);
    run.setExclusiveScope("source:1:default:mirror");
    run.setResolvedWorkerCount(2);
    return run;
  }

  private Integer rerunTriggerCount() {
    return jdbcTemplate.queryForObject(
        "select incremental_rerun_trigger_count from gitlab_sync_configs where id = 1",
        Integer.class);
  }

  private LocalDateTime rerunRequestedAt() {
    return jdbcTemplate.queryForObject(
        "select incremental_rerun_requested_at from gitlab_sync_configs where id = 1",
        LocalDateTime.class);
  }

  private void resetSchema() {
    jdbcTemplate.execute("drop table if exists sync_run_events");
    jdbcTemplate.execute("drop table if exists gitlab_sync_configs");
    jdbcTemplate.execute(
        """
        create table gitlab_sync_configs (
          id bigint primary key,
          incremental_rerun_requested_at timestamp,
          incremental_rerun_trigger_count integer not null default 0,
          updated_at timestamp
        )
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_events (
          id bigserial primary key,
          run_id bigint not null,
          config_id bigint not null,
          source_instance varchar(64),
          event_type varchar(64) not null,
          message text,
          payload_json jsonb,
          created_at timestamp not null
        )
        """);
    jdbcTemplate.update("insert into gitlab_sync_configs(id) values (1)");
  }
}
