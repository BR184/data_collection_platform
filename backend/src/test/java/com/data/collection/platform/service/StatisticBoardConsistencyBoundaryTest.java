package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
  /** 完整读版本夹具：同一发布身份内重复写入命中 do update，只留下一条修订行。 */

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private IssueFactMapper issueFactMapper;
  @Autowired private StatisticBoardSnapshotService snapshotService;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate publishTemplate;
  private Long probeCatalogId;

  @BeforeEach
  void setUp() {
    cleanUp();
    jdbcTemplate.execute("truncate read_publications, read_publication_domains cascade");
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

  /** 并发真正发布后，旧身份在本次及后续事务仍读旧内容；新身份才前进。 */
  @Test
  void publicationInsideTheBoundaryKeepsVersionAndFactViewOnTheOldGeneration() {
    insertFacts(2);
    var oldSelection = selection();
    String versionBefore = oldSelection.sourceVersion();
    BoundaryView observed = snapshotService.withinVersionedRead(oldSelection, readPlan(), sourceRead -> {
      publishInNewTransaction(() -> insertFact(3));
      return new BoundaryView(sourceRead.sourceVersion(), countFacts(oldSelection));
    });
    assertThat(observed.sourceVersion()).isEqualTo(versionBefore);
    assertThat(observed.observedRows()).isEqualTo(2L);
    assertThat(currentVersion()).isNotEqualTo(versionBefore);
    Long retainedRows = snapshotService.withinVersionedRead(oldSelection, readPlan(), read -> countFacts(oldSelection));
    assertThat(retainedRows).isEqualTo(2L);
    assertThat(countFacts(selection())).isEqualTo(3L);
  }

  /** 全量栅栏与失败只披露更新状态，不能拒绝已经完整发布的事实及明细。 */
  @Test
  void fullRebuildAndBlockedSourceKeepTheCompletePublishedVersionReadable() {
    insertFacts(2);
    var oldSelection = selection();
    String observed = snapshotService.withinVersionedRead(oldSelection, readPlan(), sourceRead -> {
      publishInNewTransaction(() -> upsertPublicationState("BLOCKED", true, "fixture failure"));
      return sourceRead.sourceVersion();
    });
    assertThat(observed).isEqualTo(oldSelection.sourceVersion());
    assertThat(snapshotService.withinVersionedRead(selection(), readPlan(), identityAction())).isEqualTo(observed);
    Long retainedRows = snapshotService.withinVersionedRead(oldSelection, readPlan(), read -> countFacts(oldSelection));
    assertThat(retainedRows).isEqualTo(2L);
  }

  /** 目录编辑不能重解释旧身份；下一完整发布才让新目录进入新身份。 */
  @Test
  void catalogMemberChangeStaysInvisibleUntilANewCompletePublication() {
    insertProbeGroup(); insertFacts(2);
    var oldSelection = selection();
    long before = countMembers(oldSelection);
    BoundaryView observed = snapshotService.withinVersionedRead(oldSelection,
        StatisticBoardSnapshotService.SourceReadPlan.of(this::groupScopes), sourceRead -> {
          publishInNewTransaction(() -> insertProbeMember(PROBE_MILESTONE + "第二成员"));
          return new BoundaryView(sourceRead.sourceVersion(), countMembers(oldSelection));
        });
    assertThat(observed.sourceVersion()).isEqualTo(oldSelection.sourceVersion());
    assertThat(observed.observedRows()).isEqualTo(before);
    assertThat(countMembers(oldSelection)).isEqualTo(before);
    assertThat(currentVersion()).isEqualTo(oldSelection.sourceVersion());
    insertFact(2);
    assertThat(currentVersion()).isNotEqualTo(oldSelection.sourceVersion());
    assertThat(countMembers(selection())).isEqualTo(before + 1);
    assertThat(countMembers(oldSelection)).isEqualTo(before);
  }

  /** 伪造/不存在的发布身份在执行读取前拒绝，不能凭当前控制面 READY 制造产出。 */
  @Test
  void nonexistentPublicationIdentityIsRejectedBeforeAnyRead() {
    var executed = new java.util.concurrent.atomic.AtomicBoolean();
    assertThatThrownBy(() -> snapshotService.withinVersionedRead(
        com.data.collection.platform.service.read.TestReadSelections.all(), readPlan(), read -> {
          executed.set(true); return "unreachable";
        })).isInstanceOf(com.data.collection.platform.service.read.ReadViewException.class);
    assertThat(executed).isFalse();
    assertThat(countProbeFacts()).isZero();
  }

  private com.data.collection.platform.entity.read.FactReadSelection selection() {
    var published = FactRevisionFixture.currentPublishedSelection(jdbcTemplate,
        com.data.collection.platform.entity.read.PublicationFactTable.ISSUE);
    return new com.data.collection.platform.entity.read.FactReadSelection(published.contexts().stream()
        .filter(context -> PROBE_SOURCE.equals(context.sourceInstance())).toList(), published.businessDate());
  }

  private long countFacts(com.data.collection.platform.entity.read.FactReadSelection selection) {
    var query = com.data.collection.platform.service.read.VersionedFactSql.bind(selection,
        "select count(*) from issue_fact where source_instance = ?", List.of(PROBE_SOURCE));
    return jdbcTemplate.queryForObject(query.sql(), Long.class, query.arguments().toArray());
  }

  private long countMembers(com.data.collection.platform.entity.read.FactReadSelection selection) {
    String query = com.data.collection.platform.service.read.VersionedDefinitionSql.bind(selection,
        "select count(*) from issue_scope_members where group_id = ? and enabled = true");
    return jdbcTemplate.queryForObject(query, Long.class, PROBE_CONFIG_ID);
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
    return selection().sourceVersion();
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
    var key = com.data.collection.platform.entity.read.ReadPublicationDomainKey.gitlabFact(PROBE_SOURCE, FactType.ISSUE);
    boolean full = new com.data.collection.platform.service.read.ReadPublicationRepository(jdbcTemplate)
        .findDomain(key).map(domain -> domain.currentPublicationId()).isEmpty();
    FactRevisionFixture.publishFacts(jdbcTemplate, com.data.collection.platform.entity.read.PublicationFactTable.ISSUE,
        PROBE_SOURCE, full, target -> {
          issueFactMapper.batchUpsert(List.of(fact), target.epochId(), target.publicationId());
          return new com.data.collection.platform.entity.FactBuildResponse("fixture", full, 1, "fixture");
        });
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
    // 客户成员关系按外键挂在事实修订上，必须先删关系再删事实。
    jdbcTemplate.update("delete from issue_fact_customer_members where project_id = ?", CUSTOMER_PROJECT_ID);
    jdbcTemplate.update("delete from issue_fact where source_instance = ?", PROBE_SOURCE);
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
