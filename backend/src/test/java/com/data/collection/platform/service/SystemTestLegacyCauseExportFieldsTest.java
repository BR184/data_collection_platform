package com.data.collection.platform.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SystemTestLegacyCauseExportFieldsTest {
  @Test
  void test_affected_function_options_export_checked_state() {
    SystemTestLegacyCauseExportFields fields =
        SystemTestLegacyCauseExportFields.fromReasonText(
            "6、修改该缺陷可能影响的功能：*已知的受影响功能："
                + "7、是否对可能影响的功能进行了测试");

    assertThat(fields.knownAffectedFunction()).isEqualTo("是");
    assertThat(fields.newlyIdentifiedAffectedFunction()).isEqualTo("否");
  }

  @Test
  void test_affected_function_options_distinguish_unselected_from_unknown_template() {
    SystemTestLegacyCauseExportFields noOtherImpact =
        SystemTestLegacyCauseExportFields.fromReasonText(
            "6、修改该缺陷可能影响的功能：*无其他影响"
                + "7、是否对可能影响的功能进行了测试");
    SystemTestLegacyCauseExportFields legacyTemplate =
        SystemTestLegacyCauseExportFields.fromReasonText(
            "6、修改该缺陷可能影响的功能：草图"
                + "7、是否对可能影响的功能进行了测试");

    assertThat(noOtherImpact.knownAffectedFunction()).isEqualTo("否");
    assertThat(noOtherImpact.newlyIdentifiedAffectedFunction()).isEqualTo("否");
    assertThat(legacyTemplate.knownAffectedFunction()).isEqualTo("--");
    assertThat(legacyTemplate.newlyIdentifiedAffectedFunction()).isEqualTo("--");
  }

  @Test
  void test_affected_function_options_honor_selection_markers_when_template_lists_all_options() {
    SystemTestLegacyCauseExportFields fields =
        SystemTestLegacyCauseExportFields.fromReasonText(
            "6、修改该缺陷可能影响的功能：*已知的受影响功能："
                + "新识别的受影响功能：无其他影响"
                + "7、是否对可能影响的功能进行了测试");

    assertThat(fields.knownAffectedFunction()).isEqualTo("是");
    assertThat(fields.newlyIdentifiedAffectedFunction()).isEqualTo("否");
  }
}
