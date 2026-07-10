#!/usr/bin/env python3
"""Build QA Flex Platform intranet offline deployment packages.

This script replaces the old PowerShell packager.  It intentionally uses
argument-list subprocess calls instead of shell strings so Maven -D arguments,
Docker paths and Windows quoting behave the same every time.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tarfile
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DEPLOY_ROOT = Path(r"D:\projects\data_collection_platform_deploy")
JAVA_HOME = REPO_ROOT / "tools" / "jdk" / "jdk-21.0.10+7"
MAVEN_HOME = REPO_ROOT / "tools" / "maven" / "apache-maven-3.9.9"
BACKEND_JAR = REPO_ROOT / "backend" / "target" / "qa-flex-platform-backend-0.0.1-SNAPSHOT.jar"
FRONTEND_DIST = REPO_ROOT / "frontend" / "dist"
PACKAGE_PREFIX = "qa-flex-platform-intranet"
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
    release_label: str
    deploy_root: Path
    package_name: str
    package_dir: Path
    archive_path: Path
    date_stamp: str
    branch: str
    commit: str
    short_sha: str
    dirty_state: str
    backend_tag: str
    frontend_tag: str
    baseline_name: str
    template_dir: Path | None
    require_fact_rebuild: bool
    fact_rebuild_scope: str


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
        and path.name.startswith(f"{PACKAGE_PREFIX}-")
        and "-runnable-" in path.name
        and (path / "offline-debs").exists()
        and (path / "docker-compose.yml").exists()
    ]
    if not candidates:
        fail(f"no full package template with offline-debs found in {deploy_root}")
    return max(candidates, key=lambda item: item.stat().st_mtime)


def read_compose_image_tag(compose_path: Path, image: str) -> str:
    require_path(compose_path, "baseline docker-compose.yml")
    text = compose_path.read_text(encoding="utf-8")
    match = re.search(re.escape(image) + r":(?P<tag>[A-Za-z0-9_.-]+)", text)
    if not match:
        fail(f"cannot find image tag for {image} in {compose_path}")
    return match.group("tag")


def clean_label(label: str) -> str:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", label):
        fail(f"release label may only contain letters, numbers, dot, underscore and hyphen: {label}")
    return label


def resolve_context(args: argparse.Namespace) -> BuildContext:
    date_stamp, _ = now_stamp()
    branch = git_value("rev-parse", "--abbrev-ref", "HEAD") or "unknown"
    commit = git_value("rev-parse", "HEAD") or "unknown"
    short_sha = git_value("rev-parse", "--short=8", "HEAD") or "nogit"
    status = git_value("status", "--short")
    dirty = bool(status.strip()) or args.working
    dirty_suffix = "-working" if dirty else ""
    dirty_state = "working tree contains uncommitted changes or --working was specified" if dirty else "clean"

    deploy_root = args.deploy_root.resolve()
    deploy_root.mkdir(parents=True, exist_ok=True)
    image_tag = f"{date_stamp}-{short_sha}{dirty_suffix}"

    if args.mode == "fresh-empty":
        release_label = clean_label(args.release_label or f"empty-{short_sha}{dirty_suffix}")
        package_name = f"{PACKAGE_PREFIX}-{date_stamp}-runnable-{release_label}"
        backend_tag = image_tag
        frontend_tag = image_tag
        baseline_name = ""
    else:
        release_label = clean_label(args.release_label or f"{short_sha}{dirty_suffix}")
        suffix = "-fact-rebuild" if args.require_fact_rebuild else ""
        package_name = f"{PACKAGE_PREFIX}-{date_stamp}-incremental-images-{release_label}{suffix}"
        baseline_dir = args.baseline_deploy_dir
        if baseline_dir is None:
            baselines = [
                path
                for path in deploy_root.iterdir()
                if path.is_dir()
                and path.name.startswith(f"{PACKAGE_PREFIX}-")
                and "-runnable-" in path.name
                and (path / "docker-compose.yml").exists()
            ]
            if not baselines:
                fail("--baseline-deploy-dir was not provided and no runnable package directory was found")
            baseline_dir = max(baselines, key=lambda item: item.stat().st_mtime)
        baseline_dir = baseline_dir.resolve()
        backend_tag = read_compose_image_tag(baseline_dir / "docker-compose.yml", BACKEND_IMAGE)
        frontend_tag = read_compose_image_tag(baseline_dir / "docker-compose.yml", FRONTEND_IMAGE)
        baseline_name = baseline_dir.name

    package_dir = deploy_root / package_name
    archive_path = deploy_root / f"{package_name}-ubuntu2404-offline.tar.gz"
    template_dir = args.template_package_dir.resolve() if args.template_package_dir else None
    if template_dir is None and args.mode == "fresh-empty":
        template_dir = latest_full_template(deploy_root)

    return BuildContext(
        mode=args.mode,
        release_label=release_label,
        deploy_root=deploy_root,
        package_name=package_name,
        package_dir=package_dir,
        archive_path=archive_path,
        date_stamp=date_stamp,
        branch=branch,
        commit=commit,
        short_sha=short_sha,
        dirty_state=dirty_state,
        backend_tag=backend_tag,
        frontend_tag=frontend_tag,
        baseline_name=baseline_name,
        template_dir=template_dir,
        require_fact_rebuild=args.require_fact_rebuild,
        fact_rebuild_scope=args.fact_rebuild_scope,
    )


def build_products(args: argparse.Namespace) -> tuple[bool, str]:
    if args.skip_build:
        log("skip frontend/backend build by parameter")
        return False, "build skipped by parameter"

    env = package_env()
    frontend_dir = REPO_ROOT / "frontend"

    frontend_jobs: list[tuple[str, Sequence[str | Path], Path]] = []
    if not args.skip_frontend_release_tests:
        frontend_jobs.append(
            (
                "frontend release tests",
                ("npm.cmd", "run", "test", "--", "feature-manifest-access.test.ts", "ux-interaction-regressions.test.ts"),
                frontend_dir,
            )
        )
    frontend_jobs.append(("frontend production build", ("npm.cmd", "run", "build"), frontend_dir))

    # The tests and production build are independent in this project and can run
    # concurrently, which trims packaging time without changing output files.
    if len(frontend_jobs) == 1:
        name, command, cwd = frontend_jobs[0]
        log(name)
        run(command, cwd=cwd, env=env)
    else:
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            futures = {
                executor.submit(run, command, cwd=cwd, env=env): name
                for name, command, cwd in frontend_jobs
            }
            for future in concurrent.futures.as_completed(futures):
                future.result()

    strict_args = (
        MAVEN_HOME / "bin" / "mvn.cmd",
        "-q",
        "-f",
        REPO_ROOT / "backend" / "pom.xml",
        "-DskipTests",
        "package",
    )
    strict = run(strict_args, env=env, capture=True, check=False)
    if strict.returncode == 0:
        return False, "backend jar built with -DskipTests package"

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
        "package",
    )
    run(fallback_args, env=env)
    return True, "backend jar built with -Dmaven.test.skip=true after -DskipTests testCompile blocker"


def backend_dockerfile() -> str:
    return """\
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY app.jar /app/app.jar
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


def dockerignore() -> str:
    return """\
backend/target/
frontend/node_modules/
frontend/dist/
.git/
.tmp/
.tmp-logs/
logs/
"""


def env_content() -> str:
    return """\
# Platform URL reachable by users and GitLab system hook.
PLATFORM_PUBLIC_BASE_URL=http://172.22.10.115:18181

# GitLab web URL for links shown in the platform.
# GitLab PostgreSQL source connection is configured later in the UI.
GITLAB_WEB_BASE_URL=http://172.22.10.233

# Built-in platform database. This is not the GitLab source database.
POSTGRES_USER=qaflex
POSTGRES_PASSWORD=qaflex
POSTGRES_DB=qaflex
POSTGRES_PORT=15432
POSTGRES_BIND=127.0.0.1

# Platform ports.
FRONTEND_PORT=18181
FRONTEND_BIND=0.0.0.0
BACKEND_PORT=18080
BACKEND_BIND=127.0.0.1

# Platform login accounts initialized into the empty built-in database.
PLATFORM_ADMIN_USERNAME=admin
PLATFORM_ADMIN_PASSWORD=admin123
PLATFORM_APPROVAL_USERNAME=approval
PLATFORM_APPROVAL_PASSWORD=approval123

# Runtime options.
GITLAB_SYSTEM_HOOK_MAX_QUEUE_SIZE=1000
GITLAB_MAX_SYNC_THREADS=16
PLATFORM_QUERY_TIMEOUT_SECONDS=30
PLATFORM_SLOW_QUERY_THRESHOLD_MS=1000
REVIEW_DATA_SEARCH_INDEX_BACKFILL_ENABLED=false
CUSTOMER_ISSUE_DELAY_LABEL_WRITEBACK_API_ENABLED=false
"""


def compose_content(backend_tag: str, frontend_tag: str) -> str:
    return f"""\
services:
  postgres:
    image: postgres:16-alpine
    container_name: qaflex-postgres
    restart: unless-stopped
    environment:
      POSTGRES_USER: ${{POSTGRES_USER}}
      POSTGRES_PASSWORD: ${{POSTGRES_PASSWORD}}
      POSTGRES_DB: ${{POSTGRES_DB}}
      TZ: Asia/Shanghai
    ports:
      - "${{POSTGRES_BIND:-127.0.0.1}}:${{POSTGRES_PORT:-15432}}:5432"
    volumes:
      - qaflex_pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${{POSTGRES_USER}} -d ${{POSTGRES_DB}}"]
      interval: 10s
      timeout: 5s
      retries: 12

  backend:
    image: {BACKEND_IMAGE}:{backend_tag}
    container_name: qaflex-backend
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
      PLATFORM_ADMIN_USERNAME: ${{PLATFORM_ADMIN_USERNAME}}
      PLATFORM_ADMIN_PASSWORD: ${{PLATFORM_ADMIN_PASSWORD}}
      PLATFORM_APPROVAL_USERNAME: ${{PLATFORM_APPROVAL_USERNAME}}
      PLATFORM_APPROVAL_PASSWORD: ${{PLATFORM_APPROVAL_PASSWORD}}
      PLATFORM_SECURE_CONFIG_REQUIRED: "false"
      PLATFORM_AUTH_CSRF_ENABLED: "false"
      GITLAB_WEB_BASE_URL: ${{GITLAB_WEB_BASE_URL}}
      GITLAB_SYSTEM_HOOK_BASE_URL: ${{PLATFORM_PUBLIC_BASE_URL}}/api/gitlab-sync/system-hook
      GITLAB_SYSTEM_HOOK_MAX_QUEUE_SIZE: ${{GITLAB_SYSTEM_HOOK_MAX_QUEUE_SIZE:-1000}}
      GITLAB_MAX_SYNC_THREADS: ${{GITLAB_MAX_SYNC_THREADS:-16}}
      PLATFORM_QUERY_TIMEOUT_SECONDS: ${{PLATFORM_QUERY_TIMEOUT_SECONDS:-30}}
      PLATFORM_SLOW_QUERY_THRESHOLD_MS: ${{PLATFORM_SLOW_QUERY_THRESHOLD_MS:-1000}}
      REVIEW_DATA_SEARCH_INDEX_BACKFILL_ENABLED: ${{REVIEW_DATA_SEARCH_INDEX_BACKFILL_ENABLED:-false}}
      CUSTOMER_ISSUE_DELAY_LABEL_WRITEBACK_API_ENABLED: ${{CUSTOMER_ISSUE_DELAY_LABEL_WRITEBACK_API_ENABLED:-false}}
    ports:
      - "${{BACKEND_BIND:-127.0.0.1}}:${{BACKEND_PORT:-18080}}:18080"
    volumes:
      - qaflex_backend_logs:/app/logs
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:18080/actuator/health >/dev/null || exit 1"]
      interval: 20s
      timeout: 5s
      retries: 12
      start_period: 60s

  frontend:
    image: {FRONTEND_IMAGE}:{frontend_tag}
    container_name: qaflex-frontend
    restart: unless-stopped
    depends_on:
      backend:
        condition: service_healthy
    ports:
      - "${{FRONTEND_BIND:-0.0.0.0}}:${{FRONTEND_PORT:-18181}}:80"
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://127.0.0.1/ >/dev/null || exit 1"]
      interval: 20s
      timeout: 5s
      retries: 12

volumes:
  qaflex_pgdata:
  qaflex_backend_logs:
"""


def initialize_layout(ctx: BuildContext) -> None:
    if ctx.package_dir.exists():
        fail(f"package directory already exists: {ctx.package_dir}")
    if ctx.archive_path.exists():
        fail(f"archive already exists: {ctx.archive_path}")
    for relative in ("backend", "frontend", "docker-images"):
        (ctx.package_dir / relative).mkdir(parents=True, exist_ok=True)


def copy_artifacts(ctx: BuildContext) -> None:
    require_path(BACKEND_JAR, "backend jar")
    require_path(FRONTEND_DIST, "frontend dist")
    shutil.copy2(BACKEND_JAR, ctx.package_dir / "backend" / "app.jar")
    write_text(ctx.package_dir / "backend" / "Dockerfile", backend_dockerfile())
    copy_tree(FRONTEND_DIST, ctx.package_dir / "frontend" / "dist")
    write_text(ctx.package_dir / "frontend" / "Dockerfile", frontend_dockerfile())
    write_text(ctx.package_dir / "frontend" / "nginx-default.conf", nginx_config())
    write_text(ctx.package_dir / ".dockerignore", dockerignore())


def copy_offline_debs(ctx: BuildContext) -> None:
    if ctx.template_dir is None:
        fail("fresh-empty package requires a template package directory with offline-debs")
    source = ctx.template_dir / "offline-debs"
    require_path(source, "offline-debs template")
    copy_tree(source, ctx.package_dir / "offline-debs")


def fresh_readme(ctx: BuildContext) -> str:
    return f"""\
# QA Flex Platform 内网离线部署包

本包是 **全新空数据部署包**，适用于 Ubuntu 24.04 amd64 无公网服务器。

请不要把本包当作保留旧平台数据库的普通增量包使用。只有新服务器首次部署、明确清空环境、灾难恢复或业务方批准重建时才使用本包。

## 固定地址

- 平台访问地址：http://172.22.10.115:18181
- GitLab Web 地址：http://172.22.10.233

包内 PostgreSQL 只作为平台内置库。GitLab PostgreSQL、老平台 MySQL、老平台 MongoDB 均在平台 UI 中配置，不要写入 DATASOURCE_URL。

## 1. 解压

```bash
tar -xzf {ctx.package_name}-ubuntu2404-offline.tar.gz
cd {ctx.package_name}
```

## 2. 安装离线 Docker 依赖

目标机已安装 Docker / Compose 时可跳过本步骤。

```bash
sudo dpkg -i offline-debs/ubuntu-24.04-amd64/*.deb || sudo apt-get -f install
sudo systemctl enable --now docker
sudo docker version
sudo docker compose version
```

## 3. 加载镜像

```bash
sudo docker load -i docker-images/postgres_16-alpine.tar
sudo docker load -i docker-images/{BACKEND_IMAGE}_{ctx.backend_tag}.tar
sudo docker load -i docker-images/{FRONTEND_IMAGE}_{ctx.frontend_tag}.tar
```

## 4. 准备环境变量

```bash
cp .env.example .env
vi .env
```

默认 .env 已写入当前内网地址、端口和本地账号。按需只修改端口、绑定地址或密码，不要把 GitLab / MySQL / MongoDB 源库连接写进平台库变量。

## 5. 全新空数据部署

```bash
sudo docker compose --env-file .env up -d --force-recreate postgres backend frontend
sudo docker compose --env-file .env ps
```

如果是在测试机上替换旧的 qaflex-* 容器，并且业务方确认要清空平台库，先删除旧容器和旧 volume，再启动本包。删除 volume 会清空平台数据，请确认后再执行。

```bash
sudo docker rm -f qaflex-frontend qaflex-backend qaflex-postgres
sudo docker volume ls | grep qaflex
# 仅在确认清空旧平台数据时执行：
# sudo docker volume rm <approved-qaflex-volume-name>

sudo docker compose --env-file .env up -d --force-recreate postgres backend frontend
sudo docker compose --env-file .env ps
```

## 6. 健康检查

```bash
curl -fsS http://127.0.0.1:18080/actuator/health
curl -fsS http://127.0.0.1:18181/
sudo docker compose --env-file .env logs --tail=120 backend
sudo docker compose --env-file .env logs --tail=120 frontend
```

浏览器访问：http://172.22.10.115:18181

默认登录账号：

- 管理员：admin / admin123
- 审批用户：approval / approval123

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
## 4. 事实层重建

本更新包标记为需要事实层重建。该步骤只基于现有镜像表重建事实层和统计快照，不重新全量同步 GitLab，不删除平台数据。

登录并保存 Cookie：

```bash
curl -c /tmp/qaflex-cookie.txt \\
  -H 'Content-Type: application/json' \\
  -d '{{"username":"admin","password":"admin123"}}' \\
  http://127.0.0.1:18181/api/auth/login
```

触发事实层重建：

```bash
curl -b /tmp/qaflex-cookie.txt -X POST \\
  'http://127.0.0.1:18181/api/facts/rebuild?scope={ctx.fact_rebuild_scope}&full=true'
```
"""
    else:
        fact_section = """\
## 4. 不执行事实层重建

本更新包只替换后端和前端业务镜像，不改变事实表、统计口径或历史聚合结果。部署后不要主动触发事实重建、全量同步或清空快照。
"""

    return f"""\
# QA Flex Platform 前后端同容器更新包

本包用于既有内网实例的同容器增量更新，只替换后端和前端业务镜像。

目标基线部署目录：

```text
{ctx.baseline_name}
```

## 禁止操作

- 不要执行 `docker compose down -v`。
- 不要删除 `qaflex_pgdata` 或任何 PostgreSQL volume。
- 不要重新加载、重建或替换 postgres 镜像/容器。
- 不要清空镜像表、事实表、同步状态、用户、页面设置或平台配置。
- 不要在内网服务器执行 `docker build`。
- 不要因为本包部署而触发 GitLab 全量同步。

## 1. 解压

将本包放到既有部署目录旁边并解压：

```bash
tar -xzf {ctx.package_name}-ubuntu2404-offline.tar.gz
```

## 2. 加载前后端业务镜像

进入既有部署目录：

```bash
cd {ctx.baseline_name}

sudo docker load -i ../{ctx.package_name}/docker-images/{BACKEND_IMAGE}_{ctx.backend_tag}.tar
sudo docker load -i ../{ctx.package_name}/docker-images/{FRONTEND_IMAGE}_{ctx.frontend_tag}.tar
```

本包镜像 tag 复用既有 compose 中的 tag，避免现场修改 docker-compose.yml：

- `{BACKEND_IMAGE}:{ctx.backend_tag}`
- `{FRONTEND_IMAGE}:{ctx.frontend_tag}`

## 3. 只重建后端和前端容器

```bash
sudo docker compose --env-file .env up -d --no-deps --force-recreate backend frontend
sudo docker compose --env-file .env ps
curl -fsS http://127.0.0.1:18080/actuator/health
```

如果这里报 `container name ... is already in use`，先确认当前目录是既有部署目录 `{ctx.baseline_name}`。若目录正确但仍有遗留同名应用容器，只删除前端/后端应用容器后重建，禁止删除 postgres 或任何 volume：

```bash
sudo docker ps -a --filter "name=^/qaflex-backend$" --filter "name=^/qaflex-frontend$"
sudo docker rm -f qaflex-backend qaflex-frontend
sudo docker compose --env-file .env up -d --no-deps --force-recreate backend frontend
sudo docker compose --env-file .env ps
curl -fsS http://127.0.0.1:18080/actuator/health
```

{fact_section}

## 5. 冒烟检查

```bash
curl -fsS http://127.0.0.1:18080/actuator/health
curl -fsS http://127.0.0.1:18181/
sudo docker compose --env-file .env logs --tail=120 backend
sudo docker compose --env-file .env logs --tail=120 frontend
```

## 6. 包完整性校验

```bash
sha256sum -c ../{ctx.package_name}/SHA256SUMS.txt
```
"""


def deploy_helper(ctx: BuildContext) -> str:
    if ctx.require_fact_rebuild:
        fact_block = f"""\
echo "[deploy] fact rebuild is required. Login and trigger it manually after health check:"
echo "curl -c /tmp/qaflex-cookie.txt -H 'Content-Type: application/json' -d '{{\"username\":\"admin\",\"password\":\"admin123\"}}' http://127.0.0.1:18181/api/auth/login"
echo "curl -b /tmp/qaflex-cookie.txt -X POST 'http://127.0.0.1:18181/api/facts/rebuild?scope={ctx.fact_rebuild_scope}&full=true'"
"""
    else:
        fact_block = 'echo "[deploy] fact rebuild is not required for this package."\n'

    return f"""\
#!/usr/bin/env bash
set -euo pipefail

PACKAGE_DIR="{ctx.package_name}"

echo "[deploy] loading backend/frontend images from ../${{PACKAGE_DIR}}"
sudo docker load -i "../${{PACKAGE_DIR}}/docker-images/{BACKEND_IMAGE}_{ctx.backend_tag}.tar"
sudo docker load -i "../${{PACKAGE_DIR}}/docker-images/{FRONTEND_IMAGE}_{ctx.frontend_tag}.tar"

echo "[deploy] recreating backend/frontend only"
sudo docker compose --env-file .env up -d --no-deps --force-recreate backend frontend
sudo docker compose --env-file .env ps

echo "[deploy] backend health"
curl -fsS http://127.0.0.1:18080/actuator/health

{fact_block}"""


def write_metadata(ctx: BuildContext, backend_fallback_used: bool, backend_build_note: str) -> None:
    write_text(ctx.package_dir / ".env.example", env_content())
    write_text(ctx.package_dir / ".env", env_content())
    write_text(ctx.package_dir / "docker-compose.yml", compose_content(ctx.backend_tag, ctx.frontend_tag))

    if ctx.mode == "fresh-empty":
        write_text(ctx.package_dir / "README-INTRANET-DEPLOY.md", fresh_readme(ctx))
    else:
        write_text(ctx.package_dir / "README-INCREMENTAL-DEPLOY.md", incremental_readme(ctx))
        deploy_dir = ctx.package_dir / "deploy"
        deploy_dir.mkdir(parents=True, exist_ok=True)
        helper = deploy_dir / "update-backend-frontend-only.sh"
        write_text(helper, deploy_helper(ctx))

    date_stamp, build_time = now_stamp()
    status = git_value("status", "--short")
    version = f"""\
QA Flex Platform Intranet Offline Package

Package: {ctx.package_name}
Package type: {"empty full deployment package" if ctx.mode == "fresh-empty" else "backend/frontend incremental image package"}
Archive: {ctx.archive_path.name}
Build time: {build_time}
Branch: {ctx.branch}
Commit: {ctx.commit}
Image tags:
- {BACKEND_IMAGE}:{ctx.backend_tag}
- {FRONTEND_IMAGE}:{ctx.frontend_tag}
Target OS: Ubuntu 24.04 amd64, offline intranet
Topology: postgres:16-alpine + qa-flex-platform-backend + qa-flex-platform-frontend
Public URL: http://172.22.10.115:18181
GitLab Web URL: http://172.22.10.233
Facts rebuild: {"required, scope=" + ctx.fact_rebuild_scope if ctx.require_fact_rebuild else "not required"}
Baseline: {ctx.baseline_name or "n/a"}

Build notes:
- {backend_build_note}
- Backend test-source fallback used: {str(backend_fallback_used).lower()}
- Docker backend/frontend build uses --no-cache by default to avoid stale COPY layers.
- Backend image /app/app.jar SHA256 is verified against packaged backend/app.jar.
- Empty package scan rejects postgres-data, pg_wal, SQL dumps and runtime data markers.

Workspace status at packaging time:
{status or "(clean)"}

Standard:
- docs/intranet-offline-packaging-standard.md
"""
    write_text(ctx.package_dir / "VERSION.txt", version)


def build_and_save_images(ctx: BuildContext, args: argparse.Namespace) -> None:
    backend_ref = f"{BACKEND_IMAGE}:{ctx.backend_tag}"
    frontend_ref = f"{FRONTEND_IMAGE}:{ctx.frontend_tag}"

    if not args.skip_docker_build:
        cache_arg = [] if args.docker_cache else ["--no-cache"]
        run(("docker", "build", *cache_arg, "-t", backend_ref, "backend"), cwd=ctx.package_dir)
        run(("docker", "build", *cache_arg, "-t", frontend_ref, "frontend"), cwd=ctx.package_dir)
    else:
        log("skip docker build by parameter; existing local images will be saved")

    image_dir = ctx.package_dir / "docker-images"
    save_jobs: list[tuple[str, tuple[str, ...]]] = []
    if ctx.mode == "fresh-empty":
        run(("docker", "image", "inspect", POSTGRES_IMAGE), cwd=ctx.package_dir)
        save_jobs.append(("postgres image", ("docker", "save", POSTGRES_IMAGE, "-o", str(image_dir / "postgres_16-alpine.tar"))))
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

    local_hash = file_sha256(ctx.package_dir / "backend" / "app.jar")
    result = run(("docker", "run", "--rm", "--entrypoint", "sha256sum", backend_ref, "/app/app.jar"), capture=True)
    image_hash = result.output.split()[0].lower()
    if image_hash != local_hash:
        fail(f"backend image jar hash mismatch: local={local_hash} image={image_hash}")
    log(f"backend image jar hash verified: {local_hash}")


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
        ctx.package_dir / "backend" / "app.jar",
        ctx.package_dir / "backend" / "Dockerfile",
        ctx.package_dir / "frontend" / "dist" / "index.html",
        ctx.package_dir / "frontend" / "Dockerfile",
        ctx.package_dir / "frontend" / "nginx-default.conf",
        ctx.package_dir / "docker-images" / f"{BACKEND_IMAGE}_{ctx.backend_tag}.tar",
        ctx.package_dir / "docker-images" / f"{FRONTEND_IMAGE}_{ctx.frontend_tag}.tar",
        ctx.package_dir / ".env",
        ctx.package_dir / ".env.example",
        ctx.package_dir / "docker-compose.yml",
        ctx.package_dir / "VERSION.txt",
    ]
    if ctx.mode == "fresh-empty":
        files.extend(
            [
                ctx.package_dir / "docker-images" / "postgres_16-alpine.tar",
                ctx.package_dir / "offline-debs" / "ubuntu-24.04-amd64",
                ctx.package_dir / "README-INTRANET-DEPLOY.md",
            ]
        )
    else:
        files.extend(
            [
                ctx.package_dir / "README-INCREMENTAL-DEPLOY.md",
                ctx.package_dir / "deploy" / "update-backend-frontend-only.sh",
            ]
        )
    return files


def validate_layout(ctx: BuildContext) -> None:
    for path in required_files(ctx):
        require_path(path, f"required package item {path.relative_to(ctx.package_dir)}")
    if "proxy_pass http://backend:18080/api/;" not in (ctx.package_dir / "frontend" / "nginx-default.conf").read_text(encoding="utf-8"):
        fail("frontend nginx config does not proxy /api/ to backend:18080")
    run(("docker", "compose", "--env-file", ".env", "config"), cwd=ctx.package_dir)
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
    if f"{ctx.package_name}/backend/app.jar" not in listing.output:
        fail("archive listing does not contain backend/app.jar")
    log(f"archive: {ctx.archive_path}")
    log(f"archive sha256: {archive_hash}")


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("fresh-empty", "incremental-images"), required=True)
    parser.add_argument("--release-label", default="")
    parser.add_argument("--deploy-root", type=Path, default=DEFAULT_DEPLOY_ROOT)
    parser.add_argument("--baseline-deploy-dir", type=Path)
    parser.add_argument("--template-package-dir", type=Path)
    parser.add_argument("--require-fact-rebuild", action="store_true")
    parser.add_argument("--fact-rebuild-scope", choices=("issue", "merge-request", "all"), default="all")
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
        require_path(REPO_ROOT / "docs" / "intranet-offline-packaging-standard.md", "packaging standard")
        ctx = resolve_context(args)
        log(f"mode: {ctx.mode}")
        log(f"package: {ctx.package_name}")
        log(f"backend tag: {ctx.backend_tag}")
        log(f"frontend tag: {ctx.frontend_tag}")
        if args.plan_only:
            log(f"deploy root: {ctx.deploy_root}")
            log(f"package dir: {ctx.package_dir}")
            log(f"archive: {ctx.archive_path}")
            if ctx.template_dir is not None:
                log(f"template dir: {ctx.template_dir}")
            if ctx.baseline_name:
                log(f"baseline: {ctx.baseline_name}")
            return 0

        backend_fallback_used, backend_build_note = build_products(args)
        initialize_layout(ctx)
        copy_artifacts(ctx)
        if ctx.mode == "fresh-empty":
            copy_offline_debs(ctx)
        write_metadata(ctx, backend_fallback_used, backend_build_note)
        build_and_save_images(ctx, args)
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
