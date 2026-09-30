#!/usr/bin/env python3
"""把本地假 CAT 服务端的数据经真实镜像链路灌入 BI 单元测试/集成测试页面。

链路与页面上真实使用的一致，没有任何跳过或伪造：登录 -> 保存 CAT 连接配置 -> 全量同步（发布目录）
-> 用平台真实产品版本保存阶段映射 -> 再全量同步（原子发布单元/集成两个阶段快照）-> 校验页面接口。

前置条件：

1. 本地后端 18080、前端 18181 已启动；
2. 假服务端已启动：`python scripts/mock-cat-server.py --port 18899`。

用法：

    python scripts/seed-local-bi-cat-demo.py
    python scripts/seed-local-bi-cat-demo.py --catalog-only          # 只发布目录，便于调试映射
    python scripts/seed-local-bi-cat-demo.py --product-version-id 10 # 指定要映射的产品版本
    python scripts/seed-local-bi-cat-demo.py --restore-config        # 把 CAT 连接配置恢复为内网默认

说明：脚本结束前会把自动同步关掉，避免假服务端离线后按调度累积失败运行记录；已发布的快照与映射
保持不动，因此之后停掉假服务端，两页仍可继续查看演示数据。
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from datetime import datetime
from http.cookiejar import CookieJar
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import HTTPCookieProcessor, Request, build_opener

DEFAULT_INTRANET_BASE_URL = "http://172.22.10.56:88"
TERMINAL_RUN_STATUSES = {"SUCCEEDED", "PARTIAL_SUCCESS", "FAILED"}
PAGES = (("单元测试", "unit-test"), ("集成测试", "integration-test"))


class SmokeFailure(RuntimeError):
    """脚本自身的失败信号：链路未按预期推进或页面未达到 READY。"""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="通过真实镜像链路写入 BI 单元/集成页面的本地演示数据。"
    )
    parser.add_argument("--base-url", default="http://localhost:18181", help="前端地址。")
    parser.add_argument("--username", default="admin", help="登录账号。")
    parser.add_argument("--password", default="admin123", help="登录口令。")
    parser.add_argument(
        "--cat-base-url", default="http://127.0.0.1:18899", help="本地假 CAT 服务端地址。"
    )
    parser.add_argument(
        "--product-version-id",
        default="",
        help=(
            "要映射的平台产品版本 ID，逗号分隔可填多个；留空则跟随平台产品版本目录首项"
            "（即前端无显式参数时的默认版本）。"
        ),
    )
    parser.add_argument("--timeout", type=int, default=120, help="每次同步的等待上限（秒）。")
    parser.add_argument("--catalog-only", action="store_true", help="只同步 CAT 目录，不保存映射。")
    parser.add_argument(
        "--restore-config",
        action="store_true",
        help="把 CAT 连接配置恢复为内网默认并禁用；不改映射与已发布快照。",
    )
    return parser.parse_args()


def parse_product_version_ids(value: str) -> list[int]:
    ids: list[int] = []
    for part in (value or "").split(","):
        part = part.strip()
        if not part:
            continue
        if not part.isdigit() or int(part) <= 0:
            raise SmokeFailure(f"--product-version-id 只接受正整数，收到：{part}")
        ids.append(int(part))
    return ids


def request_json(
    opener: Any,
    base_url: str,
    path: str,
    method: str = "GET",
    body: Any | None = None,
    headers: dict[str, str] | None = None,
) -> tuple[int, Any, dict[str, str]]:
    data = None
    request_headers = dict(headers or {})
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        request_headers["Content-Type"] = "application/json"
    request = Request(
        f"{base_url.rstrip('/')}{path}", data=data, method=method, headers=request_headers
    )
    with opener.open(request, timeout=60) as response:
        raw = response.read().decode("utf-8", errors="replace")
        parsed = json.loads(raw) if raw else None
        return response.status, parsed, {k.lower(): v for k, v in response.headers.items()}


def require_data(payload: Any, label: str) -> Any:
    if not isinstance(payload, dict) or payload.get("success") is not True:
        raise SmokeFailure(f"{label} 调用失败：{json.dumps(payload, ensure_ascii=False)[:400]}")
    return payload.get("data")


class LocalCatDemoSeed:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.opener = build_opener(HTTPCookieProcessor(CookieJar()))
        self.csrf_token = ""
        self.product_version_ids: list[int] = []

    # ---- HTTP 基础 ----

    def call(
        self, path: str, method: str = "GET", body: Any | None = None, label: str = ""
    ) -> Any:
        headers = {"X-XSRF-TOKEN": self.csrf_token} if self.csrf_token and method != "GET" else {}
        try:
            _, payload, response_headers = request_json(
                self.opener, self.args.base_url, path, method, body, headers
            )
        except HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:400]
            raise SmokeFailure(f"{label or path} HTTP {error.code}：{detail}") from error
        except (URLError, TimeoutError) as error:
            raise SmokeFailure(f"{label or path} 无法访问 {self.args.base_url}：{error}") from error
        if response_headers.get("x-xsrf-token"):
            self.csrf_token = response_headers["x-xsrf-token"]
        return require_data(payload, label or path)

    def login(self) -> None:
        self.call("/api/auth/current", label="读取当前会话")
        self.call(
            "/api/auth/login",
            method="POST",
            body={"username": self.args.username, "password": self.args.password},
            label="登录",
        )
        self.call("/api/auth/current", label="刷新 CSRF 令牌")
        if not self.csrf_token:
            raise SmokeFailure("未能取得 CSRF 令牌，写操作会被拒绝")

    # ---- 链路步骤 ----

    def settings(self) -> dict[str, Any]:
        return self.call("/api/bi-cat-mirror/settings", label="读取 CAT 镜像设置")

    def restore_config(self, settings: dict[str, Any]) -> None:
        config = settings.get("config") or {}
        self.call(
            "/api/bi-cat-mirror/config",
            method="PUT",
            body={
                "enabled": False,
                "baseUrl": DEFAULT_INTRANET_BASE_URL,
                "autoSyncEnabled": False,
                "syncIntervalMinutes": config.get("syncIntervalMinutes", 20),
                "fullCompensationEnabled": config.get("fullCompensationEnabled", False),
                "fullCompensationTime": config.get("fullCompensationTime", "02:00:00"),
            },
            label="恢复 CAT 连接配置",
        )
        print(f"[demo] 已恢复内网默认配置：enabled=false baseUrl={DEFAULT_INTRANET_BASE_URL}")
        print("[demo] 映射与已发布快照未改动，两页仍可继续查看演示数据。")

    def save_config(self, settings: dict[str, Any]) -> None:
        config = settings.get("config") or {}
        print(
            f"[demo] 原配置：enabled={config.get('enabled')} baseUrl={config.get('baseUrl')} "
            f"autoSync={config.get('autoSyncEnabled')} 间隔={config.get('syncIntervalMinutes')} 分钟"
        )
        self.call(
            "/api/bi-cat-mirror/config",
            method="PUT",
            body={
                "enabled": True,
                "baseUrl": self.args.cat_base_url,
                "autoSyncEnabled": False,
                "syncIntervalMinutes": config.get("syncIntervalMinutes", 20),
                "fullCompensationEnabled": False,
                "fullCompensationTime": config.get("fullCompensationTime", "02:00:00"),
            },
            label="保存 CAT 连接配置",
        )
        print(f"[demo] 已指向本地假 CAT：{self.args.cat_base_url}（自动同步已关闭）")

    def run_full_sync(self, label: str) -> dict[str, Any]:
        submission = self.call("/api/bi-cat-mirror/full-sync", method="POST", label=label)
        if not submission.get("accepted"):
            raise SmokeFailure(f"{label} 未被受理：{submission.get('message')}")
        run_id = submission.get("runId")
        print(f"[demo] {label} 已提交 runId={run_id}，等待执行完成…")
        deadline = time.monotonic() + self.args.timeout
        while time.monotonic() < deadline:
            time.sleep(2)
            runs = (self.settings().get("recentRuns") or [])
            current = next((run for run in runs if run.get("runId") == run_id), None)
            if current is None:
                continue
            if current.get("status") in TERMINAL_RUN_STATUSES:
                print(
                    f"[demo] {label} 结果：status={current.get('status')} "
                    f"发布阶段={current.get('publishedStageCount')} 失败阶段={current.get('failedStageCount')}"
                )
                print(f"[demo] {label} 说明：{current.get('message')}")
                return current
        raise SmokeFailure(f"{label} 在 {self.args.timeout} 秒内未结束，请检查后端日志")

    @staticmethod
    def resolve_catalog_ids(settings: dict[str, Any]) -> tuple[str, str, str, str]:
        catalog = settings.get("catalog") or {}
        projects = catalog.get("projects") or []
        if not projects:
            raise SmokeFailure("目录同步后仍无 CAT 项目，请先检查假服务端与后端日志")
        project = next((p for p in projects if p.get("defaultProject")), projects[0])
        nodes = catalog.get("nodes") or []
        versions = [
            node
            for node in nodes
            if node.get("nodeType") == "VERSION" and node.get("projectId") == project["id"]
        ]
        if len(versions) != 1:
            raise SmokeFailure(f"期望唯一的 CAT 版本，实际 {len(versions)} 个")
        version = versions[0]
        phases = [
            node
            for node in nodes
            if node.get("nodeType") == "TEST_PHASE"
            and node.get("projectId") == project["id"]
            and node.get("versionId") == version["id"]
        ]
        unit = next((node for node in phases if "单元测试" in (node.get("name") or "")), None)
        integration = next((node for node in phases if "集成测试" in (node.get("name") or "")), None)
        if unit is None or integration is None:
            raise SmokeFailure("目录中未找到单元测试或集成测试阶段")
        return project["id"], version["id"], unit["id"], integration["id"]

    def save_mapping(self, settings: dict[str, Any]) -> None:
        project_id, version_id, unit_id, integration_id = self.resolve_catalog_ids(settings)
        business_keys = {
            item.get("id"): item.get("businessKey") for item in (settings.get("productVersions") or [])
        }
        suggestions = {
            item.get("productVersionId"): item for item in (settings.get("mappingSuggestions") or [])
        }
        # 保存映射是整体替换，因此这里一次提交全部目标版本，避免后一次调用删掉前一次。
        self.call(
            "/api/bi-cat-mirror/mappings",
            method="PUT",
            body=[
                {
                    "productVersionId": product_version_id,
                    "catProjectId": project_id,
                    "catVersionId": version_id,
                    "unitTestingPhaseId": unit_id,
                    "integrationTestingPhaseId": integration_id,
                }
                for product_version_id in self.product_version_ids
            ],
            label="保存阶段映射",
        )
        for product_version_id in self.product_version_ids:
            suggestion = suggestions.get(product_version_id) or {}
            hint = "（设置页建议一致，会唯一预填）" if suggestion.get("unitTestingPhaseId") == unit_id else (
                "（设置页无唯一建议：假服务端版本名与平台 business key 不一致时不预填，属预期）"
            )
            print(
                f"[demo] 已映射 产品版本={product_version_id}"
                f"({business_keys.get(product_version_id)}) → CAT 项目={project_id} "
                f"版本={version_id} 单元测试={unit_id} 集成测试={integration_id} {hint}"
            )

    # ---- 校验 ----

    def verify_pages(self) -> None:
        failures: list[str] = []
        for product_version_id in self.product_version_ids:
            for label, page_key in PAGES:
                page = self.call(
                    f"/api/bi/{page_key}?productVersionId={product_version_id}",
                    label=f"产品版本 {product_version_id} 的{label}页面",
                )
                status = page.get("status")
                data = page.get("data") or {}
                modules = data.get("modules") or []
                functions = data.get("functions") or []
                overall = data.get("overall") or {}
                counts = overall.get("counts") or {}
                achieved = [row for row in modules if (row.get("attainment") or {}).get("achieved")]
                not_achieved = [
                    row for row in modules if (row.get("attainment") or {}).get("achieved") is False
                ]
                print(
                    f"[demo] [{product_version_id}] {label}页：status={status} "
                    f"sourceVersion={page.get('sourceVersion')} "
                    f"整体通过率={overall.get('passRate')}% 目标={overall.get('targetRate')}% "
                    f"达标/统计={counts.get('attainedCount')}/{counts.get('totalCount')} "
                    f"模块={len(modules)}(达标 {len(achieved)} / 未达标 {len(not_achieved)}) "
                    f"功能={len(functions)}"
                )
                if status != "READY":
                    failures.append(f"[{product_version_id}] {label}页状态为 {status}，期望 READY")
                if not page.get("sourceVersion") or not page.get("snapshotId"):
                    failures.append(f"[{product_version_id}] {label}页缺少 sourceVersion 或 snapshotId")
                if not modules or not functions:
                    failures.append(f"[{product_version_id}] {label}页模块或功能为空")
                if not achieved or not not_achieved:
                    failures.append(f"[{product_version_id}] {label}页未同时出现达标与未达标模块")
        if failures:
            raise SmokeFailure("；".join(failures))
        count = len(self.product_version_ids)
        print(f"[demo] {count} 个产品版本的单元/集成页均为 READY，且达标与未达标两种状态并存。")


def resolve_product_version_ids(args: argparse.Namespace, settings: dict[str, Any]) -> list[int]:
    """确定要映射的平台产品版本；未显式指定时跟随目录首项（即前端默认版本）。"""
    versions = settings.get("productVersions") or []
    if not versions:
        raise SmokeFailure("平台产品版本目录为空，无法确定映射目标")
    known = {item.get("id"): item.get("businessKey") for item in versions}
    requested = parse_product_version_ids(args.product_version_id)
    if requested:
        unknown = [item for item in requested if item not in known]
        if unknown:
            raise SmokeFailure(f"平台产品版本目录里没有这些 ID：{unknown}")
        return requested
    default_id = versions[0].get("id")
    print(
        f"[demo] 未指定 --product-version-id，跟随平台产品版本目录首项（前端默认版本）："
        f"{default_id}({known.get(default_id)})"
    )
    return [default_id]


def main() -> int:
    args = parse_args()
    seed = LocalCatDemoSeed(args)
    started_at = datetime.now()
    print(f"[demo] 开始：{started_at:%Y-%m-%d %H:%M:%S} 前端={args.base_url}")
    try:
        seed.login()
        print(f"[demo] 已登录 {args.username}")
        settings = seed.settings()
        if args.restore_config:
            seed.restore_config(settings)
            return 0
        seed.save_config(settings)
        seed.product_version_ids = resolve_product_version_ids(args, settings)
        # 全量同步总是先发布目录、再按已有映射发布阶段快照；首次运行时映射还不存在，
        # 因此第一次只发布目录，保存映射后的第二次才发布两个阶段。
        catalog_run = seed.run_full_sync("首次全量同步（发布目录，并按已有映射刷新阶段）")
        if catalog_run.get("status") == "FAILED":
            raise SmokeFailure("目录同步失败，无法继续；请检查假服务端地址与后端日志")
        if args.catalog_only:
            print("[demo] 已按 --catalog-only 停在目录阶段，可在系统设置页手动保存映射。")
            return 0
        seed.save_mapping(seed.settings())
        stage_run = seed.run_full_sync("映射后全量同步（发布单元/集成两阶段快照）")
        if stage_run.get("status") != "SUCCEEDED":
            raise SmokeFailure(
                f"阶段同步未全部成功：status={stage_run.get('status')} "
                f"说明={stage_run.get('message')}"
            )
        seed.verify_pages()
    except SmokeFailure as failure:
        print(f"[demo] FAIL：{failure}", file=sys.stderr)
        return 1
    print("[demo] PASS：本地 CAT 演示数据已就绪，可刷新 18181 的单元测试/集成测试页面查看。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
