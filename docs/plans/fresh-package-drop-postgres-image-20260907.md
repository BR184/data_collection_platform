<!-- 计划状态：实施与验证全部完成，待用户确认提交 -->

## 进度与中间物

状态：**实施与验证全部完成，待用户确认提交。**

- 已完成：打包脚本删除 PG 镜像交付链路（save/manifest/required_files）、PG tar 通用禁止、compose PG 引用收敛常量、README 守卫重写；契约测试 34/34 全绿（含新增 4 项）；plan-only 解析正常；规范文档四处同步；仓库四项门禁全绿；progress.md 已记录。
- 提交范围（待用户确认）：`scripts/package_intranet_offline.py`、`scripts/test_package_intranet_offline.py`、`deploy/intranet-offline-packaging-standard.md`、`docs/progress.md`（追加条目）、本计划文档。与 D-10 单元（backend/ + golden 快照 + decisions.md）无文件交集，可独立提交。

## 恢复线索

- 当前阶段：收尾。恢复后首条命令 = `git status --short` 确认工作树（本单元文件：scripts/package_intranet_offline.py、scripts/test_package_intranet_offline.py、deploy/intranet-offline-packaging-standard.md、本文档、docs/progress.md；与 D-10 单元无文件交集，除 progress.md 追加）。
- 验证入口：仓库根 `python -m unittest scripts.test_package_intranet_offline`（契约测试 34 项）；`python scripts/package_intranet_offline.py --mode fresh-empty --plan-only`（端到端解析，不写产物）。

## 目标与边界

**用户原始需求**（2026-09-07）：0904 全新包仍生成了 pg_16 镜像，但 PG 一直没有改动；镜像只需要生成最新版的前后端镜像。用户补充事实：`D:\projects\data_collection_platform_deploy` 是历史打包归档（含 20260803/20260806/20260904 三个 qaflex-full 包），过去部署时目标机已 load 过 PG 镜像。

- 成功标准：fresh-empty 打包器不再导出/记录/要求 `postgres_16-alpine.tar`；任何模式包内出现 PG 镜像 tar 即拒绝；包内 README 的镜像加载步骤不再含 PG 行并带目标机镜像守卫；契约测试与规范文档同步；保数据更新包行为零变化。
- 明确禁止：保留兼容开关（不留 `--include-postgres-image` 之类双轨）；golden/后端套件不涉及（本单元纯打包器域）。

## 约束与背景

- `postgres:16-alpine` 是可变 tag：旧机制每次全新包把打包机本地缓存快照 docker load 到现场，会静默替换 115 上所有实例（含 20001 生产）共用的那份镜像——去掉后消除该跨实例影响面。
- compose 中 postgres 服务定义不变（仍引用 `image: postgres:16-alpine`），镜像由目标机既有本地镜像提供。
- 历史全新包归档 = PG 镜像的既有离线来源（真正新服务器缺镜像时从历史包加载）。

## 证据与根因

- 0904 包带 PG tar 的根因 = `build_and_save_images` fresh 分支无条件 `docker image inspect` + `docker save`（scripts/package_intranet_offline.py L1426-1428），`required_files`（L1497-1505）与 manifest（L1396-1402）强制要求。
- `POSTGRES_IMAGE = "postgres:16-alpine"` 自打包器诞生（b025ef03）零变更（git -S 实证）。
- 打包机本地 PG 镜像 inspect+save 不是构建，仅导出缓存快照。

## 方案与步骤

1. 计划文档（本文档）。
2. 脚本：删 fresh 的 PG inspect/save、manifest postgres 条目、required_files 条目；`postgres_16-alpine.tar` 移入通用 forbidden（任何模式拒绝）；compose 的 PG 镜像引用改用常量（单一事实源）；README 模板删 PG load 行并加「目标机 `docker image inspect postgres:16-alpine` 守卫 + 缺失时从历史全新包加载」说明。
3. 契约测试：fresh required_files 完整集合断言（无 PG tar）、fresh 模式拒 PG tar、fresh manifest images 仅 backend/frontend、README 断言；既有 incremental 拒 PG tar 断言保持。
4. 验证：契约测试全绿 + plan-only 解析成功 + 四项仓库门禁。
5. 规范文档：全新包结构契约、发布策略例外段、发布验收统一（任何包不含 PG 镜像）、缺镜像加载指引。
6. progress.md 记录 + 计划收口。

## 决策记录

- 已选（用户拍板 2026-09-07）：全新包默认且唯一行为 = 不携带 PG 镜像，无开关。
- 否决：A 方案（默认带 + `--reuse-postgres-image` 显式省略）——用户明确「不用，不带PG镜像即可」；理由：115 是唯一内网主机且已 load 过 PG 镜像，历史包归档可满足真新服务器需求。
- 否决：保留 `--include-postgres-image` 开关——兼容双轨，违反红线。

## 接口契约

- `RELEASE-MANIFEST.json`（fresh-empty）：`target.images` 仅含 `backend`、`frontend` 两键，不再有 `postgres` 键。
- fresh 包 `docker-images/` 仅含 `qa-flex-platform-backend_<tag>.tar` 与 `qa-flex-platform-frontend_<tag>.tar`。
- `validate_forbidden_delivery_items`：`docker-images/postgres_16-alpine.tar` 两种模式均禁止。
- 打包机不再要求本地存在 `postgres:16-alpine` 镜像。

## 风险与假设

- 假设：未来全新包落点仍是已部署过平台的 115 服务器；真正的新服务器场景由历史包归档提供 PG 镜像（规范中写明）。
- 已核：`docker compose config` 解析不需要本地存在镜像；scan_empty_package 的 forbidden 模式与 PG tar 无关。
- 注意：契约测试数量将从 30 项增加（新增 2-3 用例），门禁判读以全绿为准。
