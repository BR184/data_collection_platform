package com.data.collection.platform.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.JsonUtils;
import com.data.collection.platform.entity.statistics.StatisticBoardDefinition;
import com.data.collection.platform.entity.statistics.StatisticFilterField;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StatisticFilterGroupSupportTest {

  private final JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());

  @Test
  void shouldParseJsonFilterGroupBeforeLegacyFields() {
    StatisticFilterGroup parsed =
        StatisticFilterGroupSupport.parseFilterGroup(
            jsonUtils,
            Map.of(
                "projectName",
                "legacy-project",
                "filterGroup",
                "{\"logic\":\"OR\",\"conditions\":[{\"fieldKey\":\"moduleName\",\"operator\":\"contains\",\"value\":\"alpha\"}]}"),
            definition());

    assertThat(parsed.logic()).isEqualTo("OR");
    assertThat(parsed.conditions()).hasSize(1);
    assertThat(parsed.conditions().get(0).fieldKey()).isEqualTo("moduleName");
  }

  @Test
  void shouldBuildLegacyFilterGroupForSimpleQueryParams() {
    StatisticFilterGroup parsed =
        StatisticFilterGroupSupport.parseFilterGroup(
            jsonUtils,
            Map.of("projectName", "project-a"),
            definition());

    assertThat(parsed.logic()).isEqualTo("AND");
    assertThat(parsed.conditions()).hasSize(1);
    assertThat(parsed.conditions().get(0).fieldKey()).isEqualTo("projectName");
    assertThat(parsed.conditions().get(0).operator()).isEqualTo("eq");
  }

  @Test
  void shouldPreserveLabelGroupConditionsFromJsonFilterGroup() {
    StatisticFilterGroup parsed =
        StatisticFilterGroupSupport.parseFilterGroup(
            jsonUtils,
            Map.of(
                "filterGroup",
                """
                {"logic":"AND","conditions":[{"fieldKey":"moduleName","operator":"eq","valueType":"LABEL_GROUP","labelGroupId":8,"labelGroupName":"核心模块"}]}
                """),
            definition());

    assertThat(parsed.conditions()).hasSize(1);
    assertThat(parsed.conditions().get(0).usesLabelGroup()).isTrue();
    assertThat(parsed.conditions().get(0).value()).isNull();
    assertThat(parsed.conditions().get(0).labelGroupId()).isEqualTo(8L);
    assertThat(parsed.conditions().get(0).labelGroupName()).isEqualTo("核心模块");
  }

  @Test
  void shouldRejectUnsupportedLabelGroupOperator() {
    assertThatThrownBy(
            () ->
                StatisticFilterGroupSupport.parseFilterGroup(
                    jsonUtils,
                    Map.of(
                        "filterGroup",
                        """
                        {"logic":"AND","conditions":[{"fieldKey":"moduleName","operator":"contains","valueType":"LABEL_GROUP","labelGroupId":8}]}
                        """),
                    definition()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported label group operator");
  }

  @Test
  void shouldRejectUnsupportedJsonField() {
    assertThatThrownBy(
            () ->
                StatisticFilterGroupSupport.parseFilterGroup(
                    jsonUtils,
                    Map.of(
                        "filterGroup",
                        "{\"logic\":\"AND\",\"conditions\":[{\"fieldKey\":\"unknown\",\"operator\":\"eq\",\"value\":\"x\"}]}"),
                    definition()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private StatisticBoardDefinition definition() {
    return new StatisticBoardDefinition(
        "board",
        "Board",
        "",
        "",
        "",
        "对象",
        List.of(
            new StatisticFilterField(
                "projectName",
                "项目名称",
                "text",
                "",
                "",
                180,
                List.of("eq", "contains"),
                List.of()),
            new StatisticFilterField(
                "moduleName",
                "模块名称",
                "text",
                "",
                "",
                180,
                List.of("eq", "contains"),
                List.of())),
        List.of(),
        List.of(),
        10,
        "empty");
  }
}
