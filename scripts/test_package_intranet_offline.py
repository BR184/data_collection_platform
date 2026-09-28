import argparse
import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
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
            release_id="20260721T120000Z-001122334455",
            deploy_root=Path("deploy"),
            package_name="package",
            package_dir=Path("deploy/package"),
            archive_path=Path("deploy/package.tar.gz"),
            branch="main",
            commit="commit",
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
            postgres_port=15432,
            ldap_base_url="http://172.22.10.116:80",
            include_offline_docker_debs=False,
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

    def test_fresh_environment_scopes_resources_and_declares_distinct_ports(self):
        context = MODULE.BuildContext(
            **{
                **self.build_context().__dict__,
                "frontend_port": 30001,
                "backend_port": 30002,
                "postgres_port": 35432,
            }
        )

        content = MODULE.env_content(context)

        self.assertIn("COMPOSE_PROJECT_NAME=qaflex-20260721t120000z-001122334455", content)
        self.assertIn("POSTGRES_VOLUME_NAME=qaflex-20260721t120000z-001122334455_qaflex_pgdata", content)
        self.assertIn("BACKEND_LOG_VOLUME_NAME=qaflex-20260721t120000z-001122334455_qaflex_backend_logs", content)
        self.assertIn("FRONTEND_PORT=30001", content)
        self.assertIn("BACKEND_PORT=30002", content)
        self.assertIn("POSTGRES_PORT=35432", content)
        self.assertIn("GITLAB_DELETE_RECONCILIATION_ENABLED=false", content)

    def test_fresh_compose_uses_project_scoped_resource_names(self):
        content = MODULE.compose_content(self.build_context())

        self.assertTrue(content.startswith('name: "${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME is required}"\n\nservices:\n'))
        self.assertNotIn("container_name:", content)
        self.assertIn("qaflex_pgdata:/var/lib/postgresql/data", content)
        self.assertIn("GITLAB_DELETE_RECONCILIATION_ENABLED", content)
        self.assertIn('PLATFORM_INSTANCE_ID: ${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME is required}', content)

    def test_fresh_env_resolves_incremental_compose_without_shell_overrides(self):
        docker = shutil.which("docker")
        if docker is None:
            self.skipTest("Docker Compose is required for the generated config integration check")

        fresh_context = self.build_context()
        incremental_context = IntranetPreservingUpgradePackagingTest().build_context()
        with tempfile.TemporaryDirectory() as root:
            env_path = Path(root) / ".env"
            compose_path = Path(root) / "docker-compose.yml"
            env_path.write_text(MODULE.env_content(fresh_context), encoding="utf-8")
            compose_path.write_text(
                MODULE.compose_content(incremental_context, external_postgres_volume=True),
                encoding="utf-8",
            )
            process_env = {
                key: value
                for key, value in os.environ.items()
                if key not in {
                    "COMPOSE_PROJECT_NAME",
                    "POSTGRES_VOLUME_NAME",
                    "BACKEND_LOG_VOLUME_NAME",
                    "COMPOSE_FILE",
                    "COMPOSE_PROFILES",
                }
            }
            for upgrade_number in (1, 2):
                result = subprocess.run(
                    [docker, "compose", "--env-file", str(env_path), "-f", str(compose_path), "config", "--quiet"],
                    cwd=root,
                    env=process_env,
                    capture_output=True,
                    text=True,
                    check=False,
                )
                self.assertEqual(0, result.returncode, f"upgrade {upgrade_number}: {result.stderr}")

    def test_backend_dockerfile_pins_noble_and_installs_postgresql_client_16(self):
        content = MODULE.backend_dockerfile()

        self.assertIn("FROM eclipse-temurin:21-jre-noble", content)
        self.assertNotIn("FROM eclipse-temurin:21-jre\n", content)
        self.assertIn("postgresql-client-16", content)
        self.assertIn("rm -rf /var/lib/apt/lists/*", content)

    def test_fresh_env_generates_random_backup_master_key_and_host_dir(self):
        content = MODULE.env_content(self.build_context())
        second = MODULE.env_content(self.build_context())

        first_keys = [line for line in content.splitlines() if line.startswith("PLATFORM_BACKUP_SECRET_KEY=")]
        second_keys = [line for line in second.splitlines() if line.startswith("PLATFORM_BACKUP_SECRET_KEY=")]
        self.assertEqual(1, len(first_keys))
        self.assertEqual(1, len(second_keys))
        first_value = first_keys[0].split("=", 1)[1]
        second_value = second_keys[0].split("=", 1)[1]
        self.assertNotEqual(first_value, second_value)
        self.assertEqual(32, len(MODULE.base64.b64decode(first_value)))
        self.assertEqual(32, len(MODULE.base64.b64decode(second_value)))
        self.assertIn("PLATFORM_BACKUP_HOST_DIR=/opt/qaflex-backups", content)

    def test_compose_passes_backup_config_and_binds_host_backup_dir(self):
        content = MODULE.compose_content(self.build_context())

        self.assertIn("PLATFORM_BACKUP_ROOT: /var/lib/qaflex/backups", content)
        self.assertIn("PLATFORM_BACKUP_SECRET_KEY: ${PLATFORM_BACKUP_SECRET_KEY:-}", content)
        self.assertIn('"${PLATFORM_BACKUP_HOST_DIR:-/opt/qaflex-backups}:/var/lib/qaflex/backups"', content)

    def test_incremental_compose_keeps_backup_mount_for_external_volume_mode(self):
        content = MODULE.compose_content(self.build_context(), external_postgres_volume=True)

        self.assertIn("PLATFORM_BACKUP_ROOT: /var/lib/qaflex/backups", content)
        self.assertIn('"${PLATFORM_BACKUP_HOST_DIR:-/opt/qaflex-backups}:/var/lib/qaflex/backups"', content)

    def test_fresh_readme_never_instructs_removing_another_stack(self):
        content = MODULE.fresh_readme(self.build_context())

        self.assertIn("只管理当前 `COMPOSE_PROJECT_NAME` 下的资源", content)
        self.assertNotIn("docker rm -f", content)
        self.assertNotIn("docker volume rm", content)

    def test_resolve_context_rejects_invalid_or_overlapping_host_ports(self):
        invalid_arguments = (
            ["--frontend-port", "0"],
            ["--backend-port", "65536"],
            ["--frontend-port", "30001", "--backend-port", "30001"],
            ["--backend-port", "30002", "--postgres-port", "30002"],
        )
        for arguments in invalid_arguments:
            with self.subTest(arguments=arguments), tempfile.TemporaryDirectory() as deploy_root:
                args = MODULE.parse_args(
                    ["--mode", "fresh-empty", "--deploy-root", deploy_root, *arguments]
                )
                with self.assertRaises(MODULE.PackageError):
                    MODULE.resolve_context(args)

    def test_frontend_release_test_finishes_before_production_build(self):
        args = argparse.Namespace(
            skip_build=False,
            skip_frontend_release_tests=False,
            allow_backend_test_source_skip=False,
        )
        command_results = []

        def capture_run(command, **_kwargs):
            command_results.append(tuple(str(part) for part in command))
            return MODULE.CommandResult(tuple(str(part) for part in command), 0, "")

        with mock.patch.object(MODULE, "package_env", return_value={}), mock.patch.object(
            MODULE, "run", side_effect=capture_run
        ), mock.patch.object(MODULE, "verify_backend_migrations_match_source"):
            MODULE.build_products(args)

        self.assertEqual(
            (
                "npm.cmd",
                "run",
                "test",
                "--",
                "feature-manifest-access.test.ts",
                "ux-interaction-regressions.test.ts",
            ),
            command_results[0],
        )
        self.assertEqual(("npm.cmd", "run", "build"), command_results[1])
        self.assertIn("clean", command_results[2])
        self.assertIn("package", command_results[2])

    def test_release_id_is_compact_sortable_and_contains_random_entropy(self):
        with mock.patch.object(MODULE.secrets, "token_hex", return_value="a1b2c3d4e5f6"):
            release_id = MODULE.new_release_id()

        self.assertRegex(release_id, r"^\d{8}T\d{6}Z-a1b2c3d4e5f6$")

    def test_fresh_package_uses_short_unique_release_identity(self):
        with tempfile.TemporaryDirectory() as deploy_root:
            args = MODULE.parse_args(["--mode", "fresh-empty", "--deploy-root", deploy_root])
            with mock.patch.object(
                MODULE, "new_release_id", return_value="20260727T153012Z-012345abcdef"
            ):
                context = MODULE.resolve_context(args)

        self.assertEqual("qaflex-full-20260727T153012Z-012345abcdef", context.package_name)
        self.assertEqual(f"{context.package_name}.tar.gz", context.archive_path.name)
        self.assertEqual(context.release_id, context.backend_tag)
        self.assertEqual(context.release_id, context.frontend_tag)

    def test_fresh_package_rejects_fact_rebuild_and_unused_baseline(self):
        for extra_argument in ("--require-fact-rebuild", "--baseline-dir"):
            arguments = ["--mode", "fresh-empty", extra_argument]
            if extra_argument == "--baseline-dir":
                arguments.append("unused")
            with self.subTest(argument=extra_argument), self.assertRaises(MODULE.PackageError):
                MODULE.resolve_context(MODULE.parse_args(arguments))


class IntranetPreservingUpgradePackagingTest(unittest.TestCase):
    def build_context(self):
        return MODULE.BuildContext(
            mode="incremental-update",
            release_id="20260721T120000Z-001122334455",
            deploy_root=Path("deploy"),
            package_name="package",
            package_dir=Path("deploy/package"),
            archive_path=Path("deploy/package.tar.gz"),
            branch="main",
            commit="commit",
            dirty_state="clean",
            backend_tag="20260721T120000Z-001122334455",
            frontend_tag="20260721T120000Z-001122334455",
            baseline_backend_tag="20260714-466478a4-working",
            baseline_frontend_tag="20260714-466478a4-working",
            baseline_name="qa-flex-platform-intranet-20260714-runnable-empty-18181-18080-working",
            expected_flyway_version="20260721.03",
            template_dir=None,
            require_fact_rebuild=True,
            fact_rebuild_scope="all",
            frontend_port=18181,
            backend_port=18080,
            postgres_port=15432,
            ldap_base_url="http://172.22.10.116:80",
            include_offline_docker_debs=False,
        )

    def test_incremental_mode_requires_explicit_baseline(self):
        with tempfile.TemporaryDirectory() as deploy_root:
            args = argparse.Namespace(
                mode="incremental-update",
                working=False,
                deploy_root=Path(deploy_root),
                baseline_dir=None,
                template_package_dir=None,
                require_fact_rebuild=True,
                fact_rebuild_scope="all",
                frontend_port=18181,
                backend_port=18080,
                postgres_port=15432,
                ldap_base_url="http://172.22.10.116:80",
                include_offline_docker_debs=False,
            )

            with self.assertRaisesRegex(MODULE.PackageError, "--baseline-dir"):
                MODULE.resolve_context(args)

    def test_incremental_mode_rejects_fresh_install_dependencies(self):
        args = MODULE.parse_args(
            [
                "--mode",
                "incremental-update",
                "--baseline-dir",
                "baseline",
                "--include-offline-docker-debs",
            ]
        )

        with self.assertRaisesRegex(MODULE.PackageError, "cannot include offline Docker debs"):
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
                working=False,
                deploy_root=Path(deploy_root),
                baseline_dir=baseline,
                template_package_dir=None,
                require_fact_rebuild=True,
                fact_rebuild_scope="all",
                frontend_port=18181,
                backend_port=18080,
                postgres_port=15432,
                ldap_base_url="http://172.22.10.116:80",
                include_offline_docker_debs=False,
            )

            with mock.patch.object(
                MODULE, "new_release_id", return_value="20260721T120000Z-aabbccddeeff"
            ), mock.patch.object(MODULE, "git_value", side_effect=lambda *parts: {
                    ("rev-parse", "--abbrev-ref", "HEAD"): "main",
                    ("rev-parse", "HEAD"): "full-commit",
                    ("status", "--short"): "",
                }.get(parts, "")
            ):
                context = MODULE.resolve_context(args)

            self.assertEqual("old-backend", context.baseline_backend_tag)
            self.assertEqual("old-frontend", context.baseline_frontend_tag)
            self.assertEqual("20260721T120000Z-aabbccddeeff", context.backend_tag)
            self.assertEqual("20260721T120000Z-aabbccddeeff", context.frontend_tag)
            self.assertEqual("qaflex-update-20260721T120000Z-aabbccddeeff", context.package_name)
            self.assertEqual(f"{context.package_name}.tar.gz", context.archive_path.name)

    def test_package_identity_does_not_encode_working_state_or_fact_rebuild(self):
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
            args = MODULE.parse_args(
                [
                    "--mode",
                    "incremental-update",
                    "--baseline-dir",
                    str(baseline),
                    "--working",
                ]
            )

            with mock.patch.object(
                MODULE, "new_release_id", return_value="20260727T153012Z-012345abcdef"
            ), mock.patch.object(MODULE, "git_value", side_effect=lambda *parts: {
                    ("rev-parse", "--abbrev-ref", "HEAD"): "main",
                    ("rev-parse", "HEAD"): "full-commit",
                    ("status", "--short"): "",
                }.get(parts, "")
            ):
                context = MODULE.resolve_context(args)

            self.assertEqual("20260727T153012Z-012345abcdef", context.backend_tag)
            self.assertEqual("qaflex-update-20260727T153012Z-012345abcdef", context.package_name)
            self.assertNotIn("working", context.package_name)
            self.assertNotIn("fact", context.package_name)
            self.assertIn("working tree", context.dirty_state)

    def test_initialize_layout_rejects_release_identity_collision(self):
        collisions = (
            ("directory", "package_dir", "package directory already exists"),
            ("archive", "archive_path", "archive already exists"),
            ("checksum", "checksum_path", "archive checksum already exists"),
        )
        for label, target, message in collisions:
            with self.subTest(collision=label), tempfile.TemporaryDirectory() as deploy_root:
                root = Path(deploy_root)
                context = MODULE.BuildContext(
                    **{
                        **self.build_context().__dict__,
                        "deploy_root": root,
                        "package_dir": root / "qaflex-update-existing",
                        "archive_path": root / "qaflex-update-existing.tar.gz",
                    }
                )
                checksum_path = context.archive_path.with_suffix(context.archive_path.suffix + ".sha256")
                collision_path = checksum_path if target == "checksum_path" else getattr(context, target)
                if target == "package_dir":
                    collision_path.mkdir()
                else:
                    collision_path.write_text("existing", encoding="utf-8")

                with self.assertRaisesRegex(MODULE.PackageError, message):
                    MODULE.initialize_layout(context)

    def test_incremental_compose_is_complete_and_preserves_external_database_volume(self):
        content = MODULE.compose_content(self.build_context(), external_postgres_volume=True)

        self.assertIn("qa-flex-platform-backend:20260721T120000Z-001122334455", content)
        self.assertIn("qa-flex-platform-frontend:20260721T120000Z-001122334455", content)
        self.assertIn("postgres:", content)
        self.assertIn("image: postgres:16-alpine", content)
        self.assertIn("external: true", content)
        self.assertTrue(content.startswith('name: "${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME is required}"\n\nservices:\n'))
        self.assertIn('name: "${POSTGRES_VOLUME_NAME:?POSTGRES_VOLUME_NAME is required}"', content)
        self.assertIn('name: "${BACKEND_LOG_VOLUME_NAME:?BACKEND_LOG_VOLUME_NAME is required}"', content)
        self.assertIn("container_name: qaflex-postgres", content)
        self.assertIn("container_name: qaflex-backend", content)
        self.assertIn("container_name: qaflex-frontend", content)
        self.assertNotIn(r"\n", content)
        self.assertIn("PLATFORM_AUTH_PROVIDER: ${PLATFORM_AUTH_PROVIDER}", content)
        self.assertIn('PLATFORM_AUTH_CSRF_ENABLED: "true"', content)
        self.assertIn('PLATFORM_INSTANCE_ID: ${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME is required}', content)
        self.assertIn('PLATFORM_BACKGROUND_JOBS_ENABLED: "${PLATFORM_BACKGROUND_JOBS_ENABLED:-true}"', content)
        self.assertNotIn("PLATFORM_ADMIN_PASSWORD", content)

    def test_backup_and_upgrade_are_separate_verified_stages(self):
        backup = MODULE.backup_helper(self.build_context())
        content = MODULE.upgrade_helper(self.build_context())

        self.assertIn("pg_dump -Fc", backup)
        self.assertIn("critical-tables.dump", backup)
        self.assertIn("pg_restore --list", backup)
        self.assertIn("backup-manifest.env", backup)
        self.assertIn("SHA256SUMS.txt", backup)
        self.assertIn("-print0 | sort -z | xargs -0 sha256sum", backup)
        self.assertNotIn("\x00", backup)
        self.assertIn("latest-predeploy-backup.txt", backup)
        self.assertNotIn("--force-recreate", backup)
        self.assertIn('BACKUP_DIR="${2:-}"', content)
        self.assertIn("invalid pre-deployment backup", content)
        self.assertIn("sha256sum --strict -c SHA256SUMS.txt", content)
        self.assertNotIn('source "$BACKUP_DIR/backup-manifest.env"', content)
        self.assertNotIn("pg_dump -Fc", content)
        self.assertIn("sync_runs", content)
        self.assertIn("fact_build_tasks", content)
        self.assertIn('TARGET_COMPOSE="$PACKAGE_DIR/docker-compose.yml"', content)
        self.assertIn('TEMP_COMPOSE="docker-compose.yml.$PACKAGE_NAME.tmp"', content)
        self.assertIn('mv -f "$TEMP_COMPOSE" docker-compose.yml', content)
        self.assertNotIn("docker-compose.override.yml", content)
        self.assertIn("PLATFORM_BACKGROUND_JOBS_ENABLED=false", content)
        self.assertIn("background scheduling disabled", content)
        self.assertIn("recreating backend with normal background scheduling", content)
        self.assertIn("PLATFORM_BACKGROUND_JOBS_ENABLED=true compose up", content)
        self.assertIn("normal background scheduling mode was not restored", content)
        self.assertIn("compose stop backend", content)
        self.assertIn("counts-migration-start.txt", content)
        backend_position = content.index("--force-recreate backend")
        frontend_position = content.index("--force-recreate frontend")
        self.assertLess(backend_position, frontend_position)
        self.assertIn("BACKEND_HEALTH_PORT", content)
        self.assertIn("FRONTEND_HEALTH_PORT", content)
        self.assertNotIn("upsert_env", content)
        self.assertNotIn("ensure_env", content)
        self.assertIn("refusing layered Compose configuration", backup)
        self.assertNotIn("cp -a docker-compose.override.yml", backup)
        self.assertNotIn("docker-compose.override.absent", backup)
        self.assertNotIn("already exists; inspect it before upgrading", content)
        self.assertNotIn("down -v", content)
        self.assertNotIn("volume rm", content)

    def test_incremental_readme_distinguishes_release_baseline_from_live_deployment_directory(self):
        context = self.build_context()

        content = MODULE.incremental_readme(context)

        self.assertIn("cd <现有部署目录>", content)
        self.assertIn("ls -la .env docker-compose.yml", content)
        self.assertIn("docker compose --env-file .env config --images", content)
        self.assertIn("cat upgrade-backups/latest-backup.txt", content)
        self.assertIn("backup.sh", content)
        self.assertIn("latest-predeploy-backup.txt", content)
        self.assertLess(content.index("backup.sh"), content.index("upgrade.sh"))
        self.assertIn("backend baseline image does not match", content)
        self.assertIn('find "$PWD/upgrade-backups"', content)
        self.assertIn("FAILED_BACKUP_DIR", content)
        self.assertIn("counts.diff", content)
        self.assertIn("PLATFORM_BACKGROUND_JOBS_ENABLED=true", content)
        self.assertIn("select version from flyway_schema_history", content)
        self.assertIn("打包基线交付物（用于识别现场升级前镜像，不是现场部署目录）", content)
        self.assertIn("## 6. 事实层重建", content)
        self.assertNotIn(f"cd {context.baseline_name}", content)

    def test_incremental_readme_does_not_claim_application_outputs_are_unchanged(self):
        context = MODULE.BuildContext(
            **{**self.build_context().__dict__, "require_fact_rebuild": False}
        )

        content = MODULE.incremental_readme(context)

        self.assertIn("未声明事实重建不等于所有 API、图表或统计展示数值必须不变", content)
        self.assertNotIn("不改变事实表、统计口径或历史聚合结果", content)

    def test_upgrade_script_does_not_request_fact_rebuild_when_release_does_not_require_it(self):
        context = MODULE.BuildContext(**{**self.build_context().__dict__, "require_fact_rebuild": False})

        content = MODULE.upgrade_helper(context)

        self.assertIn("fact rebuild is not required for this release", content)
        self.assertNotIn("before final statistics acceptance", content)

    def test_rollback_script_restores_application_configuration_without_database_rewrite(self):
        content = MODULE.rollback_helper(self.build_context())

        self.assertIn('TEMP_COMPOSE="$(mktemp "$TARGET_DIR/.docker-compose.rollback.XXXXXX")"', content)
        self.assertIn('mv -f "$TEMP_COMPOSE" "$TARGET_DIR/docker-compose.yml"', content)
        self.assertNotIn("docker-compose.override.yml", content)
        self.assertIn('verify_compose_images "$CANDIDATE_CONFIG" "$EXPECTED_BACKEND" "$EXPECTED_FRONTEND"', content)
        self.assertIn("wait_healthy backend 900", content)
        self.assertIn("wait_healthy frontend 300", content)
        self.assertIn("PostgreSQL container changed after the pre-deployment backup", content)
        self.assertIn("--force-recreate backend", content)
        self.assertIn("--force-recreate frontend", content)
        self.assertLess(content.index("wait_healthy backend 900"), content.index("--force-recreate frontend"))
        self.assertNotIn("pg_restore", content)
        self.assertNotIn("down -v", content)
        self.assertIn("exit 20", content)
        self.assertIn("exit 21", content)
        self.assertIn("ROLLBACK_FAILED_PREVIOUS_VERSION_RECOVERED", content)
        self.assertIn("ROLLBACK_AND_COMPENSATION_FAILED", content)

    def test_rollback_preflights_verified_backup_and_volume_identity_before_replace(self):
        content = MODULE.rollback_helper(self.build_context())

        self.assertIn("verify_backup_checksums", content)
        self.assertIn("backup-manifest.env", content)
        self.assertIn("BACKUP_SCHEMA_VERSION", content)
        self.assertNotIn('source "$BACKUP_DIR/backup-manifest.env"', content)
        self.assertIn("POSTGRES_VOLUME_NAME", content)
        self.assertIn("BACKEND_LOG_VOLUME_NAME", content)
        self.assertIn("compose -f", content)
        self.assertIn("--format json", content)
        self.assertIn("postgres.container-id", content)
        self.assertIn("cmp -s", content)
        self.assertIn("volume inspect", content)
        self.assertIn("verify_container_volume_mount", content)
        self.assertLess(content.index("verify_backup_checksums", content.index("cd \"$TARGET_DIR\"")), content.index('mv -f "$TEMP_COMPOSE" "$TARGET_DIR/docker-compose.yml"'))
        self.assertLess(content.index("--format json"), content.index('mv -f "$TEMP_COMPOSE" "$TARGET_DIR/docker-compose.yml"'))
        self.assertLess(content.index("verify_compose_images \"$CANDIDATE_CONFIG\""), content.index('mv -f "$TEMP_COMPOSE" "$TARGET_DIR/docker-compose.yml"'))
        self.assertLess(content.index("image inspect \"$EXPECTED_BACKEND\""), content.index('mv -f "$TEMP_COMPOSE" "$TARGET_DIR/docker-compose.yml"'))
        self.assertNotIn('source "$BACKUP_DIR/backup-manifest.env"', content)

    @staticmethod
    def _git_bash_path():
        git = shutil.which("git")
        if git is None:
            return None
        candidate = Path(git).resolve().parents[1] / "bin" / "bash.exe"
        return candidate if candidate.is_file() else None

    @staticmethod
    def _msys_path(path: Path) -> str:
        drive, tail = os.path.splitdrive(str(path.resolve()))
        if not drive:
            return str(path.resolve()).replace("\\", "/")
        return f"/{drive[0].lower()}{tail.replace(os.sep, '/')}"

    def _write_rollback_preflight_fixture(self, root: Path, *, manifest_extra: str = ""):
        context = self.build_context()
        deployment = root / "deployment"
        backup = deployment / "upgrade-backups" / "predeploy"
        fake_bin = root / "fake-bin"
        deployment.mkdir(parents=True)
        backup.mkdir(parents=True)
        fake_bin.mkdir()
        env_content = (
            "COMPOSE_PROJECT_NAME=qaflex-test\n"
            "POSTGRES_VOLUME_NAME=qaflex-test_qaflex_pgdata\n"
            "BACKEND_LOG_VOLUME_NAME=qaflex-test_qaflex_backend_logs\n"
            "POSTGRES_PASSWORD=private-test-value\n"
        )
        (deployment / ".env").write_text(env_content, encoding="utf-8", newline="\n")
        (deployment / "docker-compose.yml").write_text("services:\n  backend:\n    image: current\n", encoding="utf-8", newline="\n")
        (backup / ".env").write_text(env_content, encoding="utf-8", newline="\n")
        (backup / "docker-compose.yml").write_text("services:\n  backend:\n    image: baseline\n", encoding="utf-8", newline="\n")
        (backup / "postgres.container-id").write_text("abcdef012345\n", encoding="utf-8", newline="\n")
        (backup / "postgres.inspect.json").write_text("{}\n", encoding="utf-8", newline="\n")
        (backup / "compose-images.txt").write_text("images\n", encoding="utf-8", newline="\n")
        (backup / "flyway-before.txt").write_text("20260714.01|true\n", encoding="utf-8", newline="\n")
        (backup / "counts-before.txt").write_text("example_table=1\n", encoding="utf-8", newline="\n")
        (backup / "database.dump").write_bytes(b"database dump")
        (backup / "critical-tables.dump").write_bytes(b"critical table dump")
        (backup / "database.restore-list.txt").write_text("database toc\n", encoding="utf-8", newline="\n")
        (backup / "critical-tables.restore-list.txt").write_text("critical toc\n", encoding="utf-8", newline="\n")
        manifest = (
            "BACKUP_SCHEMA_VERSION=1\n"
            f"PACKAGE_NAME={context.package_name}\n"
            f"BACKUP_EXPECTED_BACKEND={MODULE.BACKEND_IMAGE}:{context.baseline_backend_tag}\n"
            f"BACKUP_EXPECTED_FRONTEND={MODULE.FRONTEND_IMAGE}:{context.baseline_frontend_tag}\n"
            "BACKUP_POSTGRES_ID=abcdef012345\n"
            "CREATED_AT_UTC=20260721T120000Z\n"
            f"{manifest_extra}"
        )
        (backup / "backup-manifest.env").write_text(manifest, encoding="utf-8", newline="\n")
        required = [
            ".env", "docker-compose.yml", "postgres.container-id", "postgres.inspect.json",
            "compose-images.txt", "flyway-before.txt", "counts-before.txt", "database.dump",
            "critical-tables.dump", "database.restore-list.txt", "critical-tables.restore-list.txt",
            "backup-manifest.env",
        ]
        (backup / "SHA256SUMS.txt").write_text(
            "".join(
                f"{hashlib.sha256((backup / name).read_bytes()).hexdigest()}  ./{name}\n"
                for name in required
            ),
            encoding="utf-8",
            newline="\n",
        )
        (backup / "database.dump").write_bytes(b"tampered after checksum creation")
        script = root / "rollback.sh"
        script.write_text(MODULE.rollback_helper(context), encoding="utf-8", newline="\n")
        mutation_log = root / "docker-mutations.log"
        fake_docker = fake_bin / "docker"
        fake_docker.write_text(
            "#!/usr/bin/env bash\n"
            "set -euo pipefail\n"
            "if [[ \"${1:-}\" == info ]]; then exit 0; fi\n"
            "case \"$*\" in\n"
            "  *' compose up '*|*' compose stop '*|*' compose down '*|*' load -i *|*' volume rm '*|*' rm *)\n"
            "    printf '%s\\n' \"$*\" >> \"$FAKE_DOCKER_MUTATIONS\";;\n"
            "esac\n"
            "exit 99\n",
            encoding="utf-8",
            newline="\n",
        )
        fake_docker.chmod(0o755)
        return context, deployment, backup, script, fake_bin, mutation_log

    def _run_rejected_rollback(
        self,
        root: Path,
        deployment: Path,
        backup: Path,
        script: Path,
        fake_bin: Path,
        mutation_log: Path,
        *,
        extra_env: dict[str, str] | None = None,
    ):
        bash = self._git_bash_path()
        env = os.environ.copy()
        env["PATH"] = f"{self._msys_path(fake_bin)}:/usr/bin:/bin:" + env.get("PATH", "")
        env["FAKE_DOCKER_MUTATIONS"] = self._msys_path(mutation_log)
        if extra_env:
            env.update(extra_env)
        return subprocess.run(
            [str(bash), self._msys_path(script), self._msys_path(deployment), self._msys_path(backup)],
            cwd=deployment,
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )

    def test_generated_rollback_rejects_corrupt_backup_without_changing_deployment(self):
        bash = self._git_bash_path()
        if bash is None:
            self.skipTest("Git Bash is required to execute the generated deployment script in isolation")
        probe = subprocess.run([str(bash), "-lc", "python3 --version"], capture_output=True, text=True, check=False)
        if probe.returncode != 0:
            self.skipTest("Git Bash Python runtime is required for structured Compose preflight")

        with tempfile.TemporaryDirectory() as temp_root:
            root = Path(temp_root)
            _, deployment, backup, script, fake_bin, mutation_log = self._write_rollback_preflight_fixture(root)
            before = ((deployment / ".env").read_bytes(), (deployment / "docker-compose.yml").read_bytes())
            result = self._run_rejected_rollback(root, deployment, backup, script, fake_bin, mutation_log)

            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("invalid pre-deployment backup checksums", result.stderr)
            self.assertEqual(before, ((deployment / ".env").read_bytes(), (deployment / "docker-compose.yml").read_bytes()))
            self.assertFalse(mutation_log.exists())

    def test_generated_rollback_rejects_manifest_code_without_executing_it(self):
        bash = self._git_bash_path()
        if bash is None:
            self.skipTest("Git Bash is required to execute the generated deployment script in isolation")
        probe = subprocess.run([str(bash), "-lc", "python3 --version"], capture_output=True, text=True, check=False)
        if probe.returncode != 0:
            self.skipTest("Git Bash Python runtime is required for structured Compose preflight")

        with tempfile.TemporaryDirectory() as temp_root:
            root = Path(temp_root)
            marker = root / "manifest-executed"
            code = f"$(touch {self._msys_path(marker)})"
            _, deployment, backup, script, fake_bin, mutation_log = self._write_rollback_preflight_fixture(
                root, manifest_extra=f"UNTRUSTED={code}\n"
            )
            checksums = backup / "SHA256SUMS.txt"
            lines = []
            for line in checksums.read_text(encoding="utf-8").splitlines():
                name = line.split("  ./", 1)[1]
                lines.append(f"{hashlib.sha256((backup / name).read_bytes()).hexdigest()}  ./{name}\n")
            checksums.write_text("".join(lines), encoding="utf-8", newline="\n")
            before = ((deployment / ".env").read_bytes(), (deployment / "docker-compose.yml").read_bytes())
            result = self._run_rejected_rollback(root, deployment, backup, script, fake_bin, mutation_log)

            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("invalid backup manifest field", result.stderr)
            self.assertFalse(marker.exists())
            self.assertEqual(before, ((deployment / ".env").read_bytes(), (deployment / "docker-compose.yml").read_bytes()))
            self.assertFalse(mutation_log.exists())

    def test_generated_rollback_rejects_environment_drift_before_compose_inspection(self):
        bash = self._git_bash_path()
        if bash is None:
            self.skipTest("Git Bash is required to execute the generated deployment script in isolation")
        probe = subprocess.run([str(bash), "-lc", "python3 --version"], capture_output=True, text=True, check=False)
        if probe.returncode != 0:
            self.skipTest("Git Bash Python runtime is required for structured Compose preflight")

        with tempfile.TemporaryDirectory() as temp_root:
            root = Path(temp_root)
            _, deployment, backup, script, fake_bin, mutation_log = self._write_rollback_preflight_fixture(root)
            (backup / ".env").write_text(
                (deployment / ".env").read_text(encoding="utf-8").replace("private-test-value", "changed-value"),
                encoding="utf-8",
                newline="\n",
            )
            checksum_path = backup / "SHA256SUMS.txt"
            names = [line.split("  ./", 1)[1] for line in checksum_path.read_text(encoding="utf-8").splitlines()]
            checksum_path.write_text(
                "".join(
                    f"{hashlib.sha256((backup / name).read_bytes()).hexdigest()}  ./{name}\n"
                    for name in names
                ),
                encoding="utf-8",
                newline="\n",
            )
            before = ((deployment / ".env").read_bytes(), (deployment / "docker-compose.yml").read_bytes())
            result = self._run_rejected_rollback(root, deployment, backup, script, fake_bin, mutation_log)

            self.assertEqual(1, result.returncode, result.stdout + result.stderr)
            self.assertIn("live .env differs from the verified pre-deployment backup", result.stderr)
            self.assertEqual(before, ((deployment / ".env").read_bytes(), (deployment / "docker-compose.yml").read_bytes()))
            self.assertFalse(mutation_log.exists())

    def test_generated_rollback_rejects_structural_mismatch_before_compose_replacement(self):
        bash = self._git_bash_path()
        if bash is None:
            self.skipTest("Git Bash is required to execute the generated deployment script in isolation")
        probe = subprocess.run([str(bash), "-lc", "python3 --version"], capture_output=True, text=True, check=False)
        if probe.returncode != 0:
            self.skipTest("Git Bash Python runtime is required for structured Compose preflight")

        for case in ("project", "backend-image", "volume", "network", "port", "mount"):
            with self.subTest(mismatch=case), tempfile.TemporaryDirectory() as temp_root:
                root = Path(temp_root)
                context, deployment, backup, script, fake_bin, mutation_log = self._write_rollback_preflight_fixture(root)
                checksum_path = backup / "SHA256SUMS.txt"
                checksum_names = [line.split("  ./", 1)[1] for line in checksum_path.read_text(encoding="utf-8").splitlines()]
                checksum_path.write_text(
                    "".join(
                        f"{hashlib.sha256((backup / name).read_bytes()).hexdigest()}  ./{name}\n"
                        for name in checksum_names
                    ),
                    encoding="utf-8",
                    newline="\n",
                )
                backend_baseline = f"{MODULE.BACKEND_IMAGE}:{context.baseline_backend_tag}"
                frontend_baseline = f"{MODULE.FRONTEND_IMAGE}:{context.baseline_frontend_tag}"

                def compose_config(project: str, backend_image: str) -> dict:
                    return {
                        "name": project,
                        "volumes": {
                            "qaflex_pgdata": {"name": "qaflex-test_qaflex_pgdata", "external": True},
                            "qaflex_backend_logs": {"name": "qaflex-test_qaflex_backend_logs"},
                        },
                        "networks": {"default": {"name": "qaflex-test_default"}},
                        "services": {
                            "postgres": {
                                "image": MODULE.POSTGRES_IMAGE,
                                "networks": {"default": None},
                                "ports": [{"host_ip": "127.0.0.1", "published": "15432", "target": 5432, "protocol": "tcp"}],
                                "volumes": [{"type": "volume", "source": "qaflex_pgdata", "target": "/var/lib/postgresql/data"}],
                            },
                            "backend": {
                                "image": backend_image,
                                "networks": {"default": None},
                                "ports": [{"host_ip": "127.0.0.1", "published": "18080", "target": 18080, "protocol": "tcp"}],
                                "volumes": [
                                    {"type": "volume", "source": "qaflex_backend_logs", "target": "/app/logs"},
                                    {"type": "bind", "source": "/opt/qaflex-backups", "target": "/var/lib/qaflex/backups"},
                                ],
                            },
                            "frontend": {
                                "image": frontend_baseline,
                                "networks": {"default": None},
                                "ports": [{"host_ip": "0.0.0.0", "published": "18181", "target": 80, "protocol": "tcp"}],
                                "volumes": [],
                            },
                        },
                    }

                current_config = compose_config("qaflex-test", f"{MODULE.BACKEND_IMAGE}:current")
                candidate_project = "qaflex-other" if case == "project" else "qaflex-test"
                candidate_backend_image = "wrong-backend" if case == "backend-image" else backend_baseline
                candidate_config = compose_config(candidate_project, candidate_backend_image)
                if case == "volume":
                    candidate_config["volumes"]["qaflex_pgdata"]["name"] = "qaflex-test_wrong_pgdata"
                elif case == "network":
                    candidate_config["networks"]["default"]["name"] = "qaflex-test_wrong_network"
                elif case == "port":
                    candidate_config["services"]["backend"]["ports"][0]["published"] = "18081"
                elif case == "mount":
                    candidate_config["services"]["backend"]["volumes"][1]["source"] = "/srv/qaflex-backups"
                current_path = root / "current-compose.json"
                candidate_path = root / "candidate-compose.json"
                current_path.write_text(json.dumps(current_config), encoding="utf-8", newline="\n")
                candidate_path.write_text(json.dumps(candidate_config), encoding="utf-8", newline="\n")
                docker = fake_bin / "docker"
                docker.write_text(
                    "#!/usr/bin/env bash\n"
                    "set -euo pipefail\n"
                    "if [[ \"${1:-}\" == info ]]; then exit 0; fi\n"
                    "if [[ \"${1:-}\" == compose && \"$*\" == *' config --format json'* ]]; then\n"
                    "  if [[ \"$*\" == *'.docker-compose.rollback.'* ]]; then cat \"$FAKE_CANDIDATE_JSON\"; else cat \"$FAKE_CURRENT_JSON\"; fi\n"
                    "  exit 0\n"
                    "fi\n"
                    "if [[ \"${1:-}\" == compose && \"$*\" == *' ps -q postgres'* ]]; then echo abcdef012345; exit 0; fi\n"
                    "if [[ \"${1:-}\" == compose && \"$*\" == *' ps -q backend'* ]]; then echo backend123456; exit 0; fi\n"
                    "if [[ \"${1:-}\" == inspect && \"$*\" == *'State.Health.Status'* ]]; then echo healthy; exit 0; fi\n"
                    "case \"$*\" in *' compose up '*|*' compose stop '*|*' compose down '*|* load -i *|*' volume rm '*|* rm *) printf '%s\\n' \"$*\" >> \"$FAKE_DOCKER_MUTATIONS\";; esac\n"
                    "exit 99\n",
                    encoding="utf-8",
                    newline="\n",
                )
                docker.chmod(0o755)
                before = ((deployment / ".env").read_bytes(), (deployment / "docker-compose.yml").read_bytes())
                result = self._run_rejected_rollback(
                    root,
                    deployment,
                    backup,
                    script,
                    fake_bin,
                    mutation_log,
                    extra_env={
                        "FAKE_CURRENT_JSON": self._msys_path(current_path),
                        "FAKE_CANDIDATE_JSON": self._msys_path(candidate_path),
                    },
                )

                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                expected_message = "Compose application image identity mismatch" if case == "backend-image" else "candidate Compose changes project, volume, network, port, or mount identity"
                self.assertIn(expected_message, result.stderr)
                self.assertEqual(before, ((deployment / ".env").read_bytes(), (deployment / "docker-compose.yml").read_bytes()))
                self.assertFalse(mutation_log.exists())

    def test_baseline_reads_only_authoritative_full_compose(self):
        with tempfile.TemporaryDirectory() as root:
            deployment = Path(root)
            (deployment / "docker-compose.yml").write_text(
                "services:\n  backend:\n    image: qa-flex-platform-backend:current\n",
                encoding="utf-8",
            )
            (deployment / "docker-compose.override.yml").write_text(
                "services:\n  backend:\n    image: qa-flex-platform-backend:obsolete\n",
                encoding="utf-8",
            )

            tag = MODULE.read_deployment_image_tag(deployment, MODULE.BACKEND_IMAGE)

            self.assertEqual("current", tag)

    def test_incremental_layout_contains_only_runtime_and_release_control_files(self):
        context = self.build_context()

        relative = {path.relative_to(context.package_dir).as_posix() for path in MODULE.required_files(context)}

        self.assertEqual(
            {
                "docker-images/qa-flex-platform-backend_20260721T120000Z-001122334455.tar",
                "docker-images/qa-flex-platform-frontend_20260721T120000Z-001122334455.tar",
                "RELEASE-MANIFEST.json",
                "README-INCREMENTAL-DEPLOY.md",
                "docker-compose.yml",
                "backup.sh",
                "upgrade.sh",
                "rollback.sh",
            },
            relative,
        )
        self.assertFalse(any(path.startswith("backend/") or path.startswith("frontend/") for path in relative))

    def test_incremental_manifest_declares_single_compose_and_preserved_environment(self):
        with tempfile.TemporaryDirectory() as root:
            package_dir = Path(root)
            context = MODULE.BuildContext(**{**self.build_context().__dict__, "package_dir": package_dir})
            image_dir = package_dir / "docker-images"
            image_dir.mkdir()
            (image_dir / "qa-flex-platform-backend_20260721T120000Z-001122334455.tar").write_bytes(b"backend")
            (image_dir / "qa-flex-platform-frontend_20260721T120000Z-001122334455.tar").write_bytes(b"frontend")
            jar_path = package_dir / "app.jar"
            jar_path.write_bytes(b"jar")
            dist_dir = package_dir / "dist"
            dist_dir.mkdir()
            (dist_dir / "index.html").write_text("frontend", encoding="utf-8")
            with mock.patch.object(MODULE, "BACKEND_JAR", jar_path), mock.patch.object(
                MODULE, "FRONTEND_DIST", dist_dir
            ):
                MODULE.write_release_manifest(context, False, "test")

            manifest = json.loads((package_dir / "RELEASE-MANIFEST.json").read_text(encoding="utf-8"))

        self.assertEqual(
            {
                "entrypoint": "docker-compose.yml",
                "model": "single-authoritative-file",
                "environmentPreserved": True,
            },
            manifest["compose"],
        )

    def test_incremental_empty_package_scan_allows_backup_script_but_rejects_backup_data(self):
        with tempfile.TemporaryDirectory() as root:
            package_dir = Path(root)
            context = MODULE.BuildContext(**{**self.build_context().__dict__, "package_dir": package_dir})
            (package_dir / "backup.sh").write_text("#!/usr/bin/env bash\n", encoding="utf-8")

            MODULE.scan_empty_package(context)

            (package_dir / "database.backup").write_bytes(b"not allowed")
            with self.assertRaisesRegex(MODULE.PackageError, "database.backup"):
                MODULE.scan_empty_package(context)

    def test_incremental_layout_rejects_build_contexts_site_env_and_database_image(self):
        with tempfile.TemporaryDirectory() as root:
            package_dir = Path(root)
            context = MODULE.BuildContext(**{**self.build_context().__dict__, "package_dir": package_dir})
            forbidden = (
                package_dir / "backend",
                package_dir / ".env",
                package_dir / "docker-images" / "postgres_16-alpine.tar",
            )
            for path in forbidden:
                if path.suffix:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text("forbidden", encoding="utf-8")
                else:
                    path.mkdir(parents=True, exist_ok=True)

            with self.assertRaisesRegex(MODULE.PackageError, "backend, docker-images/postgres_16-alpine.tar, .env"):
                MODULE.validate_forbidden_delivery_items(context)

    def test_fresh_layout_rejects_build_context_and_database_image(self):
        with tempfile.TemporaryDirectory() as root:
            package_dir = Path(root)
            context = MODULE.BuildContext(
                **{**IntranetLdapPackagingTest().build_context().__dict__, "package_dir": package_dir}
            )
            (package_dir / "backend").mkdir()
            image_dir = package_dir / "docker-images"
            image_dir.mkdir()
            (image_dir / "postgres_16-alpine.tar").write_text("forbidden", encoding="utf-8")

            with self.assertRaisesRegex(MODULE.PackageError, "backend, docker-images/postgres_16-alpine.tar"):
                MODULE.validate_forbidden_delivery_items(context)

    def test_fresh_layout_contains_only_application_images_and_release_controls(self):
        context = IntranetLdapPackagingTest().build_context()

        relative = {path.relative_to(context.package_dir).as_posix() for path in MODULE.required_files(context)}

        self.assertEqual(
            {
                "docker-images/qa-flex-platform-backend_test.tar",
                "docker-images/qa-flex-platform-frontend_test.tar",
                "RELEASE-MANIFEST.json",
                ".env",
                "docker-compose.yml",
                "README-INTRANET-DEPLOY.md",
            },
            relative,
        )

    def test_fresh_manifest_lists_only_application_images(self):
        with tempfile.TemporaryDirectory() as root:
            root_path = Path(root)
            package_dir = root_path / "package"
            image_dir = package_dir / "docker-images"
            image_dir.mkdir(parents=True)
            context = MODULE.BuildContext(
                **{
                    **IntranetLdapPackagingTest().build_context().__dict__,
                    "deploy_root": root_path,
                    "package_dir": package_dir,
                    "archive_path": root_path / "package.tar.gz",
                }
            )
            backend_jar = root_path / "app.jar"
            backend_jar.write_bytes(b"backend")
            frontend_dist = root_path / "dist"
            frontend_dist.mkdir()
            (frontend_dist / "index.html").write_text("frontend", encoding="utf-8")
            (image_dir / f"{MODULE.BACKEND_IMAGE}_{context.backend_tag}.tar").write_bytes(b"backend-image")
            (image_dir / f"{MODULE.FRONTEND_IMAGE}_{context.frontend_tag}.tar").write_bytes(b"frontend-image")

            with mock.patch.object(MODULE, "BACKEND_JAR", backend_jar), mock.patch.object(
                MODULE, "FRONTEND_DIST", frontend_dist
            ):
                MODULE.write_release_manifest(context, False, "standard build")

            manifest = json.loads((package_dir / "RELEASE-MANIFEST.json").read_text(encoding="utf-8"))
            self.assertEqual({"backend", "frontend"}, set(manifest["target"]["images"]))
            self.assertIsNone(manifest["baseline"])
            self.assertEqual("fresh-empty", manifest["package"]["type"])

    def test_fresh_readme_defers_postgres_image_to_target_host(self):
        content = MODULE.fresh_readme(self.build_context())

        self.assertNotIn("docker load -i docker-images/postgres_16-alpine.tar", content)
        self.assertIn("docker image inspect postgres:16-alpine", content)
        self.assertIn("不携带", content)
        self.assertNotIn("cp .env.example", content)
        self.assertIn(".env` 已写入当前内网地址", content)

    def test_fresh_layout_only_requires_offline_debs_when_explicitly_enabled(self):
        context = IntranetLdapPackagingTest().build_context()
        context_with_debs = MODULE.BuildContext(**{**context.__dict__, "include_offline_docker_debs": True})

        normal = {path.relative_to(context.package_dir).as_posix() for path in MODULE.required_files(context)}
        with_debs = {
            path.relative_to(context_with_debs.package_dir).as_posix()
            for path in MODULE.required_files(context_with_debs)
        }

        self.assertNotIn("offline-debs/ubuntu-24.04-amd64", normal)
        self.assertIn("offline-debs/ubuntu-24.04-amd64", with_debs)
        self.assertIn(".env", normal)

    def test_release_manifest_replaces_raw_build_artifacts_with_auditable_hashes(self):
        with tempfile.TemporaryDirectory() as root:
            root_path = Path(root)
            package_dir = root_path / "package"
            image_dir = package_dir / "docker-images"
            image_dir.mkdir(parents=True)
            context = MODULE.BuildContext(
                **{
                    **self.build_context().__dict__,
                    "deploy_root": root_path,
                    "package_dir": package_dir,
                    "archive_path": root_path / "package.tar.gz",
                }
            )
            backend_jar = root_path / "app.jar"
            backend_jar.write_bytes(b"backend")
            frontend_dist = root_path / "dist"
            frontend_dist.mkdir()
            (frontend_dist / "index.html").write_text("frontend", encoding="utf-8")
            (image_dir / f"{MODULE.BACKEND_IMAGE}_{context.backend_tag}.tar").write_bytes(b"backend-image")
            (image_dir / f"{MODULE.FRONTEND_IMAGE}_{context.frontend_tag}.tar").write_bytes(b"frontend-image")

            with mock.patch.object(MODULE, "BACKEND_JAR", backend_jar), mock.patch.object(
                MODULE, "FRONTEND_DIST", frontend_dist
            ), mock.patch.object(MODULE, "now_stamp", return_value=("20260727", "2026-07-27 12:00:00 +0800")):
                MODULE.write_release_manifest(context, False, "standard build")

            manifest = json.loads((package_dir / "RELEASE-MANIFEST.json").read_text(encoding="utf-8"))
            self.assertEqual(1, manifest["schemaVersion"])
            self.assertEqual(context.release_id, manifest["package"]["id"])
            self.assertEqual("incremental-update", manifest["package"]["type"])
            self.assertEqual(
                "qa-flex-platform-backend:20260714-466478a4-working",
                manifest["baseline"]["backendImage"],
            )
            self.assertEqual(MODULE.file_sha256(backend_jar), manifest["source"]["backendJarSha256"])
            self.assertFalse((package_dir / "backend").exists())
            self.assertFalse((package_dir / "frontend").exists())

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
