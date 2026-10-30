#!/usr/bin/env python3
"""隔离启动入口的纯函数回归：预检失败聚合、外部地址识别与启动参数固定项。"""

from __future__ import annotations

import unittest

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


if __name__ == "__main__":
    unittest.main()
