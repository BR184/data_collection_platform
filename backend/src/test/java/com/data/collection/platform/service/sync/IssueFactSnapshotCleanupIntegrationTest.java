package com.data.collection.platform.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.data.collection.platform.entity.IssueFact;
import com.data.collection.platform.mapper.IssueFactMapper;
import com.data.collection.platform.service.IssueFactPersistenceService;
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
 * 快照外事实清理的真实数据库回归：快照规模跨过任意内部分片边界时，快照内行必须全部保留。
 *
 * <p>锁定的缺陷形态：清理按身份分片循环执行「删除不在本片身份集的行」，多片时各片互相清除，
 * 黄金基线 1200 行夹具曾被整体清空。
 */
class IssueFactSnapshotCleanupIntegrationTest {
  private static final int SNAPSHOT_SIZE = 1200;
  private static PostgresIntegrationTestDatabase database;

  private JdbcTemplate jdbcTemplate;
  private TransactionTemplate transactionTemplate;
  private IssueFactPersistenceService service;

  @BeforeAll
  static void setUpDatabase() {
    database = PostgresIntegrationTestDatabase.open("issue_snapshot_cleanup_test");
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
    jdbcTemplate.execute("drop table if exists issue_fact_customer_members cascade");
    jdbcTemplate.execute("drop table if exists issue_fact cascade");
    jdbcTemplate.execute(
        """
        create table issue_fact (
          source_system varchar(32) not null,
          source_instance varchar(128) not null,
          project_id bigint,
          issue_id bigint
        )
        """);
    jdbcTemplate.execute(
        """
        create table issue_fact_customer_members (
          source_system varchar(32) not null,
          source_instance varchar(128) not null,
          project_id bigint,
          issue_id bigint
        )
        """);
    transactionTemplate =
        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    // 快照外清理只依赖 jdbcTemplate；客户成员仓储为包私有类型，此处无需替身。
    service =
        new IssueFactPersistenceService(mock(IssueFactMapper.class), null, jdbcTemplate);
  }

  @Test
  void test_cleanup_keeps_every_snapshot_row_and_deletes_outside_rows_across_chunk_boundaries() {
    for (long issueId = 1; issueId <= SNAPSHOT_SIZE; issueId++) {
      jdbcTemplate.update(
          "insert into issue_fact(source_system, source_instance, project_id, issue_id)"
              + " values ('GITLAB', 'default', 9, ?)",
          issueId);
    }
    jdbcTemplate.update(
        "insert into issue_fact(source_system, source_instance, project_id, issue_id)"
            + " values ('GITLAB', 'default', 8, 777)");
    jdbcTemplate.update(
        "insert into issue_fact(source_system, source_instance, project_id, issue_id)"
            + " values ('GITLAB', 'default', null, 888)");
    jdbcTemplate.update(
        "insert into issue_fact_customer_members(source_system, source_instance, project_id, issue_id)"
            + " values ('GITLAB', 'default', 8, 777)");

    List<IssueFact> snapshot = new ArrayList<>(SNAPSHOT_SIZE);
    for (long issueId = 1; issueId <= SNAPSHOT_SIZE; issueId++) {
      IssueFact fact = new IssueFact();
      fact.setProjectId(9L);
      fact.setIssueId(issueId);
      snapshot.add(fact);
    }
    transactionTemplate.executeWithoutResult(
        status -> service.deleteFactsNotInSnapshot("GITLAB", "default", snapshot));

    Long keptRows =
        jdbcTemplate.queryForObject(
            "select count(*) from issue_fact where source_instance = 'default'", Long.class);
    assertThat(keptRows).isEqualTo((long) SNAPSHOT_SIZE);
    Long outsideGone =
        jdbcTemplate.queryForObject(
            "select count(*) from issue_fact where project_id = 8"
                + " or (project_id is null and issue_id = 888)",
            Long.class);
    assertThat(outsideGone).isZero();
    Long membersGone =
        jdbcTemplate.queryForObject(
            "select count(*) from issue_fact_customer_members where project_id = 8", Long.class);
    assertThat(membersGone).isZero();
  }
}
