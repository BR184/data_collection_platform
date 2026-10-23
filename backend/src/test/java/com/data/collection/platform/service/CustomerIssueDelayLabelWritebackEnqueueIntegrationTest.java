package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.entity.GitlabSyncConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 写回候选分页、真实标签读取与处理中修订（真实 PostgreSQL）。
 *
 * <p>标签必须来自当前 ODS；ODS 议题缺失时视为未知而不是空集合。RUNNING 期间到达的新差异要推进
 * 任务修订并保留新内容，旧 worker 的结算必须失败。
 */
@SpringBootTest
class CustomerIssueDelayLabelWritebackEnqueueIntegrationTest {
  private static final long PROJECT_ID = 325L;
  private static final long FIRST_IID = 81001L;
  private static final long SECOND_IID = 81002L;
  private static final long THIRD_IID = 81003L;
  private static final String SOURCE = "default";
  private static final long AUTH_REVISION = 7L;
  private static final long FACT_VERSION = 42L;
  private static final long MIRROR_RUN_ID = 77L;

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private CustomerIssueDelayLabelWritebackQueueService queueService;

  private long configId;
  private GitlabSyncConfig config;

  @BeforeEach
  void setUp() {
    GitlabIssueMirrorFixture.ensureSchema(jdbcTemplate);
    cleanUp();
    configId =
        jdbcTemplate.queryForObject(
            """
            insert into gitlab_sync_configs(
                name, source_instance, source_enabled, auto_sync_enabled, db_name,
                db_username, db_password, delay_label_writeback_enabled,
                web_base_url, api_token)
            values ('delay-enqueue-it', ?, true, false, 'mirror', 'reader', 'secret',
                    true, 'http://127.0.0.1', 'token')
            returning id
            """,
            Long.class,
            SOURCE);
    config = new GitlabSyncConfig();
    config.setId(configId);
    config.setSourceInstance(SOURCE);
    config.setSourceEnabled(true);
    config.setDelayLabelWritebackEnabled(true);
    config.setWebBaseUrl("http://127.0.0.1");
    config.setApiToken("token");
  }

  @AfterEach
  void tearDown() {
    cleanUp();
  }

  @Test
  void enqueuePageReadsCurrentOdsLabelsAndAdvancesCursor() {
    insertIssue(FIRST_IID, 981001L, "stale-label", true, false);
    insertIssue(SECOND_IID, 981002L, "stale-label", true, false);
    insertIssue(THIRD_IID, 981003L, "stale-label", false, false);

    CustomerIssueDelayLabelWritebackQueueService.PageResult first =
        queueService.enqueuePage(config, AUTH_REVISION, FACT_VERSION, MIRROR_RUN_ID, null, 2);

    assertThat(first.enqueued()).isEqualTo(2);
    assertThat(first.lastIssueId()).isEqualTo(981002L);
    assertThat(first.hasMore()).isTrue();
    Map<String, Object> job = job(981001L);
    assertThat(job.get("current_label_names"))
        .as("标签必须来自当前 ODS，而不是事实中的旧标签")
        .isEqualTo("");
    assertThat(job.get("add_labels")).isEqualTo("响应已延期");
    assertThat(job.get("config_id")).isEqualTo(configId);
    assertThat(job.get("authorization_revision")).isEqualTo(AUTH_REVISION);
    assertThat(job.get("required_fact_version")).isEqualTo(FACT_VERSION);
    assertThat(job.get("round_mirror_run_id")).isEqualTo(MIRROR_RUN_ID);
    assertThat(((Number) job.get("task_revision")).longValue()).isEqualTo(1L);

    CustomerIssueDelayLabelWritebackQueueService.PageResult second =
        queueService.enqueuePage(
            config, AUTH_REVISION, FACT_VERSION, MIRROR_RUN_ID, first.lastIssueId(), 2);

    assertThat(second.lastIssueId()).isEqualTo(981003L);
    assertThat(second.hasMore()).isFalse();
  }

  @Test
  void actualOdsLabelQueryFailureMustNotReturnEmptyLabels() {
    jdbcTemplate.execute("alter table ods_gitlab_labels rename to delay_labels_unavailable");
    try {
      assertThatThrownBy(() -> queueService.loadCandidate(SOURCE, PROJECT_ID, FIRST_IID))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally {
      jdbcTemplate.execute("alter table delay_labels_unavailable rename to ods_gitlab_labels");
    }
  }

  @Test
  void simultaneousJobClaimsMustHaveOnlyOneOwner() throws Exception {
    insertIssue(FIRST_IID, 981001L, "stale-label", true, false);
    queueService.enqueuePage(config, AUTH_REVISION, FACT_VERSION, MIRROR_RUN_ID, null, 10);
    var start = new java.util.concurrent.CyclicBarrier(2);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> { start.await(); return queueService.claimNext("worker-a", 120); });
      var second = executor.submit(() -> { start.await(); return queueService.claimNext("worker-b", 120); });
      var a = first.get(10, java.util.concurrent.TimeUnit.SECONDS);
      var b = second.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertThat((a == null ? 0 : 1) + (b == null ? 0 : 1)).isEqualTo(1);
      var owner = a != null ? a : b;
      assertThat(queueService.markSucceeded(owner)).isTrue();
      assertThat(job(981001L).get("status")).isEqualTo("SUCCEEDED");
    }
  }

  @Test
  void newDiffArrivingWhileRunningIncrementsRevisionAndOldSettlementFails() {
    insertIssue(FIRST_IID, 981001L, "stale-label", true, false);
    CustomerIssueDelayLabelWritebackQueueService.PageResult page =
        queueService.enqueuePage(config, AUTH_REVISION, FACT_VERSION, MIRROR_RUN_ID, null, 10);
    assertThat(page.enqueued()).isEqualTo(1);

    CustomerIssueDelayLabelWritebackJob claimed = queueService.claimNext("worker-a", 120);
    assertThat(claimed).isNotNull();
    assertThat(claimed.taskRevision()).isEqualTo(1L);

    // 处理期间来源出现两个延期标签；事实仍判定解决未延期，本轮应新增"摘除解决已延期"。
    insertLabel(1L, "响应已延期");
    linkLabel(1L, 981001L);
    insertLabel(2L, "解决已延期");
    linkLabel(2L, 981001L);

    CustomerIssueDelayLabelWritebackQueueService.PageResult newPage =
        queueService.enqueuePage(
            config, AUTH_REVISION + 1, FACT_VERSION + 1, MIRROR_RUN_ID, null, 10);

    assertThat(newPage.enqueued()).isEqualTo(1);
    Map<String, Object> updated = job(981001L);
    assertThat(updated.get("remove_labels")).isEqualTo("解决已延期");
    assertThat(updated.get("status")).isEqualTo("RUNNING");
    assertThat(((Number) updated.get("task_revision")).longValue()).isEqualTo(2L);
    assertThat(updated.get("authorization_revision")).isEqualTo(AUTH_REVISION + 1);
    assertThat(updated.get("required_fact_version")).isEqualTo(FACT_VERSION + 1);

    assertThat(queueService.markSucceeded(claimed))
        .as("旧任务修订不能结算处理中到达的新差异")
        .isFalse();
    assertThat(job(981001L).get("status")).isEqualTo("RUNNING");
  }

  @Test
  void missingOdsIssueIsUnknownRatherThanEmptyLabels() {
    insertIssue(FIRST_IID, 981001L, "stale-label", true, false);
    jdbcTemplate.update(
        "update ods_gitlab_issues set mirror_deleted = true where id = ?", 981001L);

    CustomerIssueDelayLabelWritebackQueueService.PageResult page =
        queueService.enqueuePage(config, AUTH_REVISION, FACT_VERSION, MIRROR_RUN_ID, null, 10);

    assertThat(page.enqueued()).isZero();
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from customer_issue_delay_label_writeback_jobs where issue_id = ?",
            Long.class,
            981001L))
        .isZero();
  }

  private void insertIssue(
      long iid, long issueId, String staleLabels, boolean responseDelayed, boolean resolveDelayed) {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_issues(id, iid, project_id, title, mirror_deleted)
        values (?, ?, ?, 'issue', false)
        """,
        issueId,
        iid,
        PROJECT_ID);
    jdbcTemplate.update(
        """
        insert into issue_fact(
            source_system, source_instance, project_id, issue_id, issue_iid, title,
            issue_state, created_at_source, label_names, is_response_delayed,
            is_resolve_delayed, response_overdue, deleted)
        values ('GITLAB', ?, ?, ?, ?, 'issue', 'opened', '2026-05-01', ?, ?, ?, ?,
                false)
        """,
        SOURCE,
        PROJECT_ID,
        issueId,
        iid,
        staleLabels,
        responseDelayed,
        resolveDelayed,
        responseDelayed);
  }

  private void insertLabel(long id, String title) {
    jdbcTemplate.update(
        "insert into ods_gitlab_labels(id, title, mirror_deleted) values (?, ?, false)",
        id,
        title);
  }

  private void linkLabel(long labelId, long targetId) {
    jdbcTemplate.update(
        """
        insert into ods_gitlab_label_links(
            id, label_id, target_id, target_type, mirror_deleted)
        values (?, ?, ?, 'Issue', false)
        """,
        1000 + labelId,
        labelId,
        targetId);
  }

  private Map<String, Object> job(long issueId) {
    return jdbcTemplate.queryForMap(
        """
        select config_id, current_label_names, add_labels, remove_labels, status,
               authorization_revision, required_fact_version, round_mirror_run_id,
               task_revision
          from customer_issue_delay_label_writeback_jobs
         where project_id = ? and issue_id = ?
        """,
        PROJECT_ID,
        issueId);
  }

  private void cleanUp() {
    jdbcTemplate.update(
        "delete from customer_issue_delay_label_writeback_jobs where project_id = ? and issue_iid in (?, ?, ?)",
        PROJECT_ID,
        FIRST_IID,
        SECOND_IID,
        THIRD_IID);
    jdbcTemplate.update(
        "delete from issue_fact where project_id = ? and issue_iid in (?, ?, ?)",
        PROJECT_ID,
        FIRST_IID,
        SECOND_IID,
        THIRD_IID);
    jdbcTemplate.update(
        "delete from ods_gitlab_label_links where target_id in (981001, 981002, 981003)");
    jdbcTemplate.update(
        "delete from ods_gitlab_labels where id in (1, 2)");
    jdbcTemplate.update(
        "delete from ods_gitlab_issues where id in (981001, 981002, 981003)");
    jdbcTemplate.update(
        "delete from customer_issue_delay_closure_requests where source_instance = ?", SOURCE);
    jdbcTemplate.update(
        "delete from gitlab_sync_configs where source_instance = ?", SOURCE);
  }
}
