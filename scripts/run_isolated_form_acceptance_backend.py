#!/usr/bin/env python3
"""专属库预检与受控后端启动入口（发布验收用）。

先以只读 SQL 核对目标库身份、迁移头、非目标作业开关与外部目标，再启动一个只开放发布 worker 的
后端；启动后扫描日志，确认没有发生非回环地址访问或 Mongo 连接尝试。任何一项不符即停止，
不会出现“先启动再关设置”的顺序。

用法示例：

    python scripts/run_isolated_form_acceptance_backend.py \
        --db-container dcp-n3r-acceptance-pg-20261029 \
        --db-port 15608 --server-port 18094 \
        --jar backend/target/qa-flex-platform-backend-0.0.1-SNAPSHOT.jar \
        --log .tmp-logs/n3r-backend.log \
        --evidence .tmp/w4-n3r-20261029/r04/preflight.json \
        --probe-seconds 20

退出码：0 通过；2 预检失败（后端未启动）；3 启动或启动后核验失败（进程已停止）。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Iterable

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_HEAD = "20261029.02"
LOOPBACK_HOSTS = {"127.0.0.1", "0.0.0.0", "localhost", "::1"}
ADDRESS_PATTERN = re.compile(r"\b(\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})\b")

# 只开放发布 worker：其余调度要么显式关闭，要么把节拍推迟到验收窗口之外。
ISOLATION_PROPERTIES = {
    "platform.background-jobs.enabled": "true",
    "platform.form-publication.worker-enabled": "true",
    "platform.form-publication.worker-delay-ms": "300",
    "platform.gitlab-mirror.scheduler-enabled": "false",
    "platform.gitlab-mirror.delete-reconciliation-enabled": "false",
    "platform.gitlab-mirror.customer-issue-delay-pre-writeback-sync-enabled": "false",
    "platform.gitlab-mirror.customer-issue-delay-writeback-worker-enabled": "false",
    "platform.gitlab-mirror.code-review-metric-enrichment-enabled": "false",
    "platform.review-data.search-index-backfill-enabled": "false",
    "platform.external-api.enabled": "false",
}
DEFERRED_TICK_PROPERTIES = {
    "platform.gitlab-mirror.scheduler-delay-ms": "86400000",
    "platform.gitlab-mirror.run-dispatcher-delay-ms": "86400000",
    "platform.gitlab-mirror.fact-worker-delay-ms": "86400000",
    "platform.gitlab-mirror.system-hook-batch-window-seconds": "86400",
    "platform.gitlab-mirror.customer-issue-delay-check-delay-ms": "86400000",
    "platform.gitlab-mirror.code-review-metric-enrichment-delay-ms": "86400000",
    "platform.gitlab-mirror.code-review-metric-enrichment-initial-delay-ms": "86400000",
    "platform.code-review.match-mode.sync-delay-ms": "86400000",
    "platform.code-review.match-mode.initial-delay-ms": "86400000",
    "platform.code-review.match-mode.mongo-sync-delay-ms": "86400000",
    "platform.code-review.match-mode.mongo-initial-delay-ms": "86400000",
    "platform.code-review.dgm-project-options.scheduler-delay-ms": "86400000",
    "platform.code-review.dgm-project-options.initial-delay-ms": "86400000",
    "platform.bi.cat.scheduler-delay-ms": "86400000",
    "platform.bi.cat.initial-delay-ms": "86400000",
    "platform.backup.scheduler-delay-ms": "86400000",
    "platform.backup.scheduler-initial-delay-ms": "86400000",
}
MONGO_EXCLUDES = (
    "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration,"
    "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration,"
    "org.springframework.boot.autoconfigure.mongo.MongoHealthContributorAutoConfiguration"
)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="专属库预检并只开放发布 worker 启动后端。")
    parser.add_argument("--db-container", required=True, help="专属 PostgreSQL 容器名。")
    parser.add_argument("--db-name", default="qaflex", help="目标库名。")
    parser.add_argument("--db-user", default="qaflex", help="库用户。")
    parser.add_argument("--db-port", type=int, required=True, help="回环映射的宿主机端口。")
    parser.add_argument("--server-port", type=int, required=True, help="后端监听端口。")
    parser.add_argument("--jar", required=True, help="后端可执行 jar 路径。")
    parser.add_argument("--log", required=True, help="后端日志输出路径。")
    parser.add_argument("--evidence", required=True, help="预检/启动证据 JSON 路径。")
    parser.add_argument("--expected-head", default=EXPECTED_HEAD, help="期望 Flyway 迁移头。")
    parser.add_argument("--admin-password", default="admin-w4-acceptance-2026")
    parser.add_argument("--health-timeout", type=int, default=180)
    parser.add_argument("--probe-seconds", type=int, default=20)
    parser.add_argument(
        "--seed-smoke-source",
        action="store_true",
        help="初始化一条禁用状态的冒烟来源配置（回环地址），供发布冒烟绑定使用。",
    )
    parser.add_argument(
        "--smoke-command",
        default="",
        help="启动健康后执行的冒烟命令；输出写入 <log>.smoke.log，退出码计入证据。",
    )
    parser.add_argument(
        "--java",
        default=str(Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java")
        if os.environ.get("JAVA_HOME")
        else "java",
        help="java 可执行文件路径；默认取 JAVA_HOME/bin/java 或 PATH。",
    )
    parser.add_argument(
        "--preflight-only", action="store_true", help="只执行预检，不启动后端。"
    )
    parser.add_argument(
        "--migrate",
        action="store_true",
        help="预检前先以迁移专用进程把专属库升到当前 head（不启动 Web 与业务组件）。",
    )
    return parser.parse_args(argv)


def psql(container: str, user: str, database: str, sql: str) -> str:
    completed = subprocess.run(
        ["docker", "exec", container, "psql", "-U", user, "-d", database, "-tAc", sql],
        capture_output=True,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"psql failed ({completed.returncode}): {completed.stderr.strip()[:400]}"
        )
    return completed.stdout.strip()


def check(name: str, ok: bool, detail: str) -> dict[str, object]:
    return {"name": name, "ok": ok, "detail": detail}


def preflight_failures(checks: Iterable[dict[str, object]]) -> list[str]:
    return [f"{item['name']}: {item['detail']}" for item in checks if not item["ok"]]


def non_loopback_addresses(text: str) -> list[str]:
    found: list[str] = []
    for host, port in ADDRESS_PATTERN.findall(text):
        if host not in LOOPBACK_HOSTS:
            found.append(f"{host}:{port}")
    return sorted(set(found))


def port_is_free(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(1)
        return probe.connect_ex(("127.0.0.1", port)) != 0


def collect_preflight(args: argparse.Namespace) -> list[dict[str, object]]:
    checks: list[dict[str, object]] = []

    running = subprocess.run(
        ["docker", "inspect", "-f", "{{.State.Running}}", args.db_container],
        capture_output=True,
        text=True,
        check=False,
    )
    checks.append(
        check(
            "专属库容器运行",
            running.returncode == 0 and running.stdout.strip() == "true",
            running.stdout.strip() or running.stderr.strip()[:200],
        )
    )

    mapping = subprocess.run(
        ["docker", "port", args.db_container, "5432"],
        capture_output=True,
        text=True,
        check=False,
    )
    checks.append(
        check(
            "专属库端口映射",
            f":{args.db_port}" in mapping.stdout,
            mapping.stdout.strip().replace("\n", " ") or mapping.stderr.strip()[:200],
        )
    )

    def sql(name: str, query: str, expected: str) -> None:
        try:
            actual = psql(args.db_container, args.db_user, args.db_name, query)
        except RuntimeError as error:
            checks.append(check(name, False, str(error)))
            return
        checks.append(check(name, actual == expected, f"actual={actual!r} expected={expected!r}"))

    sql("目标库身份", "select current_database()", args.db_name)
    sql(
        "迁移头",
        "select version from flyway_schema_history where success"
        " order by installed_rank desc limit 1",
        args.expected_head,
    )
    sql(
        "match-mode 关闭",
        "select enabled::text || '|' || sync_enabled::text"
        " from code_review_match_mode_db_settings where id = 1",
        "false|false",
    )
    sql(
        "备份调度关闭",
        "select coalesce((select enabled::text from backup_settings where id = 1), 'false')",
        "false",
    )
    sql(
        "BI CAT 调度关闭",
        "select enabled::text || '|' || auto_sync_enabled::text || '|'"
        " || full_compensation_enabled::text from bi_cat_mirror_configs where id = 1",
        "false|false|false",
    )
    sql(
        "DGM 项目源关闭",
        "select enabled::text from code_review_dgm_gitlab_project_source_settings where id = 1",
        "false",
    )
    sql(
        "GitLab 配置均为回环",
        "select count(*)::text from gitlab_sync_configs"
        " where web_base_url <> '' and web_base_url !~"
        " '^(https?://)?(localhost|127\\.0\\.0\\.1|\\[::1\\])([:/]|$)'",
        "0",
    )
    sql(
        "match-mode 外部 Mongo 目标未启用",
        "select count(*)::text from code_review_match_mode_db_settings"
        " where coalesce(mongo_uri, '') <> '' and mongo_uri !~"
        " '^(mongodb://)?(localhost|127\\.0\\.0\\.1|\\[::1\\])([:/]|$)'"
        " and (enabled or sync_enabled)",
        "0",
    )

    checks.append(
        check(
            "后端端口空闲",
            port_is_free(args.server_port),
            f"127.0.0.1:{args.server_port}",
        )
    )
    return checks


FIXTURE_SOURCE_SQL = (
    "insert into gitlab_sync_configs(name, enabled, auto_sync_enabled, source_enabled,"
    " db_name, db_username, db_password, web_base_url, match_mode_enabled,"
    " full_compensation_enabled)"
    " select 'n3r-smoke', false, false, false, 'qaflex', 'qaflex', 'w4-acceptance',"
    " 'http://127.0.0.1', false, false"
    " where not exists (select 1 from gitlab_sync_configs where name = 'n3r-smoke')"
)

SAFETY_SWITCHES = [
    "update code_review_match_mode_db_settings"
    " set enabled = false, sync_enabled = false where id = 1",
    "update bi_cat_mirror_configs set enabled = false, auto_sync_enabled = false,"
    " full_compensation_enabled = false where id = 1",
    "update code_review_dgm_gitlab_project_source_settings set enabled = false where id = 1",
    "update backup_settings set enabled = false where id = 1",
]


def apply_safety_switches(args: argparse.Namespace) -> list[str]:
    applied: list[str] = []
    for statement in SAFETY_SWITCHES:
        try:
            psql(args.db_container, args.db_user, args.db_name, statement)
        except RuntimeError as error:
            if "backup_settings" not in statement:
                raise
            applied.append(f"skipped: {statement} ({str(error).splitlines()[0]})")
            continue
        applied.append(f"applied: {statement}")
    return applied


def run_migrations(args: argparse.Namespace) -> dict[str, object]:
    command = [
        args.java,
        "-Dloader.main=com.data.collection.migration.MigrationApplication",
        "-cp",
        args.jar,
        "org.springframework.boot.loader.launch.PropertiesLauncher",
    ]
    log_path = Path(args.log).with_suffix(".migrate.log")
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8") as log_handle:
        completed = subprocess.run(
            command,
            stdout=log_handle,
            stderr=subprocess.STDOUT,
            env=build_environment(args),
            cwd=str(ROOT),
            timeout=900,
            check=False,
        )
    return {
        "command": command,
        "exitCode": completed.returncode,
        "logPath": str(log_path),
        "logSha256": sha256(log_path) if log_path.exists() else "",
    }


def build_command(args: argparse.Namespace) -> list[str]:
    command = [
        args.java,
        "-jar",
        args.jar,
        f"--server.port={args.server_port}",
        f"--spring.autoconfigure.exclude={MONGO_EXCLUDES}",
    ]
    for key, value in {**ISOLATION_PROPERTIES, **DEFERRED_TICK_PROPERTIES}.items():
        command.append(f"--{key}={value}")
    return command


def build_environment(args: argparse.Namespace) -> dict[str, str]:
    environment = dict(os.environ)
    environment.update(
        {
            "DATASOURCE_URL": f"jdbc:postgresql://127.0.0.1:{args.db_port}/{args.db_name}",
            "DATASOURCE_USERNAME": args.db_user,
            "DATASOURCE_PASSWORD": os.environ.get("ACCEPTANCE_DB_PASSWORD", "w4-acceptance"),
            "PLATFORM_AUTH_PROVIDER": "local",
            "PLATFORM_AUTH_SECURE_CONFIG_REQUIRED": "false",
            "PLATFORM_ADMIN_PASSWORD": args.admin_password,
            "GITLAB_WEB_BASE_URL": "http://127.0.0.1",
        }
    )
    return environment


def wait_health(port: int, timeout: int, process: subprocess.Popen[bytes]) -> tuple[bool, str]:
    deadline = time.time() + timeout
    url = f"http://127.0.0.1:{port}/actuator/health"
    last = "not attempted"
    while time.time() < deadline:
        if process.poll() is not None:
            return False, f"process exited with {process.returncode}"
        try:
            with urllib.request.urlopen(url, timeout=3) as response:
                body = json.loads(response.read().decode("utf-8", "replace") or "{}")
            if body.get("status") == "UP":
                return True, "UP"
            last = f"status={body.get('status')}"
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
            last = str(error)
        time.sleep(2)
    return False, f"timeout after {timeout}s ({last})"


def terminate(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=30)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_evidence(path: Path, payload: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    evidence: dict[str, object] = {
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "dbContainer": args.db_container,
        "dbPort": args.db_port,
        "serverPort": args.server_port,
        "isolationProperties": ISOLATION_PROPERTIES,
        "deferredTickProperties": DEFERRED_TICK_PROPERTIES,
    }

    if args.migrate:
        migration = run_migrations(args)
        evidence["migration"] = migration
        if migration["exitCode"] == 0:
            evidence["safetySwitches"] = apply_safety_switches(args)
            if args.seed_smoke_source:
                psql(args.db_container, args.db_user, args.db_name, FIXTURE_SOURCE_SQL)
                evidence["smokeSource"] = "seeded"
        print(f"迁移进程 exitCode={migration['exitCode']} log={migration['logPath']}")
        if migration["exitCode"] != 0:
            evidence["result"] = "MIGRATION_FAILED"
            write_evidence(Path(args.evidence), evidence)
            print("迁移未成功，后端未启动。", file=sys.stderr)
            return 2

    checks = collect_preflight(args)
    evidence["preflight"] = checks
    failures = preflight_failures(checks)
    for item in checks:
        print(f"{'PASS' if item['ok'] else 'FAIL'} {item['name']}: {item['detail']}")
    if failures:
        evidence["result"] = "PREFLIGHT_FAILED"
        write_evidence(Path(args.evidence), evidence)
        print("预检未通过，后端未启动。", file=sys.stderr)
        return 2
    if args.preflight_only:
        evidence["result"] = "PREFLIGHT_ONLY"
        write_evidence(Path(args.evidence), evidence)
        print("预检通过（未启动后端）。")
        return 0

    log_path = Path(args.log)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    command = build_command(args)
    evidence["command"] = command
    with log_path.open("w", encoding="utf-8") as log_handle:
        process = subprocess.Popen(
            command,
            stdout=log_handle,
            stderr=subprocess.STDOUT,
            env=build_environment(args),
            cwd=str(ROOT),
        )
        try:
            healthy, detail = wait_health(args.server_port, args.health_timeout, process)
            evidence["health"] = {"ok": healthy, "detail": detail}
            if not healthy:
                evidence["result"] = "STARTUP_FAILED"
                write_evidence(Path(args.evidence), evidence)
                print(f"后端启动失败: {detail}", file=sys.stderr)
                return 3
            if args.smoke_command:
                smoke_log = Path(args.log).with_suffix(".smoke.log")
                with smoke_log.open("w", encoding="utf-8") as smoke_handle:
                    smoke = subprocess.run(
                        args.smoke_command,
                        shell=True,
                        stdout=smoke_handle,
                        stderr=subprocess.STDOUT,
                        cwd=str(ROOT),
                        check=False,
                    )
                evidence["smoke"] = {
                    "command": args.smoke_command,
                    "exitCode": smoke.returncode,
                    "logPath": str(smoke_log),
                    "logSha256": sha256(smoke_log),
                }
                print(f"smoke exitCode={smoke.returncode} log={smoke_log}")
            time.sleep(args.probe_seconds)
            log_text = log_path.read_text(encoding="utf-8", errors="replace")
        finally:
            terminate(process)

    external = non_loopback_addresses(log_text)
    mongo_attempts = len(re.findall(r"Mongo", log_text))
    monitor_errors = len(re.findall(r"Exception in monitor thread", log_text))
    smoke_exit = evidence.get("smoke", {}).get("exitCode", 0)
    post = [
        check("发布冒烟通过", smoke_exit == 0, f"exitCode={smoke_exit}"),
        check("无非回环地址", not external, ", ".join(external) or "none"),
        check("无 Mongo 连接尝试", mongo_attempts == 0, f"Mongo occurrences={mongo_attempts}"),
        check("无 Mongo 监控线程异常", monitor_errors == 0, f"matches={monitor_errors}"),
    ]
    evidence["postStart"] = post
    evidence["logPath"] = str(log_path)
    evidence["logSha256"] = sha256(log_path)
    for item in post:
        print(f"{'PASS' if item['ok'] else 'FAIL'} {item['name']}: {item['detail']}")
    post_failures = preflight_failures(post)
    evidence["result"] = "FAILED" if post_failures else "PASSED"
    write_evidence(Path(args.evidence), evidence)
    print(f"evidence={args.evidence}")
    return 3 if post_failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
