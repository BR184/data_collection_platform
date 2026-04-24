package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.CustomerIssueRecordListResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerIssueRecordServiceTest {

  @Mock private IssueFactRecordRepository issueFactRecordRepository;
  @Mock private CustomerIssueScopeProfile customerIssueScopeProfile;

  @InjectMocks private CustomerIssueRecordService service;

  @Test
  void shouldApplyTopicAndCommonFiltersThroughRequestObject() {
    service =
        new CustomerIssueRecordService(
            issueFactRecordRepository, customerIssueScopeProfile, new ObjectMapper());
    when(issueFactRecordRepository.findByProjectId(325L))
        .thenReturn(
            List.of(
                record(101, "CC_PRODUCT 延期议题", List.of("草图"), true, false, "设计问题", "Alice", "Bob"),
                record(102, "普通客户问题", List.of("草图"), false, false, "设计问题", "Alice", "Bob"),
                record(103, "模块不匹配", List.of("装配"), true, false, "流程问题", "Alice", "Bob")));
    when(customerIssueScopeProfile.matches(any())).thenReturn(true);

    CustomerIssueRecordListResponse response =
        service.listRecords(
            new CustomerIssueRecordQueryRequest(
                "delay",
                new IssueFactRecordListRequest(
                    325L,
                    "延期",
                    null,
                    null,
                    "CC_PRODUCT",
                    "草图",
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
                "设计问题",
                null));

    assertThat(response.records()).hasSize(1);
    assertThat(response.records().getFirst().issueIid()).isEqualTo(101);
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
        List.of("客户问题"),
        delayIssue,
        delayIssue ? "延期申请" : "",
        "",
        false,
        false,
        illegal,
        illegal ? "非法原因" : "",
        now.minusDays(3),
        now.minusDays(1),
        null);
  }
}
