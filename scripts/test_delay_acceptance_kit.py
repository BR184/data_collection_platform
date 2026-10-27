"""验收套件配置生成和元数据预检的确定性回归测试。"""
from __future__ import annotations

import hashlib
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

ROOT = Path(__file__).resolve().parents[1]
KIT = ROOT / "deploy" / "acceptance-kit"
SPEC = importlib.util.spec_from_file_location("kit_preflight", KIT / "source-preflight.py")
assert SPEC is not None and SPEC.loader is not None
PREFLIGHT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREFLIGHT)


class InitEnvironmentTest(unittest.TestCase):
    def test_new_directory_creates_isolated_disabled_configuration(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(KIT / "init-env.py"), "--server", "10.1.2.3"], cwd=directory, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            text = (Path(directory) / ".env").read_text(encoding="utf-8")
            values = dict(line.split("=", 1) for line in text.splitlines())
            self.assertEqual("false", values["BACKGROUND_JOBS_ENABLED"])
            self.assertEqual("false", values["DELETE_RECONCILIATION_ENABLED"])
            self.assertEqual("127.0.0.1", values["WEB_BIND"])
            self.assertEqual("http://10.1.2.3:20280", values["GITLAB_PUBLIC_URL"])
            self.assertTrue(values["COMPOSE_PROJECT_NAME"].startswith("dcp-delay-test-"))
            expected_md5 = hashlib.md5((values["GITLAB_DB_PASSWORD"] + "gitlab").encode()).hexdigest()
            self.assertEqual(expected_md5, values["GITLAB_DB_PASSWORD_MD5"])
            passwords = [values[key] for key in values if key.endswith("PASSWORD")]
            self.assertEqual(len(passwords), len(set(passwords)))
            for password in passwords:
                self.assertNotIn(password.encode(), result.stdout + result.stderr)
            if os.name != "nt":
                self.assertEqual(0o600, (Path(directory) / ".env").stat().st_mode & 0o777)
                self.assertEqual(0o600, (Path(directory) / ".secrets/target.pgpass").stat().st_mode & 0o777)

    def test_existing_environment_is_never_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env = Path(directory) / ".env"
            env.write_text("preserve\n", encoding="utf-8")
            result = subprocess.run([sys.executable, str(KIT / "init-env.py"), "--server", "10.1.2.3"], cwd=directory, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("preserve\n", env.read_text(encoding="utf-8"))
            self.assertFalse((Path(directory) / ".secrets").exists())

    def test_existing_secrets_are_never_overwritten(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / ".secrets").mkdir()
            result = subprocess.run([sys.executable, str(KIT / "init-env.py"), "--server", "10.1.2.3"], cwd=directory, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((Path(directory) / ".env").exists())

    def test_invalid_server_is_rejected_before_creating_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, str(KIT / "init-env.py"), "--server", "bad'host"], cwd=directory, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual([], list(Path(directory).iterdir()))


class ComposeWiringTest(unittest.TestCase):
    def test_gitlab_uses_md5_verifier_and_plaintext_rails_password(self) -> None:
        compose = (KIT / "docker-compose.yml").read_text(encoding="utf-8")
        self.assertIn("postgresql['sql_user_password'] = '${GITLAB_DB_PASSWORD_MD5:?}'", compose)
        self.assertIn("gitlab_rails['db_password'] = '${GITLAB_DB_PASSWORD:?}'", compose)
        self.assertNotIn("postgresql['sql_user_password'] = '${GITLAB_DB_PASSWORD:?}'", compose)


class MetadataPreflightTest(unittest.TestCase):
    def test_success_is_read_only_metadata_and_releases_snapshot(self) -> None:
        connection = MagicMock()
        cursor = connection.cursor.return_value
        cursor.fetchone.return_value = ("on",)
        cursor.fetchall.side_effect = [[("issues", "issues_pkey", "CREATE INDEX")], [("issues", True)]]
        with patch.object(PREFLIGHT, "connect", return_value=connection), patch.object(PREFLIGHT, "server_identity", return_value={"database": "test"}), patch.object(PREFLIGHT, "inspect_schema", return_value={"fingerprint": "known"}):
            report = PREFLIGHT.inspect_source()
        self.assertEqual("METADATA_ONLY", report["status"])
        self.assertEqual(0, report["business_rows_read"])
        self.assertFalse(report["sampling_approved"])
        connection.set_session.assert_called_once_with(isolation_level="REPEATABLE READ", readonly=True, autocommit=False)
        connection.rollback.assert_called_once()
        connection.close.assert_called_once()
        connection.commit.assert_not_called()

    def test_invalid_schema_releases_snapshot_without_sampling(self) -> None:
        connection = MagicMock()
        connection.cursor.return_value.fetchone.return_value = ("on",)
        with patch.object(PREFLIGHT, "connect", return_value=connection), patch.object(PREFLIGHT, "server_identity", return_value={}), patch.object(PREFLIGHT, "inspect_schema", side_effect=PREFLIGHT.PackageError("unsupported")):
            with self.assertRaises(PREFLIGHT.PackageError):
                PREFLIGHT.inspect_source()
        connection.rollback.assert_called_once()
        connection.close.assert_called_once()
        connection.commit.assert_not_called()

    def test_insufficient_permissions_fail_and_release_snapshot(self) -> None:
        connection = MagicMock()
        cursor = connection.cursor.return_value
        cursor.fetchone.return_value = ("on",)
        cursor.fetchall.side_effect = [[], [("issues", False)]]
        with patch.object(PREFLIGHT, "connect", return_value=connection), patch.object(PREFLIGHT, "server_identity", return_value={}), patch.object(PREFLIGHT, "inspect_schema", return_value={}):
            with self.assertRaises(PREFLIGHT.PackageError):
                PREFLIGHT.inspect_source()
        connection.rollback.assert_called_once()
        connection.close.assert_called_once()

    def test_non_read_only_transaction_is_rejected(self) -> None:
        connection = MagicMock()
        connection.cursor.return_value.fetchone.return_value = ("off",)
        with patch.object(PREFLIGHT, "connect", return_value=connection):
            with self.assertRaises(PREFLIGHT.PackageError):
                PREFLIGHT.inspect_source()
        connection.rollback.assert_called_once()
        connection.close.assert_called_once()

    def test_connection_is_closed_even_when_rollback_fails(self) -> None:
        connection = MagicMock()
        connection.cursor.return_value.fetchone.return_value = ("off",)
        connection.rollback.side_effect = OSError("disconnected")
        with patch.object(PREFLIGHT, "connect", return_value=connection):
            with self.assertRaises(OSError):
                PREFLIGHT.inspect_source()
        connection.close.assert_called_once()


class FixtureEntrypointTest(unittest.TestCase):
    def test_fixture_cli_help_loads_without_exporting_data(self) -> None:
        result = subprocess.run([sys.executable, str(ROOT / "scripts/extract_golden_baseline_fixtures.py"), "--help"], capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr.decode("utf-8", errors="replace"))


if __name__ == "__main__":
    unittest.main()
