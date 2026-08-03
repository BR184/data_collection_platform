# ADR-006：Compose project 作为容器命名空间

## 状态

已接受

## 日期

2026-08-03

## 背景

内网同一 Docker daemon 运行 18181、20001 和隔离验证等多个平台实例。历史发布 Compose 同时设置 `COMPOSE_PROJECT_NAME` 和固定 `qaflex-postgres`、`qaflex-backend`、`qaflex-frontend` 容器名；固定名称属于 daemon 全局资源，会绕过 project 隔离。20001 更新在停止自身基线后端后，因另一个 project 已占用 `/qaflex-backend` 而中断，现场 Compose 已切换目标版本但应用容器未创建。

服务发现、备份、升级和回滚代码均已按 Compose service 操作，不需要稳定的容器显示名称。主机端口和数据 volume 仍需实例级隔离。

## 决策

- 所有生成 Compose 都必须显式要求唯一 `COMPOSE_PROJECT_NAME`。
- 发布 Compose 只定义 `postgres`、`backend`、`frontend` 服务，不声明 `container_name`。
- 容器、默认网络和 fresh 包的 named volume 由 Compose project 派生名称；更新包的 PostgreSQL external volume 继续由现场 `POSTGRES_VOLUME_NAME` 精确绑定。
- 服务间通信使用 Compose DNS 服务名；运维脚本使用 `docker compose ... <service>` 或 `docker compose ps -q <service>`，不按容器显示名称寻址。
- 发布验证必须覆盖同机存在历史全局同名容器的场景，且不得以删除其他 project 资源作为通过条件。

## 否决方案

- 为 20001 再硬编码一组名称：只能推迟下一实例冲突，并把现场差异写入通用发布模板。
- 部署前删除或重命名冲突容器：无法证明其不属于 18181 或其他有效实例，会扩大故障范围。
- 只依赖不同宿主端口：端口隔离不能解决 Docker daemon 全局容器名称冲突。

## 影响

- 新容器显示名改为 Compose 派生名称，外部手工脚本必须改用 service 寻址。
- 同机多个实例可在 project、端口和 volume 不同的前提下共存。
- 旧实例显式命名的容器可保留；新 project 不接管、不删除这些容器。
- 数据模型、Flyway、镜像内容、内部服务 DNS 和产品 API 不变。
