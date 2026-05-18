# 2026-05-18 同步模块解耦方案

## 背景

新的 GitLab 数据同步模块已经完成 run/task/state/event 的核心建模，并补齐了优先级、去重、锁、压力验证和管理员权限边界。当前模块可以支撑现有链路，但执行端和契约层仍偏耦合：调度器直接执行 run，线程预算尚未驱动 worker 并发，payload 仍以裸 JSON/Map 解析，状态集合在多个服务中重复维护。

后续目标不是重写同步模块，而是在现有稳定提交模型上分阶段解耦，使每一阶段都可验证、可回退。

## 总体目标

- 保持现有同步行为、接口和压力测试结论不回退。
- 将状态机、payload、运行时执行、源读取、fact refresh 逐步拆成清晰边界。
- 为外部来源表和管理员运维能力保留独立权限域。
- 明确本项目使用项目内局部工具链，后端验证使用 `tools/maven/apache-maven-3.9.9/bin/mvn.cmd`，不依赖全局 `mvn`。
- 将实装网络视为不稳定环境：内网经常掉线、断连、延迟，大文件上传可能需要多次重试才能成功。

## 当前风险标记

| 编号 | 优先级 | 问题 | 影响 | 处理阶段 |
| --- | --- | --- | --- | --- |
| DCP-SYNC-DEC-01 | P0 | active/terminal/completed 状态集合在多个服务重复维护 | 状态扩展时容易漂移 | 第一阶段 |
| DCP-SYNC-DEC-02 | P0 | run payload 使用裸 Map 和字符串判断 | precise target 缺字段会变成 `"null"`，fact fullBuild 解析脆弱 | 第一阶段 |
| DCP-SYNC-DEC-03 | P1 | dispatcher 领取 run 后同步执行完整任务 | 大 run 会占住调度线程，线程预算未真正生效 | 第二阶段 |
| DCP-SYNC-DEC-04 | P1 | 全量同步仍可能一次性 `select *` 读入内存 | 大表同步有内存和外部库压力 | 第三阶段 |
| DCP-SYNC-DEC-05 | P1 | fact refresh 由 run worker 直接提交和执行 | 镜像层与事实层生命周期耦合 | 第四阶段 |
| DCP-SYNC-DEC-06 | P2 | 同步诊断存在重复实现残留 | 后续维护成本和接口语义不清 | 第一/第二阶段 |
| DCP-SYNC-DEC-07 | P1 | 实装网络质量差，上传和外部查询容易中断 | 同步/上传链路必须可重试、可恢复、幂等 | 第二/第三阶段 |

## 第一阶段：状态与契约收口

范围：

- 新增统一的 `SyncRunStateMachine`，集中 active/completed/terminal 判断和 API 状态映射。
- 新增 typed `SyncRunPayload`，集中 sourceTables、fullBuild、preciseTargets 的解析和序列化。
- 将 `SyncRunPolicyService`、提交去重、状态查询、表规划、fact refresh fullBuild 判断切到统一契约。
- 增加单元测试覆盖：
  - active/completed/terminal 状态集合；
  - `MERGED` 当前仍保持 API `PENDING` 表现；
  - malformed precise target 不再生成 `"null"` 任务；
  - fullBuild 通过 JSON 布尔字段解析。

验收：

- 后端相关同步单元测试通过。
- 现有提交/去重/压力测试语义不变。
- 不引入数据库迁移。
- 使用项目内 Maven 验证：`tools/maven/apache-maven-3.9.9/bin/mvn.cmd`。

## 第二阶段：运行时执行解耦

范围：

- dispatcher 只负责 claim run 和投递执行，不在 scheduled 线程内长时间跑完整 run。
- 引入 run executor/table worker pool，并让 `SyncThreadBudgetResolver` 真正控制并发。
- 补齐 heartbeat renewal、lease timeout recovery、worker lease 表实际写入。
- 针对弱网络环境加入更明确的 lease 恢复、外部查询重试、断线后继续执行策略。

验收：

- 同一 source scope 仍互斥。
- 不同 source 或不同 scope 可按线程预算并行推进。
- 高优先级 run 在领取顺序上仍优先。

## 第三阶段：源读取与镜像写入解耦

范围：

- 抽出 `SourceTableReader`、`SourceMetadataInspector`、`MirrorTableWriter`。
- 全量、增量、精确同步统一走 batch/cursor contract。
- 避免全量 `select *` 一次性加载。

验收：

- 大表全量同步内存稳定。
- Docker/JDBC 两种来源模式共用同一读取契约。
- 为未来大文件/压缩包上传保留 resumable chunk、校验和、幂等重试的接口设计空间。

## 第四阶段：fact refresh 事件化

范围：

- 镜像 run 完成后发布 run completion 事件。
- fact refresh 作为订阅者提交/合并任务，不再由 `SyncRunWorkerService` 直接依赖。

验收：

- 镜像层和事实层可以独立测试。
- fact refresh 去重和父 run 追踪不回退。

## 第五阶段：权限域与来源表完成

范围：

- 同步配置、源表浏览、诊断、运行队列全部归管理员域。
- 游客只保留外部表单和未来脱敏公开摘要 API。
- 完成外部来源表功能时必须以管理员权限、审计日志、脱敏展示作为默认约束。

## 真实用户链路验收补充

目标：

- 使用本地拉起的 GitLab 作为真实数据源，不只依赖 mock、单元测试或开发者视角的接口测试。
- 从管理员用户手动操作路径验证：登录、进入同步配置、保存数据源、测试连接、诊断、全量同步、增量同步、按表刷新、运行队列/监控展示、system hook 接收与触发。
- 验证同步策略线程设置的真实效果：固定线程、按 CPU 比例、上限线程数在 run/table task 执行、worker lease、队列推进中的表现一致。
- 将 Data mirror monitor 模块用户可见文案改为中文，保证管理员在真实使用时能理解运行状态、队列、错误和操作反馈。
- 按百万级数据量风险评估链路：全量同步必须走游标/批次推进，不能因为少量测试数据通过就认为可上线；需要观察批次数、任务续排、内存、外部查询超时、lease 恢复和幂等重试。

真实链路测试范围：

- 数据源连接：使用本地 GitLab PostgreSQL 或本地 GitLab Docker 数据源配置，验证 `/api/gitlab-sync/test-connection/by-config` 与前端按钮表现一致。
- 元数据诊断：通过前端诊断入口验证白名单、主键、`updated_at`、system hook 配置、运行时告警展示。
- system hook：使用本地 GitLab hook 或等价 HTTP 请求携带 `X-Gitlab-Event`、`X-Gitlab-Token` 和真实 payload，确认 precise sync run 被提交并进入队列。
- 线程策略：分别配置低线程与高线程，观察 `sync_worker_leases`、run/table task 状态、并发 table task 数是否随预算变化。
- 大数据风险：若本地 GitLab 数据不足百万级，则需要构造百万级镜像源测试表或记录为阻塞项，不能用小数据结论替代百万级结论。

验收记录格式：

- 环境：本地 GitLab 地址、来源模式、后端地址、前端地址、数据库连接方式、测试账号角色。
- 操作：按用户界面步骤记录，不只记录 API 命令。
- 结果：每个步骤记录 UI 表现、关键 API 返回、数据库 run/task 状态和日志摘要。
- 性能：记录数据量、批大小、线程配置、任务数、耗时、失败/重试次数。
- 结论：通过、部分通过、阻塞；阻塞项必须标明是环境问题、数据量不足、功能缺口还是 bug。

## 执行记录

- 2026-05-18：开始第一阶段，实现状态机与 typed payload 收口。
- 2026-05-18：推进第二阶段第一片，新增 run executor，dispatcher 从直接执行改为领取后异步投递，避免 scheduled 线程长时间占用。
- 2026-05-18：推进第二阶段第二片，新增 run lease heartbeat 与超时 run 恢复，补偿调度会先回收 stale run，再回收 stale table task。
- 2026-05-18：推进第二阶段第三片，表任务 drain 支持按 run 线程预算并发领取和执行，仍通过数据库 `for update skip locked` 保持任务互斥。
- 2026-05-18：推进第二阶段第四片，run executor 写入 `sync_worker_leases`，记录 worker heartbeat、最大线程数、活跃线程数和队列深度。
- 2026-05-18：推进第三阶段第一片，全量同步改为按主键游标分页读取，满批次自动续排下一批，最终批次再刷新 watermark，避免一次性 `select *` 拉取整表。
- 2026-05-18：推进第三阶段第二片，抽出 `SourceTableReader`，表任务 worker 只依赖源读取契约，外部库读取细节继续收敛到独立边界。
- 2026-05-18：推进第三阶段第三片，抽出 `MirrorTableWriter`，表任务 worker 通过镜像写入契约落库，写入细节继续保留在 storage service 内。
- 2026-05-18：推进第三阶段第四片，抽出 `SourceMetadataInspector`，白名单发现和镜像表结构发现通过元数据契约访问外部源。
- 2026-05-18：推进第四阶段第一片，新增 `SyncRunCompletionEvent` 与 fact refresh 监听器，镜像 run worker 改为发布完成事件，不再直接提交 fact refresh。
- 2026-05-18：推进第四阶段第二片，抽出 `SyncFactRefreshRunExecutor`，FACT_REFRESH run 的队列领取与执行从通用 run worker 中移出。
- 2026-05-18：补齐诊断边界收口，`GitlabSyncController` 的来源元数据诊断改为通过 `SourceMetadataInspector`，避免控制器直接依赖外部库访问服务。
- 2026-05-18：继续收口旧同步门面，抽出 `SourceConnectionTester`，`GitlabMirrorSyncService` 不再直接依赖外部库服务并移除未使用的旧运行时依赖。
- 2026-05-18：同步真实用户链路测试要求，后续以本地 GitLab、前端手动等价操作、system hook、线程策略和百万级数据风险为验收口径。
