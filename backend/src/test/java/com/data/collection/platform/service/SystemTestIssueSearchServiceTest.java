package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
  @Mock private GitlabResourceLinkService issueLinkService;

  @Test
  void shouldUseSqlPageForPlainSearchRequests() {
    SystemTestIssueSearchService service =
        new SystemTestIssueSearchService(
            issueFactRecordRepository, systemTestScopeProfile, issueLinkService);
    when(issueFactRecordRepository.findPage(any()))
        .thenReturn(
            new PageSlice<>(
                List.of(record(300, "draft", "draft", "phase1 system test", "alice", "bob")),
                1,
                1,
                20));

    SystemTestIssueSearchListResponse response =
        service.listRecords(
            new SystemTestIssueSearchQueryRequest(
                new IssueFactRecordListRequest(
                    1001L,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    20,
                    "updatedAt",
                    "desc"),
                null,
                null,
                null));

    assertThat(response.total()).isEqualTo(1);
    verify(issueFactRecordRepository)
        .findPage(
            argThat(
                query ->
                    query.scope() == IssueFactRecordPageQuery.Scope.SYSTEM_TEST
                        && !query.illegalOnly()));
  }

  @Test
  void shouldApplySystemTestSpecificFiltersThroughRequestObject() {
    SystemTestIssueSearchService service =
        new SystemTestIssueSearchService(
            issueFactRecordRepository, systemTestScopeProfile, issueLinkService);
    when(issueLinkService.issueUrl(1001L, 301)).thenReturn("http://gitlab.example.com/group/project/-/issues/301");
    when(issueFactRecordRepository.findPage(any()))
        .thenReturn(
            new PageSlice<>(
                List.of(record(301, "draft crash", "draft", "phase1 system test", "alice", "bob")),
                1,
                1,
                20));

    SystemTestIssueSearchListResponse response =
        service.listRecords(
            new SystemTestIssueSearchQueryRequest(
                new IssueFactRecordListRequest(
                    1001L,
                    "draft",
                    null,
                    null,
                    "Rocksdb",
                    "draft",
                    "LEVEL2",
                    null,
                    null,
                    "processing",
                    "bug",
                    "CC2026R1",
                    null,
                    null,
                    null,
                    null,
                    1,
                    20,
                    "updatedAt",
                    "desc"),
                "phase1",
                "alice",
                "bob"));

    assertThat(response.records()).hasSize(1);
    assertThat(response.records().getFirst().issueIid()).isEqualTo(301);
    assertThat(response.records().getFirst().issueLink())
        .isEqualTo("http://gitlab.example.com/group/project/-/issues/301");
    verify(issueFactRecordRepository)
        .findPage(
            argThat(
                query ->
                    query.scope() == IssueFactRecordPageQuery.Scope.SYSTEM_TEST
                        && "phase1".equals(query.testingPhase())
                        && "alice".equals(query.authorName())
                        && "bob".equals(query.assigneeName())
                        && "draft".equals(query.listRequest().keyword())));
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
        "processing",
        "bug",
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
        List.of(testingPhase, "system test"),
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
