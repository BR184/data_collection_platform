package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class CustomerIssueDelayLabelWritebackQueueServiceTest {
  private JdbcTemplate jdbcTemplate;
  private CustomerIssueDelayLabelWritebackQueueService queueService;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(JdbcTemplate.class);
    CustomerIssueDelayLabelWritebackPlanner planner =
        new CustomerIssueDelayLabelWritebackPlanner(new CustomerIssueDelayLabelWritebackService());
    queueService =
        new CustomerIssueDelayLabelWritebackQueueService(
            jdbcTemplate, planner, mock(FormIssueInputResolver.class));
  }

  @Test
  void shouldExcludeGitlabApiErrorAndUnconfirmedLabelsFromCandidateSql() {
    String sql = queueService.candidateSql();

    assertThat(sql)
        .contains("f.project_id = ?")
        .contains("lower(coalesce(f.issue_state, 'opened')) <> 'closed'")
        .contains("f.created_at_source >= ?")
        .contains("f.issue_id is not null")
        .contains("coalesce(f.illegal_reason, '') not like '%GitLab接口报错%'")
        .contains("coalesce(f.illegal_reasons, '') not like '%GitLab接口报错%'")
        .as("必须从当前 ODS 标签读取，不能用事实里的旧标签补齐")
        .contains("coalesce(current_labels.label_names, '')")
        .doesNotContain("f.label_names, ''")
        .as("ODS 议题缺失时不能把未知标签当作空集合")
        .contains("join ods_gitlab_issues source_issue")
        .as("有效行谓词必须与动态索引谓词一致，不能包 coalesce")
        .contains("source_issue.mirror_deleted = false")
        .contains("label_link.mirror_deleted = false")
        .contains("label.mirror_deleted = false")
        .doesNotContain("coalesce(source_issue.mirror_deleted")
        .doesNotContain("coalesce(label_link.mirror_deleted")
        .doesNotContain("coalesce(label.mirror_deleted");
  }

  @Test
  void shouldClaimPendingRetryOrExpiredRunningJobsWithNewTokenAndRevision() {
    when(jdbcTemplate.query(
            org.mockito.ArgumentMatchers.contains("for update skip locked"),
            any(RowMapper.class),
            eq("RUNNING"),
            eq("worker-1"),
            anyString(),
            eq(1),
            eq("PENDING"),
            eq("RETRY_WAIT"),
            eq("RUNNING")))
        .thenReturn(List.of());

    CustomerIssueDelayLabelWritebackJob claimed = queueService.claimNext("worker-1", 0);

    assertThat(claimed).isNull();
    verify(jdbcTemplate)
        .query(
            org.mockito.ArgumentMatchers.contains("claim_revision = claim_revision + 1"),
            any(RowMapper.class),
            eq("RUNNING"),
            eq("worker-1"),
            anyString(),
            eq(1),
            eq("PENDING"),
            eq("RETRY_WAIT"),
            eq("RUNNING"));
  }

  @Test
  void shouldAcknowledgeAdvancedRevisionOnlyWhenItsChangeEqualsWhatWasSent() {
    CustomerIssueDelayLabelWritebackJob job = runningJob();
    when(jdbcTemplate.update(
            anyString(),
            eq("SUCCEEDED"),
            eq(12L),
            eq("worker"),
            eq("token"),
            eq(1L),
            eq("响应已延期"),
            eq("")))
        .thenReturn(1);

    boolean settled =
        queueService.markSucceededIfSameChange(job, List.of("响应已延期"), List.of());

    assertThat(settled).isTrue();
    org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate)
        .update(
            sql.capture(),
            eq("SUCCEEDED"),
            eq(12L),
            eq("worker"),
            eq("token"),
            eq(1L),
            eq("响应已延期"),
            eq(""));
    assertThat(sql.getValue())
        .as("记账必须同时比对新修订的目标差异")
        .contains("add_labels = ? and remove_labels = ?")
        .as("领取修订仍须匹配，但任务修订已推进")
        .contains("claim_revision = ?")
        .doesNotContain("task_revision = ?");
  }

  @Test
  void shouldReleaseAdvancedRevisionWithoutAcknowledgingSuccess() {
    CustomerIssueDelayLabelWritebackJob job = runningJob();
    when(jdbcTemplate.update(anyString(), eq("PENDING"), eq(12L), eq("worker"), eq("token"), eq(1L)))
        .thenReturn(1);

    boolean released = queueService.releaseForNewRevision(job);

    assertThat(released).isTrue();
    org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate)
        .update(sql.capture(), eq("PENDING"), eq(12L), eq("worker"), eq("token"), eq(1L));
    assertThat(sql.getValue())
        .as("不同目标差异只能交回队列，不能记为成功")
        .contains("status = ?")
        .doesNotContain("finished_at")
        .doesNotContain("task_revision = ?");
  }

  private CustomerIssueDelayLabelWritebackJob runningJob() {
    return new CustomerIssueDelayLabelWritebackJob(
        12L,
        "default",
        1L,
        325L,
        1001L,
        9001L,
        true,
        false,
        "",
        List.of("响应已延期"),
        List.of(),
        "RUNNING",
        0,
        10,
        null,
        "worker",
        null,
        null,
        null,
        77L,
        7L,
        42L,
        null,
        "token",
        1L,
        2L);
  }

  @Test
  void shouldUseCappedBackoffSchedule() {
    assertThat(queueService.retryDelayMinutes(1)).isEqualTo(1);
    assertThat(queueService.retryDelayMinutes(2)).isEqualTo(5);
    assertThat(queueService.retryDelayMinutes(3)).isEqualTo(15);
    assertThat(queueService.retryDelayMinutes(4)).isEqualTo(30);
    assertThat(queueService.retryDelayMinutes(5)).isEqualTo(60);
    assertThat(queueService.retryDelayMinutes(99)).isEqualTo(60);
  }
}
