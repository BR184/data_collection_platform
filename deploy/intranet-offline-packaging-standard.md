<!-- DOC_STATUS_START -->
> 文档状态：常驻发布标准
> 说明：定义 QA Flex Platform 面向 Ubuntu 24.04 amd64 内网环境的离线发布包、连续升级和回滚契约。
<!-- DOC_STATUS_END -->

# 内网离线打包标准

## 适用范围与拓扑

本标准适用于无公网访问的 Ubuntu 24.04 amd64 平台服务器。当前运行拓扑由 Compose 管理三个服务：

- `postgres`：平台自有 PostgreSQL，只保存平台配置、镜像、事实、同步状态和业务维护数据。
- `backend`：Spring Boot 后端，通过 Compose 内部网络访问平台库。
- `frontend`：Nginx 前端，将 `/api/` 代理到后端。

全新包必须通过必填 `COMPOSE_PROJECT_NAME` 隔离容器、默认网络和 named volume，不写固定 `container_name`；同一主机部署多套实例时，project 名称及前端、后端、PostgreSQL 三个主机端口必须同时唯一。保数据更新包为兼容既有现场资源身份可继续使用其原容器名和 external volume，但不得把该约束复制到新实例。

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

仅在新服务器首次部署、明确清空环境或灾难恢复时制作 `fresh-empty` 包。它包含最新前后端镜像和完整基础 Compose，不包含平台 PostgreSQL 镜像（见“镜像与发布身份”），也不包含任何数据库文件、dump、volume、ODS、事实或用户数据。

Docker/Compose 的 Ubuntu deb 不是应用运行产物。目标服务器尚未安装 Docker 且确需同包交付时，才显式使用 `--include-offline-docker-debs`；已有容器的更新包永远不携带这些 deb。

## 包结构契约

### 保数据更新包

```text
qaflex-update-<release-id>/
├── docker-images/
│   ├── qa-flex-platform-backend_<image-tag>.tar
│   └── qa-flex-platform-frontend_<image-tag>.tar
├── docker-compose.yml
├── backup.sh
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
| `docker-compose.yml` | 本次发布的完整运行契约；升级脚本先校验，再以同目录临时文件和原子替换将其写入正式目录。 |
| `backup.sh` | 在应用变更前独立生成并校验完整数据库和关键表 custom-format dump，同时保存 Compose、镜像、容器、Flyway 和行数证据；不停止容器、不修改数据库。 |
| `upgrade.sh` | 只接受同一发布包生成且校验通过的预部署备份；执行基线、任务、磁盘、Flyway、静默迁移、健康、行数守恒和 PostgreSQL 容器不变检查。 |
| `rollback.sh` | 原子恢复升级前完整 Compose，保持现场 `.env` 不变，校验基线镜像，先等待后端健康再等待前端健康；不擅自回写数据库。 |
| `RELEASE-MANIFEST.json` | 机器可读地记录包类型、commit、工作树状态、目标/基线镜像、镜像与源码摘要、Flyway 和事实重建要求。 |
| `SHA256SUMS.txt` 与包外 `.sha256` | 分别校验包内文件和离线传输后的完整归档。 |
| `README-INCREMENTAL-DEPLOY.md` | 内网现场无法访问仓库文档时的同版本操作与验收入口。 |

更新包明确不包含：

- `backend/`、`frontend/`、Dockerfile、`.dockerignore`：它们只是镜像构建上下文，应用内容已存在于镜像 tar；重复交付没有运行用途。
- `.env`：现场 `.env` 是该实例配置的唯一事实源，更新包不得用开发机模板覆盖数据库连接、端口或凭据。
- `offline-debs/`：应用更新不安装容器运行时。
- 数据 dump、volume、数据库物理文件或运行日志：备份只能在现场升级前生成并留在现场。
- `VERSION.txt`：已由结构化 `RELEASE-MANIFEST.json` 取代，禁止双份版本事实源。

### 全新/灾备包

```text
qaflex-full-<release-id>/
├── docker-images/
│   ├── qa-flex-platform-backend_<image-tag>.tar
│   └── qa-flex-platform-frontend_<image-tag>.tar
├── docker-compose.yml
├── .env
├── RELEASE-MANIFEST.json
├── SHA256SUMS.txt
└── README-INTRANET-DEPLOY.md
```

只有显式选择时才增加 `offline-debs/ubuntu-24.04-amd64/`。全新包不携带 `backend/`、`frontend/`；包内 `.env` 即运行配置，已写入发布级默认内网地址与端口，部署人员按需调整后直接部署。

包内 `.env` 必须给出发布级唯一的默认 `COMPOSE_PROJECT_NAME`，并分别声明前端、后端和 PostgreSQL 主机端口。Compose 中所有持久卷由该 project 作用域管理；不得为了部署新实例删除、改名或复用同机其它 project 的容器、网络或卷。自动删除反熵在约 280 万总量现场容量验收前必须显式保持 `GITLAB_DELETE_RECONCILIATION_ENABLED=false`。

`COMPOSE_PROJECT_NAME` 同时是该平台实例的浏览器会话身份。完整 Compose 必须将其注入后端的 `PLATFORM_INSTANCE_ID`；同一主机上的每个实例必须使用不同值，保数据更新和应用回滚必须保留原值。后端据此派生独立的 Session/CSRF Cookie 名称，使同一 Chrome 可在不同端口同时登录不同账号；LDAP 不参与该隔离。直接运行后端时如未设置 `PLATFORM_INSTANCE_ID`，才按应用名与监听端口回退。

平台 CSRF Cookie 使用实例专属名称并设置为 `HttpOnly`，后端通过 `X-XSRF-TOKEN` 响应头交付 Token，前端按 Origin 保存并为非安全请求设置同名请求头。部署验收必须检查响应中不存在共享的 `JSESSIONID` 和 `XSRF-TOKEN` Cookie，并确认两个实例的专属 Cookie 名不同。

## 镜像与发布身份

应用镜像固定为：

```text
qa-flex-platform-backend:<release-id>
qa-flex-platform-frontend:<release-id>
```

每次执行打包器时自动生成 `YYYYMMDDTHHMMSSZ-<12 hex>` 格式的 `release-id`。UTC 时间负责排序和人工定位，6 字节密码学随机熵区分同秒及并发构建；包目录通过原子创建拒绝碰撞，归档和校验文件存在时同样拒绝覆盖。因此每个成功生成的包都有唯一名称，不依赖人工命名。

包名只允许包含产品简称、包类型和发布 ID：全新包为 `qaflex-full-<release-id>.tar.gz`，更新包为 `qaflex-update-<release-id>.tar.gz`。Ubuntu 版本、离线属性、端口、commit、工作树状态、功能说明、Flyway 和事实重建范围均写入 `RELEASE-MANIFEST.json`，不得重复拼接到文件名。前后端镜像复用同一 `release-id`，使一次发布的目录、归档、清单和镜像形成单一身份。

打包器在最终归档之外创建临时 Docker build context，构建完成后立即销毁。打包阶段必须核对镜像内 `/app/app.jar` 与本地生产 JAR 的 SHA-256，并核对前端镜像内 `index.html` 与生产 `dist`；审计摘要写入发布清单，不复制裸产物。

平台 PostgreSQL 镜像不进入任何发布包：`postgres:16-alpine` 是稳定基础镜像，自初始部署加载后从未变更，目标机所有实例共用同一本地镜像。打包器不导出、不校验、也不要求打包机本地存在该镜像，任何模式的包内出现 `docker-images/postgres_16-alpine.tar` 一律拒绝交付；`RELEASE-MANIFEST.json` 的 `target.images` 只含前后端镜像。包内部署 README 要求部署前以 `docker image inspect postgres:16-alpine` 确认目标机已有该镜像；真正的新服务器缺失时，从部署资料归档中既有历史全新包的 `docker-images/postgres_16-alpine.tar` 加载后再继续部署。

## 基线与连续更新

更新包必须显式传入 `--baseline-dir`，其值只能是：

1. 当前现场部署目录的受控副本，只包含唯一的 `docker-compose.yml` 与现场 `.env`；或
2. 当前实例最后一次成功应用的更新包目录，包含完整 `docker-compose.yml`。

镜像基线只从该目录完整 `docker-compose.yml` 读取。现场存在 `docker-compose.*.yml` 分层文件即为配置事实冲突，备份、升级和回滚必须拒绝执行；先由运维将有效参数收束到唯一完整 Compose，再重新开始预部署备份。

两个内网实例必须分别确认当前合并 Compose。不能因两者都源自 20260714 就继续把 20260714 当作永久基线；已应用 20260724 后，下一包的预期基线应是 20260724 的目标前后端镜像。

升级前独立备份会保存当前完整 Compose 和现场 `.env` 作为证据；升级只校验包内完整 Compose 后原子替换现场同名文件，绝不修改 `.env`。回滚只原子恢复备份的完整 Compose；不保留 override、release Compose 或双轨兼容路径。

## 后续更新包标准工作流

本节是发布执行者和 AI 每次制作保数据更新包时的固定入口。具体 release ID、commit、镜像摘要和事实重建范围属于当次 `RELEASE-MANIFEST.json`，不得写死在本节。任一阶段失败即停止，不得跳过失败步骤继续形成交付包。

### 1. 建立活动计划并确认工作树

更新包制作属于长任务。开始前必须读取 `AGENTS.md`、`docs/progress.md`、本标准和相关架构/ADR，并在 `docs/plans/` 建立活动计划。先检查：

```powershell
git status --short --branch
git log -5 --date=iso --pretty=format:"%h %ad %s"
```

必须区分三类内容：当前发布范围内的代码、用户尚未完成的改动、与发布无关的文档或运行产物。不得回退或删除用户改动。常规发布优先使用已提交且已验证的代码；用户明确要求打包本地未提交版本时，必须确认这些改动均属于目标版本并完成同等验证，清单会把工作区标为非干净状态。

### 2. 确定该实例的直接基线

先在实际实例或其受控部署目录副本中查看合并配置：

```bash
docker compose --env-file .env ps
docker compose --env-file .env config --images
```

记录当前前后端完整镜像引用。`--baseline-dir` 必须指向：

- 与上述镜像一致的当前部署目录受控副本；或
- 该实例最后一次成功更新包的解压目录，其 `target.images` 与上述镜像一致。

不得使用基础容器最初建立日期、上上次更新包或上一清单中的 `baseline` 字段代替当前基线。对连续更新包而言，应使用上一成功包的**目标镜像**作为新包基线。不同现场实例分别确认，不能互相推定。

读取上一成功包清单并保留以下证据：

```powershell
Get-Content -Raw <baseline-dir>\RELEASE-MANIFEST.json
```

至少确认 `package.id`、`source.commit` 和 `target.images`。如果清单目标镜像与现场合并 Compose 不同，停止打包并先查清真实部署链。

### 3. 根据基线差异决定事实重建范围

以基线清单的 `source.commit` 为代码起点，同时检查当前未提交差异：

```powershell
git diff --name-status <baseline-source-commit>..HEAD
git diff --name-status
```

如果基线清单的 `source.workspaceState` 不是干净状态，`source.commit` 只能定位其 Git 起点，不能证明上一包镜像等于该 commit。此时必须同时依据上一包的镜像/JAR/前端摘要、当次进度验证记录和实际目标镜像确认变化边界；证据不足时停止并重新建立可追溯基线，不得用 commit diff 冒充完整差异。

重建范围按实际数据契约变化决定，不能仅按提交标题或文件名猜测：

| 变化 | 清单要求 |
| --- | --- |
| 只改变页面样式、静态文案、认证或无事实结果影响的部署逻辑 | 不声明事实重建 |
| 改变议题事实字段、归一化、搜索值、客户成员、系统测试/客户问题口径或其事实依赖快照 | `--require-fact-rebuild --fact-rebuild-scope issue` |
| 改变 MR 事实字段、归一化、搜索值或其事实依赖统计 | `--require-fact-rebuild --fact-rebuild-scope merge-request` |
| 同时影响议题和 MR，或改变二者共享构建/发布契约且无法安全拆分 | `--require-fact-rebuild --fact-rebuild-scope all` |

存在不确定性时先沿调用链、迁移和测试确认，不能为了省事漏报，也不能无依据扩大范围。事实重建只重算现有 ODS；它不等同于 GitLab 全量同步。

### 4. 先解析计划，再正式构建

先运行不写产物的计划解析：

```powershell
python scripts\package_intranet_offline.py `
  --mode incremental-update `
  --baseline-dir <baseline-dir> `
  --require-fact-rebuild `
  --fact-rebuild-scope <issue|merge-request|all> `
  --plan-only
```

不需要事实重建时省略最后两个事实参数。输出中的基线目录、前后端基线镜像、包类型和目标目录必须全部正确；否则停止。

确认后用完全相同的参数去掉 `--plan-only`：

```powershell
python scripts\package_intranet_offline.py `
  --mode incremental-update `
  --baseline-dir <baseline-dir> `
  --require-fact-rebuild `
  --fact-rebuild-scope <issue|merge-request|all>
```

正式打包不得使用 `--skip-build`、`--skip-docker-build`、`--skip-archive` 或 `--skip-frontend-release-tests`。默认无缓存构建；只有定位构建问题时才临时使用调试参数，调试产物不得交付。

打包器必须成功完成：前端发布测试、类型检查和生产构建，后端 `clean package`，Flyway 源码/JAR 集合检查，无缓存业务镜像构建，镜像内产物摘要核对，Compose 解析，包内校验和与最终归档检查。

### 5. 独立审计生成物

从打包输出记录包目录、归档路径和 SHA-256，再独立检查：

```powershell
Get-Content -Raw <package-dir>\RELEASE-MANIFEST.json
Get-FileHash -Algorithm SHA256 <archive.tar.gz>
Get-Content <archive.tar.gz.sha256>
tar -tzf <archive.tar.gz>
python -m unittest scripts.test_package_intranet_offline
```

还必须在可用的 Linux Bash 环境中运行：

```bash
bash -n <package-dir>/backup.sh
bash -n <package-dir>/upgrade.sh
bash -n <package-dir>/rollback.sh
cd <package-dir> && sha256sum -c SHA256SUMS.txt
```

清单必须满足：

- `source.commit`/工作区状态对应本次代码；
- `baseline` 对应该实例直接基线；
- `target.images` 使用同一个新 release ID；
- `flywayVersion` 等于 JAR 内最新迁移；
- `facts` 与第 3 步结论一致。

归档只能包含本标准定义的更新包结构。发现 `backend/`、`frontend/`、`.env`、PostgreSQL 镜像、Docker deb、数据库 dump、volume 数据或运行日志时，包无效。

### 6. 建立隔离部署基线

本地部署测试必须使用专用 Compose 栈，不得覆盖开发栈、LDAP 专项栈或现场目录。隔离栈至少满足：

- 独立的 Compose project、容器名、主机端口和 PostgreSQL named volume；
- 唯一完整 `docker-compose.yml`、测试 `.env`，二者共同代表当前直接基线；
- 前后端运行基线包目标镜像且均健康；
- PostgreSQL 存在可迁移的代表性 schema 和最少数据，不连接内网真实平台库；
- 测试开始前记录 PostgreSQL 容器 ID、当前 Flyway、基线镜像和受保护表行数。

若本机不存在基线镜像，先从上一成功包的 `docker-images/` 执行 `docker load`。不得通过重新构建旧源码伪造基线镜像。

### 7. 执行升级、回滚、再次升级

在 Ubuntu 或其他具备 Bash、curl 和 Docker Compose v2 的 Linux 环境中执行包内脚本。第一次升级前先独立备份并验证：

```bash
bash <package-dir>/backup.sh <simulation-deployment-dir>
BACKUP_DIR="$(cat <simulation-deployment-dir>/upgrade-backups/latest-predeploy-backup.txt)"
bash <package-dir>/upgrade.sh <simulation-deployment-dir> "$BACKUP_DIR"
```

必须逐项确认：

- 新后端和前端均为目标镜像且 healthy；
- 后端健康返回 `UP`，前端 HTTP 返回 200；
- Flyway 到达清单目标版本；
- `database.dump`、`critical-tables.dump` 均非空且可由 `pg_restore --list` 读取，备份级 SHA-256 校验通过；
- 迁移后端明确关闭后台调度，守恒检查完成后正常后端明确恢复调度；
- `counts.diff` 为空；
- PostgreSQL 容器 ID 与升级前完全一致。

使用第一次升级生成的备份执行应用回滚：

```bash
BACKUP_DIR="$(cat <simulation-deployment-dir>/upgrade-backups/latest-backup.txt)"
bash <package-dir>/rollback.sh <simulation-deployment-dir> "$BACKUP_DIR"
```

回滚后前后端必须恢复直接基线镜像并健康，PostgreSQL 容器 ID 仍不变。Flyway 保持前向版本是预期行为；不得为了测试回滚而执行 `pg_restore`。

最后从已恢复的基线重新运行 `backup.sh`，再以新备份运行同一 `upgrade.sh`，并重复目标镜像、健康、Flyway、备份可恢复性、空 `counts.diff` 和 PostgreSQL ID 检查。本地隔离栈最终停留在目标镜像，证明连续操作可重复。

Windows 上仅存在 `C:\Windows\System32\bash.exe` 不代表 Bash 可用，它可能只是未安装 WSL 发行版的转发器。必须实际运行 `bash --version` 和 `bash -n`；不可用时，改用已安装的 Git Bash、WSL/Ubuntu，或预置了 Bash、curl、Docker CLI 与 Compose v2 的 Linux 工具容器挂载 Docker socket和隔离目录。工具环境只是执行同一份包内脚本，不允许改写脚本来绕过检查。Docker Desktop 工具容器还必须验证 host 网络和 Windows 目录挂载可用。

### 8. 收尾与交付

运行仓库门禁：

```powershell
python scripts\check_worktree_artifacts.py
python scripts\check_runtime_artifact_locations.py
python scripts\check_text_whitespace.py
git diff --check
git status --short --branch
```

若门禁被既有用户文件阻塞，必须报告具体文件，不得擅自删除，也不得声称全绿。将 release ID、归档大小/SHA-256、直接基线、目标 Flyway、事实重建范围、本地升级/回滚/再次升级结果、PostgreSQL ID/行数守恒和未执行项压缩写入 `docs/progress.md`；随后按文档生命周期删除活动计划。

最终交付必须提供归档、包外 `.sha256`、包内 `README-INCREMENTAL-DEPLOY.md` 的路径，并明确：适用的直接基线、部署后是否重建哪类事实、是否需要 GitLab 全量同步、哪些真实内网场景尚未验证。现场操作以包内 README 为准，不在聊天中维护另一套可能漂移的部署流程。

## 全新/灾备模式命令速查

保数据更新包必须执行上一节的完整工作流，不能用单条命令替代前置决策和本地部署验收。仅全新/灾备模式使用以下速查命令：

全新/灾备包：

```powershell
python scripts\package_intranet_offline.py `
  --mode fresh-empty `
  --frontend-port <独立前端端口> `
  --backend-port <独立后端端口> `
  --postgres-port <独立PostgreSQL端口>
```

只有目标机缺少 Docker 时增加：

```powershell
--include-offline-docker-debs --template-package-dir D:\path\to\verified-template
```

全新/灾备包同样必须通过打包器的全部默认门禁；任何一步失败都不得交付。

## 现场升级流程

在更新包目录校验：

```bash
sha256sum -c SHA256SUMS.txt
```

进入当前部署目录并执行：

```bash
cd <current-deployment-dir>
bash ../<update-package>/backup.sh "$PWD"
BACKUP_DIR="$(cat upgrade-backups/latest-predeploy-backup.txt)"
bash ../<update-package>/upgrade.sh "$PWD" "$BACKUP_DIR"
```

脚本必须在修改应用容器前完成：

1. 解析现场唯一完整 Compose，确认前后端镜像等于发布清单基线。
2. 确认 PostgreSQL 健康、无运行中同步/事实任务且磁盘足够。
3. 由独立 `backup.sh` 保存 Compose、容器/镜像、Flyway、关键表行数、完整数据库 dump 和关键表 dump，并验证可恢复性和校验和。
4. `upgrade.sh` 校验备份绑定当前包、直接基线与同一 PostgreSQL 容器；停止旧后端后记录迁移起点行数。
5. 以全部后台调度关闭的模式重建后端并完成 Flyway、健康和行数守恒，再恢复正常调度后端，最后重建前端。
6. 确认 PostgreSQL 容器 ID 未变化，迁移静默窗口内受保护业务表行数守恒。

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
- 更新包包含独立 `backup.sh`；无有效预部署备份时 `upgrade.sh` 必须拒绝执行。
- 更新包归档不存在 `backend/`、`frontend/`、真实 `.env`、离线 deb、数据库数据或运行日志；任何包归档都不存在 PostgreSQL 镜像。
- 使用隔离的 20260714/当前更新链副本完成升级、再次升级和应用回滚验证；验证期间 PostgreSQL 容器 ID 与受保护数据保持不变。

## GitLab 源库访问边界

平台对 GitLab PostgreSQL 只执行读取和元数据查询。生产账号应限制到平台服务器地址，并只授予业务 schema 及必要系统元数据的读取权限；不得使用超级用户，也不得授予 `insert`、`update`、`delete`、`truncate`、`drop`、`alter` 或 `create`。

GitLab PostgreSQL 的监听、网络、防火墙和认证必须允许平台服务器连接。`0.0.0.0` 只能作为服务监听地址，不能作为 Rails 或平台客户端连接目标；客户端应使用可路由的实际地址、`127.0.0.1` 或原有 Unix socket。

## 同步运行时现场验收

- 线程设置必须在无活动镜像运行时修改。每次运行使用提交时持久化的 worker 快照；DIRECT 连接池上限自动等于 worker 数加控制面保留连接，不需要再单独把“同步线程上限”手工改成相同数值。
- 内网首次验证从 2 worker 开始，以相同表范围依次测试 2/4/6 worker；记录总耗时、每页耗时、GitLab CPU/连接数、平台“连接池活跃/空闲/等待”和失败任务。只有源库负载与等待数稳定时才提高并发，不能用单次更快结果作为正式配置。
- 全量或补偿运行期间提交一次增量同步和一次用户单表刷新：后台运行应在当前分页提交后显示为等待状态，前台运行先完成，随后后台从原 cursor 恢复。System Hook 不属于本项验收。
- 当前运行失败/超时与历史累计必须分别查看；表任务明细应包含扫描/删除对账阶段、cursor、重试和租约。日志使用外部 `runId`、内部 `runDbId` 和 `taskId` 关联，不以页面线程数为失败原因证据。
- 本同步运行时升级不改变 ODS、事实字段或统计口径，部署后不执行 GitLab 全量补偿，也不手工重建事实层；仅当同一发布还包含发布清单明确标注的其他事实规则变更时，才按清单执行对应重建。
