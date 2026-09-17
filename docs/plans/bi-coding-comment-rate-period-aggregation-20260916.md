# BI 编码质量趋势：注释率按时间桶聚合修正

> **文档版本**：v1.1（2026-09-16）
>
> **文档性质**：已实施工作单元方案与审批记录
>
> **适用范围**：`/api/bi/coding` 代码注释率与走查缺陷密度趋势；不包含旧平台代码走查多维看板接口

## 进度与中间物

- **状态**：**评审补正、普通测试、构建、黄金基线更新与严格回归均已完成（2026-09-16）**；审批结论为通过。本工作单元未提交或推送，工作树中的其它在途改动仍由原工作单元所有。
- **恢复线索**：当前阶段=审批收口；恢复后首条命令=`git status --short`，随后读取本计划和 `docs/bi-dashboard/progress.md` 顶部同日条目。当前工作树已有他人在途的 BI 密度修复、静态扫描下线、文档和快照修改，禁止重置或覆盖。
- **已完成调查**：已确认后端逐条返回 `comment_rate`，前端以同日键构造 `Map` 并发生最后一条覆盖；密度趋势已经在后端按日/周聚合；旧平台同一物理字段存在 `avg(comment_rate)` 先例；本工作单元已将注释率周期聚合正式冻结为核对表 CD-38A，并在实现中补齐密度冲突、量程和交互边界。
- **测试状态**：本次评审补正后后端编码相关定向测试 43/43 全绿；前端全量 Vitest 134 文件/530 项、类型检查、Lint、生产构建和 Impeccable 检测均通过；黄金基线更新模式与不带更新参数的严格回归均为 191/191 全绿。后端默认 `mvn test` 为 1361 项、0 failures、76 errors、1 skipped，错误均是本机未启动 `localhost:15433` 测试 PostgreSQL 导致的 Spring 集成上下文连接错误，未出现业务断言失败；不把该环境结果伪报为全绿。
- **评审补正**：修复了 SystemTest 测试夹具类型阻塞；密度冲突走查 ID 按输入顺序不再任选首条；自适应值轴取整后继续保证最低量程；断轴遮罩设置为非交互并新增真实 ECharts/ZRender 悬浮命中回归；密度完整性与走查工时能力拆分。黄金快照已人工审阅，开发服务已恢复并验证后端 `/actuator/health=UP`、前端 HTTP 200。
- **当前审批结论**：报告指出的五项问题均已修复并有对应回归；允许将本工作单元结果交付。工作树其它未提交改动不得借本审批一并视为本单元成果。

## 目标与边界

### 用户目标

修正编码页“代码注释率与走查密度趋势”中注释率取值错误和两条轨道粒度不一致的问题，使日粒度、周粒度、页面图表和 Excel 导出的数值使用同一套后端口径。

### 可验证成功标准

1. 同一日或同一周存在多条合法注释率记录时，接口只返回一个该时间桶的注释率算术平均值，不再取最后一条。
2. 日粒度和周粒度的注释率时间桶与 `reviewDensityTrend` 完全一致；周桶以周一为桶起点。
3. `NULL`、缺失日期和现有规则判定为非法的注释率不进入平均；没有合法记录的时间桶保持缺失，前端显示 `null` 并断线，不补零、不前向填充、不连接断点。
4. 同一稳定走查记录 ID 的完全重复事实只计算一次；同一 ID 的核心字段冲突不得按输入顺序任选一条进入平均，相关区块标记 `INCOMPLETE`，其它无关事实族不被清空。
5. 注释率不使用 `added_lines`、被走查行数、缺陷数或其它字段加权；因为来源没有注释行数/代码行数分子分母，不能臆造加权口径。
6. API、前端图表、Excel 导出、单元测试和文档均反映新契约；旧的 `commentRatePoints`/`CommentRatePoint` 语义不保留为兼容别名。
7. 普通测试和构建全绿后，受影响 BI 黄金快照经更新模式重建并人工审阅；未解释差异不得带入提交。

### 明确不在本次范围

- 不修改 `code_review_match_mode_records`、`code_review_formal_records` 的表结构、同步流程或来源 SQL。
- 不修改旧平台 `CodeReviewMultiBoardService`；其 `avg(comment_rate)` 只作为已存在的参考先例。
- 不修改走查密度 CD-39 的公式、同 MR 行数去重和当前已完成的覆盖率修复。
- 不通过 `coalesce`、零值、替代日期、最近值或插值消除断点。
- 不把“同 MR 去重”新增到注释率；注释率使用走查记录事实族的稳定走查记录 ID 去重。
- 自适应值轴与断轴交互只按本次评审报告修正到可验证的最小范围，不扩展为新的图表视觉改造。

## 约束与背景

- BI 业务字段、过滤、去重、聚合和精度以 `docs/bi-dashboard/BI看板数据来源与计算口径核对表.md` 为唯一权威；`data-contracts.md` 约束数据完整性和前后端职责。
- API 请求已有 `granularity=day|week`；后端密度趋势已使用该粒度，注释率不能继续返回逐记录日数据。
- 当前适配器只查询 `comment_rate` 和可选 `comment_rate_source`。虽然走查事实有 `added_lines` 等其它字段，但没有注释率专用分子/分母，不能将它们当作权重。
- 当前代码合法性判断为：走查日期非空、`comment_rate` 非空、`comment_rate >= 0`。除非核对表另有用户确认，不额外新增“必须小于等于 100”的过滤条件，以免改变既有输入语义。
- 百分比数值沿用 0–100 量纲，聚合结果保留 2 位小数，使用非负数场景下等价于旧 SQL `round(avg(...), 2)` 的 `HALF_UP` 规则。
- 当前工作树不是干净基线。实现 AI 必须先区分已有改动和本工作单元改动，禁止 `reset --hard`、`checkout --` 或批量覆盖。

## 证据与根因

1. `BiPlatformCodingSourceAdapter` 按 `merged_at_source, id` 查询走查记录；这只是来源顺序，不是“同日应取的业务记录”。
2. `BiCodingCalculator.commentRatePoints(...)` 当前一条记录生成一个点，并按走查日期排序，没有使用 `source.granularity()`。
3. `CodingStageContent.vue` 使用 `new Map([observedOn, commentRate])` 对同日值折叠，JavaScript `Map` 对重复键保留最后一次写入值。
4. 同一当前样例中，2025-11-17 有 13 条注释率记录，最后一条为 `0.82`，算术平均为 `13.21`；这证明现状不是可接受的业务聚合。
5. `reviewDensityTrend(...)` 已按 `bucket(reviewedOn, granularity)` 计算，因此后端两条轨道的输出层级不一致是根因，前端覆盖只是把错误暴露出来。
6. 旧平台 `CodeReviewMultiBoardService` 对同一 `comment_rate` 使用 `avg`，但现有 BI CD-38 仍只写“直接使用上游值”，所以必须把 BI 趋势聚合正式写入 CD-38A，而不能只凭旧代码隐式推断。

## 已审批的目标契约

新增或修订核对表条目 **CD-38A**，内容必须与以下文字一致：

> 代码注释率趋势以 CD-16 去重后的合法走查记录为输入，使用 CD-16A 的走查日期按请求粒度分桶：日粒度使用自然日，周粒度使用周一所在日期。每个时间桶对 `comment_rate` 做非加权算术平均，忽略 `NULL` 和现有合法性校验排除的记录，结果保留 2 位小数。缺少合法记录的时间桶不生成注释率值，由页面与密度轨对齐后显示为 `null`；不得取最后一条、按新增代码行数加权、按 MR 再去重、补零或插值。`comment_rate_source` 仅作为来源追溯字段，兼容态为空不影响合法注释率参与计算。

具体规则：

| 项目 | 批准口径 |
| --- | --- |
| 记录粒度 | CD-16 的稳定走查记录 ID；完全相同重复记录去重 |
| 冲突处理 | 同一走查 ID 核心字段冲突时，该冲突事实不得进入聚合，注释率区块为 `INCOMPLETE`；其它合法记录仍可出数 |
| 日期 | `reviewedOn`/`code_walkthrough_date`；为空的记录不进入趋势 |
| 日桶 | 记录日期本身 |
| 周桶 | 周一作为桶值，与 CD-39 的 `bucket` 规则一致 |
| 合法值 | 沿用现有 `validCommentRateInputs`：日期非空、值非空、值非负 |
| 聚合 | `sum(valid comment_rate) / count(valid comment_rate)`，不加权 |
| 精度 | 原始值先求和，最后一次性保留 2 位小数，`RoundingMode.HALF_UP` |
| 无数据桶 | 不返回点；前端对齐时填 `null`，保持 `connectNulls=false` |
| 覆盖率 | 继续按走查记录数统计 `totalObservations`/`validObservations`，不要误改成时间桶数 |

## 接口契约

为避免把“周期聚合值”继续伪装成“单次记录点”，直接替换编码页 DTO：

```text
BiCodingPageData.commentRateTrend: List<CommentRateTrendPoint>
CommentRateTrendPoint(
    LocalDate period,
    BigDecimal averageCommentRate
)
```

删除旧的 `commentRatePoints`、`CommentRatePoint`、`codeReviewId`、`observedOn`、`commentRate` 和 `commentRateSource` 页面输出字段；这些字段仍可保留在来源事实 `BiCodingSource.CodeReviewRecord` 中用于计算和追溯，但不能继续作为聚合趋势 API 输出。

响应约束：

- `commentRateTrend` 按 `period` 升序、每个周期最多一个元素。
- `averageCommentRate` 已是 0–100 百分数，前端和 Excel 原样使用，不再乘 100。
- `commentRateCoverage` 仍是记录级覆盖率；区块提示文字应明确“有效走查记录 X/Y”，避免与趋势周期数混淆。
- `reviewDensityTrend` 的字段和语义不改；两条趋势均由后端返回同粒度周期值。
- `RULE_VERSION` 从 `bi-coding-v4` 升为 `bi-coding-v5`，因为页面产出公式发生有意变更。

## 方案与实施步骤

### 0. 实施前检查与工作树隔离

1. 读取 `AGENTS.md`、`docs/bi-dashboard/README.md`、`docs/bi-dashboard/progress.md`、本计划、核对表、`data-contracts.md` 和 `decisions.md`。
2. 执行 `git status --short`、`git diff --stat`，记录已有修改；不得回滚他人在途的 `BiCodingCalculator`、BI 文档或快照改动。
3. 用 `rg` 搜索 `commentRatePoints`、`CommentRatePoint`、`commentRateSource` 的全部调用点，确保替换完成后没有旧符号残留。

### 1. 冻结文档契约

1. 在核对表 CD-38 后新增 CD-38A，写入本计划“已审批的目标契约”，并明确“等价映射”与 `comment_rate` 的物理字段。
2. 在 `docs/bi-dashboard/data-contracts.md` 补充：周期聚合由 BI 后端完成、前端只做周期轴对齐、记录级覆盖率不能解释为周期数量、禁止前端最后值覆盖。
3. 在 `docs/bi-dashboard/decisions.md` 增加一个新的 BI 决策条目，记录选择算术平均、后端聚合、拒绝最后一条/加权/MR 去重的理由和状态。
4. 更新 `docs/bi-dashboard/progress.md`：解除“注释率同日/周聚合公式未冻结”的阻塞，记录 CD-38A 和待实现 `bi-coding-v5`；不要覆盖其中已有的密度修复和静态扫描记录。

### 2. 后端领域模型和计算

1. 在 `BiCodingPageData` 中把 `commentRatePoints` 替换为 `commentRateTrend`，新增 `CommentRateTrendPoint(period, averageCommentRate)`，同步更新 Javadoc 和不可变列表复制。
2. 在 `BiCodingCalculator` 中把当前 `commentRatePoints(commentValues)` 改为接收 `source.granularity()` 的周期聚合方法，使用与密度一致的日期桶函数和升序 `TreeMap`。
3. 聚合器只接收当前合法且无冲突的记录；先累加原始 `BigDecimal`，最后按桶除以有效记录数并保留 2 位小数。不得在每条记录进入桶时先四舍五入。
4. 保持 `commentRateCoverage` 的记录级统计和当前局部不完整语义；没有合法记录的桶不创建零值点。
5. 对同一走查 ID 冲突不得依赖 `LinkedHashMap` 的首条记录。若当前 `DistinctResult` 不暴露冲突 ID，直接增强该抽象或增加面向注释率事实的冲突集合，使冲突事实不进入注释率聚合；不要为旧接口增加 `v2`、别名或双轨路径。
6. 更新 `BiMetricTrace`：保留 CD-38 的来源字段，加入 CD-38A，公式说明改为“周期内合法注释率非加权算术平均，忽略 NULL，保留 2 位小数；密度按 CD-39”。
7. 将规则版本改为 `bi-coding-v5`。不修改来源查询、数据库迁移或旧平台 Service。

### 3. 前端数据契约与趋势组装

1. 更新 `frontend/src/features/bi-dashboard/data/types.ts`，将页面响应字段改为 `commentRateTrend: Array<{ period: string; averageCommentRate: number | null }>`。
2. 更新 `CodingStageContent.vue`：只使用后端已聚合的 `period`/`averageCommentRate` 做周期轴并集和查找；查找不是聚合，不得再按同日记录选择最后值。
3. 保留密度轨 `reviewDensityTrend` 的现有语义；当某一周期只有一条轨有数据时，另一条轨为 `null`，不补零、不连接。
4. 更新图表说明中的“当日无合法观测”为“当前选择粒度的时间桶无合法注释率观测”，避免周粒度下文案错误。
5. 不修改 `QualityTrendSmallMultiplesChart` 的数值计算、Excel 百分数格式或 `connectNulls=false`；其输入已经从页面 DTO 统一转换为 `QualityTrendData`。

### 4. 测试补齐

#### 后端 `BiCodingCalculatorTest`

至少增加以下独立行为测试：

1. **同日算术平均**：同一天合法值 `0、10、50、100` 返回 `40.00`，明确不是最后一条。
2. **NULL/非法值忽略**：同一桶含合法值、`NULL`、负值和缺失日期，只用合法值；覆盖率仍按记录数报告。
3. **周桶**：跨同一周的日期归并到周一，跨周日期生成两个桶，结果升序。
4. **非加权**：设置不同 `addedLines`/`reviewedLines`，验证结果仍为注释率算术平均，不能被这些字段改变。
5. **重复走查 ID**：完全相同事实只计一次。
6. **走查 ID 冲突**：冲突记录不被首条/末条选择，区块为 `INCOMPLETE`，无冲突合法记录仍可形成周期点。
7. **兼容态来源为空**：`commentRateSource=null` 的合法值仍进入平均。
8. **无合法桶**：该桶不返回零值，供前端形成真实断点。
9. 保留并复验现有密度趋势测试，证明本次注释率改动未改变 CD-39。

#### 前端 `CodingStageContent.test.ts`

1. 将响应 fixture 改为 `commentRateTrend` 周期点，不再构造逐记录 `commentRatePoints`。
2. 验证同一周期只有一个后端值，页面按周期与密度轨对齐；缺失周期为 `null`。
3. 增加 day/week 周期样例，确认页面不进行二次聚合、不改变后端平均值。
4. 删除旧字段测试，`rg` 确认生产代码和测试中无旧 DTO 符号。

#### 接口与图表验证

- 运行后端相关单测、完整 `mvn test`、前端 BI 定向 Vitest、前端全量 Vitest、类型检查、Lint 和现有构建命令。
- 使用现有真实 fixture 请求 `/api/bi/coding` 的 day 与 week，检查序列每个周期唯一、两条轨周期键一致、序列值与手算平均一致。
- 验证 Excel 表格第一列为周期，注释率与图表相同且保持 0–100 量纲。
- 视觉验收只确认：重复记录不再覆盖、日/周粒度一致、无合法观测的周期仍诚实断线；不得以“没有任何断点”作为验收标准。

### 5. 黄金基线与发布门禁

1. 普通测试、构建、`git diff --check` 和工作树产物检查全部通过后，先提交实现差异、文档差异和一份 day/week 手算对照，等待本工作单元审批。
2. 审批通过后，按 `AGENTS.md`：停止后端 18080 和前端 18181 开发服务，进入 `backend`，仅使用 PowerShell 7 执行黄金基线命令。
3. 只有明确获准时才使用 `-Dgolden.update=true` 重建快照；不得手工编辑快照，不得用更新模式掩盖非预期差异。
4. 重点审阅 `/api/bi/coding` 受影响快照：字段从 `commentRatePoints` 变为 `commentRateTrend`，每个周期一个平均值，`ruleVersion` 为 `bi-coding-v5`；其余编码字段、密度轨和不相关端点不得出现无解释变化。
5. 去掉更新参数重新运行黄金测试，确认全绿；重启开发服务并检查后端 `/actuator/health` 为 UP、前端 18181 可访问。
6. 若快照出现与注释率字段、周期平均、规则版本之外的差异，立即停止，不得更新基线；先定位实现回归或工作树串改。

## 决策记录

| 编号 | 事项 | 已审批结论 | 否决方案 |
| --- | --- | --- | --- |
| C1 | 周期聚合位置 | BI 后端按请求 day/week 聚合 | 前端聚合，因会绕过页面数据契约 |
| C2 | 注释率聚合公式 | 合法走查记录注释率的非加权算术平均，忽略 NULL，保留 2 位 | 最后一条、加权总量比、用 `added_lines` 加权 |
| C3 | 注释率去重键 | 稳定走查记录 ID，沿用 CD-16 | 按 MR 去重；这是 CD-39 密度专用规则 |
| C4 | 缺失周期 | 返回缺失并在前端显示 `null`/断线 | 补零、前向填充、插值、连接断线 |
| C5 | 输出模型 | `commentRateTrend(period, averageCommentRate)`，删除旧逐记录 DTO | 保留旧字段并改变其语义、增加 v2/兼容双轨 |

## 风险与假设

- 当前接口只有 `day-all` 黄金目录用例，week 需要至少由领域单测和接口定向测试覆盖；若实现 AI 要新增 `week-all` 黄金用例，必须把目录、快照和人工审阅作为同一变更提交，不能只增加目录不生成快照。
- 现有工作树已包含 `bi-coding-v4` 密度修复；实现 AI 必须在其基础上完成 `v5`，不能误把本计划与密度修复回滚或混合重写。
- 旧平台 `avg(comment_rate)` 是参考证据，不代表可以修改旧平台接口；若真实业务负责人否决本方案平均口径，必须在代码实现前重新冻结 CD-38A，不得由实现 AI自行改成其它算法。
- 仍然可能存在真实无合法注释率观测的时间桶；修复后出现少量真实断点是允许且必须保留的结果。
- 本方案未授权任何数据库写入、内网数据回填或生产发布。

## 实现交付清单

实现 AI 返回审批时必须附上：

1. 修改文件清单和 `git diff --stat`，说明哪些是本工作单元、哪些是既有改动。
2. CD-38A/数据契约/决策/进度文档的具体差异。
3. day/week、同日多记录、NULL/冲突、非加权测试结果。
4. 一个真实样例的旧值与新值对照，例如 2025-11-17 的旧 `0.82` 与新平均 `13.21`。
5. 普通测试、构建、Lint、产物门禁和 `git diff --check` 结果。
6. 黄金基线更新前先暂停等待审批；更新后附快照 diff 和重跑结果。

---

## 初次实现交付记录（2026-09-16，评审前）

> 本节保留初次实现的过程证据；其中的文件数量、测试计数和“类型检查/构建阻塞”状态已被下方“评审补正交付记录”取代，当前验收以补正记录为准。

### 1. 修改文件清单

本工作单元改动（7 个文件）：

| 文件 | 改动 |
| --- | --- |
| `backend/.../bi/domain/model/BiCodingPageData.java` | 组件 `commentRatePoints` → `commentRateTrend`；`CommentRatePoint` → `CommentRateTrendPoint(LocalDate period, BigDecimal averageCommentRate)` |
| `backend/.../bi/domain/BiCodingCalculator.java` | `commentRatePoints(...)` → `commentRateTrend(values, granularity)`（`TreeMap` 分桶 + `CommentRateAccumulator` 先累加后一次性 `HALF_UP`）；冲突走查 ID 整组剔除；`coverageSection` 增加 `observationLabel`；`traces()` 加入 `CD-38A` 并改公式说明；`RULE_VERSION` → `bi-coding-v5`；`DistinctResult` 泛型化并携带 `conflictingIdentities` |
| `backend/.../bi/domain/BiCodingCalculatorTest.java` | 新增 7 项周期聚合行为测试；`source(...)`/`review(...)` 增加重载；更新 3 处旧 DTO 断言与 2 处覆盖率文案断言 |
| `frontend/.../bi-dashboard/data/types.ts` | `commentRatePoints` → `commentRateTrend: Array<{ period; averageCommentRate }>` |
| `frontend/.../bi-dashboard/stages/CodingStageContent.vue` | `qualityTrend` 改为只做周期轴并集与查找，删除同日 `Map` 折叠 |
| `frontend/.../bi-dashboard/stages/CodingStageContent.test.ts` | fixture 改周期点、`ruleVersion` 升 v5；新增周粒度不二次聚合用例 |
| `frontend/.../bi-dashboard/data/chart-explanations.ts` | `codingQualityTrend` 文案“当日无合法观测” → “当前选择粒度的时间桶无合法注释率观测” |

文档改动：核对表 CD-38A、`data-contracts.md`、`decisions.md` D-03、`progress.md`、本计划。

**初次交付时的既有改动（非本工作单元，未触碰）**：`BiCodingCalculator`/`BiCodingPageData`/`CodingStageContent.vue` 中的 `bi-coding-v4` 密度覆盖率修复与静态扫描下线、`BiDownloadAuthorizationService`、`BiPlatformCodingSourceAdapter`、`BiCodingSource`、`entity/statistics/StatisticCellData.java`、`service/statistics/*`、`chart-explanations.ts` 的断轴词条、`SystemTestStageContent.*`、`adaptive-value-axis.*`、`components.d.ts` 等。评审补正涉及其中部分同文件 hunk，按下方清单区分；未回滚、未覆盖其它在途改动。

### 2. 验证结果

- 实现交付阶段后端定向 `BiCodingCalculatorTest`：30 项全绿；本次审批修复可选 `commentRateSource` 参与冲突判定和质量趋势覆盖率文案后，复跑为 **31 项全绿**。
- 后端默认套件：1358 项、**0 错误**；仅 `SqlPushdownRealChainTest` 4 项失败，全部发生在 `setUp → loginHeaders`（登录 HTTP 非 2xx），本地身份服务不接受 `admin/admin-test-2026`，与 BI 计算无关。另：本机 `qaflex-test-postgres-15433` 原本未启动，会额外产生 76 个 PostgreSQL `ConnectException`；启动容器后即归零。
- 前端：BI 定向 **82 项全绿**；全量 **133 文件 / 527 项全绿**；ESLint 0 错。
- `tsc --noEmit`：仅 3 处报错，均在他人未提交的 `SystemTestStageContent.test.ts`（`'FAILED'` 不在 `BiDataStatus` 联合类型内；HEAD 无此串）。
- `vite build`：沙箱拦截 dist 批量删除与 `src/components.d.ts` 写入，未能跑完（已成功转换 2743 模块后中断）；被误写的生成文件已 `git checkout --` 还原。
- `git diff --check`：无空白错误（仅 CRLF 提示）。
- 本次审批后前端 `CodingStageContent.test.ts`：**3 项全绿**；`npm run lint`：通过。
- 本次审批后黄金基线更新模式：**191 项全绿**；更新后不带 `golden.update=true` 的严格比对：**191 项全绿**。
- 开发环境恢复：后端 `http://127.0.0.1:18080/actuator/health` 返回 `{"status":"UP"}`；前端 `http://127.0.0.1:18181/` 返回 HTTP 200。

### 3. 真实样例旧/新对照（冻结快照 `snapshots/bi/get___coding__day-all.json`，`bi-coding-v4` 旧产出）

| 项 | 旧（逐记录 + 前端同日取最后一条） | 新（周期非加权算术平均） |
| --- | --- | --- |
| `2025-11-17` | `0.82` | **`13.21`**（13 条，min 0 / max 100） |
| 日粒度周期数 | 2425 条记录 / 169 天 | **169 个周期**（⊆ 密度 176 周期） |
| 周粒度周期数 | — | **35 个周期**，`2025-11-17` 周平均 `9.39` |
| 只有密度无注释率的周期 | 7 天 | **仍为同样 7 天**（2025-10-20、10-22、10-25、10-28、10-29、11-03、11-04） |
| `commentRateCoverage` | 2425/2634 = 92.07% | **不变**（记录级） |
| 区块文案 | `有效观测 2425/2634` | `有效走查记录 2425/2634` |
| trace 公式 | `注释率直接使用上游值` | `注释率=周期内合法注释率非加权算术平均，忽略 NULL，保留 2 位小数` |
| `ruleVersion` | `bi-coding-v4` | `bi-coding-v5` |

多记录日样例（旧 → 新）：`2026-03-18` 53 条 `33.33 → 19.09`；`2026-02-27` 45 条 `63.64 → 20.90`；`2026-02-25` 49 条 `0.00 → 16.77`。

### 4. 真实接口验收（2026-09-16）

本地身份服务不接受测试口令 `admin/admin-test-2026`（LDAP 绑定返回 `Invalid credentials (49)`），因此改用**独立临时实例**验收，不改仓库文件、不影响 18080/18181：
`PLATFORM_AUTH_PROVIDER=local` + `PLATFORM_AUTH_ADMIN_USERNAME/PASSWORD` + `PLATFORM_AUTH_CSRF_ENABLED=false` + `SERVER_PORT=18090`，其余环境同 `.env.local`，同一真实库。

`GET /api/bi/coding?productVersionId=11（CC2026R3）&source=all`：

| 检查项 | day | week |
| --- | --- | --- |
| `ruleVersion` | `bi-coding-v5` | `bi-coding-v5` |
| `commentRateTrend` 周期数 | **169**（原 2425 条记录） | **35** |
| `reviewDensityTrend` 周期数 | 176 | 35 |
| 周期严格升序且唯一 | ✅ | ✅ |
| 注释率周期 ⊆ 密度周期 | ✅ | ✅ |
| 只有密度无注释率的周期 | **7**（真实断点保留） | 0 |
| 全部为周一 | — | ✅ |
| `commentRateCoverage` | 2634 / 2425 / 92.07%（记录级，未变） | 同 |
| 区块文案 | `有效走查记录 2425/2634` | 同 |
| trace | 含 `CD-38A` 与“周期内合法注释率非加权算术平均” | 同 |

**手算交叉验证**：SQL 导出同范围记录（`code_review_match_mode_records`，`MERGED` + `target_branch='dev'` + `merged_at_source` 非空 + `coalesce(legacy_merged_time_source, merged_at_source) > 2024-04-01`）共 14014 行 → 本地复刻 `BiProductVersionMatcher` 与 `eligibleReview`（11 个无效走查人值）→ CC2026R3 命中 2872 → 有效走查人 2634 → 合法注释率 **2425**（与接口覆盖率完全一致）；按日/周手算 `HALF_UP` 平均后与接口**逐周期比对：周期键完全一致，数值 0 处不一致**。

**旧/新对照（真实数据，非冻结夹具）**：169 个周期中 **144 个**旧值 ≠ 新值；153/169 天存在多条记录（单日最多 56 条）。

| 日期 | 记录数 | 旧（同日最后一条） | 新（非加权算术平均） | 接口 |
| --- | --- | --- | --- | --- |
| 2025-11-17 | 13 | 0.82 | **13.21** | 13.21 |
| 2026-02-25 | 49 | 0.00 | 16.77 | 16.77 |
| 2026-02-27 | 45 | 63.64 | 20.90 | 20.90 |
| 2026-03-05 | 50 | 50.00 | 27.46 | 27.46 |
| 2026-03-18 | 53 | 33.33 | 19.09 | 19.09 |

验收后已关闭临时实例，并以新代码重启正式开发后端（18080 `/actuator/health` = UP）和前端（18181 HTTP 200）。本次审批随后按发布门禁停止开发服务，完成黄金基线更新与严格比对，再按门禁要求恢复两个服务。

### 5. 审批收口与黄金基线记录

1. **黄金基线更新与比对已完成（2026-09-16）**。按门禁停止 18080/18181 后，以 `-Dgolden.update=true` 更新模式运行 `GoldenBaselineChainTest`，191 项全绿；人工审阅编码快照确认 `bi-coding-v5`、`commentRateTrend`、同日平均值、CD-38A 追溯和分别披露的质量趋势覆盖率。随后去掉更新参数复跑，191 项全绿。
   - 受影响编码快照中的 2025-11-17 值为 13.21（旧工作树产出为 0.82），快照不再包含 `commentRatePoints`。
   - 当前工作树在本工作单元开始前已经包含其它 BI 密度/静态扫描/统计看板变更及大量快照差异；本次未使用重置或批量恢复，未将这些既有改动归入本工作单元。
   - 前置条件已核实：Docker 运行中；`GoldenBaselineSupport.EXPORT_READ_TIMEOUT`（6 分钟）修复在位，链路未因 Excel 导出 120 秒契约超时。
2. **week 黄金用例**：当前 `endpoint-catalog.yml` 仍只有 `day-all`；周粒度由领域单测和此前真实接口验收覆盖，本次没有擅自增加一个新的黄金目录用例。

## 评审补正交付记录（2026-09-16）

评审报告指出的五项问题已逐项闭环，未修改数据库结构、冻结夹具或无关在途改动：

1. **前端构建阻塞**：`SystemTestStageContent.test.ts` 的三个目标夹具改为合法 `BiDataStatus`，补齐 `totalCount/fixedCount`，用 `READY + achieved=false` 表达未达标，不再伪造不存在的 `FAILED` 数据状态。
2. **密度冲突记录**：`BiCodingCalculator` 在密度趋势计算前按稳定走查 ID 排除冲突事实，保留记录级覆盖率 `total=1/valid=0`；新增正序/逆序输入回归，2/500 行和 3/500 行不会再分别产生 4.00/6.00 的顺序依赖结果。
3. **自适应值轴量程**：`adaptive-value-axis.ts` 对最近 nice 步长取整后，使用 `max(interval * splitNumber, niceAxisMax(minimumMax, splitNumber))` 保证最低量程；均匀 `[0,3,6,9,12]`、`minimumMax=12.1` 不再得到 `max=12` 的错误断轴。
4. **断轴悬浮交互**：断轴 `breakArea` 设置 `expandOnClick=false`，保持断带只作视觉提示；新增真实 SVG ECharts/ZRender 命中测试，断带中的极值点仍能触发散点悬浮详情，而不是被遮罩拦截。
5. **密度完整性能力**：`BiCodingSource` 增加独立 `reviewDensityDataAvailable` 能力标志；来源适配器只按日期、被走查行数和缺陷数判断密度完整性，不再把缺失走查工时传递为密度不完整。新增“工时为空但密度与注释率合法”的回归，质量趋势保持 `READY`。

为保证生产构建不在 Windows 上改写已提交的组件声明，`frontend/vite.config.ts` 保留开发模式的 `components.d.ts` 自动维护，生产 `build` 关闭该写回步骤；类型检查仍显式执行并已通过。这是构建确定性修复，不改变运行时页面契约。

### 补正验证证据

- 后端：`BiCodingCalculatorTest` + `BiPlatformSourceAdapterTest` **43/43**；新增冲突密度、缺工时能力和来源能力标志覆盖。
- 前端：全量 Vitest **134 个文件 / 530 项**；评审相关轴、断轴交互、SystemTest 夹具和图表契约均包含在内。
- `npm run typecheck`、`npm run lint`、`npm run build`：均通过；Impeccable detector 返回空结果 `[]`。
- 后端默认 `mvn test`：**1361 项、0 failures、76 errors、1 skipped**；76 个错误均因本机未启动 `localhost:15433` 测试 PostgreSQL，属于环境条件，编码相关定向测试和黄金链路未受影响。
- 黄金基线：更新模式和随后严格模式各 **191/191** 通过；第一次更新模式曾遇到编码快照的瞬时 Windows 文件占用，解除占用后重新运行成功，未修改比对规则或掩码。
- 门禁恢复：后端 `http://127.0.0.1:18080/actuator/health` 为 `{"status":"UP"}`，前端 `http://127.0.0.1:18181/` 为 HTTP 200。
