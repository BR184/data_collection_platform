package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.SourceCursorStrategy;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.TableWhitelistOption;
import com.data.collection.platform.entity.WhitelistMode;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.mapper.SyncRunMapper;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import com.data.collection.platform.service.sync.SyncRunAuthoritativeScopeRepository;
import com.data.collection.platform.service.sync.SyncRunCompletionEvent;
import com.data.collection.platform.service.sync.SyncRunFactPublicationCoordinator;
import com.data.collection.platform.service.sync.SyncRunTableWorkerService;
import com.data.collection.platform.service.sync.SyncRunWorkerService;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class IncrementalDeleteTargetedPublicationIntegrationTest {
  private static final long ISSUE_ID = 9031L;
  private static final long PROJECT_ID = 9L;
  private static final long SEVERITY_LABEL_ID = 31L;
  private static final long STATUS_LABEL_ID = 32L;
  private static final long SEVERITY_LINK_ID = 7001L;
  private static final long STATUS_LINK_ID = 7002L;

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DataSourceProperties dataSourceProperties;
  @Autowired private GitlabConfigService configService;
  @Autowired private GitlabMirrorSchemaService mirrorSchemaService;
  @Autowired private GitlabMirrorTableStorageService mirrorStorageService;
  @Autowired private FactBuildService factBuildService;
  @Autowired private SyncRunTableWorkerService tableWorkerService;
  @Autowired private SyncRunWorkerService runWorkerService;
  @Autowired private SyncRunMapper syncRunMapper;
  @Autowired private SyncRunAuthoritativeScopeRepository authoritativeScopeRepository;
  @Autowired private SyncFactPublicationStateService publicationStateService;
  @Autowired private SyncRunFactPublicationCoordinator publicationCoordinator;

  @BeforeEach
  void setUp() {
    GitlabIssueMirrorFixture.ensureSchema(jdbcTemplate);
    cleanPlatformState();
    jdbcTemplate.execute("drop table if exists public.label_links");
    jdbcTemplate.execute(
        """
        create table public.label_links (
          id bigint primary key,
          label_id bigint not null,
          target_id bigint not null,
          target_type varchar(64) not null,
          created_at timestamp,
          updated_at timestamp
        )
        """);
    insertGitlabLabelLink(SEVERITY_LINK_ID, SEVERITY_LABEL_ID);
    insertGitlabLabelLink(STATUS_LINK_ID, STATUS_LABEL_ID);
  }

  @AfterEach
  void dropGitlabSourceTable() {
    jdbcTemplate.execute("drop table if exists public.label_links");
    cleanPlatformState();
  }

  @Test
  void test_delete_reconciliation_clears_last_labels_and_publishes_latest_issue_fact() {
    GitlabSyncConfig config = prepareMirrorState();
    establishReadyIssueGeneration(config);
    SyncRunType runType = SyncRunType.DELETE_RECONCILIATION;

    assertThat(issueSeverity()).isEqualTo("LEVEL1");
    assertThat(levelOneIssueCount()).isOne();

    long mirrorRunId = insertMirrorRun(config.getId(), runType);
    authoritativeScopeRepository.snapshotSelectedSourceTables(
        mirrorRunId, List.of("label_links"));
    long stateId = insertTableState(config.getId());
    insertReconciliationTask(mirrorRunId, config.getId(), stateId, runType);
    jdbcTemplate.update(
        """
        insert into fact_projection_generations(
            source_instance, fact_type, scope_type, scope_key, generation)
        values ('default', 'ISSUE', 'PROJECT', '999', 7)
        """);

    jdbcTemplate.update(
        "delete from public.label_links where id in (?, ?)",
        SEVERITY_LINK_ID,
        STATUS_LINK_ID);

    SyncRun mirrorRun = syncRunMapper.selectById(mirrorRunId);
    SyncRunTableWorkerService.DrainResult drainResult =
        tableWorkerService.drainRunTasks(mirrorRun, 1);

    assertThat(drainResult.yielded()).isFalse();
    assertThat(drainResult.processedTasks()).isEqualTo(3);
    assertReconciliationUsedOneDurableTask(mirrorRunId, 2L);
    assertThat(
            jdbcTemplate.queryForObject(
                "select mirror_deleted from ods_gitlab_label_links where id = ?",
                Boolean.class,
                SEVERITY_LINK_ID))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select mirror_deleted from ods_gitlab_label_links where id = ?",
                Boolean.class,
                STATUS_LINK_ID))
        .isTrue();

    finishMirrorRun(mirrorRunId);
    publicationCoordinator.onMirrorCompleted(
        new SyncRunCompletionEvent(
            mirrorRunId,
            config.getId(),
            "default",
            runType,
            SyncRunStatus.SUCCESS,
            2L));
    long factRunId = factRunId(config.getId());
    startFactRun(factRunId);
    runWorkerService.executeRun(syncRunMapper.selectById(factRunId));

    assertThat(issueSeverity()).isNull();
    assertThat(levelOneIssueCount()).isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from issue_fact where source_instance = 'default' and issue_id = ?",
                Integer.class,
                ISSUE_ID))
        .isOne();
    assertTargetPublished(mirrorRunId);
    assertTargetedProjectionPublication(factRunId);
    assertThat(
            jdbcTemplate.queryForObject(
                "select status from sync_runs where id = ?", String.class, factRunId))
        .isEqualTo("SUCCESS");
  }

  @Test
  void test_delete_reconciliation_without_deletion_only_advances_table_freshness() {
    GitlabSyncConfig config = prepareMirrorState();
    establishReadyIssueGeneration(config);
    long mirrorRunId = insertMirrorRun(config.getId(), SyncRunType.DELETE_RECONCILIATION);
    authoritativeScopeRepository.snapshotSelectedSourceTables(
        mirrorRunId, List.of("label_links"));
    long stateId = insertTableState(config.getId());
    insertReconciliationTask(
        mirrorRunId,
        config.getId(),
        stateId,
        SyncRunType.DELETE_RECONCILIATION);

    SyncRunTableWorkerService.DrainResult drainResult =
        tableWorkerService.drainRunTasks(syncRunMapper.selectById(mirrorRunId), 1);

    assertThat(drainResult.yielded()).isFalse();
    assertThat(drainResult.processedTasks()).isEqualTo(3);
    assertReconciliationUsedOneDurableTask(mirrorRunId, 0L);
    assertThat(
            jdbcTemplate.queryForObject(
                "select last_delete_reconciled_at is not null from sync_run_table_states where id = ?",
                Boolean.class,
                stateId))
        .isTrue();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from sync_run_fact_targets where mirror_run_id = ?",
                Integer.class,
                mirrorRunId))
        .isZero();
    finishMirrorRun(mirrorRunId);
    publicationCoordinator.onMirrorCompleted(
        new SyncRunCompletionEvent(
            mirrorRunId,
            config.getId(),
            "default",
            SyncRunType.DELETE_RECONCILIATION,
            SyncRunStatus.SUCCESS,
            0L));
    assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*) from sync_runs
                 where config_id = ? and source_instance = 'default'
                   and run_type = 'FACT_REFRESH'
                """,
                Integer.class,
                config.getId()))
        .isZero();
  }

  private void establishReadyIssueGeneration(GitlabSyncConfig config) {
    long baselineRunId = insertMirrorRun(config.getId(), SyncRunType.INCREMENTAL_SYNC);
    authoritativeScopeRepository.snapshotSelectedSourceTables(
        baselineRunId,
        publicationStateService.requiredTables(config, FactType.ISSUE));
    finishMirrorRun(baselineRunId);

    assertThat(
            publicationStateService.recordMirrorCompletion(
                config, baselineRunId, SyncRunStatus.SUCCESS, false))
        .isFalse();
  }

  private GitlabSyncConfig prepareMirrorState() {
    GitlabSyncConfig config = configService.saveConfig(sourceConfig());
    TableWhitelistOption labelLinks =
        new TableWhitelistOption(
            "label_links",
            "Label links",
            "id",
            "updated_at",
            SourceCursorStrategy.TIMESTAMP_KEYSET,
            true);
    GitlabMirrorSchemaService.PreparedMirrorTable mirrorTable =
        mirrorSchemaService.prepareMirrorTable(config, labelLinks);
    LocalDateTime sourceTime = LocalDateTime.of(2026, 7, 31, 9, 0);
    mirrorStorageService.applyBatch(
        mirrorTable.mirrorSchema(),
        List.of(
            labelLink(SEVERITY_LINK_ID, SEVERITY_LABEL_ID, sourceTime),
            labelLink(STATUS_LINK_ID, STATUS_LABEL_ID, sourceTime)),
        null);
    seedIssueFacts(sourceTime);
    return config;
  }

  private void seedIssueFacts(LocalDateTime sourceTime) {
    jdbcTemplate.update(
        "insert into ods_gitlab_projects(id, name, mirror_deleted) values (?, ?, false)",
        PROJECT_ID,
        "CrownCAD");
    jdbcTemplate.update(
        "insert into ods_gitlab_users(id, name, mirror_deleted) values (?, ?, false)",
        509L,
        "reporter-delete-reconcile");
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issues(
          id, iid, project_id, title, author_id, created_at, updated_at,
          closed_at, state_id, milestone_id, mirror_deleted)
        values (?, ?, ?, ?, ?, ?, ?, null, 1, null, false)
        """,
        ISSUE_ID,
        32129L,
        PROJECT_ID,
        "physical delete targeted publication",
        509L,
        sourceTime.minusHours(1),
        sourceTime);
    jdbcTemplate.update(
        "insert into ods_gitlab_labels(id, title, mirror_deleted) values (?, ?, false)",
        SEVERITY_LABEL_ID,
        "一级缺陷");
    jdbcTemplate.update(
        "insert into ods_gitlab_labels(id, title, mirror_deleted) values (?, ?, false)",
        STATUS_LABEL_ID,
        "状态：未修复");
    factBuildService.rebuildIssueFactsByRootIds("default", List.of(ISSUE_ID));
  }

  private GitlabSyncConfig sourceConfig() {
    DatabaseEndpoint endpoint = databaseEndpoint();
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setName("physical-delete-e2e");
    config.setEnabled(true);
    config.setSourceEnabled(true);
    config.setSourceInstance("default");
    config.setAutoSyncEnabled(true);
    config.setSourceMode(SourceMode.DIRECT);
    config.setWhitelistMode(WhitelistMode.RECOMMENDED);
    config.setWhitelistTables(List.of());
    config.setDbHost(endpoint.host());
    config.setDbPort(endpoint.port());
    config.setDbName(endpoint.database());
    config.setDbUsername(dataSourceProperties.determineUsername());
    config.setDbPassword(dataSourceProperties.determinePassword());
    config.setCompensationIntervalMinutes(60);
    return config;
  }

  private Map<String, Object> labelLink(long id, long labelId, LocalDateTime sourceTime) {
    return Map.of(
        "id", id,
        "label_id", labelId,
        "target_id", ISSUE_ID,
        "target_type", "Issue",
        "created_at", sourceTime.minusMinutes(5),
        "updated_at", sourceTime);
  }

  private void insertGitlabLabelLink(long id, long labelId) {
    jdbcTemplate.update(
        """
        insert into public.label_links(
            id, label_id, target_id, target_type, created_at, updated_at)
        values (?, ?, ?, 'Issue', timestamp '2026-07-31 08:55:00', timestamp '2026-07-31 09:00:00')
        """,
        id,
        labelId,
        ISSUE_ID);
  }

  private long insertMirrorRun(Long configId, SyncRunType runType) {
    String triggerType = runType == SyncRunType.TABLE_REFRESH ? "MANUAL" : "SCHEDULE";
    Long runId =
        jdbcTemplate.queryForObject(
            """
            insert into sync_runs(
                run_id, config_id, source_instance, run_type, trigger_type,
                status, priority, exclusive_scope, payload_json,
                resolved_worker_count, lease_owner, lease_until, started_at)
            values (?, ?, 'default', ?, ?, 'RUNNING', 100, 'gitlab:default', '{}',
                    1, 'mirror-e2e', current_timestamp + interval '1 hour', current_timestamp)
            returning id
            """,
            Long.class,
            "physical-delete-" + runType.name().toLowerCase(),
            configId,
            runType.name(),
            triggerType);
    return requireId(runId, "镜像运行");
  }

  private long insertTableState(Long configId) {
    Long stateId =
        jdbcTemplate.queryForObject(
            """
            insert into sync_run_table_states(
                config_id, source_instance, source_table, mirror_table,
                primary_key_columns, updated_at_column, row_strategy,
                cursor_strategy, sync_enabled)
            values (?, 'default', 'label_links', 'ods_gitlab_label_links',
                    'id', 'updated_at', 'INCREMENTAL', 'TIMESTAMP_KEYSET', true)
            returning id
            """,
            Long.class,
            configId);
    return requireId(stateId, "同步表状态");
  }

  private void insertReconciliationTask(
      long mirrorRunId, Long configId, long stateId, SyncRunType runType) {
    jdbcTemplate.update(
        """
        insert into sync_run_table_tasks(
            run_id, config_id, state_id, source_instance, source_table, mirror_table,
            task_type, status, row_strategy, task_stage, batch_size,
            run_after, retry_count, max_retry_count, rows_scanned, rows_applied)
        values (?, ?, ?, 'default', 'label_links', 'ods_gitlab_label_links',
                ?, 'QUEUED', 'INCREMENTAL', 'RECONCILE', 1,
                current_timestamp, 0, 3, 0, 0)
        """,
        mirrorRunId,
        configId,
        stateId,
        runType.name());
  }

  private void assertReconciliationUsedOneDurableTask(
      long mirrorRunId, long expectedAppliedRows) {
    assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*) from sync_run_table_tasks
                 where run_id = ? and task_stage = 'RECONCILE'
                """,
                Integer.class,
                mirrorRunId))
        .isOne();
    Map<String, Object> task =
        jdbcTemplate.queryForMap(
            """
            select status, page_number, rows_scanned, rows_applied
              from sync_run_table_tasks
             where run_id = ? and task_stage = 'RECONCILE'
            """,
            mirrorRunId);
    assertThat(task)
        .containsEntry("status", "SUCCESS")
        .containsEntry("page_number", 3)
        .containsEntry("rows_scanned", 2L)
        .containsEntry("rows_applied", expectedAppliedRows);
  }

  private void finishMirrorRun(long mirrorRunId) {
    jdbcTemplate.update(
        """
        update sync_runs
           set status = 'SUCCESS', lease_owner = null, lease_until = null,
               finished_at = current_timestamp, updated_at = current_timestamp
         where id = ?
        """,
        mirrorRunId);
  }

  private long factRunId(long configId) {
    Long factRunId =
        jdbcTemplate.queryForObject(
            """
             select id from sync_runs
              where config_id = ? and source_instance = 'default'
                and run_type = 'FACT_REFRESH' and parent_run_id is null
              order by id desc
              limit 1
             """,
            Long.class,
            configId);
    return requireId(factRunId, "来源级事实运行");
  }

  private void startFactRun(long factRunId) {
    jdbcTemplate.update(
        """
        update sync_runs
           set status = 'RUNNING', lease_owner = 'fact-e2e',
               lease_until = current_timestamp + interval '1 hour',
               started_at = current_timestamp, updated_at = current_timestamp
         where id = ?
        """,
        factRunId);
  }

  private void assertTargetPublished(long mirrorRunId) {
    Map<String, Object> issueTarget =
        jdbcTemplate.queryForMap(
            """
            select publication_status, change_version, published_version
              from sync_run_fact_targets
             where mirror_run_id = ? and fact_type = 'ISSUE' and root_id = ?
            """,
            mirrorRunId,
            ISSUE_ID);
    assertThat(issueTarget).containsEntry("publication_status", "PUBLISHED");
    assertThat(issueTarget.get("change_version"))
        .isEqualTo(issueTarget.get("published_version"));
    assertThat(
            jdbcTemplate.queryForObject(
                """
                select published_version = latest_change_version
                  from fact_change_heads
                 where source_instance = 'default' and fact_type = 'ISSUE' and root_id = ?
                """,
                Boolean.class,
                ISSUE_ID))
        .isTrue();
  }

  private void assertTargetedProjectionPublication(long factRunId) {
    assertThat(
            jdbcTemplate.queryForList(
                """
                select scope_type || ':' || scope_key
                  from fact_projection_generations
                 where source_instance = 'default' and fact_type = 'ISSUE'
                 order by scope_type, scope_key
                """,
                String.class))
        .contains("GLOBAL_VIEW:*", "PROJECT:9", "PROJECT:999")
        .doesNotContain("FULL_EPOCH:*");
    assertThat(
            jdbcTemplate.queryForObject(
                """
                select generation from fact_projection_generations
                 where source_instance = 'default' and fact_type = 'ISSUE'
                   and scope_type = 'PROJECT' and scope_key = '999'
                """,
                Long.class))
        .isEqualTo(7L);
    assertThat(
            jdbcTemplate.queryForList(
                """
                select scope_type || ':' || scope_key || ':' || status
                  from fact_projection_refresh_tasks
                 where fact_run_id = ? and fact_type = 'ISSUE'
                 order by scope_type, scope_key
                """,
                String.class,
                factRunId))
        .containsExactly("GLOBAL_VIEW:*:SUCCESS", "PROJECT:9:SUCCESS");
    assertThat(
        jdbcTemplate.queryForObject(
                "select count(*) from fact_build_tasks where run_id = ? and full_build = true",
                Integer.class,
                String.valueOf(factRunId)))
        .isZero();
  }

  private String issueSeverity() {
    return jdbcTemplate.queryForObject(
        "select severity_level from issue_fact where source_instance = 'default' and issue_id = ?",
        String.class,
        ISSUE_ID);
  }

  private int levelOneIssueCount() {
    Integer count =
        jdbcTemplate.queryForObject(
            """
            select count(*) from issue_fact
             where source_instance = 'default' and severity_level = 'LEVEL1'
            """,
            Integer.class);
    return count == null ? 0 : count;
  }

  private long requireId(Long id, String subject) {
    if (id == null || id <= 0L) {
      throw new IllegalStateException(subject + "创建失败");
    }
    return id;
  }

  private DatabaseEndpoint databaseEndpoint() {
    String jdbcUrl = dataSourceProperties.determineUrl();
    if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
      throw new IllegalStateException("物理删除集成测试要求标准 PostgreSQL JDBC URL");
    }
    URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
    String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
    if (uri.getHost() == null || database.isBlank()) {
      throw new IllegalStateException("无法从测试 JDBC URL 解析 PostgreSQL 地址");
    }
    return new DatabaseEndpoint(uri.getHost(), uri.getPort() < 0 ? 5432 : uri.getPort(), database);
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
    jdbcTemplate.update("delete from ods_gitlab_issue_assignees");
    jdbcTemplate.update("delete from ods_gitlab_labels");
    jdbcTemplate.update("delete from ods_gitlab_notes");
    jdbcTemplate.update("delete from ods_gitlab_issues");
    jdbcTemplate.update("delete from ods_gitlab_milestones");
    jdbcTemplate.update("delete from ods_gitlab_users");
    jdbcTemplate.update("delete from ods_gitlab_projects");
  }

  private record DatabaseEndpoint(String host, int port, String database) {}
}
