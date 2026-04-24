package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.ReviewDataRecordRowResponse;
import com.data.collection.platform.entity.statistics.StatisticFilterCondition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewDataRecordFilterGroupSupportTest {

  private final JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());

  @Test
  void shouldNormalizeFilterGroupAndDropUnsupportedConditions() {
    StatisticFilterGroup filterGroup =
        ReviewDataRecordFilterGroupSupport.parse(
            jsonUtils,
            """
            {
              "logic": "OR",
              "conditions": [
                {"fieldKey": "title", "operator": "contains", "value": " 评审 "},
                {"fieldKey": "unknown", "operator": "eq", "value": "x"},
                {"fieldKey": "problemCount", "operator": "between", "value": "2", "secondaryValue": "5"}
              ]
            }
            """);

    assertThat(filterGroup.logic()).isEqualTo("OR");
    assertThat(filterGroup.conditions()).hasSize(2);
    assertThat(filterGroup.conditions().get(0).value()).isEqualTo("评审");
  }

  @Test
  void shouldMatchStatusNumberAndDateConditions() {
    StatisticFilterGroup filterGroup =
        ReviewDataRecordFilterGroupSupport.parse(
            jsonUtils,
            """
            {
              "logic": "AND",
              "conditions": [
                {"fieldKey": "problemStatus", "operator": "eq", "value": "待整改"},
                {"fieldKey": "problemCount", "operator": "between", "value": "2", "secondaryValue": "5"},
                {"fieldKey": "reviewDate", "operator": "after", "value": "2026-04-01"}
              ]
            }
            """);

    ReviewDataRecordRowResponse row = row(10L, "架构评审报告", 3, LocalDate.of(2026, 4, 20));

    assertThat(
            ReviewDataRecordFilterGroupSupport.matches(
                row, filterGroup, Map.of(10L, List.of("待整改"))))
        .isTrue();
    assertThat(
            ReviewDataRecordFilterGroupSupport.matches(
                row, filterGroup, Map.of(10L, List.of("已关闭"))))
        .isFalse();
  }

  @Test
  void shouldReportWhetherFilterNeedsField() {
    StatisticFilterGroup filterGroup =
        new StatisticFilterGroup(
            "AND",
            List.of(new StatisticFilterCondition("problemStatus", "eq", "待整改", null)));

    assertThat(ReviewDataRecordFilterGroupSupport.needsField(filterGroup, "problemStatus")).isTrue();
    assertThat(ReviewDataRecordFilterGroupSupport.needsField(filterGroup, "reviewExpert")).isFalse();
  }

  private ReviewDataRecordRowResponse row(
      Long id, String title, Integer problemCount, LocalDate reviewDate) {
    return new ReviewDataRecordRowResponse(
        id,
        "项目A",
        title,
        "模块A",
        "人工评审",
        reviewDate,
        "负责人A",
        "专家A",
        12,
        "产品A",
        "作者A",
        "V1.0",
        problemCount,
        0.25,
        LocalDateTime.of(2026, 4, 20, 10, 0),
        false);
  }
}
