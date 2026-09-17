# 全新包 .env 直出（2026-09-07）

## 进度与中间物

- 已完成：打包脚本 5 处、契约测试 3 处、规范文档 4 处全部改完；契约测试 34/34 全绿；仓库四项门禁全绿；progress.md 已记录；已提交 0e929604（同单元含 plan 文档与 progress 记录）。
- 状态：实施与验证全部完成。

## 恢复线索

- 当前阶段：实施已完成，剩余门禁与提交。
- 恢复后首条命令：`python scripts/test_package_intranet_offline.py`
- 关联提交：本单元基于 47c231ea（去 PG 镜像）之上。

## 目标与边界

- 用户原始需求：全新包不知从哪个版本起生成 `.env.example` 而非 `.env`，部署时需手动 cp/改名，要求改回直出。
- 成功标准：fresh-empty 包直接包含可用的 `.env`（发布级默认值），包内不再出现 `.env.example`，README 无复制步骤；增量包行为零变化。
- 禁止：改动增量包 env 语义（现场 `.env` 唯一事实源约束保持）。

## 约束与背景

- 引入版本考证：b025ef03（2026-07-08 打包脚本初版）同时生成 `.env.example` 与 `.env`；4cac4c19（2026-07-27）删 `.env` 直出只留 `.env.example`（提交说明未记载动机，属混合提交顺带改动）。
- `env_content` 仅含发布级默认值（qaflex 占位密码、内网地址），无真实密钥，直出无安全问题。
- 同工作单元背景：去 PG 镜像（47c231ea）已完成。

## 证据与根因

- `scripts/package_intranet_offline.py`：write_operational_files 1319、required_files 1499、validate_forbidden_delivery_items 1532、validate_layout 1546、fresh_readme 第 4 节 736-740。
- 部署摩擦实证：30001 包（20260904）README 要求 `cp .env.example .env`。

## 方案与步骤

1. fresh-empty：`write_text(.env, env_content(ctx))`；README 第 4 节改为「`.env` 已写入……如需调整先编辑」。
2. required_files fresh 分支 `.env.example` → `.env`；compose 解析校验 `--env-file .env`。
3. 增量包禁带清单删 `.env.example`（全链路退役、无生成路径），保留 `.env` 禁带。
4. 契约测试：fresh required 集合断言改 `.env`；`assertNotIn(".env", normal)` 翻转为 `assertIn`；README 测试补 `assertNotIn("cp .env.example")` 锁契约。
5. 规范文档 4 处同步（更新包不含清单、全新包结构树、部署说明、COMPOSE_PROJECT_NAME 条款主语）。

## 决策记录

- 已选：直出 `.env`，彻底删除 `.env.example` 概念（AGENTS.md 演进红线：不留双轨）。
- 否决：保留 `.env.example` + 额外生成 `.env`（双文件冗余，无消费方）。

## 接口契约

- 无新增 API/表结构。fresh-empty 包内 `.env` 语义 = 原 `.env.example` 内容直接生效。

## 风险与假设

- 假设：无任何脚本/文档在包外引用包内 `.env.example`（已 grep 全仓核实，仅历史计划文档提及）。
- 风险：低；增量包 upgrade/rollback/backup 脚本只操作现场 `.env`，与包内文件无关。
