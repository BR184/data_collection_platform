package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 客户问题延期事实重算的写入面边界。
 *
 * <p>该任务只拥有三个延期布尔列，因此必须只写这三列：其它列、客户成员关系与搜索列都不得被它改写，
 * 否则读入内存的快照会把并发发布的事实回退（且发布状态已推进，不会重发）。
 */
@Testcontainers(disabledWithoutDocker = true)
class FactBuildServiceCustomerIssueDelayFlagsTest {
  private static final long CC_PROJECT_ID = 325L;
  private static final Set<String> DELAY_FLAG_COLUMNS =
      Set.of("is_response_delayed", "response_overdue", "is_resolve_delayed", "updated_at");

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("secret");

  private static JdbcTemplate jdbcTemplate;
  private FactBuildService service;
  private IssueFactPersistenceService issueFactPersistenceService;
  private FactSearchIndexRepairService searchIndexRepairService;

  @BeforeAll
  static void migrate() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    jdbcTemplate = new JdbcTemplate(dataSource);
    createOdsTables();
  }

  /** ODS 镜像表由镜像引擎在运行时创建，不在 Flyway 迁移内，这里只建延期读取需要的最小结构。 */
  private static void createOdsTables() {
    jdbcTemplate.execute(
        "create table if not exists ods_gitlab_issues("
            + "id bigint primary key, project_id bigint, iid bigint, title text,"
            + " mirror_deleted boolean default false)");
    jdbcTemplate.execute(
        "create table if not exists ods_gitlab_labels("
            + "id bigint primary key, project_id bigint, title text,"
            + " mirror_deleted boolean default false)");
    jdbcTemplate.execute(
        "create table if not exists ods_gitlab_label_links("
            + "id bigint primary key, label_id bigint, target_id bigint,"
            + " target_type varchar(64), mirror_deleted boolean default false)");
  }

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("delete from issue_fact_customer_members");
    jdbcTemplate.update("delete from issue_fact");
    jdbcTemplate.update("delete from ods_gitlab_label_links");
    jdbcTemplate.update("delete from ods_gitlab_labels");
    jdbcTemplate.update("delete from ods_gitlab_issues");
    issueFactPersistenceService = mock(IssueFactPersistenceService.class);
    searchIndexRepairService = mock(FactSearchIndexRepairService.class);
    service =
        new FactBuildService(
            jdbcTemplate,
            issueFactPersistenceService,
            mock(IssueCustomerNameAliasService.class),
            mock(MergeRequestFactPersistenceService.class),
            mock(ModuleDictionaryService.class),
            mock(FactBuildTaskService.class),
            mock(GitlabSourceSchemaGuard.class),
            mock(GitlabConfigService.class),
            mock(IntegrationTestFactBuildService.class),
            mock(CustomerIssueMilestoneCatalogReconciliationService.class),
            new GitlabFactSourceSqlProvider(),
            mock(GitlabFactSourceQueryExecutor.class),
            mock(IssuePhaseCalendarLoader.class),
            new IssueFactSourceRowMapper(),
            new MergeRequestFactSourceRowMapper(),
            searchIndexRepairService,
            new FactPublicationTransaction(),
            new GitlabMirrorProperties());
  }

  @Test
  void shouldWriteOnlyDelayFlagsAndLeavePublishedColumnsUntouched() {
    insertSourceIssue(9001L, CC_PROJECT_ID, 1L);
    insertSourceLabel(7001L, "模块：平台");
    insertLabelLink(8001L, 7001L, 9001L);
    long targetFactId =
        insertIssueFact(
            CC_PROJECT_ID,
            1L,
            "opened",
            "",
            false,
            false,
            false,
            "now() - interval '100 days'");
    jdbcTemplate.update(
        "insert into issue_fact_customer_members(source_system, source_instance, project_id, issue_id, customer_name)"
            + " values ('GITLAB', 'default', ?, 1, '客户A')",
        CC_PROJECT_ID);

    Map<String, Object> before = rowSnapshot(targetFactId);
    List<Map<String, Object>> membersBefore = memberRows();

    service.refreshCustomerIssueDelayFactsForConfig(config());

    Map<String, Object> after = rowSnapshot(targetFactId);
    assertThat(after.get("is_response_delayed")).isEqualTo(true);
    assertThat(after.get("response_overdue")).isEqualTo(true);
    assertThat(after.get("updated_at")).isNotEqualTo(before.get("updated_at"));
    assertThat(changedColumns(before, after)).isEmpty();
    assertThat(memberRows()).isEqualTo(membersBefore);
    verifyNoInteractions(issueFactPersistenceService, searchIndexRepairService);
  }

  @Test
  void shouldLeaveRowsOutsideCustomerIssueScopeUntouched() {
    long otherProjectFactId =
        insertIssueFact(9L, 2L, "opened", "", false, false, false, "now() - interval '100 days'");
    long closedFactId =
        insertIssueFact(CC_PROJECT_ID, 3L, "closed", "", false, false, false, "now() - interval '100 days'");
    long beforeStartFactId =
        insertIssueFact(
            CC_PROJECT_ID, 4L, "opened", "", false, false, false, "timestamp '2025-12-31 10:00:00'");
    Map<String, Object> otherProjectBefore = rowSnapshot(otherProjectFactId);
    Map<String, Object> closedBefore = rowSnapshot(closedFactId);
    Map<String, Object> beforeStartBefore = rowSnapshot(beforeStartFactId);

    service.refreshCustomerIssueDelayFactsForConfig(config());

    assertThat(rowSnapshot(otherProjectFactId)).isEqualTo(otherProjectBefore);
    assertThat(rowSnapshot(closedFactId)).isEqualTo(closedBefore);
    assertThat(rowSnapshot(beforeStartFactId)).isEqualTo(beforeStartBefore);
  }

  @Test
  void shouldNotWriteRowsWhoseDelayFlagsAlreadyMatch() {
    insertSourceIssue(9002L, CC_PROJECT_ID, 5L);
    long factId =
        insertIssueFact(
            CC_PROJECT_ID,
            5L,
            "opened",
            "# 问题调研情况说明",
            false,
            false,
            false,
            "now() - interval '100 days'");
    Map<String, Object> before = rowSnapshot(factId);

    service.refreshCustomerIssueDelayFactsForConfig(config());

    assertThat(rowSnapshot(factId)).isEqualTo(before);
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("default");
    return config;
  }

  private Map<String, Object> rowSnapshot(long factId) {
    return jdbcTemplate.queryForMap("select * from issue_fact where id = ?", factId);
  }

  private List<Map<String, Object>> memberRows() {
    return jdbcTemplate.queryForList(
        "select * from issue_fact_customer_members order by project_id, issue_id, customer_name");
  }

  private Map<String, Object> changedColumns(
      Map<String, Object> before, Map<String, Object> after) {
    Map<String, Object> changed = new LinkedHashMap<>();
    for (String column : before.keySet()) {
      if (DELAY_FLAG_COLUMNS.contains(column)) {
        continue;
      }
      if (!Objects.equals(before.get(column), after.get(column))) {
        changed.put(column, before.get(column) + " -> " + after.get(column));
      }
    }
    return changed;
  }

  private void insertSourceIssue(long id, long projectId, long iid) {
    jdbcTemplate.update(
        "insert into ods_gitlab_issues(id, project_id, iid, title, mirror_deleted)"
            + " values (?, ?, ?, '目标议题', false)",
        id,
        projectId,
        iid);
  }

  private void insertSourceLabel(long id, String title) {
    jdbcTemplate.update(
        "insert into ods_gitlab_labels(id, project_id, title, mirror_deleted)"
            + " values (?, ?, ?, false)",
        id,
        CC_PROJECT_ID,
        title);
  }

  private void insertLabelLink(long id, long labelId, long targetId) {
    jdbcTemplate.update(
        "insert into ods_gitlab_label_links(id, label_id, target_id, target_type, mirror_deleted)"
            + " values (?, ?, ?, 'Issue', false)",
        id,
        labelId,
        targetId);
  }

  private long insertIssueFact(
      long projectId,
      long issueId,
      String issueState,
      String rawPayload,
      boolean responseDelayed,
      boolean responseOverdue,
      boolean resolveDelayed,
      String createdAtSourceSql) {
    return jdbcTemplate.queryForObject(
        "insert into issue_fact(source_system, source_instance, project_id, issue_id, issue_iid,"
            + " title, issue_state, raw_payload, label_names, has_response, handler_name,"
            + " module_names, customer_names, planned_resolution_at, illegal_reasons, search_text,"
            + " is_response_delayed, response_overdue, is_resolve_delayed, created_at_source, deleted)"
            + " values ('GITLAB', 'default', ?, ?, ?, '目标议题', ?, ?, '模块：平台', false, '张三',"
            + " '模块：平台', '客户A', timestamp '2026-05-01', '[]', '目标议题 张三', ?, ?, ?, "
            + createdAtSourceSql
            + ", false) returning id",
        Long.class,
        projectId,
        issueId,
        issueId,
        issueState,
        rawPayload,
        responseDelayed,
        responseOverdue,
        resolveDelayed);
  }
}
