package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.IssueFactDiagnosticsResponse;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class IssueFactDiagnosticsServiceTest {

  @Mock private IssueFactQueryService issueFactQueryService;

  @Test
  @SuppressWarnings("unchecked")
  void shouldSummarizeIssueFactCoverageByScope() throws Exception {
    when(issueFactQueryService.query(anyString(), anyMap(), any(RowMapper.class)))
        .thenAnswer(
            invocation -> {
              RowMapper<Object> mapper = invocation.getArgument(2);
              return List.of(
                  mapper.mapRow(
                      row(
                          9L,
                          "CrownCAD",
                          "",
                          "CC2026R1第一轮系统测试",
                          "",
                          "环境部署问题",
                          true,
                          false,
                          false,
                          "工程图, CC2026R1第一轮系统测试",
                          LocalDateTime.of(2026, 4, 10, 9, 0)),
                      0),
                  mapper.mapRow(
                      row(
                          325L,
                          "CC_Product",
                          "CC2026R1-M1",
                          "",
                          "",
                          "",
                          false,
                          true,
                          true,
                          "工程图, P2",
                          LocalDateTime.of(2026, 4, 12, 10, 0)),
                      1),
                  mapper.mapRow(
                      row(
                          325L,
                          "CC_Product",
                          "CC2026R1-M1",
                          "",
                          "",
                          "需求理解偏差",
                          true,
                          false,
                          false,
                          "草图, P1",
                          LocalDateTime.of(2026, 4, 13, 10, 0)),
                      2));
            });

    IssueFactDiagnosticsService service =
        new IssueFactDiagnosticsService(
            issueFactQueryService,
            new SystemTestScopeProfile(),
            new CustomerIssueScopeProfile(new SystemTestScopeProfile()));

    IssueFactDiagnosticsResponse response = service.getDiagnostics();

    assertThat(response.overall().totalCount()).isEqualTo(3);
    assertThat(response.systemTest().totalCount()).isEqualTo(1);
    assertThat(response.customerIssue().totalCount()).isEqualTo(2);
    assertThat(response.customerIssue().withMilestoneTitleCount()).isEqualTo(2);
    assertThat(response.customerIssue().withTemplateReplyCount()).isEqualTo(1);
    assertThat(response.customerIssue().responseDelayedCount()).isEqualTo(1);
    assertThat(response.customerIssueReasonCategories())
        .extracting(item -> item.label() + ":" + item.count())
        .contains("需求理解偏差:1", "未归因:1");
    assertThat(response.customerIssueProjects())
        .extracting(item -> item.label() + ":" + item.count())
        .contains("CC_Product:2");
  }

  private ResultSet row(
      long projectId,
      String projectName,
      String milestoneTitle,
      String testingPhase,
      String systemTestLabel,
      String reasonCategory,
      boolean hasResponse,
      boolean responseDelayed,
      boolean resolveDelayed,
      String labelNames,
      LocalDateTime createdAt)
      throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong("project_id")).thenReturn(projectId);
    when(rs.getString("project_name")).thenReturn(projectName);
    when(rs.getString("milestone_title")).thenReturn(milestoneTitle);
    when(rs.getString("testing_phase")).thenReturn(testingPhase);
    when(rs.getString("system_test_label")).thenReturn(systemTestLabel);
    when(rs.getString("reason_category")).thenReturn(reasonCategory);
    when(rs.getBoolean("has_response")).thenReturn(hasResponse);
    when(rs.getBoolean("is_response_delayed")).thenReturn(responseDelayed);
    when(rs.getBoolean("is_resolve_delayed")).thenReturn(resolveDelayed);
    when(rs.getString("label_names")).thenReturn(labelNames);
    when(rs.getTimestamp("created_at_source")).thenReturn(Timestamp.valueOf(createdAt));
    return rs;
  }
}
