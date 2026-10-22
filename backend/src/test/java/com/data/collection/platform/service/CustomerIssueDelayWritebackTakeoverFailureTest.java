package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 接替修复的原实现失败反例（真实 PostgreSQL）。
 *
 * <p>这些用例锁定 10/20 复核发现的两个缺陷：旧 worker 的结算只按 id 更新，既能清除
 * RUNNING 期间到达的新差异，也能覆盖新 worker 已经接管的租约。修复后它们必须保持绿色。
 */
@SpringBootTest
class CustomerIssueDelayWritebackTakeoverFailureTest {
  private static final String SOURCE = "delay-takeover-it";

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private CustomerIssueDelayLabelWritebackQueueService queueService;

  @BeforeEach
  @AfterEach
  void cleanUp() {
    jdbcTemplate.update(
        "delete from customer_issue_delay_label_writeback_jobs where source_instance = ?", SOURCE);
  }

  @Test
  void newDiffArrivingWhileRunningMustSurviveStaleCompletion() {
    long jobId = insertPendingJob("响应已延期", false);

    CustomerIssueDelayLabelWritebackJob claimed = queueService.claimNext("worker-a", 120);
    assertThat(claimed).isNotNull();

    jdbcTemplate.update(
        """
        update customer_issue_delay_label_writeback_jobs
           set current_label_names = '响应已延期, 解决已延期',
               add_labels = '解决已延期',
               remove_labels = '',
               desired_resolve_delayed = true,
               task_revision = task_revision + 1,
               updated_at = current_timestamp
         where id = ?
        """,
        jobId);

    assertThat(queueService.markSucceeded(claimed)).isFalse();

    Map<String, Object> row = jobRow(jobId);
    assertThat(row.get("status"))
        .as("旧修订完成不得清除处理中到达的新差异")
        .isNotEqualTo("SUCCEEDED");
    assertThat(row.get("add_labels")).isEqualTo("解决已延期");
  }

  @Test
  void staleLeaseMustNotSettleAfterAnotherWorkerTookOver() {
    long jobId = insertPendingJob("", true);

    CustomerIssueDelayLabelWritebackJob first = queueService.claimNext("worker-a", 120);
    assertThat(first).isNotNull();
    jdbcTemplate.update(
        """
        update customer_issue_delay_label_writeback_jobs
           set lease_until = current_timestamp - interval '1 second'
         where id = ?
        """,
        jobId);

    CustomerIssueDelayLabelWritebackJob second = queueService.claimNext("worker-b", 120);
    assertThat(second).as("过期租约必须可被接管").isNotNull();

    assertThat(queueService.markSucceeded(first)).isFalse();

    Map<String, Object> row = jobRow(jobId);
    assertThat(row.get("status"))
        .as("旧租约返回后不得覆盖新 worker 的执行状态")
        .isEqualTo("RUNNING");
    assertThat(row.get("lease_owner")).isEqualTo("worker-b");
  }

  private long insertPendingJob(String addLabels, boolean resolveDelayed) {
    return jdbcTemplate.queryForObject(
        """
        insert into customer_issue_delay_label_writeback_jobs (
            source_instance, project_id, issue_iid, issue_id,
            desired_response_delayed, desired_resolve_delayed,
            current_label_names, add_labels, remove_labels, status,
            attempt_count, max_attempts, next_run_at, created_at, updated_at)
        values (?, 325, 9001, 99001, ?, ?, '', ?, '', 'PENDING', 0, 10,
                current_timestamp, current_timestamp, current_timestamp)
        returning id
        """,
        Long.class,
        SOURCE,
        !resolveDelayed,
        resolveDelayed,
        addLabels);
  }

  private Map<String, Object> jobRow(long jobId) {
    return jdbcTemplate.queryForMap(
        """
        select status, lease_owner, add_labels, remove_labels, desired_resolve_delayed
          from customer_issue_delay_label_writeback_jobs
         where id = ?
        """,
        jobId);
  }
}
