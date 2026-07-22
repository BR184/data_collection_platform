import importlib.util
import argparse
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock


SCRIPT_PATH = Path(__file__).with_name("package_intranet_offline.py")
SPEC = importlib.util.spec_from_file_location("package_intranet_offline", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class IntranetLdapPackagingTest(unittest.TestCase):
    def build_context(self):
        return MODULE.BuildContext(
            mode="fresh-empty",
            release_label="test",
            deploy_root=Path("deploy"),
            package_name="package",
            package_dir=Path("deploy/package"),
            archive_path=Path("deploy/package.tar.gz"),
            date_stamp="20260721",
            branch="main",
            commit="commit",
            short_sha="abcdef12",
            dirty_state="clean",
            backend_tag="test",
            frontend_tag="test",
            baseline_backend_tag="",
            baseline_frontend_tag="",
            baseline_name="",
            expected_flyway_version="20260721.03",
            template_dir=None,
            require_fact_rebuild=False,
            fact_rebuild_scope="all",
            frontend_port=18181,
            backend_port=18080,
            ldap_base_url="http://172.22.10.116:80",
        )

    def test_env_declares_ldap_as_the_only_login_provider(self):
        content = MODULE.env_content(self.build_context())

        self.assertIn("PLATFORM_AUTH_PROVIDER=ldap", content)
        self.assertIn("PLATFORM_LDAP_BASE_URL=http://172.22.10.116:80", content)
        self.assertNotIn("PLATFORM_ADMIN_USERNAME", content)
        self.assertNotIn("PLATFORM_ADMIN_PASSWORD", content)

    def test_default_intranet_ldap_url_matches_production_endpoint(self):
        args = MODULE.parse_args(["--mode", "fresh-empty"])

        self.assertEqual("http://172.22.10.116:80", args.ldap_base_url)


class IntranetPreservingUpgradePackagingTest(unittest.TestCase):
    def build_context(self):
        return MODULE.BuildContext(
            mode="incremental-update",
            release_label="ldap-v03-update",
            deploy_root=Path("deploy"),
            package_name="package",
            package_dir=Path("deploy/package"),
            archive_path=Path("deploy/package.tar.gz"),
            date_stamp="20260721",
            branch="main",
            commit="commit",
            short_sha="abcdef12",
            dirty_state="clean",
            backend_tag="20260721-abcdef12",
            frontend_tag="20260721-abcdef12",
            baseline_backend_tag="20260714-466478a4-working",
            baseline_frontend_tag="20260714-466478a4-working",
            baseline_name="qa-flex-platform-intranet-20260714-runnable-empty-18181-18080-working",
            expected_flyway_version="20260721.03",
            template_dir=None,
            require_fact_rebuild=True,
            fact_rebuild_scope="all",
            frontend_port=18181,
            backend_port=18080,
            ldap_base_url="http://172.22.10.116:80",
        )

    def test_incremental_mode_requires_explicit_baseline(self):
        with tempfile.TemporaryDirectory() as deploy_root:
            args = argparse.Namespace(
                mode="incremental-update",
                release_label="test",
                working=False,
                deploy_root=Path(deploy_root),
                baseline_deploy_dir=None,
                template_package_dir=None,
                require_fact_rebuild=True,
                fact_rebuild_scope="all",
                frontend_port=18181,
                backend_port=18080,
                ldap_base_url="http://172.22.10.116:80",
            )

            with self.assertRaisesRegex(MODULE.PackageError, "--baseline-deploy-dir"):
                MODULE.resolve_context(args)

    def test_incremental_mode_uses_new_image_tags_and_records_old_tags(self):
        with tempfile.TemporaryDirectory() as deploy_root:
            baseline = Path(deploy_root) / "baseline"
            baseline.mkdir()
            (baseline / "docker-compose.yml").write_text(
                "services:\n"
                "  backend:\n"
                "    image: qa-flex-platform-backend:old-backend\n"
                "  frontend:\n"
                "    image: qa-flex-platform-frontend:old-frontend\n",
                encoding="utf-8",
            )
            args = argparse.Namespace(
                mode="incremental-update",
                release_label="test",
                working=False,
                deploy_root=Path(deploy_root),
                baseline_deploy_dir=baseline,
                template_package_dir=None,
                require_fact_rebuild=True,
                fact_rebuild_scope="all",
                frontend_port=18181,
                backend_port=18080,
                ldap_base_url="http://172.22.10.116:80",
            )

            with mock.patch.object(MODULE, "now_stamp", return_value=("20260721", "ignored")), mock.patch.object(
                MODULE, "git_value", side_effect=lambda *parts: {
                    ("rev-parse", "--abbrev-ref", "HEAD"): "main",
                    ("rev-parse", "HEAD"): "full-commit",
                    ("rev-parse", "--short=8", "HEAD"): "abcdef12",
                    ("status", "--short"): "",
                }.get(parts, "")
            ):
                context = MODULE.resolve_context(args)

            self.assertEqual("old-backend", context.baseline_backend_tag)
            self.assertEqual("old-frontend", context.baseline_frontend_tag)
            self.assertEqual("20260721-abcdef12", context.backend_tag)
            self.assertEqual("20260721-abcdef12", context.frontend_tag)

    def test_incremental_override_only_replaces_apps_and_enables_ldap_security(self):
        content = MODULE.incremental_override_content(self.build_context())

        self.assertIn("qa-flex-platform-backend:20260721-abcdef12", content)
        self.assertIn("qa-flex-platform-frontend:20260721-abcdef12", content)
        self.assertIn("PLATFORM_AUTH_PROVIDER: ldap", content)
        self.assertIn('PLATFORM_AUTH_CSRF_ENABLED: "true"', content)
        self.assertNotIn("postgres:", content)
        self.assertNotIn("PLATFORM_ADMIN_PASSWORD", content)

    def test_upgrade_script_backs_up_database_blocks_active_jobs_and_updates_backend_first(self):
        content = MODULE.upgrade_helper(self.build_context())

        self.assertIn("pg_dump -Fc", content)
        self.assertIn("sync_runs", content)
        self.assertIn("fact_build_tasks", content)
        self.assertIn("docker-compose.override.yml", content)
        backend_position = content.index("--force-recreate backend")
        frontend_position = content.index("--force-recreate frontend")
        self.assertLess(backend_position, frontend_position)
        self.assertIn("BACKEND_HEALTH_PORT", content)
        self.assertIn("FRONTEND_HEALTH_PORT", content)
        self.assertIn("ensure_env PLATFORM_LDAP_BASE_URL", content)
        self.assertNotIn("down -v", content)
        self.assertNotIn("volume rm", content)

    def test_rollback_script_restores_application_configuration_without_database_rewrite(self):
        content = MODULE.rollback_helper(self.build_context())

        self.assertIn("docker-compose.override.yml", content)
        self.assertIn("--force-recreate backend", content)
        self.assertIn("--force-recreate frontend", content)
        self.assertNotIn("pg_restore", content)
        self.assertNotIn("down -v", content)

    def test_backend_jar_rejects_migrations_deleted_from_source(self):
        with tempfile.TemporaryDirectory() as root:
            root_path = Path(root)
            migration_dir = root_path / "migration"
            migration_dir.mkdir()
            (migration_dir / "V1__current.sql").write_text("select 1;", encoding="utf-8")
            jar_path = root_path / "app.jar"
            with zipfile.ZipFile(jar_path, "w") as jar:
                jar.writestr("BOOT-INF/classes/db/migration/V1__current.sql", "select 1;")
                jar.writestr("BOOT-INF/classes/db/migration/V2__deleted.sql", "select 2;")

            with self.assertRaisesRegex(MODULE.PackageError, "stale migrations"):
                MODULE.verify_backend_migrations_match_source(jar_path, migration_dir)

    def test_backend_jar_accepts_exact_source_migration_set(self):
        with tempfile.TemporaryDirectory() as root:
            root_path = Path(root)
            migration_dir = root_path / "migration"
            migration_dir.mkdir()
            (migration_dir / "V1__current.sql").write_text("select 1;", encoding="utf-8")
            jar_path = root_path / "app.jar"
            with zipfile.ZipFile(jar_path, "w") as jar:
                jar.writestr("BOOT-INF/classes/db/migration/V1__current.sql", "select 1;")

            MODULE.verify_backend_migrations_match_source(jar_path, migration_dir)

    def test_compose_injects_ldap_and_keeps_session_csrf_enabled(self):
        content = MODULE.compose_content(self.build_context())

        self.assertIn("PLATFORM_AUTH_PROVIDER: ${PLATFORM_AUTH_PROVIDER}", content)
        self.assertIn("PLATFORM_LDAP_BASE_URL: ${PLATFORM_LDAP_BASE_URL}", content)
        self.assertIn('PLATFORM_AUTH_CSRF_ENABLED: "true"', content)
        self.assertNotIn("PLATFORM_ADMIN_USERNAME", content)
        self.assertNotIn("PLATFORM_ADMIN_PASSWORD", content)


if __name__ == "__main__":
    unittest.main()
