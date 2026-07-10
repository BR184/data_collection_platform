package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SourceMode;
import com.data.collection.platform.entity.WhitelistMode;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class IssueFactSourceInstancePipelineTest {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private GitlabConfigService configService;
  @Autowired private FactBuildService factBuildService;

  @BeforeEach
  void setUp() {
    createMinimalOdsTables();
    cleanTables();
    GitlabSyncConfig config = baseConfig();
    config.setSourceInstance("default");
    configService.saveConfig(config);
  }

  @Test
  void shouldBuildIssueFactsFromSingleMirrorTables() {
    LocalDateTime now = LocalDateTime.of(2026, 5, 7, 9, 0);
    jdbcTemplate.update(
        "insert into ods_gitlab_projects(id, name, mirror_deleted) values (?, ?, false)",
        100L,
        "CC_PRODUCT");
    jdbcTemplate.update(
        "insert into ods_gitlab_users(id, name, mirror_deleted) values (?, ?, false)",
        501L,
        "reviewer-a");
    jdbcTemplate.update(
        "insert into ods_gitlab_users(id, name, mirror_deleted) values (?, ?, false)",
        601L,
        "assignee-a");
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issues(
          id, iid, project_id, title, author_id, created_at, updated_at, closed_at, state_id, milestone_id, mirror_deleted
        ) values (?, ?, ?, ?, ?, ?, ?, null, ?, null, false)
        """,
        9001L,
        88L,
        100L,
        "single mirror issue",
        501L,
        now.minusHours(1),
        now,
        1);
    jdbcTemplate.update(
        "insert into ods_gitlab_issue_assignees(issue_id, user_id, mirror_deleted) values (?, ?, false)",
        9001L,
        601L);

    FactBuildResponse response = factBuildService.rebuildIssueFacts(true);

    assertThat(response.affectedRows()).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from issue_fact where source_instance = 'default' and issue_id = 9001",
        Integer.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "select assignee_name from issue_fact where source_instance = 'default' and issue_id = 9001",
        String.class)).isEqualTo("assignee-a");
  }

  @Test
  void shouldNormalizeModuleAndToolboxLabelsWhenBuildingIssueFacts() {
    LocalDateTime now = LocalDateTime.of(2026, 5, 7, 9, 0);
    jdbcTemplate.update(
        "insert into ods_gitlab_projects(id, name, mirror_deleted) values (?, ?, false)",
        101L,
        "CC_PRODUCT");
    jdbcTemplate.update(
        "insert into ods_gitlab_users(id, name, mirror_deleted) values (?, ?, false)",
        502L,
        "reviewer-b");
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issues(
          id, iid, project_id, title, author_id, created_at, updated_at, closed_at, state_id, milestone_id, mirror_deleted
        ) values (?, ?, ?, ?, ?, ?, ?, null, ?, null, false)
        """,
        9002L,
        89L,
        101L,
        "module normalized issue",
        502L,
        now.minusHours(1),
        now,
        1);
    insertLabel(1L, "模块：草图");
    insertLabel(2L, "工具箱：草图");
    insertLabel(3L, "9007");
    insertLabel(4L, "分支：发布");
    insertLabel(5L, "前端");
    insertLabel(6L, "模块：：草图");
    insertLabel(7L, "工程图");
    linkLabel(1L, 9002L);
    linkLabel(2L, 9002L);
    linkLabel(3L, 9002L);
    linkLabel(4L, 9002L);
    linkLabel(5L, 9002L);
    linkLabel(6L, 9002L);
    linkLabel(7L, 9002L);

    FactBuildResponse response = factBuildService.rebuildIssueFacts(true);

    assertThat(response.affectedRows()).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "select module_names from issue_fact where source_instance = 'default' and issue_id = 9002",
        String.class)).isEqualTo("草图");
  }

  @Test
  void shouldUseStrictFixTemplateReasonOnlyForCustomerIssues() {
    LocalDateTime now = LocalDateTime.of(2026, 7, 6, 9, 0);
    jdbcTemplate.update(
        "insert into ods_gitlab_projects(id, name, mirror_deleted) values (?, ?, false), (?, ?, false)",
        325L,
        "CC_Product",
        9L,
        "CrownCAD");
    jdbcTemplate.update(
        "insert into ods_gitlab_users(id, name, mirror_deleted) values (?, ?, false)",
        503L,
        "reviewer-c");
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issues(
          id, iid, project_id, title, author_id, created_at, updated_at, closed_at, state_id, milestone_id, mirror_deleted
        ) values
          (?, ?, ?, ?, ?, ?, ?, null, ?, null, false),
          (?, ?, ?, ?, ?, ?, ?, null, ?, null, false)
        """,
        9003L,
        90L,
        325L,
        "customer research-only reason",
        503L,
        now.minusHours(2),
        now,
        1,
        9004L,
        91L,
        9L,
        "system test fallback reason",
        503L,
        now.minusHours(1),
        now,
        1);
    String researchTemplate =
        "# 问题调研情况说明\n## 问题类型：\n* [x] 需求\n## 问题原因：新增需求";
    jdbcTemplate.update(
        """
        insert into ods_gitlab_notes(
          id, noteable_id, noteable_type, note, created_at, updated_at, mirror_deleted
        ) values
          (?, ?, 'Issue', ?, ?, ?, false),
          (?, ?, 'Issue', ?, ?, ?, false)
        """,
        7001L,
        9003L,
        researchTemplate,
        now,
        now,
        7002L,
        9004L,
        researchTemplate,
        now,
        now);

    FactBuildResponse response = factBuildService.rebuildIssueFacts(true);

    assertThat(response.affectedRows()).isEqualTo(2);
    assertThat(jdbcTemplate.queryForObject(
        "select reason_category from issue_fact where source_instance = 'default' and issue_id = 9003",
        String.class)).isNull();
    assertThat(jdbcTemplate.queryForObject(
        "select reason_category from issue_fact where source_instance = 'default' and issue_id = 9004",
        String.class)).isEqualTo("新增需求");
  }

  private void createMinimalOdsTables() {
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_projects (
          id bigint primary key,
          name varchar(255),
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_users (
          id bigint primary key,
          name varchar(255),
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_milestones (
          id bigint primary key,
          title varchar(255),
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_issues (
          id bigint primary key,
          iid bigint,
          project_id bigint,
          title varchar(512),
          author_id bigint,
          created_at timestamp,
          updated_at timestamp,
          closed_at timestamp,
          state_id integer,
          milestone_id bigint,
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_notes (
          id bigint primary key,
          noteable_id bigint,
          noteable_type varchar(64),
          note text,
          created_at timestamp,
          updated_at timestamp,
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_labels (
          id bigint primary key,
          title varchar(255),
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_label_links (
          id bigint primary key,
          label_id bigint,
          target_id bigint,
          target_type varchar(64),
          source_updated_at timestamp,
          updated_at timestamp,
          created_at timestamp,
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_issue_assignees (
          issue_id bigint,
          user_id bigint,
          mirror_deleted boolean default false
        )
        """);
  }

  private void cleanTables() {
    jdbcTemplate.update("delete from issue_fact");
    jdbcTemplate.update("delete from module_dictionary");
    jdbcTemplate.update("delete from testing_phase_calendar");
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

  private void insertLabel(long id, String title) {
    jdbcTemplate.update(
        "insert into ods_gitlab_labels(id, title, mirror_deleted) values (?, ?, false)",
        id,
        title);
  }

  private void linkLabel(long labelId, long issueId) {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_label_links(
          id, label_id, target_id, target_type, source_updated_at, updated_at, created_at, mirror_deleted
        ) values (?, ?, ?, 'Issue', current_timestamp, current_timestamp, current_timestamp, false)
        """,
        labelId,
        labelId,
        issueId);
  }

  private GitlabSyncConfig baseConfig() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setName("GitLab source");
    config.setEnabled(true);
    config.setAutoSyncEnabled(true);
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.CUSTOM);
    config.setWhitelistTables(List.of("issues", "issue_assignees", "projects", "users"));
    config.setDbHost("localhost");
    config.setDbPort(5432);
    config.setDbName("gitlabhq_production");
    config.setDbUsername("gitlab");
    config.setDbPassword("");
    config.setDockerContainerName("gitlab-data-web-1");
    config.setCompensationIntervalMinutes(60);
    return config;
  }
}
