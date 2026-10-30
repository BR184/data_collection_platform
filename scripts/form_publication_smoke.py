#!/usr/bin/env python3
"""发布冒烟：经真实管理接口创建空结构版本，由发布 worker 执行并核对新建指针。

只用于隔离验收环境的启动后冒烟，不替代真实发布回归；默认同 requestKey 幂等，
失败时输出可追溯步骤与响应摘要。

退出码：0 通过；1 断言失败或接口错误。
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
from datetime import datetime
from http.cookiejar import CookieJar
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import HTTPCookieProcessor, Request, build_opener

ROOT = Path(__file__).resolve().parents[1]


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="隔离环境发布冒烟。")
    parser.add_argument("--base-url", default="http://127.0.0.1:18094")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin-w4-acceptance-2026")
    parser.add_argument("--template-key", default="issue_reply")
    parser.add_argument("--scope-key", default="n3r-smoke")
    parser.add_argument("--source-config-name", default="n3r-smoke")
    parser.add_argument("--project-id", type=int, default=9)
    parser.add_argument("--request-key", default="", help="发布请求标识；缺省生成规范 UUID。")
    parser.add_argument("--timeout", type=int, default=120)
    parser.add_argument("--output", required=True, help="冒烟结果 JSON 路径。")
    return parser.parse_args(argv)


class Api:
    def __init__(self, base_url: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.cookies = CookieJar()
        self.opener = build_opener(HTTPCookieProcessor(self.cookies))
        self.csrf = ""

    def call(self, method: str, path: str, body: Any | None = None) -> Any:
        data = None
        headers: dict[str, str] = {}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if self.csrf:
            headers["X-XSRF-TOKEN"] = self.csrf
        request = Request(f"{self.base_url}{path}", data=data, method=method, headers=headers)
        try:
            with self.opener.open(request, timeout=30) as response:
                payload = json.loads(response.read().decode("utf-8", "replace") or "{}")
                token = response.headers.get("X-XSRF-Token")
                if token:
                    self.csrf = token
        except HTTPError as error:
            body = error.read().decode("utf-8", "replace")
            raise RuntimeError(f"{method} {path} -> HTTP {error.code}: {body[:500]}") from error
        return payload

    def login(self, username: str, password: str) -> None:
        self.call("GET", "/api/auth/current")
        payload = self.call("POST", "/api/auth/login", {"username": username, "password": password})
        if payload.get("success") is not True:
            raise RuntimeError(f"登录失败: {payload.get('message')}")


def data_of(payload: Any, step: str) -> Any:
    if not isinstance(payload, dict) or payload.get("success") is not True:
        message = payload.get("message") if isinstance(payload, dict) else payload
        raise RuntimeError(f"{step} 失败: {message}")
    return payload.get("data")


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    steps: list[dict[str, Any]] = []
    report: dict[str, Any] = {
        "generatedAt": datetime.now().isoformat(timespec="seconds"),
        "baseUrl": args.base_url,
        "templateKey": args.template_key,
        "scopeKey": args.scope_key,
        "steps": steps,
    }

    def record(step: str, detail: dict[str, Any]) -> None:
        steps.append({"step": step, **detail})
        print(f"PASS {step} {json.dumps(detail, ensure_ascii=False)}")

    try:
        api = Api(args.base_url)
        api.login(args.username, args.password)
        record("login", {"username": args.username, "csrf": bool(api.csrf)})

        configs = data_of(api.call("GET", "/api/gitlab-sync/configs"), "读取来源配置") or []
        source = next(
            (item for item in configs if item.get("name") == args.source_config_name), None
        )
        if source is None:
            raise RuntimeError(f"缺少冒烟来源配置: {args.source_config_name}")
        record("sourceConfig", {"id": source["id"], "name": source["name"]})

        scope_key = f"{args.scope_key}-{int(time.time())}"[:64]
        scope = data_of(
            api.call(
                "POST",
                "/api/collect-forms/template-scopes",
                {"templateKey": args.template_key, "scopeKey": scope_key},
            ),
            "创建范围",
        )
        scope_id = scope["scope"]["id"]
        draft = data_of(api.call("GET", f"/api/collect-forms/template-scopes/{scope_id}"), "读取范围")[
            "draft"
        ]
        record("createScope", {"scopeId": scope_id, "draftRevision": draft["revision"]})

        draft = data_of(
            api.call(
                "PUT",
                f"/api/collect-forms/template-scopes/{scope_id}/draft",
                {
                    "expectedDraftRevision": draft["revision"],
                    "fieldSpec": draft["fieldSpec"],
                    "binding": {
                        "sourceConfigId": source["id"],
                        "projectIds": [args.project_id],
                        "versionProjectId": args.project_id,
                    },
                    "versionLabel": "N3R-01",
                    "versionEvidence": {"milestoneId": 1},
                    "migrationSpec": None,
                },
            ),
            "保存草稿",
        )
        draft_revision = draft["revision"]
        record("saveDraft", {"draftRevision": draft_revision, "bound": draft["binding"] is not None})

        preview = data_of(
            api.call(
                "POST",
                f"/api/collect-forms/template-scopes/{scope_id}/preview",
                {"draftRevision": draft_revision},
            ),
            "预览",
        )
        record("preview", {"previewId": preview["id"], "blockingErrors": preview["blockingErrors"]})
        if preview["blockingErrors"]:
            raise RuntimeError(f"预览存在阻塞项: {preview['blockingErrors']}")

        publication = data_of(
            api.call(
                "POST",
                f"/api/collect-forms/template-scopes/{scope_id}/publications",
                {
                    "draftRevision": draft_revision,
                    "previewId": preview["id"],
                    "expectedCurrentVersionId": None,
                    "requestKey": args.request_key or str(uuid.uuid4()),
                },
            ),
            "创建发布任务",
        )
        publication_id = publication["id"]
        record("createPublication", {"publicationId": publication_id, "stage": publication["stage"]})

        deadline = time.time() + args.timeout
        stage = publication["stage"]
        while time.time() < deadline and stage not in {"PUBLISHED", "FAILED"}:
            time.sleep(1)
            current = data_of(
                api.call("GET", f"/api/collect-forms/publications/{publication_id}"),
                "读取发布任务",
            )
            stage = current["stage"]
        record(
            "publicationStage",
            {"publicationId": publication_id, "stage": stage, "lastError": current.get("lastError")},
        )
        if stage != "PUBLISHED":
            raise RuntimeError(f"发布未成功: stage={stage} lastError={current.get('lastError')}")

        detail = data_of(
            api.call("GET", f"/api/collect-forms/template-scopes/{scope_id}"), "读取范围"
        )
        summary = detail["scope"]
        if summary["currentVersionId"] != current["toVersionId"]:
            raise RuntimeError(
                f"新建指针未指向新版本: current={summary['currentVersionId']}"
                f" toVersion={current['toVersionId']}"
            )
        if detail["recordCount"] not in (0, None):
            raise RuntimeError(f"新版应为空结构，实际 recordCount={detail['recordCount']}")
        record(
            "emptyVersionSwitched",
            {
                "currentVersionId": summary["currentVersionId"],
                "versionCount": len(detail["versions"]),
                "recordCount": detail["recordCount"],
            },
        )
        report["result"] = "PASSED"
        exit_code = 0
    except (HTTPError, URLError, RuntimeError, TimeoutError, KeyError) as error:
        report["result"] = "FAILED"
        report["error"] = str(error)
        print(f"FAIL {error}", file=sys.stderr)
        exit_code = 1

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"report={output}")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
