package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.IssueSourceReadinessResponse;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class IssueSourceReadinessServiceTest {

  @Mock private JdbcTemplate jdbcTemplate;

  @Test
  @SuppressWarnings("unchecked")
  void shouldSummarizeOdsIssueSourceReadiness() throws Exception {
    when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0, String.class);
              if (sql.contains("from ods_gitlab_milestones")) {
                return 3L;
              }
              if (sql.contains("milestone_id is not null")) {
                return 7L;
              }
              if (sql.contains("join ods_gitlab_projects p") && sql.contains("from ods_gitlab_issues i")) {
                return 6L;
              }
              if (sql.contains("from ods_gitlab_projects p")) {
                return 1L;
              }
              if (sql.contains("'%系统测试%'")) {
                return 5L;
              }
              if (sql.contains("ll.target_type = 'Issue'")) {
                return 4L;
              }
              if (sql.contains("from ods_gitlab_issues")) {
                return 15L;
              }
              if (sql.contains("from ods_gitlab_projects")) {
                return 2L;
              }
              throw new AssertionError("Unexpected SQL: " + sql);
            });
    when(jdbcTemplate.query(contains("group by i.project_id"), any(RowMapper.class)))
        .thenAnswer(
            invocation -> {
              RowMapper<Object> mapper = invocation.getArgument(1);
              return List.of(
                  mapper.mapRow(row(325L, "CC_Product", 6L), 0),
                  mapper.mapRow(row(9L, "CrownCAD", 5L), 1));
            });

    IssueSourceReadinessResponse response =
        new IssueSourceReadinessService(jdbcTemplate).getReadiness();

    assertThat(response.projectCount()).isEqualTo(2);
    assertThat(response.issueCount()).isEqualTo(15);
    assertThat(response.milestoneCount()).isEqualTo(3);
    assertThat(response.issuesWithMilestoneCount()).isEqualTo(7);
    assertThat(response.customerProjectCount()).isEqualTo(1);
    assertThat(response.customerProjectIssueCount()).isEqualTo(6);
    assertThat(response.customerLabelIssueCount()).isEqualTo(4);
    assertThat(response.systemTestIssueCount()).isEqualTo(5);
    assertThat(response.topIssueProjects())
        .extracting(item -> item.label() + ":" + item.count())
        .contains("CC_Product:6", "CrownCAD:5");
    assertThat(response.warnings()).isEmpty();
  }

  private ResultSet row(long projectId, String projectName, long issueCount) throws Exception {
    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
    when(rs.getLong("project_id")).thenReturn(projectId);
    when(rs.getString("project_name")).thenReturn(projectName);
    when(rs.getLong("issue_count")).thenReturn(issueCount);
    return rs;
  }
}
