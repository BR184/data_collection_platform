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
    queueService = new CustomerIssueDelayLabelWritebackQueueService(jdbcTemplate, planner);
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
        .contains("join ods_gitlab_issues source_issue");
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
  void shouldUseCappedBackoffSchedule() {
    assertThat(queueService.retryDelayMinutes(1)).isEqualTo(1);
    assertThat(queueService.retryDelayMinutes(2)).isEqualTo(5);
    assertThat(queueService.retryDelayMinutes(3)).isEqualTo(15);
    assertThat(queueService.retryDelayMinutes(4)).isEqualTo(30);
    assertThat(queueService.retryDelayMinutes(5)).isEqualTo(60);
    assertThat(queueService.retryDelayMinutes(99)).isEqualTo(60);
  }
}
