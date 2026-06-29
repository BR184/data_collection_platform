<!-- DOC_STATUS_START -->
> 文档状态：常驻发布标准
> 说明：定义 QA Flex Platform 内网离线 Ubuntu 24.04 amd64 发布包的固定结构、拓扑边界、打包流程和验收清单。
<!-- DOC_STATUS_END -->

# 内网离线打包标准

## 适用范围

本标准用于生成可部署到内网服务器的 QA Flex Platform 离线包。目标环境默认满足：

- 操作系统：Ubuntu 24.04 amd64。
- 目标服务器无公网访问能力。
- 平台和 GitLab 部署在不同服务器。
- 平台自带 PostgreSQL 作为平台库，GitLab PostgreSQL 只作为后续在 UI 中配置的数据源。

## 发布包类型

内网交付必须先判断发布包类型，不能把所有交付都当作“不改变容器的前端更新”。当前固定分为三类：

1. **离线全新部署包**：用于新服务器首次部署、明确清空环境、灾难恢复或业务方批准重建。包内包含 PostgreSQL、后端、前端镜像和离线依赖，平台数据为空，首次启动后由 Flyway 建库。
2. **需要事实层重建的增量更新包**：用于保留既有容器、volume、用户配置、同步状态和镜像表，但本次改动影响事实字段、事实规则、统计口径、非法判定、默认范围、快照结构或聚合结果。包内必须包含后端/前端业务镜像 tar、Flyway 迁移和升级后事实层重建/快照预热步骤说明。
3. **不需要事实层重建的增量更新包**：用于纯前端展示、样式、文案、非事实字段接口展示、非统计规则类后端小修等不改变事实表和统计结果的更新。包内仍必须包含已构建业务镜像 tar，部署时只替换后端/前端容器，不触发事实层重建。

判断是否需要事实层重建时，以下改动一律按“需要事实层重建的增量更新包”处理：

- 修改 `issue_fact`、`merge_request_fact` 或评审事实/统计事实字段生成规则。
- 修改非法数据判定、缺陷原因、延期/响应效率、系统测试阶段、客户问题里程碑、代码走查异常等业务口径。
- 修改默认筛选范围、老平台字段映射、模块/项目/阶段/里程碑归一化逻辑。
- 修改统计快照/中间表结构、快照 key、规则版本、预热逻辑或聚合服务输出。
- 修复导致既有事实表中已落库字段值错误的问题。

如果只是修复页面排版、按钮布局、表格列宽、前端下拉样式、接口超时提示、文案错别字，且后端事实字段、统计数量和导出结果不变，才允许归为“不需要事实层重建的增量更新包”。

## 内网既有实例更新规则

当前内网已部署基线包为：

```text
D:\projects\data_collection_platform_deploy\qa-flex-platform-intranet-20260618-runnable-empty-working-ubuntu2404-offline.tar.gz
```

后续普通修复、页面调整、统计口径修正和前后端代码更新，默认必须以该基线包在内网已经运行的实例、容器和 PostgreSQL volume 为更新目标，采用同环境增量更新方式。除非业务方明确批准重建环境，不得重新制作一个全新的空平台全量包去替换现有实例。

同容器增量更新的目的：

- 保留平台数据库、同步状态、镜像表、事实表、用户配置、持久化视图、页面设置和后续接入统一账号后的用户侧数据。
- 避免重新部署新容器后触发无意义的全量同步。
- 避免普通 bugfix 变成一次完整迁移，降低内网更新时间和回滚风险。

增量更新包或更新脚本只能替换应用代码和必要配置，例如：

- 后端 `app.jar` 或等价后端构建产物。
- 前端 `dist/` 静态资源。
- 无联网内网环境需要直接部署时，增量包必须包含已经构建好的后端/前端业务镜像 tar，不能要求目标服务器现场 `docker build`。
- 必须随代码同步更新的 Nginx、启动脚本或环境变量模板。
- 必须执行且可重复运行的 Flyway 迁移脚本。
- 需要事实层重建时，可附带明确的事实重建、快照预热和验收命令；这些命令只重建平台库中的事实表/统计快照，不删除镜像表、同步状态、用户配置或 PostgreSQL volume。

增量更新时禁止执行：

- 删除或重建 `qaflex-postgres` 数据卷。
- 清空平台库、镜像表、事实表、同步状态表、用户视图表或页面配置表。
- 使用全新的空平台包覆盖内网既有实例。
- 无审批地执行会导致全量同步重跑的初始化流程。
- 把“需要事实层重建”误处理成“清空平台库/重建容器/重新全量同步”。事实层重建只能基于既有镜像表和同步状态重算事实结果。
- 为普通代码更新执行 `docker compose down -v`、删除 volume、删除 PostgreSQL 容器数据目录或重置 `.env`。
- 在无公网内网服务器上执行依赖 Docker Hub 解析基础镜像的 `docker build`。`FROM eclipse-temurin:21-jre`、`FROM nginx:1.27-alpine` 等基础镜像即使以前通过业务镜像间接存在，也可能因 tag 解析访问 `registry-1.docker.io` 而失败。

如果确实需要重建镜像或替换容器，必须先明确说明原因并获得确认；即使替换后端或前端容器，也必须复用既有 PostgreSQL 数据卷和同步状态，不能重建平台数据库。只有在明确目标就是清空环境、重新初始化或灾难恢复时，才允许重新走全量空平台部署流程。

### 需要事实层重建的增量包部署要求

需要事实层重建的增量包仍然是“保留用户数据”的更新包，不是空平台包。部署顺序固定为：

1. `docker load` 后端/前端业务镜像。
2. `docker compose --env-file .env up -d --no-deps --force-recreate backend frontend`，让后端启动并执行 Flyway 迁移。
3. 确认后端 `/actuator/health` 为健康。
4. 触发或等待事实层重建任务。若本次改动影响 issue 事实，必须重建 issue 事实；影响 MR/代码走查事实，必须重建 merge request 事实；同时影响两者则两者都重建。
5. 事实重建成功后，统计快照/中间表应由事实刷新链路预热。若升级脚本提供了显式预热命令，执行后再验收相关统计页面。

事实层重建会增加升级后的首次处理时间，但它使用既有镜像表重算事实和快照，不会重新从 GitLab 源库拉全量镜像数据。同步时间和事实重建时间必须在发布说明中分开描述，不能把事实重建误写成全量同步。

### 不需要事实层重建的增量包部署要求

不需要事实层重建的增量包部署时只替换后端/前端业务容器并做健康检查，不主动触发事实重建、全量同步或快照清空。若部署后页面仍读取旧事实数据，这是符合预期的；该类包不承担修正历史事实结果的职责。

### 无联网增量镜像包

内网服务器无公网访问能力时，普通代码更新应发布“增量镜像包”，而不是只发布 `app.jar` / `dist` 文件包。增量镜像包与全量空平台包的边界如下：

- 必须包含后端和前端业务镜像 tar，镜像 tag 默认沿用现有 `docker-compose.yml` 中的 tag，避免修改现场 compose。
- 可以同时包含 `backend/app.jar` 和 `frontend/dist/` 作为审计产物，但目标服务器部署时不依赖现场构建。
- 不包含 `postgres` 镜像、`offline-debs/`、数据库 dump、Docker volume、镜像表、事实表、同步状态或用户配置。
- 部署时只执行 `docker load` 后端/前端业务镜像，然后 `docker compose --env-file .env up -d --no-deps --force-recreate backend frontend`。
- 不执行 `docker build`，不重新 `docker load` postgres，不重启或重建 `postgres`，不删除 volume。
- 如属于“需要事实层重建的增量更新包”，部署说明必须在上述容器替换步骤之后追加事实层重建和快照预热步骤；如属于“不需要事实层重建的增量更新包”，部署说明必须明确不执行事实重建。

增量镜像包部署命令模板：

```bash
cd qa-flex-platform-intranet-20260618-runnable-empty-working

sudo docker load -i ../qa-flex-platform-intranet-YYYYMMDD-incremental-images-<release-label>/docker-images/qa-flex-platform-backend_<image-tag>.tar
sudo docker load -i ../qa-flex-platform-intranet-YYYYMMDD-incremental-images-<release-label>/docker-images/qa-flex-platform-frontend_<image-tag>.tar

sudo docker compose --env-file .env up -d --no-deps --force-recreate backend frontend
sudo docker compose --env-file .env ps
```

如果增量包目录不在原部署目录的上一级，必须把 `../qa-flex-platform-intranet-YYYYMMDD-incremental-images-<release-label>/...` 改成现场实际路径。

## 包结构

离线包目录名使用：

```text
qa-flex-platform-intranet-YYYYMMDD-runnable-<release-label>
```

压缩包名使用：

```text
qa-flex-platform-intranet-YYYYMMDD-runnable-<release-label>-ubuntu2404-offline.tar.gz
```

目录内必须包含：

```text
backend/app.jar
backend/Dockerfile
frontend/dist/
frontend/Dockerfile
frontend/nginx-default.conf
docker-images/postgres_16-alpine.tar
docker-images/qa-flex-platform-backend_<image-tag>.tar
docker-images/qa-flex-platform-frontend_<image-tag>.tar
offline-debs/ubuntu-24.04-amd64/*.deb
.dockerignore
.env
.env.example
docker-compose.yml
README-INTRANET-DEPLOY.md
SHA256SUMS.txt
VERSION.txt
```

## 空平台要求

发布包必须是空平台包：

- 禁止包含 `infra/postgres-data/`、`pg_wal/`、`base/`、Docker volume 数据或任何平台数据库物理文件。
- 禁止包含业务数据 dump、备份文件、初始化数据快照或从本地测试环境导出的数据库内容。
- 禁止把本地 GitLab、镜像表、事实表、评审记录、测试数据、同步日志等运行期数据打入发布包。
- 允许包含 Flyway 迁移脚本和空 PostgreSQL 镜像；平台库结构只能由首次启动时的 Flyway 迁移创建。
- `offline-debs/` 只用于离线安装 Docker/Compose 依赖，不得夹带业务数据。

## 运行拓扑

离线包运行三个容器：

- `qaflex-postgres`：平台内置数据库，只保存平台配置、镜像表、事实表和业务维护数据。
- `qaflex-backend`：Spring Boot 后端，连接内置 `postgres` 服务。
- `qaflex-frontend`：Nginx 前端，代理 `/api/` 到 `backend:18080`。

`DATASOURCE_URL` 必须指向 compose 内的内置平台库：

```text
jdbc:postgresql://postgres:5432/${POSTGRES_DB}
```

禁止把 GitLab PostgreSQL 写入 `DATASOURCE_URL`。GitLab web 地址和 GitLab PostgreSQL 源库连接在平台启动后通过数据镜像设置页面配置。

## GitLab 源库访问边界

平台和 GitLab 部署在不同服务器时，推荐使用 `DIRECT` 数据源模式。该模式要求平台服务器能够访问 GitLab PostgreSQL，并且 GitLab 侧提供一个只读账号。

GitLab 服务器侧至少需要满足：

- PostgreSQL 监听平台服务器可达的地址和端口。
- 防火墙、路由和 GitLab PostgreSQL 认证规则允许平台服务器连接。
- 账号可以连接 GitLab 业务库。
- 账号可以读取 `public` schema 下需要同步的 GitLab 表。
- 账号可以读取 `pg_catalog` / `information_schema` 元数据，用于发现表、字段、主键和更新时间字段。

如果正式 GitLab 服务器没有这些权限，平台本身仍可启动，但以下功能会失败：

- 数据源测试连接。
- 表白名单发现和数据库查看。
- 增量同步、全量同步、补偿扫描和全量补偿对账。
- 依赖镜像表和事实表刷新的统计看板更新。

当前平台对 GitLab 源库的访问边界固定为只读：源库访问代码只执行 `select`、`count`、`max`、元数据查询和校验查询，不向 GitLab PostgreSQL 执行 `insert`、`update`、`delete`、`truncate`、`drop`、`alter` 或 `create`。平台的写入、删除、建表和清理操作只作用于平台自带 PostgreSQL。

不建议为了方便使用 GitLab 超级用户账号。生产环境应优先申请只读账号，并把网络放行范围限制到平台服务器地址。

GitLab Omnibus 配置 PostgreSQL 对外直连时，需要区分监听地址和 Rails 内部连接地址：

- `postgresql['listen_address'] = '0.0.0.0'` 只表示 PostgreSQL 监听所有网卡，不是 Rails 应该连接的数据库主机。
- `postgresql['port'] = 5432` 表示 PostgreSQL 使用 5432 端口。
- `postgresql['trust_auth_cidr_addresses'] = ['127.0.0.1/32']` 只用于本机本地连接。
- `postgresql['md5_auth_cidr_addresses']` 生产环境应限制为平台服务器 IP，例如 `['<平台服务器IP>/32']`，不要长期使用 `['0.0.0.0/0']`。

如果 `gitlab-ctl reconfigure` 报错为 `connection to server at "0.0.0.0", port 5432 failed: Connection refused`，优先检查是否把 Rails/ActiveRecord 的数据库 host 错配成了 `0.0.0.0`。`0.0.0.0` 可以作为监听地址，但不能作为客户端连接目标；Rails 内部连接一般应保持 GitLab 原本的本机 socket 或 `127.0.0.1`。

如果无法修改正式 GitLab 服务器配置，但必须直连，唯一可行路径是让运维提供等价能力：平台服务器到 GitLab PostgreSQL 的网络放行、认证放行、只读账号和实际连接端口。没有这些能力时，平台可以启动，但直连同步不可用。

## 镜像与标签

运行镜像固定为：

- `postgres:16-alpine`
- `qa-flex-platform-backend:<image-tag>`
- `qa-flex-platform-frontend:<image-tag>`

`image-tag` 使用：

```text
YYYYMMDD-<git-short-sha>
```

如果源码包含未提交改动但必须出包，则后缀追加 `-working`，例如：

```text
20260601-2215cac5-working
```

## 构建要求

打包前必须完成：

```powershell
npm.cmd run test -- feature-manifest-access.test.ts ux-interaction-regressions.test.ts
npm.cmd run build
tools\apache-maven-3.9.6\bin\mvn.cmd -f backend\pom.xml -DskipTests package
```

发布包必须使用生产构建产物：

- 后端：`backend/target/qa-flex-platform-backend-0.0.1-SNAPSHOT.jar`
- 前端：`frontend/dist/`

前端 Nginx 必须包含 `/api/` 反向代理：

```nginx
location /api/ {
    proxy_pass http://backend:18080/api/;
}
```

后端 compose healthcheck 使用：

```text
http://localhost:18080/actuator/health
```

## 默认环境变量

`.env.example` 和 `.env` 必须包含：

```text
PLATFORM_PUBLIC_BASE_URL=http://REPLACE_WITH_PLATFORM_SERVER_IP:18181
GITLAB_WEB_BASE_URL=http://REPLACE_WITH_GITLAB_SERVER_IP

POSTGRES_USER=qaflex
POSTGRES_PASSWORD=qaflex
POSTGRES_DB=qaflex
POSTGRES_PORT=15432
POSTGRES_BIND=127.0.0.1

FRONTEND_PORT=18181
FRONTEND_BIND=0.0.0.0
BACKEND_PORT=18080
BACKEND_BIND=127.0.0.1

PLATFORM_ADMIN_USERNAME=admin
PLATFORM_ADMIN_PASSWORD=admin123
PLATFORM_APPROVAL_USERNAME=approval
PLATFORM_APPROVAL_PASSWORD=approval123
```

真实部署前必须修改 `PLATFORM_PUBLIC_BASE_URL`；`GITLAB_WEB_BASE_URL` 只用于平台展示链接和默认提示，不等同于 GitLab 数据库连接。

## 目标机部署步骤

目标机无网络时，部署流程固定为：

```bash
tar -xzf qa-flex-platform-intranet-YYYYMMDD-runnable-<release-label>-ubuntu2404-offline.tar.gz
cd qa-flex-platform-intranet-YYYYMMDD-runnable-<release-label>
```

```bash
sudo docker load -i docker-images/postgres_16-alpine.tar
sudo docker load -i docker-images/qa-flex-platform-backend_<image-tag>.tar
sudo docker load -i docker-images/qa-flex-platform-frontend_<image-tag>.tar
```

```bash
cp .env.example .env
vi .env
sudo docker compose --env-file .env up -d postgres backend frontend
sudo docker compose --env-file .env ps
```

升级已有实例前，先停止旧容器：

```bash
sudo docker rm -f qaflex-frontend qaflex-backend qaflex-postgres
```

只有明确需要清空平台数据库时，才删除旧 volume。

## 验收清单

每个离线包发布前必须验证：

- 前端生产构建通过。
- 后端 jar 打包通过。
- `docker compose --env-file .env config` 能解析。
- 三个运行镜像均已导出到 `docker-images/`。
- `SHA256SUMS.txt` 覆盖包内所有文件，且 `sha256sum -c SHA256SUMS.txt` 可通过。
- `tar -tzf` 能列出压缩包内容。
- `VERSION.txt` 记录 commit、branch、image tag、构建时间、构建命令、重要变更和目标拓扑。
