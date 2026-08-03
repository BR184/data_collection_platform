# Compose 容器重名修复与 20001 全新部署打包计划

## 进度与中间物

- 状态：阶段 3 进行中。内网失败根因已确认；打包器已删除生成 Compose 的全部 `container_name`，fresh 模式强制 release 级 project 并支持 PostgreSQL 宿主端口参数，部署说明已改为只停止指定旧 project。
- 当前处置：旧实例可使用同一备份目录执行包内 `rollback.sh` 恢复；用户已决定另建全新空平台，旧实例只停用并保留，不删除 PostgreSQL 或 volume。
- 当前进行点：执行完整发布门禁；通过后正式生成并演练 `fresh-empty` 全新部署包。
- 已完成验证：定向发布测试 30 项、Python 编译、20001/20002/15433 `--plan-only` 和生成模板固定容器名扫描通过；ADR-006、架构与离线发布标准已同步。

## 恢复线索

- 当前阶段：阶段 3，根因修复和定向测试已完成，等待完整门禁与正式打包。
- 恢复后首条命令：`python -m unittest scripts.test_package_intranet_offline`，随后执行仓库发布门禁。
- 对应提交：`dc9f213b`；被替代发布：`20260803T110730Z-508eada12e44`。

## 目标与边界

- 用户需求：停止修复旧实例，改为从头部署新容器、新前后端和新平台数据库；交付完整全新部署包、20001 配置和说明。
- 成功标准：新生成 Compose 不声明全局固定容器名；同机保留旧项目容器时，新 project 仍可创建独立 postgres/backend/frontend、网络和 named volume；新平台从空库完成 Flyway、LDAP 登录准备、首次 `FULL_SYNC` 及自动 `FACT_REFRESH` 契约。
- 禁止行为：不执行 `down -v`，不删除旧 PostgreSQL 或 volume，不要求现场删除或重命名其他实例容器，不复用旧平台数据库，不通过手工改包绕过发布校验。

## 约束与背景

- Compose project 已由 `COMPOSE_PROJECT_NAME` 强制提供稳定实例命名空间，服务发现使用服务名 `postgres`、`backend`、`frontend`，不依赖容器显示名称。
- 全新部署和更新脚本均通过 Compose service 操作容器，不依赖 `container_name`。
- 现场同机存在多套历史平台容器；全局固定名称会跨 project 冲突，主机端口、project 名和 external volume 名仍必须保持实例唯一。
- 全新平台为空库，首次配置 GitLab PostgreSQL 后执行 `FULL_SYNC`，成功运行按现有链路自动创建全量 `FACT_REFRESH`；不单独手工重建事实层。

## 证据与根因

- 现场日志显示升级脚本成功加载目标镜像、停止 `qaflex-backend-ldaptest`，随后 Docker 报 `/qaflex-backend` 已被容器 `6aaa...` 占用。
- 失败后现场 `docker-compose.yml` 已是目标镜像，因此再次执行脚本按设计报 `backend baseline image does not match ...082443...`。
- 根因是 `compose_content()` 同时强制 Compose project 和全局固定 `qaflex-postgres`、`qaflex-backend`、`qaflex-frontend`；后者绕过 project 命名空间，使本应隔离的多个实例争用 Docker daemon 全局名称。
- 本地隔离演练只有单一目标 project，未预置其他 project 的同名容器，因而没有暴露该发布环境差异。

## 方案与步骤

1. 删除打包器生成 Compose 中三个 `container_name`，保留服务名、project、端口和 volume 契约。
2. 增加生成内容测试，明确禁止所有 `container_name`，并验证服务发现仍使用 `postgres:5432`。
3. 增加实际 Compose/Docker 回归场景：预置其他 project 的旧固定名称容器，新 fresh project 仍能创建自身三项服务、网络和 volume。
4. 更新发布标准和架构决策，规定 `COMPOSE_PROJECT_NAME` 是容器命名空间唯一事实源，发布包不得声明全局固定容器名。
5. 运行打包器单元测试、Compose 解析、仓库门禁及受影响发布验证。
6. 生成 `fresh-empty` 包，携带 PostgreSQL 16、目标后端和前端 `linux/amd64` 镜像，不携带任何平台数据、真实 `.env` 或旧 volume。
7. 在隔离栈预置名称冲突容器后启动全新 project，验证三服务健康、Flyway、空业务数据、独立 volume 和旧容器不变；执行停止/再次启动验证持久化。
8. 生成匹配新 release ID 的包外 20001 `.env` 和部署说明，更新进度，删除本计划，提交并推送。

## 决策记录

- 已选移除 `container_name`，让 Compose 按 `<project>-<service>-<replica>` 管理容器；该模型原生支持同机多实例且脚本已完全按 service 寻址。
- 否决把固定名称改成另一组 20001 特例名称；这会把环境差异继续写入发布模板并在下一实例重复失败。
- 否决删除或重命名现场 `/qaflex-backend`；它可能属于另一有效实例，发布包不应要求破坏其他 project 才能部署。
- 已选全新 project 和新 volume，旧项目只停用并保留为回退；否决在旧部署目录直接改成空平台，以免新旧数据所有权混淆。
- 否决绕过旧包基线检查；`110730` 继续作废，不作为全新部署材料。

## 接口契约

- Compose 服务名保持 `postgres`、`backend`、`frontend`；内部 JDBC 地址继续为 `postgres:5432`。
- `COMPOSE_PROJECT_NAME` 为全新实例必填命名空间；fresh 包的 PostgreSQL 与日志 named volume 由该 project 自动命名，不引用旧 external volume。
- 不新增产品 API、数据库迁移或事实字段；只改变 Docker 容器显示名称的生成规则。
- 新发布清单类型为 `fresh-empty`，`baseline=null`，三项镜像使用新的唯一 release ID/固定 PostgreSQL 版本。

## 风险与假设

- 旧现场 Compose 可能显式使用自定义 `container_name`；新 project 不管理这些旧 service，只通过不同 project、端口和 volume 隔离。
- 旧实例若仍占用 20001/20002/15433，启动新实例会发生端口冲突；部署说明必须要求先只停止旧 project，不删除其 volume，再启动新 project。
- 其他运维脚本若按容器显示名寻址会失效；仓库必须搜索并改为 Compose service 寻址，现场外部脚本需在部署说明中提示。
- 现场回滚结果需用户确认；本地不能替代真实服务器的容器标签、端口和其他实例状态证据。
