package com.data.collection.platform.service.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.bi.domain.BiSystemTestCauseClassifier;
import com.data.collection.platform.bi.domain.BiSystemTestDelayCauseClassifier;
import com.data.collection.platform.bi.domain.source.BiProductVersionScope;
import com.data.collection.platform.bi.domain.source.BiSystemTestSource;
import com.data.collection.platform.bi.infrastructure.BiPlatformProductVersionAdapter;
import com.data.collection.platform.bi.infrastructure.BiPlatformSystemTestSourceAdapter;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.service.CodeReviewDataReadMode;
import com.data.collection.platform.service.IssueScopeCatalogService;
import com.data.collection.platform.service.PageRecordSnapshotService;
import com.data.collection.platform.service.QualityBoardCodeReviewReadSupport;
import com.data.collection.platform.service.SystemTestPhaseCatalogService;
import com.data.collection.platform.service.SystemTestPhaseScopeResolver;
import com.data.collection.platform.service.dashboard.DashboardVersionScopeConfigLoader;
import com.data.collection.platform.service.dashboard.DashboardVersionScopeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 两个看板的组合范围在真实 PostgreSQL 上的合并、去重与隔离回归。 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardCombinedVersionPostgresIntegrationTest {
  private static final String COMBINED_KEY = "cc-2027-r1-r2";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("secret");

  private JdbcTemplate jdbcTemplate;
  private DashboardVersionScopeService versionScopeService;
  private long catalogId;
  private long r1GroupId;
  private long r2GroupId;

  @BeforeAll
  void migrate() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbcTemplate = new JdbcTemplate(dataSource);
    IssueScopeCatalogService catalogService = new IssueScopeCatalogService(jdbcTemplate);
    versionScopeService = new DashboardVersionScopeService(
        catalogService, new DashboardVersionScopeConfigLoader(new ObjectMapper()));
  }

  @BeforeEach
  void seed() {
    jdbcTemplate.update("delete from issue_fact");
    jdbcTemplate.update("delete from integration_test_fact");
    jdbcTemplate.update("delete from issue_scope_members");
    jdbcTemplate.update("delete from issue_scope_groups");
    jdbcTemplate.update("delete from issue_scope_catalogs");

    catalogId = jdbcTemplate.queryForObject(
        """
        insert into issue_scope_catalogs(project_id, project_name, dimension, enabled)
        values (9, 'CrownCAD', 'TESTING_PHASE', true)
        returning id
        """,
        Long.class);
    r1GroupId = insertGroup("CC2027R1", 2);
    r2GroupId = insertGroup("CC2027R2", 3);
    long otherGroupId = insertGroup("CC2026R3", 1);
    insertPhase(otherGroupId, "CC2026R3第一轮系统测试", 1);
    insertPhase(r1GroupId, "CC2027R1第一轮系统测试", 1);
    insertPhase(r1GroupId, "CC2027R1回归测试", 2);
    insertPhase(r2GroupId, "CC2027R2第一轮系统测试", 1);
    insertPhase(r2GroupId, "CC2027R2第二轮系统测试", 2);
    insertPhase(r2GroupId, "CC2027R2回归测试", 3);

    insertIssue(1L, "CC2027R1第一轮系统测试");
    insertIssue(2L, "CC2027R1回归测试");
    insertIssue(3L, "CC2027R2第一轮系统测试");
    insertIssue(4L, "CC2027R2第二轮系统测试");
    insertIssue(5L, "CC2027R2回归测试");
    insertIssue(6L, "CC2026R3第一轮系统测试");
    insertIssue(7L, null);

    insertIntegration(1L, "CC2027R1集成测试", 1, 2, 1);
    insertIntegration(2L, "CC2027R2集成测试", 9, 10, 1);
    insertIntegration(3L, "CC2027R1&R2集成测试", 100, 100, 0);
  }

  @Test
  void qualityBoardCombinesMemberFactsWhileIndependentSystemTestKeepsRounds() {
    QualityBoardOtherQueryService queryService = new QualityBoardOtherQueryService(
        jdbcTemplate,
        mock(QualityBoardCodeReviewReadSupport.class),
        versionScopeService);

    var rows = queryService.load(
        QualityBoardOtherTopic.FUNCTION_DEFECT_COUNT,
        AnalyticsDashboardQueryContext.of(Map.of("functionCountProjectName", COMBINED_KEY)));

    long combinedTotal = rows.stream()
        .mapToLong(QualityBoardOtherQueryService.Row::numerator)
        .sum();
    assertThat(combinedTotal).isEqualTo(5L);

    SystemTestPhaseScopeResolver independentResolver = new SystemTestPhaseScopeResolver(
        new SystemTestPhaseCatalogService(new IssueScopeCatalogService(jdbcTemplate)));
    assertThat(independentResolver.resolvePhases(9L, "CC2027R1"))
        .containsExactly("CC2027R1第一轮系统测试", "CC2027R1回归测试");
    assertThat(independentResolver.resolvePhases(9L, "CC2027R2"))
        .containsExactly(
            "CC2027R2第一轮系统测试", "CC2027R2第二轮系统测试", "CC2027R2回归测试");
    assertThat(countIssues(independentResolver.resolvePhases(9L, "CC2027R1"))).isEqualTo(2L);
    assertThat(countIssues(independentResolver.resolvePhases(9L, "CC2027R2"))).isEqualTo(3L);
  }

  @Test
  void combinedScopeCannotBeUsedAsAnAliasForSingleMemberQueries() {
    assertThatThrownBy(() -> versionScopeService.resolve(
            9L,
            com.data.collection.platform.service.IssueScopeDimension.TESTING_PHASE,
            "CC2027R1"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("已合并为");
  }

  @Test
  void biSystemTestLoadsTheUnionOnceAndKeepsOriginalRounds() {
    PageRecordSnapshotService snapshotService = mock(PageRecordSnapshotService.class);
    when(snapshotService.issueFactSourceVersion(anyString(), anyLong(), any(), anyString()))
        .thenReturn("issue-v1");
    var adapter = new BiPlatformSystemTestSourceAdapter(
        jdbcTemplate,
        snapshotService,
        new BiSystemTestCauseClassifier(),
        new BiSystemTestDelayCauseClassifier());
    BiProductVersionScope scope = new BiPlatformProductVersionAdapter(versionScopeService)
        .requireScope(r1GroupId);

    BiSystemTestSource source = adapter.load(scope);

    assertThat(source.issues()).hasSize(5);
    assertThat(source.issues()).extracting(BiSystemTestSource.IssueRecord::issueId)
        .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
    assertThat(source.rounds()).extracting(BiSystemTestSource.RoundDefinition::roundName)
        .containsExactly(
            "CC2027R1第一轮系统测试", "CC2027R1回归测试",
            "CC2027R2第一轮系统测试", "CC2027R2第二轮系统测试", "CC2027R2回归测试");
  }

  @Test
  void qualityOverviewExpandsIntegrationPhasesAndIgnoresUnregisteredCombinedPhase() {
    var rdService = new com.data.collection.platform.service.QualityBoardRdService(
        jdbcTemplate,
        mock(com.data.collection.platform.service.ReviewDataRecordReadRepository.class),
        mock(com.data.collection.platform.service.ReviewDataMatchModeRecordRepository.class),
        mock(QualityBoardCodeReviewReadSupport.class),
        versionScopeService);

    var overview = rdService.getOverview(COMBINED_KEY, CodeReviewDataReadMode.FORMAL);

    assertThat(overview.integrationPassRate()).isEqualTo(70D);
    assertThat(overview.metrics())
        .filteredOn(metric -> "integrationPassRate".equals(metric.key()))
        .singleElement()
        .satisfies(metric -> assertThat(metric.value()).isEqualTo(70D));
  }

  @Test
  void detailViewsUseTheSameCombinedRangeAsChartsAndExports() {
    var rdService = new com.data.collection.platform.service.QualityBoardRdService(
        jdbcTemplate,
        mock(com.data.collection.platform.service.ReviewDataRecordReadRepository.class),
        mock(com.data.collection.platform.service.ReviewDataMatchModeRecordRepository.class),
        mock(QualityBoardCodeReviewReadSupport.class),
        versionScopeService);
    var detailService = new QualityRdAnalyticsDetailQueryService(
        jdbcTemplate,
        mock(QualityBoardCodeReviewReadSupport.class),
        versionScopeService,
        rdService);
    AnalyticsDashboardQueryContext context = new AnalyticsDashboardQueryContext(
        Map.of("projectName", COMBINED_KEY), 1, 200, null, null,
        AnalyticsDashboardQueryContext.ReadMode.FORMAL);

    var integrationPage = detailService.load("integration-test-results", context);
    assertThat(integrationPage.records()).hasSize(2);
    assertThat(integrationPage.records())
        .extracting(row -> row.get("testingPhase"))
        .containsExactlyInAnyOrder("CC2027R1集成测试", "CC2027R2集成测试");

    var leakagePage = detailService.load("development-leakage-defects", context);
    assertThat(leakagePage.records()).hasSize(7);
  }

  private long insertGroup(String businessKey, int sortOrder) {
    return jdbcTemplate.queryForObject(
        """
        insert into issue_scope_groups(catalog_id, business_key, display_name, sort_order, enabled)
        values (?, ?, ?, ?, true)
        returning id
        """,
        Long.class,
        catalogId,
        businessKey,
        businessKey,
        sortOrder);
  }

  private void insertPhase(long groupId, String sourceValue, int sortOrder) {
    jdbcTemplate.update(
        """
        insert into issue_scope_members(
            catalog_id, group_id, source_value, display_name, sort_order, enabled)
        values (?, ?, ?, ?, ?, true)
        """,
        catalogId,
        groupId,
        sourceValue,
        sourceValue,
        sortOrder);
  }

  private void insertIssue(long issueId, String testingPhase) {
    jdbcTemplate.update(
        """
        insert into issue_fact(
            source_instance, project_id, issue_id, issue_iid, title, testing_phase,
            function_name, fix_user, assignee_name, bug_status, issue_state,
            severity_level, category, deleted, is_excluded, exclusion_reason)
        values ('default', 9, ?, ?, 't', ?, '装配', '张三', '李四', '已修复',
                'open', 'LEVEL2', '功能', false, false, '')
        """,
        issueId,
        issueId,
        testingPhase);
  }

  private void insertIntegration(
      long issueId, String testingPhase, int passCase, int executeCase, int notPassCase) {
    jdbcTemplate.update(
        """
        insert into integration_test_fact(
            source_instance, project_id, issue_id, issue_iid, title, testing_phase,
            execute_case, pass_case, not_pass_case, deleted)
        values ('default', 9, ?, ?, 't', ?, ?, ?, ?, false)
        """,
        issueId,
        issueId,
        testingPhase,
        executeCase,
        passCase,
        notPassCase);
  }

  private long countIssues(List<String> phases) {
    String placeholders = String.join(",", phases.stream().map(ignored -> "?").toList());
    Long count = jdbcTemplate.queryForObject(
        "select count(*) from issue_fact where deleted = false and project_id = 9"
            + " and testing_phase in (" + placeholders + ")",
        Long.class,
        phases.toArray());
    return count == null ? 0L : count;
  }
}
