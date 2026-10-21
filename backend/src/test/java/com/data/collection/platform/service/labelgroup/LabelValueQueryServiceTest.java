package com.data.collection.platform.service.labelgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.CustomerIssueRecordFilterOptionsResponse;
import com.data.collection.platform.entity.OptionItemResponse;
import com.data.collection.platform.entity.ReviewDataFilterOptionsResponse;
import com.data.collection.platform.entity.SystemTestIssueSearchFilterOptionsResponse;
import com.data.collection.platform.entity.labelgroup.LabelValuePageResponse;
import com.data.collection.platform.entity.labelgroup.LabelValueResponse;
import com.data.collection.platform.service.CustomerIssueRecordService;
import com.data.collection.platform.service.ReviewDataMirrorOptionRepository;
import com.data.collection.platform.service.ReviewDataRecordService;
import com.data.collection.platform.service.SystemTestIssueSearchService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LabelValueQueryServiceTest {

  @Mock(answer = org.mockito.Answers.RETURNS_SELF) private ReviewDataMirrorOptionRepository mirrorOptionRepository;
  @Mock(answer = org.mockito.Answers.RETURNS_SELF) private ReviewDataRecordService reviewDataRecordService;
  @Mock(answer = org.mockito.Answers.RETURNS_SELF) private SystemTestIssueSearchService systemTestIssueSearchService;
  @Mock(answer = org.mockito.Answers.RETURNS_SELF) private CustomerIssueRecordService customerIssueRecordService;

  @Test
  void mirrorDimensionsLoadFullCandidatesFromMirrorRepository() {
    when(mirrorOptionRepository.loadLabelProjectNames()).thenReturn(List.of("DGM", "CC2026"));
    when(mirrorOptionRepository.loadUserNames()).thenReturn(List.of("王五", "李四", "张三"));
    when(mirrorOptionRepository.loadMilestoneTitles()).thenReturn(List.of("CC2026 R3", "CC2025 R1"));

    assertThat(valuesOf(service().listValues("project", null, null, null, 1, 20)))
        .containsExactly("CC2026", "DGM");
    assertThat(valuesOf(service().listValues("person", null, null, null, 1, 20)))
        .containsExactly("张三", "李四", "王五");
    assertThat(valuesOf(service().listValues("milestone", null, null, null, 1, 20)))
        .containsExactly("CC2025 R1", "CC2026 R3");
    verifyNoInteractions(reviewDataRecordService, systemTestIssueSearchService, customerIssueRecordService);
  }

  @Test
  void personValuesStayFullWhenScopedToCompatiblePage() {
    when(mirrorOptionRepository.loadUserNames()).thenReturn(List.of("王五", "李四", "张三"));

    assertThat(valuesOf(service().listValues("person", "review-data-home", null, null, 1, 20)))
        .containsExactly("张三", "李四", "王五");
    assertThat(valuesOf(service().listValues("person", "customer-issues-cc-product-issues", "cc", null, 1, 20)))
        .containsExactly("张三", "李四", "王五");
  }

  @Test
  void personValuesRejectIncompatiblePage() {
    assertThatThrownBy(() -> service().listValues("person", "unknown-page", null, null, 1, 20))
        .isInstanceOf(BizException.class)
        .hasMessage("当前页面不支持该标签维度：人员");
  }

  @Test
  void shouldReturnPageScopedModuleValuesWhenPageKeyProvided() {
    when(reviewDataRecordService.getFilterOptions())
        .thenReturn(reviewOptions(
            List.of(option("CrownCAD")),
            List.of(option("草图")),
            List.of(option("张三")),
            List.of(option("李四"))));

    LabelValuePageResponse response =
        service().listValues("module", "review-data-home", null, null, 1, 20);

    assertThat(response.items()).extracting(LabelValueResponse::value).containsExactly("草图");
    assertThat(response.total()).isEqualTo(1);
  }

  @Test
  void shouldForwardSourceInstanceForSystemTestIssuePage() {
    when(systemTestIssueSearchService.getFilterOptions(null, "cc"))
        .thenReturn(
            new SystemTestIssueSearchFilterOptionsResponse(
                List.of(option("CC2026R1")),
                List.of(option("拉伸")),
                List.of(),
                List.of(option("第一轮系统测试")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(option("R1"))));

    LabelValuePageResponse response =
        service().listValues("test_stage", "question-metrics-issue-search", "cc", null, 1, 20);

    assertThat(response.items()).extracting(LabelValueResponse::value).containsExactly("第一轮系统测试");
  }

  @Test
  void shouldForwardSourceInstanceForCustomerIssuePage() {
    when(customerIssueRecordService.getFilterOptions("cc-product", null, "cc"))
        .thenReturn(customerOptions(
            List.of(),
            List.of(option("P2")),
            List.of(),
            List.of()));

    LabelValuePageResponse response =
        service().listValues("priority_level", "customer-issues-cc-product-issues", "cc", null, 1, 20);

    assertThat(response.items()).extracting(LabelValueResponse::value).containsExactly("P2");
  }

  @Test
  void shouldFilterKeywordAndPaginateValues() {
    when(reviewDataRecordService.getFilterOptions())
        .thenReturn(reviewOptions(
            List.of(),
            List.of(option("草图"), option("工程图"), option("草图工具")),
            List.of(),
            List.of()));

    LabelValuePageResponse response = service().listValues("module", "review-data-home", null, "草图", 1, 1);

    assertThat(response.total()).isEqualTo(2);
    assertThat(response.items()).extracting(LabelValueResponse::value).containsExactly("草图");
  }

  @Test
  void shouldReturnClosureStatusCanonicalValueOnly() {
    LabelValuePageResponse response = service().listValues("closure_status", null, null, null, 1, 20);

    assertThat(response.items()).extracting(LabelValueResponse::value).containsExactly("需求如此");
    assertThat(response.items()).extracting(LabelValueResponse::label).containsExactly("需求如此");
  }

  @Test
  void shouldAggregateAllCompatiblePagesWhenPageKeyMissing() {
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

    LabelValuePageResponse response = service().listValues("module", null, null, null, 1, 20);

    assertThat(response.items()).extracting(LabelValueResponse::value).containsExactly("BOM", "工程图", "草图");
  }

  private LabelValueQueryService service() {
    // 此套件只锁定候选值业务映射；真实版本与镜像分页失败隔离在数据集集成测试中验证。
    LabelValuePublishedReader reader = org.mockito.Mockito.mock(LabelValuePublishedReader.class, invocation -> {
      var facts = com.data.collection.platform.service.read.TestReadSelections.all();
      var selection = new com.data.collection.platform.entity.read.DatasetReadSelection(java.util.List.of(
          new com.data.collection.platform.entity.read.PublishedDatasetReadContext("default", "fixture",
              java.util.UUID.randomUUID(), 1, java.util.UUID.randomUUID(), java.time.Instant.now())), facts, facts.businessDate());
      @SuppressWarnings("unchecked")
      var callback = (java.util.function.Function<com.data.collection.platform.entity.read.ReadSelection,Object>) invocation.getArgument(3);
      return callback.apply(selection);
    });
    return new LabelValueQueryService(
        new LabelDimensionCatalogService(),
        mirrorOptionRepository,
        reviewDataRecordService,
        systemTestIssueSearchService,
        customerIssueRecordService, reader);
  }

  private static OptionItemResponse option(String value) {
    return new OptionItemResponse(value, value);
  }

  private static List<String> valuesOf(LabelValuePageResponse response) {
    return response.items().stream().map(LabelValueResponse::value).toList();
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
        List.of(),
        milestoneTitles);
  }
}
