import argparse
import csv
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable


ISSUE_KEY_FIELDS = ["source_instance", "project_id", "issue_iid"]
INTEGRATION_KEY_FIELDS = ["source_instance", "testing_phase", "issue_iid", "module_name", "function_name"]


@dataclass(frozen=True)
class DiffReport:
    old_only: list[dict[str, str]]
    new_only: list[dict[str, str]]
    changed: list[dict[str, str]]


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        return [dict(row) for row in csv.DictReader(handle)]


def compare_csv_records(
    old_rows: Iterable[dict[str, str]],
    new_rows: Iterable[dict[str, str]],
    key_fields: list[str],
    compare_fields: list[str] | None = None,
) -> DiffReport:
    old_by_key = {_row_key(row, key_fields): row for row in old_rows}
    new_by_key = {_row_key(row, key_fields): row for row in new_rows}
    old_only = [old_by_key[key] for key in sorted(old_by_key.keys() - new_by_key.keys())]
    new_only = [new_by_key[key] for key in sorted(new_by_key.keys() - old_by_key.keys())]
    fields = compare_fields or sorted(_all_fields(old_by_key.values(), new_by_key.values()) - set(key_fields))
    changed: list[dict[str, str]] = []
    for key in sorted(old_by_key.keys() & new_by_key.keys()):
        old_row = old_by_key[key]
        new_row = new_by_key[key]
        for field in fields:
            old_value = _cell(old_row, field)
            new_value = _cell(new_row, field)
            if old_value != new_value:
                changed.append({
                    **{key_field: _cell(old_row, key_field) for key_field in key_fields},
                    "field": field,
                    "old_value": old_value,
                    "new_value": new_value,
                })
    return DiffReport(old_only=old_only, new_only=new_only, changed=changed)


def write_diff_report(report: DiffReport, output_dir: Path, prefix: str) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    _write_csv(output_dir / f"{prefix}_old_only.csv", report.old_only)
    _write_csv(output_dir / f"{prefix}_new_only.csv", report.new_only)
    _write_csv(output_dir / f"{prefix}_changed.csv", report.changed)


def main() -> int:
    parser = argparse.ArgumentParser(description="Compare old/new platform data by stable identity keys.")
    parser.add_argument("--old-issues-csv", type=Path)
    parser.add_argument("--new-issues-csv", type=Path)
    parser.add_argument("--old-integration-csv", type=Path)
    parser.add_argument("--new-integration-csv", type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    if args.old_issues_csv and args.new_issues_csv:
        issue_report = compare_csv_records(
            read_csv(args.old_issues_csv),
            read_csv(args.new_issues_csv),
            ISSUE_KEY_FIELDS,
        )
        write_diff_report(issue_report, args.output_dir, "issues")

    if args.old_integration_csv and args.new_integration_csv:
        integration_report = compare_csv_records(
            read_csv(args.old_integration_csv),
            read_csv(args.new_integration_csv),
            INTEGRATION_KEY_FIELDS,
        )
        write_diff_report(integration_report, args.output_dir, "integration")

    return 0


def _row_key(row: dict[str, str], key_fields: list[str]) -> tuple[str, ...]:
    return tuple(_cell(row, field) for field in key_fields)


def _cell(row: dict[str, str], field: str) -> str:
    return str(row.get(field, "") or "").strip()


def _all_fields(*row_groups: Iterable[dict[str, str]]) -> set[str]:
    fields: set[str] = set()
    for rows in row_groups:
        for row in rows:
            fields.update(row.keys())
    return fields


def _write_csv(path: Path, rows: list[dict[str, str]]) -> None:
    fieldnames = sorted({field for row in rows for field in row.keys()})
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    raise SystemExit(main())
