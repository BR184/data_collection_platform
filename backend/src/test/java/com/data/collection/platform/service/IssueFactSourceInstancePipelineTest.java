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
    createMinimalCcOdsTables();
    cleanTables();
    GitlabSyncConfig config = baseConfig();
    config.setSourceInstance("cc");
    configService.saveConfig(config);
  }

  @Test
  void shouldBuildIssueFactsFromSourceSpecificMirrorTables() {
    LocalDateTime now = LocalDateTime.of(2026, 5, 7, 9, 0);
    jdbcTemplate.update(
        "insert into ods_gitlab_cc_projects(id, name, mirror_deleted) values (?, ?, false)",
        100L,
        "CC_PRODUCT");
    jdbcTemplate.update(
        "insert into ods_gitlab_cc_users(id, name, mirror_deleted) values (?, ?, false)",
        501L,
        "reviewer-a");
    jdbcTemplate.update(
        """
        insert into ods_gitlab_cc_issues(
          id, iid, project_id, title, author_id, created_at, updated_at, closed_at, state_id, milestone_id, mirror_deleted
        ) values (?, ?, ?, ?, ?, ?, ?, null, ?, null, false)
        """,
        9001L,
        88L,
        100L,
        "source isolated issue",
        501L,
        now.minusHours(1),
        now,
        1);

    FactBuildResponse response = factBuildService.rebuildIssueFacts(true);

    assertThat(response.affectedRows()).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from issue_fact where source_instance = 'cc' and issue_id = 9001",
        Integer.class)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
        "select count(*) from issue_fact where source_instance = 'default' and issue_id = 9001",
        Integer.class)).isZero();
  }

  private void createMinimalCcOdsTables() {
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_cc_projects (
          id bigint primary key,
          name varchar(255),
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_cc_users (
          id bigint primary key,
          name varchar(255),
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_cc_milestones (
          id bigint primary key,
          title varchar(255),
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_cc_issues (
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
        create table if not exists ods_gitlab_cc_notes (
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
        create table if not exists ods_gitlab_cc_labels (
          id bigint primary key,
          title varchar(255),
          mirror_deleted boolean default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_cc_label_links (
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
  }

  private void cleanTables() {
    jdbcTemplate.update("delete from issue_fact");
    jdbcTemplate.update("delete from module_dictionary");
    jdbcTemplate.update("delete from testing_phase_calendar");
    jdbcTemplate.update("delete from gitlab_sync_configs");
    jdbcTemplate.update("delete from ods_gitlab_cc_label_links");
    jdbcTemplate.update("delete from ods_gitlab_cc_labels");
    jdbcTemplate.update("delete from ods_gitlab_cc_notes");
    jdbcTemplate.update("delete from ods_gitlab_cc_issues");
    jdbcTemplate.update("delete from ods_gitlab_cc_milestones");
    jdbcTemplate.update("delete from ods_gitlab_cc_users");
    jdbcTemplate.update("delete from ods_gitlab_cc_projects");
  }

  private GitlabSyncConfig baseConfig() {
    GitlabSyncConfig config = new GitlabSyncConfig();
    config.setName("GitLab CC source");
    config.setEnabled(true);
    config.setAutoSyncEnabled(true);
    config.setSourceMode(SourceMode.DOCKER);
    config.setWhitelistMode(WhitelistMode.CUSTOM);
    config.setWhitelistTables(List.of("issues", "projects", "users"));
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
