#!/usr/bin/env python3
"""导入器的目标标记、引用行比较与替换清理单元测试。"""

from __future__ import annotations

import json
import os
import unittest
from unittest import mock

import gitlab_bounded_sql_import as importer
from gitlab_sample_contract import PackageError


class FakeCursor:
    def __init__(self, responses=None):
        self.responses = list(responses or [])
        self.executed: list[tuple[str, tuple]] = []
        self.rowcount = 1
        self._rows = []

    def execute(self, sql, params=()):
        self.executed.append((sql, tuple(params)))
        self._rows = self.responses.pop(0) if self.responses else []

    def fetchall(self):
        return self._rows

    def fetchone(self):
        return self._rows[0] if self._rows else None


class ImportUnitTest(unittest.TestCase):
    def test_target_token_comes_from_environment_and_has_minimum_length(self) -> None:
        args = mock.Mock(target_token_env="DCP_TEST_TOKEN")
        with mock.patch.dict(os.environ, {"DCP_TEST_TOKEN": "short"}, clear=False):
            with self.assertRaisesRegex(PackageError, "at least 16"):
                importer.target_token(args)
        with mock.patch.dict(os.environ, {"DCP_TEST_TOKEN": "a" * 24}, clear=False):
            self.assertEqual(importer.target_token(args), "a" * 24)
        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(PackageError, "not available"):
                importer.target_token(args)

    def test_token_hash_is_deterministic_and_not_reversible(self) -> None:
        digest = importer.token_hash("example-token-value")
        self.assertEqual(digest, importer.token_hash("example-token-value"))
        self.assertEqual(len(digest), 64)
        self.assertNotIn("example-token-value", digest)

    def test_organization_is_adopted_by_path(self) -> None:
        cursor = FakeCursor()
        maps: dict[str, dict[int, int]] = {}
        source = {"id": 1, "name": "Default", "path": "default"}
        with mock.patch.object(importer, "fetch_target_row_by", return_value={"id": 7}), \
                mock.patch.object(importer, "fetch_target_row", return_value=None):
            row, outcome = importer.resolve_reference_row(cursor, "organizations", source, maps)
        self.assertIsNone(row)
        self.assertEqual(outcome, "adopted")
        self.assertEqual(maps["organizations"][1], 7)

    def test_work_item_type_is_adopted_by_business_key(self) -> None:
        cursor = FakeCursor()
        maps: dict[str, dict[int, int]] = {}
        source = {"id": 2, "namespace_id": None, "name": "Issue", "base_type": 0}
        with mock.patch.object(importer, "fetch_target_row_by", return_value={"id": 1}), \
                mock.patch.object(importer, "fetch_target_row", return_value=None):
            row, outcome = importer.resolve_reference_row(cursor, "work_item_types", source, maps)
        self.assertIsNone(row)
        self.assertEqual(outcome, "adopted")
        self.assertEqual(maps["work_item_types"][2], 1)

    def test_work_item_type_id_collision_allocates_new_id(self) -> None:
        cursor = FakeCursor([[(9,)]])
        maps: dict[str, dict[int, int]] = {}
        source = {"id": 2, "namespace_id": None, "name": "Custom", "base_type": 0}
        with mock.patch.object(importer, "fetch_target_row_by", return_value=None), \
                mock.patch.object(importer, "fetch_target_row", return_value={"id": 2}):
            row, outcome = importer.resolve_reference_row(cursor, "work_item_types", source, maps)
        self.assertIsNotNone(row)
        self.assertEqual(outcome, "inserted")
        self.assertEqual(row["id"], 9)
        self.assertEqual(maps["work_item_types"][2], 9)

    def test_json_adapter_is_used_for_json_columns_only(self) -> None:
        adapted = importer.adapt_value({"a": 1}, "jsonb")
        self.assertEqual(adapted.adapted, {"a": 1})
        self.assertEqual(importer.adapt_value([1, 2], "ARRAY"), [1, 2])
        self.assertIsNone(importer.adapt_value(None, "jsonb"))

    def test_replace_deletes_journaled_rows_in_reverse_dependency_order(self) -> None:
        cursor = FakeCursor(
            [
                [
                    ("notes", "[1]"),
                    ("users", "[2]"),
                    ("organizations", "[1]"),
                ],
                [],
                [],
                [],
                [],
            ]
        )
        deleted = importer.delete_previous_import(cursor, "package-1")
        sql = "\n".join(statement for statement, _ in cursor.executed)
        self.assertIn('delete from "organizations"', sql)
        self.assertLess(sql.index('delete from "notes"'), sql.index('delete from "users"'))
        self.assertLess(sql.index('delete from "users"'), sql.index('delete from "organizations"'))
        self.assertIn(f'delete from "{importer.JOURNAL_TABLE}"', sql)
        self.assertEqual(deleted, 3)

    def test_replace_without_journal_fails(self) -> None:
        cursor = FakeCursor([[]])
        with self.assertRaisesRegex(PackageError, "No import journal"):
            importer.delete_previous_import(cursor, "missing-package")


if __name__ == "__main__":
    unittest.main()
