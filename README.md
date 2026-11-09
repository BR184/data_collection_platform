<!-- DOC_STATUS_START -->
> 文档状态：常驻入口
> 说明：项目启动、运行和模块说明入口，继续维护。
<!-- DOC_STATUS_END -->

# QA Flex Platform

## 接手入口

本仓库的运行代码以已部署的 `qaflex-update-20261105T170324Z-25219abeda3c.tar.gz` 为基准，源码提交为 `d9fbdca7f30b4f3ab3d7b425611d380292a3fecf`。2026-11-09按用户要求归并正式版本与main历史；本次交接不增加业务功能。版本差异、验证与后续工作见[当前进度](docs/progress.md)，不要从历史任务分支恢复运行代码。

建议按以下顺序阅读：

1. [当前进度](docs/progress.md)：确认生产版本、main变化、当前待办和验证边界。
2. [架构与接手导航](docs/architecture.md#接手导航)：理解模块职责、数据流和源码入口。
3. [产品范围](docs/product.md)和[页面业务规则](docs/platform-page-business-rules.md)：确认页面行为和数据口径。
4. [BI入口](docs/bi-dashboard/README.md)：查询BI独立口径、CAT契约及模块说明。
5. [部署标准](deploy/intranet-offline-packaging-standard.md)、[本次生产包手册](deploy/runbooks/dashboard-timeout-update.md)和[数据库恢复手册](deploy/runbooks/database-backup-restore.md)：接管环境与发布。

开发脚本统一用PowerShell 7执行，例如 `pwsh -File backend/run-backend.ps1`。下面本机路径和端口只是开发默认值，实际环境以所接管实例的配置为准；开始前必须分离测试数据库、服务端口和外部写入目标。

Standard separated project structure:

- `backend/`: Java 21 + Spring Boot
- `frontend/`: Vue 3 + Element Plus
- `tools/`: local Java and Maven toolchain

## Start backend

```powershell
cd backend
.\run-backend.ps1
```

本机开发后端默认连接 Docker 容器 `qaflex-dev-postgres-15432`：
`127.0.0.1:15432/qaflex`，账号为 `qaflex`。不要误连 `qaflex-postgres`
（`127.0.0.1:25432`，独立的 `qaflex/qaflex` 凭据）。

`run-backend.ps1` 会自动加载 `backend/.env.local`。该文件仅供本机使用、已被 Git 忽略；
进程环境变量优先于该文件，方便 CI 和部署显式覆盖本地配置。
首次启动先复制 `backend/.env.local.example` 为 `backend/.env.local`。当前产品默认使用
LDAP 认证；本地账号模式必须显式设置 `PLATFORM_AUTH_PROVIDER=local`，不会在配置缺失时
静默接管 LDAP 登录。

Backend default URL:

- `http://localhost:18080/`

## Start frontend

```powershell
cd frontend
.\run-frontend.ps1
```

Frontend default URL:

- `http://localhost:18181/`

## Verify local environment

Use the project environment script instead of relying on a user PowerShell profile:

```powershell
.\scripts\verify-local.ps1
```

The verification script checks the local toolchain, compiles the backend, and runs the frontend typecheck.

For manual commands:

```powershell
. .\scripts\dev-env.ps1
cd backend
mvn -q -DskipTests compile

cd ..\frontend
npm.cmd run typecheck
```

## Documentation

- 当前产品、架构、进度和页面业务口径分别位于 `docs/product.md`、`docs/architecture.md`、`docs/progress.md`、`docs/platform-page-business-rules.md`。
- 架构决策位于 `docs/decisions/`；进行中的工作单元计划位于 `docs/plans/`；内网发布和同步运维说明位于 `deploy/`。
