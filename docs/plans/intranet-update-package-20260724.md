# 20260724 内网保数据更新包

## 进度与中间物

- 状态：已完成打包与本地升级验证。
- 已完成：生成 Ubuntu 24.04 amd64、无联网、平台与 GitLab/源库分离拓扑的保数据增量包。
- 已完成：本地升级模拟栈完成后端迁移、前端切换、健康检查和数据库不变量核验。
- 产物：18181 专用 `D:/projects/data_collection_platform_deploy/qa-flex-platform-intranet-20260724-incremental-update-cc-product-loading-alias-issue-from-20260714-fact-rebuild-ubuntu2404-offline.tar.gz`；此前按 20260721 基线生成的同版本包不适用于 18181。

## 恢复线索

- 当前阶段：工作单元收尾，无需恢复编码。
- 恢复后首条命令：`Get-Content D:/projects/data_collection_platform_deploy/qa-flex-platform-intranet-20260724-incremental-update-cc-product-loading-alias-issue-from-20260714-fact-rebuild/VERSION.txt`。

## 目标与边界

- 目标：交付只替换后端/前端业务镜像的离线保数据更新包，保留 PostgreSQL 容器、volume、镜像表、事实表、同步状态和平台配置。
- 本次数据影响：客户问题别名迁移改变 issue 事实生成口径，包要求部署后执行 `issue` 范围事实层重建；不触发 GitLab 全量同步。
- 禁止：删除数据库 volume、清空平台数据、重建 PostgreSQL、在目标内网现场构建镜像。

## 约束与背景

- 目标 OS：Ubuntu 24.04 amd64；平台公开端口 18181，后端端口 18080，LDAP 地址 `http://172.22.10.116:80`。
- 18181 打包基线镜像：`qa-flex-platform-backend:20260714-466478a4-working`、`qa-flex-platform-frontend:20260714-466478a4-working`。
- 当前目标 Flyway：`20260723.01`。

## 证据与根因

- 工作树包含客户问题别名迁移 `V20260723_01__seed_customer_issue_customer_aliases.sql` 及客户问题加载韧性修复；因此不能归类为纯展示更新。
- 本地升级前同步/事实活动任务数为 0，PostgreSQL healthy；升级脚本成功生成数据库 custom-format 备份并保留 PostgreSQL 容器 ID。

## 方案与步骤

1. 使用 `scripts/package_intranet_offline.py --mode incremental-update` 生成业务镜像、升级覆盖文件、升级/回滚脚本、SHA256 和 tar.gz。
2. 先重建后端并等待 Flyway/health，再重建前端；只允许应用容器变化。
3. 部署后检查健康端点、前端入口、Flyway 版本、受保护表行数、迁移别名数量和归档完整性。

## 决策记录

- 选择保数据增量包，理由是本次必须保留既有平台数据和同步状态。
- 事实重建范围选择 `issue`，理由是当前数据口径变更只涉及客户问题别名/issue 事实；集成测试事实不在本包变更范围。

## 接口契约

- 包内升级入口：`deploy/upgrade.sh <existing-deployment-dir>`。
- 包内回滚入口：`deploy/rollback-app.sh <existing-deployment-dir> <backup-dir>`。
- 无新增 HTTP API；事实重建由既有平台 `/api/facts/rebuild` 链路执行。

## 风险与假设

- 真实内网服务器必须使用现场实际基线镜像，并在升级前无活动同步/事实任务；脚本不接受未审查的已有 compose override。
- 本地验证使用 Git Bash 模拟 Ubuntu shell；目标服务器仍需按 README 在 Ubuntu 24.04 上执行，并在 LDAP 可达环境完成登录和 issue 事实重建验收。

## 实际验证结果

- 通过：前端发布测试 14 项、前端生产构建、后端 `-DskipTests clean package`、Docker 业务镜像构建与 jar SHA 校验。
- 通过：升级脚本；PostgreSQL 容器 ID `e7f1632a...53707` 前后一致；Flyway `20260720.06 -> 20260723.01`；保护表行数 diff 为空；别名表 5 条。
- 通过：后端 health 200、前端入口 200、三容器 healthy、包内 138 文件 SHA256 全部匹配、归档 SHA256 `52a49d3c1e27d472c8cbd002d22635f7bcb70e85b32e23553d223f53898215b1`。
