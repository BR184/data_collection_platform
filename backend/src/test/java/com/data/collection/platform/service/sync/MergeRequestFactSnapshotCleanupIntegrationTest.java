package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.data.collection.platform.entity.MergeRequestCommitFact;
import com.data.collection.platform.entity.MergeRequestFact;
import com.data.collection.platform.mapper.MergeRequestFactMapper;
import com.data.collection.platform.service.MergeRequestFactPersistenceService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MR 与提交关系快照外清理的真实数据库回归：快照规模跨过任意内部分片边界时，快照内行必须全部保留。
 */
class MergeRequestFactSnapshotCleanupIntegrationTest {
  private static final int SNAPSHOT_SIZE = 1200;
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate transactionTemplate;
  private MergeRequestFactPersistenceService service;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("mr_snapshot_cleanup_test");
  }

  @AfterAll
  static void closeDatabase() {
    if (database != null) {
      database.close();
    }
  }

  @BeforeEach
  void setUp() {
    // 事务管理器与 JdbcTemplate 必须共享同一 DataSource 实例，临时表才与事务绑定在同一连接。
    DataSource dataSource = database.dataSource();
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("drop table if exists merge_request_commit_fact cascade");
    jdbcTemplate.execute("drop table if exists merge_request_fact cascade");
    jdbcTemplate.execute(
        """
        create table merge_request_fact (
          source_system varchar(32) not null,
          source_instance varchar(128) not null,
          project_id bigint,
          merge_request_id bigint
        )
        """);
    jdbcTemplate.execute(
        """
        create table merge_request_commit_fact (
          source_system varchar(32) not null,
          source_instance varchar(128) not null,
          project_id bigint,
          merge_request_id bigint,
          commit_sha varchar(64)
        )
        """);
    transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    service =
        new MergeRequestFactPersistenceService(
            mock(MergeRequestFactMapper.class), jdbcTemplate);
  }

  @Test
  void test_cleanup_keeps_every_snapshot_mr_and_commit_and_deletes_outside_rows() {
    for (long mrId = 1; mrId <= SNAPSHOT_SIZE; mrId++) {
      jdbcTemplate.update(
          "insert into merge_request_fact(source_system, source_instance, project_id, merge_request_id)"
              + " values ('GITLAB', 'default', 9, ?)",
          mrId);
      jdbcTemplate.update(
          "insert into merge_request_commit_fact(source_system, source_instance, project_id,"
              + " merge_request_id, commit_sha) values ('GITLAB', 'default', 9, ?, ?)",
          mrId,
          "sha-" + mrId);
    }
    jdbcTemplate.update(
        "insert into merge_request_fact(source_system, source_instance, project_id, merge_request_id)"
            + " values ('GITLAB', 'default', 8, 777)");
    jdbcTemplate.update(
        "insert into merge_request_commit_fact(source_system, source_instance, project_id,"
            + " merge_request_id, commit_sha) values ('GITLAB', 'default', 8, 777, 'stale-sha')");

    List<MergeRequestFact> snapshot = new ArrayList<>(SNAPSHOT_SIZE);
    List<MergeRequestCommitFact> commitSnapshot = new ArrayList<>(SNAPSHOT_SIZE);
    for (long mrId = 1; mrId <= SNAPSHOT_SIZE; mrId++) {
      MergeRequestFact fact = new MergeRequestFact();
      fact.setProjectId(9L);
      fact.setMergeRequestId(mrId);
      snapshot.add(fact);
      commitSnapshot.add(
          new MergeRequestCommitFact(
              "GITLAB", "default", 9L, mrId, mrId, "sha-" + mrId, LocalDateTime.now()));
    }
    transactionTemplate.executeWithoutResult(
        status -> service.deleteFactsNotInSnapshot("GITLAB", "default", snapshot, commitSnapshot));

    Long keptMrs =
        jdbcTemplate.queryForObject(
            "select count(*) from merge_request_fact where source_instance = 'default'", Long.class);
    assertThat(keptMrs).isEqualTo((long) SNAPSHOT_SIZE);
    Long outsideMrGone =
        jdbcTemplate.queryForObject(
            "select count(*) from merge_request_fact where project_id = 8", Long.class);
    assertThat(outsideMrGone).isZero();
    Long keptCommits =
        jdbcTemplate.queryForObject(
            "select count(*) from merge_request_commit_fact where source_instance = 'default'",
            Long.class);
    assertThat(keptCommits).isEqualTo((long) SNAPSHOT_SIZE);
    Long outsideCommitGone =
        jdbcTemplate.queryForObject(
            "select count(*) from merge_request_commit_fact where project_id = 8", Long.class);
    assertThat(outsideCommitGone).isZero();
  }
}
