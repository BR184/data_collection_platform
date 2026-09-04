package com.data.collection.platform.service.labelgroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.labelgroup.LabelGroupCompatiblePageResponse;
import org.junit.jupiter.api.Test;

class LabelDimensionCatalogServiceTest {

  private final LabelDimensionCatalogService service = new LabelDimensionCatalogService();

  @Test
  void shouldReturnChineseDimensionNamesAndStableKeys() {
    assertThat(service.listDimensions())
        .extracting(LabelDimensionDefinition::key)
        .containsExactly(
            "module",
            "project",
            "person",
            "target_branch",
            "milestone",
            "round",
            "test_stage",
            "severity_level",
            "priority_level",
            "defect_reason",
            "delay_reason",
            "closure_status");

    assertThat(service.getDimension("module").name()).isEqualTo("模块");
    assertThat(service.getDimension("person").name()).isEqualTo("人员");
    assertThat(service.getDimension("project").description()).contains("镜像");
    assertThat(service.getDimension("milestone").description()).contains("镜像");
  }

  @Test
  void shouldDeclareValueKindsAndExcludeDeprecatedConcepts() {
    assertThat(service.getDimension("module").valueKind()).isEqualTo(LabelValueKind.STRING_LITERAL);
    assertThat(service.getDimension("closure_status").valueKind()).isEqualTo(LabelValueKind.ENUM_KEY);

    assertThat(service.listDimensions())
        .extracting(LabelDimensionDefinition::key)
        .doesNotContain("owner", "reviewer", "tag_value", "tag_group");
    assertThat(service.listDimensions())
        .extracting(LabelDimensionDefinition::name)
        .doesNotContain("缺陷密度", "占比", "工作量合计");
  }

  @Test
  void shouldRejectLegacyPersonDimensions() {
    assertThatThrownBy(() -> service.getDimension("review_owner"))
        .isInstanceOf(BizException.class)
        .hasMessage("标签维度不存在：review_owner");
    assertThatThrownBy(() -> service.getDimension("review_expert"))
        .isInstanceOf(BizException.class)
        .hasMessage("标签维度不存在：review_expert");
    assertThatThrownBy(() -> service.getDimension("issue_assignee"))
        .isInstanceOf(BizException.class)
        .hasMessage("标签维度不存在：issue_assignee");
    assertThatThrownBy(() -> service.getDimension("customer_author"))
        .isInstanceOf(BizException.class)
        .hasMessage("标签维度不存在：customer_author");
    assertThatThrownBy(() -> service.getDimension("customer_assignee"))
        .isInstanceOf(BizException.class)
        .hasMessage("标签维度不存在：customer_assignee");
  }

  @Test
  void shouldReturnCompatiblePagesForDimensions() {
    assertThat(service.listCompatiblePages("person"))
        .extracting(LabelGroupCompatiblePageResponse::pageKey)
        .containsExactly(
            "review-data-home",
            "question-metrics-issue-search",
            "customer-issues-cc-product-issues");

    assertThat(service.listCompatiblePages("project"))
        .extracting(LabelGroupCompatiblePageResponse::pageKey)
        .containsExactly("review-data-home", "question-metrics-issue-search");

    assertThat(service.listCompatiblePages("test_stage"))
        .extracting(LabelGroupCompatiblePageResponse::pageKey)
        .containsExactly("question-metrics-issue-search");

    assertThat(service.listCompatiblePages("closure_status"))
        .extracting(LabelGroupCompatiblePageResponse::pageKey)
        .containsExactly("customer-issues-cc-product-issues");
  }
}
