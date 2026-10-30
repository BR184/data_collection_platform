#!/usr/bin/env python3
"""隔离启动入口的纯函数回归：预检失败聚合、外部地址识别与启动参数固定项。"""

from __future__ import annotations

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

import run_isolated_form_acceptance_backend as entry


class PreflightFailuresTest(unittest.TestCase):
    def test_reports_only_failed_checks(self) -> None:
        checks = [
            {"name": "目标库身份", "ok": True, "detail": "qaflex"},
            {"name": "match-mode 关闭", "ok": False, "detail": "actual='true|true'"},
        ]
        self.assertEqual(
            entry.preflight_failures(checks),
            ["match-mode 关闭: actual='true|true'"],
        )

    def test_returns_empty_when_every_check_passes(self) -> None:
        self.assertEqual(
            entry.preflight_failures([{"name": "迁移头", "ok": True, "detail": "20261029.02"}]),
            [],
        )


class AddressScanTest(unittest.TestCase):
    def test_ignores_loopback_and_reports_external(self) -> None:
        text = (
            "jdbc:postgresql://127.0.0.1:15608/qaflex\n"
            "client created with hosts=[172.22.10.72:27017]\n"
            "bound 0.0.0.0:18094\n"
        )
        self.assertEqual(entry.non_loopback_addresses(text), ["172.22.10.72:27017"])

    def test_treats_localhost_and_ipv6_loopback_as_internal(self) -> None:
        self.assertEqual(
            entry.non_loopback_addresses("localhost:18094 [::1]:15608 127.0.0.1:5432"), []
        )


class IsolationPropertiesTest(unittest.TestCase):
    def test_only_publication_worker_is_enabled(self) -> None:
        properties = entry.ISOLATION_PROPERTIES
        self.assertEqual(properties["platform.form-publication.worker-enabled"], "true")
        for key in (
            "platform.gitlab-mirror.scheduler-enabled",
            "platform.gitlab-mirror.delete-reconciliation-enabled",
            "platform.gitlab-mirror.customer-issue-delay-pre-writeback-sync-enabled",
            "platform.gitlab-mirror.customer-issue-delay-writeback-worker-enabled",
            "platform.gitlab-mirror.code-review-metric-enrichment-enabled",
            "platform.review-data.search-index-backfill-enabled",
            "platform.external-api.enabled",
        ):
            self.assertEqual(properties[key], "false", key)

    def test_non_target_ticks_are_deferred_beyond_acceptance_window(self) -> None:
        for key, value in entry.DEFERRED_TICK_PROPERTIES.items():
            # system-hook 窗口属性以秒计，其余以毫秒计，均为一天。
            self.assertEqual(int(value), 86400 if key.endswith("-batch-window-seconds") else 86400000, key)

    def test_command_contains_isolation_and_mongo_exclusions(self) -> None:
        args = entry.parse_args(
            [
                "--db-container",
                "pg",
                "--db-port",
                "15608",
                "--server-port",
                "18094",
                "--jar",
                "app.jar",
                "--log",
                "log.txt",
                "--evidence",
                "evidence.json",
            ]
        )
        command = entry.build_command(args)
        self.assertIn("--platform.form-publication.worker-enabled=true", command)
        self.assertIn("--platform.gitlab-mirror.scheduler-enabled=false", command)
        self.assertTrue(
            any(item.startswith("--spring.autoconfigure.exclude=") and "Mongo" in item for item in command)
        )




class MainFlowTest(unittest.TestCase):
    """主入口编排：资源身份只读检查必须早于任何迁移/SQL 写入。"""

    def setUp(self) -> None:
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.events: list[str] = []

    def argv(self, *extra: str) -> list[str]:
        return [
            "--db-container",
            "dedicated-pg",
            "--db-port",
            "15608",
            "--server-port",
            "18094",
            "--jar",
            str(self.root / "app.jar"),
            "--log",
            str(self.root / "backend.log"),
            "--evidence",
            str(self.root / "evidence.json"),
            "--probe-seconds",
            "0",
            *extra,
        ]

    def patch_flow(self, identity_ok: bool, preflight_ok: bool) -> None:
        def identity(_args):
            self.events.append("identity")
            return [{"name": "资源身份", "ok": identity_ok, "detail": "stub"}]

        def migrate(_args):
            self.events.append("migrate")
            return {"exitCode": 0, "command": [], "logPath": "stub", "logSha256": ""}

        def switches(_args):
            self.events.append("switches")
            return ["applied: stub"]

        def preflight(_args):
            self.events.append("preflight")
            return [{"name": "迁移头", "ok": preflight_ok, "detail": "stub"}]

        class FakeProcess:
            returncode = 0

            def poll(self):
                return None

            def terminate(self):
                pass

            def wait(self, timeout=None):
                return 0

            def kill(self):
                pass

        def start(*_args, **_kwargs):
            self.events.append("start")
            return FakeProcess()

        for name, value in (
            ("collect_resource_identity", identity),
            ("run_migrations", migrate),
            ("apply_safety_switches", switches),
            ("collect_preflight", preflight),
            ("wait_health", lambda *_a, **_k: (True, "UP")),
            ("terminate", lambda *_a, **_k: None),
        ):
            patcher = mock.patch.object(entry, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        patcher = mock.patch.object(entry.subprocess, "Popen", start)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_success_flow_runs_writes_only_after_identity_then_starts_backend(self) -> None:
        self.patch_flow(identity_ok=True, preflight_ok=True)
        self.assertEqual(entry.main(self.argv("--migrate")), 0)
        self.assertEqual(self.events, ["identity", "migrate", "switches", "preflight", "start"])

    def test_identity_rejection_triggers_no_write_and_no_web_start(self) -> None:
        self.patch_flow(identity_ok=False, preflight_ok=True)
        self.assertEqual(entry.main(self.argv("--migrate")), 2)
        self.assertEqual(self.events, ["identity"])

    def test_post_migration_preflight_failure_still_skips_web_start(self) -> None:
        self.patch_flow(identity_ok=True, preflight_ok=False)
        self.assertEqual(entry.main(self.argv("--migrate")), 2)
        self.assertEqual(self.events, ["identity", "migrate", "switches", "preflight"])


if __name__ == "__main__":
    unittest.main()
