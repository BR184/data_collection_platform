package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.CustomerIssueRecordListResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerIssueRecordServiceTest {

  @Mock private IssueFactRecordRepository issueFactRecordRepository;
  @Mock private CustomerIssueScopeProfile customerIssueScopeProfile;
  @Mock private GitlabIssueLinkService issueLinkService;

  @Test
  void shouldUseSqlPageForPlainListRequests() {
    CustomerIssueRecordService service =
        new CustomerIssueRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService);
    when(issueFactRecordRepository.findPage(any()))
        .thenReturn(
            new PageSlice<>(
                List.of(record(100, "CC_PRODUCT", List.of("draft"), false, false, "", "Alice", "Bob")),
                1,
                1,
                20));

    CustomerIssueRecordListResponse response =
        service.listRecords(
            new CustomerIssueRecordQueryRequest(
                "cc-product",
                new IssueFactRecordListRequest(
                    325L,
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
                null));

    assertThat(response.total()).isEqualTo(1);
    verify(issueFactRecordRepository)
        .findPage(
            argThat(
                query ->
                    query.scope() == IssueFactRecordPageQuery.Scope.CUSTOMER
                        && !query.delayOnly()
                        && !query.illegalOnly()));
  }

  @Test
  void shouldApplyTopicAndCommonFiltersThroughRequestObject() {
    CustomerIssueRecordService service =
        new CustomerIssueRecordService(
            issueFactRecordRepository,
            customerIssueScopeProfile,
            new ObjectMapper(),
            issueLinkService);
    when(issueLinkService.issueUrl(325L, 101)).thenReturn("http://gitlab.example.com/group/project/-/issues/101");
    when(issueFactRecordRepository.findPage(any()))
        .thenReturn(
            new PageSlice<>(
                List.of(
                    record(
                        101,
                        "CC_PRODUCT delayed issue",
                        List.of("draft"),
                        true,
                        false,
                        "design",
                        "Alice",
                        "Bob")),
                1,
                1,
                20));

    CustomerIssueRecordListResponse response =
        service.listRecords(
            new CustomerIssueRecordQueryRequest(
                "delay",
                new IssueFactRecordListRequest(
                    325L,
                    "delay",
                    null,
                    null,
                    "CC_PRODUCT",
                    "draft",
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
                "design",
                null));

    assertThat(response.records()).hasSize(1);
    assertThat(response.records().getFirst().issueIid()).isEqualTo(101);
    assertThat(response.records().getFirst().issueLink())
        .isEqualTo("http://gitlab.example.com/group/project/-/issues/101");
    verify(issueFactRecordRepository)
        .findPage(
            argThat(
                query ->
                    query.scope() == IssueFactRecordPageQuery.Scope.CUSTOMER
                        && query.delayOnly()
                        && "design".equals(query.reasonCategory())
                        && query.listRequest().projectId().equals(325L)
                        && "delay".equals(query.listRequest().keyword())));
  }

  private IssueFactRecord record(
      int issueIid,
      String title,
      List<String> moduleNames,
      boolean delayIssue,
      boolean illegal,
      String reasonCategory,
      String authorName,
      String assigneeName) {
    LocalDateTime now = LocalDateTime.of(2026, 4, 24, 10, 0);
    return new IssueFactRecord(
        325L,
        "CC_PRODUCT",
        9000L + issueIid,
        issueIid,
        title,
        "opened",
        "",
        "",
        "S2",
        "P1",
        "Open",
        "Bug",
        reasonCategory,
        false,
        "",
        false,
        false,
        false,
        false,
        false,
        "R1",
        authorName,
        assigneeName,
        moduleNames,
        List.of("customer"),
        delayIssue,
        delayIssue ? "delay requested" : "",
        "",
        false,
        false,
        illegal,
        illegal ? "illegal reason" : "",
        now.minusDays(3),
        now.minusDays(1),
        null);
  }
}
