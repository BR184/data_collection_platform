package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 用真实迁移约束验证首次手工重建意图及已有镜像身份，避免简化 DDL 漏掉生产非空/外键约束。 */
class ManualFactRebuildPublicationIntentIntegrationTest {
  private static final long CONFIG_ID = 9900L;
  private static final String SOURCE_INSTANCE = "default";
  private static final String PENDING_MIRROR_CONSTRAINT = "ck_source_fact_publication_pending_mirror";
  private static PostgresIntegrationTestDatabase database;
  private static JdbcTemplate jdbc;
  private static Map<String, Object> baselineBeforeMigration;

  private GitlabSyncConfig config;
  private SyncFactPublicationStateService service;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("manual_rebuild_publication_test");
    jdbc = new JdbcTemplate(database.dataSource());
    Flyway.configure().dataSource(database.dataSource()).locations("classpath:db/migration")
        .target("20261013.12").load().migrate();
    insertConfig(9001L, "upgrade-baseline");
    insertMirrorRun(600L, 9001L, "upgrade-baseline");
    jdbc.update("""
        insert into source_fact_publication_states(config_id, source_instance, fact_type,
            latest_mirror_run_id, ready_mirror_run_id, readiness_status, full_request_revision)
        values (9001, 'upgrade-baseline', 'ISSUE', 600, 600, 'READY', 5)
        """);
    baselineBeforeMigration = jdbc.queryForMap(
        "select * from source_fact_publication_states where config_id = 9001");
    Flyway.configure().dataSource(database.dataSource()).locations("classpath:db/migration").load().migrate();
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeEach
  void setUp() {
    jdbc.update("delete from source_fact_publication_states where config_id = ?", CONFIG_ID);
    jdbc.update("delete from sync_runs where config_id = ?", CONFIG_ID);
    insertConfig(CONFIG_ID, SOURCE_INSTANCE);
    config = new GitlabSyncConfig();
    config.setId(CONFIG_ID);
    config.setSourceInstance(SOURCE_INSTANCE);
    SyncRunAuthoritativeScopeRepository scopes = mock(SyncRunAuthoritativeScopeRepository.class);
    when(scopes.selectedSourceTables(514L)).thenReturn(Set.of());
    service = new SyncFactPublicationStateService(jdbc, scopes);
  }

  @Test
  void firstRequestRegistersBlockedIntentWithoutInventingMirrorIdentity() {
    assertThat(service.requestFullPublication(config)).isEqualTo(FactType.values().length);

    List<Map<String, Object>> states = states();
    assertThat(states).hasSize(FactType.values().length);
    for (Map<String, Object> state : states) {
      assertThat(state).containsEntry("readiness_status", "BLOCKED")
          .containsEntry("full_publication_requested", true)
          .containsEntry("full_request_revision", 1L)
          .containsEntry("latest_mirror_run_id", null)
          .containsEntry("ready_mirror_run_id", null);
    }
    for (FactType factType : FactType.values()) {
      assertThat(service.isReady(SOURCE_INSTANCE, factType)).isFalse();
    }
  }

  @Test
  void existingReadyIdentityAndReadinessSurviveRequest() {
    insertMirrorRun(514L, CONFIG_ID, SOURCE_INSTANCE);
    jdbc.update("""
        insert into source_fact_publication_states(config_id, source_instance, fact_type,
            latest_mirror_run_id, ready_mirror_run_id, readiness_status, full_request_revision, error_message)
        values (?, ?, 'ISSUE', 514, 514, 'READY', 7, '原记录说明')
        """, CONFIG_ID, SOURCE_INSTANCE);

    service.requestFullPublication(config);

    assertThat(issueState()).containsEntry("latest_mirror_run_id", 514L)
        .containsEntry("ready_mirror_run_id", 514L)
        .containsEntry("readiness_status", "READY")
        .containsEntry("full_publication_requested", true)
        .containsEntry("full_request_revision", 8L)
        .containsEntry("error_message", "原记录说明");
  }

  @Test
  void repeatedRequestsKeepOneRowPerFamilyAndAdvanceIntentRevision() {
    service.requestFullPublication(config);
    service.requestFullPublication(config);

    assertThat(states()).hasSize(FactType.values().length)
        .allSatisfy(state -> assertThat(state).containsEntry("full_request_revision", 2L));
  }

  @Test
  void mirrorCompletionFillsRealIdentityWithoutClearingUnsettledIntent() {
    service.requestFullPublication(config);
    insertMirrorRun(514L, CONFIG_ID, SOURCE_INSTANCE);

    service.recordMirrorCompletion(config, 514L, SyncRunStatus.SUCCESS, false);

    assertThat(states()).allSatisfy(state -> assertThat(state)
        .containsEntry("latest_mirror_run_id", 514L)
        .containsEntry("readiness_status", "BLOCKED")
        .containsEntry("full_publication_requested", true)
        .containsEntry("full_request_revision", 1L));
  }

  @Test
  void readyStateWithoutRealMirrorIdentityIsRejected() {
    assertThatThrownBy(() -> jdbc.update("""
        insert into source_fact_publication_states(config_id, source_instance, fact_type,
            readiness_status, full_publication_requested)
        values (?, ?, 'ISSUE', 'READY', true)
        """, CONFIG_ID, SOURCE_INSTANCE)).rootCause().hasMessageContaining(PENDING_MIRROR_CONSTRAINT);
  }

  @Test
  void blockedStateWithoutIntentCannotOmitMirrorIdentity() {
    assertThatThrownBy(() -> jdbc.update("""
        insert into source_fact_publication_states(config_id, source_instance, fact_type,
            readiness_status, full_publication_requested)
        values (?, ?, 'ISSUE', 'BLOCKED', false)
        """, CONFIG_ID, SOURCE_INSTANCE)).rootCause().hasMessageContaining(PENDING_MIRROR_CONSTRAINT);
  }

  @Test
  void inventedMirrorRunIdStillViolatesTheForeignKey() {
    assertThatThrownBy(() -> jdbc.update("""
        insert into source_fact_publication_states(config_id, source_instance, fact_type,
            latest_mirror_run_id, readiness_status, full_publication_requested)
        values (?, ?, 'ISSUE', 999999, 'BLOCKED', true)
        """, CONFIG_ID, SOURCE_INSTANCE)).rootCause().hasMessageContaining("foreign key constraint");
  }

  @Test
  void pendingIntentWithoutMirrorEvidenceCannotBeSettled() {
    service.requestFullPublication(config);

    assertThatThrownBy(() -> service.settleFullPublicationIntent(SOURCE_INSTANCE, FactType.ISSUE, 1L))
        .rootCause().hasMessageContaining(PENDING_MIRROR_CONSTRAINT);
    assertThat(issueState()).containsEntry("full_publication_requested", true)
        .containsEntry("latest_mirror_run_id", null);
  }

  @Test
  void forwardMigrationPreservesExistingPublicationAndDependencyConstraint() {
    assertThat(jdbc.queryForMap("select * from source_fact_publication_states where config_id = 9001"))
        .isEqualTo(baselineBeforeMigration);
    assertThat(jdbc.queryForObject("""
        select is_nullable from information_schema.columns
         where table_schema = 'public' and table_name = 'source_fact_dependency_states'
           and column_name = 'latest_mirror_run_id'
        """, String.class)).isEqualTo("NO");
  }

  private List<Map<String, Object>> states() {
    return jdbc.queryForList("select * from source_fact_publication_states where config_id = ?", CONFIG_ID);
  }

  private Map<String, Object> issueState() {
    return jdbc.queryForMap("""
        select * from source_fact_publication_states where config_id = ? and fact_type = 'ISSUE'
        """, CONFIG_ID);
  }

  private static void insertConfig(long id, String sourceInstance) {
    jdbc.update("""
        insert into gitlab_sync_configs(id, name, source_instance, db_name, db_username, db_password)
        values (?, ?, ?, 'fixture', 'fixture', 'fixture') on conflict (id) do nothing
        """, id, sourceInstance, sourceInstance);
  }

  private static void insertMirrorRun(long runId, long configId, String sourceInstance) {
    jdbc.update("""
        insert into sync_runs(id, run_id, config_id, source_instance, run_type, trigger_type, status, exclusive_scope)
        values (?, ?, ?, ?, 'INCREMENTAL_SYNC', 'SCHEDULE', 'SUCCESS', ?)
        """, runId, "manual-rebuild-mirror-" + runId, configId, sourceInstance, "source:" + sourceInstance + ":mirror");
  }
}
