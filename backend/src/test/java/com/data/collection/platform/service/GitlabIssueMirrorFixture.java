package com.data.collection.platform.service;

import org.springframework.jdbc.core.JdbcTemplate;

/** 为 GitLab 事实管道集成测试提供唯一的最小镜像表结构。 */
final class GitlabIssueMirrorFixture {
  private GitlabIssueMirrorFixture() {}

  static void ensureSchema(JdbcTemplate jdbcTemplate) {
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_projects (
          id bigint primary key,
          name varchar(255),
          path varchar(255),
          namespace_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute("alter table ods_gitlab_projects add column if not exists path varchar(255)");
    jdbcTemplate.execute(
        "alter table ods_gitlab_projects add column if not exists namespace_id bigint");
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
          project_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        "alter table ods_gitlab_milestones add column if not exists project_id bigint");
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
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_resource_label_events (
          id bigint primary key,
          issue_id bigint,
          merge_request_id bigint,
          label_id bigint,
          action smallint,
          created_at timestamp,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_merge_requests (
          id bigint primary key,
          iid bigint,
          target_project_id bigint,
          title varchar(512),
          author_id bigint,
          merge_user_id bigint,
          target_branch varchar(255),
          source_branch varchar(255),
          created_at timestamp,
          updated_at timestamp,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_merge_request_metrics (
          merge_request_id bigint,
          merged_at timestamp,
          added_lines integer,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_namespaces (
          id bigint primary key,
          path varchar(255),
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_merge_request_reviewers (
          merge_request_id bigint,
          user_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_merge_request_assignees (
          merge_request_id bigint,
          user_id bigint,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_merge_request_diffs (
          id bigint primary key,
          merge_request_id bigint,
          created_at timestamp,
          updated_at timestamp,
          mirror_deleted boolean not null default false
        )
        """);
    jdbcTemplate.execute(
        """
        create table if not exists ods_gitlab_merge_request_diff_commits (
          merge_request_diff_id bigint,
          relative_order integer,
          sha varchar(64),
          committed_date timestamp,
          mirror_deleted boolean not null default false
        )
        """);
  }
}
