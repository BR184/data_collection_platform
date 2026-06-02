#!/usr/bin/env python3
"""Validate verification ledger rows have explicit statuses and follow-up actions."""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LEDGER = ROOT / "docs/real-chain-old-platform-comparison-ledger-20260602.md"

ALLOWED_STATUSES = {
    "PASS_SAME_DATA",
    "PASS_REAL",
    "PARTIAL_REAL",
    "PASS_MOCK_ONLY",
    "PASS_UNIT_ONLY",
    "FAILED",
    "BLOCKED_ENV",
    "NOT_RUN",
}
PASS_STATUSES = {"PASS_SAME_DATA", "PASS_REAL"}


@dataclass(frozen=True)
class LedgerRow:
    line_number: int
    cells: dict[str, str]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check verification ledger markdown tables.")
    parser.add_argument(
        "ledger",
        nargs="?",
        default=str(DEFAULT_LEDGER),
        help="Path to the ledger markdown file.",
    )
    return parser.parse_args()


def normalize_cell(value: str) -> str:
    return value.strip().strip("`").strip()


def split_table_line(line: str) -> list[str]:
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def is_separator(cells: list[str]) -> bool:
    return all(cell.replace("-", "").replace(":", "").strip() == "" for cell in cells)


def parse_rows(path: Path) -> list[LedgerRow]:
    lines = path.read_text(encoding="utf-8").splitlines()
    rows: list[LedgerRow] = []
    headers: list[str] | None = None
    for index, line in enumerate(lines, start=1):
        if not line.strip().startswith("|"):
            headers = None
            continue
        cells = split_table_line(line)
        if headers is None:
            headers = cells
            continue
        if is_separator(cells):
            continue
        if len(cells) != len(headers):
            rows.append(LedgerRow(index, {"__error__": f"column count {len(cells)} != {len(headers)}"}))
            continue
        if "ID" not in headers or "状态" not in headers:
            continue
        rows.append(LedgerRow(index, dict(zip(headers, cells))))
    return rows


def validate_row(row: LedgerRow) -> list[str]:
    if "__error__" in row.cells:
        return [row.cells["__error__"]]
    errors: list[str] = []
    status = normalize_cell(row.cells.get("状态", ""))
    evidence = normalize_cell(row.cells.get("证据", ""))
    note = normalize_cell(row.cells.get("阻塞/说明", ""))
    next_action = normalize_cell(row.cells.get("后续动作", ""))
    item_id = normalize_cell(row.cells.get("ID", f"line-{row.line_number}"))

    if status not in ALLOWED_STATUSES:
        errors.append(f"{item_id}: unknown or empty status '{status}'")
    if status in PASS_STATUSES and (not evidence or evidence == "无"):
        errors.append(f"{item_id}: passing real status requires evidence")
    if status not in PASS_STATUSES:
        if not note or note == "无":
            errors.append(f"{item_id}: non-real-pass status requires 阻塞/说明")
        if not next_action or next_action == "无":
            errors.append(f"{item_id}: non-real-pass status requires 后续动作")
    return errors


def main() -> int:
    args = parse_args()
    path = Path(args.ledger)
    if not path.exists():
        print(f"ledger not found: {path}", file=sys.stderr)
        return 2

    rows = parse_rows(path)
    errors: list[str] = []
    for row in rows:
        for error in validate_row(row):
            errors.append(f"{path}:{row.line_number}: {error}")

    if errors:
        print("Verification ledger check failed:")
        for error in errors:
            print(f"  {error}")
        return 1

    print(f"Verification ledger check passed: {len(rows)} tracked rows.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
