#!/usr/bin/env python3
"""真实 PostgreSQL 的小样本导出/导入集成测试。

默认跳过；在独立源库与空目标库准备好后，通过环境变量启用：

  PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD  源库连接（只读角色）
  GITLAB_SAMPLE_IT_TARGET_HOST/PORT/DB/USER   目标库连接
  GITLAB_SAMPLE_IT_TARGET_LABEL               目标标记名称
  GITLAB_SAMPLE_IT_TARGET_TOKEN               目标标记令牌（>=16 字符）
  GITLAB_SAMPLE_IT_WORKDIR                    可选，测试临时目录

测试会初始化目标标记、导入样本、执行重复/损坏/版本/身份拒绝场景。目标库必须可丢弃。
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import psycopg2

ROOT = Path(__file__).resolve().parents[1]
SCRIPT_EXPORT = ROOT / "scripts" / "gitlab_bounded_sql_export.py"
SCRIPT_IMPORT = ROOT / "scripts" / "gitlab_bounded_sql_import.py"


def env(name: str) -> str:
    value = os.getenv(name, "")
    if not value:
        raise unittest.SkipTest(f"{name} is not set")
    return value


def source_env() -> dict[str, str]:
    result = dict(os.environ)
    result.update(
        {
            "PGHOST": env("GITLAB_SAMPLE_IT_SOURCE_HOST"),
            "PGPORT": env("GITLAB_SAMPLE_IT_SOURCE_PORT"),
            "PGDATABASE": env("GITLAB_SAMPLE_IT_SOURCE_DB"),
            "PGUSER": env("GITLAB_SAMPLE_IT_SOURCE_USER"),
        }
    )
    return result


def target_env() -> dict[str, str]:
    result = dict(os.environ)
    result.update(
        {
            "PGHOST": env("GITLAB_SAMPLE_IT_TARGET_HOST"),
            "PGPORT": env("GITLAB_SAMPLE_IT_TARGET_PORT"),
            "PGDATABASE": env("GITLAB_SAMPLE_IT_TARGET_DB"),
            "PGUSER": env("GITLAB_SAMPLE_IT_TARGET_USER"),
            "DCP_TARGET_TOKEN": env("GITLAB_SAMPLE_IT_TARGET_TOKEN"),
        }
    )
    return result


def connect(environment: dict[str, str]):
    return psycopg2.connect(
        host=environment["PGHOST"],
        port=int(environment["PGPORT"]),
        dbname=environment["PGDATABASE"],
        user=environment["PGUSER"],
        password=environment.get("PGPASSWORD", ""),
        connect_timeout=10,
    )


def scalar(conn, sql: str, params=()):
    with conn.cursor() as cur:
        cur.execute(sql, params)
        return cur.fetchone()[0]


def run(command: list[str], environment: dict[str, str], expected: int = 0) -> subprocess.CompletedProcess:
    result = subprocess.run(command, cwd=ROOT, env=environment, capture_output=True, text=True, encoding="utf-8")
    if result.returncode != expected:
        raise AssertionError(
            f"command failed ({result.returncode}): {' '.join(command)}\nSTDOUT:\n{result.stdout}\nSTDERR:\n{result.stderr}"
        )
    return result


def rewrite_manifest(manifest_path: Path, checksums_path: Path, mutate) -> None:
    value = json.loads(manifest_path.read_text(encoding="utf-8"))
    mutate(value)
    manifest_path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    digest = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
    lines = checksums_path.read_text(encoding="utf-8").splitlines()
    rewritten = [f"{digest}  manifest.json" if line.endswith("  manifest.json") else line for line in lines]
    checksums_path.write_text("\n".join(rewritten) + "\n", encoding="utf-8", newline="\n")


class GitlabSampleTransferIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workdir = Path(env("GITLAB_SAMPLE_IT_WORKDIR") if os.getenv("GITLAB_SAMPLE_IT_WORKDIR") else tempfile.mkdtemp(prefix="gitlab-sample-it-"))
        cls.workdir.mkdir(parents=True, exist_ok=True)
        cls.source_env = source_env()
        cls.target_env = target_env()
        cls.source = connect(cls.source_env)
        cls.target = connect(cls.target_env)
        cls.before = cls.snapshot_source()
        cls.package = cls.workdir / "package-41803"
        run(
            [
                sys.executable,
                str(SCRIPT_EXPORT),
                "--project-ids",
                "325",
                "--issue-ids",
                "41803",
                "--max-issues",
                "10",
                "--batch-size",
                "2",
                "--batch-sleep-ms",
                "0",
                "--output",
                str(cls.package),
            ],
            cls.source_env,
        )

    @classmethod
    def tearDownClass(cls) -> None:
        if hasattr(cls, "source") and not cls.source.closed:
            cls.source.close()
        if hasattr(cls, "target") and not cls.target.closed:
            cls.target.close()

    @classmethod
    def snapshot_source(cls) -> dict:
        tables = ("issues", "notes", "label_links", "resource_label_events", "users", "projects", "labels", "milestones")
        result = {table: scalar(cls.source, f"select count(*) from {table}") for table in tables}
        result["issue_hash"] = scalar(
            cls.source,
            "select md5(coalesce(string_agg(id||':'||coalesce(title,''), ',' order by id), '')) from issues where project_id=325",
        )
        return result

    def test_01_package_contains_all_history_and_associations(self) -> None:
        manifest = json.loads((self.package / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["status"], "COMPLETE")
        self.assertEqual(manifest["selection"]["issue_ids"], [41803])
        self.assertEqual(manifest["selection"]["issue_iids"], {"325": [2251]})
        self.assertEqual(manifest["checks"]["association_check"], "PASS")
        self.assertEqual(manifest["checks"]["note_count"], 7)
        notes = [json.loads(line) for line in (self.package / "data" / "notes.jsonl").read_text(encoding="utf-8").splitlines()]
        source_notes = set(
            scalar(
                self.source,
                "select array_agg(id) from notes where noteable_type='Issue' and noteable_id=41803",
            )
        )
        self.assertEqual({row["id"] for row in notes}, source_notes)
        oldest = min(row["created_at"] for row in notes)
        self.assertLess(oldest, "2026-08-27T00:00:00+08:00")
        self.assertGreater(len(notes), 4)
        users = [json.loads(line) for line in (self.package / "data" / "users.jsonl").read_text(encoding="utf-8").splitlines()]
        for user in users:
            self.assertNotIn("encrypted_password", user)
            self.assertNotIn("@crowncad.com", user["email"])
            self.assertTrue(user["email"].endswith("@example.invalid"))
        labels = [json.loads(line) for line in (self.package / "data" / "labels.jsonl").read_text(encoding="utf-8").splitlines()]
        milestones = [json.loads(line) for line in (self.package / "data" / "milestones.jsonl").read_text(encoding="utf-8").splitlines()]
        members = [json.loads(line) for line in (self.package / "data" / "members.jsonl").read_text(encoding="utf-8").splitlines()]
        self.assertGreaterEqual(len(labels), 1)
        self.assertGreaterEqual(len(milestones), 1)
        self.assertGreaterEqual(len(members), 1)
        serialized = "".join(path.read_text(encoding="utf-8") for path in (self.package / "data").glob("*.jsonl"))
        self.assertNotIn("huayun", serialized.lower())
        self.assertNotIn("encrypted_password", serialized)
        self.assertNotIn("personal_access_tokens", serialized)
        self.assertNotIn("web_hooks", serialized)

    def test_02_source_is_unchanged_and_write_is_rejected(self) -> None:
        self.assertEqual(self.snapshot_source(), self.before)
        self.source.rollback()
        with self.source.cursor() as cur:
            cur.execute("set session characteristics as transaction read only")
            with self.assertRaises(psycopg2.Error):
                cur.execute("update issues set title = title where id = 41803")
        self.source.rollback()

    def test_03_window_boundary_limit_and_empty_selection(self) -> None:
        # 时间窗口上限：148 个议题大于 5，必须明确失败而不是截断。
        failed = run(
            [
                sys.executable,
                str(SCRIPT_EXPORT),
                "--project-ids",
                "325",
                "--since",
                "2026-08-27T00:00:00+08:00",
                "--until",
                "2026-10-26T23:59:59+08:00",
                "--max-issues",
                "5",
                "--output",
                str(self.workdir / "too-many"),
            ],
            self.source_env,
            expected=2,
        )
        self.assertIn("above --max-issues", failed.stderr)
        empty = run(
            [
                sys.executable,
                str(SCRIPT_EXPORT),
                "--project-ids",
                "325",
                "--since",
                "2030-01-01T00:00:00+08:00",
                "--until",
                "2030-01-02T00:00:00+08:00",
                "--max-issues",
                "5",
                "--output",
                str(self.workdir / "empty"),
            ],
            self.source_env,
            expected=2,
        )
        self.assertIn("No root issues", empty.stderr)

    def test_04_target_identity_and_import_checks(self) -> None:
        label = env("GITLAB_SAMPLE_IT_TARGET_LABEL")
        init = run(
            [
                sys.executable,
                str(SCRIPT_IMPORT),
                "--init-target",
                "--target-label",
                label,
                "--report",
                str(self.workdir / "target-init.json"),
            ],
            self.target_env,
        )
        self.assertIn("target marker initialized", init.stdout)
        imported = run(
            [
                sys.executable,
                str(SCRIPT_IMPORT),
                "--package",
                str(self.package),
                "--target-label",
                label,
                "--report",
                str(self.workdir / "import.json"),
            ],
            self.target_env,
        )
        report = json.loads((self.workdir / "import.json").read_text(encoding="utf-8"))
        self.assertEqual(report["status"], "IMPORTED")
        self.assertEqual(scalar(self.target, "select count(*) from issues where id=41803"), 1)
        self.assertEqual(scalar(self.target, "select count(*) from notes where noteable_type='Issue' and noteable_id=41803"), 7)
        self.assertEqual(
            scalar(self.target, "select count(*) from label_links where target_type='Issue' and target_id=41803"),
            scalar(self.source, "select count(*) from label_links where target_type='Issue' and target_id=41803"),
        )
        for table, where in (
            ("labels", "project_id=325"),
            ("milestones", "project_id=325"),
            ("members", "source_type='Project' and source_id=325"),
            ("project_authorizations", "project_id=325"),
        ):
            self.assertEqual(
                scalar(self.target, f"select count(*) from {table} where {where}"),
                scalar(self.source, f"select count(*) from {table} where {where}"),
                table,
            )
        self.assertEqual(scalar(self.target, "select count(*) from user_details"), scalar(self.target, "select count(*) from users"))
        self.assertEqual(scalar(self.target, "select count(*) from project_features where project_id=325 and builds_access_level=0"), 1)
        self.assertEqual(scalar(self.target, "select count(*) from projects where id=325 and shared_runners_enabled=false and mirror=false"), 1)
        self.assertTrue(all(entry["orphans"] == 0 for entry in report["foreign_key_checks"]))
        self.assertTrue(all(entry["violations"] == 0 for entry in report["polymorphic_checks"]))
        # 重复导入必须拒绝。
        duplicate = run(
            [
                sys.executable,
                str(SCRIPT_IMPORT),
                "--package",
                str(self.package),
                "--target-label",
                label,
                "--report",
                str(self.workdir / "duplicate.json"),
            ],
            self.target_env,
            expected=2,
        )
        self.assertIn("not an empty test GitLab", duplicate.stderr)
        imported_again = run(
            [
                sys.executable,
                str(SCRIPT_IMPORT),
                "--package",
                str(self.package),
                "--target-label",
                label,
                "--replace-existing",
                "--report",
                str(self.workdir / "replace.json"),
            ],
            self.target_env,
        )
        self.assertIn("IMPORTED", imported_again.stdout)
        self.assertEqual(scalar(self.target, "select count(*) from notes where noteable_id=41803"), 7)

    def test_05_damaged_and_version_mismatched_packages_are_rejected(self) -> None:
        label = env("GITLAB_SAMPLE_IT_TARGET_LABEL")
        damaged = self.workdir / "damaged"
        shutil.copytree(self.package, damaged)
        with (damaged / "data" / "notes.jsonl").open("a", encoding="utf-8") as handle:
            handle.write("{}\n")
        failed = run(
            [
                sys.executable,
                str(SCRIPT_IMPORT),
                "--package",
                str(damaged),
                "--target-label",
                label,
                "--replace-existing",
                "--report",
                str(self.workdir / "damaged.json"),
            ],
            self.target_env,
            expected=2,
        )
        self.assertIn("Checksum mismatch", failed.stderr)
        mismatched = self.workdir / "mismatched"
        shutil.copytree(self.package, mismatched)
        rewrite_manifest(
            mismatched / "manifest.json",
            mismatched / "CHECKSUMS.sha256",
            lambda value: value["schema"].update({"schema_migrations_max": "20990101000000"}),
        )
        failed = run(
            [
                sys.executable,
                str(SCRIPT_IMPORT),
                "--package",
                str(mismatched),
                "--target-label",
                label,
                "--replace-existing",
                "--report",
                str(self.workdir / "mismatched.json"),
            ],
            self.target_env,
            expected=2,
        )
        self.assertIn("Unsupported GitLab schema", failed.stderr)

    def test_06_wrong_target_token_and_same_database_are_rejected(self) -> None:
        label = env("GITLAB_SAMPLE_IT_TARGET_LABEL")
        wrong_env = dict(self.target_env)
        wrong_env["DCP_TARGET_TOKEN"] = "wrong-token-0123456789"
        failed = run(
            [
                sys.executable,
                str(SCRIPT_IMPORT),
                "--package",
                str(self.package),
                "--target-label",
                label,
                "--replace-existing",
                "--report",
                str(self.workdir / "wrong-token.json"),
            ],
            wrong_env,
            expected=2,
        )
        self.assertIn("Target token does not match", failed.stderr)
        same_env = dict(self.source_env)
        same_env["DCP_TARGET_TOKEN"] = env("GITLAB_SAMPLE_IT_TARGET_TOKEN")
        failed = run(
            [
                sys.executable,
                str(SCRIPT_IMPORT),
                "--package",
                str(self.package),
                "--target-label",
                label,
                "--replace-existing",
                "--report",
                str(self.workdir / "same-db.json"),
            ],
            same_env,
            expected=2,
        )
        self.assertIn("same database", failed.stderr)

    def test_07_statement_timeout_fails_without_partial_package(self) -> None:
        output = self.workdir / "timeout-package"
        failed = run(
            [
                sys.executable,
                str(SCRIPT_EXPORT),
                "--project-ids",
                "325",
                "--issue-ids",
                "41803",
                "--statement-timeout-ms",
                "1",
                "--output",
                str(output),
            ],
            self.source_env,
            expected=2,
        )
        self.assertIn("timeout", failed.stderr.lower())
        self.assertFalse(output.exists())

    def test_08_insufficient_table_privilege_is_reported(self) -> None:
        role = os.getenv("GITLAB_SAMPLE_IT_LIMITED_ROLE", "")
        if not role:
            raise unittest.SkipTest("GITLAB_SAMPLE_IT_LIMITED_ROLE is not set")
        environment = dict(self.source_env)
        environment["PGOPTIONS"] = f"-c role={role}"
        failed = run(
            [
                sys.executable,
                str(SCRIPT_EXPORT),
                "--project-ids",
                "325",
                "--issue-ids",
                "41803",
                "--output",
                str(self.workdir / "limited-package"),
            ],
            environment,
            expected=2,
        )
        self.assertIn("lacks access to required GitLab tables", failed.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
