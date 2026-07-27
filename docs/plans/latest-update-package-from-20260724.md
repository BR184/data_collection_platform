# 最新平台更新包与 20260724 基线部署验证计划

## 进度与中间物

- 已完成：读取仓库规则、进度、离线发布标准与 Docker 技能；确认用户指定的 20260724 历史更新包归档和解压目录存在，目标前后端镜像均为 `20260724-8c1c39ad-working`。
- 已完成环境预检：本地隔离栈 `qa-flex-platform-upgrade-sim-20260721-v2` 使用 19181/19080/19432 和专用 volume；当前无运行中同步/事实任务，PostgreSQL 容器 ID 为 `e7f1632a...b53707`，存在可恢复 20260724 override 的 r3 升级备份。
- 已完成文件/变更：本计划；上一工作单元已实现短包名打包规则，本轮尚未生成新包或修改容器。
- 测试状态：短包名契约 22 项已通过；本轮完整构建、归档、部署、回滚、再次部署尚未执行。
- 当前进行点：用已有受控备份把隔离栈从 r3 恢复为用户指定的 20260724 应用基线，然后生成最新更新包。

## 恢复线索

- 当前阶段：构建前环境准备。
- 恢复后首条命令：`docker ps --filter "label=com.docker.compose.project=qa-flex-platform-upgrade-sim-20260721-v2" --format "{{.Names}}|{{.Image}}|{{.Status}}"`。
- 上份计划/commit：短包名计划已完成并删除；长期规则见 `deploy/intranet-offline-packaging-standard.md`。工作树包含其他已确认的最新平台改动且未提交，本包按用户要求包含当前完整工作树。

## 目标与边界

- 用户需求：按新打包要求将本地最新版平台制作成保数据更新包，目标现场容器已存在且最后部署过指定 20260724 更新包；提供适合 Docker 初学者的分步部署指令；完成本地真实部署测试。
- 成功标准：生成 `qaflex-update-<release-id>.tar.gz`；基线严格为 20260724 目标镜像；归档结构、manifest、SHA-256 和镜像摘要有效；隔离栈从 20260724 首次升级、应用回滚、再次升级均成功；PostgreSQL 容器 ID 与受保护表行数不变；前后端健康且宿主端点可访问；最终 README/交付说明给出逐步诊断命令而非一键部署。
- 明确禁止：不操作开发端口 18181/18080/15432；不访问内网；不删除容器 volume；不执行 `docker compose down -v`；不把 `.env`、数据库或日志打进包；不执行事实重建或 GitLab 同步；不回退用户已有代码改动。

## 约束与背景

- 目标环境是 Ubuntu 24.04 amd64 内网离线，数据库与平台可位于不同服务器；更新包只携带前后端镜像和受控升级文件，不携带 PostgreSQL 镜像。
- 用户现场已建立容器，本次只通过 Compose override 重建前后端应用容器；数据库连接、端口、凭据和 volume 继续使用现场 `.env` 与基础 Compose。
- 当前工作树非干净但用户明确要求“本地最新版”，因此 manifest 必须记录完整 commit 和 working 状态；短包名不编码这些信息。
- 20260724 是用户明确的现场应用基线；不能用本机当前 r3 镜像作为新包基线。

## 证据与根因

- 指定旧归档为 258,034,613 bytes，SHA-256 `7d4b968e...091a1`；其历史结构把 `docker-compose.release.yml` 放在 `deploy/` 子目录，打包器已支持该读取位置。
- 该发布 Compose 指向 `qa-flex-platform-backend/frontend:20260724-8c1c39ad-working`。
- 本机 19181/19080 当前运行 r3 镜像，不符合现场基线；r3 最近两份升级备份都保存了 20260724 override，可由 r3 `rollback.sh` 受控恢复且等待健康。
- 隔离栈 PostgreSQL 使用 `qa-flex-platform-upgrade-sim-20260721-v2_qaflex_pgdata`，与开发库 `qaflex-dev-postgres-15432` 隔离；当前 Flyway `20260727.03`，事实表样本各 1 行，适合验证前向迁移下的应用回滚但不会伪装成数据库降级。

## 方案与步骤

1. 记录隔离栈 PostgreSQL ID、Flyway、保护表行数与当前 Compose；调用已验证的 r3 回滚脚本恢复 20260724 应用 override，并确认前后端健康和数据库不变。
2. 运行打包器完整更新模式，以用户指定的 20260724 解压目录为 `--baseline-dir`，声明 `--require-fact-rebuild --fact-rebuild-scope issue`；不跳过构建、测试、镜像或归档步骤。
3. 解压最终归档，校验包外/包内 SHA-256、manifest、Compose、Ubuntu Bash 语法、禁止路径和镜像内容摘要。
4. 在隔离栈逐步执行新包 `upgrade.sh`，记录每个阶段、健康、Flyway、PostgreSQL ID、保护表前后行数；随后应用回滚到 20260724，再使用同一包第二次升级。
5. 生成面向初学者的分步部署说明：上传/校验/解压/查看 manifest/确认现场目录/检查容器与任务/执行升级/逐项验收/按明确备份目录回滚；每一步单独命令并说明预期结果与停止条件。
6. 更新进度，执行文档和工作树门禁；长期事实归档后删除本计划，保留最终包与健康隔离栈。

## 决策记录

- 已选：以指定 20260724 更新目录为构建基线，以 r3 备份恢复本地隔离栈到相同应用镜像后测试，确保构建和运行基线一致。
- 已选：声明 issue 事实重建，因为最新版包含 20260724 之后的客户问题事实规则；部署脚本不自动重建，现场升级健康后由管理员在页面单独执行。
- 已选：完整执行升级、回滚、再次升级，而不是只看容器启动，以验证连续发布和故障恢复。
- 否决：直接在当前 r3 栈上应用以 20260724 为基线的新包，脚本应正确拒绝且不能代表现场路径。
- 否决：在 18181 开发环境测试，原因是会影响用户本地开发服务和数据。
- 否决：提供一条复制即运行的长命令，原因是用户需要逐步定位失败阶段。

## 接口契约

- 构建：`python scripts/package_intranet_offline.py --mode incremental-update --baseline-dir <20260724-dir> --require-fact-rebuild --fact-rebuild-scope issue`。
- 包名：`qaflex-update-<release-id>.tar.gz`，目录、manifest 和前后端镜像复用同一 `release-id`。
- 现场升级：在解压包目录旁保留当前部署目录，执行 `bash <update-dir>/upgrade.sh <deployment-dir>`；脚本校验基线、任务、备份、健康、Flyway、行数和 PostgreSQL ID。
- 应用回滚：`bash <update-dir>/rollback.sh <deployment-dir> <deployment-dir>/upgrade-backups/<backup-dir>`；不自动执行数据库恢复。
- 无新增 HTTP API、数据库表或业务数据格式。

## 风险与假设

- 完整工作树包含其他任务的最新版改动；构建失败时只修复真实根因，不回退或隐藏这些改动。
- 本地 LDAP 外部依赖可能不可达；容器健康、未认证页面和宿主 HTTP 可验证，真实 LDAP 登录仍属于内网验收。
- Flyway 为前向迁移，应用回滚不降低数据库 schema；20260724 镜像必须能在当前已前移 schema 上健康运行，已有 r3 回滚已验证该条件。
- 完整无缓存镜像构建和导出可能持续较久；D 盘当前约 597 GB 可用，Docker 镜像约 28.8 GB。
- 根目录既有 `.tmp-*.log` 预计继续阻塞运行产物位置门禁，本轮不擅自删除。
