# 修复评审数据快照来源版本超长

## 进度与中间物

- 状态：数据库修复和前端资源故障修复完成，真实 LDAP Session 页面验收待补。
- 已完成：在 `18181` 评审数据管理页稳定复现并修复 `source_version` 超长；随后复现系统设置相关路由的 Vite `504 Outdated Optimize Dep`，以强制重建优化依赖的开发启动命令修复。
- 已验证：主源码编译、Checkstyle、`PageRecordSnapshotServiceTest`、`FlywayMigrationSmokeTest`、Flyway 118 份迁移锁定检查、前端生产 `vite build` 和六个目标路由浏览器烟测通过；Flyway `20260803.01` 成功应用，公共列类型为 `text`。
- 未完成：使用真实 LDAP Session 对评审数据和设置页面进行人工权限链路验收。
- 当前阻塞：浏览器中的 LDAP Session 在后端重启后失效；不代填密码。

## 恢复线索

- 当前阶段：实现完成，等待认证后的页面验收。
- 恢复后建议首条命令：登录 `http://localhost:18181/review-data/home` 后刷新页面，观察两个评审数据请求是否成功；随后运行 `git status --short --branch`。
- 相关进度：[docs/progress.md](../progress.md)。

## 目标与边界

### 用户目标

- 修复 `18181` 评审数据管理页面显示“服务处理异常，请联系开发人员排查”的问题。

### 成功标准

- 评审数据筛选选项接口和分页接口均返回成功响应。
- 页面可以正常加载筛选项和记录列表。
- 新部署数据库和现有数据库都使用与动态来源版本值匹配的字段类型。
- 服务层回归测试覆盖来源版本可超过旧 256 字符限制且仍可保存/读取的契约。

### 明确禁止

- 不截断、哈希替换或静默丢失来源版本内容；来源版本必须继续作为快照失效键参与精确匹配。
- 不删除已有快照数据，不执行 `docker compose down -v`，不重建 PostgreSQL 数据卷。
- 不覆盖工作树中与本故障无关的用户改动。

## 约束与背景

- 后端使用 Spring Boot + PostgreSQL + Flyway，当前开发数据库端口为 `15432`。
- `PageRecordSnapshotService.reviewDataSourceVersion()` 的结果是内部缓存版本标识，不是面向用户的固定长度 API 字段。
- `page_record_snapshots` 的唯一约束不包含 `source_version`；写入同一请求时更新来源版本，读取时按来源版本精确命中。

## 证据与根因

- 浏览器复现路径：打开 `http://localhost:18181/review-data/home`。
- 失败请求：`GET /api/review-data/records/filter-options`、`GET /api/review-data/records?page=1&size=20`。
- 后端错误：`value too long for type character varying(256)`，超长列为 `page_record_snapshots.source_version`。
- 根因链：读取评审数据 -> 计算包含读模式、评审记录、兼容模式评审、Issue 事实和标签组版本的动态来源版本 -> `save()` 写入快照 -> PostgreSQL 256 字符约束失败 -> 接口返回通用服务异常。
- 前端资源根因链：路由懒加载设置页面 -> Element Plus 依赖优化资源带旧版本查询串 -> Vite 返回 `504 Outdated Optimize Dep` -> `router.onError` 保留基础壳并显示统一错误；重启 Vite 并使用 `--force` 后资源重新生成，六个目标路由均可挂载。

## 方案与步骤

1. 新增 Flyway 迁移，将 `page_record_snapshots.source_version` 改为 `text`。
2. 更新 `backend/src/main/resources/schema.sql`，保证初始化 schema 与迁移后结构一致。
3. 增加服务层回归测试，验证长来源版本可以进入快照写入参数，并保留现有来源版本组成契约。
4. 重启后端并确认 Flyway 执行成功；通过两个 HTTP 接口和浏览器页面验收。
5. 运行相关后端测试、编译和前端类型/构建检查；更新 `docs/progress.md`，最后检查差异和工作树。
6. 将 `frontend` 开发启动固定为 `vite --force`，同步路由烟测的认证和设置接口夹具，覆盖资源失效回归。

### 已执行

- 已完成步骤 1-6 的代码、迁移、定向自动化和运行环境验证；页面认证验收仍待登录后执行。

## 决策记录

- 采用 `text` 而不是继续扩大为另一个固定 `varchar` 长度，因为来源版本由多个动态组成部分生成，本身没有对外长度契约；数据库应表达其不透明文本标识语义，避免下一次版本组成扩展再次触发同类故障。
- 保留完整来源版本字符串，不通过截断或摘要改变现有快照命中/失效语义。
- 不新增应用层兼容分支；现有表通过一次可审计的 Flyway DDL 迁移完成结构修复。
- 已验证迁移实际在公共 schema 应用成功，未触碰 PostgreSQL 容器或数据卷。
- Vite 开发服务采用 `--force` 启动，避免重启后复用失效的依赖优化资源；生产构建和 Nginx 发布链路不受该开发参数影响。

## 接口契约

- HTTP 接口不变：`GET /api/review-data/records/filter-options`、`GET /api/review-data/records`。
- 数据库字段契约变更：`page_record_snapshots.source_version` 从 `varchar(256)` 改为 `text`，允许完整保存动态来源版本。
- `PageRecordSnapshotService` 的来源版本生成规则不变。
- 开发启动契约：`frontend` 的 `npm run dev` 使用 `vite --force`，启动时重建依赖优化缓存。

## 风险与假设

- 风险：后端当前进程可能使用旧构建产物；已通过重启日志确认 `20260803.01` 成功应用且新进程监听 18080。
- 风险：已有失败请求可能留下部分快照或状态；验证时检查接口成功和快照可读取，不删除业务数据。
- 风险：未登录状态下无法验证真实 LDAP 权限集合是否包含全部系统设置权限；当前仅以管理员模拟响应完成路由资源和挂载烟测。
- 假设：当前本地 PostgreSQL 是项目开发库，Flyway 自动迁移已启用；已验证该假设。页面最终验收仍依赖有效 LDAP 账号，不手工绕过权限。
