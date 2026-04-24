package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.data.collection.platform.config.GitlabMirrorProperties;
import com.data.collection.platform.entity.SystemTestIssueSearchListResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SystemTestIssueSearchServiceTest {

  @Mock private IssueFactRecordRepository issueFactRecordRepository;
  @Mock private SystemTestScopeProfile systemTestScopeProfile;

  @Test
  void shouldApplySystemTestSpecificFiltersThroughRequestObject() {
    GitlabMirrorProperties gitlabMirrorProperties = new GitlabMirrorProperties();
    gitlabMirrorProperties.setWebBaseUrl("http://gitlab.example.com");
    SystemTestIssueSearchService service =
        new SystemTestIssueSearchService(
            issueFactRecordRepository, systemTestScopeProfile, gitlabMirrorProperties);
    when(issueFactRecordRepository.findByProjectId(1001L))
        .thenReturn(
            List.of(
                record(301, "草图崩溃", "草图", "CC2026R1第一轮系统测试", "alice", "bob"),
                record(302, "草图其他轮次", "草图", "CC2026R2第一轮系统测试", "alice", "bob"),
                record(303, "作者不匹配", "草图", "CC2026R1第一轮系统测试", "charlie", "bob")));
    when(systemTestScopeProfile.matches(any())).thenReturn(true);

    SystemTestIssueSearchListResponse response =
        service.listRecords(
            new SystemTestIssueSearchQueryRequest(
                new IssueFactRecordListRequest(
                    1001L,
                    "草图",
                    null,
                    null,
                    "Rocksdb",
                    "草图",
                    "LEVEL2",
                    null,
                    null,
                    "处理中",
                    "功能缺陷",
                    "CC2026R1",
                    null,
                    null,
                    null,
                    null,
                    1,
                    20,
                    "updatedAt",
                    "desc"),
                "CC2026R1第一轮",
                "alice",
                "bob"));

    assertThat(response.records()).hasSize(1);
    assertThat(response.records().getFirst().issueIid()).isEqualTo(301);
    assertThat(response.records().getFirst().issueLink())
        .isEqualTo("http://gitlab.example.com/-/issues/301");
  }

  private IssueFactRecord record(
      int issueIid,
      String title,
      String moduleName,
      String testingPhase,
      String authorName,
      String assigneeName) {
    LocalDateTime now = LocalDateTime.of(2026, 4, 24, 10, 0);
    return new IssueFactRecord(
        1001L,
        "Rocksdb",
        9200L + issueIid,
        issueIid,
        title,
        "opened",
        testingPhase,
        testingPhase,
        "LEVEL2",
        "",
        "处理中",
        "功能缺陷",
        "",
        false,
        "",
        false,
        false,
        false,
        false,
        false,
        "CC2026R1",
        authorName,
        assigneeName,
        List.of(moduleName),
        List.of(testingPhase, "系统测试"),
        false,
        "",
        "",
        false,
        false,
        false,
        "",
        now.minusDays(3),
        now.minusDays(1),
        null);
  }
}
