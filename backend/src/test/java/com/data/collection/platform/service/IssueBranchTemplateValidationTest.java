package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IssueBranchTemplateValidationTest {
  @ParameterizedTest
  @ValueSource(strings = {"dev", "CC2026R4SP1", "release/2027&hotfix/login", "dev & CC2026R4SP1"})
  void shouldAcceptFreeBranchesInResearchTemplate(String branch) {
    assertThat(reasons(branch)).doesNotContain("未按照要求填写缺陷调研模板");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "dev、release", "dev&&release", "dev＆release"})
  void shouldRejectInvalidBranchListsInResearchTemplate(String branch) {
    assertThat(reasons(branch)).contains("未按照要求填写缺陷调研模板");
  }

  private List<String> reasons(String branch) {
    String notes = "# 问题调研情况说明\n[x] 缺陷\n## 问题原因：已定位\n"
        + "## 修改方案：已确认\n## 计划解决时间：2026.11.11\n## 计划合并的版本分支：" + branch;
    return IssueClassificationRules.customerIssueIllegalReasons(
        List.of("二级缺陷"), List.of("模块A"), notes, false);
  }
}
