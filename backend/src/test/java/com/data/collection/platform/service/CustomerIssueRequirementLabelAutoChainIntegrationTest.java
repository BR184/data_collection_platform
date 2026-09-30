package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.GitlabSyncConfig;
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
import java.sql.Timestamp;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * S02 自动增量链证据：客户需求的标签身份变化不经手工全量重建即可生效。
 *
 * <p>每个场景都从真实来源表变化开始，依次走过镜像扫描提交（变更登记）、来源血缘根解析、
 * 定向事实发布与投影代际推进，全部由同步执行器驱动；测试全程不调用 {@code rebuildIssueFacts(true)}，
 * 也不手工改写 {@code issue_fact} 的派生列。覆盖标签新增、删除、改名与归属变化四类。
 *
 * <p>唯一的非自动步骤是基线播种：项目/议题 ODS 行与首轮事实由 {@code rebuildIssueFactsByRootIds}
 * 建立，等价于“上一次同步已完成”的既有状态；被验证的变化一律不走该入口。
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
      "platform.auth.secure-config-required=false"
    })
class CustomerIssueRequirementLabelAutoChainIntegrationTest {
  private static final long CUSTOMER_PROJECT_ID = 325L;
  private static final String CUSTOMER_PROJECT_NAME = "CC_PRODUCT";
  private static final long ISSUE_A = 91001L;
  private static final long ISSUE_B = 91002L;
  private static final long REQUIREMENT_LABEL_ID = 401L;
  private static final long SUGGESTION_LABEL_ID = 402L;
  private static final long LINK_ID = 501L;
  private static final long FIXED_LABEL_ID = 403L;
  private static final long FIXED_LINK_ID = 504L;
  private static final long FIXED_EVENT_ID = 901L;
  private static final String SOURCE_INSTANCE = "default";
  private static final String LABELS_TABLE = "labels";
  private static final String LABEL_LINKS_TABLE = "label_links";
  private static final String LABEL_EVENTS_TABLE = "resource_label_events";
  private static final int LABEL_ACTION_ADD = 1;
  private static final int LABEL_ACTION_REMOVE = 2;
  private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 9, 1, 9, 0);
  private static final int STEP_MINUTES = 10;

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
  @Autowired private FactBuildService factBuildService;
  @Autowired private SyncRunTableWorkerService tableWorkerService;
  @Autowired private SyncRunWorkerService runWorkerService;
  @Autowired private SyncRunMapper syncRunMapper;
  @Autowired private SyncRunAuthoritativeScopeRepository authoritativeScopeRepository;
  @Autowired private SyncFactPublicationStateService publicationStateService;
  @Autowired private SyncRunFactPublicationCoordinator publicationCoordinator;

  private GitlabSyncConfig config;
  private long labelsStateId;
  private long labelLinksStateId;
  private long labelEventsStateId;
  private int step;
  private int runSequence;
  private long lastFactRunId;

  @BeforeEach
  void setUp() {
    GitlabIssueMirrorFixture.ensureSchema(jdbcTemplate);
    cleanPlatformState();
    step = 0;
    runSequence = 0;
    createSourceTables();
    config = configService.saveConfig(sourceConfig());
    prepareLabelMirrorTables();
    insertCustomerIssueRows();
    establishVerifiedLabelEventHistory();
    establishReadyIssueGeneration();
    factBuildService.rebuildIssueFactsByRootIds(
        SOURCE_INSTANCE, List.of(ISSUE_A, ISSUE_B));
    assertThat(requirementFlag(ISSUE_A)).isFalse();
    assertThat(requirementFlag(ISSUE_B)).isFalse();
  }

  @AfterEach
  void tearDown() {
    dropSourceTables();
    cleanPlatformState();
  }

  @Test
  void addedRequirementLabelPublishesCustomerRequirementThroughAutomaticChain() {
    long generationBefore = projectProjectionGeneration();

    insertSourceLabel(REQUIREMENT_LABEL_ID, "需求");
    insertSourceLabelLink(LINK_ID, REQUIREMENT_LABEL_ID, ISSUE_A);
    long mirrorRunId = syncLabelTables();

    assertThat(requirementFlag(ISSUE_A)).isTrue();
    assertThat(requirementFlag(ISSUE_B)).isFalse();
    assertTargetPublished(mirrorRunId, ISSUE_A);
    assertIssueFactNotDuplicated(ISSUE_A);
    assertThat(projectProjectionGeneration()).isGreaterThan(generationBefore);
    assertThat(issueProjectionRefreshScopes())
        .isNotEmpty()
        .allSatisfy(scope -> assertThat(scope).endsWith(":SUCCESS"));
    assertNoFullBuildTaskRequested();
    assertThat(lastFactRunStatus()).isEqualTo("SUCCESS");
  }

  @Test
  void deletedLabelLinkClearsCustomerRequirementThroughAutomaticChain() {
    insertSourceLabel(REQUIREMENT_LABEL_ID, "需求");
    insertSourceLabelLink(LINK_ID, REQUIREMENT_LABEL_ID, ISSUE_A);
    syncLabelTables();
    assertThat(requirementFlag(ISSUE_A)).isTrue();

    jdbcTemplate.update("delete from public.label_links where id = ?", LINK_ID);
    long mirrorRunId = reconcileLabelLinks();

    assertThat(
            jdbcTemplate.queryForObject(
                "select mirror_deleted from ods_gitlab_label_links where id = ?",
                Boolean.class,
                LINK_ID))
        .isTrue();
    assertThat(requirementFlag(ISSUE_A)).isFalse();
    assertTargetPublished(mirrorRunId, ISSUE_A);
    assertIssueFactNotDuplicated(ISSUE_A);
    assertNoFullBuildTaskRequested();
  }

  @Test
  void renamedLabelRepublishesLinkedIssueRootsThroughAutomaticChain() {
    insertSourceLabel(REQUIREMENT_LABEL_ID, "需求");
    insertSourceLabelLink(LINK_ID, REQUIREMENT_LABEL_ID, ISSUE_A);
    syncLabelTables();
    assertThat(requirementFlag(ISSUE_A)).isTrue();

    // 改名到非目标标签：需求身份必须退出，根由标签反向解析到议题。
    renameSourceLabel(REQUIREMENT_LABEL_ID, "产品建议");
    long renamedOutRunId = syncTables(List.of(LABELS_TABLE));
    assertThat(requirementFlag(ISSUE_A)).isFalse();
    assertTargetPublished(renamedOutRunId, ISSUE_A);

    // 再改名回目标标签：需求身份必须恢复，且仍不产生全量重建。
    renameSourceLabel(REQUIREMENT_LABEL_ID, "需求");
    long renamedBackRunId = syncTables(List.of(LABELS_TABLE));
    assertThat(requirementFlag(ISSUE_A)).isTrue();
    assertTargetPublished(renamedBackRunId, ISSUE_A);
    assertIssueFactNotDuplicated(ISSUE_A);
    assertNoFullBuildTaskRequested();
  }

  @Test
  void movedLabelLinkRepublishesBothIssueRootsThroughAutomaticChain() {
    insertSourceLabel(REQUIREMENT_LABEL_ID, "需求");
    insertSourceLabelLink(LINK_ID, REQUIREMENT_LABEL_ID, ISSUE_A);
    syncLabelTables();
    assertThat(requirementFlag(ISSUE_A)).isTrue();

    // 归属变化：同一条关联换目标议题，旧根与新根都必须重新发布。
    jdbcTemplate.update(
        "update public.label_links set target_id = ?, updated_at = ? where id = ?",
        ISSUE_B,
        nextStepTime(),
        LINK_ID);
    long mirrorRunId = syncTables(List.of(LABEL_LINKS_TABLE));

    assertThat(requirementFlag(ISSUE_A)).isFalse();
    assertThat(requirementFlag(ISSUE_B)).isTrue();
    assertTargetPublished(mirrorRunId, ISSUE_A);
    assertTargetPublished(mirrorRunId, ISSUE_B);
    assertIssueFactNotDuplicated(ISSUE_A);
    assertIssueFactNotDuplicated(ISSUE_B);
    assertNoFullBuildTaskRequested();
  }

  @Test
  void suggestionLabelAndUnrelatedLabelFollowTheSameAutomaticChain() {
    insertSourceLabel(SUGGESTION_LABEL_ID, "类别：建议");
    insertSourceLabel(403L, "模块：草图");
    insertSourceLabelLink(502L, 403L, ISSUE_A);
    syncLabelTables();
    assertThat(requirementFlag(ISSUE_A)).isFalse();

    insertSourceLabelLink(503L, SUGGESTION_LABEL_ID, ISSUE_A);
    syncLabelTables();
    assertThat(requirementFlag(ISSUE_A)).isTrue();

    // 只删除非目标标签，需求身份必须保持不变。
    jdbcTemplate.update("delete from public.label_links where id = ?", 502L);
    reconcileLabelLinks();
    assertThat(requirementFlag(ISSUE_A)).isTrue();
    assertNoFullBuildTaskRequested();
  }

  @Test
  void fixedLabelEventsPublishAddRemoveAndReaddThroughTheAutomaticChain() {
    insertSourceLabel(FIXED_LABEL_ID, "状态：已修复/完成");
    insertSourceLabelLink(FIXED_LINK_ID, FIXED_LABEL_ID, ISSUE_A);
    syncLabelTables();
    assertThat(fixedLabelTime(ISSUE_A)).isNull();

    LocalDateTime firstFixTime = nextStepTime();
    insertSourceLabelEvent(
        FIXED_EVENT_ID, ISSUE_A, FIXED_LABEL_ID, LABEL_ACTION_ADD, firstFixTime);
    long firstEventRunId = syncTables(List.of(LABEL_EVENTS_TABLE));

    assertThat(fixedLabelTime(ISSUE_A)).isEqualTo(firstFixTime);
    assertTargetPublished(firstEventRunId, ISSUE_A);
    assertIssueFactNotDuplicated(ISSUE_A);
    assertNoFullBuildTaskRequested();

    LocalDateTime duplicateFixTime = nextStepTime();
    insertSourceLabelEvent(
        FIXED_EVENT_ID + 1, ISSUE_A, FIXED_LABEL_ID, LABEL_ACTION_ADD, duplicateFixTime);
    long repeatedEventRunId = syncTables(List.of(LABEL_EVENTS_TABLE));

    assertThat(fixedLabelTime(ISSUE_A)).isEqualTo(duplicateFixTime);
    assertTargetPublished(repeatedEventRunId, ISSUE_A);
    assertIssueFactNotDuplicated(ISSUE_A);

    jdbcTemplate.update("delete from public.label_links where id = ?", FIXED_LINK_ID);
    insertSourceLabelEvent(
        FIXED_EVENT_ID + 2,
        ISSUE_A,
        FIXED_LABEL_ID,
        LABEL_ACTION_REMOVE,
        nextStepTime());
    long removalEventRunId = syncTables(List.of(LABEL_EVENTS_TABLE));
    assertTargetPublished(removalEventRunId, ISSUE_A);
    assertThat(fixedLabelTime(ISSUE_A)).isEqualTo(duplicateFixTime);

    long linkRemovalRunId = reconcileLabelLinks();
    assertTargetPublished(linkRemovalRunId, ISSUE_A);
    assertThat(fixedLabelTime(ISSUE_A)).isNull();

    LocalDateTime readdedFixTime = nextStepTime();
    insertSourceLabelLink(FIXED_LINK_ID + 1, FIXED_LABEL_ID, ISSUE_A);
    insertSourceLabelEvent(
        FIXED_EVENT_ID + 3, ISSUE_A, FIXED_LABEL_ID, LABEL_ACTION_ADD, readdedFixTime);
    long readdedEventRunId = syncTables(List.of(LABEL_EVENTS_TABLE));
    assertTargetPublished(readdedEventRunId, ISSUE_A);
    assertThat(fixedLabelTime(ISSUE_A)).isNull();

    long readdedLinkRunId = syncTables(List.of(LABEL_LINKS_TABLE));
    assertTargetPublished(readdedLinkRunId, ISSUE_A);
    assertThat(fixedLabelTime(ISSUE_A)).isEqualTo(readdedFixTime);
    assertIssueFactNotDuplicated(ISSUE_A);
    assertNoFullBuildTaskRequested();

    LocalDateTime lateBackfillTime = nextStepTime();
    insertSourceLabelEvent(850L, ISSUE_A, FIXED_LABEL_ID, LABEL_ACTION_ADD, lateBackfillTime);
    syncTables(List.of(LABEL_EVENTS_TABLE));
    assertThat(fixedLabelTime(ISSUE_A)).isEqualTo(readdedFixTime);

    fullCompensateLabelEvents();
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from ods_gitlab_resource_label_events "
                    + "where id = 850 and mirror_deleted = false",
                Integer.class))
        .isOne();
    assertThat(fixedLabelTime(ISSUE_A)).isEqualTo(lateBackfillTime);

    LocalDateTime correctedExistingEventTime = nextStepTime();
    jdbcTemplate.update(
        "update public.resource_label_events set created_at = ? where id = ?",
        correctedExistingEventTime,
        FIXED_EVENT_ID + 3);
    syncTables(List.of(LABEL_EVENTS_TABLE));
    assertThat(fixedLabelTime(ISSUE_A)).isEqualTo(lateBackfillTime);

    fullCompensateLabelEvents();
    assertThat(mirroredLabelEventTime(FIXED_EVENT_ID + 3))
        .isEqualTo(correctedExistingEventTime);
    assertThat(fixedLabelTime(ISSUE_A)).isEqualTo(correctedExistingEventTime);

    jdbcTemplate.update("delete from public.resource_label_events where id = ?", FIXED_EVENT_ID + 3);
    fullCompensateLabelEvents();
    assertThat(fixedLabelTime(ISSUE_A)).isEqualTo(lateBackfillTime);
    assertThat(
            jdbcTemplate.queryForObject(
                "select mirror_deleted from ods_gitlab_resource_label_events where id = ?",
                Boolean.class,
                FIXED_EVENT_ID + 3))
        .isTrue();
    assertIssueFactNotDuplicated(ISSUE_A);
  }

  /** 扫描标签与标签关联两张表，并走完事实发布与投影推进。 */
  private long syncLabelTables() {
    return syncTables(List.of(LABELS_TABLE, LABEL_LINKS_TABLE));
  }

  private long syncTables(List<String> sourceTables) {
    long mirrorRunId = insertMirrorRun(SyncRunType.INCREMENTAL_SYNC);
    authoritativeScopeRepository.snapshotSelectedSourceTables(mirrorRunId, sourceTables);
    LocalDateTime upperBound = nextStepTime();
    for (String sourceTable : sourceTables) {
      insertScanTask(mirrorRunId, sourceTable, upperBound);
    }
    drainAndPublish(mirrorRunId, SyncRunType.INCREMENTAL_SYNC);
    return mirrorRunId;
  }

  private long fullCompensateLabelEvents() {
    SyncRunType runType = SyncRunType.FULL_COMPENSATION_SCAN;
    long mirrorRunId = insertMirrorRun(runType);
    authoritativeScopeRepository.snapshotSelectedSourceTables(
        mirrorRunId, List.of(LABEL_EVENTS_TABLE));
    insertFullScanTask(mirrorRunId, LABEL_EVENTS_TABLE, runType);
    drainAndPublish(mirrorRunId, runType);
    return mirrorRunId;
  }

  private long reconcileLabelLinks() {
    long mirrorRunId = insertMirrorRun(SyncRunType.DELETE_RECONCILIATION);
    authoritativeScopeRepository.snapshotSelectedSourceTables(
        mirrorRunId, List.of(LABEL_LINKS_TABLE));
    insertReconciliationTask(mirrorRunId);
    drainAndPublish(mirrorRunId, SyncRunType.DELETE_RECONCILIATION);
    return mirrorRunId;
  }

  private void drainAndPublish(long mirrorRunId, SyncRunType runType) {
    SyncRun mirrorRun = syncRunMapper.selectById(mirrorRunId);
    tableWorkerService.drainRunTasks(mirrorRun, 1);
    finishMirrorRun(mirrorRunId);
    publicationCoordinator.onMirrorCompleted(
        new SyncRunCompletionEvent(
            mirrorRunId,
            config.getId(),
            SOURCE_INSTANCE,
            runType,
            SyncRunStatus.SUCCESS,
            changedRowCount(mirrorRunId)));
    long factRunId = factRunId(config.getId());
    lastFactRunId = factRunId;
    startFactRun(factRunId);
    runWorkerService.executeRun(syncRunMapper.selectById(factRunId));
  }

  /** 本轮事实运行实际推进的投影范围与结果。 */
  private List<String> issueProjectionRefreshScopes() {
    return jdbcTemplate.queryForList(
        """
        select scope_type || ':' || scope_key || ':' || status
          from fact_projection_refresh_tasks
         where fact_run_id = ? and fact_type = 'ISSUE'
         order by scope_type, scope_key
        """,
        String.class,
        lastFactRunId);
  }

  private long changedRowCount(long mirrorRunId) {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from sync_run_fact_targets where mirror_run_id = ?",
            Long.class,
            mirrorRunId);
    return count == null ? 0L : count;
  }

  private void prepareLabelMirrorTables() {
    labelsStateId = prepareMirrorTable(LABELS_TABLE, "ods_gitlab_labels");
    labelLinksStateId = prepareMirrorTable(LABEL_LINKS_TABLE, "ods_gitlab_label_links");
    labelEventsStateId =
        prepareMirrorTable(LABEL_EVENTS_TABLE, "ods_gitlab_resource_label_events");
  }

  private long prepareMirrorTable(String sourceTable, String mirrorTable) {
    boolean monotonicPrimaryKey = LABEL_EVENTS_TABLE.equals(sourceTable);
    String updatedAtColumn = monotonicPrimaryKey ? "" : "updated_at";
    SourceCursorStrategy cursorStrategy =
        monotonicPrimaryKey
            ? SourceCursorStrategy.PRIMARY_KEY_KEYSET
            : SourceCursorStrategy.TIMESTAMP_KEYSET;
    TableWhitelistOption option =
        new TableWhitelistOption(
            sourceTable,
            sourceTable,
            "id",
            updatedAtColumn,
            cursorStrategy,
            true);
    mirrorSchemaService.prepareMirrorTable(config, option);
    Long stateId =
        jdbcTemplate.queryForObject(
            """
            insert into sync_run_table_states(
                config_id, source_instance, source_table, mirror_table,
                primary_key_columns, updated_at_column, row_strategy,
                cursor_strategy, sync_enabled)
            values (?, ?, ?, ?, 'id', ?, ?, ?, true)
            returning id
            """,
            Long.class,
            config.getId(),
            SOURCE_INSTANCE,
            sourceTable,
            mirrorTable,
            updatedAtColumn,
            monotonicPrimaryKey ? "MONOTONIC_PRIMARY_KEY" : "INCREMENTAL",
            cursorStrategy.name());
    return requireId(stateId, "同步表状态 " + sourceTable);
  }

  private void establishReadyIssueGeneration() {
    long baselineRunId = insertMirrorRun(SyncRunType.INCREMENTAL_SYNC);
    authoritativeScopeRepository.snapshotSelectedSourceTables(
        baselineRunId, publicationStateService.requiredTables(config, FactType.ISSUE));
    finishMirrorRun(baselineRunId);
    assertThat(
            publicationStateService.recordMirrorCompletion(
                config, baselineRunId, SyncRunStatus.SUCCESS, false))
        .isFalse();
  }

  private void establishVerifiedLabelEventHistory() {
    long mirrorRunId = insertMirrorRun(SyncRunType.FULL_SYNC);
    authoritativeScopeRepository.snapshotSelectedSourceTables(
        mirrorRunId, List.of(LABEL_EVENTS_TABLE));
    insertFullScanTask(mirrorRunId, LABEL_EVENTS_TABLE, SyncRunType.FULL_SYNC);
    SyncRun run = syncRunMapper.selectById(mirrorRunId);
    tableWorkerService.drainRunTasks(run, 1);
    finishMirrorRun(mirrorRunId);
    publicationCoordinator.onMirrorCompleted(
        new SyncRunCompletionEvent(
            mirrorRunId,
            config.getId(),
            SOURCE_INSTANCE,
            SyncRunType.FULL_SYNC,
            SyncRunStatus.SUCCESS,
            changedRowCount(mirrorRunId)));

    assertThat(
            jdbcTemplate.queryForObject(
                """
                select sync_enabled and not dirty_flag and last_full_verified_at is not null
                  from sync_run_table_states
                 where id = ?
                """,
                Boolean.class,
                labelEventsStateId))
        .isTrue();
  }

  private void insertCustomerIssueRows() {
    jdbcTemplate.update(
        "insert into ods_gitlab_projects(id, name, mirror_deleted) values (?, ?, false)",
        CUSTOMER_PROJECT_ID,
        CUSTOMER_PROJECT_NAME);
    jdbcTemplate.update(
        "insert into ods_gitlab_users(id, name, mirror_deleted) values (?, ?, false)",
        7701L,
        "reporter-auto-chain");
    insertMirrorIssue(ISSUE_A, 601L);
    insertMirrorIssue(ISSUE_B, 602L);
  }

  private void insertMirrorIssue(long issueId, long issueIid) {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issues(
          id, iid, project_id, title, author_id, created_at, updated_at, closed_at,
          state_id, milestone_id, mirror_deleted)
        values (?, ?, ?, ?, ?, ?, ?, null, 1, null, false)
        """,
        issueId,
        issueIid,
        CUSTOMER_PROJECT_ID,
        "自动增量链样例议题 " + issueId,
        7701L,
        BASE_TIME.minusDays(1),
        BASE_TIME.minusDays(1));
  }

  private void createSourceTables() {
    jdbcTemplate.execute("drop table if exists public.resource_label_events");
    jdbcTemplate.execute("drop table if exists public.label_links");
    jdbcTemplate.execute("drop table if exists public.labels");
    jdbcTemplate.execute(
        """
        create table public.labels (
          id bigint primary key,
          title varchar(255),
          created_at timestamp,
          updated_at timestamp
        )
        """);
    jdbcTemplate.execute(
        """
        create table public.label_links (
          id bigint primary key,
          label_id bigint,
          target_id bigint,
          target_type varchar(64),
          created_at timestamp,
          updated_at timestamp
        )
        """);
    jdbcTemplate.execute(
        """
        create table public.resource_label_events (
          id bigint primary key,
          issue_id bigint,
          merge_request_id bigint,
          label_id bigint,
          action smallint,
          created_at timestamp
        )
        """);
  }

  private void dropSourceTables() {
    jdbcTemplate.execute("drop table if exists public.resource_label_events");
    jdbcTemplate.execute("drop table if exists public.label_links");
    jdbcTemplate.execute("drop table if exists public.labels");
  }

  private void insertSourceLabelEvent(
      long eventId, long issueId, long labelId, int action, LocalDateTime createdAt) {
    jdbcTemplate.update(
        """
        insert into public.resource_label_events(
            id, issue_id, merge_request_id, label_id, action, created_at)
        values (?, ?, null, ?, ?, ?)
        """,
        eventId,
        issueId,
        labelId,
        action,
        createdAt);
  }

  private LocalDateTime fixedLabelTime(long issueId) {
    return jdbcTemplate.queryForObject(
        """
        select fixed_label_time from issue_fact
         where source_instance = ? and project_id = ? and issue_id = ?
        """,
        LocalDateTime.class,
        SOURCE_INSTANCE,
        CUSTOMER_PROJECT_ID,
        issueId);
  }

  private LocalDateTime mirroredLabelEventTime(long eventId) {
    return jdbcTemplate.queryForObject(
        "select created_at from ods_gitlab_resource_label_events where id = ?",
        LocalDateTime.class,
        eventId);
  }

  private void insertSourceLabel(long labelId, String title) {
    LocalDateTime at = nextStepTime();
    jdbcTemplate.update(
        "insert into public.labels(id, title, created_at, updated_at) values (?, ?, ?, ?)",
        labelId,
        title,
        at,
        at);
  }

  private void renameSourceLabel(long labelId, String title) {
    jdbcTemplate.update(
        "update public.labels set title = ?, updated_at = ? where id = ?",
        title,
        nextStepTime(),
        labelId);
  }

  private void insertSourceLabelLink(long linkId, long labelId, long targetIssueId) {
    LocalDateTime at = nextStepTime();
    jdbcTemplate.update(
        """
        insert into public.label_links(id, label_id, target_id, target_type, created_at, updated_at)
        values (?, ?, ?, 'Issue', ?, ?)
        """,
        linkId,
        labelId,
        targetIssueId,
        at,
        at);
  }

  /** 每次来源变化都推进时间游标，保证扫描上界能覆盖本轮新增或改行的数据。 */
  private LocalDateTime nextStepTime() {
    step += 1;
    return BASE_TIME.plusMinutes((long) step * STEP_MINUTES);
  }

  private long insertMirrorRun(SyncRunType runType) {
    runSequence += 1;
    Long runId =
        jdbcTemplate.queryForObject(
            """
            insert into sync_runs(
                run_id, config_id, source_instance, run_type, trigger_type,
                status, priority, exclusive_scope, payload_json,
                resolved_worker_count, lease_owner, lease_until, started_at)
            values (?, ?, ?, ?, ?, 'RUNNING', 100, 'gitlab:default', '{}',
                    1, 'auto-chain-e2e', current_timestamp + interval '1 hour', current_timestamp)
            returning id
            """,
            Long.class,
            "requirement-auto-chain-" + runSequence,
            config.getId(),
            SOURCE_INSTANCE,
            runType.name(),
            runType == SyncRunType.TABLE_REFRESH ? "MANUAL" : "SCHEDULE");
    return requireId(runId, "镜像运行");
  }

  private void insertScanTask(long mirrorRunId, String sourceTable, LocalDateTime upperBound) {
    String rowStrategy =
        LABEL_EVENTS_TABLE.equals(sourceTable) ? "MONOTONIC_PRIMARY_KEY" : "INCREMENTAL";
    jdbcTemplate.update(
        """
        insert into sync_run_table_tasks(
            run_id, config_id, state_id, source_instance, source_table, mirror_table,
            task_type, status, row_strategy, task_stage, batch_size, scan_upper_bound_at,
            run_after, retry_count, max_retry_count, rows_scanned, rows_applied)
        values (?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, 'SCAN', 500, ?,
                current_timestamp, 0, 3, 0, 0)
        """,
        mirrorRunId,
        config.getId(),
        stateIdOf(sourceTable),
        SOURCE_INSTANCE,
        sourceTable,
        mirrorTableName(sourceTable),
        SyncRunType.INCREMENTAL_SYNC.name(),
        rowStrategy,
        upperBound);
  }

  private void insertFullScanTask(
      long mirrorRunId, String sourceTable, SyncRunType runType) {
    jdbcTemplate.update(
        """
        insert into sync_run_table_tasks(
            run_id, config_id, state_id, source_instance, source_table, mirror_table,
            task_type, status, row_strategy, task_stage, batch_size,
            run_after, retry_count, max_retry_count, rows_scanned, rows_applied)
        values (?, ?, ?, ?, ?, ?, ?, 'QUEUED', 'FULL_RECONCILE', 'SCAN', 500,
                current_timestamp, 0, 3, 0, 0)
        """,
        mirrorRunId,
        config.getId(),
        stateIdOf(sourceTable),
        SOURCE_INSTANCE,
        sourceTable,
        mirrorTableName(sourceTable),
        runType.name());
  }

  private void insertReconciliationTask(long mirrorRunId) {
    jdbcTemplate.update(
        """
        insert into sync_run_table_tasks(
            run_id, config_id, state_id, source_instance, source_table, mirror_table,
            task_type, status, row_strategy, task_stage, batch_size,
            run_after, retry_count, max_retry_count, rows_scanned, rows_applied)
        values (?, ?, ?, ?, 'label_links', 'ods_gitlab_label_links',
                ?, 'QUEUED', 'INCREMENTAL', 'RECONCILE', 1,
                current_timestamp, 0, 3, 0, 0)
        """,
        mirrorRunId,
        config.getId(),
        labelLinksStateId,
        SOURCE_INSTANCE,
        SyncRunType.DELETE_RECONCILIATION.name());
  }

  private long stateIdOf(String sourceTable) {
    return switch (sourceTable) {
      case LABELS_TABLE -> labelsStateId;
      case LABEL_LINKS_TABLE -> labelLinksStateId;
      case LABEL_EVENTS_TABLE -> labelEventsStateId;
      default -> throw new IllegalArgumentException("未知标签来源表：" + sourceTable);
    };
  }

  private String mirrorTableName(String sourceTable) {
    return switch (sourceTable) {
      case LABELS_TABLE -> "ods_gitlab_labels";
      case LABEL_LINKS_TABLE -> "ods_gitlab_label_links";
      case LABEL_EVENTS_TABLE -> "ods_gitlab_resource_label_events";
      default -> throw new IllegalArgumentException("未知标签来源表：" + sourceTable);
    };
  }

  private GitlabSyncConfig sourceConfig() {
    DatabaseEndpoint endpoint = databaseEndpoint();
    GitlabSyncConfig syncConfig = new GitlabSyncConfig();
    syncConfig.setName("customer-requirement-auto-chain");
    syncConfig.setEnabled(true);
    syncConfig.setSourceEnabled(true);
    syncConfig.setSourceInstance(SOURCE_INSTANCE);
    syncConfig.setAutoSyncEnabled(true);
    syncConfig.setSourceMode(SourceMode.DIRECT);
    syncConfig.setWhitelistMode(WhitelistMode.CUSTOM);
    syncConfig.setWhitelistTables(
        List.of(
            "issues",
            "projects",
            "users",
            "milestones",
            "labels",
            "label_links",
            "notes",
            "issue_assignees",
            "issue_metrics",
            "resource_label_events"));
    syncConfig.setDbHost(endpoint.host());
    syncConfig.setDbPort(endpoint.port());
    syncConfig.setDbName(endpoint.database());
    syncConfig.setDbUsername(dataSourceProperties.determineUsername());
    syncConfig.setDbPassword(dataSourceProperties.determinePassword());
    syncConfig.setCompensationIntervalMinutes(60);
    return syncConfig;
  }

  private Boolean requirementFlag(long issueId) {
    return jdbcTemplate.queryForObject(
        """
        select is_customer_requirement from issue_fact
         where source_instance = ? and project_id = ? and issue_id = ?
        """,
        Boolean.class,
        SOURCE_INSTANCE,
        CUSTOMER_PROJECT_ID,
        issueId);
  }

  private void assertIssueFactNotDuplicated(long issueId) {
    assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*) from issue_fact
                 where source_instance = ? and project_id = ? and issue_id = ?
                """,
                Integer.class,
                SOURCE_INSTANCE,
                CUSTOMER_PROJECT_ID,
                issueId))
        .isOne();
  }

  private void assertTargetPublished(long mirrorRunId, long rootId) {
    // 待发布权威是版本栅栏：日志行登记后，只有该根的 published_version 追平 latest_change_version
    // 才算真正发布；日志本身的 publication_status 已不再被任何代码维护。
    Map<String, Object> target =
        jdbcTemplate.queryForMap(
            """
            select target.change_version, head.published_version, head.latest_change_version
              from sync_run_fact_targets target
              join fact_change_heads head
                on head.source_instance = target.source_instance
               and head.fact_type = target.fact_type
               and head.root_id = target.root_id
             where target.mirror_run_id = ? and target.fact_type = 'ISSUE' and target.root_id = ?
            """,
            mirrorRunId,
            rootId);
    assertThat(((Number) target.get("published_version")).longValue())
        .isGreaterThanOrEqualTo(((Number) target.get("change_version")).longValue());
    assertThat(target.get("published_version")).isEqualTo(target.get("latest_change_version"));
  }

  /** 自动链路只能出现定向构建任务；出现全量任务即说明改动退化成了手工重建。 */
  private void assertNoFullBuildTaskRequested() {
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from fact_build_tasks where full_build = true",
                Integer.class))
        .isZero();
  }

  private long projectProjectionGeneration() {
    Long generation =
        jdbcTemplate.queryForObject(
            """
            select coalesce(max(generation), 0) from fact_projection_generations
             where source_instance = ? and fact_type = 'ISSUE'
               and scope_type = 'PROJECT' and scope_key = ?
            """,
            Long.class,
            SOURCE_INSTANCE,
            String.valueOf(CUSTOMER_PROJECT_ID));
    return generation == null ? 0L : generation;
  }

  private String lastFactRunStatus() {
    return jdbcTemplate.queryForObject(
        """
        select status from sync_runs
         where config_id = ? and source_instance = ? and run_type = 'FACT_REFRESH'
         order by id desc limit 1
        """,
        String.class,
        config.getId(),
        SOURCE_INSTANCE);
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
              where config_id = ? and source_instance = ?
                and run_type = 'FACT_REFRESH' and parent_run_id is null
              order by id desc
              limit 1
             """,
            Long.class,
            configId,
            SOURCE_INSTANCE);
    return requireId(factRunId, "来源级事实运行");
  }

  private void startFactRun(long factRunId) {
    jdbcTemplate.update(
        """
        update sync_runs
           set status = 'RUNNING', lease_owner = 'fact-auto-chain',
               lease_until = current_timestamp + interval '1 hour',
               started_at = current_timestamp, updated_at = current_timestamp
         where id = ?
        """,
        factRunId);
  }

  private DatabaseEndpoint databaseEndpoint() {
    String jdbcUrl = dataSourceProperties.determineUrl();
    if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
      throw new IllegalStateException("自动增量链集成测试要求标准 PostgreSQL JDBC URL");
    }
    URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
    String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
    if (uri.getHost() == null || database.isBlank()) {
      throw new IllegalStateException("无法从测试 JDBC URL 解析 PostgreSQL 地址");
    }
    return new DatabaseEndpoint(uri.getHost(), uri.getPort() < 0 ? 5432 : uri.getPort(), database);
  }

  private long requireId(Long id, String subject) {
    if (id == null || id <= 0L) {
      throw new IllegalStateException(subject + "创建失败");
    }
    return id;
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
    jdbcTemplate.update("delete from ods_gitlab_labels");
    jdbcTemplate.update("delete from ods_gitlab_resource_label_events");
    jdbcTemplate.update("delete from ods_gitlab_issue_assignees");
    jdbcTemplate.update("delete from ods_gitlab_notes");
    jdbcTemplate.update("delete from ods_gitlab_issues");
    jdbcTemplate.update("delete from ods_gitlab_milestones");
    jdbcTemplate.update("delete from ods_gitlab_users");
    jdbcTemplate.update("delete from ods_gitlab_projects");
  }

  private record DatabaseEndpoint(String host, int port, String database) {}
}
