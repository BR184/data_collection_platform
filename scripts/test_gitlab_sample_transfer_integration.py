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

import contextlib
import hashlib
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import psycopg2
from psycopg2 import sql

ROOT = Path(__file__).resolve().parents[1]
SCRIPT_EXPORT = ROOT / "scripts" / "gitlab_bounded_sql_export.py"
SCRIPT_IMPORT = ROOT / "scripts" / "gitlab_bounded_sql_import.py"
sys.path.insert(0, str(ROOT / "scripts"))
import gitlab_bounded_sql_export as exporter  # noqa: E402
import gitlab_bounded_sql_import as importer  # noqa: E402
from gitlab_sample_contract import TABLE_ORDER  # noqa: E402


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


class ControllableClock:
    def __init__(self, value: float) -> None:
        self.value = value

    def __call__(self) -> float:
        return self.value

    def advance(self, seconds: float) -> None:
        self.value += seconds


class RecordingConnection:
    """记录连接释放顺序的代理；psycopg2 连接本身不接受实例属性。"""

    def __init__(self, connection, events: list[str]) -> None:
        self._connection = connection
        self._events = events

    def __getattr__(self, name: str):
        return getattr(self._connection, name)

    def rollback(self) -> None:
        self._events.append("rollback")
        self._connection.rollback()

    def close(self) -> None:
        self._events.append("close")
        self._connection.close()


class GitlabSampleTransferIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workdir = Path(env("GITLAB_SAMPLE_IT_WORKDIR") if os.getenv("GITLAB_SAMPLE_IT_WORKDIR") else tempfile.mkdtemp(prefix="gitlab-sample-it-"))
        cls.workdir.mkdir(parents=True, exist_ok=True)
        cls.source_env = source_env()
        cls.target_env = target_env()
        cls.source = connect(cls.source_env)
        cls.target = connect(cls.target_env)
        cls.source.autocommit = True
        cls.target.autocommit = True
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

    def admin_connection(self):
        environment = dict(self.target_env)
        environment["PGDATABASE"] = "postgres"
        return connect(environment)

    def test_09_init_rejects_non_gitlab_and_nonempty_target(self) -> None:
        label = env("GITLAB_SAMPLE_IT_TARGET_LABEL")
        token = env("GITLAB_SAMPLE_IT_TARGET_TOKEN")
        dbname = f"gl_sample_nongitlab_{os.getpid()}"
        admin = self.admin_connection()
        admin.autocommit = True
        with admin.cursor() as cursor:
            cursor.execute(sql.SQL("drop database if exists {}").format(sql.Identifier(dbname)))
            cursor.execute(sql.SQL("create database {}").format(sql.Identifier(dbname)))
        admin.close()
        non_gitlab_env = dict(self.target_env)
        non_gitlab_env["PGDATABASE"] = dbname
        non_gitlab_env["DCP_TARGET_TOKEN"] = token
        connection = connect(non_gitlab_env)
        connection.autocommit = True
        with connection.cursor() as cursor:
            cursor.execute("create table business_rows(id integer primary key)")
            cursor.execute("insert into business_rows values (1)")
        connection.close()
        try:
            failed = run(
                [
                    sys.executable,
                    str(SCRIPT_IMPORT),
                    "--init-target",
                    "--target-label",
                    label,
                    "--report",
                    str(self.workdir / "nongitlab-init.json"),
                ],
                non_gitlab_env,
                expected=2,
            )
            self.assertIn("Required GitLab tables", failed.stderr)
            connection = connect(non_gitlab_env)
            with connection.cursor() as cursor:
                cursor.execute(
                    "select to_regclass('public.dcp_sample_import_target') is null, "
                    "to_regclass('public.dcp_sample_import_journal') is null"
                )
                self.assertEqual(cursor.fetchone(), (True, True))
                self.assertEqual(scalar(connection, "select count(*) from business_rows"), 1)
            connection.close()
        finally:
            admin = self.admin_connection()
            admin.autocommit = True
            with admin.cursor() as cursor:
                cursor.execute(sql.SQL("drop database if exists {}").format(sql.Identifier(dbname)))
            admin.close()

        nonempty_env = dict(self.source_env)
        nonempty_env["DCP_TARGET_TOKEN"] = token
        failed = run(
            [
                sys.executable,
                str(SCRIPT_IMPORT),
                "--init-target",
                "--target-label",
                label,
                "--report",
                str(self.workdir / "nonempty-init.json"),
            ],
            nonempty_env,
            expected=2,
        )
        self.assertIn("not an empty test GitLab", failed.stderr)
        with self.source.cursor() as cursor:
            cursor.execute(
                "select to_regclass('public.dcp_sample_import_target') is null, "
                "to_regclass('public.dcp_sample_import_journal') is null"
            )
            self.assertEqual(cursor.fetchone(), (True, True))

    def test_10_export_row_and_byte_budgets_fail_without_package(self) -> None:
        cases = (
            ("--max-total-rows", "1", "Total row budget"),
            ("--max-total-bytes", "100", "Total byte budget"),
        )
        for option, value, message in cases:
            output = self.workdir / ("budget-" + option.removeprefix("--"))
            failed = run(
                [
                    sys.executable,
                    str(SCRIPT_EXPORT),
                    "--project-ids",
                    "325",
                    "--issue-ids",
                    "41803",
                    "--batch-size",
                    "2",
                    "--batch-sleep-ms",
                    "0",
                    option,
                    value,
                    "--output",
                    str(output),
                ],
                self.source_env,
                expected=2,
            )
            self.assertIn(message, failed.stderr)
            self.assertFalse(output.exists())

    def test_11_sequence_restart_rolls_back_with_old_rows(self) -> None:
        connection = connect(self.target_env)
        connection.autocommit = False
        try:
            with connection.cursor() as cursor:
                cursor.execute("drop table if exists seq_probe")
                cursor.execute("create table seq_probe(id bigserial primary key)")
                cursor.execute(
                    "insert into seq_probe(id) "
                    "select nextval('seq_probe_id_seq') from generate_series(1, 1000)"
                )
                connection.commit()
                self.assertEqual(scalar(connection, "select max(id) from seq_probe"), 1000)
                importer.reset_sequence_for_table(cursor, "seq_probe", "id")
                try:
                    cursor.execute("select 1 / 0")
                except psycopg2.Error:
                    connection.rollback()
                self.assertEqual(scalar(connection, "select max(id) from seq_probe"), 1000)
                self.assertGreater(scalar(connection, "select nextval('seq_probe_id_seq')"), 1000)
        finally:
            connection.rollback()
            with connection.cursor() as cursor:
                cursor.execute("drop table if exists seq_probe")
            connection.commit()
            connection.close()

    def test_12_commit_failure_restores_rows_marker_and_sequence(self) -> None:
        label = env("GITLAB_SAMPLE_IT_TARGET_LABEL")
        two_issue_package = self.workdir / "package-41803-41809"
        run(
            [
                sys.executable,
                str(SCRIPT_EXPORT),
                "--project-ids",
                "325",
                "--issue-ids",
                "41803,41809",
                "--batch-size",
                "2",
                "--batch-sleep-ms",
                "0",
                "--output",
                str(two_issue_package),
            ],
            self.source_env,
        )
        run(
            [
                sys.executable,
                str(SCRIPT_IMPORT),
                "--package",
                str(two_issue_package),
                "--target-label",
                label,
                "--replace-existing",
                "--report",
                str(self.workdir / "two-issue-import.json"),
            ],
            self.target_env,
        )
        two_manifest = json.loads((two_issue_package / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(scalar(self.target, "select count(*) from issues where id in (41803, 41809)"), 2)

        connection = connect(self.target_env)
        connection.autocommit = False
        with connection.cursor() as cursor:
            cursor.execute("drop trigger if exists probe_fail_commit on notes")
            cursor.execute("drop function if exists probe_fail_commit()")
            cursor.execute(
                "create function probe_fail_commit() returns trigger language plpgsql as "
                "$$ begin raise exception 'probe commit failure'; end $$"
            )
            cursor.execute(
                "create constraint trigger probe_fail_commit after insert on notes "
                "deferrable initially deferred for each row execute function probe_fail_commit()"
            )
        connection.commit()
        try:
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
                    str(self.workdir / "commit-failure.json"),
                ],
                self.target_env,
                expected=2,
            )
            self.assertIn("probe commit failure", failed.stderr)
        finally:
            with connection.cursor() as cursor:
                cursor.execute("drop trigger if exists probe_fail_commit on notes")
                cursor.execute("drop function if exists probe_fail_commit()")
            connection.commit()

        self.assertEqual(scalar(self.target, "select count(*) from issues where id in (41803, 41809)"), 2)
        self.assertEqual(
            scalar(self.target, f"select last_package_id from dcp_sample_import_target"),
            two_manifest["package_id"],
        )
        maximum = scalar(self.target, "select max(id) from issues")
        self.assertGreater(scalar(self.target, "select nextval('issues_id_seq')"), maximum)
        self.assertEqual(scalar(self.target, "select count(*) from notes where noteable_id=41803"), 7)
        connection.close()

    def assert_exporter_connection_released(self) -> None:
        self.assertEqual(
            scalar(
                self.source,
                "select count(*) from pg_stat_activity "
                "where application_name = 'gitlab_bounded_sql_export' and datname = current_database()",
            ),
            0,
        )

    def exporter_arguments(self, output: Path) -> list[str]:
        return [
            "gitlab_bounded_sql_export.py",
            "--project-ids",
            "325",
            "--issue-ids",
            "41803",
            "--batch-size",
            "2",
            "--batch-sleep-ms",
            "0",
            "--output",
            str(output),
        ]

    def test_13_export_timeout_mid_package_write_leaves_no_package(self) -> None:
        output = self.workdir / "deadline-during-write"
        clock = ControllableClock(1000.0)
        events: list[str] = []
        real_connect = exporter.connect
        real_write = exporter.write_package

        def connect(timeout: int, application_name: str):
            return RecordingConnection(real_connect(timeout, application_name), events)

        def write_with_late_deadline(*call_args, **call_kwargs):
            events.append("write")
            original = call_kwargs["deadline_check"]
            calls = {"count": 0}

            def deadline_check() -> None:
                calls["count"] += 1
                if calls["count"] == 3:
                    clock.advance(10**6)
                original()

            call_kwargs["deadline_check"] = deadline_check
            return real_write(*call_args, **call_kwargs)

        stderr = io.StringIO()
        with mock.patch.object(exporter, "clock", clock), mock.patch.object(exporter, "connect", connect), mock.patch.object(
            exporter, "write_package", write_with_late_deadline
        ), mock.patch.dict(os.environ, self.source_env), mock.patch.object(
            sys, "argv", self.exporter_arguments(output)
        ), contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as raised:
                exporter.main()
        self.assertEqual(raised.exception.code, 2)
        self.assertIn("while writing package", stderr.getvalue())
        self.assertFalse(output.exists())
        self.assertLess(events.index("rollback"), events.index("write"))
        self.assertIn("close", events)
        self.assert_exporter_connection_released()

    def test_14_export_timeout_after_last_page_leaves_no_package(self) -> None:
        output = self.workdir / "deadline-after-last-page"
        clock = ControllableClock(1000.0)
        events: list[str] = []
        real_connect = exporter.connect
        real_fetch = exporter.fetch_table_rows

        def connect(timeout: int, application_name: str):
            return RecordingConnection(real_connect(timeout, application_name), events)

        def fetch_then_expire(*call_args, **call_kwargs):
            table = call_args[2]
            rows = real_fetch(*call_args, **call_kwargs)
            if table == TABLE_ORDER[-1]:
                clock.advance(10**6)
            return rows

        stderr = io.StringIO()
        with mock.patch.object(exporter, "clock", clock), mock.patch.object(exporter, "connect", connect), mock.patch.object(
            exporter, "fetch_table_rows", fetch_then_expire
        ), mock.patch.dict(os.environ, self.source_env), mock.patch.object(
            sys, "argv", self.exporter_arguments(output)
        ), contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as raised:
                exporter.main()
        self.assertEqual(raised.exception.code, 2)
        self.assertIn("before package write", stderr.getvalue())
        self.assertFalse(output.exists())
        self.assertNotIn("write", events)
        self.assertIn("rollback", events)
        self.assertIn("close", events)
        self.assert_exporter_connection_released()


if __name__ == "__main__":
    unittest.main(verbosity=2)
