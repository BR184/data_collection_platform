#!/usr/bin/env python3
"""Build QA Flex Platform intranet offline deployment packages.

This script replaces the old PowerShell packager.  It intentionally uses
argument-list subprocess calls instead of shell strings so Maven -D arguments,
Docker paths and Windows quoting behave the same every time.
"""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import datetime as dt
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DEPLOY_ROOT = Path(r"D:\projects\data_collection_platform_deploy")
JAVA_HOME = REPO_ROOT / "tools" / "jdk" / "jdk-21.0.10+7"
MAVEN_HOME = REPO_ROOT / "tools" / "maven" / "apache-maven-3.9.9"
BACKEND_JAR = REPO_ROOT / "backend" / "target" / "qa-flex-platform-backend-0.0.1-SNAPSHOT.jar"
FRONTEND_DIST = REPO_ROOT / "frontend" / "dist"
PACKAGE_PREFIX = "qaflex"
POSTGRES_IMAGE = "postgres:16-alpine"
BACKEND_IMAGE = "qa-flex-platform-backend"
FRONTEND_IMAGE = "qa-flex-platform-frontend"


class PackageError(RuntimeError):
    """Raised when packaging cannot continue safely."""


@dataclass(frozen=True)
class CommandResult:
    args: tuple[str, ...]
    returncode: int
    output: str


@dataclass(frozen=True)
class BuildContext:
    mode: str
    release_id: str
    deploy_root: Path
    package_name: str
    package_dir: Path
    archive_path: Path
    branch: str
    commit: str
    dirty_state: str
    backend_tag: str
    frontend_tag: str
    baseline_backend_tag: str
    baseline_frontend_tag: str
    baseline_name: str
    expected_flyway_version: str
    template_dir: Path | None
    require_fact_rebuild: bool
    fact_rebuild_scope: str
    frontend_port: int
    backend_port: int
    postgres_port: int
    ldap_base_url: str
    include_offline_docker_debs: bool


def log(message: str) -> None:
    print(f"[pack] {message}", flush=True)


def fail(message: str) -> None:
    raise PackageError(message)


def run(
    args: Sequence[str | Path],
    *,
    cwd: Path = REPO_ROOT,
    env: dict[str, str] | None = None,
    capture: bool = False,
    check: bool = True,
) -> CommandResult:
    command = tuple(str(arg) for arg in args)
    log("run: " + " ".join(command))
    completed = subprocess.run(
        command,
        cwd=str(cwd),
        env=env,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    )
    output = completed.stdout or ""
    if capture and output:
        print(output, end="" if output.endswith("\n") else "\n")
    if check and completed.returncode != 0:
        raise PackageError(f"command failed ({completed.returncode}): {' '.join(command)}")
    return CommandResult(command, completed.returncode, output)


def git_value(*args: str) -> str:
    try:
        result = subprocess.run(
            ("git", "-C", str(REPO_ROOT), *args),
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            check=False,
        )
    except OSError:
        return ""
    if result.returncode != 0:
        return ""
    return result.stdout.strip()


def now_stamp() -> tuple[str, str]:
    now = dt.datetime.now().astimezone()
    return now.strftime("%Y%m%d"), now.strftime("%Y-%m-%d %H:%M:%S %z")


def new_release_id() -> str:
    """Return a compact, sortable identity for one packaging execution."""
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return f"{timestamp}-{secrets.token_hex(6)}"


def require_path(path: Path, label: str) -> None:
    if not path.exists():
        fail(f"{label} not found: {path}")


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content.replace("\r\n", "\n"), encoding="utf-8", newline="\n")


def copy_tree(source: Path, destination: Path) -> None:
    require_path(source, "source directory")
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(source, destination)


def remove_if_exists(path: Path) -> None:
    if path.is_dir():
        shutil.rmtree(path)
    elif path.exists():
        path.unlink()


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def directory_sha256(root: Path) -> str:
    """Return a stable digest of relative paths and file contents below root."""
    require_path(root, "directory to hash")
    digest = hashlib.sha256()
    for path in list_files(root):
        relative = path.relative_to(root).as_posix().encode("utf-8")
        digest.update(relative)
        digest.update(b"\0")
        digest.update(file_sha256(path).encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest()


def list_files(root: Path) -> list[Path]:
    return sorted(path for path in root.rglob("*") if path.is_file())


def path_for_command(path: Path) -> str:
    return str(path)


def package_env() -> dict[str, str]:
    require_path(JAVA_HOME, "JAVA_HOME")
    require_path(MAVEN_HOME, "Maven")
    env = os.environ.copy()
    env["JAVA_HOME"] = str(JAVA_HOME)
    env["MAVEN_HOME"] = str(MAVEN_HOME)
    env["PATH"] = os.pathsep.join(
        [
            str(JAVA_HOME / "bin"),
            str(MAVEN_HOME / "bin"),
            env.get("PATH", ""),
        ]
    )
    return env


def latest_full_template(deploy_root: Path) -> Path:
    require_path(deploy_root, "deploy root")
    candidates = [
        path
        for path in deploy_root.iterdir()
        if path.is_dir()
        and (path / "offline-debs").exists()
        and (path / "docker-compose.yml").exists()
    ]
    if not candidates:
        fail(f"no full package template with offline-debs found in {deploy_root}")
    return max(candidates, key=lambda item: item.stat().st_mtime)


def read_deployment_image_tag(deployment_dir: Path, image: str) -> str:
    """Read an application tag from the deployment's authoritative Compose file."""
    compose_path = deployment_dir / "docker-compose.yml"
    require_path(compose_path, "baseline docker-compose.yml")
    text = compose_path.read_text(encoding="utf-8")
    match = re.search(re.escape(image) + r":(?P<tag>[A-Za-z0-9_.-]+)", text)
    if match:
        return match.group("tag")
    fail(f"cannot find image tag for {image} in deployment Compose files under {deployment_dir}")


def latest_flyway_version() -> str:
    migration_dir = REPO_ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"
    require_path(migration_dir, "Flyway migration directory")
    versions: list[tuple[tuple[int, ...], str]] = []
    for path in migration_dir.glob("V*__*.sql"):
        match = re.match(r"^V(?P<version>[0-9][0-9_.]*)__", path.name)
        if not match:
            continue
        raw = match.group("version")
        normalized = raw.replace("_", ".")
        versions.append((tuple(int(part) for part in re.split(r"[_.]", raw)), normalized))
    if not versions:
        fail(f"no Flyway versioned migrations found in {migration_dir}")
    return max(versions, key=lambda item: item[0])[1]


def validate_host_ports(frontend_port: int, backend_port: int, postgres_port: int) -> None:
    """Reject invalid or overlapping host ports before creating package output."""
    ports = {
        "frontend": frontend_port,
        "backend": backend_port,
        "postgres": postgres_port,
    }
    invalid = {name: value for name, value in ports.items() if value < 1 or value > 65535}
    if invalid:
        details = ", ".join(f"{name}={value}" for name, value in invalid.items())
        fail(f"host ports must be between 1 and 65535: {details}")
    if len(set(ports.values())) != len(ports):
        details = ", ".join(f"{name}={value}" for name, value in ports.items())
        fail(f"frontend, backend, and postgres host ports must be distinct: {details}")


def default_compose_project_name(ctx: BuildContext) -> str:
    """Return the release-scoped default project name used by fresh deployments."""
    return f"qaflex-{ctx.release_id.lower()}"


def verify_backend_migrations_match_source(jar_path: Path, migration_dir: Path) -> None:
    require_path(jar_path, "backend jar")
    require_path(migration_dir, "Flyway migration directory")
    source_names = {path.name for path in migration_dir.glob("*.sql") if path.is_file()}
    with zipfile.ZipFile(jar_path) as archive:
        jar_names = {
            Path(name).name
            for name in archive.namelist()
            if name.startswith("BOOT-INF/classes/db/migration/") and name.endswith(".sql")
        }
    stale = sorted(jar_names - source_names)
    missing = sorted(source_names - jar_names)
    if stale or missing:
        details: list[str] = []
        if stale:
            details.append("stale migrations in jar: " + ", ".join(stale))
        if missing:
            details.append("source migrations missing from jar: " + ", ".join(missing))
        fail("backend migration set mismatch; " + "; ".join(details))


def resolve_context(args: argparse.Namespace) -> BuildContext:
    validate_host_ports(args.frontend_port, args.backend_port, args.postgres_port)
    if args.mode == "fresh-empty":
        if args.baseline_dir is not None:
            fail("fresh-empty does not accept --baseline-dir")
        if args.require_fact_rebuild:
            fail("fresh-empty cannot require fact rebuild because it contains no business data")
    else:
        if args.include_offline_docker_debs:
            fail("incremental-update cannot include offline Docker debs")
        if args.template_package_dir is not None:
            fail("incremental-update does not use --template-package-dir")
    if args.template_package_dir is not None and not args.include_offline_docker_debs:
        fail("--template-package-dir requires --include-offline-docker-debs")

    branch = git_value("rev-parse", "--abbrev-ref", "HEAD") or "unknown"
    commit = git_value("rev-parse", "HEAD") or "unknown"
    status = git_value("status", "--short")
    dirty = bool(status.strip()) or args.working
    dirty_state = "working tree contains uncommitted changes or --working was specified" if dirty else "clean"

    deploy_root = args.deploy_root.resolve()
    deploy_root.mkdir(parents=True, exist_ok=True)
    release_id = new_release_id()
    image_tag = release_id

    baseline_backend_tag = ""
    baseline_frontend_tag = ""
    if args.mode == "fresh-empty":
        package_name = f"{PACKAGE_PREFIX}-full-{release_id}"
        backend_tag = image_tag
        frontend_tag = image_tag
        baseline_name = ""
    else:
        package_name = f"{PACKAGE_PREFIX}-update-{release_id}"
        baseline_dir = args.baseline_dir
        if baseline_dir is None:
            fail("incremental-update requires an explicit --baseline-dir")
        baseline_dir = baseline_dir.resolve()
        require_path(baseline_dir, "baseline deployment directory")
        baseline_backend_tag = read_deployment_image_tag(baseline_dir, BACKEND_IMAGE)
        baseline_frontend_tag = read_deployment_image_tag(baseline_dir, FRONTEND_IMAGE)
        backend_tag = image_tag
        frontend_tag = image_tag
        baseline_name = baseline_dir.name

    package_dir = deploy_root / package_name
    archive_path = deploy_root / f"{package_name}.tar.gz"
    template_dir = args.template_package_dir.resolve() if args.template_package_dir else None
    if template_dir is None and args.mode == "fresh-empty" and args.include_offline_docker_debs:
        template_dir = latest_full_template(deploy_root)

    return BuildContext(
        mode=args.mode,
        release_id=release_id,
        deploy_root=deploy_root,
        package_name=package_name,
        package_dir=package_dir,
        archive_path=archive_path,
        branch=branch,
        commit=commit,
        dirty_state=dirty_state,
        backend_tag=backend_tag,
        frontend_tag=frontend_tag,
        baseline_backend_tag=baseline_backend_tag,
        baseline_frontend_tag=baseline_frontend_tag,
        baseline_name=baseline_name,
        expected_flyway_version=latest_flyway_version(),
        template_dir=template_dir,
        require_fact_rebuild=args.require_fact_rebuild,
        fact_rebuild_scope=args.fact_rebuild_scope,
        frontend_port=args.frontend_port,
        backend_port=args.backend_port,
        postgres_port=args.postgres_port,
        ldap_base_url=args.ldap_base_url,
        include_offline_docker_debs=args.include_offline_docker_debs,
    )


def build_products(args: argparse.Namespace) -> tuple[bool, str]:
    if args.skip_build:
        verify_backend_migrations_match_source(
            BACKEND_JAR,
            REPO_ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration",
        )
        log("skip frontend/backend build by parameter")
        return False, "build skipped by parameter after existing jar migration-set verification"

    env = package_env()
    frontend_dir = REPO_ROOT / "frontend"

    if not args.skip_frontend_release_tests:
        log("frontend release tests")
        run(
            (
                "npm.cmd",
                "run",
                "test",
                "--",
                "feature-manifest-access.test.ts",
                "ux-interaction-regressions.test.ts",
            ),
            cwd=frontend_dir,
            env=env,
        )
    # Both commands load unplugin-vue-components and write src/components.d.ts.
    # Keep them sequential so a release build cannot race its declaration output.
    log("frontend production build")
    run(("npm.cmd", "run", "build"), cwd=frontend_dir, env=env)

    strict_args = (
        MAVEN_HOME / "bin" / "mvn.cmd",
        "-q",
        "-f",
        REPO_ROOT / "backend" / "pom.xml",
        "-DskipTests",
        "clean",
        "package",
    )
    strict = run(strict_args, env=env, capture=True, check=False)
    if strict.returncode == 0:
        verify_backend_migrations_match_source(
            BACKEND_JAR,
            REPO_ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration",
        )
        return False, "backend jar built with -DskipTests clean package"

    if not args.allow_backend_test_source_skip:
        raise PackageError(
            "backend package failed with -DskipTests. Re-run with "
            "--allow-backend-test-source-skip to fall back to -Dmaven.test.skip=true."
        )

    log("backend -DskipTests package failed; falling back to -Dmaven.test.skip=true package")
    fallback_args = (
        MAVEN_HOME / "bin" / "mvn.cmd",
        "-q",
        "-f",
        REPO_ROOT / "backend" / "pom.xml",
        "-Dmaven.test.skip=true",
        "clean",
        "package",
    )
    run(fallback_args, env=env)
    verify_backend_migrations_match_source(
        BACKEND_JAR,
        REPO_ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration",
    )
    return True, "backend jar built with -Dmaven.test.skip=true clean package after -DskipTests testCompile blocker"


def backend_dockerfile() -> str:
    # noble 显式钉住 Ubuntu 24.04 底座：21-jre 浮动 tag 已漂移到 26.04，会导致 apt 源不可预期。
    # postgresql-client-16 提供容器内 pg_dump/pg_restore（数据库备份功能），与目标 PG 16 主版本一致。
    return """\
FROM eclipse-temurin:21-jre-noble
WORKDIR /app
COPY app.jar /app/app.jar
RUN apt-get update \
        && apt-get install -y --no-install-recommends postgresql-client-16 \
        && rm -rf /var/lib/apt/lists/*
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
EXPOSE 18080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
"""


def frontend_dockerfile() -> str:
    return """\
FROM nginx:1.27-alpine
COPY nginx-default.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html/
EXPOSE 80
"""


def nginx_config() -> str:
    return """\
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;
    client_max_body_size 50m;

    location /api/ {
        proxy_pass http://backend:18080/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 10s;
        proxy_send_timeout 300s;
        proxy_read_timeout 300s;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
"""


def generate_backup_master_key() -> str:
    """Generate a fresh base64-encoded 32-byte AES master key for one release."""
    return base64.b64encode(secrets.token_bytes(32)).decode("ascii")


def env_content(ctx: BuildContext) -> str:
    return f"""\
# Compose project scopes container, network, and named-volume identities.
COMPOSE_PROJECT_NAME={default_compose_project_name(ctx)}

# Platform URL reachable by users and GitLab system hook.
PLATFORM_PUBLIC_BASE_URL=http://172.22.10.115:{ctx.frontend_port}

# GitLab web URL for links shown in the platform.
# GitLab PostgreSQL source connection is configured later in the UI.
GITLAB_WEB_BASE_URL=http://172.22.10.233

# Built-in platform database. This is not the GitLab source database.
POSTGRES_USER=qaflex
POSTGRES_PASSWORD=qaflex
POSTGRES_DB=qaflex
POSTGRES_PORT={ctx.postgres_port}
POSTGRES_BIND=127.0.0.1

# Platform ports.
FRONTEND_PORT={ctx.frontend_port}
FRONTEND_BIND=0.0.0.0
BACKEND_PORT={ctx.backend_port}
BACKEND_BIND=127.0.0.1

# LDAP is the only interactive login provider. The LDAP service remains an
# independent deployment and this address must be reachable from the backend container.
PLATFORM_AUTH_PROVIDER=ldap
PLATFORM_LDAP_BASE_URL={ctx.ldap_base_url}
PLATFORM_LDAP_CONNECT_TIMEOUT_MS=3000
PLATFORM_LDAP_READ_TIMEOUT_MS=10000
PLATFORM_LDAP_INITIAL_SYNC_REQUIRED=true

# Database backup master key: base64 of 32 random bytes, generated per fresh
# package. Remote backup credentials entered in the UI are encrypted with it.
# Rotating it invalidates stored remote passwords (re-enter them in the UI).
PLATFORM_BACKUP_SECRET_KEY={generate_backup_master_key()}

# Host directory bound into the backend container for database backup files.
# Kept separate from the deployment directory so stack removal never touches backups.
PLATFORM_BACKUP_HOST_DIR=/opt/qaflex-backups

# Runtime options.
GITLAB_SYSTEM_HOOK_MAX_QUEUE_SIZE=1000
GITLAB_MAX_SYNC_THREADS=16
GITLAB_DELETE_RECONCILIATION_ENABLED=false
PLATFORM_QUERY_TIMEOUT_SECONDS=30
PLATFORM_SLOW_QUERY_THRESHOLD_MS=1000
REVIEW_DATA_SEARCH_INDEX_BACKFILL_ENABLED=false
CUSTOMER_ISSUE_DELAY_LABEL_WRITEBACK_API_ENABLED=false
"""


def compose_content(ctx: BuildContext, *, external_postgres_volume: bool = False) -> str:
    """Generate the complete authoritative Compose model for a release."""
    project_name = 'name: "${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME is required}"\n\n'
    postgres_container_name = "    container_name: qaflex-postgres\n" if external_postgres_volume else ""
    backend_container_name = "    container_name: qaflex-backend\n" if external_postgres_volume else ""
    frontend_container_name = "    container_name: qaflex-frontend\n" if external_postgres_volume else ""
    postgres_volume = (
        """  qaflex_pgdata:
    external: true
    name: "${POSTGRES_VOLUME_NAME:?POSTGRES_VOLUME_NAME is required}"
  qaflex_backend_logs:
    name: "${BACKEND_LOG_VOLUME_NAME:?BACKEND_LOG_VOLUME_NAME is required}"
"""
        if external_postgres_volume
        else """  qaflex_pgdata:
  qaflex_backend_logs:
"""
    )
    return f"""\
{project_name}\
services:
  postgres:
    image: {POSTGRES_IMAGE}
{postgres_container_name}\
    restart: unless-stopped
    environment:
      POSTGRES_USER: ${{POSTGRES_USER}}
      POSTGRES_PASSWORD: ${{POSTGRES_PASSWORD}}
      POSTGRES_DB: ${{POSTGRES_DB}}
      TZ: Asia/Shanghai
    ports:
      - "${{POSTGRES_BIND:-127.0.0.1}}:${{POSTGRES_PORT:-{ctx.postgres_port}}}:5432"
    volumes:
      - qaflex_pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${{POSTGRES_USER}} -d ${{POSTGRES_DB}}"]
      interval: 10s
      timeout: 5s
      retries: 12

  backend:
    image: {BACKEND_IMAGE}:{ctx.backend_tag}
{backend_container_name}\
    restart: unless-stopped
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      TZ: Asia/Shanghai
      DATASOURCE_URL: jdbc:postgresql://postgres:5432/${{POSTGRES_DB}}
      DATASOURCE_USERNAME: ${{POSTGRES_USER}}
      DATASOURCE_PASSWORD: ${{POSTGRES_PASSWORD}}
      SPRING_SQL_INIT_MODE: never
      PLATFORM_TIME_ZONE: Asia/Shanghai
      PLATFORM_INSTANCE_ID: ${{COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME is required}}
      PLATFORM_BACKUP_ROOT: /var/lib/qaflex/backups
      PLATFORM_BACKUP_SECRET_KEY: ${{PLATFORM_BACKUP_SECRET_KEY:-}}
      PLATFORM_AUTH_PROVIDER: ${{PLATFORM_AUTH_PROVIDER}}
      PLATFORM_LDAP_BASE_URL: ${{PLATFORM_LDAP_BASE_URL}}
      PLATFORM_LDAP_CONNECT_TIMEOUT_MS: ${{PLATFORM_LDAP_CONNECT_TIMEOUT_MS:-3000}}
      PLATFORM_LDAP_READ_TIMEOUT_MS: ${{PLATFORM_LDAP_READ_TIMEOUT_MS:-10000}}
      PLATFORM_LDAP_INITIAL_SYNC_REQUIRED: ${{PLATFORM_LDAP_INITIAL_SYNC_REQUIRED:-true}}
      PLATFORM_SECURE_CONFIG_REQUIRED: "true"
      PLATFORM_AUTH_CSRF_ENABLED: "true"
      PLATFORM_BACKGROUND_JOBS_ENABLED: "${{PLATFORM_BACKGROUND_JOBS_ENABLED:-true}}"
      GITLAB_WEB_BASE_URL: ${{GITLAB_WEB_BASE_URL}}
      GITLAB_SYSTEM_HOOK_BASE_URL: ${{PLATFORM_PUBLIC_BASE_URL}}/api/gitlab-sync/system-hook
      GITLAB_SYSTEM_HOOK_MAX_QUEUE_SIZE: ${{GITLAB_SYSTEM_HOOK_MAX_QUEUE_SIZE:-1000}}
      GITLAB_MAX_SYNC_THREADS: ${{GITLAB_MAX_SYNC_THREADS:-16}}
      GITLAB_DELETE_RECONCILIATION_ENABLED: ${{GITLAB_DELETE_RECONCILIATION_ENABLED:-false}}
      PLATFORM_QUERY_TIMEOUT_SECONDS: ${{PLATFORM_QUERY_TIMEOUT_SECONDS:-30}}
      PLATFORM_SLOW_QUERY_THRESHOLD_MS: ${{PLATFORM_SLOW_QUERY_THRESHOLD_MS:-1000}}
      REVIEW_DATA_SEARCH_INDEX_BACKFILL_ENABLED: ${{REVIEW_DATA_SEARCH_INDEX_BACKFILL_ENABLED:-false}}
      CUSTOMER_ISSUE_DELAY_LABEL_WRITEBACK_API_ENABLED: ${{CUSTOMER_ISSUE_DELAY_LABEL_WRITEBACK_API_ENABLED:-false}}
    ports:
      - "${{BACKEND_BIND:-127.0.0.1}}:${{BACKEND_PORT:-{ctx.backend_port}}}:18080"
    volumes:
      - qaflex_backend_logs:/app/logs
      - "${{PLATFORM_BACKUP_HOST_DIR:-/opt/qaflex-backups}}:/var/lib/qaflex/backups"
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:18080/actuator/health >/dev/null || exit 1"]
      interval: 20s
      timeout: 5s
      retries: 12
      start_period: 60s

  frontend:
    image: {FRONTEND_IMAGE}:{ctx.frontend_tag}
{frontend_container_name}\
    restart: unless-stopped
    depends_on:
      backend:
        condition: service_healthy
    ports:
      - "${{FRONTEND_BIND:-0.0.0.0}}:${{FRONTEND_PORT:-{ctx.frontend_port}}}:80"
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://127.0.0.1/ >/dev/null || exit 1"]
      interval: 20s
      timeout: 5s
      retries: 12

volumes:
{postgres_volume}\
"""


def initialize_layout(ctx: BuildContext) -> None:
    if ctx.archive_path.exists():
        fail(f"archive already exists: {ctx.archive_path}")
    checksum_path = ctx.archive_path.with_suffix(ctx.archive_path.suffix + ".sha256")
    if checksum_path.exists():
        fail(f"archive checksum already exists: {checksum_path}")
    try:
        ctx.package_dir.mkdir(parents=True, exist_ok=False)
    except FileExistsError as exc:
        raise PackageError(f"package directory already exists: {ctx.package_dir}") from exc
    (ctx.package_dir / "docker-images").mkdir()


def prepare_image_build_contexts(root: Path) -> None:
    """Create disposable Docker build contexts outside the delivery package."""
    require_path(BACKEND_JAR, "backend jar")
    require_path(FRONTEND_DIST, "frontend dist")
    backend_dir = root / "backend"
    frontend_dir = root / "frontend"
    backend_dir.mkdir(parents=True, exist_ok=True)
    frontend_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(BACKEND_JAR, backend_dir / "app.jar")
    write_text(backend_dir / "Dockerfile", backend_dockerfile())
    copy_tree(FRONTEND_DIST, frontend_dir / "dist")
    write_text(frontend_dir / "Dockerfile", frontend_dockerfile())
    write_text(frontend_dir / "nginx-default.conf", nginx_config())


def copy_offline_debs(ctx: BuildContext) -> None:
    if ctx.template_dir is None:
        fail("--include-offline-docker-debs requires a template package directory with offline-debs")
    source = ctx.template_dir / "offline-debs"
    require_path(source, "offline-debs template")
    copy_tree(source, ctx.package_dir / "offline-debs")


def fresh_readme(ctx: BuildContext) -> str:
    dependency_section = """
## 2. Docker / Compose 前置条件

目标机必须已安装 Docker Engine 与 Docker Compose plugin。本包未携带系统软件包；确需为全新离线服务器附带 Ubuntu 24.04 安装包时，打包时显式使用 `--include-offline-docker-debs`。
"""
    if ctx.include_offline_docker_debs:
        dependency_section = """
## 2. 安装离线 Docker 依赖

目标机已安装 Docker / Compose 时可跳过本步骤。

```bash
sudo dpkg -i offline-debs/ubuntu-24.04-amd64/*.deb || sudo apt-get -f install
sudo systemctl enable --now docker
sudo docker version
sudo docker compose version
```
"""
    return f"""\
# QA Flex Platform 内网离线部署包

本包是 **全新空数据部署包**，适用于 Ubuntu 24.04 amd64 无公网服务器。

请不要把本包当作保留旧平台数据库的普通增量包使用。只有新服务器首次部署、明确清空环境、灾难恢复或业务方批准重建时才使用本包。

## 固定地址

- 平台访问地址：http://172.22.10.115:{ctx.frontend_port}
- GitLab Web 地址：http://172.22.10.233

包内 PostgreSQL 只作为平台内置库。GitLab PostgreSQL、老平台 MySQL、老平台 MongoDB 均在平台 UI 中配置，不要写入 DATASOURCE_URL。

## 1. 解压

```bash
tar -xzf {ctx.archive_path.name}
cd {ctx.package_name}
```

{dependency_section}

## 3. 加载镜像

PostgreSQL 使用目标机既有的 `{POSTGRES_IMAGE}` 镜像，本包不携带它。先确认镜像存在：

```bash
sudo docker image inspect {POSTGRES_IMAGE}
```

若目标机缺失该镜像（真正的新服务器），先从既有历史全新包（部署资料归档）的 `docker-images/postgres_16-alpine.tar` 执行 `docker load` 加载，再继续本节。

加载本包应用镜像：

```bash
sudo docker load -i docker-images/{BACKEND_IMAGE}_{ctx.backend_tag}.tar
sudo docker load -i docker-images/{FRONTEND_IMAGE}_{ctx.frontend_tag}.tar
```

## 4. 准备环境变量

`.env` 已写入当前内网地址、端口和 LDAP v0.3 后端地址 `{ctx.ldap_base_url}`，是本实例的运行配置；如需调整端口或密码，先编辑该文件再部署。部署前确认平台后端容器能够访问 LDAP 地址；不要把 GitLab / MySQL / MongoDB 源库连接写进平台库变量。

默认 Compose project 为 `{default_compose_project_name(ctx)}`，前端、后端和 PostgreSQL 主机端口分别为 `{ctx.frontend_port}`、`{ctx.backend_port}`、`{ctx.postgres_port}`。同一主机再次部署本包时，必须同时修改 `COMPOSE_PROJECT_NAME` 和三个端口；容器、默认网络、数据库卷及日志卷均按 project 隔离。

## 5. 全新空数据部署

```bash
sudo docker compose --env-file .env up -d --force-recreate postgres backend frontend
sudo docker compose --env-file .env ps
```

该命令只管理当前 `COMPOSE_PROJECT_NAME` 下的资源，不会替换或删除同机其它 QA Flex 实例。禁止为解决名称冲突删除其它项目的容器或 volume；若 `docker compose config` 显示的 project 或端口不符合预期，应先修改 `.env`。

## 6. 健康检查

```bash
curl -fsS http://127.0.0.1:{ctx.backend_port}/actuator/health
curl -fsS http://127.0.0.1:{ctx.frontend_port}/
sudo docker compose --env-file .env logs --tail=120 backend
sudo docker compose --env-file .env logs --tail=120 frontend
```

浏览器访问：http://172.22.10.115:{ctx.frontend_port}

平台仅接受 LDAP 账号密码登录，不启用本地 `admin/admin123` 或审批账号。LDAP 不可用时，新登录必须失败；已建立的平台 Session 可继续使用至退出或过期。

## 7. 首次数据重新导入/同步

1. 登录平台后，在 GitLab 数据镜像设置页面配置 GitLab PostgreSQL 只读源库，执行全量同步。
2. 需要代码走查兼容模式数据时，在老平台数据库设置页面配置老平台 MySQL 源库并导入 MR/代码走查非法数据表。
3. 需要评审数据兼容模式时，在老平台 MongoDB 设置页面配置 MongoDB 源库并导入对应集合。
4. 数据导入或同步完成后，执行事实层刷新/重建，并等待统计快照预热完成后再验收系统测试、客户问题和代码走查页面。

## 8. 包完整性校验

```bash
sha256sum -c SHA256SUMS.txt
sudo docker compose --env-file .env config
```
"""


def incremental_readme(ctx: BuildContext) -> str:
    if ctx.require_fact_rebuild:
        fact_section = f"""\
## 6. 事实层重建

本更新包标记为需要事实层重建。该步骤只基于现有镜像表重建事实层和统计快照，不重新全量同步 GitLab，不删除平台数据。

使用具备数据同步权限的 LDAP 账号登录平台，在“系统设置/数据镜像设置”中触发 `{ctx.fact_rebuild_scope}` 范围的事实层重建。平台启用 Session CSRF 保护，不在部署文档中保存账号密码或绕过浏览器安全流程。
"""
    else:
        fact_section = """\
## 6. 不执行事实层重建

本更新包只替换后端和前端业务镜像，不改变事实表、统计口径或历史聚合结果。部署后不要主动触发事实重建、全量同步或清空快照。
"""

    return f"""\
# QA Flex Platform 保数据更新包

本包用于既有内网实例的受控升级。它保留现有 PostgreSQL 容器、volume、镜像表、事实表、同步状态、平台配置和业务数据，只重新创建后端与前端应用容器。

打包基线交付物（用于识别现场升级前镜像，不是现场部署目录）：

```text
{ctx.baseline_name}
```

基线应用镜像：

- `{BACKEND_IMAGE}:{ctx.baseline_backend_tag}`
- `{FRONTEND_IMAGE}:{ctx.baseline_frontend_tag}`

目标应用镜像：

- `{BACKEND_IMAGE}:{ctx.backend_tag}`
- `{FRONTEND_IMAGE}:{ctx.frontend_tag}`

目标 Flyway 版本：`{ctx.expected_flyway_version}`。

## 禁止操作

- 不要执行 `docker compose down -v`。
- 不要删除 `qaflex_pgdata` 或任何 PostgreSQL volume。
- 不要重建、替换或删除 postgres 容器。
- 不要清空镜像表、事实表、同步状态、用户、页面设置或平台配置。
- 不要在内网服务器执行 `docker build`。
- 不要因为本包部署而触发 GitLab 全量同步。

## 1. 校验并解压更新包

先进入上传更新包的目录，确认文件存在：

```bash
pwd
ls -lh {ctx.archive_path.name} {ctx.archive_path.name}.sha256
```

校验传输后的归档；输出必须为 `OK`，否则停止：

```bash
sha256sum -c {ctx.archive_path.name}.sha256
```

解压并进入更新包目录：

```bash
tar -xzf {ctx.archive_path.name}
cd {ctx.package_name}
```

逐项校验包内文件；所有项目必须为 `OK`：

```bash
sha256sum -c SHA256SUMS.txt
```

查看发布清单，确认 `baseline`、`target`、`flywayVersion` 和 `facts`：

```bash
cat RELEASE-MANIFEST.json
```

## 2. 找到并检查现有部署目录

返回上级目录，进入**当前正在运行平台的原部署目录**。该目录必须包含现场 `.env` 和基础 `docker-compose.yml`；不要把历史更新包目录当成部署目录：

```bash
cd ..
cd <现有部署目录>
pwd
ls -la .env docker-compose.yml
```

检查当前三个服务均在运行，postgres 必须为 healthy：

```bash
sudo docker compose --env-file .env ps
```

查看当前唯一 Compose 配置中的镜像；前后端必须与本文开头的基线应用镜像完全一致：

```bash
sudo docker compose --env-file .env config --images
```

### 如果这里显示上一次失败包的镜像

若上一轮升级在行数检查处中止，现场可能已经原子替换为失败包的完整 `docker-compose.yml` 并重建后端，再次执行升级就会报 `backend baseline image does not match`。这不表示失败包已经成为成功基线，也不要修改本包脚本绕过检查。

先列出曾产生 `counts.diff` 的失败备份目录：

```bash
find "$PWD/upgrade-backups" -mindepth 2 -maxdepth 2 -type f -name counts.diff -print
```

查看候选差异和其中保存的旧镜像；选择**第一次失败时 `counts.diff` 所在的同一目录**：

```bash
cat <失败备份目录>/counts.diff
cat <失败备份目录>/compose-images.txt
```

把确认后的绝对目录写入变量并检查必需文件：

```bash
FAILED_BACKUP_DIR="<失败备份目录的绝对路径>"
echo "$FAILED_BACKUP_DIR"
ls -lh "$FAILED_BACKUP_DIR/.env" "$FAILED_BACKUP_DIR/docker-compose.yml" "$FAILED_BACKUP_DIR/database.dump"
```

使用**本包**的回滚脚本只恢复应用配置和基线前后端；它不会执行数据库恢复，ODS 在失败期间正常新增的数据会保留：

```bash
bash ../{ctx.package_name}/rollback.sh "$PWD" "$FAILED_BACKUP_DIR"
```

回滚完成后重新检查；前后端必须同时显示本文开头的基线镜像，postgres 必须仍为 healthy。满足后才能继续第 3 节：

```bash
sudo docker compose --env-file .env ps
sudo docker compose --env-file .env config --images
```

确认没有正在执行的同步或事实任务，再继续。若页面仍显示运行中任务，等待其完成或先取消。

## 3. 生成并校验预部署备份

先运行独立备份脚本。此步骤不会重建或停止任何应用容器，也不会修改数据库：

```bash
bash ../{ctx.package_name}/backup.sh "$PWD"
```

读取脚本输出的备份目录并逐项确认。两个 dump 必须非空，校验必须全部显示 `OK`：

```bash
BACKUP_DIR="$(cat upgrade-backups/latest-predeploy-backup.txt)"
echo "$BACKUP_DIR"
ls -lh "$BACKUP_DIR/database.dump" "$BACKUP_DIR/critical-tables.dump"
cd "$BACKUP_DIR"
sha256sum -c SHA256SUMS.txt
cd -
```

备份内容包括完整数据库 `database.dump`、关键表 `critical-tables.dump`、两份可恢复内容清单、关键表行数、Flyway 版本、Compose 配置和 PostgreSQL 容器证据。完整数据库备份是灾备恢复权威；关键表备份用于快速核验和经审批的定向恢复。

## 4. 执行受控升级

仅在上述备份校验全部通过后执行升级。第二个参数必须是刚确认的备份目录：

```bash
bash ../{ctx.package_name}/upgrade.sh "$PWD" "$BACKUP_DIR"
```

升级脚本依次执行：

- 校验现场 Compose 仍使用上述基线镜像，检查 PostgreSQL 容器和磁盘空间。
- 检测 `sync_runs`、`fact_build_tasks` 中是否存在运行中任务；存在时拒绝升级。
- 复核备份校验和、所属发布包、基线镜像和 PostgreSQL 容器 ID；任一不符即拒绝升级并要求重新备份。
- 加载唯一的新镜像标签，停止旧后端后记录迁移起点行数，再以后台调度关闭的迁移模式启动后端，完成 Flyway、健康与行数守恒校验；随后以正常模式重启后端恢复后台任务，最后重建前端。
- 校验 PostgreSQL 容器 ID 未变化、Flyway 到达 `{ctx.expected_flyway_version}`、关键业务表行数在事实重建前保持不变。

脚本不会执行 `docker compose down`、不会删除 volume，也不会触发 GitLab 全量同步。

## 5. 逐项检查升级结果

先确认容器状态，backend、frontend、postgres 都必须为 healthy：

```bash
sudo docker compose --env-file .env ps
```

再分别检查后端和前端：

```bash
curl -fsS http://127.0.0.1:{ctx.backend_port}/actuator/health
```

```bash
curl -fsS http://127.0.0.1:{ctx.frontend_port}/
```

查看脚本记录的本次备份目录，保留输出供回滚使用：

```bash
cat upgrade-backups/latest-backup.txt
```

确认迁移守恒差异为空、后台调度已恢复，Flyway 已到目标版本：

```bash
BACKUP_DIR="$(cat upgrade-backups/latest-backup.txt)"
ls -l "$BACKUP_DIR/counts.diff"
cat "$BACKUP_DIR/counts.diff"
sudo docker inspect "$(sudo docker compose --env-file .env ps -q backend)" --format '{{{{range .Config.Env}}}}{{{{println .}}}}{{{{end}}}}' | grep '^PLATFORM_BACKGROUND_JOBS_ENABLED=true$'
sudo docker compose --env-file .env exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select version from flyway_schema_history where success order by installed_rank desc limit 1;"'
```

`counts.diff` 必须是 0 字节且 `cat` 无输出；后台调度检查必须输出 `PLATFORM_BACKGROUND_JOBS_ENABLED=true`；Flyway 必须输出 `{ctx.expected_flyway_version}`。

使用 LDAP v0.3 账号登录；旧本地 `admin/admin123` 必须被拒绝。LDAP 不可用时，新登录失败，已有 Session 可继续使用到退出或过期。

{fact_section}

## 7. 仅在升级失败时回滚应用

应用回滚命令会原子恢复升级前完整 `docker-compose.yml` 并重新创建旧后端/前端容器；现场 `.env` 在升级和回滚中均不修改。它不会自动执行 `pg_restore`，避免误覆盖现场数据：

先读取上一步保存的备份目录，并检查其中确实存在配置和数据库备份：

```bash
BACKUP_DIR="$(cat upgrade-backups/latest-backup.txt)"
echo "$BACKUP_DIR"
ls -lh "$BACKUP_DIR"
```

确认路径无误后执行应用回滚：

```bash
bash ../{ctx.package_name}/rollback.sh "$PWD" "$BACKUP_DIR"
```

Flyway 迁移是前向迁移。只有应用回滚仍不能恢复服务时，才在停机并确认备份无误后，将 `database.dump` 恢复到隔离数据库或经审批重建的平台库。
"""


def backup_helper(ctx: BuildContext) -> str:
    return f"""\
#!/usr/bin/env bash
set -euo pipefail

TARGET_DIR="${{1:-$PWD}}"
EXPECTED_BACKEND="{BACKEND_IMAGE}:{ctx.baseline_backend_tag}"
EXPECTED_FRONTEND="{FRONTEND_IMAGE}:{ctx.baseline_frontend_tag}"
PACKAGE_NAME="{ctx.package_name}"

fail() {{ echo "[backup] ERROR: $*" >&2; exit 1; }}
log() {{ echo "[backup] $*"; }}

if docker info >/dev/null 2>&1; then
  DOCKER=(docker)
elif sudo docker info >/dev/null 2>&1; then
  DOCKER=(sudo docker)
else
  fail "Docker is unavailable"
fi

cd "$TARGET_DIR"
[[ -f .env && -f docker-compose.yml ]] || fail "run from an existing deployment directory containing .env and docker-compose.yml"
[[ -z "$(find . -maxdepth 1 -type f -name 'docker-compose.*.yml' -print -quit)" ]] || fail "refusing layered Compose configuration; keep only docker-compose.yml before backing up"
compose() {{ "${{DOCKER[@]}}" compose --env-file .env "$@"; }}
db_query() {{
  local sql="$1"
  compose exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "$1"' sh "$sql"
}}

BASE_CONFIG="$(compose config)"
grep -Fq "image: $EXPECTED_BACKEND" <<<"$BASE_CONFIG" || fail "backend baseline image does not match $EXPECTED_BACKEND"
grep -Fq "image: $EXPECTED_FRONTEND" <<<"$BASE_CONFIG" || fail "frontend baseline image does not match $EXPECTED_FRONTEND"
POSTGRES_ID="$(compose ps -q postgres)"
[[ -n "$POSTGRES_ID" ]] || fail "postgres service is not running"
[[ "$("${{DOCKER[@]}}" inspect -f '{{{{.State.Health.Status}}}}' "$POSTGRES_ID")" == "healthy" ]] || fail "postgres is not healthy"
ACTIVE_JOBS="$(db_query "select (select count(*) from sync_runs where status in ('SUBMITTED','QUEUED','RUNNING','RETRYING','CANCELLING')) + (select count(*) from fact_build_tasks where status in ('PENDING','QUEUED','RUNNING','RETRYING'));" )"
[[ "$ACTIVE_JOBS" == "0" ]] || fail "$ACTIVE_JOBS sync/fact jobs are active; wait for completion or cancel them before backing up"

DB_BYTES="$(db_query "select pg_database_size(current_database());")"
FREE_BYTES="$(df -PB1 "$TARGET_DIR" | awk 'NR==2 {{print $4}}')"
REQUIRED_BYTES="$(( DB_BYTES * 2 + 1073741824 ))"
(( FREE_BYTES >= REQUIRED_BYTES )) || fail "insufficient disk space for verified backups: free=$FREE_BYTES required=$REQUIRED_BYTES"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_DIR="$TARGET_DIR/upgrade-backups/$PACKAGE_NAME-$STAMP"
mkdir -p "$BACKUP_DIR"
cp -a .env docker-compose.yml "$BACKUP_DIR/"
printf '%s\n' "$POSTGRES_ID" > "$BACKUP_DIR/postgres.container-id"
"${{DOCKER[@]}}" inspect "$POSTGRES_ID" > "$BACKUP_DIR/postgres.inspect.json"
compose images > "$BACKUP_DIR/compose-images.txt"
db_query "select coalesce(version,'') || '|' || success from flyway_schema_history order by installed_rank desc limit 1;" > "$BACKUP_DIR/flyway-before.txt"

CRITICAL_TABLES=(gitlab_sync_configs ods_gitlab_issues ods_gitlab_merge_requests issue_fact merge_request_fact integration_test_fact issue_fact_customer_members sync_runs sync_run_table_tasks fact_build_tasks review_records review_problem_items review_data_match_mode_reports review_data_match_mode_problem_details code_review_match_mode_records platform_ldap_users platform_ldap_roles platform_ldap_user_roles platform_permissions platform_role_permissions platform_default_role_permissions issue_scope_catalogs issue_scope_groups issue_scope_members statistic_board_snapshots page_record_snapshots)
TABLE_ARGS=()
: > "$BACKUP_DIR/counts-before.txt"
for table in "${{CRITICAL_TABLES[@]}}"; do
  if [[ "$(db_query "select to_regclass('public.$table') is not null;")" == "t" ]]; then
    printf '%s=%s\n' "$table" "$(db_query "select count(*) from $table;")" >> "$BACKUP_DIR/counts-before.txt"
    TABLE_ARGS+=(--table "public.$table")
  fi
done
(( ${{#TABLE_ARGS[@]}} > 0 )) || fail "none of the critical tables exist"

log "creating complete PostgreSQL custom-format backup"
compose exec -T postgres sh -c 'pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > "$BACKUP_DIR/database.dump"
log "creating critical-table PostgreSQL custom-format backup"
compose exec -T postgres sh -c 'pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"' sh "${{TABLE_ARGS[@]}}" > "$BACKUP_DIR/critical-tables.dump"
[[ -s "$BACKUP_DIR/database.dump" && -s "$BACKUP_DIR/critical-tables.dump" ]] || fail "database backup is empty"
compose exec -T postgres pg_restore --list < "$BACKUP_DIR/database.dump" > "$BACKUP_DIR/database.restore-list.txt"
compose exec -T postgres pg_restore --list < "$BACKUP_DIR/critical-tables.dump" > "$BACKUP_DIR/critical-tables.restore-list.txt"

cat > "$BACKUP_DIR/backup-manifest.env" <<EOF
BACKUP_SCHEMA_VERSION=1
PACKAGE_NAME=$PACKAGE_NAME
BACKUP_EXPECTED_BACKEND=$EXPECTED_BACKEND
BACKUP_EXPECTED_FRONTEND=$EXPECTED_FRONTEND
BACKUP_POSTGRES_ID=$POSTGRES_ID
CREATED_AT_UTC=$STAMP
EOF
(cd "$BACKUP_DIR" && find . -maxdepth 1 -type f ! -name SHA256SUMS.txt -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS.txt)
(cd "$BACKUP_DIR" && sha256sum -c SHA256SUMS.txt)
printf '%s\n' "$BACKUP_DIR" > "$TARGET_DIR/upgrade-backups/latest-predeploy-backup.txt"
log "verified pre-deployment backup completed: $BACKUP_DIR"
"""


def upgrade_helper(ctx: BuildContext) -> str:
    completion_message = (
        f"login with LDAP and rebuild fact scope '{ctx.fact_rebuild_scope}' before final statistics acceptance"
        if ctx.require_fact_rebuild
        else "fact rebuild is not required for this release"
    )
    return f"""\
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${{BASH_SOURCE[0]}}")" && pwd)"
PACKAGE_DIR="$SCRIPT_DIR"
TARGET_DIR="${{1:-$PWD}}"
BACKUP_DIR="${{2:-}}"
PACKAGE_NAME="{ctx.package_name}"
TARGET_COMPOSE="$PACKAGE_DIR/docker-compose.yml"
EXPECTED_BACKEND="{BACKEND_IMAGE}:{ctx.baseline_backend_tag}"
EXPECTED_FRONTEND="{FRONTEND_IMAGE}:{ctx.baseline_frontend_tag}"
TARGET_FLYWAY="{ctx.expected_flyway_version}"

fail() {{ echo "[upgrade] ERROR: $*" >&2; exit 1; }}
log() {{ echo "[upgrade] $*"; }}

[[ -n "$BACKUP_DIR" ]] || fail "usage: upgrade.sh <deployment-dir> <backup-dir>"
[[ -f "$BACKUP_DIR/backup-manifest.env" && -f "$BACKUP_DIR/SHA256SUMS.txt" ]] || fail "invalid pre-deployment backup: $BACKUP_DIR"
(cd "$BACKUP_DIR" && sha256sum -c SHA256SUMS.txt) || fail "invalid pre-deployment backup checksums"
source "$BACKUP_DIR/backup-manifest.env"
[[ "${{PACKAGE_NAME:-}}" == "{ctx.package_name}" ]] || fail "pre-deployment backup belongs to another release package"
[[ "${{BACKUP_EXPECTED_BACKEND:-}}" == "{BACKEND_IMAGE}:{ctx.baseline_backend_tag}" ]] || fail "pre-deployment backup backend baseline mismatch"
[[ "${{BACKUP_EXPECTED_FRONTEND:-}}" == "{FRONTEND_IMAGE}:{ctx.baseline_frontend_tag}" ]] || fail "pre-deployment backup frontend baseline mismatch"

if docker info >/dev/null 2>&1; then
  DOCKER=(docker)
elif sudo docker info >/dev/null 2>&1; then
  DOCKER=(sudo docker)
else
  fail "Docker is unavailable"
fi

cd "$TARGET_DIR"
[[ -f .env && -f docker-compose.yml ]] || fail "run from an existing deployment directory containing .env and docker-compose.yml"
[[ -z "$(find . -maxdepth 1 -type f -name 'docker-compose.*.yml' -print -quit)" ]] || fail "refusing layered Compose configuration; keep only docker-compose.yml"

compose() {{ "${{DOCKER[@]}}" compose --env-file .env "$@"; }}
db_query() {{
  local sql="$1"
  compose exec -T postgres sh -c 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "$1"' sh "$sql"
}}

BASE_CONFIG="$(compose config)"
grep -Fq "image: $EXPECTED_BACKEND" <<<"$BASE_CONFIG" || fail "backend baseline image does not match $EXPECTED_BACKEND"
grep -Fq "image: $EXPECTED_FRONTEND" <<<"$BASE_CONFIG" || fail "frontend baseline image does not match $EXPECTED_FRONTEND"
POSTGRES_ID="$(compose ps -q postgres)"
[[ -n "$POSTGRES_ID" ]] || fail "postgres service is not running"
[[ "$("${{DOCKER[@]}}" inspect -f '{{{{.State.Health.Status}}}}' "$POSTGRES_ID")" == "healthy" ]] || fail "postgres is not healthy"

ACTIVE_JOBS="$(db_query "select (select count(*) from sync_runs where status in ('SUBMITTED','QUEUED','RUNNING','RETRYING','CANCELLING')) + (select count(*) from fact_build_tasks where status in ('PENDING','QUEUED','RUNNING','RETRYING'));" )"
[[ "$ACTIVE_JOBS" == "0" ]] || fail "$ACTIVE_JOBS sync/fact jobs are active; wait for completion or cancel them before upgrading"

IMAGE_BYTES="$(( $(stat -c %s "$PACKAGE_DIR/docker-images/{BACKEND_IMAGE}_{ctx.backend_tag}.tar") + $(stat -c %s "$PACKAGE_DIR/docker-images/{FRONTEND_IMAGE}_{ctx.frontend_tag}.tar") ))"
FREE_BYTES="$(df -PB1 "$TARGET_DIR" | awk 'NR==2 {{print $4}}')"
REQUIRED_BYTES="$(( IMAGE_BYTES * 2 + 1073741824 ))"
(( FREE_BYTES >= REQUIRED_BYTES )) || fail "insufficient disk space: free=$FREE_BYTES required=$REQUIRED_BYTES"

capture_counts() {{
  local output="$1"
  : > "$output"
  while IFS='=' read -r table _count; do
    [[ "$table" =~ ^[a-z_][a-z0-9_]*$ ]] || fail "invalid table name in backup counts: $table"
    printf '%s=%s\n' "$table" "$(db_query "select count(*) from $table;")" >> "$output"
  done < "$BACKUP_DIR/counts-before.txt"
}}

[[ "$POSTGRES_ID" == "${{BACKUP_POSTGRES_ID:-}}" ]] || fail "PostgreSQL container changed after the pre-deployment backup"

log "loading new application images"
"${{DOCKER[@]}}" load -i "$PACKAGE_DIR/docker-images/{BACKEND_IMAGE}_{ctx.backend_tag}.tar"
"${{DOCKER[@]}}" load -i "$PACKAGE_DIR/docker-images/{FRONTEND_IMAGE}_{ctx.frontend_tag}.tar"
TEMP_COMPOSE="docker-compose.yml.$PACKAGE_NAME.tmp"
trap 'rm -f "$TEMP_COMPOSE"' EXIT
cp "$TARGET_COMPOSE" "$TEMP_COMPOSE"
"${{DOCKER[@]}}" compose --env-file .env -f "$TEMP_COMPOSE" config >/dev/null
mv -f "$TEMP_COMPOSE" docker-compose.yml
trap - EXIT
BACKEND_HEALTH_PORT="$(awk -F= '$1 == "BACKEND_PORT" {{print substr($0, index($0, "=") + 1)}}' .env | tail -n 1)"
FRONTEND_HEALTH_PORT="$(awk -F= '$1 == "FRONTEND_PORT" {{print substr($0, index($0, "=") + 1)}}' .env | tail -n 1)"
BACKEND_HEALTH_PORT="${{BACKEND_HEALTH_PORT:-{ctx.backend_port}}}"
FRONTEND_HEALTH_PORT="${{FRONTEND_HEALTH_PORT:-{ctx.frontend_port}}}"

wait_healthy() {{
  local service="$1" timeout_seconds="$2" started container status
  started="$(date +%s)"
  while true; do
    container="$(compose ps -q "$service")"
    status="$("${{DOCKER[@]}}" inspect -f '{{{{if .State.Health}}}}{{{{.State.Health.Status}}}}{{{{else}}}}{{{{.State.Status}}}}{{{{end}}}}' "$container" 2>/dev/null || true)"
    [[ "$status" == "healthy" ]] && return 0
    [[ "$status" == "unhealthy" || "$status" == "exited" || "$status" == "dead" ]] && fail "$service entered state $status"
    (( $(date +%s) - started < timeout_seconds )) || fail "$service health timeout"
    sleep 5
  done
}}

log "stopping baseline backend to establish a quiet migration boundary"
compose stop backend
capture_counts "$BACKUP_DIR/counts-migration-start.txt"
log "recreating migration backend with background scheduling disabled"
PLATFORM_BACKGROUND_JOBS_ENABLED=false compose up -d --no-deps --force-recreate backend
wait_healthy backend 900
BACKEND_ID="$(compose ps -q backend)"
[[ "$("${{DOCKER[@]}}" inspect -f '{{{{range .Config.Env}}}}{{{{println .}}}}{{{{end}}}}' "$BACKEND_ID" | grep '^PLATFORM_BACKGROUND_JOBS_ENABLED=' | tail -n 1)" == "PLATFORM_BACKGROUND_JOBS_ENABLED=false" ]] || fail "background scheduling disabled mode was not applied"
CURRENT_FLYWAY="$(db_query "select version from flyway_schema_history where success order by installed_rank desc limit 1;")"
[[ "$CURRENT_FLYWAY" == "$TARGET_FLYWAY" ]] || fail "Flyway version $CURRENT_FLYWAY does not match target $TARGET_FLYWAY"

capture_counts "$BACKUP_DIR/counts-after-migration.txt"
diff -u "$BACKUP_DIR/counts-migration-start.txt" "$BACKUP_DIR/counts-after-migration.txt" > "$BACKUP_DIR/counts.diff" || fail "protected business row counts changed during migration; see $BACKUP_DIR/counts.diff"
[[ "$(compose ps -q postgres)" == "$POSTGRES_ID" ]] || fail "postgres container changed unexpectedly"

log "recreating backend with normal background scheduling"
PLATFORM_BACKGROUND_JOBS_ENABLED=true compose up -d --no-deps --force-recreate backend
wait_healthy backend 900
BACKEND_ID="$(compose ps -q backend)"
[[ "$("${{DOCKER[@]}}" inspect -f '{{{{range .Config.Env}}}}{{{{println .}}}}{{{{end}}}}' "$BACKEND_ID" | grep '^PLATFORM_BACKGROUND_JOBS_ENABLED=' | tail -n 1)" == "PLATFORM_BACKGROUND_JOBS_ENABLED=true" ]] || fail "normal background scheduling mode was not restored"

log "recreating frontend after backend migration and health verification"
compose up -d --no-deps --force-recreate frontend
wait_healthy frontend 300
curl -fsS "http://127.0.0.1:${{BACKEND_HEALTH_PORT}}/actuator/health" > "$BACKUP_DIR/backend-health.json"
curl -fsS "http://127.0.0.1:${{FRONTEND_HEALTH_PORT}}/" > /dev/null
printf '%s\n' "$BACKUP_DIR" > "$TARGET_DIR/upgrade-backups/latest-backup.txt"

log "upgrade completed; backup: $BACKUP_DIR"
log "{completion_message}"
"""


def rollback_helper(ctx: BuildContext) -> str:
    return f"""\
#!/usr/bin/env bash
set -euo pipefail

TARGET_DIR="${{1:-$PWD}}"
BACKUP_DIR="${{2:-}}"
EXPECTED_BACKEND="{BACKEND_IMAGE}:{ctx.baseline_backend_tag}"
EXPECTED_FRONTEND="{FRONTEND_IMAGE}:{ctx.baseline_frontend_tag}"
[[ -n "$BACKUP_DIR" ]] || {{ echo "usage: rollback.sh <deployment-dir> <backup-dir>" >&2; exit 2; }}
[[ -f "$BACKUP_DIR/.env" && -f "$BACKUP_DIR/docker-compose.yml" ]] || {{ echo "invalid backup directory: $BACKUP_DIR" >&2; exit 2; }}

fail() {{ echo "[rollback] ERROR: $*" >&2; exit 1; }}
log() {{ echo "[rollback] $*"; }}

if docker info >/dev/null 2>&1; then
  DOCKER=(docker)
elif sudo docker info >/dev/null 2>&1; then
  DOCKER=(sudo docker)
else
  fail "Docker is unavailable"
fi

cd "$TARGET_DIR"
compose() {{ "${{DOCKER[@]}}" compose --env-file .env "$@"; }}
POSTGRES_ID="$(compose ps -q postgres)"
[[ -n "$POSTGRES_ID" ]] || fail "postgres service is not running"

[[ -z "$(find . -maxdepth 1 -type f -name 'docker-compose.*.yml' -print -quit)" ]] || fail "refusing layered Compose configuration during rollback"
TEMP_COMPOSE="docker-compose.yml.rollback.tmp"
trap 'rm -f "$TEMP_COMPOSE"' EXIT
cp -a "$BACKUP_DIR/docker-compose.yml" "$TEMP_COMPOSE"
"${{DOCKER[@]}}" compose --env-file .env -f "$TEMP_COMPOSE" config >/dev/null
mv -f "$TEMP_COMPOSE" docker-compose.yml
trap - EXIT

RESTORED_CONFIG="$(compose config)"
grep -Fq "image: $EXPECTED_BACKEND" <<<"$RESTORED_CONFIG" || fail "restored backend image does not match $EXPECTED_BACKEND"
grep -Fq "image: $EXPECTED_FRONTEND" <<<"$RESTORED_CONFIG" || fail "restored frontend image does not match $EXPECTED_FRONTEND"

wait_healthy() {{
  local service="$1" timeout_seconds="$2" started container status
  started="$(date +%s)"
  while true; do
    container="$(compose ps -q "$service")"
    status="$("${{DOCKER[@]}}" inspect -f '{{{{if .State.Health}}}}{{{{.State.Health.Status}}}}{{{{else}}}}{{{{.State.Status}}}}{{{{end}}}}' "$container" 2>/dev/null || true)"
    [[ "$status" == "healthy" ]] && return 0
    [[ "$status" == "unhealthy" || "$status" == "exited" || "$status" == "dead" ]] && fail "$service entered state $status"
    (( $(date +%s) - started < timeout_seconds )) || fail "$service health timeout"
    sleep 5
  done
}}

log "recreating baseline backend"
compose up -d --no-deps --force-recreate backend
wait_healthy backend 900
[[ "$(compose ps -q postgres)" == "$POSTGRES_ID" ]] || fail "postgres container changed unexpectedly"

log "recreating baseline frontend after backend health verification"
compose up -d --no-deps --force-recreate frontend
wait_healthy frontend 300
compose ps
log "application configuration and images restored; both services are healthy"
log "database was not rewritten; Flyway is forward-only. Use database.dump only through an approved database restore procedure."
"""


def write_operational_files(ctx: BuildContext) -> None:
    if ctx.mode == "fresh-empty":
        write_text(ctx.package_dir / ".env", env_content(ctx))
        write_text(ctx.package_dir / "docker-compose.yml", compose_content(ctx))
        write_text(ctx.package_dir / "README-INTRANET-DEPLOY.md", fresh_readme(ctx))
    else:
        write_text(ctx.package_dir / "README-INCREMENTAL-DEPLOY.md", incremental_readme(ctx))
        write_text(
            ctx.package_dir / "docker-compose.yml",
            compose_content(ctx, external_postgres_volume=True),
        )
        write_text(ctx.package_dir / "backup.sh", backup_helper(ctx))
        write_text(ctx.package_dir / "upgrade.sh", upgrade_helper(ctx))
        write_text(ctx.package_dir / "rollback.sh", rollback_helper(ctx))


def write_release_manifest(ctx: BuildContext, backend_fallback_used: bool, backend_build_note: str) -> None:
    """Write the machine-readable release identity and artifact evidence."""
    _, build_time = now_stamp()
    image_dir = ctx.package_dir / "docker-images"
    backend_archive = image_dir / f"{BACKEND_IMAGE}_{ctx.backend_tag}.tar"
    frontend_archive = image_dir / f"{FRONTEND_IMAGE}_{ctx.frontend_tag}.tar"
    manifest = {
        "schemaVersion": 1,
        "package": {
            "id": ctx.release_id,
            "name": ctx.package_name,
            "type": "fresh-empty" if ctx.mode == "fresh-empty" else "incremental-update",
            "archive": ctx.archive_path.name,
            "builtAt": build_time,
        },
        "source": {
            "branch": ctx.branch,
            "commit": ctx.commit,
            "workspaceState": ctx.dirty_state,
            "backendJarSha256": file_sha256(BACKEND_JAR),
            "frontendDistSha256": directory_sha256(FRONTEND_DIST),
        },
        "target": {
            "os": "ubuntu-24.04-amd64",
            "offline": True,
            "flywayVersion": ctx.expected_flyway_version,
            "images": {
                "backend": {
                    "reference": f"{BACKEND_IMAGE}:{ctx.backend_tag}",
                    "archive": backend_archive.name,
                    "sha256": file_sha256(backend_archive),
                },
                "frontend": {
                    "reference": f"{FRONTEND_IMAGE}:{ctx.frontend_tag}",
                    "archive": frontend_archive.name,
                    "sha256": file_sha256(frontend_archive),
                },
            },
        },
        "baseline": None if ctx.mode == "fresh-empty" else {
            "deployment": ctx.baseline_name,
            "backendImage": f"{BACKEND_IMAGE}:{ctx.baseline_backend_tag}",
            "frontendImage": f"{FRONTEND_IMAGE}:{ctx.baseline_frontend_tag}",
        },
        "facts": {
            "rebuildRequired": ctx.require_fact_rebuild,
            "scope": ctx.fact_rebuild_scope if ctx.require_fact_rebuild else None,
        },
        "compose": {
            "entrypoint": "docker-compose.yml",
            "model": "single-authoritative-file",
            "environmentPreserved": ctx.mode == "incremental-update",
            **({
                "defaultProjectName": default_compose_project_name(ctx),
                "hostPorts": {
                    "frontend": ctx.frontend_port,
                    "backend": ctx.backend_port,
                    "postgres": ctx.postgres_port,
                },
            } if ctx.mode == "fresh-empty" else {}),
        },
        "preDeploymentBackup": None if ctx.mode == "fresh-empty" else {
            "required": True,
            "entrypoint": "backup.sh",
            "fullDatabaseDump": "database.dump",
            "criticalTablesDump": "critical-tables.dump",
        },
        "build": {
            "backendTestSourceFallbackUsed": backend_fallback_used,
            "backendBuildNote": backend_build_note,
        },
    }
    write_text(
        ctx.package_dir / "RELEASE-MANIFEST.json",
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
    )


def build_and_save_images(ctx: BuildContext, args: argparse.Namespace) -> None:
    backend_ref = f"{BACKEND_IMAGE}:{ctx.backend_tag}"
    frontend_ref = f"{FRONTEND_IMAGE}:{ctx.frontend_tag}"

    if not args.skip_docker_build:
        cache_arg = [] if args.docker_cache else ["--no-cache"]
        ctx.deploy_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="qaflex-image-build-", dir=ctx.deploy_root) as temporary:
            build_root = Path(temporary)
            prepare_image_build_contexts(build_root)
            run(("docker", "build", *cache_arg, "-t", backend_ref, build_root / "backend"))
            run(("docker", "build", *cache_arg, "-t", frontend_ref, build_root / "frontend"))
    else:
        log("skip docker build by parameter; existing local images will be saved")

    image_dir = ctx.package_dir / "docker-images"
    save_jobs: list[tuple[str, tuple[str, ...]]] = []
    save_jobs.append(("backend image", ("docker", "save", backend_ref, "-o", str(image_dir / f"{BACKEND_IMAGE}_{ctx.backend_tag}.tar"))))
    save_jobs.append(("frontend image", ("docker", "save", frontend_ref, "-o", str(image_dir / f"{FRONTEND_IMAGE}_{ctx.frontend_tag}.tar"))))

    if args.serial_docker_save:
        for _name, command in save_jobs:
            run(command, cwd=ctx.package_dir)
    else:
        with concurrent.futures.ThreadPoolExecutor(max_workers=min(3, len(save_jobs))) as executor:
            futures = {executor.submit(run, command, cwd=ctx.package_dir): name for name, command in save_jobs}
            for future in concurrent.futures.as_completed(futures):
                future.result()

    local_hash = file_sha256(BACKEND_JAR)
    result = run(("docker", "run", "--rm", "--entrypoint", "sha256sum", backend_ref, "/app/app.jar"), capture=True)
    image_hash = result.output.split()[0].lower()
    if image_hash != local_hash:
        fail(f"backend image jar hash mismatch: local={local_hash} image={image_hash}")
    log(f"backend image jar hash verified: {local_hash}")

    pg_dump_result = run(("docker", "run", "--rm", "--entrypoint", "pg_dump", backend_ref, "--version"), capture=True)
    pg_dump_version = pg_dump_result.output.strip()
    if not pg_dump_version.startswith("pg_dump (PostgreSQL) 16."):
        fail(f"backend image pg_dump version unexpected: {pg_dump_version}")
    log(f"backend image pg_dump verified: {pg_dump_version}")

    frontend_index_hash = file_sha256(FRONTEND_DIST / "index.html")
    result = run(
        ("docker", "run", "--rm", "--entrypoint", "sha256sum", frontend_ref, "/usr/share/nginx/html/index.html"),
        capture=True,
    )
    image_index_hash = result.output.split()[0].lower()
    if image_index_hash != frontend_index_hash:
        fail(f"frontend image index hash mismatch: local={frontend_index_hash} image={image_index_hash}")
    log(f"frontend image index hash verified: {frontend_index_hash}")


def scan_empty_package(ctx: BuildContext) -> None:
    forbidden_patterns = (
        "postgres-data",
        "pg_wal",
        "/base/",
        "\\base\\",
        ".sql",
        ".sql.gz",
        "data_full",
        "data_new",
        "issue_fact",
        "merge_request_fact",
        "qaflex_pgdata",
        "dump",
        "backup",
    )
    offenders: list[Path] = []
    for path in ctx.package_dir.rglob("*"):
        rel = path.relative_to(ctx.package_dir).as_posix()
        lowered = rel.lower()
        if ctx.mode == "incremental-update" and lowered == "backup.sh":
            continue
        if any(pattern.lower() in lowered for pattern in forbidden_patterns):
            # docker image tarballs and offline deb metadata are allowed; SQL/data files are not.
            if lowered.startswith("docker-images/") and lowered.endswith(".tar"):
                continue
            offenders.append(path)
    if offenders:
        formatted = "\n".join(str(path) for path in offenders[:20])
        fail(f"empty package scan found forbidden runtime/data files:\n{formatted}")


def required_files(ctx: BuildContext) -> list[Path]:
    files = [
        ctx.package_dir / "docker-images" / f"{BACKEND_IMAGE}_{ctx.backend_tag}.tar",
        ctx.package_dir / "docker-images" / f"{FRONTEND_IMAGE}_{ctx.frontend_tag}.tar",
        ctx.package_dir / "RELEASE-MANIFEST.json",
    ]
    if ctx.mode == "fresh-empty":
        files.extend(
            [
                ctx.package_dir / ".env",
                ctx.package_dir / "docker-compose.yml",
                ctx.package_dir / "README-INTRANET-DEPLOY.md",
            ]
        )
        if ctx.include_offline_docker_debs:
            files.append(ctx.package_dir / "offline-debs" / "ubuntu-24.04-amd64")
    else:
        files.extend(
            [
                ctx.package_dir / "README-INCREMENTAL-DEPLOY.md",
                ctx.package_dir / "docker-compose.yml",
                ctx.package_dir / "backup.sh",
                ctx.package_dir / "upgrade.sh",
                ctx.package_dir / "rollback.sh",
            ]
        )
    return files


def validate_forbidden_delivery_items(ctx: BuildContext) -> None:
    """Reject build contexts, duplicate metadata, secrets, the database image and mode-specific payloads."""
    forbidden = [
        ctx.package_dir / "backend",
        ctx.package_dir / "frontend",
        ctx.package_dir / ".dockerignore",
        ctx.package_dir / "VERSION.txt",
        ctx.package_dir / "docker-images" / "postgres_16-alpine.tar",
    ]
    if ctx.mode == "incremental-update":
        forbidden.extend(
            [
                ctx.package_dir / ".env",
                ctx.package_dir / "offline-debs",
            ]
        )
    offenders = [path.relative_to(ctx.package_dir).as_posix() for path in forbidden if path.exists()]
    if offenders:
        fail("package contains forbidden delivery items: " + ", ".join(offenders))


def validate_layout(ctx: BuildContext) -> None:
    for path in required_files(ctx):
        require_path(path, f"required package item {path.relative_to(ctx.package_dir)}")
    validate_forbidden_delivery_items(ctx)
    if ctx.mode == "fresh-empty":
        run(("docker", "compose", "--env-file", ".env", "config"), cwd=ctx.package_dir)
    else:
        compose_env = os.environ.copy()
        compose_env.update(
            {
                "COMPOSE_PROJECT_NAME": "qaflex-package-validation",
                "POSTGRES_VOLUME_NAME": "qaflex-package-validation-pgdata",
                "BACKEND_LOG_VOLUME_NAME": "qaflex-package-validation-backend-logs",
            }
        )
        run(
            (
                "docker",
                "compose",
                "-f",
                ctx.package_dir / "docker-compose.yml",
                "config",
            ),
            cwd=ctx.package_dir,
            env=compose_env,
        )
    scan_empty_package(ctx)


def write_sha256s(ctx: BuildContext) -> None:
    sha_path = ctx.package_dir / "SHA256SUMS.txt"
    remove_if_exists(sha_path)
    lines: list[str] = []
    for path in list_files(ctx.package_dir):
        if path == sha_path:
            continue
        rel = path.relative_to(ctx.package_dir).as_posix()
        lines.append(f"{file_sha256(path)}  {rel}")
    write_text(sha_path, "\n".join(lines) + "\n")

    for line in sha_path.read_text(encoding="utf-8").splitlines():
        expected, rel = line.split(None, 1)
        actual = file_sha256(ctx.package_dir / rel.strip())
        if actual != expected:
            fail(f"SHA256SUMS mismatch for {rel}")
    log(f"SHA256SUMS verify ok ({len(lines)} files)")


def create_archive(ctx: BuildContext, args: argparse.Namespace) -> None:
    if args.skip_archive:
        log("skip archive by parameter")
        return
    if ctx.archive_path.exists():
        fail(f"archive already exists: {ctx.archive_path}")

    # Native tar is substantially faster than Python gzip tar on Windows.
    run(("tar", "-czf", ctx.archive_path, "-C", ctx.deploy_root, ctx.package_name))
    archive_hash = file_sha256(ctx.archive_path)
    write_text(ctx.archive_path.with_suffix(ctx.archive_path.suffix + ".sha256"), f"{archive_hash}  {ctx.archive_path.name}\n")
    listing = run(("tar", "-tzf", ctx.archive_path), capture=True)
    if f"{ctx.package_name}/RELEASE-MANIFEST.json" not in listing.output:
        fail("archive listing does not contain RELEASE-MANIFEST.json")
    for forbidden in ("/backend/", "/frontend/", "/.dockerignore", "/VERSION.txt"):
        if forbidden in listing.output:
            fail(f"archive listing contains obsolete delivery item: {forbidden}")
    log(f"archive: {ctx.archive_path}")
    log(f"archive sha256: {archive_hash}")


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("fresh-empty", "incremental-update"), required=True)
    parser.add_argument("--deploy-root", type=Path, default=DEFAULT_DEPLOY_ROOT)
    parser.add_argument(
        "--baseline-dir",
        type=Path,
        help="current deployment directory or the immediately preceding update package directory",
    )
    parser.add_argument("--template-package-dir", type=Path)
    parser.add_argument(
        "--include-offline-docker-debs",
        action="store_true",
        help="include Ubuntu 24.04 Docker/Compose debs in a fresh package from the template directory",
    )
    parser.add_argument("--require-fact-rebuild", action="store_true")
    parser.add_argument("--fact-rebuild-scope", choices=("issue", "merge-request", "all"), default="all")
    parser.add_argument("--frontend-port", type=int, default=18181)
    parser.add_argument("--backend-port", type=int, default=18080)
    parser.add_argument("--postgres-port", type=int, default=15432)
    parser.add_argument(
        "--ldap-base-url",
        default="http://172.22.10.116:80",
        help="LDAP platform HTTP base URL reachable from the backend container",
    )
    parser.add_argument("--working", action="store_true", help="force -working suffix even if git status is clean")
    parser.add_argument("--skip-frontend-release-tests", action="store_true")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--skip-docker-build", action="store_true")
    parser.add_argument("--skip-archive", action="store_true")
    parser.add_argument("--plan-only", action="store_true", help="print resolved package plan without building or writing files")
    parser.add_argument("--docker-cache", action="store_true", help="allow Docker layer cache; default uses --no-cache")
    parser.add_argument("--serial-docker-save", action="store_true", help="save image tar files one at a time")
    parser.add_argument(
        "--allow-backend-test-source-skip",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="fall back to -Dmaven.test.skip=true if -DskipTests package is blocked by stale test sources",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str]) -> int:
    args = parse_args(argv)
    try:
        require_path(REPO_ROOT / "AGENTS.md", "AGENTS.md")
        require_path(REPO_ROOT / "deploy" / "intranet-offline-packaging-standard.md", "packaging standard")
        ctx = resolve_context(args)
        log(f"mode: {ctx.mode}")
        log(f"package: {ctx.package_name}")
        log(f"backend tag: {ctx.backend_tag}")
        log(f"frontend tag: {ctx.frontend_tag}")
        if args.plan_only:
            log(f"deploy root: {ctx.deploy_root}")
            log(f"package dir: {ctx.package_dir}")
            log(f"archive: {ctx.archive_path}")
            if ctx.mode == "fresh-empty":
                log(f"compose project: {default_compose_project_name(ctx)}")
                log(
                    "host ports: "
                    f"frontend={ctx.frontend_port}, backend={ctx.backend_port}, postgres={ctx.postgres_port}"
                )
            if ctx.template_dir is not None:
                log(f"template dir: {ctx.template_dir}")
            if ctx.baseline_name:
                log(f"baseline: {ctx.baseline_name}")
                log(f"baseline backend: {BACKEND_IMAGE}:{ctx.baseline_backend_tag}")
                log(f"baseline frontend: {FRONTEND_IMAGE}:{ctx.baseline_frontend_tag}")
            return 0

        backend_fallback_used, backend_build_note = build_products(args)
        initialize_layout(ctx)
        if ctx.mode == "fresh-empty" and ctx.include_offline_docker_debs:
            copy_offline_debs(ctx)
        write_operational_files(ctx)
        build_and_save_images(ctx, args)
        write_release_manifest(ctx, backend_fallback_used, backend_build_note)
        validate_layout(ctx)
        write_sha256s(ctx)
        create_archive(ctx, args)
        log(f"done: {ctx.package_dir}")
        return 0
    except PackageError as exc:
        print(f"[pack] ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
