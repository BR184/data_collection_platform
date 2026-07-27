<!-- DOC_STATUS_START -->
> 文档状态：常驻发布标准
> 说明：定义 QA Flex Platform 面向 Ubuntu 24.04 amd64 内网环境的离线发布包、连续升级和回滚契约。
<!-- DOC_STATUS_END -->

# 内网离线打包标准

## 适用范围与拓扑

本标准适用于无公网访问的 Ubuntu 24.04 amd64 平台服务器。当前运行拓扑由 Compose 管理三个容器：

- `qaflex-postgres`：平台自有 PostgreSQL，只保存平台配置、镜像、事实、同步状态和业务维护数据。
- `qaflex-backend`：Spring Boot 后端，通过 Compose 内部网络访问平台库。
- `qaflex-frontend`：Nginx 前端，将 `/api/` 代理到后端。

GitLab Web、GitLab PostgreSQL、LDAP、老平台 MySQL/MongoDB 均是其他服务器上的外部系统，不进入本包。GitLab 等源数据库只通过平台 UI 配置为只读数据源，不能写入平台库的 `DATASOURCE_URL`。

115 地址的 18181、20001 实例均由 20260714 全新部署包建立，且已应用 20260721 LDAP 和 20260724 CC_PRODUCT 更新。它们今后分别作为独立现场实例维护；端口相同、初始包相同或应用过同名更新包，均不能替代升级前的现场基线检查。

## 发布策略

### 默认：保数据更新包

日常发布只制作保数据更新包。更新包加载唯一 tag 的前后端镜像，保留现有 PostgreSQL 容器、volume、`.env`、同步状态、镜像表、事实表、平台配置和用户数据。

以下变化需要更新包：前后端代码、Nginx 配置、Flyway 迁移、认证/安全运行参数、事实规则、统计口径和页面资源。是否重建事实层由发布清单显式声明：

- 修改事实字段、非法判定、归一化、统计快照、聚合口径或既有事实结果时，必须声明重建范围。
- 纯页面样式、文案、布局且不改变事实和统计结果时，不重建事实层。
- 事实重建只基于既有 ODS 重算事实与快照，不重新拉取 GitLab，不删除同步状态或平台数据。

### 例外：全新/灾备部署包

仅在新服务器首次部署、明确清空环境或灾难恢复时制作 `fresh-empty` 包。它包含平台 PostgreSQL 镜像和完整基础 Compose，但不包含任何数据库文件、dump、volume、ODS、事实或用户数据。

Docker/Compose 的 Ubuntu deb 不是应用运行产物。目标服务器尚未安装 Docker 且确需同包交付时，才显式使用 `--include-offline-docker-debs`；已有容器的更新包永远不携带这些 deb。

## 包结构契约

### 保数据更新包

```text
qaflex-update-<release-id>/
├── docker-images/
│   ├── qa-flex-platform-backend_<image-tag>.tar
│   └── qa-flex-platform-frontend_<image-tag>.tar
├── docker-compose.release.yml
├── upgrade.sh
├── rollback.sh
├── RELEASE-MANIFEST.json
├── SHA256SUMS.txt
└── README-INCREMENTAL-DEPLOY.md
```

每项职责唯一：

| 内容 | 必要性 |
| --- | --- |
| `docker-images/` | 目标机无网络，必须直接 `docker load` 已构建业务镜像。 |
| `docker-compose.release.yml` | 只声明本次应用镜像和必须同步的运行参数；升级脚本将其安装为现场 override。 |
| `upgrade.sh` | 统一执行基线、任务、磁盘、备份、Flyway、健康、行数守恒和 PostgreSQL 容器不变检查。手工堆命令不能替代。 |
| `rollback.sh` | 恢复升级前 `.env`、基础 Compose 和旧 override，校验基线镜像，先等待后端健康再等待前端健康；不擅自回写数据库。 |
| `RELEASE-MANIFEST.json` | 机器可读地记录包类型、commit、工作树状态、目标/基线镜像、镜像与源码摘要、Flyway 和事实重建要求。 |
| `SHA256SUMS.txt` 与包外 `.sha256` | 分别校验包内文件和离线传输后的完整归档。 |
| `README-INCREMENTAL-DEPLOY.md` | 内网现场无法访问仓库文档时的同版本操作与验收入口。 |

更新包明确不包含：

- `backend/`、`frontend/`、Dockerfile、`.dockerignore`：它们只是镜像构建上下文，应用内容已存在于镜像 tar；重复交付没有运行用途。
- `.env` 或 `.env.example`：现场 `.env` 是该实例配置的唯一事实源，更新包不得用开发机模板覆盖数据库连接、端口或凭据。
- `postgres` 镜像、`offline-debs/`：应用更新不创建平台库，也不安装容器运行时。
- 数据 dump、volume、数据库物理文件或运行日志：备份只能在现场升级前生成并留在现场。
- `VERSION.txt`：已由结构化 `RELEASE-MANIFEST.json` 取代，禁止双份版本事实源。

### 全新/灾备包

```text
qaflex-full-<release-id>/
├── docker-images/
│   ├── postgres_16-alpine.tar
│   ├── qa-flex-platform-backend_<image-tag>.tar
│   └── qa-flex-platform-frontend_<image-tag>.tar
├── docker-compose.yml
├── .env.example
├── RELEASE-MANIFEST.json
├── SHA256SUMS.txt
└── README-INTRANET-DEPLOY.md
```

只有显式选择时才增加 `offline-debs/ubuntu-24.04-amd64/`。全新包同样不携带 `backend/`、`frontend/` 或真实 `.env`；部署人员必须从 `.env.example` 建立现场 `.env` 并设置实际配置。

## 镜像与发布身份

应用镜像固定为：

```text
qa-flex-platform-backend:<release-id>
qa-flex-platform-frontend:<release-id>
```

每次执行打包器时自动生成 `YYYYMMDDTHHMMSSZ-<12 hex>` 格式的 `release-id`。UTC 时间负责排序和人工定位，6 字节密码学随机熵区分同秒及并发构建；包目录通过原子创建拒绝碰撞，归档和校验文件存在时同样拒绝覆盖。因此每个成功生成的包都有唯一名称，不依赖人工命名。

包名只允许包含产品简称、包类型和发布 ID：全新包为 `qaflex-full-<release-id>.tar.gz`，更新包为 `qaflex-update-<release-id>.tar.gz`。Ubuntu 版本、离线属性、端口、commit、工作树状态、功能说明、Flyway 和事实重建范围均写入 `RELEASE-MANIFEST.json`，不得重复拼接到文件名。前后端镜像复用同一 `release-id`，使一次发布的目录、归档、清单和镜像形成单一身份。

打包器在最终归档之外创建临时 Docker build context，构建完成后立即销毁。打包阶段必须核对镜像内 `/app/app.jar` 与本地生产 JAR 的 SHA-256，并核对前端镜像内 `index.html` 与生产 `dist`；审计摘要写入发布清单，不复制裸产物。

## 基线与连续更新

更新包必须显式传入 `--baseline-dir`，其值只能是：

1. 当前现场部署目录的受控副本，包含 `docker-compose.yml`，以及存在时的 `docker-compose.override.yml`；或
2. 当前实例最后一次成功应用的更新包目录，包含 `docker-compose.release.yml`。

镜像基线读取优先级固定为现场 override、上次发布 Compose、历史更新包的 `deploy/docker-compose.release.yml`、基础 Compose。此优先级只用于从当前历史格式迁移；新包一律采用根目录发布 Compose。

两个内网实例必须分别确认当前合并 Compose。不能因两者都源自 20260714 就继续把 20260714 当作永久基线；已应用 20260724 后，下一包的预期基线应是 20260724 的目标前后端镜像。

升级允许现场已有标准 `docker-compose.override.yml`。脚本在变更前备份它，随后用本次 `docker-compose.release.yml` 替换；回滚时恢复上一份 override。禁止要求操作者先手工删除 override，因为这会丢失当前镜像基线和回滚证据。

## 打包命令

默认更新包：

```powershell
python scripts\package_intranet_offline.py `
  --mode incremental-update `
  --baseline-dir D:\path\to\current-deployment-or-last-update `
  --require-fact-rebuild `
  --fact-rebuild-scope issue
```

不需要事实重建时省略 `--require-fact-rebuild`。范围只能是 `issue`、`merge-request` 或 `all`。

全新/灾备包：

```powershell
python scripts\package_intranet_offline.py --mode fresh-empty
```

只有目标机缺少 Docker 时增加：

```powershell
--include-offline-docker-debs --template-package-dir D:\path\to\verified-template
```

打包器默认执行前端发布测试和生产构建、后端生产打包、Flyway 源码/JAR 集合校验、无缓存镜像构建、镜像内容校验、Compose 解析、禁止数据扫描、SHA-256 校验和归档清单校验。任何一步失败都不得交付。

## 现场升级流程

在更新包目录校验：

```bash
sha256sum -c SHA256SUMS.txt
```

进入当前部署目录并执行：

```bash
cd <current-deployment-dir>
bash ../<update-package>/upgrade.sh "$PWD"
```

脚本必须在修改应用容器前完成：

1. 解析现场基础 Compose 与已有 override，确认前后端镜像等于发布清单基线。
2. 确认 PostgreSQL 健康、无运行中同步/事实任务且磁盘足够。
3. 在 `upgrade-backups/` 保存 `.env`、基础 Compose、已有 override、容器/镜像信息、Flyway、关键表行数和 `pg_dump -Fc`。
4. 加载前后端镜像，安装新 override；先重建后端并等待 Flyway 和健康检查，再重建前端。
5. 确认 PostgreSQL 容器 ID 未变化，迁移期间受保护业务表行数守恒。

需要事实重建时，容器升级完成后由具备权限的用户在“数据镜像设置”提交发布清单指定范围的事实重建，并观察 `FACT_REFRESH` 终态和快照预热。升级脚本不得保存账号密码、绕过 Session/CSRF 或触发 GitLab 全量同步。

应用回滚：

```bash
bash ../<update-package>/rollback.sh "$PWD" "$PWD/upgrade-backups/<backup-dir>"
```

应用回滚只有在基线镜像匹配、后端和前端均健康且 PostgreSQL 容器 ID 未变化后才报告成功，不得把 `health: starting` 当作完成。它不自动执行 `pg_restore`。Flyway 是前向迁移；数据库恢复只能在应用回滚无法恢复服务、已停机且明确审批后执行，优先先恢复到隔离数据库验证。

## 禁止操作

- 普通更新不得执行 `docker compose down -v`，不得删除 PostgreSQL 容器或 volume。
- 不得清空平台库、ODS、事实、同步状态、用户、权限、页面配置或持久化视图。
- 不得以全新空平台包覆盖现有实例。
- 不得在无公网目标机执行 `docker build` 或解析 Docker Hub 基础镜像。
- 不得把事实重建解释成全量同步、清库或重建数据库容器。
- 容器名冲突只能处理精确的前后端应用容器；不得借此删除 PostgreSQL。

## 发布验收

每个包交付前必须确认：

- 定向打包契约测试、前端生产构建、后端生产包和镜像构建通过。
- Compose 可解析，镜像内产物摘要与生产构建产物一致。
- `RELEASE-MANIFEST.json` 的基线、目标镜像、Flyway 和事实重建标记与本次发布一致。
- `SHA256SUMS.txt` 覆盖包内全部其他文件，包外 `.sha256` 与最终 tar.gz 一致。
- 更新包归档不存在 `backend/`、`frontend/`、真实 `.env`、PostgreSQL 镜像、离线 deb、数据库数据或运行日志。
- 使用隔离的 20260714/当前更新链副本完成升级、再次升级和应用回滚验证；验证期间 PostgreSQL 容器 ID 与受保护数据保持不变。

## GitLab 源库访问边界

平台对 GitLab PostgreSQL 只执行读取和元数据查询。生产账号应限制到平台服务器地址，并只授予业务 schema 及必要系统元数据的读取权限；不得使用超级用户，也不得授予 `insert`、`update`、`delete`、`truncate`、`drop`、`alter` 或 `create`。

GitLab PostgreSQL 的监听、网络、防火墙和认证必须允许平台服务器连接。`0.0.0.0` 只能作为服务监听地址，不能作为 Rails 或平台客户端连接目标；客户端应使用可路由的实际地址、`127.0.0.1` 或原有 Unix socket。
