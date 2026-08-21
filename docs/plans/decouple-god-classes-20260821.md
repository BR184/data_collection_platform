# 进度与中间物

- 状态：接续 `code-audit-refactor-20260821.md` 暂停点，执行非统计模块上帝类拆分。统计看板工厂与 SQL/内存筛选双轨由同事负责，本工作单元冻结不触碰。
- 已完成：确认 `service/statistics/` 目录已回退到 HEAD 干净状态（用户指令：涉及同事模块的改动统统回退）。
- 已完成：工具链确认——后端经 Docker `maven:3.9-eclipse-temurin-21`，前端 Node 24 本地。
- 当前进行点：深读五个目标文件，按依赖顺序逐个拆分。

## 恢复线索

- 当前阶段：阶段五"大型类拆分"（继承上一计划的阶段四已完成部分）。
- 恢复后首条命令：`git status --porcelain -- backend/src/main/java/com/data/collection/platform/service/statistics/`（必须为空）。
- 上一份计划：`docs/plans/code-audit-refactor-20260821.md`。

## 目标与边界

- 用户原始需求：根据交接文档继续整个项目的解耦；同事正在处理 `service/statistics/` 看板工厂与 SQL/内存筛选双轨两个问题，本工作单元不得修改该目录及其筛选语义。
- 成功标准：五个上帝类拆分为职责清晰的协作者，行为不变，定向测试通过，Checkstyle/SpotBugs 零违规，前端 typecheck/build/test 通过。
- 明确禁止：修改 `backend/src/main/java/com/data/collection/platform/service/statistics/` 下任何文件；改变业务口径、公开 HTTP API、数据库迁移、事实发布代际、导出列序；为旧实现保留别名或双轨。

## 约束与背景

- 后端 Java 21/Spring Boot 3.5/MyBatis-Plus；前端 Vue 3/TS/Vite/Vitest。
- 项目维护文本中文，标识符英文；公共方法补 Javadoc/JSDoc 契约。
- 内部接口允许破坏性调整，但必须同步全部调用者与测试并删除旧路径。

## 方案与步骤

1. [完成] 回退 statistics 目录到 HEAD（用户指令）。
2. [进行中] 深读五个目标：`GitlabExternalDbService`(582)、`IssueFactRecordRepository`(1135)、`CodeReviewIllegalRecordService`(1304)、`BaseRecordTable.vue`(1527)、`MirrorSettingsView.vue`(1374)。
3. [待办] 后端拆分一：`GitlabExternalDbService` → 扫描编排 / Docker 行解析 / 连接生命周期 / 配置响应组件。
4. [待办] 后端拆分二：`IssueFactRecordRepository` → 条件构建器 + 结果读取器，保留唯一查询入口与排序白名单。
5. [待办] 后端拆分三：`CodeReviewIllegalRecordService` → 导出器 / 来源访问端口 / 规则预览 / 响应映射。
6. [待办] 前端拆分四：`BaseRecordTable.vue` → 筛选状态 / 列宽测量 / 滚动条生命周期 composable。
7. [待办] 前端拆分五：`MirrorSettingsView.vue` → 沿现有 useMirror*Controller 模式拆诊断加载 / 配置快照 / 事实操作。
8. [待办] 收口：定向测试 + Checkstyle/SpotBugs + 前端全量门禁 + 更新 progress.md。

## 决策记录

- 已选：先深读再拆分，每个文件拆分前先确认现有测试保护范围；无测试保护的行为先补行为测试再动结构。
- 已选：拆分顺序按依赖风险从低到高：GitlabExternalDbService（边界清晰）→ IssueFactRecordRepository → CodeReviewIllegalRecordService → BaseRecordTable → MirrorSettingsView。
- 已决：statistics 目录冻结；发现该目录的任何必要协作接口变化时停止并报告用户。

## 风险与假设

- 工作树包含大量未提交变更（168 项），本工作单元在其上继续，不回退用户已有改动。
- 本机无 mvn/java，后端验证走 Docker；SpotBugs 需在每阶段补跑。
- `IssueFactRecordRepository` 与 `CodeReviewIllegalRecordService` 的 SQL 条件拼装可能与 statistics 包存在调用关系，拆分时只改非统计侧实现，不改统计侧调用契约。

## 接口契约

- 不新增对外 API、表结构、数据格式。内部接口调整同步所有调用者与测试。
