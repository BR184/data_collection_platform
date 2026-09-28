package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.FactProjectionScope;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.entity.ProjectionScopeType;
import com.data.collection.platform.mapper.IssueFactMapper;
import com.data.collection.platform.service.statistics.StatisticBoardSnapshotService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 审查项 3：来源一致性边界必须真实生效（真实 PostgreSQL，受控并发）。
 *
 * <p>易错的四个时点都在边界内部制造并发提交：来源版本推进（发布）、全量重建请求落地（未结算）、
 * 议题范围目录成员变化，以及完全没有发布记录。每个用例都在一致性事务内的确定时点用独立事务提交，
 * 不使用重试、延时或轮询，因此断言的是隔离级别本身，而不是调度运气。
 *
 * <p>判定标准只有一条：同一次读取里，来源版本、目录解析结果与事实行必须始终属于同一数据库视图；
 * 边界外的下一次读取则整体前进到新视图。
 */
@SpringBootTest
class StatisticBoardConsistencyBoundaryTest {
  private static final String PROBE_SOURCE = "s04bound";
  private static final long PROBE_CONFIG_ID = 990_402L;
  private static final long CUSTOMER_PROJECT_ID = 325L;
  private static final String PROBE_MILESTONE = "CC2026R9边界一致性";
  private static final String PROBE_BUSINESS_KEY = "s04-boundary-group";
  private static final String PROBE_CATALOG_NAME = "边界一致性目录";
  private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 5, 6, 9, 0);

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IssueFactMapper issueFactMapper;
  @Autowired private FactProjectionGenerationService generationService;
  @Autowired private StatisticBoardSnapshotService snapshotService;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate publishTemplate;
  private Long probeCatalogId;

  @BeforeEach
  void setUp() {
    cleanUp();
    probeCatalogId = null;
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    publishTemplate = template;
    upsertPublicationState("READY", false, null);
    LabelEventHistoryTestSupport.markComplete(jdbcTemplate, PROBE_SOURCE);
  }

  @AfterEach
  void tearDown() {
    cleanUp();
  }

  /** 版本检查期间发生发布：边界内的版本与事实视图都不得前进，边界外的下一次读取整体更新。 */
  @Test
  void publicationInsideTheBoundaryKeepsVersionAndFactViewOnTheOldGeneration() {
    insertFacts(2);
    String versionBefore = currentVersion();

    BoundaryView observed =
        snapshotService.withinConsistentSourceRead(
            readPlan(),
            sourceRead -> {
              // 确定时点：边界已解析范围、校验资格并算出版本，此刻由独立事务完成一次发布。
              publishInNewTransaction(
                  () -> {
                    insertFact(3);
                    generationService.advanceIssueScopeGenerations(PROBE_SOURCE, probeScopes());
                  });
              return new BoundaryView(sourceRead.sourceVersion(), countProbeFacts());
            });

    assertThat(observed.sourceVersion())
        .as("边界内解析出的来源版本必须停留在读取开始的那一代")
        .isEqualTo(versionBefore);
    assertThat(observed.observedRows())
        .as("同一边界内的事实读取必须与版本同视图：并发发布新增的行不得出现")
        .isEqualTo(2L);
    assertThat(currentVersion())
        .as("边界外的下一次读取整体前进到发布后的版本")
        .isNotEqualTo(versionBefore);
    assertThat(countProbeFacts()).isEqualTo(3L);
  }

  /** 边界内落地的重建请求不追溯推翻本次读取，但下一次读取必须在读取动作之前拒绝。 */
  @Test
  void fullRebuildRequestedInsideTheBoundaryRejectsTheNextReadButNotTheOngoingOne() {
    insertFacts(2);
    String versionBefore = currentVersion();

    String ongoingVersion =
        snapshotService.withinConsistentSourceRead(
            readPlan(),
            sourceRead -> {
              publishInNewTransaction(() -> upsertPublicationState("READY", true, null));
              return sourceRead.sourceVersion();
            });

    assertThat(ongoingVersion)
        .as("边界自视图开始时的资格是可证实的，不得被之后的提交追溯性推翻")
        .isEqualTo(versionBefore);

    assertThatThrownBy(() -> snapshotService.withinConsistentSourceRead(readPlan(), identityAction()))
        .as("下一次读取必须在事实读取之前就拒绝，且不得复用旧 READY 冒充完整")
        .isInstanceOf(BizException.class)
        .hasMessageContaining("尚未结算");
  }

  /** 目录变化必须与版本、事实落在同一视图：边界内看不到新启用成员，边界外才整体生效。 */
  @Test
  void catalogMemberInsertInsideTheBoundaryIsInvisibleToTheOngoingRead() {
    insertProbeGroup();
    String versionBefore = snapshotService.issueFactSourceVersion(groupScopes());
    long membersBefore = countProbeMembers();

    BoundaryView observed =
        snapshotService.withinConsistentSourceRead(
            StatisticBoardSnapshotService.SourceReadPlan.of(this::groupScopes),
            sourceRead -> {
              publishInNewTransaction(
                  () -> {
                    insertProbeMember(PROBE_MILESTONE + "-追加成员");
                    jdbcTemplate.update(
                        "update issue_scope_groups set definition_generation = definition_generation + 1"
                            + " where business_key = ?",
                        PROBE_BUSINESS_KEY);
                  });
              return new BoundaryView(sourceRead.sourceVersion(), countProbeMembers());
            });

    assertThat(observed.sourceVersion())
        .as("范围组定义代际在边界内不得变化，否则缓存键与事实视图会分属两代")
        .isEqualTo(versionBefore);
    assertThat(observed.observedRows())
        .as("边界内的目录读取必须停留在同一视图：新启用成员不得出现")
        .isEqualTo(membersBefore);
    assertThat(snapshotService.issueFactSourceVersion(groupScopes()))
        .as("范围组定义变化必须让下一次读取整体失效")
        .isNotEqualTo(versionBefore);
    assertThat(countProbeMembers()).isEqualTo(membersBefore + 1);
  }

  /** 无发布记录且已有事实投影：完整性无从证实，边界必须在读取动作之前就拒绝。 */
  @Test
  void unknownSourceWithoutPublicationRecordIsRejectedBeforeAnyRead() {
    insertFacts(2);
    publishInNewTransaction(
        () -> {
          jdbcTemplate.update(
              "delete from source_fact_publication_states where source_instance = ?", PROBE_SOURCE);
          generationService.advanceIssueScopeGenerations(PROBE_SOURCE, probeScopes());
        });

    assertThatThrownBy(() -> snapshotService.withinConsistentSourceRead(readPlan(), identityAction()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("无法确认完整性");
    assertThat(countProbeFacts()).as("拒绝发生在读取之后才有意义，这里不得产生任何副作用").isEqualTo(2L);
  }

  private static Function<StatisticBoardSnapshotService.SourceRead, String> identityAction() {
    return sourceRead -> sourceRead.sourceVersion();
  }

  private void publishInNewTransaction(Runnable action) {
    publishTemplate.execute(
        status -> {
          action.run();
          return null;
        });
  }

  private String currentVersion() {
    return snapshotService.issueFactSourceVersion(probeScopes());
  }

  private StatisticBoardSnapshotService.SourceReadPlan readPlan() {
    return StatisticBoardSnapshotService.SourceReadPlan.of(this::probeScopes);
  }

  private Set<FactProjectionScope> probeScopes() {
    return Set.of(
        new FactProjectionScope(
            PROBE_SOURCE,
            FactType.ISSUE,
            ProjectionScopeType.PROJECT,
            FactProjectionScopeKeyCodec.project(CUSTOMER_PROJECT_ID)));
  }

  /** 里程碑选择下的真实读取形状：范围组自带定义代际分量。 */
  private Set<FactProjectionScope> groupScopes() {
    return Set.of(
        new FactProjectionScope(
            PROBE_SOURCE,
            FactType.ISSUE,
            ProjectionScopeType.ISSUE_SCOPE_GROUP,
            FactProjectionScopeKeyCodec.issueScopeGroup(
                CUSTOMER_PROJECT_ID, IssueScopeDimension.MILESTONE, PROBE_CONFIG_ID)));
  }

  private void insertFacts(int totalRows) {
    for (int index = 1; index <= totalRows; index++) {
      insertFact(index);
    }
  }

  private void insertFact(int sequence) {
    long issueId = 960_000L + sequence;
    IssueFact fact = new IssueFact();
    fact.setSourceSystem("GITLAB");
    fact.setSourceInstance(PROBE_SOURCE);
    fact.setIngestChannel("MIRROR");
    fact.setSourceSummary("边界一致性用例");
    fact.setProjectId(CUSTOMER_PROJECT_ID);
    fact.setProjectName("CC_PRODUCT");
    fact.setIssueId(issueId);
    fact.setIssueIid(issueId);
    fact.setTitle("边界议题" + sequence);
    fact.setIssueState("opened");
    fact.setMilestoneTitle(PROBE_MILESTONE);
    fact.setModuleNames("模块A");
    fact.setFunctionName("功能A");
    fact.setSeverityLevel("LEVEL2");
    fact.setPriorityLevel("P2");
    fact.setBugStatus("处理中");
    fact.setCategory("功能");
    fact.setCreatedAtSource(CREATED_AT);
    fact.setUpdatedAtSource(CREATED_AT);
    fact.setDeleted(false);
    fact.setExcluded(false);
    fact.setFixed(false);
    fact.setDelayIssue(false);
    fact.setResponseDelayed(false);
    fact.setResolveDelayed(false);
    fact.setRegression(false);
    fact.setCrash(false);
    fact.setLevel1Other(false);
    fact.setIllegal(false);
    fact.setHasResponse(false);
    fact.setResponseOverdue(false);
    fact.setResolveSlaDays(0);
    fact.setLegacy(false);
    issueFactMapper.upsert(fact);
  }

  private long countProbeFacts() {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from issue_fact where source_instance = ?", Long.class, PROBE_SOURCE);
    return count == null ? 0L : count;
  }

  private void insertProbeGroup() {
    long catalogId = ensureProbeCatalog();
    jdbcTemplate.update(
        """
        insert into issue_scope_groups(id, catalog_id, business_key, display_name, sort_order, enabled)
        values (?, ?, ?, '边界一致性范围', 9001, true)
        on conflict (id) do nothing
        """,
        PROBE_CONFIG_ID,
        catalogId,
        PROBE_BUSINESS_KEY);
    insertProbeMember(PROBE_MILESTONE);
  }

  private void insertProbeMember(String sourceValue) {
    jdbcTemplate.update(
        """
        insert into issue_scope_members(catalog_id, group_id, source_value, display_name, sort_order, enabled)
        values (?, ?, ?, ?, 1, true)
        """,
        ensureProbeCatalog(),
        PROBE_CONFIG_ID,
        sourceValue,
        sourceValue);
  }

  private long countProbeMembers() {
    Long count =
        jdbcTemplate.queryForObject(
            "select count(*) from issue_scope_members where group_id = ? and enabled = true",
            Long.class,
            PROBE_CONFIG_ID);
    return count == null ? 0L : count;
  }

  private Long ensureProbeCatalog() {
    if (probeCatalogId != null) {
      return probeCatalogId;
    }
    List<Long> existing =
        jdbcTemplate.queryForList(
            """
            select id from issue_scope_catalogs
             where project_id = ? and dimension = 'MILESTONE'
             order by id
             limit 1
            """,
            Long.class,
            CUSTOMER_PROJECT_ID);
    if (!existing.isEmpty()) {
      // 项目 325 的里程碑目录每个项目只允许一条：复用现有目录，本用例只增删自己的范围组与成员。
      probeCatalogId = existing.get(0);
      return probeCatalogId;
    }
    probeCatalogId =
        jdbcTemplate.queryForObject(
            """
            insert into issue_scope_catalogs(project_id, project_name, dimension, enabled)
            values (?, ?, 'MILESTONE', true)
            returning id
            """,
            Long.class,
            CUSTOMER_PROJECT_ID,
            PROBE_CATALOG_NAME);
    return probeCatalogId;
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

  private long ensureProbeRun() {
    jdbcTemplate.update(
        """
        insert into gitlab_sync_configs(
          id, name, source_instance, source_mode, db_name, db_username, db_password)
        values (?, 's04bound-probe', ?, 'DOCKER', 'gl_database', 'gl_user', 'gl_password')
        on conflict (id) do nothing
        """,
        PROBE_CONFIG_ID,
        PROBE_SOURCE);
    List<Long> existing =
        jdbcTemplate.queryForList(
            "select id from sync_runs where run_id = ?", Long.class, "s04bound-run");
    if (!existing.isEmpty()) {
      return existing.get(0);
    }
    return jdbcTemplate.queryForObject(
        """
        insert into sync_runs(
          run_id, config_id, source_instance, run_type, trigger_type, status, exclusive_scope)
        values (?, ?, ?, 'TABLE_REFRESH', 'MANUAL', 'SUCCESS', 's04bound')
        returning id
        """,
        Long.class,
        "s04bound-run",
        PROBE_CONFIG_ID,
        PROBE_SOURCE);
  }

  private void cleanUp() {
    jdbcTemplate.update("delete from issue_fact where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update("delete from issue_fact_customer_members where project_id = ?", CUSTOMER_PROJECT_ID);
    jdbcTemplate.update("delete from issue_scope_members where group_id = ?", PROBE_CONFIG_ID);
    jdbcTemplate.update("delete from issue_scope_groups where id = ?", PROBE_CONFIG_ID);
    jdbcTemplate.update("delete from issue_scope_catalogs where project_name = ?", PROBE_CATALOG_NAME);
    jdbcTemplate.update(
        "delete from fact_projection_generations where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update(
        "delete from source_fact_publication_states where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update("delete from sync_runs where source_instance = ?", PROBE_SOURCE);
    jdbcTemplate.update("delete from gitlab_sync_configs where id = ?", PROBE_CONFIG_ID);
  }

  /** 边界内观察到的视图：来源版本与同一视图内读到的行数。 */
  private record BoundaryView(String sourceVersion, long observedRows) {}
}
