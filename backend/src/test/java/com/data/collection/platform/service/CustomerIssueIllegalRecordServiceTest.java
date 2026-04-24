package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.data.collection.platform.entity.CustomerIssueIllegalRecordListResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CustomerIssueIllegalRecordServiceTest {

  @Mock private IssueFactRecordRepository issueFactRecordRepository;
  @Mock private CustomerIssueScopeProfile customerIssueScopeProfile;

  @Test
  void shouldApplyIllegalFiltersThroughSharedPipeline() {
    CustomerIssueIllegalRecordService service =
        new CustomerIssueIllegalRecordService(
            issueFactRecordRepository, customerIssueScopeProfile, new ObjectMapper());
    when(issueFactRecordRepository.findByProjectId(325L))
        .thenReturn(
            List.of(
                record(201, "草图非法", "草图", true, "模块缺失"),
                record(202, "草图合法", "草图", false, ""),
                record(203, "装配非法", "装配", true, "模块缺失")));
    when(customerIssueScopeProfile.matches(any())).thenReturn(true);

    CustomerIssueIllegalRecordListResponse response =
        service.listRecords(
            new CustomerIssueIllegalRecordQueryRequest(
                new IssueFactRecordListRequest(
                    325L,
                    "非法",
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
                "模块缺失",
                null));

    assertThat(response.records()).hasSize(1);
    assertThat(response.records().getFirst().issueIid()).isEqualTo(201);
  }

  private IssueFactRecord record(
      int issueIid, String title, String moduleName, boolean illegal, String illegalReason) {
    LocalDateTime now = LocalDateTime.of(2026, 4, 24, 10, 0);
    return new IssueFactRecord(
        325L,
        "CC_PRODUCT",
        9100L + issueIid,
        issueIid,
        title,
        "opened",
        "",
        "",
        "S1",
        "P0",
        "Open",
        "Bug",
        "设计问题",
        false,
        "",
        false,
        false,
        false,
        false,
        false,
        "R1",
        "Alice",
        "Bob",
        List.of(moduleName),
        List.of("客户问题"),
        false,
        "",
        "",
        false,
        false,
        illegal,
        illegalReason,
        now.minusDays(3),
        now.minusDays(1),
        null);
  }
}
