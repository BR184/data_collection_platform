package com.data.collection.platform.service;

import org.springframework.jdbc.core.JdbcTemplate;

/** 为 Issue 事实管道集成测试提供唯一的 GitLab 镜像表结构。 */
final class GitlabIssueMirrorFixture {
  private GitlabIssueMirrorFixture() {}

  static void ensureSchema(JdbcTemplate jdbcTemplate) {
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_projects (
          id bigint primary key,
          name varchar(255),
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_users (
          id bigint primary key,
          name varchar(255),
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_milestones (
          id bigint primary key,
          title varchar(255),
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_issues (
          id bigint primary key,
          iid bigint,
          project_id bigint,
          title varchar(512),
          description text,
          author_id bigint,
          created_at timestamp,
          updated_at timestamp,
          closed_at timestamp,
          state_id integer,
          milestone_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_notes (
          id bigint primary key,
          noteable_id bigint,
          noteable_type varchar(64),
          author_id bigint,
          note text,
          created_at timestamp,
          updated_at timestamp,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_labels (
          id bigint primary key,
          title varchar(255),
          color varchar(32),
          mirror_deleted boolean not null default false
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
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_issue_assignees (
          issue_id bigint,
          user_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
  }
}
