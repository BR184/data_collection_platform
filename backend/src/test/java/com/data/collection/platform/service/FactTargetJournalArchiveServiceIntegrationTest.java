package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.service.sync.PostgresIntegrationTestDatabase;
import com.data.collection.platform.service.sync.SyncFactPublicationStateService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 事实发布会话日志归档的语义安全性。
 *
 * <p>归档只删除"已过保留窗口且已被版本栅栏覆盖"的行；本测试锁定两条不变量：未发布切片完整保留，
 * 且归档前后"尚未发布的稳定根数量"完全不变。
 */
class FactTargetJournalArchiveServiceIntegrationTest {
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private GitlabMirrorProperties properties;
  private FactTargetJournalArchiveService archiveService;
  private SyncFactPublicationStateService publicationStateService;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("fact_target_journal_archive_test");
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeEach
  void setUp() {
    jdbcTemplate = new JdbcTemplate(database.dataSource());
    resetSchema();
    properties = new GitlabMirrorProperties();
    properties.setFactTargetJournalRetentionDays(30);
    properties.setFactTargetJournalArchivePageSize(2);
    properties.setFactTargetJournalArchiveBatchesPerRun(10);
    archiveService = new FactTargetJournalArchiveService(jdbcTemplate, properties);
    publicationStateService = new SyncFactPublicationStateService(jdbcTemplate, null);
    seed();
  }

  @Test
  void test_archive_removes_only_rows_covered_by_the_version_fence_outside_retention() {
    long unpublishedBefore = publicationStateService.countUnpublishedTargets("alpha", FactType.ISSUE);

    int deleted = archiveService.archiveExpiredBatches();

    assertThat(deleted).as("只有 (旧且已覆盖) 的两行可归档").isEqualTo(2);
    assertThat(journalKeys())
        .as("保留窗口内的行、以及栅栏未覆盖的行必须完整保留")
        .containsExactly("3/602", "4/603", "5/601");
    assertThat(publicationStateService.countUnpublishedTargets("alpha", FactType.ISSUE))
        .as("归档不得改变未发布根数量")
        .isEqualTo(unpublishedBefore);
  }

  @Test
  void test_archive_is_limit_bounded_per_run() {
    properties.setFactTargetJournalArchivePageSize(1);
    properties.setFactTargetJournalArchiveBatchesPerRun(1);

    int deleted = archiveService.archiveExpiredBatches();

    assertThat(deleted).as("单次巡检只允许提交一批").isEqualTo(1);
    assertThat(journalKeys()).hasSize(4);
  }

  @Test
  void test_archive_is_idempotent_after_the_window_is_cleared() {
    assertThat(archiveService.archiveExpiredBatches()).isEqualTo(2);

    assertThat(archiveService.archiveExpiredBatches()).isZero();
    assertThat(journalKeys()).containsExactly("3/602", "4/603", "5/601");
  }

  private void seed() {
    jdbcTemplate.update(
        """
        insert into fact_change_heads(
            source_instance, fact_type, root_id, project_id,
            latest_change_version, published_version) values
          ('alpha', 'ISSUE', 601, 42, 10, 10),
          ('alpha', 'ISSUE', 602, 42, 20, 12),
          ('alpha', 'ISSUE', 603, 42, 30, 6)
        """);
    jdbcTemplate.update(
        """
        insert into sync_run_fact_targets(
            mirror_run_id, source_instance, fact_type, root_id,
            change_version, publication_status, updated_at) values
          (1, 'alpha', 'ISSUE', 601, 10, 'PUBLISHED', current_timestamp - interval '40 days'),
          (2, 'alpha', 'ISSUE', 602, 11, 'PUBLISHED', current_timestamp - interval '40 days'),
          (3, 'alpha', 'ISSUE', 602, 19, 'PUBLISHED', current_timestamp - interval '40 days'),
          (4, 'alpha', 'ISSUE', 603, 30, 'PUBLISHED', current_timestamp - interval '40 days'),
          (5, 'alpha', 'ISSUE', 601, 10, 'PUBLISHED', current_timestamp - interval '1 day')
        """);
  }

  private java.util.List<String> journalKeys() {
    return jdbcTemplate.queryForList(
        """
        select mirror_run_id || '/' || root_id as journal_key
          from sync_run_fact_targets
         order by mirror_run_id
        """,
        String.class);
  }

  private void resetSchema() {
    jdbcTemplate.execute("drop table if exists sync_run_fact_targets cascade");
    jdbcTemplate.execute("drop table if exists fact_change_heads cascade");
    jdbcTemplate.execute(
        """
        create table fact_change_heads (
          source_instance varchar(128) not null,
          fact_type varchar(64) not null,
          root_id bigint not null,
          project_id bigint,
          latest_change_version bigint not null,
          published_version bigint not null default 0,
          published_by_fact_build_task_id bigint,
          primary key (source_instance, fact_type, root_id)
        )
        """);
    jdbcTemplate.execute(
        """
        create table sync_run_fact_targets (
          mirror_run_id bigint not null,
          source_instance varchar(128) not null,
          fact_type varchar(64) not null,
          root_id bigint not null,
          change_version bigint not null,
          publication_status varchar(32) not null,
          assigned_fact_run_id bigint,
          assigned_fact_build_task_id bigint,
          published_version bigint,
          published_by_fact_build_task_id bigint,
          published_at timestamp,
          created_at timestamp not null default current_timestamp,
          updated_at timestamp not null default current_timestamp,
          primary key (mirror_run_id, source_instance, fact_type, root_id)
        )
        """);
  }
}
