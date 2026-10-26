#!/usr/bin/env python3
"""数据包契约、脱敏与校验和的正式单元测试。"""

from __future__ import annotations

import json
import tempfile
import unittest
from datetime import datetime
from decimal import Decimal
from pathlib import Path

from gitlab_sample_contract import (
    FORMAT,
    TABLE_ORDER,
    TABLE_SPECS,
    PackageError,
    canonical_json,
    compute_schema_fingerprint,
    decode_value,
    encode_value,
    load_package,
    quote_ident,
    sanitize_user_row,
    sha256_file,
    supported_gitlab_version,
    write_package,
)


def table_columns() -> dict[str, list[str]]:
    return {table: list(TABLE_SPECS[table].key_columns) for table in TABLE_ORDER}


def manifest(selection: dict | None = None) -> dict:
    return {
        "format": FORMAT,
        "tool_version": "test",
        "created_at": "2026-10-26T12:00:00+08:00",
        "source": {
            "identity_fingerprint": "source-identity",
            "server_fingerprint": "source-server",
            "database": "source",
        },
        "schema": {
            "gitlab_version": "16.11.10",
            "schema_migrations_max": "20240702181131",
            "schema_migrations_count": 5000,
            "fingerprint": "schema-fingerprint",
        },
        "selection": selection or {"mode": "explicit_ids", "issue_ids": [1]},
        "checks": {"association_check": "PASS"},
        "table_counts": {},
    }


def build_package(root: Path, rows: dict | None = None, columns: dict | None = None) -> Path:
    package_rows = {table: [] for table in TABLE_ORDER}
    if rows:
        package_rows.update(rows)
    return write_package(root, manifest(), package_rows, columns or table_columns())


def rewrite_manifest(package: Path, mutate) -> None:
    manifest_path = package / "manifest.json"
    value = json.loads(manifest_path.read_text(encoding="utf-8"))
    mutate(value)
    manifest_path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    checksums = (package / "CHECKSUMS.sha256").read_text(encoding="utf-8").splitlines()
    rewritten = [
        f"{sha256_file(manifest_path)}  manifest.json" if line.endswith("  manifest.json") else line
        for line in checksums
    ]
    (package / "CHECKSUMS.sha256").write_text("\n".join(rewritten) + "\n", encoding="utf-8", newline="\n")


class PackageContractTest(unittest.TestCase):
    def test_roundtrip_preserves_rows_and_checksums(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            package = build_package(
                Path(temporary) / "sample",
                {"projects": [{"id": 325}], "issues": [{"id": 41803}]},
            )
            loaded_manifest, rows = load_package(package)
            self.assertEqual(loaded_manifest["format"], FORMAT)
            self.assertEqual(len(loaded_manifest["package_id"]), 64)
            self.assertEqual(rows["projects"], [{"id": 325}])
            self.assertEqual(rows["issues"], [{"id": 41803}])
            for entry in loaded_manifest["tables"]:
                self.assertEqual(sha256_file(package / entry["file"]), entry["sha256"])
            self.assertIn("manifest.json", (package / "CHECKSUMS.sha256").read_text(encoding="utf-8"))

    def test_tampered_data_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            package = build_package(Path(temporary) / "sample", {"issues": [{"id": 1}]})
            with (package / "data" / "issues.jsonl").open("a", encoding="utf-8") as handle:
                handle.write('{"id": 2}\n')
            with self.assertRaisesRegex(PackageError, "Checksum mismatch"):
                load_package(package)

    def test_incomplete_manifest_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            package = build_package(Path(temporary) / "sample")
            manifest_path = package / "manifest.json"
            value = json.loads(manifest_path.read_text(encoding="utf-8"))
            value["status"] = "WRITING"
            manifest_path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
            checksums = (package / "CHECKSUMS.sha256").read_text(encoding="utf-8").splitlines()
            rewritten = []
            for line in checksums:
                digest, separator, relative = line.partition("  ")
                if relative == "manifest.json":
                    rewritten.append(f"{sha256_file(manifest_path)}  manifest.json")
                else:
                    rewritten.append(f"{digest}{separator}{relative}")
            (package / "CHECKSUMS.sha256").write_text("\n".join(rewritten) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(PackageError, "not COMPLETE"):
                load_package(package)

    def test_extra_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            package = build_package(Path(temporary) / "sample")
            (package / "data" / "extra.jsonl").write_text("", encoding="utf-8")
            with self.assertRaisesRegex(PackageError, "exactly the contract files"):
                load_package(package)

    def test_unsupported_schema_version_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            package = build_package(Path(temporary) / "sample")
            manifest_path = package / "manifest.json"
            value = json.loads(manifest_path.read_text(encoding="utf-8"))
            value["schema"]["schema_migrations_max"] = "20990101000000"
            manifest_path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
            checksums = (package / "CHECKSUMS.sha256").read_text(encoding="utf-8").splitlines()
            rewritten = [
                f"{sha256_file(manifest_path)}  manifest.json"
                if line.endswith("  manifest.json")
                else line
                for line in checksums
            ]
            (package / "CHECKSUMS.sha256").write_text("\n".join(rewritten) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(PackageError, "Unsupported GitLab schema"):
                load_package(package)

    def test_sensitive_column_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            columns = table_columns()
            columns["users"] = ["id", "encrypted_password"]
            package = build_package(
                Path(temporary) / "sample",
                {"users": [{"id": 2, "encrypted_password": "secret"}]},
                columns,
            )
            with self.assertRaisesRegex(PackageError, "not allowed by the package contract"):
                load_package(package)

    def test_user_sanitization_removes_identity_and_secrets(self) -> None:
        value = sanitize_user_row(
            {
                "id": 7,
                "username": "real.user",
                "name": "真实姓名",
                "email": "real@example.com",
                "admin": True,
                "auditor": True,
                "external": True,
                "otp_required_for_login": True,
                "require_two_factor_authentication_from_group": True,
                "encrypted_password": "secret",
            }
        )
        self.assertEqual(value["username"], "sample-user-7")
        self.assertEqual(value["email"], "sample-user-7@example.invalid")
        self.assertEqual(value["name"], "Sample User 7")
        self.assertFalse(value["admin"])
        self.assertFalse(value["otp_required_for_login"])
        self.assertFalse(value["require_two_factor_authentication_from_group"])
        self.assertNotIn("encrypted_password", value)
        self.assertNotIn("real@example.com", canonical_json(value))

    def test_value_encoding_roundtrip(self) -> None:
        cases = [
            (None, None),
            (True, True),
            (3, 3),
            ("文本", "文本"),
            (Decimal("12.3400"), "12.3400"),
            (datetime(2026, 10, 26, 12, 30, 1, 123456), "2026-10-26T12:30:01.123456"),
            (b"\x00\x01abc", b"\x00\x01abc"),
            ({"a": [1, Decimal("1.5")]}, {"a": [1, "1.5"]}),
        ]
        for value, expected in cases:
            self.assertEqual(decode_value(json.loads(json.dumps(encode_value(value)))), expected)

    def test_schema_fingerprint_is_order_independent(self) -> None:
        rows = [
            {"table_name": "issues", "column_name": "id", "data_type": "integer", "is_nullable": "NO"},
            {"table_name": "users", "column_name": "id", "data_type": "integer", "is_nullable": "NO"},
        ]
        self.assertEqual(compute_schema_fingerprint(rows), compute_schema_fingerprint(reversed(rows)))
        self.assertNotEqual(
            compute_schema_fingerprint(rows),
            compute_schema_fingerprint([{**rows[0], "data_type": "bigint"}, rows[1]]),
        )
        self.assertEqual(supported_gitlab_version("20240702181131"), "16.11.10")
        self.assertIsNone(supported_gitlab_version("20990101000000"))

    def test_table_order_respects_foreign_key_dependencies(self) -> None:
        positions = {table: index for index, table in enumerate(TABLE_ORDER)}
        self.assertLess(positions["organizations"], positions["namespaces"])
        self.assertLess(positions["users"], positions["user_details"])
        self.assertLess(positions["users"], positions["namespaces"])
        self.assertLess(positions["namespaces"], positions["work_item_types"])
        self.assertLess(positions["namespaces"], positions["member_roles"])
        self.assertLess(positions["namespaces"], positions["projects"])
        self.assertLess(positions["projects"], positions["issues"])
        self.assertLess(positions["issues"], positions["notes"])
        self.assertLess(positions["notes"], positions["system_note_metadata"])
        self.assertLess(positions["labels"], positions["label_links"])
        self.assertLess(positions["member_roles"], positions["members"])
        self.assertLess(positions["projects"], positions["project_authorizations"])

    def test_package_id_is_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            first = build_package(Path(temporary) / "a", {"issues": [{"id": 7}]})
            second = build_package(Path(temporary) / "b", {"issues": [{"id": 7}]})
            first_manifest, _ = load_package(first)
            second_manifest, _ = load_package(second)
            self.assertEqual(first_manifest["package_id"], second_manifest["package_id"])

    def test_selection_tampering_is_rejected_even_with_recomputed_manifest_checksum(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            package = build_package(Path(temporary) / "sample", {"issues": [{"id": 7}]})
            rewrite_manifest(package, lambda value: value["selection"].update({"issue_ids": [8]}))
            with self.assertRaisesRegex(PackageError, "package_id"):
                load_package(package)

    def test_source_identity_tampering_is_rejected_by_package_id(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            package = build_package(Path(temporary) / "sample", {"issues": [{"id": 7}]})
            rewrite_manifest(
                package,
                lambda value: value["source"].update({"identity_fingerprint": "other-identity"}),
            )
            with self.assertRaisesRegex(PackageError, "package_id"):
                load_package(package)

    def test_schema_fingerprint_tampering_is_rejected_by_package_id(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            package = build_package(Path(temporary) / "sample", {"issues": [{"id": 7}]})
            rewrite_manifest(
                package,
                lambda value: value["schema"].update({"fingerprint": "other-schema"}),
            )
            with self.assertRaisesRegex(PackageError, "package_id"):
                load_package(package)

    def test_write_package_deadline_removes_partial_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "sample"
            calls = 0

            def deadline_check() -> None:
                nonlocal calls
                calls += 1
                if calls >= 2:
                    raise PackageError("deadline exceeded")

            with self.assertRaisesRegex(PackageError, "deadline"):
                write_package(
                    output,
                    manifest(),
                    {"issues": [{"id": 7}]},
                    table_columns(),
                    deadline_check=deadline_check,
                )
            self.assertFalse(output.exists())

    def test_write_package_byte_budget_removes_partial_output(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "sample"
            with self.assertRaisesRegex(PackageError, "byte budget"):
                write_package(
                    output,
                    manifest(),
                    {"issues": [{"id": 7}]},
                    table_columns(),
                    byte_limit=1,
                )
            self.assertFalse(output.exists())

    def test_unsafe_identifiers_are_rejected(self) -> None:
        for value in ("users; drop table x", "users--", "1users", "users name"):
            with self.assertRaises(PackageError):
                quote_ident(value)

    def test_manifest_requires_all_contract_tables_in_order(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            package = build_package(Path(temporary) / "sample")
            manifest_path = package / "manifest.json"
            value = json.loads(manifest_path.read_text(encoding="utf-8"))
            value["tables"] = list(reversed(value["tables"]))
            manifest_path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
            checksums = (package / "CHECKSUMS.sha256").read_text(encoding="utf-8").splitlines()
            rewritten = [
                f"{sha256_file(manifest_path)}  manifest.json"
                if line.endswith("  manifest.json")
                else line
                for line in checksums
            ]
            (package / "CHECKSUMS.sha256").write_text("\n".join(rewritten) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(PackageError, "out of contract order"):
                load_package(package)


if __name__ == "__main__":
    unittest.main()
