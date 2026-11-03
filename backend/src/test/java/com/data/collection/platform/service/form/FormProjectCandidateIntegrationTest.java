package com.data.collection.platform.service.form;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.form.admin.FormAdminViews;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.service.sync.SyncRunCompletionEvent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    properties = {
      "platform.background-jobs.enabled=false",
      "platform.form-directory-event.worker-enabled=false",
      "platform.form-publication.worker-enabled=false",
      "platform.gitlab-mirror.scheduler-enabled=false",
      "platform.gitlab-mirror.delete-reconciliation-enabled=false",
      "platform.gitlab-mirror.customer-issue-delay-pre-writeback-sync-enabled=false",
      "platform.gitlab-mirror.customer-issue-delay-writeback-worker-enabled=false",
      "platform.gitlab-mirror.code-review-metric-enrichment-enabled=false",
      "platform.review-data.search-index-backfill-enabled=false",
      "platform.auth.provider=local",
      "platform.auth.secure-config-required=false"
    })
class FormProjectCandidateIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("isolated-test");

  @DynamicPropertySource
  static void registerIsolatedDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "?currentSchema=public");
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.schemas", () -> "public");
    registry.add("spring.flyway.default-schema", () -> "public");
  }

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FormProjectCandidateService candidateService;

  private long sourceA;
  private long sourceB;
  private long projectTaskA;
  private long namespaceTaskA;
  private long projectTaskB;
  private long namespaceTaskB;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("delete from gitlab_sync_configs where name like 'w4-candidate-%'");
    jdbcTemplate.execute("delete from code_review_dgm_project_options");
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_namespaces(
            id bigint primary key,
            path varchar(255),
            parent_id bigint,
            mirror_task_id bigint,
            mirror_deleted boolean default false)
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_projects(
            id bigint primary key,
            name varchar(255),
            path varchar(255),
            namespace_id bigint,
            mirror_task_id bigint,
            mirror_deleted boolean default false)
        """);
    jdbcTemplate.execute("delete from ods_gitlab_projects");
    jdbcTemplate.execute("delete from ods_gitlab_namespaces");
    sourceA = createSource("w4-candidate-a", "default");
    sourceB = createSource("w4-candidate-b", "secondary");
    projectTaskA = createMirrorTask(sourceA, "ods_gitlab_projects");
    namespaceTaskA = createMirrorTask(sourceA, "ods_gitlab_namespaces");
    projectTaskB = createMirrorTask(sourceB, "ods_gitlab_projects");
    namespaceTaskB = createMirrorTask(sourceB, "ods_gitlab_namespaces");
    insertNamespace(1, "team", null, namespaceTaskA);
  }

  @Test
  void searchFindsPinyinAndPathMatchesBeforePagination() {
    for (long id = 1; id <= 25; id++) {
      insertProject(id, "Project " + id, "project-" + id, 1);
    }
    insertProject(30, "支付网关", "payment-gateway", 1);
    candidateService.refresh(sourceA);

    FormAdminViews.ProjectCandidatePage firstPage =
        candidateService.search(sourceA, null, 20, null, null);
    assertThat(firstPage.items()).hasSize(20);
    assertThat(firstPage.items()).noneMatch(item -> item.projectId().equals("30"));

    FormAdminViews.ProjectCandidatePage pinyin =
        candidateService.search(sourceA, "zhifu", 20, null, null);
    assertThat(pinyin.items()).extracting(FormAdminViews.ProjectOption::projectId).containsExactly("30");
    assertThat(pinyin.items().getFirst().fullPath()).isEqualTo("team/payment-gateway");

    FormAdminViews.ProjectCandidatePage path =
        candidateService.search(sourceA, "team/payment", 20, null, null);
    assertThat(path.items()).extracting(FormAdminViews.ProjectOption::projectId).containsExactly("30");
  }

  @Test
  void sameNameProjectsStayDistinctByIdentityAndEchoReportsMissingIds() {
    insertNamespace(2, "other", null, namespaceTaskA);
    insertProject(101, "Shared Project", "shared", 1);
    insertProject(202, "Shared Project", "shared", 2);
    candidateService.refresh(sourceA);

    FormAdminViews.ProjectCandidatePage shared =
        candidateService.search(sourceA, "shared", 20, null, null);
    assertThat(shared.items())
        .extracting(FormAdminViews.ProjectOption::projectId)
        .containsExactly("101", "202");
    assertThat(shared.items())
        .extracting(FormAdminViews.ProjectOption::fullPath)
        .containsExactly("team/shared", "other/shared");

    FormAdminViews.ProjectCandidatePage echo =
        candidateService.search(sourceA, null, 20, null, List.of(101L, 999L));
    assertThat(echo.items()).extracting(FormAdminViews.ProjectOption::projectId).containsExactly("101");
    assertThat(echo.missingProjectIds()).containsExactly("999");
  }

  @Test
  void unrefreshedSourceReportsUnavailableUntilSuccessfulSyncEventRefreshesIt() {
    insertProject(301, "Event Project", "event-project", 1);
    assertThat(candidateService.search(sourceA, null, 20, null, null).available()).isFalse();

    new FormProjectCandidateRefreshListener(candidateService)
        .onSyncRunCompleted(
            new SyncRunCompletionEvent(
                1L, sourceA, "w4-candidate-a", SyncRunType.FULL_SYNC, SyncRunStatus.SUCCESS, 1L));

    FormAdminViews.ProjectCandidatePage page =
        candidateService.search(sourceA, "event", 20, null, null);
    assertThat(page.available()).isTrue();
    assertThat(page.status()).isEqualTo("READY");
    assertThat(page.items()).extracting(FormAdminViews.ProjectOption::projectId).containsExactly("301");
  }

  @Test
  void refreshingOneSourceDoesNotPublishAnotherConfigProjection() {
    insertProject(501, "Scoped Project", "scoped", 1);
    candidateService.refresh(sourceA);

    assertThat(candidateService.search(sourceA, null, 20, null, null).available()).isTrue();
    assertThat(candidateService.search(sourceB, null, 20, null, null).available()).isFalse();
  }

  @Test
  void refreshIsIdempotentAndRejectsUnknownSource() {
    insertProject(401, "Repeat Project", "repeat", 1);
    candidateService.refresh(sourceA);
    candidateService.refresh(sourceA);
    assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from form_project_candidates where source_config_id = ?",
                Integer.class,
                sourceA))
        .isEqualTo(1);

    assertThatThrownBy(() -> candidateService.search(999999L, null, 20, null, null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("来源配置不存在");
  }

  @Test
  void dgmSourceReadsOnlyItsOwnDirectoryAndKeepsSameIdDistinct() {
    jdbcTemplate.update(
        "update gitlab_sync_configs set source_instance = 'dgm' where id = ?", sourceB);
    insertProject(901, "ODS Only", "ods-only", 1);
    insertDgmProject(901, "DGM Only", "dgm-only", "dgm-team/dgm-only");
    insertDgmProject(902, "DGM Nested", "nested", "dgm-team/sub/nested");

    candidateService.refresh(sourceA);
    candidateService.refresh(sourceB);

    assertThat(candidateService.search(sourceA, null, 20, null, null).items())
        .extracting(FormAdminViews.ProjectOption::name)
        .containsExactly("ODS Only");
    FormAdminViews.ProjectCandidatePage dgm =
        candidateService.search(sourceB, null, 20, null, null);
    assertThat(dgm.items())
        .extracting(FormAdminViews.ProjectOption::projectId)
        .containsExactly("901", "902");
    assertThat(dgm.items())
        .extracting(FormAdminViews.ProjectOption::fullPath)
        .containsExactly("dgm-team/dgm-only", "dgm-team/sub/nested");
  }

  @Test
  void nestedNamespaceKeepsRootPathAndBrokenLineageIsExcluded() {
    insertNamespace(2, "sub", 1L, namespaceTaskA);
    insertProject(99, "Nested", "project", 2);
    candidateService.refresh(sourceA);
    assertThat(candidateService.search(sourceA, null, 20, null, null).items().getFirst().fullPath())
        .isEqualTo("team/sub/project");

    insertNamespace(3, "orphan", 999L, namespaceTaskA);
    insertNamespace(4, "cycle-a", 5L, namespaceTaskA);
    insertNamespace(5, "cycle-b", 4L, namespaceTaskA);
    insertNamespace(6, "foreign", null, namespaceTaskB);
    insertProject(100, "Orphan", "orphan-project", 3);
    insertProject(101, "Cycle", "cycle-project", 4);
    insertProject(102, "Foreign", "foreign-project", 6);
    candidateService.refresh(sourceA);
    assertThat(candidateService.search(sourceA, null, 20, null, null).items())
        .extracting(FormAdminViews.ProjectOption::projectId)
        .containsExactly("99");
  }

  @Test
  void successfullyRefreshedEmptyDirectoryIsAvailableWithNoOptions() {
    candidateService.refresh(sourceA);

    FormAdminViews.ProjectCandidatePage page =
        candidateService.search(sourceA, null, 20, null, null);
    assertThat(page.available()).as("同步成功的空目录不能显示尚未同步").isTrue();
    assertThat(page.status()).isEqualTo("EMPTY");
    assertThat(page.items()).isEmpty();
  }

  @Test
  void failedRefreshKeepsLastSuccessfulSnapshotAndReportsFailure() {
    insertProject(701, "Preserved", "preserved", 1);
    candidateService.refresh(sourceA);
    jdbcTemplate.execute("alter table ods_gitlab_namespaces drop column parent_id");
    try {
      candidateService.refresh(sourceA);

      FormAdminViews.ProjectCandidatePage page =
          candidateService.search(sourceA, null, 20, null, null);
      assertThat(page.available()).as("失败时保留最后成功快照").isTrue();
      assertThat(page.status()).isEqualTo("FAILED");
      assertThat(page.message()).contains("parent_id");
      assertThat(page.items()).extracting(FormAdminViews.ProjectOption::projectId).containsExactly("701");
    } finally {
      jdbcTemplate.execute("alter table ods_gitlab_namespaces add column parent_id bigint");
    }
  }

  @Test
  void startupInitializationBuildsExistingDirectoryProjection() {
    insertProject(801, "Startup", "startup", 1);
    jdbcTemplate.update("delete from form_project_candidates where source_config_id = ?", sourceA);
    jdbcTemplate.update("delete from form_project_candidate_states where source_config_id = ?", sourceA);

    new FormProjectCandidateStartupInitializer(candidateService).run(null);

    FormAdminViews.ProjectCandidatePage page =
        candidateService.search(sourceA, "startup", 20, null, null);
    assertThat(page.available()).isTrue();
    assertThat(page.items()).extracting(FormAdminViews.ProjectOption::projectId).containsExactly("801");
  }

  private long createSource(String name, String sourceInstance) {
    return jdbcTemplate.queryForObject(
        """
        insert into gitlab_sync_configs(
            name, source_instance, db_name, db_username, db_password, web_base_url)
        values (?, ?, 'qaflex', 'qaflex', 'secret', ?)
        returning id
        """,
        Long.class,
        name,
        sourceInstance,
        "http://gitlab.w4.local/" + UUID.randomUUID());
  }

  private long createMirrorTask(long configId, String mirrorTable) {
    String sourceTable = mirrorTable.substring("ods_gitlab_".length());
    String runKey = UUID.randomUUID().toString();
    long runId =
        jdbcTemplate.queryForObject(
            """
            insert into sync_runs(
                run_id, config_id, source_instance, run_type, trigger_type, status,
                exclusive_scope)
            values (?, ?, 'default', 'FULL_SYNC', 'MANUAL', 'SUCCESS', ?)
            returning id
            """,
            Long.class,
            runKey,
            configId,
            "scope-" + runKey);
    return jdbcTemplate.queryForObject(
        """
        insert into sync_run_table_tasks(
            run_id, config_id, source_instance, source_table, mirror_table, task_type, status,
            row_strategy)
        values (?, ?, 'default', ?, ?, 'MIRROR', 'SUCCESS', 'FULL')
        returning id
        """,
        Long.class,
        runId,
        configId,
        sourceTable,
        mirrorTable);
  }

  private void insertNamespace(long id, String path, Long parentId, long taskId) {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_namespaces(id, path, parent_id, mirror_task_id, mirror_deleted)
        values (?, ?, ?, ?, false)
        """,
        id,
        path,
        parentId,
        taskId);
  }

  private void insertProject(long id, String name, String path, long namespaceId) {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_projects(
            id, name, path, namespace_id, mirror_task_id, mirror_deleted)
        values (?, ?, ?, ?, ?, false)
        """,
        id,
        name,
        path,
        namespaceId,
        projectTaskA);
  }

  private void insertDgmProject(
      long projectId, String name, String path, String pathWithNamespace) {
    jdbcTemplate.update(
        """
        insert into code_review_dgm_project_options(
            source_instance, gitlab_project_id, name, path, path_with_namespace,
            namespace_full_path, active)
        values ('dgm', ?, ?, ?, ?, ?, true)
        """,
        projectId,
        name,
        path,
        pathWithNamespace,
        pathWithNamespace.substring(0, pathWithNamespace.lastIndexOf('/')));
  }
}
