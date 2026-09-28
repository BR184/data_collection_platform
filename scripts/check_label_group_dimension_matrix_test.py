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


PERSON_MULTI_FIELD_MATRIX = """
schemaVersion: 1
dimensions:
  - key: person
    name: 人员
    valueKind: STRING_LITERAL
    mvpSupported: true
pages:
  - pageKey: review-data-home
    name: 评审数据管理
    mvpEnabled: true
    dimensions:
      - dimensionKey: person
        fieldKey: reviewOwner
      - dimensionKey: person
        fieldKey: reviewExpert
        matchMode: ANY_INTERSECT
"""


class CheckLabelGroupDimensionMatrixTest(unittest.TestCase):
    def test_should_accept_valid_matrix(self) -> None:
        matrix = script.parse_matrix(VALID_MATRIX)

        self.assertEqual(script.validate_matrix(matrix), [])

    def test_should_accept_same_dimension_on_multiple_fields(self) -> None:
        matrix = script.parse_matrix(PERSON_MULTI_FIELD_MATRIX)

        self.assertEqual(script.validate_matrix(matrix), [])

    def test_should_reject_duplicate_field_under_same_dimension(self) -> None:
        matrix = script.parse_matrix(
            PERSON_MULTI_FIELD_MATRIX.replace("fieldKey: reviewExpert", "fieldKey: reviewOwner")
        )

        self.assertIn(
            "页面 review-data-home 重复声明维度 person 的字段 reviewOwner。",
            script.validate_matrix(matrix),
        )

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

    def test_should_extract_page_field_triples_from_code_catalog(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            catalog_root = Path(tmp)
            (catalog_root / "LabelDimensionCatalogService.java").write_text(
                """
                class LabelDimensionCatalogService {
                  void create() {
                    add(pages, "person", "review-data-home", "评审数据管理", "reviewExpert", "评审专家", true);
                  }
                }
                """,
                encoding="utf-8",
            )

            self.assertEqual(
                script.extract_code_page_field_triples(catalog_root),
                {("review-data-home", "person", "reviewExpert")},
            )

    def test_should_reject_yaml_page_field_not_registered_in_code(self) -> None:
        matrix = script.parse_matrix(VALID_MATRIX)

        # 把 YAML 的 module/moduleName 改成代码里不存在的字段名，双向都应报缺失。
        errors = script.compare_page_field_mappings(
            matrix, {("review-data-home", "module", "wrongField")}
        )

        self.assertIn("代码兼容目录缺少页面 review-data-home 的维度 module 字段 moduleName。", errors)
        self.assertIn("维度矩阵缺少代码兼容目录声明的页面 review-data-home 的维度 module 字段 wrongField。", errors)

    def test_run_check_passes_on_real_matrix_and_catalog(self) -> None:
        # 入口级正向：对仓库真实矩阵与真实代码目录执行完整 run_check，必须无错误。
        self.assertEqual(script.run_check(), [])

    def test_run_check_detects_page_field_typo_on_real_inputs(self) -> None:
        # 入口级负向：仅把真实 YAML 的一个人员字段改名，run_check 必须双向指认缺失，
        # 证明字段三元组检查真的接入门禁，而非只调用比较辅助函数。
        real_matrix = script.MATRIX_PATH.read_text(encoding="utf-8")
        mutated = real_matrix.replace("fieldKey: reviewExpert", "fieldKey: reviewExpert_TYPO")
        self.assertNotEqual(mutated, real_matrix)
        with tempfile.TemporaryDirectory() as tmp:
            matrix_path = Path(tmp) / "matrix.yml"
            matrix_path.write_text(mutated, encoding="utf-8")
            errors = " ".join(script.run_check(matrix_path, script.CATALOG_ROOT))

        self.assertIn("代码兼容目录缺少页面 review-data-home 的维度 person 字段 reviewExpert_TYPO", errors)
        self.assertIn("维度矩阵缺少代码兼容目录声明的页面 review-data-home 的维度 person 字段 reviewExpert", errors)

    def test_should_extract_dimension_keys_from_catalog_put_calls(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            catalog_root = Path(tmp)
            catalog_file = catalog_root / "LabelDimensionCatalogService.java"
            catalog_file.write_text(
                """
                class LabelDimensionCatalogService {
                  void create() {
                    put(dimensions, "module", "模块", "desc", LabelValueKind.STRING_LITERAL);
                    put(dimensions, "closure_status", "客户问题闭环状态", "desc", LabelValueKind.ENUM_KEY);
                  }
                }
                """,
                encoding="utf-8",
            )

            self.assertEqual(
                script.extract_code_dimension_keys(catalog_root),
                {"module", "closure_status"},
            )


if __name__ == "__main__":
    unittest.main()
