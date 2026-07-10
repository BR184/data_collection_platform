package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class IssueFactFixUserBackfillMigrationTest {
  private static final String MIGRATION =
      "V20260710_01__backfill_issue_fact_fix_user.sql";

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("qaflex")
          .withUsername("qaflex")
          .withPassword("secret");

  @Test
  void shouldBackfillOnlyDefaultIssueFactsFromLatestValidFixComment() throws Exception {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          create table ods_gitlab_issues (
            id bigint primary key,
            project_id bigint not null,
            mirror_deleted boolean not null default false
          );
          create table ods_gitlab_users (
            id bigint primary key,
            name varchar(128),
            mirror_deleted boolean not null default false
          );
          create table ods_gitlab_notes (
            id bigint primary key,
            noteable_id bigint not null,
            noteable_type varchar(32) not null,
            author_id bigint,
            note text,
            created_at timestamp,
            mirror_deleted boolean not null default false
          );
          create table issue_fact (
            id bigint primary key,
            source_instance varchar(128) not null,
            project_id bigint not null,
            issue_id bigint not null,
            deleted boolean not null default false,
            fix_user varchar(128)
          );

          insert into ods_gitlab_issues(id, project_id) values (101, 9), (102, 9), (103, 9);
          insert into ods_gitlab_users(id, name, mirror_deleted) values
            (1, '较早修复人', false),
            (2, '最新修复人', false),
            (3, '已删除用户', true);
          insert into ods_gitlab_notes(
            id, noteable_id, noteable_type, author_id, note, created_at, mirror_deleted
          ) values
            (11, 101, 'Issue', 1, E'### 1、修复状态\n较早评论', timestamp '2026-07-01 09:00:00', false),
            (12, 101, 'Issue', 2, E'### 1、修复状态\r\n最新评论', timestamp '2026-07-02 09:00:00', false),
            (13, 102, 'Issue', 2, E'前缀\n### 1、修复状态', timestamp '2026-07-02 09:00:00', false),
            (14, 103, 'Issue', 3, E'### 1、修复状态\n无效作者', timestamp '2026-07-02 09:00:00', false);
          insert into issue_fact(id, source_instance, project_id, issue_id, fix_user) values
            (1, 'default', 9, 101, null),
            (2, 'legacy-compat', 9, 101, '兼容数据保持不变'),
            (3, 'default', 9, 102, null),
            (4, 'default', 9, 103, null);
          """);

      statement.execute(readMigration());

      assertThat(fixUser(statement, 1)).isEqualTo("最新修复人");
      assertThat(fixUser(statement, 2)).isEqualTo("兼容数据保持不变");
      assertThat(fixUser(statement, 3)).isNull();
      assertThat(fixUser(statement, 4)).isNull();
    }
  }

  private static String readMigration() throws Exception {
    return Files.readString(
        Path.of("src", "main", "resources", "db", "migration", MIGRATION),
        StandardCharsets.UTF_8);
  }

  private static String fixUser(Statement statement, long factId) throws Exception {
    try (ResultSet resultSet =
        statement.executeQuery("select fix_user from issue_fact where id = " + factId)) {
      assertThat(resultSet.next()).isTrue();
      return resultSet.getString(1);
    }
  }
}
