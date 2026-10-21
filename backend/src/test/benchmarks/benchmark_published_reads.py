"""固定页面/范围/完整版本的顺序读取基准；只建租约和读取，不触发同步或修改业务数据。

认证 Cookie 从 READ_BENCH_COOKIE 环境变量取得，绝不写入输出。真实同步由验收者另行触发；
分别在健康、增量、全量、失败/中止期间用相同参数运行，不能用本地夹具外推目标容量。
"""
from __future__ import annotations

import argparse
import json
import math
import os
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path


def summarize(values: list[float]) -> dict[str, float]:
    """报告同一固定场景的毫秒分布；失败请求不计成快速成功。"""
    ordered = sorted(values)
    if not ordered:
        return {}
    def percentile(fraction: float) -> float:
        return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]
    return {"min_ms": ordered[0], "median_ms": statistics.median(ordered),
            "p95_ms": percentile(.95), "p99_ms": percentile(.99), "max_ms": ordered[-1]}


def execute(base: str, path: str, cookie: str, *, body: dict | None = None,
            context: str | None = None, method: str | None = None) -> tuple[int, bytes, dict, float]:
    """执行一次有 10 秒截止的请求，只返回状态/内容/头/耗时，调用方负责区分失败。"""
    headers = {"Cookie": cookie, "Content-Type": "application/json"}
    csrf = os.environ.get("READ_BENCH_CSRF", "")
    if csrf:
        headers["X-XSRF-TOKEN"] = csrf
    if context:
        headers["X-Read-Context-Id"] = context
    request = urllib.request.Request(base.rstrip("/") + path, headers=headers,
        data=None if body is None else json.dumps(body).encode(), method=method)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, response.read(), dict(response.headers), (time.perf_counter() - started) * 1000
    except urllib.error.HTTPError as error:
        return error.code, error.read(), dict(error.headers), (time.perf_counter() - started) * 1000


def main() -> None:
    """固定负载必须显式给出页面、来源范围、业务端点及样本数；报告保留每次状态而不保存业务内容。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--page", required=True)
    parser.add_argument("--scope", default="{}", help="JSON 字符串：与实际页面相同的来源范围")
    parser.add_argument("--operation", action="append", required=True, help="名称=/api/实际读取路径，可重复")
    parser.add_argument("--samples", type=int, default=50)
    parser.add_argument("--warmup", type=int, default=5)
    parser.add_argument("--scenario", required=True, choices=["healthy", "incremental", "full", "failed", "cancelled"])
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = Path(args.output).resolve()
    workspace = Path(__file__).resolve().parents[4]
    if not any(directory in output.parents for directory in [workspace / ".tmp-logs", workspace / ".tmp"]):
        parser.error("基准输出只允许位于本仓库 .tmp-logs/ 或 .tmp/")
    cookie = os.environ.get("READ_BENCH_COOKIE", "")
    if not cookie:
        parser.error("需要 READ_BENCH_COOKIE 环境变量，报告不记录该值")
    if not 1 <= args.samples <= 10000 or not 0 <= args.warmup <= 100:
        parser.error("samples 应为 1–10000，warmup 应为 0–100")
    scope = json.loads(args.scope)
    if not isinstance(scope, dict) or any(not isinstance(value, str) for value in scope.values()):
        parser.error("scope 必须是字符串值的 JSON 对象")
    operations = [value.split("=", 1) for value in args.operation]
    if any(len(value) != 2 or not value[1].startswith("/api/") for value in operations):
        parser.error("operation 必须为 名称=/api/实际读取路径")
    status, raw, _, preparation_ms = execute(args.base_url, "/api/read-views", cookie,
        body={"pageKey": args.page, "scope": scope, "clientViewId": str(uuid.uuid4())})
    envelope = json.loads(raw)
    if status != 201 or not envelope.get("success"):
        raise RuntimeError(f"完整视图准备失败，HTTP {status}")
    metadata = envelope["data"]
    context = metadata["readContextId"]
    source_version = metadata["sourceVersion"]
    report = {"scenario": args.scenario, "page": args.page, "samples": args.samples,
              "warmup": args.warmup, "preparation_ms": preparation_ms, "source_version": source_version,
              "business_date": metadata["businessDate"], "operations": {}, "failures": []}
    renewed_at = time.monotonic()
    try:
        for name, path in operations:
            durations = []
            query = urllib.parse.parse_qsl(urllib.parse.urlsplit(path).query, keep_blank_values=True)
            query.extend((key, value) for key, value in scope.items() if key not in dict(query))
            bound_path = urllib.parse.urlsplit(path).path + "?" + urllib.parse.urlencode(query)
            for sample in range(args.warmup + args.samples):
                if time.monotonic() - renewed_at > 60:
                    renewal, _, _, _ = execute(args.base_url, f"/api/read-views/{context}/renew", cookie, method="POST")
                    if renewal != 200:
                        raise RuntimeError(f"续约失败，HTTP {renewal}")
                    renewed_at = time.monotonic()
                code, data, response_headers, elapsed = execute(args.base_url, bound_path, cookie, context=context)
                valid = code == 200
                if valid and "json" in response_headers.get("Content-Type", ""):
                    payload = json.loads(data)
                    read = payload.get("readView", {})
                    valid = payload.get("success") is True and read.get("readContextId") == context and read.get("sourceVersion") == source_version
                elif valid:
                    valid = response_headers.get("X-Read-Context-Id") == context
                if not valid:
                    report["failures"].append({"operation": name, "sample": sample, "http_status": code, "elapsed_ms": elapsed})
                elif sample >= args.warmup:
                    durations.append(elapsed)
            report["operations"][name] = {"successful_samples": len(durations), **summarize(durations)}
    finally:
        execute(args.base_url, f"/api/read-views/{context}", cookie, method="DELETE")
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if report["failures"]:
        raise SystemExit("发现读取失败或混合版本，请按报告修复；本轮不可作成功容量证据")


if __name__ == "__main__":
    main()
