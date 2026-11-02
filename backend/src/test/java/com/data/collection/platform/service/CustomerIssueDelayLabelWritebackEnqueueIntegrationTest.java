package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.entity.GitlabSyncConfig;
import java.time.LocalDateTime;
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
  private static final long FOURTH_IID = 81004L;
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
  void oppositeDiffArrivingDuringSendMustBeRetainedAndReleased() {
    insertIssue(FIRST_IID, 981001L, "", true, false);
    queueService.enqueuePage(config, AUTH_REVISION, FACT_VERSION, MIRROR_RUN_ID, null, 10);
    CustomerIssueDelayLabelWritebackJob claimed = queueService.claimNext("worker-a", 120);
    assertThat(claimed.addLabels()).containsExactly("响应已延期");

    jdbcTemplate.update(
        """
        update issue_fact
           set is_response_delayed = false, response_overdue = false
         where source_instance = ? and project_id = ? and issue_iid = ?
        """,
        SOURCE, PROJECT_ID, FIRST_IID);
    insertLabel(1L, "响应已延期");
    linkLabel(1L, 981001L);
    queueService.enqueuePage(
        config, AUTH_REVISION + 1, FACT_VERSION + 1, MIRROR_RUN_ID + 1, null, 10);

    Map<String, Object> duringSend = job(981001L);
    assertThat(duringSend.get("status")).isEqualTo("RUNNING");
    assertThat(duringSend.get("remove_labels")).isEqualTo("响应已延期");
    assertThat(((Number) duringSend.get("task_revision")).longValue()).isEqualTo(2L);

    assertThat(queueService.markSucceeded(claimed)).isFalse();
    assertThat(
            queueService.markSucceededIfSameChange(
                claimed, List.of("响应已延期"), List.of()))
        .as("新修订要求相反操作，不能按这次成功记账")
        .isFalse();
    assertThat(queueService.releaseForNewRevision(claimed)).isTrue();

    Map<String, Object> released = job(981001L);
    assertThat(released.get("status")).isEqualTo("PENDING");
    assertThat(released.get("add_labels")).isEqualTo("");
    assertThat(released.get("remove_labels")).isEqualTo("响应已延期");
    assertThat(((Number) released.get("task_revision")).longValue()).isEqualTo(2L);

    CustomerIssueDelayLabelWritebackJob next = queueService.claimNext("worker-b", 120);
    assertThat(next).isNotNull();
    assertThat(next.issueIid()).isEqualTo(FIRST_IID);
    assertThat(next.taskRevision()).isEqualTo(2L);
    assertThat(next.removeLabels()).containsExactly("响应已延期");
  }

  @Test
  void sameDiffArrivingDuringSendIsAcknowledgedForTheNewRevision() {
    insertIssue(FIRST_IID, 981001L, "", true, false);
    queueService.enqueuePage(config, AUTH_REVISION, FACT_VERSION, MIRROR_RUN_ID, null, 10);
    CustomerIssueDelayLabelWritebackJob claimed = queueService.claimNext("worker-a", 120);

    queueService.enqueuePage(
        config, AUTH_REVISION + 1, FACT_VERSION + 1, MIRROR_RUN_ID + 1, null, 10);
    Map<String, Object> duringSend = job(981001L);
    assertThat(duringSend.get("status")).isEqualTo("RUNNING");
    assertThat(((Number) duringSend.get("task_revision")).longValue()).isEqualTo(2L);

    assertThat(queueService.markSucceeded(claimed)).isFalse();
    assertThat(
            queueService.markSucceededIfSameChange(
                claimed, List.of("响应已延期"), List.of()))
        .as("新修订要求同一次写入，可记为已完成")
        .isTrue();
    assertThat(job(981001L).get("status")).isEqualTo("SUCCEEDED");
  }

  @Test
  void sameWriteFromStaleLabelRefreshMustNotReopenSucceededJob() {
    insertIssue(FIRST_IID, 981001L, "stale-label", true, false);
    insertLabelRefreshRun(MIRROR_RUN_ID, LocalDateTime.now().minusMinutes(5));
    queueService.enqueuePage(config, AUTH_REVISION, FACT_VERSION, MIRROR_RUN_ID, null, 10);
    CustomerIssueDelayLabelWritebackJob claimed = queueService.claimNext("worker-a", 120);
    assertThat(queueService.markSucceeded(claimed)).isTrue();

    CustomerIssueDelayLabelWritebackQueueService.PageResult stale =
        queueService.enqueuePage(
            config, AUTH_REVISION + 1, FACT_VERSION + 1, MIRROR_RUN_ID, null, 10);

    assertThat(stale.enqueued())
        .as("标签来源刷新早于成功写入且目标未变时不能重复登记同一次标签变更")
        .isZero();
    assertThat(status(FIRST_IID)).as("已成功作业保持已完成").isEqualTo("SUCCEEDED");

    insertLabelRefreshRun(MIRROR_RUN_ID + 1, LocalDateTime.now().plusSeconds(2));
    CustomerIssueDelayLabelWritebackQueueService.PageResult fresh =
        queueService.enqueuePage(
            config, AUTH_REVISION + 2, FACT_VERSION + 2, MIRROR_RUN_ID + 1, null, 10);

    assertThat(fresh.enqueued())
        .as("标签来源在成功写入后重新读取且标签仍缺失时必须重新入队")
        .isEqualTo(1);
    assertThat(status(FIRST_IID)).isEqualTo("PENDING");
  }

  @Test
  void laterLabelLinksRefreshMustReopenEvenIfLabelsFinishedBeforeSucceededWrite() {
    insertIssue(FIRST_IID, 981001L, "stale-label", true, false);
    insertLabelRefreshRun(MIRROR_RUN_ID, LocalDateTime.now().minusMinutes(5));
    queueService.enqueuePage(config, AUTH_REVISION, FACT_VERSION, MIRROR_RUN_ID, null, 10);
    CustomerIssueDelayLabelWritebackJob claimed = queueService.claimNext("worker-a", 120);
    assertThat(queueService.markSucceeded(claimed)).isTrue();

    insertLabelRefreshRun(
        MIRROR_RUN_ID + 1, LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusMinutes(5));
    CustomerIssueDelayLabelWritebackQueueService.PageResult page =
        queueService.enqueuePage(
            config, AUTH_REVISION + 1, FACT_VERSION + 1, MIRROR_RUN_ID + 1, null, 10);

    assertThat(page.enqueued())
        .as("以两张表的最晚完成时刻判断：后完成的一张晚于成功写入时必须重新入队")
        .isEqualTo(1);
    assertThat(status(FIRST_IID)).isEqualTo("PENDING");
  }

  @Test
  void missingFinishedAtMustNotCountAsRefreshEvidence() {
    insertIssue(FIRST_IID, 981001L, "stale-label", true, false);
    insertLabelRefreshRun(MIRROR_RUN_ID, LocalDateTime.now().minusMinutes(5));
    queueService.enqueuePage(config, AUTH_REVISION, FACT_VERSION, MIRROR_RUN_ID, null, 10);
    CustomerIssueDelayLabelWritebackJob claimed = queueService.claimNext("worker-a", 120);
    assertThat(queueService.markSucceeded(claimed)).isTrue();

    insertLabelRefreshRun(MIRROR_RUN_ID + 1, LocalDateTime.now().minusMinutes(5));
    jdbcTemplate.update(
        "update sync_run_table_tasks set finished_at = null where run_id = ? and source_table = 'label_links'",
        MIRROR_RUN_ID + 1);
    CustomerIssueDelayLabelWritebackQueueService.PageResult page =
        queueService.enqueuePage(
            config, AUTH_REVISION + 1, FACT_VERSION + 1, MIRROR_RUN_ID + 1, null, 10);

    assertThat(page.enqueued())
        .as("任一张表完成时间缺失时刷新证据不完整，必须重新入队")
        .isEqualTo(1);
    assertThat(status(FIRST_IID)).isEqualTo("PENDING");
  }

  @Test
  void succeededJobMustReopenWhenResponseDelayTargetDisappears() {
    insertIssue(FIRST_IID, 981001L, "stale-label", true, false);
    insertLabelRefreshRun(MIRROR_RUN_ID, LocalDateTime.now().minusMinutes(5));
    queueService.enqueuePage(config, AUTH_REVISION, FACT_VERSION, MIRROR_RUN_ID, null, 10);
    CustomerIssueDelayLabelWritebackJob claimed = queueService.claimNext("worker-a", 120);
    assertThat(queueService.markSucceeded(claimed)).isTrue();

    jdbcTemplate.update(
        """
        update issue_fact
           set is_response_delayed = false, response_overdue = false
         where source_instance = ? and project_id = ? and issue_iid = ?
        """,
        SOURCE, PROJECT_ID, FIRST_IID);
    insertLabel(1L, "响应已延期");
    linkLabel(1L, 981001L);

    CustomerIssueDelayLabelWritebackQueueService.PageResult page =
        queueService.enqueuePage(
            config, AUTH_REVISION + 1, FACT_VERSION + 1, MIRROR_RUN_ID, null, 10);

    assertThat(page.enqueued()).as("成功后出现相反目标必须重新入队").isEqualTo(1);
    Map<String, Object> reopened = job(981001L);
    assertThat(reopened.get("status")).isEqualTo("PENDING");
    assertThat(reopened.get("remove_labels")).isEqualTo("响应已延期");
  }

  @Test
  void succeededJobMustReopenWhenResolveDelayTargetAppears() {
    insertIssue(FIRST_IID, 981001L, "stale-label", true, false);
    insertLabelRefreshRun(MIRROR_RUN_ID, LocalDateTime.now().minusMinutes(5));
    queueService.enqueuePage(config, AUTH_REVISION, FACT_VERSION, MIRROR_RUN_ID, null, 10);
    CustomerIssueDelayLabelWritebackJob claimed = queueService.claimNext("worker-a", 120);
    assertThat(queueService.markSucceeded(claimed)).isTrue();

    jdbcTemplate.update(
        """
        update issue_fact
           set is_resolve_delayed = true
         where source_instance = ? and project_id = ? and issue_iid = ?
        """,
        SOURCE, PROJECT_ID, FIRST_IID);

    CustomerIssueDelayLabelWritebackQueueService.PageResult page =
        queueService.enqueuePage(
            config, AUTH_REVISION + 1, FACT_VERSION + 1, MIRROR_RUN_ID, null, 10);

    assertThat(page.enqueued()).as("成功后新增的解决延期目标必须重新入队").isEqualTo(1);
    Map<String, Object> reopened = job(981001L);
    assertThat(reopened.get("status")).isEqualTo("PENDING");
    assertThat((String) reopened.get("add_labels")).contains("解决已延期");
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

  @Test
  void settlementEndsOnlyObsoletePendingWorkOfTheSameSource() {
    insertJob(FIRST_IID, "PENDING", 7L);
    insertJob(SECOND_IID, "RETRY_WAIT", 8L);
    insertJob(THIRD_IID, "RUNNING", 7L);
    insertJob(FOURTH_IID, "DEAD", 7L);

    int settled = queueService.settleObsoletePendingJobs(SOURCE, 8L);

    assertThat(settled).isEqualTo(1);
    assertThat(status(FIRST_IID)).isEqualTo("SKIPPED");
    assertThat(status(SECOND_IID)).as("本轮重新选中的任务必须保留").isEqualTo("RETRY_WAIT");
    assertThat(status(THIRD_IID)).as("有效领取中的任务由持有者按围栏结算").isEqualTo("RUNNING");
    assertThat(status(FOURTH_IID)).as("DEAD 保持既有契约，不自动复活或清理").isEqualTo("DEAD");
  }

  private void insertJob(long issueIid, String status, long authorizationRevision) {
    jdbcTemplate.update(
        """
        insert into customer_issue_delay_label_writeback_jobs(
            source_instance, project_id, issue_iid, status, authorization_revision,
            required_fact_version, next_run_at, created_at, updated_at)
        values (?, ?, ?, ?, ?, ?, current_timestamp, current_timestamp, current_timestamp)
        """,
        SOURCE,
        PROJECT_ID,
        issueIid,
        status,
        authorizationRevision,
        FACT_VERSION);
  }

  private String status(long issueIid) {
    return jdbcTemplate.queryForObject(
        """
        select status from customer_issue_delay_label_writeback_jobs
         where source_instance = ? and project_id = ? and issue_iid = ?
        """,
        String.class,
        SOURCE,
        PROJECT_ID,
        issueIid);
  }

  private void insertLabelRefreshRun(long runId, LocalDateTime finishedAt) {
    insertLabelRefreshRun(runId, finishedAt, finishedAt);
  }

  private void insertLabelRefreshRun(
      long runId, LocalDateTime labelsFinishedAt, LocalDateTime labelLinksFinishedAt) {
    LocalDateTime runFinishedAt =
        labelsFinishedAt == null
            ? labelLinksFinishedAt
            : labelLinksFinishedAt == null
                ? labelsFinishedAt
                : (labelsFinishedAt.isAfter(labelLinksFinishedAt)
                    ? labelsFinishedAt
                    : labelLinksFinishedAt);
    jdbcTemplate.update(
        """
        insert into sync_runs(
            id, run_id, config_id, source_instance, run_type, trigger_type, status,
            exclusive_scope, started_at, finished_at, created_at, updated_at)
        values (?, ?, ?, ?, 'TABLE_REFRESH', 'MANUAL', 'SUCCESS', ?, ?, ?,
                current_timestamp, current_timestamp)
        """,
        runId,
        "test-run-" + runId,
        configId,
        SOURCE,
        "source:" + configId + ":" + SOURCE + ":mirror",
        runFinishedAt,
        runFinishedAt);
    insertLabelRefreshTask(runId, "labels", labelsFinishedAt);
    insertLabelRefreshTask(runId, "label_links", labelLinksFinishedAt);
  }

  private void insertLabelRefreshTask(
      long runId, String table, LocalDateTime finishedAt) {
    jdbcTemplate.update(
        """
        insert into sync_run_table_tasks(
            run_id, config_id, source_instance, source_table, mirror_table,
            task_type, status, row_strategy, finished_at)
        values (?, ?, ?, ?, ?, 'TABLE', 'SUCCESS', 'UPDATED_AT', ?)
        """,
        runId,
        configId,
        SOURCE,
        table,
        "ods_gitlab_" + table,
        finishedAt);
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
        "delete from customer_issue_delay_label_writeback_jobs where project_id = ? and issue_iid in (?, ?, ?, ?)",
        PROJECT_ID,
        FIRST_IID,
        SECOND_IID,
        THIRD_IID,
        FOURTH_IID);
    jdbcTemplate.update(
        "delete from issue_fact where project_id = ? and issue_iid in (?, ?, ?, ?)",
        PROJECT_ID,
        FIRST_IID,
        SECOND_IID,
        THIRD_IID,
        FOURTH_IID);
    jdbcTemplate.update(
        "delete from ods_gitlab_label_links where target_id in (981001, 981002, 981003)");
    jdbcTemplate.update(
        "delete from ods_gitlab_labels where id in (1, 2)");
    jdbcTemplate.update(
        "delete from ods_gitlab_issues where id in (981001, 981002, 981003)");
    jdbcTemplate.update(
        "delete from customer_issue_delay_closure_requests where source_instance = ?", SOURCE);
    jdbcTemplate.update("delete from sync_runs where run_id like 'test-run-%'");
    jdbcTemplate.update(
        "delete from gitlab_sync_configs where source_instance = ?", SOURCE);
  }
}
