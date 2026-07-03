package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.ReviewDataRecordListResponse;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.labelgroup.LabelGroupExpansionResponse;
import com.data.collection.platform.service.labelgroup.LabelGroupExpansionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewDataRecordQueryServiceTest {
  @Mock private ReviewDataRecordPersistenceSupport persistenceSupport;
  @Mock private LabelGroupExpansionService labelGroupExpansionService;
  @Mock private CodeReviewMatchModeSwitchService matchModeSwitchService;
  @Mock private ReviewDataMatchModeRecordRepository matchModeRecordRepository;
  @Mock private ReviewDataMatchModeMaterializeService matchModeMaterializeService;

  @Test
  void shouldMatchScalarFieldAgainstLabelGroupValuesFromFilterGroup() {
    ReviewDataRecordQueryService service = service();
    when(labelGroupExpansionService.expand(1L, "STRING", "moduleName", "review-data-home", "cc"))
        .thenReturn(expansion(1L, "核心模块", "工程图"));
    when(persistenceSupport.loadRecords(null, null, null, null, null, null, null, null))
        .thenReturn(
            List.of(
                row(1L, "项目A", "草图", "负责人A", "专家A"),
                row(2L, "项目A", "工程图", "负责人B", "专家B"),
                row(3L, "项目A", "BOM", "负责人C", "专家C")));

    ReviewDataRecordListResponse response =
        service.listRecords(
            new ReviewDataRecordQueryRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                labelGroupFilter("moduleName", "eq", 1L),
                "cc",
                1,
                20,
                "updatedAt",
                "desc"));

    assertThat(response.records())
        .extracting(ReviewDataRecordRowResponse::moduleName)
        .containsExactly("工程图");
    verify(persistenceSupport).loadRecords(null, null, null, null, null, null, null, null);
  }

  @Test
  void shouldMatchReviewExpertWhenAnyExpertIntersectsWithLabelGroupMembers() {
    ReviewDataRecordQueryService service = service();
    when(labelGroupExpansionService.expand(2L, "STRING", "reviewExpert", "review-data-home", null))
        .thenReturn(expansion(2L, "核心专家", "李四"));
    when(persistenceSupport.loadRecords(null, null, null, null, null, null, null, null))
        .thenReturn(
            List.of(
                row(1L, "项目A", "草图", "负责人A", "王五、李四"),
                row(2L, "项目A", "草图", "负责人A", "王五")));

    ReviewDataRecordListResponse response =
        service.listRecords(
            new ReviewDataRecordQueryRequest(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                labelGroupFilter("reviewExpert", "eq", 2L),
                null,
                1,
                20,
                "updatedAt",
                "desc"));

    assertThat(response.records()).extracting(ReviewDataRecordRowResponse::id).containsExactly(1L);
  }

  @Test
  void shouldRejectUnsupportedLabelGroupFieldBeforeQueryingRecords() {
    ReviewDataRecordQueryService service = service();

    assertThatThrownBy(
            () ->
                service.listRecords(
                    new ReviewDataRecordQueryRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        labelGroupFilter("reviewType", "eq", 1L),
                        null,
                        1,
                        20,
                        "updatedAt",
                        "desc")))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("当前页面不支持该标签组筛选字段");
  }

  @Test
  void shouldRejectUnsupportedLabelGroupOperator() {
    ReviewDataRecordQueryService service = service();

    assertThatThrownBy(
            () ->
                service.listRecords(
                    new ReviewDataRecordQueryRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        labelGroupFilter("title", "contains", 1L),
                        null,
                        1,
                        20,
                        "updatedAt",
                        "desc")))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("标签组筛选只支持等于或不等于关系");
  }

  @Test
  void shouldRejectExpandedValuesOverLimit() {
    ReviewDataRecordQueryService service = service();
    List<String> values =
        java.util.stream.IntStream.rangeClosed(1, 201).mapToObj(index -> "模块" + index).toList();
    when(labelGroupExpansionService.expand(1L, "STRING", "moduleName", "review-data-home", null))
        .thenReturn(new LabelGroupExpansionResponse(1L, "大组", "STRING", values, List.of()));

    assertThatThrownBy(
            () ->
                service.listRecords(
                    new ReviewDataRecordQueryRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        labelGroupFilter("moduleName", "eq", 1L),
                        null,
                        1,
                        20,
                        "updatedAt",
                        "desc")))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("超过 200 个值");
  }

  private ReviewDataRecordQueryService service() {
    return new ReviewDataRecordQueryService(
        persistenceSupport,
        new ReviewDataSummaryService(),
        new JsonUtils(new ObjectMapper()),
        labelGroupExpansionService,
        matchModeSwitchService,
        matchModeRecordRepository,
        matchModeMaterializeService);
  }

  private String labelGroupFilter(String fieldKey, String operator, Long groupId) {
    return """
        {"logic":"AND","conditions":[{"fieldKey":"%s","operator":"%s","valueType":"LABEL_GROUP","labelGroupId":%d,"labelGroupName":"测试组"}]}
        """
        .formatted(fieldKey, operator, groupId)
        .trim();
  }

  private LabelGroupExpansionResponse expansion(Long groupId, String groupName, String... values) {
    return new LabelGroupExpansionResponse(groupId, groupName, "STRING", List.of(values), List.of());
  }

  private ReviewDataRecordRowResponse row(
      Long id, String projectName, String moduleName, String reviewOwner, String reviewExperts) {
    return new ReviewDataRecordRowResponse(
        id,
        projectName,
        "评审单" + id,
        moduleName,
        "需求评审",
        LocalDate.of(2026, 6, 10),
        reviewOwner,
        reviewExperts,
        10,
        "需求文档",
        "作者A",
        "V1.0",
        1,
        0.1,
        LocalDateTime.of(2026, 6, 10, 10, 0).plusMinutes(id),
        false);
  }
}
