## 进度与中间物

- **经理整体审查与返修（2026-09-28）**：审查确认 S08—S10 与数据-10 S1/S2 已落地——`fixed_label_time` 只取资源标签 add 事件、`resource_label_events` 已归 ISSUE 业务依赖、八图含 `daily-defect-trend`、下载已统一为 `PRODUCT_VERSION`/`CUSTOMER_ISSUE` 显式范围、CI-45/46 已登记为待终审。审查中发现并亲自修正一处实现缺陷：BI 趋势日轴起点原硬编码 `2026-01-01`，现由 `BiCustomerIssuePageService` 把 `CustomerIssueFactQueryService.CUSTOMER_ISSUE_START_DATE` 传入计算器，`CustomerIssueScopeRules` 的两处重复常量改为引用同一事实源，并新增“轴起点跟随传入范围下限”“缺失起点直接拒绝”两项测试。返修后后端 BI 定向 10 项、本任务前端定向 81 项全绿。**整体验收仍未通过**：全量回归的失败项全部来自工作树中其他工作单元的进行中改动，归属见次条。
- **失败归属（2026-09-28 实证）**：① 前端 `customer-issue-illegal-records.mount-smoke.test.ts` 经受控实验确证由平台评审整改单元的进行中改动引入——把 `IssueIllegalRecordsPage.vue`、`useRouteTableState.ts`、`useDataScope.ts` 三者还原到 `HEAD` 后该测试 2/2 通过，恢复工作树版本后 `git diff` 统计与实验前一致；该失败源于新 `loader/commit` 契约尚未在页面侧收口，与本任务文件无关。② `DatabaseBrowserView.test.ts` 两项失败所在实现与测试均属数据库浏览器单元改动。③ 后端 `BackupRunStateRepositoryTest` 两项错误为备份单元进行中改动与 `ck_backup_state_execution_owner` 约束不符。④ `DropdownOptionFieldServiceConcurrencyIntegrationTest` 一项失败为下拉选项单元的并发时序断言。⑤ `api-client/request.ts` 移除“首绘让渡”属平台评审整改计划 R06 的已记录改动（该计划第 920、1452 条），不是本工作单元内容。以上均不得记为本任务回归，也不由本任务代为修复。
- **验证载体与环境（2026-09-28）**：后续验证改在**工作树**执行；前轮全量原在 `.tmp/customer-issue-batch-validation-20260924/backend` 副本内运行，该副本与工作树存在 5 个备份模块文件差异。18181/18080 已由用户恢复（`/actuator/health=UP`、前端 HTTP 200）；隔离 18090/18190 当前未运行，隔离前提与后台开关需重验后其浏览器证据才可采信。趋势的 INCOMPLETE 语义已核实由 `daily-defect-trend` 分区状态与 `BiChartPanel` 的 `stateTitle`/`statusMessage` 共同表达；本轮同时修正该分区文案与实现不一致（原文案称“只呈现已知日期贡献”，而实现是受影响序列整条留空），并补测试锁定。**最终代码状态后端全量：1543 项、0 失败、0 错误、1 跳过（BUILD SUCCESS，日志 `.tmp-logs/backend-full-20260928c.log`）。** 本轮全量首跑曾出现 121 项连接错误，根因是本机测试库容器 `qaflex-test-postgres-15433` 处于 `Exited (255)`（`docker start` 后即消失），不是代码回归；员工上轮报告的备份 2 错误与下拉并发 1 失败在测试库恢复后复跑均未复现。**最终代码状态前端全量：146 文件、144 通过，仅 2 个文件失败（`DatabaseBrowserView` 两项、客户非法记录挂载冒烟一项），两者都经同一受控还原实验确证由各自工作单元的进行中改动引入（`DatabaseBrowserView.vue`/`.test.ts` 还原到 HEAD 后该文件 3/3 通过；`IssueIllegalRecordsPage.vue`/`useRouteTableState.ts`/`useDataScope.ts` 还原后挂载冒烟 2/2 通过）。** 至此本任务文件在全量中全部通过。另修正客户 BI 页分区归属错误（“模块需求分布”原渲染在“缺陷指标”分区内，致“需求指标”分区下无任何图表），移入需求分区后复跑：视图测试 5/5、前端全量仍 146 文件/144 通过（失败项不变）、`typecheck`/`lint`/`build` 通过。
- **历史状态（2026-09-24 员工整批交付）**：本轮实现数据-10 S1/S2、独立 BI“客户问题”页和统一下载；客户 BI 保持独立页面，不进入六阶段，也不成为第七研发阶段。全量前后端回归各有失败；前端 typecheck/lint/build 与当前图表导出定向测试通过。浏览器覆盖统计页关键筛选/下钻、客户 BI 八图及可用阶段的真实下载；旧六阶段只部分验收。S11 未完成。
- **当前工作树与恢复**：基点记录为 `main@c818e720`，工作树含客户统计、数据库浏览器、评审、迁移、黄金快照等多类已有改动；只改本工作单元必要文件，不覆盖或统归其余变化。恢复后首条命令 `git status --short` 已完成；继续以代码、测试和最新隔离运行结果核对，不引用历史运行状态作为本轮实时状态。
- **目标与顺序**：① 收敛数据-10 S1/S2 精确修复事件来源、来源完整性、事件自动派生与现有 ISSUE/MR 消费者；② S08 同一冻结事实内完成客户范围及十项缺陷/四项需求指标；③ S09 完成六类缺陷分析、模块需求分布及日增/日修复趋势，共八张图；④ S10 统一 `PRODUCT_VERSION`/`CUSTOMER_ISSUE` 下载范围并迁移全部第一方调用者；⑤ S11 完成全量测试、护栏、隔离浏览器和真实下载验收，并更新现有权威文档。各图口径及 CI 映射见核对表和本计划 §6/§9。
- **前轮完成且需保留**：S01/S03—S07 的现有业务规则、窄事实、版本资格、客户统计板、公共下钻、菜单/权限、成员选择、筛选后行展开及路由生命周期实现均保留；五项前轮收口的代码与证据只代表其记录的版本，不替代本轮整体回归。历史证据见 `docs/progress.md` 与本计划 §9.4。
- **业务状态**：R1—R4 仅按方案记录值实施，尚无独立人工裁定留痕；不得在本文、CI 状态或进度文档写成“用户签批/冻结”。新增趋势按本次指令的实施建议完成并在最终审查单列复核。目标容量、完整来源上线准备、黄金门禁和发布仍属外部验收/另行授权。
- **本轮验证状态**：前端全量 146 文件/680 项有 3 项失败（677 通过；2 项 `DatabaseBrowserView.test.ts`、1 项客户非法记录挂载冒烟）；最后一行测试常量清理后图表/导出定向测试 34/34 通过，typecheck、lint、build 通过。后端全量 1524 项含 1 失败、2 错误、1 跳过；失败在下拉并发时序断言，两个错误在备份状态仓储夹具受新约束拒绝。8项仓库检查及`git diff --check`退出0。日志位于 `.tmp/customer-issue-batch-validation-20260924/`。历史1486/596/628项结果仅属于各自轮次。原始需求工作簿只读核对两张表，未修改。
- **操作边界**：允许改本任务代码/测试/必要迁移及登记，并在重新确认隔离目标与全部后台开关后使用独立服务和副本库。禁止黄金全链/快照更新、业务库迁移或真实来源重建、真实同步/写回/备份、停止或重启 18080/18181、销毁 `qaflex_uat`、提交/推送/打包/发布。

| 阶段 | 当前状态 | 证据/未完成项 |
| --- | --- | --- |
| S00 | 规则记录完成；R1—R4未获独立人工裁定 | 仅按记录值实施，不代表人工签批 |
| S01 | 旧规则保护与本轮缺陷测试已存在 | 全量测试未全绿，见本轮回归结果 |
| S02 | 部分完成：需求身份派生及数据-10 S1/S2代码/自动链已实施；阶段未关单 | 自动链定向测试通过；目标来源历史完整度及必要 ISSUE 重建未做 |
| S03 | 共享纯规则与窄事实读取已实施 | 最终后端全量仍有非本单元测试失败/错误 |
| S04 | 来源资格、版本及一致性事务已实施 | 目标环境来源完整性未验收 |
| S05 | 客户统计板及筛后行展开已实施 | 隔离浏览器验证筛客户甲、客户×模块筛选及筛后总计 |
| S06 | 公共下钻和路由任务失效处理已实施 | 浏览器打开数量、分子、分母、周期样本；全套回归未全绿 |
| S07 | 页面、权限、候选和类型化参数已接线 | 浏览器覆盖可见同名选项、分组保留条件和旧候选禁用；仍非完整业务验收 |
| S08 | BI 客户范围与整页计算已实施 | 客户范围页使用同一来源版本/业务日；目标数据重建待外部验收 |
| S09 | 独立“客户问题”页及八张图已实施 | 浏览器八图可见；空/不完整/失败态及六阶段兼容只部分验证 |
| S10 | 公共 PNG/Excel 范围统一已实施 | 客户图实际下载已覆盖；六阶段 CAT 数据缺失的页面无法完成真实下载验收 |
| S11 | 未完成 | 前后端全量有失败；护栏通过；R1—R4待裁定，来源重建/容量/黄金门禁待外部条件；全局后台调度属性未能确认 |

# 客户维度统计与 BI 客户问题展示实现方案

## 1. 恢复线索

- 调查代码基点：`main@c818e720`；本轮开始时工作树包含大量已跟踪和未跟踪改动，数据库浏览器、评审、统计板及文档变更并存。只读 `git status --short` 已核对；不能把整棵工作树归为本计划成果或整体还原。
- 恢复首条命令：`git status --short`。随后读取 `AGENTS.md`、[平台进度](../progress.md)、[BI 文档入口](../bi-dashboard/README.md)、[BI 进度](../bi-dashboard/progress.md)、本计划及本轮涉及的公式/数据-10 文档。
- 原始输入：用户附件《客户问题模块新页面需求.xlsx》，工作表“客户问题统计”“BI看板”；截图为客户项目议题 #2245，可见“需求”和“类别：建议”两个标签。
- 关联活动计划：[数据-10：客户问题修复时间筛选](data-10-customer-issue-fixed-time-20260920.md)。本功能依赖其必要的数据来源治理，不依赖其整个记录页 UI 实施。
- 当前阶段：2026-09-24 已实施 S02 必要来源治理及 S08—S10；当前为 S11 最终验收。恢复后先执行 `git status --short` 并阅读本计划与进度文档。S00 的 R1—R4 独立人工裁定仍待办；R1—R4 记录值按经理指令实现，不等于人工签批。历史冻结证据仍链接于本计划历史进度与 `.tmp/freeze*` 日志。

## 2. 目标与边界

### 2.1 成功标准

1. 用户进入“客户问题统计”，在现有默认里程碑内查看全部客户，可按客户、模块、功能查询，并查看附件要求的全部指标。
2. 表内所有指标可排序、可解释、可下钻；下钻议题身份集合与单元格计算集合一致。
3. BI 独立“客户问题”页 `/bi-dashboard/customer-issues` 有十项缺陷指标、六类缺陷分析、四项需求指标、模块需求分布图及缺陷日增/日修复趋势，共八张图，默认全部客户；客户页不进入六阶段产品版本范围，也不成为研发阶段。
4. 相同来源版本、里程碑和筛选条件下，新旧客户页面中具有相同业务定义的指标一致；不同定义的指标显式说明，不“统一”成另一套公式。
5. 缺陷、建议类、需求身份保持独立定义；不能因术语相似而替换、丢弃或重复累加。
6. 已有客户页面、BI 六阶段、同步、标签写回、权限及导出不发生未解释回归。

### 2.2 不做

- 本轮不实施数据-10记录页的修复时间筛选、23列Excel等 UI；本轮允许的隔离库迁移和夹具不等于业务库变更。禁止真实来源同步/重建、GitLab写回、黄金基线全链或快照更新、提交/推送/打包/发布。
- 不建设独立需求系统、客户主数据系统、历史事件日报或冻结历史周报。
- 不改变现有六阶段产品版本目录，不把客户里程碑当成系统测试阶段，不增加第二套兼容模式。
- 不把需求区塞入 BI 的“需求评审”页；两者数据身份和用途不同。
- 不修改现有建议类排除、修复率判据或延期业务公式以追求表面守恒。
- 不新增未经要求的质量目标线、需求完成率、预测、告警或回写功能。
- 客户统计页整体 Excel 不在原附件的明确要求内，本方案不列为必需交付；BI 保留既有单图 PNG/Excel 下载体验，不增加整页导出。

## 3. 约束、已确认口径与证据

### 3.1 已确认与待审必须分开

| 项目 | 结论 | 状态 |
| --- | --- | --- | --- |
| 里程碑 | 项目325的 MILESTONE 目录第一条启用范围；默认该范围内全部客户，不是全部里程碑 | 用户已确认 |
| 时间范围 | 保持客户问题现行2026-01-01创建起点；缺失创建时间沿现有范围函数保留，但不能进入“今日新增” | 沿用现有实现 |
| 多客户 | 每个关联客户各计一次；全部客户总计按完整议题身份去重，不能累加客户行 | 用户已确认 |
| 已修复 | 整体/严重级别与优先级继续使用各自现行判据 | 用户已确认 |
| 延期 | 沿用客户问题模块，不直接搬用 BI 系统测试口径 | 用户已确认 |
| 今日解决 | 当前仍精确包含“已修复/完成”状态成员，最近完成加标时间属于今日 | 用户已确认 |
| 总需求数 | 指定“需求”或建议标签任意一个命中，按议题去重；双标签只计一次 | 用户已确认 OR 逻辑 |
| 标签字面值 | 用户文字为“类型：建议”，截图为“类别：建议” | 方案记录值“类别：建议”，审批依据见3.2与10.1 |
| 新页布局、三种行维度、未知分组、需求解决状态细化 | 下文给出完整建议 | 方案记录值，审批依据见10.1 |

### 3.2 需求身份的唯一解释

用户已明确的是**当前议题的标签成员关系**，不是文本里出现“需求”两个字。按照用户文字登记：

```text
isRequirement = labels精确包含“需求” OR labels精确包含“类别：建议”
requirementTotal = 当前范围内 isRequirement 的完整议题身份去重数
```

- `labels` 指当前有效原始标签集合，不是标题、描述、评论、`reason_category` 或 `category` 的模糊包含判断。
- “需求如此”“新增需求问题”“需求变更未同步”等值均不能因为包含“需求”而自动命中。
- 同时具有两个目标标签时，需求区只计一条；标签删除后按当前事实退出对应集合，不保留历史身份。
- **R1 方案记录值**：唯一建议标签锁定为截图原文“**类别：建议**”；需求标签为“需求”。不接受“类型：建议”，不增加别名或容错分支。#2245 双标签样本不足以验证建议前缀，验收必须用“仅类别：建议、没有需求标签”的样本。该项的人工裁定留痕状态见 §10.1。

### 3.3 核心实现证据

下表 Java 路径前缀为 `backend/src/main/java/com/data/collection/platform/`。

| 已核实事实 | 证据 |
| --- | --- |
| 客户公共范围只凭项目325及2026起点；缺失创建时间仍保留 | `service/CustomerIssueScopeRules.java:7`、`:17` |
| 客户为多对多，不能拆展示字符串代替成员关系 | `backend/src/main/resources/db/migration/V20260722_01__customer_issue_customer_and_response_template_schema.sql:15`；[平台决策 D-03](../decisions.md#d-03-cc_product-客户成员与响应模板事实) |
| 原始标签、类别、原因分别保存，彼此不等价 | `service/IssueFactSourceRowMapper.java:99`；`service/IssueLabelRules.java:86` |
| 严重级别按一级、二级、三级优先于 SUGGESTION 命中；需求可能被归为建议类，也可能已有普通级别 | `service/IssueLabelRules.java:13`、`:66` |
| 建议类识别包含 severity/category/exclusion_reason 三口径 | `service/statistics/SuggestionMetricSupport.java:27` |
| 整体已修复与优先级已修复判据不同 | `service/statistics/CustomerIssueDefectSummaryBoardService.java:1076`、`:1080` |
| 申请延期数按状态，而旧延期占比按 delay_issue | 同文件`:895`、`:896`、`:924` |
| 响应/解决延期仅计现有有效、无接口异常、开放且有P1/P2/P3桶的议题 | `service/statistics/CustomerIssueDelayIssuesBoardService.java:366`、`:598` |
| 效率样本、小时/天精度、无样本零值有现行契约 | `service/statistics/CustomerIssueResponseEfficiencyBoardService.java:737`、`:774`、`:853` |
| 修复时间已有事件与关联时间回退路径，事件直接派生仍有缺口 | [数据-10方案第4—5节](data-10-customer-issue-fixed-time-20260920.md#4-证据与根因) |
| 统计通用入口已有主表、details、规则说明和状态接口 | `controller/StatisticBoardController.java:52`、`:60`、`:76`、`:158` |
| BI 现有请求及下载依赖 productVersionId，不能直接塞客户里程碑 | `bi/api/BiDashboardController.java:46`、`:100`、`:108`；`bi/application/BiDownloadAuthorizationService.java:45` |
| BI 已有堆积柱、环形、原因和延期图形类 | `frontend/src/features/bi-dashboard/stages/SystemTestStageContent.vue:3`—`:38` |

BI 计算权威是[字段级核对表的客户问题章节](../bi-dashboard/BI看板数据来源与计算口径核对表.md#客户问题新增需求已确认部分与评审边界)，新编号 CI-* 不覆盖系统测试 ST-*。平台现行规则仍归[平台页面规则第5章](../platform-page-business-rules.md#5-客户问题公共规则)。

## 4. 数据集合与指标规则

### 4.1 先限定范围，再按指标选择集合

在一次请求中冻结来源、里程碑成员、客户/模块/功能等过滤条件，得到基础集合 B：未删除、项目325、现行创建范围、命中选定里程碑精确成员。里程碑按稳定业务键解析，保留当前不区分大小写但不擅自折叠真实空白的匹配语义。

| 集合 | 定义与用途 |
| --- | --- |
| D：常规缺陷 | B 中沿用客户缺陷汇总 `isRegularMetricIssue` 的议题；用于表格常规缺陷及 BI 缺陷区主要指标 |
| S：现有建议类 | B 中沿用 `isSuggestionColumnIssue` 的议题；仅用于附件 Z 列，不以新需求身份替换 |
| N：客户需求 | 建议以 B 中未被客户公共业务排除的议题为基础，再按3.2标签 OR 筛选；**不应用建议类排除**。公共排除是否完全沿用列入R4一并评审 |
| L：响应/解决延期 | 沿现有客户延期页范围，包括建议类排除、GitLab接口异常排除、开放状态及合法优先级桶 |
| E：效率样本候选 | 沿现有客户响应效率页范围；包含建议类，不排除已关闭“设计如此”，只按现有规则排除关闭的申请否决/需求如此 |

重要边界：

- **不假设 D 与 N 互斥。** 现行归一化中，带“需求”且具有二级缺陷、没有建议类别的议题可能仍进入 D；本轮没有授权改旧缺陷范围。分别计算两区，不展示“总问题数＋总需求数”作为总量。若产品希望互斥，应另行明确改变缺陷定义，不能隐藏修改。
- S 与 N 不等价：`SUGGESTION` 可能由“需求如此”等归一化产生；N 只由最终锁定的两个精确标签产生。Z列保持原意义，不更名为总需求数。
- E 与 D/L 不同属现行行为，不先过滤成D后再计算效率。表头说明必须披露各指标样本范围。
- 同一身份重复且相关字段一致时去重；相同身份的关键字段冲突不得任选首条或合并字段，依赖该字段的分区返回数据不完整。

### 4.2 修复状态、申请延期与分母

设 F 为整体修复谓词、P 为优先级修复谓词、A 为申请延期状态谓词：

```text
F = bug_status 包含“已修复”或“待合并”或“未更新”（保持现行汇总语义）
P = bug_status 包含“已修复/完成”或“未复现”，或GitLab已关闭（保持现行优先级语义）
A = bug_status 包含“申请延期”（保持现行客户汇总语义）
```

- 整体及一级/二级/三级：已修复为各自集合中F命中数量；未修复为总数减F命中数量。
- P1/P2/P3：已修复为各自优先级集合中P命中数量；未修复为该集合总数减P命中数量。
- 所有修复率按本集合分子/分母计算，平台表格百分量纲与两位小数显示沿用当前规则；零分母显示 `/`，排序键为 null，而非0。
- 申请延期是独立状态统计，不是与已修复、未修复互斥的第三桶；不得要求“总数＝已修复＋未修复＋申请延期”。
- `未修复`不等于GitLab未关闭；不能借用旧 `open_count`。也不能把全部指标换成 `is_fixed`。
- 一级回退、挂机、其他沿 `is_regression/is_crash/is_level1_other`；不新增三者互斥的校验。优先级空值不归P3，级别空值不归三级。

**延期占比 R2（名称和公式裁定）**：附件 G 列叫“申请延期缺陷占比”，但现行汇总“延期缺陷占比”是 `count(D且delay_issue)/count(D)`，而 E 列申请延期数为 `count(D且A)`。推荐遵守“沿现有模块一致”，保留 `delay_issue` 分子并将表头校正为“延期缺陷占比”；若要保留附件名称并使用 E/B，则属于不同公式，必须在本次评审明确批准后再改。不得默认把两者等同。

### 4.3 响应/解决延期和效率

- 延期响应 P1/P2/P3：L 中 `is_response_delayed=true` 且命中对应优先级的去重议题数；总计为命中任一合法优先级桶的去重数。
- 解决延期对应 `is_resolve_delayed=true`，其他规则相同。两种延期可同时发生，两个总计不能相加作为唯一延期议题数。
- 缺失优先级仍按旧延期页不进入P桶及总计，不擅自补入总计或新增“其它优先级”列。常规缺陷总数可能包含它们，需说明范围差异。
- 响应周期：E 中创建时间与首次调研模板时间均存在；每条 `Duration.toHours()` 截断为完整小时，再对全部有效样本求平均并 `Math.round`。
- 解决周期：E 中当前状态成员满足“已修复/完成”且创建/最近完成时间存在；每条毫秒差除以86400000、HALF_UP保留6位，再求平均HALF_UP保留1位。
- 无有效周期样本继续显示0，但必须同时显示“有效样本0”；不是凭空新增一条0时长样本。下钻可打开空样本说明。
- 跨客户总计从去重原始有效样本计算，不平均客户均值；不替换为SLA截止时间、计划解决时间、更新时间或关闭时间。
- 如验收发现来源时间倒置，先报告数据异常，不通过绝对值或钳零改变现行公式；是否改变负值样本规则须另行裁定。

### 4.4 需求四指标及模块图

需求身份已确定，以下状态建议以“完整沿用现有客户汇总”落实为**同一F/A谓词**，列入R4供本次评审，不额外创造需求工作流：

| 附件字段 | 建议公式 |
| --- | --- |
| 总需求数 | N 中完整议题身份去重数 |
| 已解决 | N 中F命中的去重数；页面说明“沿现有客户汇总完成判据，含待合并/未更新” |
| 未解决 | 总需求数减已解决 |
| 申请延期 | N 中A命中的去重数；不直接使用 `delay_issue` 或响应/解决延期标记 |
| 各模块需求分布 | 模块内已解决＋未解决堆积，柱顶显示模块总需求数；申请延期可在tooltip单列，不作为第三堆积段 |

未明确确认过需求区的专用解决状态，不能把“已解决”默认为closed，也不能未经说明改为只认精确完成。R4推荐上述继承方案；若用户选择精确完成，只改变N的状态规则与对应验收，不改变D或既有页面。

### 4.5 今日指标

- 今日新增：D 内创建时间落在请求固定业务日 `[dayStart,nextDayStart)` 的去重议题数；创建时间未知不命中。
- 今日解决：D 内当前状态**精确成员**含“已修复/完成”且 `fixed_label_time` 落在同一业务日的去重数。
- 历史完成后撤标不计；撤标后再完成按最近一次时间，只计一次；普通编辑或新评论不计。
- 今日解决不是整体已修复数的日增量，后者包含待合并/未更新；对过去日期回看也不是不变的历史日报。
- 一次请求固定 `asOf` 和业务日，不在逐图/逐行计算时读取不同的now；Clock可控测试。浏览器不自行决定今日边界。
- 沿平台日历语义并实证来源timestamp、事实值和展示时间；当前Jackson给LocalDateTime附加偏移不等于已完成时区换算。跨午夜真值未核实前，不局部加8小时或承诺北京时间结果准确。

## 5. 客户问题统计页设计

### 5.1 导航、范围和查询

建议新增“客户问题 → 客户问题统计”，页面路由 `/customer-issues/customer-statistics`，看板键 `customer-issue-customer-statistics`，旧页面全部保留。

顶部：里程碑独立选择器、客户可搜索选择器（默认全部）、模块、功能；其他现有客户筛选按真实能力接入严重级别、优先级、状态、类别、提交人与指派人。客户/模块/功能选择按对应成员精确筛选，不对当前分页前端过滤。

展示维度采用三个明确选项：

- 按客户：附件原始51列。
- 客户×模块：保留客户列，增加模块维度列，后续50个指标不变。
- 客户×功能：保留客户列，增加功能维度列，后续50个指标不变。

筛选条件先限定事实集合，再展开行维度；例如筛选客户甲后，不再展示同一议题关联的客户乙行。多模块类似。无模块/功能/客户建议保留显式未标注组，使用有类型的缺失成员，不能与真实名称“未标注客户”碰撞。

无显式里程碑用目录默认项；目录没有启用范围时明确提示维护目录，不静默切换全部里程碑。非法或失效的深链范围明确报错。URL、刷新、后退、下钻始终保持同一组筛选。

### 5.2 与 Excel 的逐列对应

Excel虽有空白格式扩展至BI列，实际有业务标题的区域为 **A:AY共51列，即1个客户维度＋50个指标**。不得按工作表max_column误增空白指标。

| Excel列 | 分组 | 列顺序与数量 |
| --- | --- | --- |
| A | 行维度 | 客户名称（1） |
| B:G | 整体 | 缺陷数、已修复、未修复、申请延期、整体修复率、申请延期缺陷占比（6；最后一项见R2） |
| H:O | 一级缺陷 | 回退、挂机、其他、缺陷总数、已修复、未修复、申请延期、缺陷修复率（8） |
| P:T | 二级缺陷 | 缺陷数、已修复、未修复、申请延期、缺陷修复率（5） |
| U:Y | 三级缺陷 | 同二级（5） |
| Z | 建议类 | 现有建议类数量（1） |
| AA:AE | P1 | 缺陷数、已修复、未修复、申请延期、缺陷修复率（5） |
| AF:AJ | P2 | 同P1（5） |
| AK:AO | P3 | 同P1（5） |
| AP:AS | 延期响应缺陷数 | P1、P2、P3、总计（4） |
| AT:AW | 解决延期缺陷数 | P1、P2、P3、总计（4） |
| AX:AY | 效率 | 响应周期（小时）、解决周期（天）（2） |

不向该表擅自追加“总需求数”等BI专属指标；附件的两张表各按自身范围实现。

### 5.3 表格和全部指标下钻

- 采用平台现有统计板外观，多级表头、冻结左侧维度列、表内横向滚动；不以页面横向溢出展示51列。总计行钉底，所有指标和维度均可排序。
- 按真实数值排序；比率空值始终置底、0%正常参与；周期遵循其有效样本和数值契约。并列值按稳定行身份次排序。
- 数量单元格下钻实际贡献议题；0也可打开明确的空集合说明。
- 修复率下钻提供“已修复分子/全部分母”；延期占比提供所选R2分子/分母；零分母展示不可计算原因。
- 周期下钻展示有效样本数、创建时间、响应/完成时间、单条周期，支持核算最终均值，不将无效样本伪装为0贡献。
- 全部下钻继承里程碑、客户、模块、功能、筛选组、行维度、指标谓词，支持分页、真实数值/时间排序及GitLab议题跳转。
- 不直接跳现有CC_PRODUCT记录页冒充统计下钻：其全里程碑默认和排除规则不同。可复用无业务口径的表格/抽屉，但查询必须使用本单元格的过滤规格。
- 一次主表计算与下钻若来源发生变化，不承诺旧数匹配新明细：下钻携带来源版本，变更时提示刷新，不默默展示另一版集合。

## 6. BI 客户问题页设计

### 6.1 入口和视觉

BI 独立“客户问题”页 `/bi-dashboard/customer-issues`；已有需求至系统测试六阶段不改统计范围。这里是客户问题业务页，不命名为第七个研发阶段。

采用现有 Operate 工作台视觉：白底、弱边框、紧凑指标带、现有BI配色、图表卡片和问号说明。客户页顶部使用自己的里程碑与客户选择器，不能沿六阶段的productVersionId进行名称猜测或回退。六阶段间仍保留原版本继承，跨入客户页不带入系统测试版本。

布局建议：缺陷十指标分“数量/修复率/今日”紧凑排列；下方缺陷分析；再显示需求四指标和一张全宽模块需求图。首屏先回答数量和待处理量，不堆十张装饰大卡。物理1920×1080显示器、最大化浏览器原生内容区验收，保留现有底部安全空间。

### 6.2 十项缺陷指标

| 序号 | 附件字段 | 规则引用 |
| --- | --- | --- |
| 1 | 总问题数 | D去重总数；标题保留附件原文，说明“沿现有非建议类缺陷范围” |
| 2 | 已修复 | D中的F |
| 3 | 未修复 | 1减2 |
| 4 | 申请延期 | D中的A |
| 5 | 整体缺陷修复率 | 2/1 |
| 6 | 一级缺陷修复率 | D中一级的F数量/一级总数 |
| 7 | P1修复率 | D中P1的P数量/P1总数 |
| 8 | P2修复率 | D中P2的P数量/P2总数 |
| 9 | 今日新增问题数 | 4.5创建日期规则 |
| 10 | 今日解决问题数 | 4.5当前精确完成＋最近完成事件规则 |

数量、比率、今日不共用一个含糊“完成”字段。BI百分数使用既有0—100量纲，Excel不能再次乘100；不添加系统测试的达标目标线。

### 6.3 六类缺陷分析、需求模块图与日趋势（共八图）

| 图表 | CI口径 | 图形与统计 | 约束 |
| --- | --- | --- | --- |
| 模块缺陷数 | CI-30—CI-32 | 已修复＋未修复堆积柱；总数为柱顶标签 | 禁止总数＋未修复堆积；同一模块内每议题一次 |
| 缺陷严重级别分布 | CI-33 | 一级/二级/三级环形，缺失级别明确展示未标注 | 未标注不能落三级；构成计数应覆盖D |
| 模块与缺陷严重级别 | CI-34 | 模块×严重级别堆积柱，含未标注级别 | 多模块分别计数，不把模块总和冒充全局总数 |
| 缺陷原因分布 | CI-35—CI-36 | 沿客户原因模板词典的分类条形/大类下钻子类 | 不从标题、普通标签猜因；“需求问题”原因不进入需求身份 |
| 申请延期分析 | CI-37—CI-38 | 延期原因×严重级别矩阵，复用图形与交互 | 按记录值使用D且A；原因取客户 `delay_cause/delay_reason`；R3仍待人工裁定，不直接照搬ST-61 |
| 按指派人统计缺陷数 | CI-39—CI-41 | 按已修复＋未修复显示总量 | `assignee_name`为空显式“未指派”，不替换为fix_user/handler_name |
| 模块需求分布 | CI-42—CI-44 | 需求已解决＋未解决堆积柱，柱顶需求数 | 独立使用N，不从D过滤，也不叠加申请延期段 |
| 缺陷日增与日修复 | CI-45—CI-46 | 全宽双折线，共用“缺陷数／个”轴，完整自然日轴从2026-01-01延伸到固定业务日 | 当前事实回看日期分布；日增用D和`created_at_source`，日修复用D、当前精确完成成员和`fixed_label_time`；不代表历史事件流水，撤销/重加可改变历史日期 |

- 缺陷原因推荐在D上分析并沿客户模板分类；它与既有客户原因页“可包含建议类”的统计范围不完全相同，应标明“当前缺陷范围内原因”，不能承诺与未加相同条件的旧原因页总数恒等。
- 原因与延期原因可多选，同一议题在同一类别只计一次；类别数相加允许大于唯一议题总数，缺失保留未归类，不用多选原因饼图假装互斥构成。
- 按指派人本期采用现有事实字段的快照维度；字段内如有多人组合，不另行拆成新的个人身份体系。空值必须可见，当前负责人不等于历史实际修复人。
- 高基数图支持排序和10/20项可视窗口及图内滚动；搜索/窗口只是展示，不改变顶部全范围指标。PNG/Excel包含当前查询范围的完整图表数据，不能截为当前窗口。
- BI未被要求新增全部原始议题自由浏览；查看原始集合使用客户统计页的受权下钻，不为了图形复用改造平台旧看板。

### 6.4 状态、刷新和下载

- 固定分区建议：`source-consistency`、`defect-overview`、`today-activity`、`module-defects`、`severity-distribution`、`module-severity`、`cause-distribution`、`delay-analysis`、`assignee-workload`、`requirement-overview`、`module-requirements`；所有响应路径均返回状态，不靠缺少字段猜空态。
- 来源完整且当前范围没有记录才是EMPTY；缺少修复事件只影响今日解决等依赖分区；需求分类未完成回填时需求区INCOMPLETE，不能显示全零。
- 已打开页面保持其版本，来源更新或业务日切换后提示刷新；用户刷新整页切换。新旧筛选请求竞态以现有请求取消/序号能力解决。
- 每张图的问号与Excel口径说明共源，包含范围、单位、去重、未标注及多归属说明。权限沿 `bi.dashboard.view`、`bi.dashboard.download` 双门，不能用隐藏按钮代替后端授权。
- 下载必须验证客户范围、图表模板、查询条件、来源版本和业务日；旧版本拒绝并提示刷新。PNG由BI图形导出，Excel由后端现有OOXML服务生成。

## 7. 数据、架构与接口契约（已实施边界；业务裁定单列）

### 7.1 复用事实及必要派生字段

| 业务语义 | 当前真实来源 | 处理 |
| --- | --- | --- |
| 完整议题身份 | `issue_fact.source_system/source_instance/project_id/issue_id` | 去重、成员连接、下钻；IID只显示和跳转 |
| 客户 | `issue_fact_customer_members.customer_name`与四列身份 | 规范客户成员为权威；不拆 `customer_names` 展示串 |
| 里程碑 | `issue_scope_groups.business_key`、`issue_scope_members.source_value`、`issue_fact.milestone_title` | 只读项目325 MILESTONE，冻结成员后匹配 |
| 模块/功能 | `issue_fact.module_names/function_name` | 复用现行规范化成员，不从页面重新解析标题 |
| 级别/优先级/状态 | `severity_level/priority_level/bug_status/issue_state/closed_at_source` | 采用4.2各自谓词 |
| 原因/延期 | `reason_category/delay_issue/delay_cause/delay_reason/is_response_delayed/is_resolve_delayed` | 不混淆申请、事实延期和SLA超时 |
| 今日和效率 | `created_at_source/research_template_time/fixed_label_time` | 无同义时间列，修复来源先治理 |
| 需求身份输入 | ODS有效标签关系中的 `ods_gitlab_labels.title`，构建时现有 `label_titles` 数组 | 精确成员匹配，`label_names`用于展示/诊断，不做模糊标签过滤 |
| 需求身份列 | `issue_fact.is_customer_requirement` | 已由迁移、事实构建与自动增量链实现；基于原始有效标签精确派生，NULL表示尚未重建或未知，不填默认false |

需求布尔事实现已进入物理实现。派生函数限客户项目；当前实现只消费记录值“需求”或“类别：建议”，没有别名和运行时双轨。R1/R4仍待独立人工裁定。迁移阶段允许NULL表示尚未重建，不能用全量默认false把历史未知伪装成非需求；目标数据重建仍属于上线准备，不在本轮执行。非客户议题写false，不改变其业务分类。

同步更新实体、持久化、全量/定向构建、字段静态契约、查询映射与测试。移除标签或改变标签名称/关系时必须沿原血缘派生更新布尔值；不能只靠部署时回填维持。无需新建客户事实表或需求表，不新增抓取任务。

### 7.2 分组、去重与性能

- 客户筛选使用完整身份关联的EXISTS；总计在未按客户/模块展开的去重集合计算。分组层才展开客户及模块，客户×模块按同一议题的成员组合归属，每个组合一次。
- 分组键采用结构化维度，不以未转义的客户名和模块名拼字符串；空值组独立标识。SQL入参绑定，排序/指标/分组字段白名单，不执行前端传入SQL或任意谓词。
- 聚合在后端完成；不请求每客户一遍全量事实，不在浏览器加载原始议题后统计，不把缓存作为首次正确性的依赖。
- 本方案选用集合式SQL限定来源/里程碑/成员，再一次读取所需事实与成员，复用纯规则进行后端聚合；主表复用现有聚合行排序分页，details在服务端对同规格集合切片。这样不复制一套包含模板/样本规则的SQL统计引擎，也不每列全表查询；选择须通过S11真实固定负载验收，不承诺未测容量。
- 若实测证明必须下推聚合或分页，应在同一查询边界直接替换对应实现并完成规则等价测试，不保留SQL/内存两套新旧引擎。先复用成员索引；新索引须由实际EXPLAIN及目标内网负载决定，不提前创建全字段宽索引。性能验收分别记录主表、下钻、BI整页、完整下载及对既有页面的影响；本地小样本不外推内网容量。

### 7.3 后端与前端边界

- 客户统计新增 `CustomerIssueCustomerStatisticsBoardService`，继承现有 `AbstractStatisticBoardService`，由 `StatisticBoardRegistry(List<AbstractStatisticBoardService>)` 自动登记；复用现有Controller、筛选、解释、快照、刷新、权限和details。**不新建Provider接口、Registry或第二套统计API**，也不把旧汇总Service扩成全客户功能总类。
- 数量/分子分母/周期样本使用本表的固定指标描述，主表和下钻消费同一集合选择；不建设可编辑公式引擎。现有 `StatisticCellData.drilldown` 收敛为最终可点击能力，不再由前端数值猜测，旧板生产方显式保持原有效行为，详见S06。
- BI新增客户应用服务、范围端口、只读Adapter、计算器和页面数据，复用 `BiPageResponse`，在现有RuntimeFactory延迟装配。BI不调用平台Controller/页面Service/DTO，也不通过HTTP请求本平台。
- 本轮按整批实施指令完成有限共享规则抽取与窄事实读取：旧调用方统一改用权威实现并删除替代方法体；未复制整套页面Service、未新增第二套统计/同步链。R1—R4仍待人工裁定，但不影响本轮按记录值实现。
- 前端客户统计继续走 `StatisticBoardPage` 与 `StatisticBoardView`；BI新增客户页，但将确有两个消费者的状态/布局部分从 `BiDashboardView` 原位抽出共用，产品版本解析留在六阶段壳。图表、下载和请求客户端都只有一套；详细边界见S09—S10。

### 7.4 客户统计接口

沿用现有路径，增加新boardKey实例，不建立另一套统计API：

| 请求 | 本工作单元契约 |
| --- | --- |
| `GET /api/statistic-boards/customer-issue-customer-statistics` | 51列定义、分组行、去重总计、来源元信息；客户×模块/功能追加对应维度列 |
| `GET /api/statistic-boards/customer-issue-customer-statistics/details` | 单元格集合、贡献样本、分页和排序 |
| `GET /api/statistic-boards/customer-issue-customer-statistics/rule-explanation` | 指标范围、公式、样本和规则版本 |
| `GET /api/statistic-boards/customer-issue-customer-statistics/status` | 复用ISSUE工作区状态；刷新不得另起客户专属抓取链 |

新增参数建议：`groupBy=CUSTOMER|CUSTOMER_MODULE|CUSTOMER_FUNCTION`；客户精确成员及缺失组选择；details的 `population=COUNTED|NUMERATOR|DENOMINATOR|SAMPLE`、结构化行维度和主表来源版本。现有里程碑字段保留 `milestoneTitle` 业务键语义，不新增显示名别名。全部参数必须进入规范化请求、快照键和下钻规格，不能只在前端保存。

来源变化时拒绝旧版本下钻；没有跨请求历史快照存储时，不伪造旧版还可读取的承诺。

### 7.5 BI客户接口与下载

客户页使用一个整页读取入口；PNG授权和Excel继续使用现有公共下载入口，不为客户页新增下载Controller或复制授权服务。**六阶段页面GET与产品版本含义不变，公共下载请求统一为显式范围判别契约**，全部第一方调用者已同步迁移，不保留旧请求解析回退。

| 请求 | 实际职责 |
| --- | --- |
| `GET /api/bi/customer-issues` | 可选`milestoneBusinessKey`及客户/模块/功能`kind+value`条件；单一一致快照返回范围、完整候选、来源版本、业务日、十项缺陷指标、四项需求指标和八张图数据。非法或不完整选择返回400，不静默扩大为ALL |
| `POST /api/bi/download/authorize`、`POST /api/bi/download/excel` | 继续使用原公共下载链；scope严格区分`PRODUCT_VERSION`和`CUSTOMER_ISSUE`，客户范围携带里程碑、业务日及完整类型化筛选 |
| `POST /api/bi/download/authorize`（现有） | 同一授权流程验证显式范围类型、页面、图表实例/模板、来源版本；客户范围另核验业务日 |
| `POST /api/bi/download/excel`（现有） | 同一授权门后调用唯一OOXML生成器；范围说明不再写死“产品版本” |

下载范围采用封闭的两种类型，而不是可空ID拼凑：

```text
scope = { kind: PRODUCT_VERSION, productVersionId }
     或 { kind: CUSTOMER_ISSUE, milestoneTitle, customer, module, function }
context = { scope, pageKey, chartKey, chartTemplateId, sourceVersion,
            businessDate（仅客户范围必需） }
```

`customer/module/function`沿查询的有类型成员选择，区分全部、缺失与实际值；目录显示名不充当身份。`chartKey`是页面内稳定图表实例键，区别于多个图共用的 `chartTemplateId`。后端以白名单校验“页面—实例—模板—范围类型”，产品版本和客户里程碑各在自己的范围解析器内解析，随后进入同一授权与输出流程。

建议返回内容：

```text
pageKey = customer-issues
scope = { milestoneTitle, milestoneDisplayName, customer, module, function }
meta = { sourceVersion, ruleVersion, generatedAt, businessDate, snapshotId }
sections = 固定分区及各自状态、原因、覆盖情况
summary = 十项缺陷指标
requirements = { total, resolved, unresolved, appliedDelay }
charts = 六类缺陷分析、模块需求分布及缺陷日趋势，共八张图
```

这里是目标结构示意，不是已存在DTO。计数字段采用long；不可计算比率nullable，附分子/分母，0与null分开。`snapshotId`仅在确有固定快照时输出真实身份，不能拿请求时间伪造。

`BiDownloadAuthorizationService`保留为唯一授权服务，原产品版本解析直接改为按显式范围分派；`BiExcelExportService`只接收已解析的范围元信息与图表表格，不参与业务查询。PNG继续调用图类的 `exportSize/build(mode=export)`，Excel继续调用 `excelTable()`；不另写图表数据提取switch或文件生成器。现有Excel引擎序列化浏览器提交的表格，来源版本校验不等于对上传每个单元格作真实性认证，本方案不虚称服务端已重算数据；正常客户端必须锁定同一不可变页面数据与下载上下文，防止授权等待期间切换筛选造成串版。

### 7.6 来源一致性与发布状态

- 同一次主表/BI请求内，使用真实只读REPEATABLE READ事务或现有等效固定发布快照，保证多次查询看见同一数据库视图；事务必须从代理/显式边界生效，不依赖同类自调用注解。
- 同时校验来源已发布、依赖完整和当前版本；只读事务只能防请求内混读，**不能把D-10分批全量重建变成整库原子发布**。目标来源重建进行中、失败或尚未结算时，不将部分已提交数据标为READY。
- 使用现有事实发布状态与范围generation，纳入客户成员、里程碑定义、需求分类、业务规则和动态延期事实的变化；要实证延期定向更新能够使本页旧结果失效，不能仅假定issue事实版本自然涵盖全部写路径。
- 如复用现有快照，快照标识包含规范化查询与业务日；当事实版本不变但跨日时，“今日”指标仍失效。来源版本与查询标识分开，查询哈希不能冒充来源版本。
- 页面读取失败不返回0或旧数据冒充新来源成功。可保留已经展示的旧页面并明显提示刷新失败，但不得混入局部新结果。

## 8. 修复时间前置、迁移与发布

1. 数据-10 S1/S2 必要来源治理已按整批实施指令完成：精确完成成员、真实完成加标事件max时间、删除label_links时间回退、补事件直接派生与来源完整性检查。目标环境事件历史完整度和正式ISSUE重建仍待外部核验/授权。
2. 同一事件新增/撤销/重加、before/after归属变化、标签改名及删除对账必须正确驱动原/新Issue根；低ID历史修改按现有补偿路径，不新增定时全量刷新补丁。
3. `is_customer_requirement` 派生列、迁移和自动增量构建已实施；迁移NULL仍表示尚未重建/未知，不用默认false伪造历史分类。R1/R4独立裁定及目标范围来源回填仍待处理。
4. 部署前核对事件历史及原始标签是否完整；缺什么补什么，不默认执行GitLab全库同步，也不触碰源标签和客户别名资料。
5. 本方案包含需求新事实与修复时间来源收敛，发布清单需明确ISSUE事实重建；与数据-10协调为同一来源治理版本、一次必要重建，不做两套修复时间列、两次重复迁移或重复全量流程。
6. 重建走既有后台任务，完成来源、事实和投影终态并验证未知分类清零后才验收新页。历史回退时间纠正对响应效率等旧消费者的影响必须逐项展示和批准，不能称“无行为变化”。
7. 新看板/BI规则版本独立登记；旧页面仅在确有共享事实或结构变化时升版，不全库清缓存。
8. 保持单应用、既有端口及现有权限机制；只做新增页面所需登记，不扩大角色授权。更新包、回滚和生产操作另按明确指派执行，本轮不产出或应用发布包。

## 9. 实施顺序、文件范围与验收

### 9.1 复用清单与真实缺口

以下清单是实施前调查时的复用边界与缺口记录，不是当前实现清单。代码已按本工作单元落地；当前状态以顶部阶段矩阵和本节末验证表为准。Java路径相对 `backend/src/main/java/com/data/collection/platform/`，前端路径相对 `frontend/src/`。

| 能力 | 已有入口及核实结果 | 本次处理，不另造什么 |
| --- | --- | --- |
| 统计板注册与API | `service/statistics/StatisticBoardRegistry.java:13`收集抽象Service；`controller/StatisticBoardController.java:52`按boardKey分派 | 只新增一个板服务，继续使用现有Controller/Registry |
| 统计筛选 | `StatisticFilterGroupSupport`校验字段/操作符；`engine/StatisticFilterEngine`编译内存谓词 | 保留既有AND/OR、标签组和筛选UI，不造筛选DSL |
| 客户精确成员 | `service/IssueCustomerMembershipSqlSupport.java:61`已有四列身份EXISTS，但类为包私有 | 由同包窄事实读取服务调用，BI不复制SQL，不直接调用记录页Service |
| 事实查询 | `IssueFactQueryService.java:36`有绑定查询与慢SQL监控；普通Map入口的里程碑/功能是contains | 复用执行器；精确选择走明确成员谓词，不冒用contains入口 |
| 现有范围助手 | `CustomerIssueSqlScopeSupport.java:19`含公共排除和无里程碑分支 | 不能原封不动作用于全部B/D/S/N/L/E；复用来源常量与目录解析，集合排除在各自规则执行 |
| 候选和分页 | `IssueFactRecordRepository`已有记录页分页/候选，但候选不接里程碑，且部分异常返回空集 | 不把记录页候选或错误策略当成新统计查询；新读模型只补本功能所需事实投影和范围候选 |
| 快照/发布 | `StatisticBoardSnapshotService.readOrRefresh`已有缓存；`StatisticBoardSnapshotRequestFactory.java:26`及来源版本方法默认锁定default来源 | 复用缓存表/版本服务；显式贯通真实来源集合、范围代际和业务日，不另造缓存 |
| 排序与分页 | `useStatisticBoardTableState`对后端聚合行排序分页；`statistic-board-sorting.ts`支持count/ratio/duration及空值置底 | 保留聚合行排序/分页，不加载原始议题到浏览器，不重写表格排序器 |
| 下钻 | `useStatisticBoardDetail.ts:71`与`base/StatisticTableColumnGroup.vue:44`均以数值大于0阻止打开 | 统一为显式能力；复用详情弹窗，补集合选择，而非客户页专用点击补丁 |
| BI装配/信封 | `bi/infrastructure/BiDashboardRuntimeFactory.java:53`延迟装配；`BiPageResponse`不绑定产品版本 | 新客户服务接入现有Runtime和信封，不加第二个运行舱或自动扫描服务 |
| BI图表/下载 | `BiChart`已有build/hasData/exportSize/excelTable；`charts/export-chart.ts`已有离屏PNG；`BiExcelExportService.java:69`已有OOXML | 复用图类和导出器；只演进范围上下文与实例白名单，不复制下载链 |
| 权限与路由 | `PagePermissionKeyResolver`显式board白名单；`feature-manifest`统一菜单/查询白名单；BI下载已有查看＋下载双权限 | 补登记和真实鉴权测试，不另建权限模型，不默认扩大角色授权 |

**执行依赖与当前状态**：S00—S10的实现工作已按本轮指令完成（S00的R1—R4人工裁定除外）；两条页面链只共享事实和纯规则，不互调页面服务。当前独立完成者已结束实现并进入S11整体复核。浏览器实测只覆盖计划列出的路径；全量回归未全绿，CAT数据缺口、来源历史完整度、R1—R4、目标容量与获授权黄金门禁仍是不同的验收条件。

### 9.2 可执行步骤、改动边界与阶段出口

**状态读法**：本节保留原实施顺序、范围和阶段验收标准；步骤已执行与否以顶部S00—S11矩阵及§9.4/T01—T27证据对照为准。未通过的验收项继续列为未完成，不因实现代码存在而视为关闭。

#### S00：区分业务裁定与实施指派（业务裁定仍待办）

1. 本轮整批实施指令已授权按R1—R4方案记录值落地；这只是工程实施授权，不是R1—R4的人工业务裁定或签批。
2. R1—R4人工裁定仍分别待办。若最终裁定改变任一记录值，直接改唯一权威实现、测试和对应CI说明，不保留双轨。
3. S03共享规则/窄事实、S06公共下钻和S10公共下载改造已由整批实施范围明确授权并实施；工作树原有其他变更仍按文件/业务边界分别核对。
4. 验收出口：工程范围与实现指派已完成；业务裁定状态继续作为S00未完成项，不能用代码实现或文档叙述关闭。

#### S01：先建立现有行为的特征测试（前置：S00）

1. 在 `backend/src/test/` 对现有客户汇总、延期、效率、原因服务补相同确定性夹具；同时固定B/D/S/L/E、F/P/A、延期优先级匹配和效率舍入，不能只测试未来新增辅助函数。
2. 先覆盖T01—T16和T23中的可独立规则，记录预期值与议题身份集合。新缺陷先写会失败的行为测试；不更改黄金夹具来凑样本。
3. 在前端邻近测试固定现有零值不可点击、总计钉底、空比率置底、深链恢复与BI六阶段下载参数，供契约迁移前后对照。
4. 验收出口：普通定向测试形成可重现的现状基准；共享纯规则迁移后同一夹具结果不变。来源时间纠正另列预期差异，不混入规则抽取。

#### S02：完成唯一事实来源与自动派生（前置：S01）

1. 修改 `entity/IssueFact.java`、`service/IssueFactSourceRowMapper.java`、`service/IssueFactPersistenceService.java`，新增可空 `is_customer_requirement`；Flyway用新迁移，不改历史迁移、不设历史默认false。从Mapper已有 `label_titles` 精确派生，非客户项目写false；全量与按根ID更新使用同一路径。
2. 与数据-10共用 `GitlabFactSourceSqlProvider`、Mapper、`GitlabFactDependencyCatalog`及既有血缘/根解析修改：精确完成成员、最新真实add事件、删除关联时间回退、before/after根并集、标签改名/删除反查。若数据-10已完成相应步骤，仅验证复用，不再写一版。
3. 同步 `scripts/contracts/fact-field-contract.md`、实体/持久化字段护栏与相关Mapper测试；完整身份与新布尔列必须从写入到查询可读，null不能经Java基本类型或SQL coalesce丢失。
4. 用隔离PostgreSQL夹具走镜像→outbox→事实→投影，覆盖普通新增事件、撤销、重加、改归属、标签删除及既有补偿路径；不只断言SQL文本。
5. 验收出口：T05—T07、T15—T18及数据-10对应链路测试通过。真实来源补齐和必要重建遵循第8节发布授权，不在此步擅自操作生产数据。

#### S03：复用规则，补一个窄事实读模型（前置：S02）

**规则收敛范围**（拟新增纯规则放在 `domain/customerissue/`，通用建议/原因词典放在中立的 `domain/issue/`；均不依赖页面DTO、Spring Bean或数据库）：

| 当前规则位置 | 目标唯一职责与调用迁移 |
| --- | --- |
| `CustomerIssueDefectSummaryBoardService.IssueSource`的F/P/A及客户集合条件 | 拟 `CustomerIssueMetricRules`：显式整体修复、优先级修复、申请延期，不统一三个谓词；旧汇总和新统计调用，BI领域按同一规则计算 |
| `SuggestionMetricSupport`中的纯成员判定 | 移为 `SuggestionMetricRules`，全部真实调用方改import；原文件只保留统计SQL/表头等自身职责，不保留纯方法转发。SQL谓词与纯规则用真库夹具验证等价 |
| `CustomerIssueDelayIssuesBoardService`的开放/API异常/优先级桶判定 | 迁至客户纯规则，保留当前字符串匹配和桶选择，不能借抽取改成另一种优先级判断；旧延期页同步调用 |
| `CustomerIssueResponseEfficiencyBoardService`的样本资格、单条周期、均值舍入 | 拟 `CustomerIssueEfficiencyRules`，输入时间/状态，输出可辨未知的样本与均值；旧页仅替换计算调用，原显示精度与空样本输出不变 |
| `DefectCauseMetricCatalog`及客户原因页的 `LEGACY_CAUSE_TOKEN_OVERRIDES`/匹配方法 | 词典原位迁移到中立规则包；拟 `CustomerIssueCauseRules`只承担客户匹配覆盖，不把客户token改动作用于系统测试；旧原因页和BI共用，不复制词典 |

1. 上表迁移逐项进行：先S01测试、移动唯一实现、修改所有调用方/测试、删除旧方法体，再进入下一项。规则已有成熟实现时直接复用，不再包一层同义函数；不建立反射注册器、策略工厂或可编辑规则引擎。
2. 现有 `CustomerIssueScopeRules`、`IssueStatusMembers`继续作为公共范围/状态成员权威；新需求标签规则落在事实派生，查询和BI不得再判断原始标签。抽取不把统计页行组织、BI状态、图形或权限下沉到规则包。
3. 拟新增 `service/CustomerIssueFactQueryService` 作为两个新页面共同消费的**窄事实读取入口**，以 `IssueFactQueryService.query/count`执行绑定查询并保留慢SQL监控。同包调用既有客户EXISTS与过滤SQL支持；不调用 `IssueFactRecordRepository.findPage` 或整套记录页服务，不复制其大量SELECT和错误回空逻辑。
4. 读模型只承载完整四列身份、当前来源字段、规范客户成员、模块/功能成员、状态/级别/优先级、原因/延期标志、三种时间和可空需求事实。客户成员可用一次批量查询组装，不按议题/客户逐次查询；不读取无关raw_payload或重解析标题。
5. 输入为来源集合、已解析里程碑成员和有类型的客户/模块/功能选择，不含平台页面DTO。BI的Adapter负责把BI范围映射为该输入；统计板的高级筛选仍由既有 `StatisticFilterGroupSupport/StatisticFilterEngine`处理，不能为BI复制筛选语法。
6. `ALL/MISSING/VALUE`显式区分选择类型；成员等值遵循既有大小写规则，`%`、`_`不得在精确选择时变通配符。复用 `IssueCustomerMembershipSqlSupport`，对模块成员比较在现有成员SQL位置修正/补齐等值语义，不改变contains操作符含义；不支持的条件显式拒绝或由已声明的现有内存筛选执行，绝不静默漏条件。
7. B查询只施加公共来源/项目/创建时间/里程碑/用户条件；不要直接套 `CustomerIssueSqlScopeSupport.boardScope`里的全局排除，从而提前丢掉S或E合法成员。候选从同一里程碑完整B生成，与页面分页/图窗口无关；候选失败报错，不返回空列表假装没有客户。
8. 一次加载限定范围，生成不可变读结果；真实只读 `TransactionTemplate`覆盖目录成员、来源资格、版本、事实和客户成员查询。BI为普通Runtime对象，不依赖无代理的 `@Transactional`。所有后续纯计算消费该读结果，不再穿插读库。
9. 验收出口：旧指标特征测试不变；两新入口读到相同完整身份/成员；T01—T04、T08—T14、T24通过。抽取仅限本表列出职责，不能演变为平台全量统计重构。

#### S04：打通来源就绪、版本与缓存（前置：S03）

1. 在既有 `SyncFactPublicationStateService`、`IssueProjectionScopeResolver`、`FactProjectionVersionService`边界核实来源资格、待发布工作、全量重建及投影终态。沿数据-10将“有无工作”与“来源是否完整”分开，不拿 `isReady()`的旧混合含义直接判定可读。
2. S03读结果同时给出实际来源集合、范围代际与来源版本；复用 `StatisticBoardSnapshotService`及 `StatisticBoardSnapshotRequestFactory`，把隐藏的default来源参数改为显式来源输入，更新所有实际调用点。查询只读单源时版本也限定该源；若实际读多源，版本覆盖每个来源，不凭一个default版本替代。
3. 同源完整重建未结算时，不将部分提交数据存成新READY快照；只有缺某个字段来源时按真实依赖标记对应分区。读查询异常直接失败，不能捕获后调用空聚合。
4. 核查 `FactBuildService.refreshCustomerIssueDelayFactsForConfig/updateCustomerIssueDelayFlags`及其发布调用链：当前定向写只更新三种延期列与updated_at。必须新增回归测试证明它推进本范围有效版本；若调用链未覆盖，在该实际写入事务内衔接现有范围generation失效机制，不恢复整行upsert，不新建计时缓存版本。写入0行不得伪造有效变更。
5. 快照键包含规范化筛选、groupBy、成员缺失选择与规则版本；业务日对含“今日”的结果生效。冷读以实际读结果版本建键，不能在事务前取旧版本、事务内取新事实后写入旧键；命中已有快照也要核查当前发布资格。
6. 下钻/下载携带主表的sourceVersion，客户BI另携带businessDate。新请求重新读取当前资格/版本并匹配，不符明确拒绝；本期不增加跨请求历史快照存储，不能声称任意旧版仍可查询。
7. 验收出口：T18—T19及T25通过，覆盖动态延期、目录调整、客户成员变化、分类更新和跨午夜；无新缓存表、线程或定时刷新链。

**实施状态（2026-09-22）**：第1—5、7条已实现并有真库证据（`CustomerIssueDelayRefreshScopeVersionTest`、`CustomerIssueScopeVersionInvalidationTest`、`StatisticBoardSourceQualificationTest`）。第6条的“来源版本随请求显式声明”（快照请求用范围集合取代来源版本字段）与“下钻携带主表来源版本并匹配拒绝”均已落地：主表单元格 `detailParams` 暴露 `sourceVersion`/`businessDate`，明细请求缺少或过期时拒绝，证据在 S06 的 `CustomerIssueCustomerStatisticsDetailContractTest`。T19 的两半（跨自然日由业务日进入快照键、旧版本下钻拒绝）均有测试覆盖。

#### S05：实现客户统计板及50项指标（前置：S04）

1. 新增 `service/statistics/CustomerIssueCustomerStatisticsBoardService`，实现 `boardKey/buildDefinition/doLoadBoard/doLoadDetail`及现有规则说明/状态/刷新扩展点；由原Registry收集，不新造Controller。新Service只编排范围、读取、计算和响应，不容纳第二套词典与SQL执行器。
2. 新增本板固定 `CustomerIssueStatisticMetricCatalog` 与纯 `CustomerIssueStatisticsCalculator`：每个稳定columnKey登记指标类型、基础集合、分子/分母或样本选择、显示精度、说明和允许的population。与5.2的50指标逐一对应，不把表头文案当指标ID，不做动态公式语言。
3. 从一次S03读结果编译一次高级筛选，按D/S/L/E分别求资格；分类与单条周期每议题只计算一次。全局去重桶与客户/客户×模块/客户×功能桶同时累积，不能每个客户或每一列再查数据库。
4. 组键使用结构化值，缺失组带kind而非显示名；后台按稳定维度身份给初始顺序，沿前端稳定排序保留并列次序。总计从未展开去重集合计算，不能累加分组行或平均组均值。
5. 保留 `StatisticRowData(rowKey,rowLabel,cells)`：客户用rowLabel，模块/功能复用现有 `metricType=text` 单元格，不再制造第二套维度表格DTO。rowKey使用规范JSON编码的行选择结构，与现有总计键分离；服务端解码为强类型对象，不能按客户名＋分隔符拆键。
6. 同一指标描述生成数值、tooltip/解释和下钻选择；保留现有数值排序编码（Long排序值与displayValue分离），比率与解决周期按各自固定比例编码，未知比率null，不能先格式化再按字符串排序。
7. 验收出口：51列顺序/分组与附件一致，另外两种行模式只增加一个文本维度；所有数量、比率、样本与对应旧规则一致，T01—T14通过。内存仅处理限定范围事实和聚合行，不以缓存命中掩盖冷读成本。

#### S06：直接演进公共下钻契约（前置：S05）

1. 沿 `StatisticDetailRequest.filters`和已有 `StatisticCellData.detailParams`传递groupBy、来源版本及必要行选择；population必须是固定枚举。控制参数在 `StatisticFilterGroupSupport`中与业务filterGroup分离，不能被当成筛选字段忽略，也不能覆盖主表的强制里程碑/来源范围。
2. 后端解析rowKey，验证其模式与groupBy一致且成员属于当前范围；未知columnKey、非法population、篡改行维度拒绝。总计下钻仍按全局去重集合；周期只返回有效样本并含每条计算所需时间与贡献值。
3. 复用 `AbstractStatisticBoardService.sliceDetailRecords`的筛选/分页与既有详情列，接入当前指标的同一选择器和稳定完整身份次排序；不跳普通记录页。当前方案采用一次限定范围读取后的服务端集合切片，不虚称已有数据库分页；性能门槛见S11。
4. 在 `StatisticDetailResponse`和 `types/api/statistics.ts`补强类型的可选集合列表及当前集合字段，数量提供COUNTED、比率提供NUMERATOR/DENOMINATOR、周期提供SAMPLE。默认选择由指标定义，前端不按列名猜；空集合仍返回解释、列定义、total=0。所有构造调用、序列化与测试同步更新，旧详情显式单集合，不增旧签名补空构造器。
5. `StatisticCellData.drilldown`保持一个最终能力布尔值：新表所有指标即便0/null也可打开；旧板在原生产位置将历史有效条件显式写入该值，保持原点击行为。删除 `useStatisticBoardDetail.canOpenDetailCell`与 `StatisticTableColumnGroup.canOpenDetail`里的 `numericValue > 0`判断，两处只消费同一能力；不能加 `boardKey===新表` 分支。
6. `StatisticBoardDetailDialog`增加集合切换控件；`useStatisticBoardDetail`把选择、分页重置、sourceVersion与行身份贯穿请求/深链恢复。来源已变时保留旧主表并提示整表刷新，不能用新详情覆盖旧单元格语义。
7. 验收出口：T11、T19、T21、T26通过，0数量、0%、零分母与零样本均能解释；旧表点击能力迁移前后逐项一致，所有details分页的身份并集等于对应主表集合。

**实施状态（2026-09-22 第三轮：已实现并验证）**：集合契约、下钻能力单一化、集合切换与来源版本/业务日/行身份贯通均已落地。后端证据 `CustomerIssueCustomerStatisticsDetailContractTest`（13/13：0 数量、零分母、真实 0 小时样本与无样本可辨；旧来源版本与缺来源版本拒绝；分页身份并集等于主表贡献集合；非法集合/篡改行维度/范围外成员/未知指标拒绝）与 `StatisticDrilldownSupportTest`（4/4）；旧板点击行为由五个看板 Golden Master（refactor 安全网）逐字段确认仅 `drilldown`/`detailViewKey` 收窄。实现中发现并修正一处缺陷：行成员校验原先在集合筛选后执行，会把合法空集合误判为“该行不存在”，现按完整范围校验行身份后再筛集合。

#### S07：客户统计前端、导航与权限接线（前置：S06）

1. 在 `feature-manifest/modules.ts/types.ts/route-contracts.ts`增加菜单、PageKey、boardKey和查询白名单；`composables/statistic-board-data-scopes.ts`将新板接入已有项目325 MILESTONE provider。无启用项显示目录状态，不走“所有里程碑”回退。
2. 继续使用 `views/StatisticBoardPage.vue`、`components/StatisticBoardView.vue`、`BaseStatisticTable`、规则抽屉及实时工作区。通过现有配置扩展增加groupBy和精确成员选择；`api-client/statistic-boards-api.ts`仍为唯一请求客户端。
3. `useStatisticBoardRouteController`统一处理groupBy/成员选择的URL写入、重置与details失效；更新route-contracts全部允许键，测试normalizeQuery不剥掉新增参数。切换分组后只清理失效的列偏好，不清空其他页面设置。
4. 多维度固定列在现有列定义中补最小 `fixed`能力，由 `StatisticTableColumnGroup`透传到Element Plus；客户仍走既有rowLabel固定列。固定维度组不能被拖进指标区；不另写多维表格或绕过现有排序/列宽逻辑。涉及列定义构造器须同步全调用方，旧板显式保持原固定方式。
5. `PagePermissionKeyResolver`补板view/export映射，权限常量及数据库登记沿现有权限迁移方式；本轮不新增表级导出产品需求，已有公共导出路径也不得绕过授权。`StatisticBoardDependencyCatalog`验证ISSUE依赖，`StatisticBoardSyncMetadataService`补显式板登记，快照刷新沿既有服务发现。
6. 验收出口：T20—T23、T26通过；按客户/客户×模块/客户×功能、排序、规则、全部类型下钻、刷新和深链均在真实浏览器可用，普通角色不得越权。

**实施状态（历史轮次）**：菜单/路由/查询白名单/里程碑范围/权限映射/页面接线已由`CustomerIssueCustomerStatisticsWiringTest`（3/3）及当轮前端套件验证；当轮浏览器未覆盖筛选行展开、候选旧选项窗口、路由旧任务失效及类型可辨识性。以上历史浏览器证据不用于替代本轮验收；本轮实际覆盖范围见§9.4与T01—T27对照，仍有未验项。

#### S08：BI客户范围与整页计算（前置：S04；不调用S05板服务）

1. 在 `bi.domain.port`拟新增 `BiCustomerIssueScopePort/BiCustomerIssueSourcePort`；范围Adapter只读 `IssueScopeCatalogService`的325 MILESTONE目录，保留稳定业务键/启用顺序。不存在可用默认范围时返回明确状态，不使用 `BiProductVersionMatcher`猜名称。
2. 在 `bi.infrastructure`拟新增 `BiPlatformCustomerIssueScopeAdapter/BiPlatformCustomerIssueSourceAdapter`；映射到S03窄事实读模型和S04来源上下文，不复制 `BiPlatformSystemTestSourceAdapter`的轮次、is_fixed或默认来源逻辑。
3. 在 `bi.application`拟新增 `BiCustomerIssuePageService`，在 `bi.domain`拟新增 `BiCustomerIssueCalculator`和强类型页面/source数据；计算器复用S03纯规则，只承担BI固定指标/分组/分区状态，不引用统计板DTO。计数long、比例BigDecimal/null，显示层不重算。
4. 先实现十项缺陷指标与四项需求指标，再实现6+1图数据及全部固定分区；同一请求只消费一个不可变来源结果和一次业务日。复用 `BiSourceDimension`表达未知来源维度，另以完整议题身份去重；不把显示组键当议题身份。
5. 在 `BiDashboardRuntime`、`BiDashboardRuntimeFactory.create`和 `BiDashboardRuntimeManager`接线；Controller在现有 `/api/bi`下增加第7.5节三个GET，并沿原权限/异常信封处理。无新线程、网络同步或Spring自动扫描的BI内部Service。
6. 候选接口对完整有效里程碑B求客户/模块/功能候选，不受当前图窗口或当前已选客户限制；来源资格失败与确实没有候选区分。页面必须校验显式选择是否合法，过期目录/选项不静默切换。
7. 验收出口：CI各编号与T01—T19一致；固定分区正常/空/不完整/异常路径齐全，六阶段页面DTO与公式不变。

#### S09：复用BI壳与图类，完成客户页面（前置：S08）

1. 新增 `features/bi-dashboard/BiCustomerIssuesView.vue`及客户内容组件；从 `BiDashboardView.vue`只抽出通用加载/错误/刷新/滚动保持与请求序号控制，两个壳同时使用。六阶段的STAGE_BY_ROUTE、productVersionId解析及版本继承留在原壳，不给每个stage添加客户分支。
2. `api-client/bi-dashboard-api.ts`补三个GET；`data/types.ts`区分研发阶段键与BI页面键，客户页不是研发阶段枚举成员。manifest/router添加客户页及真实查询白名单，不让productVersionId流入客户路由。
3. 里程碑、客户、模块、功能控件只产生规范化请求；整页request signature包含全部查询条件。快速切换由同一请求序号机制拒绝迟到结果，局部失败不拼接上一筛选的图。
4. 指标使用 `BiMetricStrip`；模块缺陷/需求使用已有 `StackedCategoryBarChart`，严重级别使用已有环形类，原因/延期/指派人使用现有对应图类。数据适配在客户内容/适配文件完成，不在图类中增加客户业务公式或复制ECharts option；系统测试质量目标线不带入客户页。
5. 复用 `BiChartPanel`、`BiChartSortControl`、图形窗口与完整数据出口。`chart-explanations`增加客户实例词条，问号和Excel说明同源；窗口只影响渲染，不裁剪用于PNG/Excel的完整数据。
6. 验收出口：物理1920×1080最大化浏览器中缺陷/需求两区完整可用，末张图不被任务栏遮挡，无横向页面溢出；T20、T22—T23及竞态/无数据/失败场景通过。

#### S10：统一下载范围，保持一套授权与导出（前置：S08—S09）

1. 修改 `bi/api/BiDownloadAuthorizeRequest`、`BiExcelExportRequest`与 `BiDownloadAuthorizationService.Request/Authorization`为7.5的显式scope契约。`BiDownloadAuthorizationService`仍是唯一授权门，原 `BiCurrentSourceVersionPort`及Adapter按范围类型直接演进，产品范围与客户范围分别解析，不复制第二个授权类。
2. 当前PAGE_TEMPLATES仅校验页面/模板；改为明确页面实例目录，使每个chartKey对应唯一模板及允许的范围类型。登记六阶段所有实际图实例与客户7图；非法页面/实例/模板组合、伪productVersionId、旧来源/跨日客户下载均拒绝。
3. 保留 `/api/bi/download/authorize`、`/download/excel`及查看＋下载 `requireAll=true`。Excel入口先授权，再把服务端解析的“产品版本/客户里程碑及筛选”元信息交给唯一 `BiExcelExportService.export`；该方法改为接收范围元信息而非productVersionName，删除写死标签，不保留旧重载绕行。
4. 前端 `BiChartPanel`、`charts/export-chart.ts`、API客户端统一改为 `BiDownloadContext`；更新 `BiDashboardView`、全部现有stage内容组件、客户内容组件和测试，删除通用图表面板上的productVersionId/productVersionName专用props。业务页持有自己的范围类型，通用图表不猜来源。
5. 用户点击下载时固定context和完整图数据，再等待授权；PNG继续 `exportSize→build(export)→dispose`，Excel继续 `excelTable→公共Excel接口`。禁止截当前可视窗口、双重乘100、把查询哈希当sourceVersion，也不新增文件格式实现。
6. 验收出口：T19、T22—T23、T27通过；六阶段仍下载相同语义的数据/单位/口径，客户文件范围标注正确，两个接口后端鉴权有效。HTTP请求结构变更列为预期契约变化，不能声称所有字节不变。

#### S11：完整验证、容量与文档收口（前置：S02—S10全部完成）

1. 按9.3全部场景核对真实身份集合与数值，不只检查页面有图。普通后端套件、前端Vitest/typecheck/lint/build、API/字段/迁移护栏先行，使用隔离测试库，不改业务库制造夹具。
2. 浏览器实际操作里程碑和精确成员选择、三种分组、51/52列横滚和固定维度、全部列排序、四类population、0/null下钻、URL刷新/后退、刷新失败、BI窗口与完整下载；核查网络参数与控制台。解压xlsx核对范围、表头、说明、数值单位、完整行数，PNG核对全部类别。
3. 固定目标数据量、客户/模块基数、每议题成员数和筛选组合，分别测冷读/缓存命中、SQL时间/次数、后端聚合内存、传输体积、前端聚合行渲染、details及完整下载，并同时观察旧页延迟。不得用本地小样本承诺内网容量。
4. 本方案选择“后端限定范围一次读取/聚合＋现有前端聚合行排序分页”，不是默认数据库分页。若目标负载未通过，先用EXPLAIN/剖析定位，在同一窄查询实现内完成集合式聚合或分页替换并复验；不加超时重试、额外缓存、前端截断或新旧双引擎掩盖。容量未验收就不得宣布完成，性能预算由目标内网实测与验收方确认。
5. 对共享调用迁移执行删除检查：被迁移的纯规则旧方法体、客户下载专用路线、旧公共下载props/请求解析、前端数值推断下钻、未登记query键均不得残留；无 `v2/new`兼容服务、同义参数、重复词典或全新同步器。合法的产品范围分支不是旧接口兼容分支。
6. 按9.4登记端点/案例及共享响应的预期差异；获授权后运行黄金门禁和必要的来源补齐/一次ISSUE重建。发布验证未完成时明确标为待验收，不把普通单测等同发布完成。
7. 实施后的平台能力归 `product.md/platform-page-business-rules.md`，平台来源/共享边界归 `architecture.md/decisions.md`；BI产品、契约、架构和决策只更新BI各自职责文件；CI公式按已批准裁定维护。两域progress只保留状态/证据/链接，活动计划仅在工作单元真正收口后按生命周期处理。

### 9.3 必须覆盖的确定性用例

| 编号 | 场景 | 验收结果 |
| --- | --- | --- |
| T01 | 同议题属客户甲乙，另有甲的议题 | 甲2、乙1、总计2；筛甲只显示甲行，总计2 |
| T02 | 多客户×多模块 | 每组合一次，全局不乘客户数/模块数；筛选限制展示成员 |
| T03 | 缺客户、模块、功能；真实名称恰为“未标注客户” | 缺失组与真实名称分开；无静默丢行 |
| T04 | 两来源相同issue_id/IID，或相同ID冲突 | 不跨来源合并；冲突相关分区不可冒充完整 |
| T05 | 需求标签单独、建议标签单独、双标签、都没有 | 按最终两精确标签得到1/1/1/0；单建议样本验证R1 |
| T06 | 仅需求如此、原因需求遗漏、调研勾选需求、标题含需求 | 没有目标标签时不进入N；不得模糊命中 |
| T07 | 建议类＋二级；需求＋二级但无建议类别 | 前者按旧D排除；后者允许D/N各自命中，不私改旧D |
| T08 | 已修复/待合并/未更新、未复现、已关闭未完成 | F与P按现行差异分别断言；未修复不是未关闭 |
| T09 | 申请延期状态、仅有延期原因、仅SLA超时 | A/delay_issue/两种超时不互替；R2/R3逐个用例锁定 |
| T10 | 延期议题缺优先级、接口异常、已关闭 | 沿旧延期页对应排除；不塞P3，不擅自补总计 |
| T11 | 0缺陷、全未修复、无周期样本、真实0小时样本 | null比率与0%区分；周期样本0与真实0周期可辨 |
| T12 | 两客户样本数悬殊且共享议题 | 总体周期按去重样本算，不平均客户均值 |
| T13 | 一级同时回退/挂机，原因多选 | 保持多归属，不强迫分类之和等于唯一数 |
| T14 | 未指派与已有指派人；修复人不同 | 空归未指派，按assignee，不借fix_user填空 |
| T15 | 今日00:00、下一日00:00、跨月年/闰日 | 半开自然日边界正确，浏览器时区不改变结果 |
| T16 | 完成后撤标、重加、重复事件、只改评论/标题 | 今日解决只按当前精确完成＋最新有效加标，单议题一次 |
| T17 | 只有标签事件改变、标签删除/改名、原归属迁移 | 镜像→outbox→事实→投影自动更新，无手动重建依赖 |
| T18 | 来源建表但未完成同步、部分重建失败、分类仍null | INCOMPLETE/错误有理由，不伪造READY或全零 |
| T19 | 主表后来源变更、导出跨页期间事实更新、跨自然日 | 旧版本下钻/下载明确拒绝或使用真实固定快照，不混读 |
| T20 | 筛选快速切换、返回、刷新、默认目录调整/停用 | 控件、URL、请求和结果一致，旧响应不覆盖新选择 |
| T21 | 客户统计所有列升降序及全部下钻 | 真实数值顺序、总计钉底；数量/分子/分母/样本集合完全对应 |
| T22 | 高基数PNG/Excel、无下载权限/无查看权限 | 完整数据未被窗口截断；越权后端拒绝，百分量纲正确 |
| T23 | 现有客户全部页面、BI六阶段、兼容模式常开 | 原入口/范围/权限/非预期数据输出无回归 |
| T24 | 成员名含百分号、下划线、逗号；AND/OR及标签组；缺失与真实同名 | 精确选择不变通配；SQL与既有内存筛选一致；不支持条件不静默丢失 |
| T25 | 只修改非default纳入来源、客户成员、里程碑；仅定向更新延期三列 | 当前范围版本及旧结果失效覆盖实际写路径；无变化/无关联范围不伪造更新 |
| T26 | 旧零值下钻迁移、新零值/零分母/零样本；新query深链与无启用目录 | 旧点击行为不变，新能力真实可用；参数不被路由剥离；无默认项不放大范围 |
| T27 | 下载途中切换筛选、篡改页面/实例/模板/范围类型、跨日；六阶段全实例 | 使用点击时同一不可变数据；非法组合拒绝；公共下载迁移无漏接，旧请求不走兼容回退 |

#### T01—T27 实际证据对照（2026-09-24）

下表中的后端类级结果来自`.tmp/customer-issue-batch-validation-20260924/backend/.tmp-customer-issue-backend-full-final-20260924.log`；相关类通过不表示后端全套通过（全套有1失败、2错误）。前端全量为`.tmp/customer-issue-batch-validation-20260924/frontend-full-pre-final-cleanup.log`，客户页及相关单测包含其中；隔离页面交互与导出见§9.4。状态“部分”表示列出证据确实覆盖了部分行为，不能外推为整个T项通过。

| 编号 | 实际证据 | 结论 |
| --- | --- | --- |
| T01 | `CustomerIssueCustomerStatisticsDetailScopeTest.unfilteredTotalDetailCoversEveryCustomerInScope/filteredCustomerIsCarriedIntoTotalDrilldown`；隔离浏览器筛客户博诚 | 通过 |
| T02 | `...DetailScopeTest.filteredModuleDoesNotExpandUnselectedModuleRowsAndKeepsTotalAndDrilldownsAligned`；浏览器客户×模块仅显示博诚/工程图和筛后总计 | 通过 |
| T03 | `...DetailScopeTest.realMembersNamedLikeTheMissingSentinelOrWordingStaySelectableAndDistinct`、`CustomerIssueCustomerStatisticsControlOptionsTest.realMembersNamedLikeTheMissingSentinelOrWordingStayOrdinaryCandidates`；前端可见文案操作 | 通过 |
| T04 | `BiCustomerIssueCalculatorTest.deduplicatesCompleteIssueIdentityAcrossCustomersAndModulesAndAlignsTodayWithTrend`；`BiCustomerIssuePageServiceTest.unqualifiedSourceReturnsAnIncompletePageInsteadOfAnEmptyPage` | 部分：跨客户/模块完整身份去重和未知源状态有测试；相同身份冲突输入未在本轮独立证明 |
| T05 | `CustomerIssueMetricRulesTest.customerRequirement_exactLabelOr`、`CustomerIssueRequirementFactPersistenceTest`及`CustomerIssueRequirementLabelAutoChainIntegrationTest.suggestionLabelAndUnrelatedLabelFollowTheSameAutomaticChain` | 按R1记录值通过；R1仍待人工裁定 |
| T06 | `CustomerIssueStatisticsCalculatorTest.requirementIdentity_usesLabelsNotReasonOrTitle`；精确标签持久化测试 | 通过 |
| T07 | 需求标记/旧缺陷规则分别由上述需求测试与`CustomerIssueStatisticsCalculatorTest`覆盖 | 部分：R4下D/N交叉边界未作为独立裁定/全矩阵验收 |
| T08 | `CustomerIssueMetricRulesTest.fixedBySummary_matchesLegacyTokens/priorityFixed_differsFromOverallFixed/appliedDelay_isIndependentState`；统计计算器相应规则用例 | 通过 |
| T09 | `CustomerIssueMetricRulesTest.appliedDelay_isIndependentState`、BI客户计算器及旧延期板测试 | 部分：R2/R3记录值有实现与测试，仍待人工裁定；“仅原因/仅SLA”全组浏览器路径未验 |
| T10 | `CustomerIssueDelayIssuesBoardServiceTest`及其GoldenMaster测试 | 部分：旧服务自动回归通过；附件列出的异常/关闭组合未全部逐项现场验收 |
| T11 | `CustomerIssueCustomerStatisticsDetailContractTest.zeroNumeratorRatioOpensNumeratorCollectionWithExplanation/zeroDenominatorRatioKeepsNullCellAndOpensEmptyCollection/noCycleSampleAndRealZeroHourSampleAreDistinguishable` | 通过 |
| T12 | `CustomerIssueStatisticsCalculatorTest.efficiency_averagesAllValidSamplesAcrossGroups`与样本下钻集合一致性用例 | 自动测试通过；浏览器手核仅覆盖当前副本统计，不覆盖悬殊样本专门夹具 |
| T13 | `CustomerIssueStatisticsCalculatorTest.level1RegressionAndCrash_areMultiMembership`及客户原因图实际Excel | 部分：严重级别多归属有测试；原因多选不相加边界未在浏览器手核 |
| T14 | BI八图渲染/导出和按指派人数据计算代码 | 部分：本轮未独立断言未指派与fix_user冲突样本的图表数值 |
| T15 | `BiCustomerIssueCalculatorTest.deduplicatesCompleteIssueIdentityAcrossCustomersAndModulesAndAlignsTodayWithTrend`覆盖2028-02-29 23:50至2028-03-01 00:10；`naturalDayCountsCrossTheYearBoundaryWithoutChangingTheBusinessDay`覆盖2026-12-31 23:59:59与2027-01-01 00:00；`CustomerIssueStatisticsCalculatorTest.todayMetrics_requireExactCompletionAndHalfOpenBusinessDay` | 日期轴自动测试通过；未做浏览器时区切换 |
| T16 | `CustomerIssueRequirementLabelAutoChainIntegrationTest.fixedLabelEventsPublishAddRemoveAndReaddThroughTheAutomaticChain`；BI计算器精确完成成员/未知时间断言 | 事件增、撤、重加自动链通过；浏览器未演练历史事件修改 |
| T17 | `CustomerIssueRequirementLabelAutoChainIntegrationTest.deletedLabelLinkClearsCustomerRequirementThroughAutomaticChain/renamedLabelRepublishesLinkedIssueRootsThroughAutomaticChain/movedLabelLinkRepublishesBothIssueRootsThroughAutomaticChain` | 标签关系变更自动链通过；低ID补录、物理删源事件及完整历史影响范围未单独实测 |
| T18 | `BiCustomerIssuePageServiceTest.unqualifiedSourceReturnsAnIncompletePageInsteadOfAnEmptyPage`；`StatisticBoardSourceQualificationTest`来源资格用例 | 来源不完整不伪装空值通过；目标环境来源完整性未验收 |
| T19 | `BiDownloadAuthorizationServiceTest.authorizesCustomerPageAgainstFrozenRangeAndCurrentPageVersion/scopeSeparatesProductVersionAndCustomerIssueShapes` | 自动契约通过；浏览器未实测下载等待期间来源/业务日变化 |
| T20 | `StatisticBoardView.route-refresh.test.ts` stale status/detail、关闭明细、范围失效、卸载用例；`useStatisticBoardControlParams.test.ts`双向到达、重取上限、前进后退条件用例 | 自动测试通过；浏览器未完整跑完刷新、前进/后退、非法深链组合 |
| T21 | `CustomerIssueCustomerStatisticsDetailContractTest`集合/总计/版本断言；浏览器四种集合可下钻 | 部分：未在浏览器逐列验证全部升降序 |
| T22 | `BiDownloadAuthorizationServiceTest`八图白名单与范围类型、`BiDashboardSecurityContractTest`权限契约；八图PNG/Excel真实下载 | 部分：完整八图导出已验；高基数目标容量及每种拒权角色真实浏览器拒绝未验 |
| T23 | 前端/后端旧客户页与六阶段自动套件；浏览器实测编码页、系统测试页及客户页 | 部分：CAT无快照阶段无法导出，且全量测试有失败/错误 |
| T24 | `CustomerIssueCustomerStatisticsDetailScopeTest.rejectsInvalidKindsOnBoardAndOnTotalDrilldownForEveryMemberDimension`、`CustomerIssueCustomerStatisticsControlOptionsTest.illegalMemberTypeIsRejectedInsteadOfSilentlyFallingBack`、`CustomerIssueDashboardView.test.ts`非法深链/前进后退/可见同名 | 类型语义和非法类型通过；含特殊字符的所有SQL/AND-OR组合未逐项验收 |
| T25 | `CustomerIssueScopeVersionInvalidationTest`、`CustomerIssueDelayRefreshScopeVersionTest` | 自动范围失效测试通过；目标环境写入范围未做 |
| T26 | `CustomerIssueCustomerStatisticsDetailContractTest`零值/null/空集合、`resolveStatisticBoardRouteScopeState`无启用范围用例 | 自动契约通过；旧板零值浏览器行为及无启用目录全流程未本轮实测 |
| T27 | `BiDownloadAuthorizationServiceTest`页面/实例/模板/scope白名单及八图注册；8图实际PNG/Excel下载 | 部分：正常调用及注册通过；下载途中筛选切换、防篡改组合和六阶段所有实例浏览器路径未验 |

自动测试包括纯规则、来源SQL及Mapper、真实PostgreSQL聚合与事务、API参数/授权/下载、前端路由/交互。缺陷复现须打到真实运行逻辑，不能只检查SQL文本或私有字段。

浏览器实测已在隔离后端/前端18090/18190及`qaflex_uat`完成上表所列路径。数据库实时复核确认GitLab自动同步/系统钩子/补偿/延迟写回、BI CAT、代码评审数据库同步和备份开关均关闭，活动队列为0；隔离启动脚本带有相关业务worker关闭参数，但全局`platform.background-jobs.enabled=false`未能证实。补充关闭全局调度的隔离服务重启命令被执行策略拒绝，未执行；不继续操作隔离浏览器。既有18080/18181未停止或重启。

### 9.4 验证入口、黄金基线和交付纪律

下表记录本轮实际验证。Windows 使用 PowerShell 7；后端 Maven 串行运行。最终代码状态的全量前后端测试均未全绿，因此整体验收不通过；黄金门禁仍未运行，且本轮未获其授权。

| 范围 | 入口与判定 |
| --- | --- |
| 后端定向 | 在 `backend/` 使用 `../tools/maven/apache-maven-3.9.9/bin/mvn.cmd test -Dtest=<本步测试类列表>`；测试覆盖S01现有服务与本步新增真实入口，不只测试辅助函数 |
| 后端普通回归 | `mvn test` 串行，退出1；1524项：1失败、2错误、1跳过。`DropdownOptionFieldServiceConcurrencyIntegrationTest.test_concurrentFirstSavesCreateOnlyTheWinningBinding`失败；`BackupRunStateRepositoryTest`两例因执行所有者约束报错。日志`.tmp/customer-issue-batch-validation-20260924/backend/.tmp-customer-issue-backend-full-final-20260924.log`。客户需求自动链6、BI客户计算5/页面3、客户板3/候选6/明细契约13/明细范围11项在同一日志通过 |
| 前端定向/全量 | `npm test` 退出1，146文件/680项：677通过、3失败；最后一行测试常量清理后复跑相关图表导出2文件/34项全过。`typecheck`、`lint`、`build`退出0。全量日志为`.tmp/customer-issue-batch-validation-20260924/frontend-full-pre-final-cleanup.log`，定向及类型/lint/build见同目录对应`*-final.log` |
| 仓库契约 | `check_api_contract_drift.py`、`check_frontend_api_boundary.py`、`check_fact_field_contract.py`、`check_flyway_migration_immutability.py`（134项）、`check_flyway_destructive_migrations.py`均退出0；对应`.tmp/customer-issue-batch-validation-20260924/guard-*.log` |
| 文本/产物 | `check_worktree_artifacts.py`、`check_runtime_artifact_locations.py`、`check_text_whitespace.py`、`git diff --check`均退出0；对应`guard-artifacts.log`、`guard-runtime-artifacts.log`、`guard-whitespace.log`、`guard-diff-check.log` |
| 浏览器 | 统计页按可见标签筛客户“博诚”/模块“工程图”，客户×模块只显示筛后行和总计；缺失/同名语义、范围候选禁用、切分组保留条件已实测；数量/分子/分母/周期样本打开下钻。客户BI实见八图且八图PNG/Excel均已下载。趋势Excel267自然日；副本库D全身份去重手核250/80、2026-05-11为20/4、业务日点0/0与顶部今日指标一致。原因图Excel表头为“缺陷原因/缺陷数（个）”，旧编码Excel表头保留“名称/人员/模块/代码量（行）”。旧系统测试页实下PNG/Excel；CAT未发布快照的阶段页空/不完整，不能完成六阶段全部导出。UAT无版本推进夹具编辑遗留过`missing`旧缓存行，该缓存不计为干净全量页验收。控制台未检查。截图在浏览器操作时已采集并展示 |
| 环境 | `qaflex_uat`/容器`qaflex-test-postgres-15433`；隔离库Flyway `20260922.01/02`成功；GitLab同步/系统钩子/补偿/写回、BI CAT、代码评审数据库同步、备份开关全关，活动任务均0。全局调度参数未确认；重启隔离后端以关闭全局调度的命令被执行策略拒绝，未执行。18090/18190仍运行；18080 PID 44096、18181 PID 26832未触碰，当前健康UP/HTTP 200 |
| 全链路黄金 | 仅满足下述授权/发布时机后，在backend使用 `../tools/maven/apache-maven-3.9.9/bin/mvn.cmd test -Pgolden-baseline -Dtest=GoldenBaselineChainTest`；快照更新另需批准 |

- 普通Maven/Vitest、类型检查、构建及仓库API/字段契约护栏先行；全链路黄金门禁只在发布打包/内网验收前或用户明确要求时运行，不能为日常方案或开发顺手运行。
- 新REST入口登记目录；通用统计入口还要新增本boardKey案例，不能因为路径已有就漏掉新表的产出验证。
- 预期黄金差异（仅列明，不运行/更新）：新增客户统计board与control-options/下钻响应；新增`GET /api/bi/customer-issues`八图及分区状态；新增客户页权限/工作区登记和两条客户迁移；共享`fixed_label_time`从label事件重新派生，纠正旧回退值并影响响应/解决效率等ISSUE消费者；公共下载scope新增`CUSTOMER_ISSUE`、八个客户图实例登记和范围上下文，六阶段首方请求完成参数迁移；客户统计/BI各自的规则版本。原缺陷统计公式或无关页面输出变化不在范围内。快照未运行、未改动。
- 冻结夹具不足以覆盖两类建议标签时先使用专属集成夹具，不能为了测试方便擅自修改黄金夹具/manifest。
- 快照更新需用户明确批准；先展示预期差异，工具生成并人工审阅，再严格compare；禁止手改快照、改掩码或EXCLUDED掩盖回归。
- 门禁前停本机18080/18181，完成后依规则恢复并验证健康；文档阶段不停止现有服务。
- 仅在获得明确提交/发布指令后执行对应动作；远端未指定不得猜测。最后按文档生命周期将已实施事实归入权威文档，活动计划待工作单元真正收口后处理。

## 10. 决策记录与人工评审项

### 10.1 四个边界（方案记录值；审批依据见下）

**审批依据核对（2026-09-22，本轮实施者补记）**：本节原标“已冻结”与 §3.1、§4.4 的“待审/建议”表述冲突，且本文档内不存在独立的人工裁定留痕。按来源分为两类，实施与测试只消费下表记录值，不保留运行时双轨：

- **有用户直接确认来源**：里程碑与创建范围、多客户分别计而总计去重、已修复沿用现行判据、延期沿用客户问题模块、今日解决按当前精确完成与最近完成事件、总需求数按指定标签 OR（见 §3.1 标注“用户已确认”各行）。
- **仅由 AI 在本文档记录、缺独立人工裁定留痕**：R1 唯一建议标签字面值、R2 列名与分子公式、R3 BI 申请延期图成员范围、R4 需求区解决状态与公共业务排除。
- 结论：R1—R4 的记录值本轮照此实施；若人工裁定与记录值不同，须按裁定同步修改实现、CI 条目与测试，不得保留兼容分支。**在此之前不得把本节表述为已经人工签批。**

| 编号 | 差异或选择 | 方案记录值 | 实现与测试只消费 |
| --- | --- | --- | --- |
| R1 | 文字“类型：建议”与截图“类别：建议”不一致 | **唯一建议标签为“类别：建议”**；需求标签仍为“需求” | `is_customer_requirement = labels精确包含“需求” OR labels精确包含“类别：建议”`；不新增“类型：建议”或其它别名 |
| R2 | 附件“申请延期缺陷占比”与旧延期占比不等价 | **保留旧 `delay_issue` 分子公式**，列名校正为“**延期缺陷占比**” | 分子 `count(D且delay_issue)`、分母 `count(D)`；不用申请延期状态数 A 作分子 |
| R3 | BI申请延期图的成员范围 | 图名保留“申请延期”，成员采用 **D且A**；原因取客户 `delay_cause/delay_reason`，未知原因保留 | 不照搬系统测试 `delay_issue` 成员与 ST-61 分类 |
| R4 | 需求区解决状态与公共业务排除 | 需求已解决用 **F**、申请延期用 **A**；N 在 B 上应用客户公共关闭排除，**不应用建议类排除** | 不把“已解决”改为 closed 或仅精确完成；不把 N 从 D 扣除 |

R1–R4 按上表一次写入本文、BI 核对表 CI 条目与测试；不保留运行时双轨选择。

### 10.2 其余提交本次评审的技术选择

- 本轮已实施：独立客户统计入口、独立BI客户问题页，按客户/客户×模块/客户×功能三种行维度；未标注成员显式展示；表格全部指标可下钻。
- 本轮已实施：来源派生需求布尔列与数据-10必要修复时间治理；不建立新需求表、历史日报表或额外同步任务。R1—R4的实现记录值仍待独立人工裁定。
- 本轮已实施：按S03清单迁移共享纯规则和窄事实读模型；新旧调用方消费唯一实现，BI不调用平台页面Service。按S06演进下钻能力并更新调用方，不保留数值猜测与客户特例。
- 本轮已实施：BI客户查询使用专属窄范围入口，下载统一沿既有 `/api/bi/download/*`；显式scope契约替换productVersionId专用下载参数，全部第一方调用点同步迁移，不新增客户下载链，也不向六阶段产品版本模型塞同义参数。
- **替代上一版技术建议**：不再采用 `/api/bi/customer-issues/download/*`，也不承诺“旧下载请求结构完全不变”；保留的是原六页业务行为和唯一下载机制。共享HTTP请求/详情结构变化要登记并验收，不能以“兼容”保留另一套解析。
- 否决：需求原因归因代替标签身份、用SUGGESTION直接当需求、模糊contains需求、三标签容错OR、总数与子集堆积、累加客户行当总计、平均各客户均值、前端全量聚合、跳普通记录页假装统计下钻、请求实时查GitLab、未批准就执行数据治理。
- 工程验收红线：不复制旧Service/BI整页壳/图表option/Excel生成器，不新建Provider Registry/筛选DSL/同步器，不用boardKey条件补丁、默认false、异常回空、旧构造器补空或 `v2`入口隐藏契约问题；删除边界按S11逐项检查。

## 11. 风险与当前交付状态

1. R1—R4的记录值已实现，但没有独立人工裁定；若终审改变记录值，须同步实现、测试和CI条目。
2. S02自动链与来源完整性边界已实现；目标环境事件历史、旧回退时间纠正比例及必要ISSUE重建未核验/执行。`fixed_label_time`共享来源纠正会改变已有响应/解决效率、系统测试等ISSUE消费者产出，不能宣称无影响。
3. 数据-10记录页的修复时间筛选、列展示、详情和23列Excel仍未实施，本轮范围不含这些UI。
4. 客户统计与客户BI在`qaflex_uat`完成部分浏览器验收，但本地克隆数据不代表目标内网容量；客户统计全量无筛选页还受未版本化夹具编辑造成的旧缓存影响。
5. BI客户页八图和可用页面的真实导出已验证；六阶段因CAT未发布快照而无法完成全页导出验收，下载期间切换筛选和来源版本变化的浏览器时序也未在浏览器中实测。
6. 全量前端与后端测试存在失败/错误，因此S11未完成；API/字段/Flyway/产物/文本护栏及最终`git diff --check`均已退出0。最终清理只改过一行测试常量；全量前端失败日志对应此前测试文件版本，修改后只复跑了受影响定向套件。
7. 黄金门禁/快照、业务库迁移和真实ISSUE重建、同步/写回/备份、发布及目标容量验证未运行；本轮没有提交或推送。隔离环境恢复与当前监听状态以最终交付报告为准。
