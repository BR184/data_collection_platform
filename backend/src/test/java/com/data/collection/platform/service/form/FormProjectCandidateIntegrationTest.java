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
  @Autowired private FormProjectCandidateRefreshListener refreshListener;

  private long sourceA;
  private long sourceB;

  @BeforeEach
  void setUp() {
    jdbcTemplate.execute("delete from gitlab_sync_configs where name like 'w4-candidate-%'");
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_namespaces(
            id bigint primary key,
            path varchar(255),
            mirror_deleted boolean default false)
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_projects(
            id bigint primary key,
            name varchar(255),
            path varchar(255),
            namespace_id bigint,
            mirror_deleted boolean default false)
        """);
    jdbcTemplate.execute("delete from ods_gitlab_projects");
    jdbcTemplate.execute("delete from ods_gitlab_namespaces");
    jdbcTemplate.update(
        "insert into ods_gitlab_namespaces(id, path, mirror_deleted) values (1, 'team', false)");
    sourceA = createSource("w4-candidate-a");
    sourceB = createSource("w4-candidate-b");  }

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
    jdbcTemplate.update(
        "insert into ods_gitlab_namespaces(id, path, mirror_deleted) values (2, 'other', false)");
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

    refreshListener.onSyncRunCompleted(
        new SyncRunCompletionEvent(
            1L, sourceA, "w4-candidate-a", SyncRunType.FULL_SYNC, SyncRunStatus.SUCCESS, 1L));

    FormAdminViews.ProjectCandidatePage page =
        candidateService.search(sourceA, "event", 20, null, null);
    assertThat(page.available()).isTrue();
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

  private long createSource(String name) {
    return jdbcTemplate.queryForObject(
        """
        insert into gitlab_sync_configs(
            name, source_instance, db_name, db_username, db_password, web_base_url)
        values (?, ?, 'qaflex', 'qaflex', 'secret', ?)
        returning id
        """,
        Long.class,
        name,
        name,
        "http://gitlab.w4.local/" + UUID.randomUUID());
  }

  private void insertProject(long id, String name, String path, long namespaceId) {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_projects(id, name, path, namespace_id, mirror_deleted)
        values (?, ?, ?, ?, false)
        """,
        id,
        name,
        path,
        namespaceId);
  }
}
