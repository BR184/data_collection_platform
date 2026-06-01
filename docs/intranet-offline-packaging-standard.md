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
