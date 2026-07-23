package com.data.collection.platform.service.labelgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.CustomerIssueRecordFilterOptionsResponse;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.ReviewDataFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchFilterOptionsResponse;
import com.data.collection.platform.entity.labelgroup.LabelValuePageResponse;
import com.data.collection.platform.entity.labelgroup.LabelValueResponse;
import com.data.collection.platform.service.CustomerIssueRecordService;
import com.data.collection.platform.service.ReviewDataRecordService;
import com.data.collection.platform.service.SystemTestIssueSearchService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LabelValueQueryServiceTest {

  @Mock private ReviewDataRecordService reviewDataRecordService;
  @Mock private SystemTestIssueSearchService systemTestIssueSearchService;
  @Mock private CustomerIssueRecordService customerIssueRecordService;

  @Test
  void shouldReturnReviewPageScopedValuesWhenPageKeyProvided() {
    LabelValueQueryService service = service();
    when(reviewDataRecordService.getFilterOptions())
        .thenReturn(reviewOptions(
            List.of(option("CrownCAD")),
            List.of(option("草图")),
            List.of(option("张三")),
            List.of(option("李四"))));

    LabelValuePageResponse response =
        service.listValues("review_owner", "review-data-home", null, null, 1, 20);

    assertThat(response.items()).extracting(LabelValueResponse::value).containsExactly("张三");
    assertThat(response.total()).isEqualTo(1);
  }

  @Test
  void shouldForwardSourceInstanceForSystemTestIssuePage() {
    LabelValueQueryService service = service();
    when(systemTestIssueSearchService.getFilterOptions(null, "cc"))
        .thenReturn(
            new SystemTestIssueSearchFilterOptionsResponse(
                List.of(option("CC2026R1")),
                List.of(option("草图")),
                List.of(option("拉伸")),
                List.of(option("第一轮系统测试")),
                List.of(),
                List.of(option("王五")),
                List.of(),
                List.of(option("一级缺陷")),
                List.of(),
                List.of(),
                List.of(option("R1"))));

    LabelValuePageResponse response =
        service.listValues("issue_assignee", "question-metrics-issue-search", "cc", null, 1, 20);

    assertThat(response.items()).extracting(LabelValueResponse::value).containsExactly("王五");
  }

  @Test
  void shouldFilterKeywordAndPaginateValues() {
    LabelValueQueryService service = service();
    when(reviewDataRecordService.getFilterOptions())
        .thenReturn(reviewOptions(
            List.of(),
            List.of(option("草图"), option("工程图"), option("草图工具")),
            List.of(),
            List.of()));

    LabelValuePageResponse response = service.listValues("module", "review-data-home", null, "草图", 1, 1);

    assertThat(response.total()).isEqualTo(2);
    assertThat(response.items()).extracting(LabelValueResponse::value).containsExactly("草图");
  }

  @Test
  void shouldReturnClosureStatusCanonicalValueOnly() {
    LabelValueQueryService service = service();

    LabelValuePageResponse response = service.listValues("closure_status", null, null, null, 1, 20);

    assertThat(response.items()).extracting(LabelValueResponse::value).containsExactly("需求如此");
    assertThat(response.items()).extracting(LabelValueResponse::label).containsExactly("需求如此");
  }

  @Test
  void shouldRejectIncompatiblePageDimension() {
    LabelValueQueryService service = service();

    assertThatThrownBy(() -> service.listValues("review_owner", "question-metrics-issue-search", null, null, 1, 20))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("当前页面不支持该标签维度");
  }

  @Test
  void shouldAggregateAllCompatiblePagesWhenPageKeyMissing() {
    LabelValueQueryService service = service();
    when(reviewDataRecordService.getFilterOptions())
        .thenReturn(reviewOptions(
            List.of(option("CrownCAD")),
            List.of(option("草图")),
            List.of(),
            List.of()));
    when(systemTestIssueSearchService.getFilterOptions(null, null))
        .thenReturn(
            new SystemTestIssueSearchFilterOptionsResponse(
                List.of(option("CC2026R1")),
                List.of(option("工程图")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()));
    when(customerIssueRecordService.getFilterOptions("cc-product", null, null))
        .thenReturn(customerOptions(
            List.of(option("BOM")),
            List.of(),
            List.of(),
            List.of()));

    LabelValuePageResponse response = service.listValues("module", null, null, null, 1, 20);

    assertThat(response.items()).extracting(LabelValueResponse::value).containsExactly("BOM", "工程图", "草图");
  }

  @Test
  void shouldReturnCustomerAssigneeValuesFromCustomerIssueOptions() {
    LabelValueQueryService service = service();
    when(customerIssueRecordService.getFilterOptions("cc-product", null, null))
        .thenReturn(customerOptions(
            List.of(),
            List.of(),
            List.of(option("赵六")),
            List.of()));

    LabelValuePageResponse response =
        service.listValues("customer_assignee", "customer-issues-cc-product-issues", null, null, 1, 20);

    assertThat(response.items()).extracting(LabelValueResponse::value).containsExactly("赵六");
  }

  @Test
  void shouldForwardSourceInstanceForCustomerIssuePage() {
    LabelValueQueryService service = service();
    when(customerIssueRecordService.getFilterOptions("cc-product", null, "cc"))
        .thenReturn(customerOptions(
            List.of(),
            List.of(),
            List.of(option("钱七")),
            List.of()));

    LabelValuePageResponse response =
        service.listValues("customer_assignee", "customer-issues-cc-product-issues", "cc", null, 1, 20);

    assertThat(response.items()).extracting(LabelValueResponse::value).containsExactly("钱七");
  }

  private LabelValueQueryService service() {
    return new LabelValueQueryService(
        new LabelDimensionCatalogService(),
        reviewDataRecordService,
        systemTestIssueSearchService,
        customerIssueRecordService);
  }

  private static OptionItemResponse option(String value) {
    return new OptionItemResponse(value, value);
  }

  private static ReviewDataFilterOptionsResponse reviewOptions(
      List<OptionItemResponse> projectNames,
      List<OptionItemResponse> moduleNames,
      List<OptionItemResponse> reviewOwners,
      List<OptionItemResponse> reviewExperts) {
    return new ReviewDataFilterOptionsResponse(
        projectNames,
        moduleNames,
        reviewOwners,
        List.of(),
        reviewExperts,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        projectNames,
        moduleNames);
  }

  private static CustomerIssueRecordFilterOptionsResponse customerOptions(
      List<OptionItemResponse> moduleNames,
      List<OptionItemResponse> priorityLevels,
      List<OptionItemResponse> assigneeNames,
      List<OptionItemResponse> milestoneTitles) {
    return new CustomerIssueRecordFilterOptionsResponse(
        List.of(),
        moduleNames,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        priorityLevels,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        assigneeNames,
        List.of(),
        List.of(),
        List.of(),
        milestoneTitles);
  }
}
