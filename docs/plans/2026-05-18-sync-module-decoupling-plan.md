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
