#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""GitLab 小样本数据包契约、脱敏与校验工具。

本模块只处理数据包结构、清单和类型编码，不连接任何数据库，也不执行 SQL。
导出与导入脚本共用同一契约，避免出现第二套包格式。
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import tempfile
from dataclasses import dataclass
from datetime import date, datetime, time
from decimal import Decimal
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence

FORMAT = "dcp-gitlab-sample/v1"
TOOL_VERSION = "1.0.0"

# 仅支持本地已核实的 GitLab 16.11.10 结构。扩展版本必须先重新取证结构指纹。
SUPPORTED_GITLAB_VERSIONS = ("16.11.10",)
SUPPORTED_SCHEMA_MIGRATIONS = {
    "20240702181131": "16.11.10",
}
MAX_ISSUES_HARD_LIMIT = 500
DEFAULT_MAX_ISSUES = 200
DEFAULT_MAX_TOTAL_ROWS = 200_000
MAX_TOTAL_ROWS_HARD_LIMIT = 1_000_000
DEFAULT_MAX_TOTAL_BYTES = 64 * 1024 * 1024
MAX_TOTAL_BYTES_HARD_LIMIT = 512 * 1024 * 1024
DEFAULT_BATCH_SIZE = 200
DEFAULT_BATCH_SLEEP_MS = 100
DEFAULT_STATEMENT_TIMEOUT_MS = 30_000
DEFAULT_LOCK_TIMEOUT_MS = 5_000
DEFAULT_MAX_RUNTIME_SECONDS = 300
DEFAULT_WINDOW_DAYS = 60
DEFAULT_TIMEZONE = "Asia/Shanghai"
BOOTSTRAP_USER_ID = 1
BOOTSTRAP_NAMESPACE_ID = 1
BOOTSTRAP_ROUTE_ID = 1

IDENT = re.compile(r"^[a-zA-Z_][a-zA-Z0-9_]*$")
SENSITIVE_COLUMN = re.compile(
    r"(^|_)(encrypted|password|secret|token|private_key|api_key|credential|otp)(_|$)",
    re.IGNORECASE,
)


class PackageError(RuntimeError):
    """数据包或导入前置条件不满足。"""


class SelectionError(RuntimeError):
    """选样为空、超限或关联不完整。"""


@dataclass(frozen=True)
class TableSpec:
    key_columns: tuple[str, ...]
    columns: tuple[str, ...] | None = None
    excluded: frozenset[str] = frozenset()

    def keep(self, column: str) -> bool:
        if self.columns is not None:
            return column in self.columns
        return column not in self.excluded and not SENSITIVE_COLUMN.search(column)


# 顺序即导入顺序：被引用表在前。所有表都必须出现在包清单中，空表也要有空文件。
TABLE_SPECS: dict[str, TableSpec] = {
    "organizations": TableSpec(("id",)),
    "users": TableSpec(
        ("id",),
        columns=(
            "id",
            "username",
            "name",
            "email",
            "admin",
            "projects_limit",
            "can_create_group",
            "can_create_team",
            "state",
            "created_at",
            "updated_at",
            "confirmed_at",
            "color_scheme_id",
            "color_mode_id",
            "theme_id",
            "preferred_language",
            "last_activity_on",
            "user_type",
            "external",
            "auditor",
            "private_profile",
            "otp_required_for_login",
            "require_two_factor_authentication_from_group",
            "two_factor_grace_period",
            "onboarding_in_progress",
        ),
    ),
    "user_details": TableSpec(("user_id",), columns=("user_id",)),
    "namespaces": TableSpec(
        ("id",),
        excluded=frozenset({"runners_token", "runners_token_encrypted", "saml_discovery_token", "ldap_sync_error"}),
    ),
    "work_item_types": TableSpec(("id",)),
    "member_roles": TableSpec(("id",)),
    "projects": TableSpec(("id",), excluded=frozenset({"import_url", "import_source"})),
    "project_features": TableSpec(("id",)),
    "project_states": TableSpec(("id",)),
    "routes": TableSpec(("id",)),
    "labels": TableSpec(("id",)),
    "milestones": TableSpec(("id",)),
    "issues": TableSpec(("id",), excluded=frozenset({"service_desk_reply_to"})),
    "issue_assignees": TableSpec(("issue_id", "user_id")),
    "issue_metrics": TableSpec(("id",)),
    "label_links": TableSpec(("id",)),
    "resource_label_events": TableSpec(("id",)),
    "resource_state_events": TableSpec(("id",)),
    "resource_milestone_events": TableSpec(("id",)),
    "resource_weight_events": TableSpec(("id",)),
    "notes": TableSpec(
        ("id",),
        excluded=frozenset(
            {
                "attachment",
                "st_diff",
                "position",
                "original_position",
                "change_position",
                "review_id",
            }
        ),
    ),
    "system_note_metadata": TableSpec(("id",), excluded=frozenset({"description_version_id"})),
    "members": TableSpec(("id",), excluded=frozenset({"invite_token", "invite_email"})),
    "project_authorizations": TableSpec(("user_id", "project_id")),
}

TABLE_ORDER: tuple[str, ...] = tuple(TABLE_SPECS)

# 空 GitLab 初始化行允许保留；其余目标数据一律拒绝。
BOOTSTRAP_ALLOWED_KEYS: dict[str, tuple[Any, ...]] = {
    "users": (BOOTSTRAP_USER_ID,),
    "user_details": (BOOTSTRAP_USER_ID,),
    "namespaces": (BOOTSTRAP_NAMESPACE_ID,),
    "routes": (BOOTSTRAP_ROUTE_ID,),
}

# 这些表是 GitLab 自带的引用数据，目标已有同主键行时按业务字段逐行核对后复用。
REFERENCE_TABLES = frozenset({"organizations", "work_item_types", "member_roles"})

SECURITY_SENSITIVE_TABLES = (
    "web_hooks",
    "integrations",
    "personal_access_tokens",
    "keys",
    "identities",
    "sessions",
    "oauth_access_tokens",
    "oauth_applications",
    "ci_runner_tokens",
)

SANITIZATION_SUMMARY = {
    "users": "用户 ID 保留；username/name/email 替换为确定性测试值；admin/auditor/external 置 false；"
    "加密密码、重置令牌、OTP、登录令牌、IP、通知邮箱等不导出，由目标实例重建。",
    "user_details": "仅保留 user_id，个人与第三方资料字段使用目标默认值。",
    "namespaces": "命名空间路径与业务名称保留；runners_token、saml_discovery_token、ldap_sync_error 不导出。",
    "projects": "业务字段保留；import_url、import_source 及所有 token/secret/encrypted 字段不导出。",
    "notes": "评论正文保留；附件路径、diff 与行内位置不导出。",
    "members": "成员关系保留；邀请令牌与邀请邮箱不导出。",
    "excluded_tables": "application_settings、web_hooks、integrations、CI、密钥、会话、个人访问令牌与外部集成表不导出。",
}

# 机器可读的脱敏清单：说明哪些字段被替换、哪些列绝不进入数据包。
SANITIZATION_MANIFEST = {
    "user_identity_replacement": {
        "username": "sample-user-<id>",
        "name": "Sample User <id>",
        "email": "sample-user-<id>@example.invalid",
        "admin": False,
        "auditor": False,
        "external": False,
        "otp_required_for_login": False,
    },
    "user_columns": list(TABLE_SPECS["users"].columns or ()),
    "user_details_columns": list(TABLE_SPECS["user_details"].columns or ()),
    "explicit_excluded_columns": {
        table: sorted(spec.excluded) for table, spec in TABLE_SPECS.items() if spec.excluded
    },
    "sensitive_column_pattern": SENSITIVE_COLUMN.pattern,
    "excluded_tables": [
        "application_settings",
        "appearances",
        "features",
        "plans",
        "plan_limits",
        "web_hooks",
        "integrations",
        "services",
        "personal_access_tokens",
        "keys",
        "identities",
        "sessions",
        "oauth_access_tokens",
        "oauth_applications",
        "ci_runner_tokens",
    ],
    "free_text_notice": "议题标题、描述和评论正文按业务原文保留，不做内容级脱敏；传输前必须由操作者确认样本内容允许离开源环境。",
}


def quote_ident(name: str) -> str:
    """引用经白名单校验的 SQL 标识符。"""
    if not IDENT.match(name):
        raise PackageError(f"Unsafe SQL identifier: {name!r}")
    return '"' + name.replace('"', '""') + '"'


def adapt_row(row: Sequence[Any]) -> tuple[Any, ...]:
    """兼容旧调用方：dict/list 值交给 psycopg2 的 JSON 适配器。"""
    from psycopg2.extras import Json

    return tuple(Json(value) if isinstance(value, (dict, list)) else value for value in row)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def encode_value(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, (date, time)):
        return value.isoformat()
    if isinstance(value, (bytes, bytearray, memoryview)):
        return {"$bytes": base64.b64encode(bytes(value)).decode("ascii")}
    if isinstance(value, Mapping):
        return {str(key): encode_value(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [encode_value(item) for item in value]
    raise PackageError(f"Unsupported package value type: {type(value)!r}")


def decode_value(value: Any) -> Any:
    if isinstance(value, dict) and set(value) == {"$bytes"}:
        return base64.b64decode(value["$bytes"])
    if isinstance(value, list):
        return [decode_value(item) for item in value]
    if isinstance(value, dict):
        return {key: decode_value(item) for key, item in value.items()}
    return value


def sanitize_username(user_id: int) -> str:
    return f"sample-user-{user_id}"


def sanitize_email(user_id: int) -> str:
    return f"sample-user-{user_id}@example.invalid"


def sanitize_name(user_id: int) -> str:
    return f"Sample User {user_id}"


def sanitize_user_row(row: Mapping[str, Any]) -> dict[str, Any]:
    """保留关联身份，移除可用于登录或联系真实人员的字段。"""
    user_id = int(row["id"])
    value = {key: item for key, item in row.items() if table_spec("users").keep(key)}
    value["username"] = sanitize_username(user_id)
    value["name"] = sanitize_name(user_id)
    value["email"] = sanitize_email(user_id)
    value["admin"] = False
    value["auditor"] = False
    value["external"] = False
    if "otp_required_for_login" in value:
        value["otp_required_for_login"] = False
    if "require_two_factor_authentication_from_group" in value:
        value["require_two_factor_authentication_from_group"] = False
    return value


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def compute_schema_fingerprint(columns: Iterable[Mapping[str, Any]]) -> str:
    material = "\n".join(
        f"{row['table_name']}.{row['column_name']}:{row['data_type']}:{row['is_nullable']}"
        for row in sorted(columns, key=lambda item: (item["table_name"], item["column_name"]))
    )
    return sha256_bytes(material.encode("utf-8"))


def supported_gitlab_version(schema_migrations_max: str) -> str | None:
    return SUPPORTED_SCHEMA_MIGRATIONS.get(schema_migrations_max)


def resolve_schema_version(schema_migrations_max: str) -> str:
    version = supported_gitlab_version(schema_migrations_max)
    if not version:
        raise PackageError(
            "Unsupported GitLab schema migration "
            f"{schema_migrations_max}; verified versions are "
            + ", ".join(SUPPORTED_GITLAB_VERSIONS)
        )
    return version


def table_spec(table: str) -> TableSpec:
    try:
        return TABLE_SPECS[table]
    except KeyError as error:
        raise PackageError(f"Unknown package table: {table}") from error


def package_id_for(
    selection: Mapping[str, Any],
    table_hashes: Mapping[str, str],
    metadata: Mapping[str, Any] | None = None,
) -> str:
    material = canonical_json(
        {"selection": selection, "tables": table_hashes, "metadata": metadata or {}}
    )
    return sha256_bytes(material.encode("utf-8"))


def write_jsonl(path: Path, rows: Sequence[Mapping[str, Any]], columns: Sequence[str]) -> int:
    count = 0
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            if set(row) != set(columns):
                raise PackageError(f"Row columns do not match manifest for {path.name}")
            handle.write(json.dumps({column: encode_value(row[column]) for column in columns}, ensure_ascii=False) + "\n")
            count += 1
    return count


def read_jsonl(path: Path, columns: Sequence[str]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, start=1):
            if not line.strip():
                continue
            try:
                raw = json.loads(line)
            except json.JSONDecodeError as error:
                raise PackageError(f"Invalid JSON in {path.name} line {line_no}: {error}") from error
            if not isinstance(raw, dict) or set(raw) != set(columns):
                raise PackageError(f"Unexpected columns in {path.name} line {line_no}")
            rows.append({key: decode_value(value) for key, value in raw.items()})
    return rows


def write_package(
    output: Path,
    manifest: dict[str, Any],
    rows: Mapping[str, Sequence[Mapping[str, Any]]],
    columns: Mapping[str, Sequence[str]],
    deadline_check: Callable[[], None] | None = None,
    byte_limit: int | None = None,
) -> Path:
    """原子写入目录包：数据、清单、校验和全部成功后才改名为最终目录。"""
    output = output.resolve()
    if output.exists():
        raise PackageError(f"Output package already exists: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{output.name}.partial-", dir=output.parent))
    try:
        data_dir = temporary / "data"
        data_dir.mkdir()
        table_entries: list[dict[str, Any]] = []
        table_hashes: dict[str, str] = {}
        total_bytes = 0
        for table in TABLE_ORDER:
            if deadline_check:
                deadline_check()
            table_rows = rows.get(table, [])
            table_columns = list(columns[table])
            table_path = data_dir / f"{table}.jsonl"
            count = write_jsonl(table_path, table_rows, table_columns)
            digest = sha256_file(table_path)
            total_bytes += table_path.stat().st_size
            if byte_limit is not None and total_bytes > byte_limit:
                raise PackageError(
                    f"Total byte budget exceeded while writing package: "
                    f"bytes={total_bytes}, limit={byte_limit}"
                )
            table_hashes[table] = digest
            table_entries.append(
                {
                    "name": table,
                    "columns": table_columns,
                    "row_count": count,
                    "file": f"data/{table}.jsonl",
                    "sha256": digest,
                }
            )
            if deadline_check:
                deadline_check()
        manifest = dict(manifest)
        manifest["package_id"] = package_id_for(
            manifest["selection"],
            table_hashes,
            {
                "format": manifest.get("format"),
                "tool_version": manifest.get("tool_version"),
                "source": manifest.get("source"),
                "schema": manifest.get("schema"),
            },
        )
        manifest["tables"] = table_entries
        manifest["status"] = "COMPLETE"
        manifest_path = temporary / "manifest.json"
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        checksums = [f"{sha256_file(manifest_path)}  manifest.json"]
        checksums.extend(f"{entry['sha256']}  {entry['file']}" for entry in table_entries)
        (temporary / "CHECKSUMS.sha256").write_text("\n".join(checksums) + "\n", encoding="utf-8", newline="\n")
        if deadline_check:
            deadline_check()
        if byte_limit is not None and total_bytes > byte_limit:
            raise PackageError(
                f"Total byte budget exceeded while writing package: "
                f"bytes={total_bytes}, limit={byte_limit}"
            )
        os.replace(temporary, output)
        try:
            if deadline_check:
                deadline_check()
        except Exception:
            import shutil

            shutil.rmtree(output, ignore_errors=True)
            raise
        return output
    except Exception:
        if temporary.exists():
            import shutil

            shutil.rmtree(temporary, ignore_errors=True)
        raise


def load_package(package: Path) -> tuple[dict[str, Any], dict[str, list[dict[str, Any]]]]:
    """校验完成状态、清单、校验和与表清单，返回清单和已解码数据。"""
    package = package.resolve()
    if not package.is_dir():
        raise PackageError(f"Package directory not found: {package}")
    root_entries = {entry.name for entry in package.iterdir()}
    if root_entries != {"manifest.json", "CHECKSUMS.sha256", "data"}:
        raise PackageError(f"Package root contains unexpected entries: {sorted(root_entries)}")
    data_entries = {entry.name for entry in (package / "data").iterdir()}
    expected_data_entries = {f"{table}.jsonl" for table in TABLE_ORDER}
    if data_entries != expected_data_entries:
        raise PackageError("Package data directory does not contain exactly the contract files")
    manifest_path = package / "manifest.json"
    checksums_path = package / "CHECKSUMS.sha256"
    if not manifest_path.is_file() or not checksums_path.is_file():
        raise PackageError("Package is missing manifest.json or CHECKSUMS.sha256")
    checksum_entries: dict[str, str] = {}
    for line in checksums_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        digest, separator, relative = line.partition("  ")
        if not separator or len(digest) != 64 or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise PackageError(f"Invalid checksum line: {line!r}")
        if relative in checksum_entries:
            raise PackageError(f"Duplicate checksum entry: {relative}")
        checksum_entries[relative] = digest
    if "manifest.json" not in checksum_entries:
        raise PackageError("CHECKSUMS.sha256 does not cover manifest.json")
    if sha256_file(manifest_path) != checksum_entries["manifest.json"]:
        raise PackageError("manifest.json checksum mismatch")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise PackageError(f"Invalid manifest.json: {error}") from error
    if manifest.get("format") != FORMAT:
        raise PackageError(f"Unsupported package format: {manifest.get('format')!r}")
    if manifest.get("status") != "COMPLETE":
        raise PackageError("Package is not COMPLETE")
    source = manifest.get("source")
    if not isinstance(source, Mapping) or not source.get("identity_fingerprint") or not source.get("server_fingerprint"):
        raise PackageError("Manifest source identity is missing")
    schema = manifest.get("schema")
    if not isinstance(schema, Mapping) or not schema.get("fingerprint"):
        raise PackageError("Manifest schema fingerprint is missing")
    resolve_schema_version(str(schema.get("schema_migrations_max", "")))
    entries = manifest.get("tables")
    if not isinstance(entries, list):
        raise PackageError("Manifest tables are missing")
    names = [entry.get("name") for entry in entries]
    if names != list(TABLE_ORDER):
        raise PackageError("Manifest tables are missing, extra, or out of contract order")
    expected_files = {f"data/{table}.jsonl" for table in TABLE_ORDER}
    if set(checksum_entries) != expected_files | {"manifest.json"}:
        raise PackageError("CHECKSUMS.sha256 does not cover exactly the contract files")
    selection = manifest.get("selection")
    if not isinstance(selection, Mapping):
        raise PackageError("Manifest selection is missing")
    expected_package_id = package_id_for(
        selection,
        {entry["name"]: entry["sha256"] for entry in entries},
        {
            "format": manifest.get("format"),
            "tool_version": manifest.get("tool_version"),
            "source": source,
            "schema": schema,
        },
    )
    if manifest.get("package_id") != expected_package_id:
        raise PackageError(
            "package_id does not match selection, source identity, schema and table checksums"
        )
    decoded: dict[str, list[dict[str, Any]]] = {}
    for entry in entries:
        table = entry["name"]
        relative = entry["file"]
        if relative != f"data/{table}.jsonl":
            raise PackageError(f"Unexpected data file for {table}: {relative}")
        path = package / relative
        if not path.is_file():
            raise PackageError(f"Missing data file: {relative}")
        if sha256_file(path) != checksum_entries.get(relative):
            raise PackageError(f"Checksum mismatch: {relative}")
        if sha256_file(path) != entry.get("sha256"):
            raise PackageError(f"Manifest checksum mismatch: {relative}")
        columns = entry.get("columns")
        if not isinstance(columns, list) or not all(isinstance(column, str) for column in columns):
            raise PackageError(f"Invalid columns for {table}")
        spec = table_spec(table)
        for column in columns:
            if not spec.keep(column):
                raise PackageError(f"Column {table}.{column} is not allowed by the package contract")
        table_rows = read_jsonl(path, columns)
        if len(table_rows) != int(entry.get("row_count", -1)):
            raise PackageError(f"Row count mismatch for {table}")
        for row in table_rows:
            key = tuple(row.get(column) for column in spec.key_columns)
            if any(value is None for value in key):
                raise PackageError(f"Null key in {table}: {key}")
        decoded[table] = table_rows
    return manifest, decoded


def package_file_hashes(package: Path) -> dict[str, str]:
    return {
        entry["file"]: entry["sha256"]
        for entry in json.loads((package / "manifest.json").read_text(encoding="utf-8"))["tables"]
    }
