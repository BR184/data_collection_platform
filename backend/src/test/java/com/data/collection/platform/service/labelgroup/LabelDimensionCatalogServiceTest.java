package com.data.collection.platform.service.labelgroup;

import static org.assertj.core.api.Assertions.assertThat;

import com.data.collection.platform.entity.labelgroup.LabelGroupCompatiblePageResponse;
import org.junit.jupiter.api.Test;

class LabelDimensionCatalogServiceTest {

  private final LabelDimensionCatalogService service = new LabelDimensionCatalogService();

  @Test
  void shouldReturnChineseDimensionNamesAndStableKeys() {
    assertThat(service.listDimensions())
        .extracting(LabelDimensionDefinition::key)
        .contains(
            "module",
            "project",
            "review_owner",
            "review_expert",
            "issue_assignee",
            "customer_assignee",
            "closure_status");

    assertThat(service.getDimension("module").name()).isEqualTo("模块");
    assertThat(service.getDimension("review_owner").name()).isEqualTo("评审负责人");
    assertThat(service.getDimension("customer_assignee").name()).isEqualTo("客户问题处理人");
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
  void shouldReturnCompatiblePagesForDimension() {
    assertThat(service.listCompatiblePages("review_owner"))
        .extracting(LabelGroupCompatiblePageResponse::pageKey)
        .containsExactly("review-data-home");

    assertThat(service.listCompatiblePages("issue_assignee"))
        .extracting(LabelGroupCompatiblePageResponse::pageKey)
        .containsExactly("question-metrics-issue-search");
  }
}
