import argparse
import importlib.util
import json
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
        self.assertIn("sha256sum -c SHA256SUMS.txt", content)
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

    def test_upgrade_script_does_not_request_fact_rebuild_when_release_does_not_require_it(self):
        context = MODULE.BuildContext(**{**self.build_context().__dict__, "require_fact_rebuild": False})

        content = MODULE.upgrade_helper(context)

        self.assertIn("fact rebuild is not required for this release", content)
        self.assertNotIn("before final statistics acceptance", content)

    def test_rollback_script_restores_application_configuration_without_database_rewrite(self):
        content = MODULE.rollback_helper(self.build_context())

        self.assertIn('TEMP_COMPOSE="docker-compose.yml.rollback.tmp"', content)
        self.assertIn('mv -f "$TEMP_COMPOSE" docker-compose.yml', content)
        self.assertNotIn("docker-compose.override.yml", content)
        self.assertIn("restored backend image does not match", content)
        self.assertIn("wait_healthy backend 900", content)
        self.assertIn("wait_healthy frontend 300", content)
        self.assertIn("postgres container changed unexpectedly", content)
        self.assertIn("--force-recreate backend", content)
        self.assertIn("--force-recreate frontend", content)
        self.assertLess(content.index("wait_healthy backend 900"), content.index("--force-recreate frontend"))
        self.assertNotIn("pg_restore", content)
        self.assertNotIn("down -v", content)

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

            with self.assertRaisesRegex(MODULE.PackageError, "backend, .env, docker-images/postgres_16-alpine.tar"):
                MODULE.validate_forbidden_delivery_items(context)

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
        self.assertNotIn(".env", normal)

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
