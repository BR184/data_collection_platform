from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import check_label_group_dimension_matrix as script


VALID_MATRIX = """
schemaVersion: 1
dimensions:
  - key: module
    name: 模块
    valueKind: STRING_LITERAL
    mvpSupported: true
  - key: closure_status
    name: 客户问题闭环状态
    valueKind: ENUM_KEY
    mvpSupported: true
    canonicalValues:
      - 需求如此
    equivalentValues:
      需求如此:
        - 需求如此
        - 设计如此
pages:
  - pageKey: review-data-home
    name: 评审数据管理
    mvpEnabled: true
    dimensions:
      - dimensionKey: module
        fieldKey: moduleName
disallowedFields:
  - field: 缺陷密度
    reason: 指标
"""


class CheckLabelGroupDimensionMatrixTest(unittest.TestCase):
    def test_should_accept_valid_matrix(self) -> None:
        matrix = script.parse_matrix(VALID_MATRIX)

        self.assertEqual(script.validate_matrix(matrix), [])

    def test_should_reject_value_kind_expression(self) -> None:
        matrix = script.parse_matrix(VALID_MATRIX.replace("STRING_LITERAL", "GITLAB_USER_ID 或 STRING_LITERAL"))

        self.assertIn("标签维度 module 的 valueKind 不能写成多选表达式。", script.validate_matrix(matrix))

    def test_should_reject_unknown_page_dimension(self) -> None:
        matrix = script.parse_matrix(VALID_MATRIX.replace("dimensionKey: module", "dimensionKey: unknown_dimension"))

        self.assertIn(
            "页面 review-data-home 引用了未在 dimensions 声明的维度：unknown_dimension。",
            script.validate_matrix(matrix),
        )

    def test_should_reject_generic_owner_dimension(self) -> None:
        matrix = script.parse_matrix(VALID_MATRIX.replace("key: module", "key: owner", 1))

        self.assertIn("标签维度不得使用泛化人员 key：owner。", script.validate_matrix(matrix))

    def test_should_reject_closure_status_design_value_as_canonical_value(self) -> None:
        matrix = script.parse_matrix(VALID_MATRIX.replace("      - 需求如此", "      - 设计如此", 1))

        self.assertIn("closure_status 的规范保存值不能包含“设计如此”，请保存“需求如此”。", script.validate_matrix(matrix))

    def test_should_detect_yaml_dimension_missing_in_code_catalog(self) -> None:
        matrix = script.parse_matrix(VALID_MATRIX)

        errors = script.compare_with_code(matrix, {"module"})

        self.assertIn("代码维度目录缺少 YAML 维度：closure_status。", errors)

    def test_should_detect_code_dimension_missing_in_yaml(self) -> None:
        matrix = script.parse_matrix(VALID_MATRIX)

        errors = script.compare_with_code(matrix, {"module", "closure_status", "extra_dimension"})

        self.assertIn("代码维度目录存在 YAML 未声明维度：extra_dimension。", errors)

    def test_run_check_should_skip_code_compare_before_catalog_exists(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            matrix_path = root / "matrix.yml"
            matrix_path.write_text(VALID_MATRIX, encoding="utf-8")

            self.assertEqual(script.run_check(matrix_path, root / "missing-catalog"), [])


if __name__ == "__main__":
    unittest.main()
