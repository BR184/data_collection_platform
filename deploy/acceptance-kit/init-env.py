#!/usr/bin/env python3
"""仅在新目录生成测试配置和目标凭据；已有文件时拒绝覆盖，不连接数据库。"""
from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import secrets


def main() -> None:
    """生成随机项目身份、独立密码和本机文件，打印后续需修改的非秘密配置。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", required=True, help="浏览器能访问的内网服务器IPv4或DNS名，不含协议或端口")
    args = parser.parse_args()
    if not args.server or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-" for c in args.server):
        parser.error("--server仅接受IPv4或DNS名")
    root = Path.cwd()
    env = root / ".env"
    secret_dir = root / ".secrets"
    if env.exists() or secret_dir.exists():
        parser.error(".env或.secrets已存在，拒绝覆盖；请核对是否为新的测试目录")
    os.umask(0o077)
    token = secrets.token_hex(24)
    glpass = secrets.token_hex(24)
    # Omnibus 会把 sql_user_password 前缀为 md5 并交给 PostgreSQL 原样存储，校验值按 md5(口令+用户名) 生成。
    glpass_md5 = hashlib.md5((glpass + "gitlab").encode()).hexdigest()
    values = {
        "COMPOSE_PROJECT_NAME": "dcp-delay-test-" + secrets.token_hex(5),
        "BACKEND_IMAGE": "qa-flex-platform-backend:20261026-d52-delay-candidate",
        "PLATFORM_DB_PASSWORD": secrets.token_hex(24),
        "PLATFORM_ADMIN_PASSWORD": secrets.token_hex(24),
        "PLATFORM_APPROVAL_PASSWORD": secrets.token_hex(24),
        "GITLAB_ROOT_PASSWORD": secrets.token_hex(24),
        "GITLAB_DB_PASSWORD": glpass,
        "GITLAB_DB_PASSWORD_MD5": glpass_md5,
        "GITLAB_PUBLIC_URL": f"http://{args.server}:20280",
        "FRONTEND_PORT": "20201", "BACKEND_PORT": "20200",
        "PLATFORM_DB_PORT": "25433", "GITLAB_WEB_PORT": "20280", "GITLAB_DB_PORT": "25434",
        "WEB_BIND": "127.0.0.1", "BACKGROUND_JOBS_ENABLED": "false",
        "DELETE_RECONCILIATION_ENABLED": "false",
    }
    with env.open("x", encoding="utf-8", newline="\n") as output:
        output.write("".join(f"{key}={value}\n" for key, value in values.items()))
    secret_dir.mkdir(mode=0o700)
    (secret_dir / "target.pgpass").write_text(f"127.0.0.1:25434:gitlabhq_production:gitlab:{glpass}\n", encoding="utf-8")
    (secret_dir / "target-token").write_text(token + "\n", encoding="utf-8")
    (root / "work").mkdir(mode=0o700)
    (root / "reports").mkdir(mode=0o700)
    print("已生成.env和目标专用凭据。先核对五个端口、GITLAB_PUBLIC_URL和WEB_BIND；改目标PG端口时同步修改target.pgpass。")


if __name__ == "__main__":
    main()
