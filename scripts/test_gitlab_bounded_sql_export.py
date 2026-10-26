#!/usr/bin/env python3
"""导出器的选样、只读边界、脱敏与关联校验单元测试。"""

from __future__ import annotations

import argparse
import unittest
from datetime import datetime
from unittest import mock
from zoneinfo import ZoneInfo

import gitlab_bounded_sql_export as exporter
from gitlab_sample_contract import (
    TABLE_ORDER,
    PackageError,
    SelectionError,
)


def args(**overrides):
    values = {
        "timezone": "Asia/Shanghai",
        "since": "",
        "until": "",
        "issue_ids": "",
        "issue_iids": "",
        "max_issues": 200,
        "batch_size": 10,
        "batch_sleep_ms": 0,
        "statement_timeout_ms": 30000,
        "lock_timeout_ms": 5000,
        "max_runtime_seconds": 300,
        "project_ids": "325",
        "source_instance": "cc",
    }
    values.update(overrides)
    return argparse.Namespace(**values)


class FakeCursor:
    def __init__(self, responses=None):
        self.responses = list(responses or [])
        self.executed: list[tuple[str, tuple]] = []
        self._row = None
        self._rows = []

    def execute(self, sql, params=()):
        self.executed.append((sql, tuple(params)))
        self._rows = self.responses.pop(0) if self.responses else []
        self._row = self._rows[0] if self._rows else None

    def fetchone(self):
        return self._row

    def fetchall(self):
        return self._rows


class FakeConnection:
    def __init__(self, cursor):
        self._cursor = cursor
        self.session = None

    def cursor(self):
        return self._cursor

    def set_session(self, **kwargs):
        self.session = kwargs


class ExportUnitTest(unittest.TestCase):
    def test_parse_csv_ints_rejects_non_integer_and_deduplicates(self) -> None:
        self.assertEqual(exporter.parse_csv_ints("325, 9,325"), [325, 9])
        self.assertEqual(exporter.parse_csv_ints(""), [])
        with self.assertRaises(argparse.ArgumentTypeError):
            exporter.parse_csv_ints("325,abc")

    def test_time_window_is_fixed_and_converted_to_utc(self) -> None:
        value = exporter.resolve_window(args(), datetime(2026, 10, 26, 12, 0, tzinfo=ZoneInfo("Asia/Shanghai")))
        self.assertEqual(value["since"], "2026-08-27T12:00:00+08:00")
        self.assertEqual(value["until"], "2026-10-26T12:00:00+08:00")
        self.assertEqual(value["db_since_utc"], datetime(2026, 8, 27, 4, 0))
        self.assertEqual(value["db_until_utc"], datetime(2026, 10, 26, 4, 0))
        self.assertEqual(value["timezone"], "Asia/Shanghai")

    def test_invalid_or_excessive_window_is_rejected(self) -> None:
        with self.assertRaisesRegex(PackageError, "must not be after"):
            exporter.resolve_window(args(since="2026-10-27T00:00:00+08:00", until="2026-10-26T00:00:00+08:00"), datetime.now(ZoneInfo("UTC")))
        with self.assertRaisesRegex(PackageError, "exceed 180 days"):
            exporter.resolve_window(args(since="2026-01-01T00:00:00+08:00", until="2026-10-01T00:00:00+08:00"), datetime.now(ZoneInfo("UTC")))

    def test_read_only_transaction_sets_timeouts_and_readonly(self) -> None:
        cursor = FakeCursor([[("on",)]])
        connection = FakeConnection(cursor)
        result = exporter.read_only_transaction(connection, args())
        self.assertIs(result, cursor)
        self.assertEqual(connection.session["readonly"], True)
        self.assertEqual(connection.session["isolation_level"], "REPEATABLE READ")
        statements = " ".join(sql for sql, _ in cursor.executed)
        self.assertIn("statement_timeout", statements)
        self.assertIn("lock_timeout", statements)
        self.assertIn("idle_in_transaction_session_timeout", statements)

    def test_require_subset_reports_missing_reference(self) -> None:
        with self.assertRaisesRegex(SelectionError, "missing \\[99\\]"):
            exporter.require_subset([{"user_id": 99}], "notes", "user_id", {1, 2}, nullable=False)

    def test_validate_associations_accepts_complete_closure_and_rejects_orphan(self) -> None:
        rows = {table: [] for table in TABLE_ORDER}
        rows["projects"] = [{"id": 325}]
        rows["namespaces"] = [{"id": 4}, {"id": 907}]
        rows["users"] = [{"id": 5}]
        rows["work_item_types"] = [{"id": 2}]
        rows["issues"] = [
            {
                "id": 41803,
                "project_id": 325,
                "namespace_id": 907,
                "author_id": 5,
                "work_item_type_id": 2,
                "milestone_id": None,
            }
        ]
        rows["notes"] = [
            {
                "id": 1,
                "project_id": 325,
                "namespace_id": 907,
                "author_id": 5,
                "updated_by_id": None,
                "resolved_by_id": None,
            }
        ]
        exporter.validate_associations(rows)
        rows["notes"][0]["author_id"] = 99
        with self.assertRaisesRegex(SelectionError, "notes.author_id"):
            exporter.validate_associations(rows)

    def test_fetch_table_rows_paginates_sanitizes_and_uses_parameters(self) -> None:
        cursor = FakeCursor([[(2, "real.user", "真实姓名", "real@example.com", True, True, True)]])
        with mock.patch.object(exporter, "table_columns", return_value=["id", "username", "name", "email", "admin", "auditor", "external"]):
            rows = exporter.fetch_table_rows(cursor, {"users": {2}}, "users", args(), 10**12)
        self.assertEqual(rows[0]["username"], "sample-user-2")
        self.assertEqual(rows[0]["email"], "sample-user-2@example.invalid")
        self.assertFalse(rows[0]["admin"])
        data_sql, data_params = next((sql, params) for sql, params in cursor.executed if 'from "users"' in sql)
        self.assertIn("= any(%s)", data_sql)
        self.assertIn("> %s", data_sql)
        self.assertEqual(data_params[0], [2])

    def test_build_manifest_records_scope_without_credentials(self) -> None:
        values = args(issue_ids="41803", max_issues=10)
        issues = [{"id": 41803, "project_id": 325, "iid": 2251}]
        rows = {table: [] for table in TABLE_ORDER}
        rows["issues"] = issues
        manifest = exporter.build_manifest(
            values,
            {"database": "source", "role": "reader", "server_fingerprint": "sf", "identity_fingerprint": "if"},
            {"gitlab_version": "16.11.10", "schema_migrations_max": "20240702181131", "schema_migrations_count": 5000, "fingerprint": "fp"},
            None,
            issues,
            "explicit_ids",
            rows,
            {"association_check": "PASS"},
        )
        self.assertEqual(manifest["selection"]["issue_ids"], [41803])
        self.assertEqual(manifest["selection"]["issue_iids"], {"325": [2251]})
        self.assertIsNone(manifest["selection"]["time_window"])
        serialized = str(manifest).lower()
        self.assertNotIn("'password':", serialized)
        self.assertNotIn('"password":', serialized)
        self.assertNotIn("token=", serialized)
        self.assertEqual(manifest["source"]["role"], "reader")

    def test_select_root_issues_fails_on_empty_and_over_limit(self) -> None:
        empty_cursor = FakeCursor([[(0,)]])
        with self.assertRaisesRegex(SelectionError, "No root issues"):
            exporter.select_root_issues(empty_cursor, args(), [325], {"db_since_utc": 1, "db_until_utc": 2}, 10**12)
        over_cursor = FakeCursor([[(201,)]])
        with self.assertRaisesRegex(SelectionError, "above --max-issues"):
            exporter.select_root_issues(over_cursor, args(), [325], {"db_since_utc": 1, "db_until_utc": 2}, 10**12)

    def test_select_root_issues_explicit_ids_must_exist(self) -> None:
        cursor = FakeCursor([[]])
        with self.assertRaisesRegex(SelectionError, "Issue ids not found"):
            exporter.select_root_issues(cursor, args(issue_ids="41803"), [325], None, 10**12)


if __name__ == "__main__":
    unittest.main()
