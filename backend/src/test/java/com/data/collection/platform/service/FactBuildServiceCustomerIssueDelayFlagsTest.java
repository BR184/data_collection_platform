package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
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
 * <p>延期重算生成新修订：三个延期布尔列改变，其余业务列与客户集合保持；旧修订完全不改。
 * 派生搜索列按相同业务输入重建，关系绑定到新父修订。本类只验事实组件，平台投影门禁由发布集成测试保护。
 */
@Testcontainers(disabledWithoutDocker = true)
class FactBuildServiceCustomerIssueDelayFlagsTest {
  private static final long CC_PROJECT_ID = 325L;
  private static final Set<String> DELAY_FLAG_COLUMNS =
      Set.of("is_response_delayed", "response_overdue", "is_resolve_delayed", "updated_at",
          "id", "publication_epoch_id", "created_by_publication_id", "visible_from", "visible_to",
          "created_at", "fact_refreshed_at");

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
    jdbcTemplate.execute("truncate read_publications, read_publication_domains cascade");
    jdbcTemplate.update("delete from ods_gitlab_label_links");
    jdbcTemplate.update("delete from ods_gitlab_labels");
    jdbcTemplate.update("delete from ods_gitlab_issues");
    try {
      var configuration = new org.apache.ibatis.session.Configuration();
      configuration.addMapper(com.data.collection.platform.mapper.IssueFactMapper.class);
      var factory = new org.mybatis.spring.SqlSessionFactoryBean();
      factory.setDataSource(jdbcTemplate.getDataSource());
      factory.setConfiguration(configuration);
      var mapper = new org.mybatis.spring.SqlSessionTemplate(factory.getObject())
          .getMapper(com.data.collection.platform.mapper.IssueFactMapper.class);
      issueFactPersistenceService = new IssueFactPersistenceService(mapper,
          new IssueFactCustomerMembershipRepository(jdbcTemplate),
          new com.data.collection.platform.service.read.ReadPublicationRepository(jdbcTemplate));
    } catch (Exception error) { throw new IllegalStateException(error); }
    searchIndexRepairService = new FactSearchIndexRepairService(jdbcTemplate,
        new IssueFactSourceRowMapper(), new MergeRequestFactSourceRowMapper());
    SyncFactPublicationStateService publicationStateService =
        mock(SyncFactPublicationStateService.class);
    when(publicationStateService.isIssueSourceHistoryComplete("default")).thenReturn(true);
    when(publicationStateService.sourceSeal(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(com.data.collection.platform.entity.FactType.class), org.mockito.ArgumentMatchers.anyLong())).thenReturn("{\"fixture\":true}");
    service =
        new FactBuildService(
            jdbcTemplate,
            issueFactPersistenceService,
            mock(IssueCustomerNameAliasService.class),
            mock(MergeRequestFactPersistenceService.class),
            mock(ModuleDictionaryService.class),
            mock(FactBuildTaskService.class),
            publicationStateService,
            mock(GitlabSourceSchemaGuard.class),
            mock(GitlabConfigService.class),
            publicationService(),
            new GitlabFactSourceSqlProvider(),
            mock(GitlabFactSourceQueryExecutor.class),
            mock(IssuePhaseCalendarLoader.class),
            new IssueFactSourceRowMapper(),
            new MergeRequestFactSourceRowMapper(),
            searchIndexRepairService,
            new FactPublicationTransaction(
                new FactTaskExecutionGuard(jdbcTemplate, new FactTaskExecutionContext())),
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

    Map<String, Object> after = currentRowSnapshot(targetFactId);
    assertThat(after.get("is_response_delayed")).isEqualTo(true);
    assertThat(after.get("response_overdue")).isEqualTo(true);
    assertThat(after.get("updated_at")).isNotEqualTo(before.get("updated_at"));
    assertThat(changedColumns(before, after)).isEmpty();
    assertThat(memberRows()).isEqualTo(membersBefore);
    assertThat(rowSnapshot(targetFactId).get("is_response_delayed"))
        .as("旧完整修订的业务内容不能被延期重算修改").isEqualTo(false);
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
            "# 问题调研情况说明\n## 计划解决时间：2026.04.08",
            false,
            false,
            false,
            "now() - interval '100 days'");
    Map<String, Object> before = rowSnapshot(factId);

    service.refreshCustomerIssueDelayFactsForConfig(config());

    assertThat(rowSnapshot(factId)).isEqualTo(before);
  }

  @Test
  void shouldMarkResponseDelayWhenResponseTemplateHasNoLegalPlanSolutionTime() {
    insertSourceIssue(9003L, CC_PROJECT_ID, 6L);
    long factId =
        insertIssueFact(
            CC_PROJECT_ID,
            6L,
            "opened",
            "# 问题调研情况说明\n## 问题原因：已定位",
            false,
            false,
            false,
            "now() - interval '100 days'");
    Map<String, Object> before = rowSnapshot(factId);

    service.refreshCustomerIssueDelayFactsForConfig(config());

    Map<String, Object> after = currentRowSnapshot(factId);
    assertThat(after.get("is_response_delayed")).isEqualTo(true);
    assertThat(after.get("response_overdue")).isEqualTo(true);
    assertThat(after.get("is_resolve_delayed")).isEqualTo(false);
    assertThat(changedColumns(before, after)).isEmpty();
  }

  @Test
  void shouldClearResponseDelayOnceALegalPlanSolutionTimeAppears() {
    insertSourceIssue(9004L, CC_PROJECT_ID, 7L);
    long factId =
        insertIssueFact(
            CC_PROJECT_ID,
            7L,
            "opened",
            "# 问题调研情况说明\n## 计划解决时间：2026.04.08",
            true,
            true,
            false,
            "now() - interval '100 days'");

    service.refreshCustomerIssueDelayFactsForConfig(config());

    Map<String, Object> after = currentRowSnapshot(factId);
    assertThat(after.get("is_response_delayed")).isEqualTo(false);
    assertThat(after.get("response_overdue")).isEqualTo(false);
  }

  private FactTargetPublicationService publicationService() {
    FactTargetPublicationService publication = mock(FactTargetPublicationService.class);
    when(publication.publishWithinCandidate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> {
          java.util.function.Function<com.data.collection.platform.entity.read.FactRevisionTarget,
              com.data.collection.platform.entity.FactBuildResponse> body = invocation.getArgument(1);
          bootstrap();
          return FactRevisionFixture.publishFacts(jdbcTemplate,
              com.data.collection.platform.entity.read.PublicationFactTable.ISSUE, "default", false, body);
        });
    return publication;
  }

  private void bootstrap() {
    var repository = new com.data.collection.platform.service.read.ReadPublicationRepository(jdbcTemplate);
    var publication = new com.data.collection.platform.service.read.ReadPublicationService(repository,
        new com.data.collection.platform.config.ReadFallbackProperties());
    new org.springframework.transaction.support.TransactionTemplate(
        new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbcTemplate.getDataSource()))
        .executeWithoutResult(status -> publication.bootstrapInitialVersion(
            com.data.collection.platform.entity.read.ReadPublicationDomainKey.gitlabFact("default",
                com.data.collection.platform.entity.FactType.ISSUE), "GITLAB", java.time.Instant.now(),
            "{\"fixture\":true}", 0,
            key -> com.data.collection.platform.service.read.InitialVersionVerification.VerificationResult.accepted()));
  }

  private Map<String, Object> currentRowSnapshot(long oldId) {
    return jdbcTemplate.queryForMap("select current.* from issue_fact current join issue_fact old"
        + " on current.source_system = old.source_system and current.source_instance = old.source_instance"
        + " and current.project_id = old.project_id and current.issue_id = old.issue_id"
        + " where old.id = ? and current.visible_from is not null and current.visible_to is null", oldId);
  }

  private GitlabSyncConfig config() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setId(1L);
    config.setSourceInstance("default");
    return config;
  }

  private Map<String, Object> rowSnapshot(long factId) {
    bootstrap();
    return jdbcTemplate.queryForMap("select * from issue_fact where id = ?", factId);
  }

  private List<Map<String, Object>> memberRows() {
    return jdbcTemplate.queryForList(
        "select m.source_system, m.source_instance, m.project_id, m.issue_id, m.customer_name"
          + " from issue_fact_customer_members m join issue_fact f on f.id = m.issue_fact_revision_id"
          + " where f.visible_to is null order by m.project_id, m.issue_id, m.customer_name");
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
