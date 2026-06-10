from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MATRIX_PATH = ROOT / "docs/plans/2026-06-10-label-group-dimension-matrix.yml"
CATALOG_ROOT = ROOT / "backend/src/main/java/com/data/collection/platform/service/labelgroup"
ALLOWED_VALUE_KINDS = {"STRING_LITERAL", "GITLAB_USER_ID", "ENUM_KEY", "BRANCH_NAME"}
FORBIDDEN_GENERIC_PERSON_KEYS = {"owner", "reviewer", "assignee"}


@dataclass(frozen=True)
class MatrixDimension:
    key: str
    name: str
    value_kind: str
    mvp_supported: bool | None


@dataclass(frozen=True)
class MatrixPageDimension:
    dimension_key: str
    field_key: str


@dataclass(frozen=True)
class MatrixPage:
    page_key: str
    name: str
    mvp_enabled: bool | None
    dimensions: list[MatrixPageDimension]


@dataclass(frozen=True)
class Matrix:
    dimensions: list[MatrixDimension]
    pages: list[MatrixPage]
    raw_text: str


def _bool_value(value: str) -> bool | None:
    normalized = value.strip().lower()
    if normalized == "true":
        return True
    if normalized == "false":
        return False
    return None


def _value_after_colon(line: str) -> str:
    return line.split(":", 1)[1].strip()


def parse_matrix(text: str) -> Matrix:
    dimensions: list[MatrixDimension] = []
    pages: list[MatrixPage] = []
    section: str | None = None
    current_dimension: dict[str, str] | None = None
    current_page: dict[str, object] | None = None
    current_page_dimension: dict[str, str] | None = None

    def flush_dimension() -> None:
        nonlocal current_dimension
        if current_dimension is None:
            return
        dimensions.append(
            MatrixDimension(
                key=current_dimension.get("key", ""),
                name=current_dimension.get("name", ""),
                value_kind=current_dimension.get("valueKind", ""),
                mvp_supported=_bool_value(current_dimension.get("mvpSupported", "")),
            )
        )
        current_dimension = None

    def flush_page_dimension() -> None:
        nonlocal current_page_dimension
        if current_page is None or current_page_dimension is None:
            return
        page_dimensions = current_page.setdefault("dimensions", [])
        assert isinstance(page_dimensions, list)
        page_dimensions.append(
            MatrixPageDimension(
                dimension_key=current_page_dimension.get("dimensionKey", ""),
                field_key=current_page_dimension.get("fieldKey", ""),
            )
        )
        current_page_dimension = None

    def flush_page() -> None:
        nonlocal current_page
        if current_page is None:
            return
        flush_page_dimension()
        page_dimensions = current_page.get("dimensions", [])
        assert isinstance(page_dimensions, list)
        pages.append(
            MatrixPage(
                page_key=str(current_page.get("pageKey", "")),
                name=str(current_page.get("name", "")),
                mvp_enabled=_bool_value(str(current_page.get("mvpEnabled", ""))),
                dimensions=page_dimensions,
            )
        )
        current_page = None

    for raw_line in text.splitlines():
        if not raw_line.strip() or raw_line.lstrip().startswith("#"):
            continue
        if re.match(r"^[A-Za-z][A-Za-z0-9_]*:", raw_line):
            flush_dimension()
            flush_page()
            section = raw_line.split(":", 1)[0]
            continue

        if section == "dimensions":
            if raw_line.startswith("  - key:"):
                flush_dimension()
                current_dimension = {"key": _value_after_colon(raw_line)}
            elif current_dimension is not None and raw_line.startswith("    ") and ":" in raw_line:
                key, value = raw_line.strip().split(":", 1)
                current_dimension[key] = value.strip()
            continue

        if section == "pages":
            if raw_line.startswith("  - pageKey:"):
                flush_page()
                current_page = {"pageKey": _value_after_colon(raw_line), "dimensions": []}
            elif current_page is not None and raw_line.startswith("      - dimensionKey:"):
                flush_page_dimension()
                current_page_dimension = {"dimensionKey": _value_after_colon(raw_line)}
            elif current_page_dimension is not None and raw_line.startswith("        ") and ":" in raw_line:
                key, value = raw_line.strip().split(":", 1)
                current_page_dimension[key] = value.strip()
            elif current_page is not None and raw_line.strip() in {"dimensions:", "dimensions: []"}:
                continue
            elif current_page is not None and raw_line.startswith("    ") and ":" in raw_line:
                key, value = raw_line.strip().split(":", 1)
                current_page[key] = value.strip()

    flush_dimension()
    flush_page()
    return Matrix(dimensions=dimensions, pages=pages, raw_text=text)


def _duplicates(values: list[str]) -> list[str]:
    seen: set[str] = set()
    duplicated: set[str] = set()
    for value in values:
        if value in seen:
            duplicated.add(value)
        seen.add(value)
    return sorted(duplicated)


def validate_matrix(matrix: Matrix) -> list[str]:
    errors: list[str] = []
    dimension_keys = [dimension.key for dimension in matrix.dimensions]
    dimension_key_set = set(dimension_keys)

    if not matrix.dimensions:
        errors.append("维度矩阵缺少 dimensions。")
    if not matrix.pages:
        errors.append("维度矩阵缺少 pages。")

    for key in _duplicates(dimension_keys):
        errors.append(f"标签维度重复：{key}。")

    for dimension in matrix.dimensions:
        if not re.fullmatch(r"[a-z][a-z0-9_]*", dimension.key):
            errors.append(f"标签维度 key 不合法：{dimension.key}。")
        if dimension.key in FORBIDDEN_GENERIC_PERSON_KEYS:
            errors.append(f"标签维度不得使用泛化人员 key：{dimension.key}。")
        if not dimension.name:
            errors.append(f"标签维度 {dimension.key} 缺少中文名称。")
        if "或" in dimension.value_kind:
            errors.append(f"标签维度 {dimension.key} 的 valueKind 不能写成多选表达式。")
        if dimension.value_kind not in ALLOWED_VALUE_KINDS:
            errors.append(f"标签维度 {dimension.key} 的 valueKind 不受支持：{dimension.value_kind}。")
        if dimension.mvp_supported is None:
            errors.append(f"标签维度 {dimension.key} 的 mvpSupported 必须是 true 或 false。")

    for page in matrix.pages:
        if not page.page_key:
            errors.append("页面兼容矩阵存在空 pageKey。")
        if not page.name:
            errors.append(f"页面 {page.page_key} 缺少中文名称。")
        if page.mvp_enabled is None:
            errors.append(f"页面 {page.page_key} 的 mvpEnabled 必须是 true 或 false。")
        page_dimension_keys = [dimension.dimension_key for dimension in page.dimensions]
        for key in _duplicates(page_dimension_keys):
            errors.append(f"页面 {page.page_key} 重复声明维度：{key}。")
        if page.mvp_enabled and not page.dimensions:
            errors.append(f"MVP 页面 {page.page_key} 必须声明至少一个标签维度。")
        for page_dimension in page.dimensions:
            if page_dimension.dimension_key not in dimension_key_set:
                errors.append(
                    f"页面 {page.page_key} 引用了未在 dimensions 声明的维度：{page_dimension.dimension_key}。"
                )
            if not page_dimension.field_key:
                errors.append(f"页面 {page.page_key} 的维度 {page_dimension.dimension_key} 缺少 fieldKey。")

    if "canonicalValues:" in matrix.raw_text:
        closure_section = _extract_dimension_block(matrix.raw_text, "closure_status")
        canonical_values = _extract_nested_list(closure_section, "canonicalValues")
        equivalent_values = _extract_nested_list(closure_section, "equivalentValues")
        if "设计如此" in canonical_values:
            errors.append("closure_status 的规范保存值不能包含“设计如此”，请保存“需求如此”。")
        if "需求如此" not in canonical_values:
            errors.append("closure_status 必须声明规范保存值“需求如此”。")
        if "设计如此" not in equivalent_values:
            errors.append("closure_status 必须声明等价值“设计如此”。")

    return errors


def _extract_dimension_block(text: str, dimension_key: str) -> str:
    pattern = re.compile(rf"^  - key: {re.escape(dimension_key)}\n(?P<body>(?:    .*\n?)*)", re.MULTILINE)
    match = pattern.search(text)
    return match.group(0) if match else ""


def _extract_nested_list(block: str, section_name: str) -> list[str]:
    values: list[str] = []
    in_section = False
    section_indent = 0
    for line in block.splitlines():
        stripped = line.strip()
        if stripped == f"{section_name}:":
            in_section = True
            section_indent = len(line) - len(line.lstrip(" "))
            continue
        if in_section:
            indent = len(line) - len(line.lstrip(" "))
            if stripped and indent <= section_indent and not stripped.startswith("- "):
                break
            if stripped.startswith("- "):
                values.append(stripped[2:].strip())
    return values


def extract_code_dimension_keys(catalog_root: Path = CATALOG_ROOT) -> set[str] | None:
    if not catalog_root.exists():
        return None
    keys: set[str] = set()
    for path in catalog_root.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        keys.update(re.findall(r'\bdimensionKey\s*\(\s*"([a-z][a-z0-9_]*)"', text))
        keys.update(re.findall(r'\bkey\s*\(\s*"([a-z][a-z0-9_]*)"', text))
        keys.update(re.findall(r'\bnew\s+LabelDimension[^(]*\(\s*"([a-z][a-z0-9_]*)"', text))
        keys.update(re.findall(r'\bput\s*\(\s*dimensions\s*,\s*"([a-z][a-z0-9_]*)"', text))
    return keys


def compare_with_code(matrix: Matrix, code_dimension_keys: set[str] | None) -> list[str]:
    if code_dimension_keys is None:
        return []
    yaml_keys = {dimension.key for dimension in matrix.dimensions}
    missing_in_code = sorted(yaml_keys - code_dimension_keys)
    missing_in_yaml = sorted(code_dimension_keys - yaml_keys)
    errors: list[str] = []
    for key in missing_in_code:
        errors.append(f"代码维度目录缺少 YAML 维度：{key}。")
    for key in missing_in_yaml:
        errors.append(f"代码维度目录存在 YAML 未声明维度：{key}。")
    return errors


def run_check(matrix_path: Path = MATRIX_PATH, catalog_root: Path = CATALOG_ROOT) -> list[str]:
    if not matrix_path.exists():
        return [f"维度矩阵文件不存在：{matrix_path}。"]
    matrix = parse_matrix(matrix_path.read_text(encoding="utf-8"))
    errors = validate_matrix(matrix)
    errors.extend(compare_with_code(matrix, extract_code_dimension_keys(catalog_root)))
    return errors


def main() -> int:
    errors = run_check()
    if errors:
        print("标签组维度矩阵校验失败：")
        for error in errors:
            print(f"- {error}")
        return 1
    print("标签组维度矩阵校验通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
