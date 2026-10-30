# 表单接入既有消费链方案

## 结论

- Issue按启用点T分流：新资源FORM、历史COMMENT。
- 走查随包交付，MR暂填评论；外部数据齐全、旧筛选对齐后按T_mr启用。非法页全旧。
- 6点：Issue事实、周期SLA、写回资格、MR事实、质量分析、统计/下钻/导出。
- 启用前置5项，消费接入阶段N4修正4处。

## 消费点改造表

新增`IssueBusinessInput`；`FormIssueInputResolver.resolve`按权威/目录读取，响应/回复按创建时间、ID选最新，首次取最早；`IssueSlaRules.evaluate`统一计算。原因可多选；有效回复去重ID数须为1。

| 消费点 | 现状 | FORM新输入与接入位置 | COMMENT保留 | 验收断言 |
|---|---|---|---|---|
| Issue首次事实 | 解析评论 | `IssueFactSourceRowMapper`映射基础列；`FactBuildService`调用resolver及`IssueBusinessProjector.apply`写业务列 | 原解析 | 首次10:00、计划9日、修复人甲 |
| 周期SLA | 重解析raw_payload | `refreshCustomerIssueDelayFactsForConfig`复用resolver/evaluate与捕获守卫；raw_payload仅供COMMENT计算 | 原输入/时限 | 改期后解决延期false，周期再跑仍false |
| 写回资格 | 镜像/事实授权 | `CustomerIssueDelayLabelWritebackWorkerService.authorize`：当前/事实/作业`form_input_hash`（两模板head/权威修订）一致且latest=consumed；沿原队列/轮次/围栏 | 原授权 | 旧hash/head未消费→HTTP=0，消费后重规划 |
| MR事实 | Demo max/评论兜底 | `FormReviewProjector.build`经目录生成逐记录`form_review_fact`并同事务发布；`form_records` CTE读投影，汇总分钟/五类数、人员、最近创建时间 | 原查询；登记旧表经目录 | 规范2+3=5、分钟10+20=30、MR=1 |
| 质量/分析 | 共用兼容模式 | `QualityBoardCodeReviewReadSupport`、`CodeReviewMultiBoardAnalyticsQueryService`按T_mr/权威分区选投影或旧源 | 原快照/公式 | FORM区规范5、均15；COMMENT不变 |
| 统计/下钻/导出 | MR行均值 | `CodeReviewMultiBoardService`按记录sum/count计算；`FormRecordExportService`按版导出；下钻保留recordId/版本 | 原统计/导出 | 两版各1行；空版仅表头；无导出权403 |

人员ID去重，密度按MR去重新增行，缺失为空。共同字段须身份/类型/单位/语义一致；缺值不补0、0保留、作废排除。新旧分区，不拼跨口径平均。

## 非法页边界

查询/刷新/导出/判定全旧，不混合、不缩范围、不同步旧评论；试填不更新旧页。外部与筛选就绪后整体验收切换。

## N4计划修正项

执行人：N4员工。

1. 三字段/max→遗漏五类数和说明、拼接不同记录→选逐记录投影C，否决max的A和仅最新的B。
2. 先接读源→分支缺上线写回修复→先吸收已验证主线/迁移依赖，再改Issue首次/周期。
3. 只改首次Mapper→周期会覆盖→共用类型化输入，发送前校验hash及head。
4. 新MR立即FORM→外部条件未齐→Issue按T启用，MR缓启。

## 端到端验收

1. **新Issue**：上海2026-11-10 12:00，创建11-01 00:00、P1、未完成/无豁免；各一响应/回复。链接可达；响应10:00创建，甲回复“部分解决”、原因`standard_error`、分支CC2026R4。初始仅有“响应已延期”；每步消费/SLA/写回后同步标签：

| 步骤 | 事实预期 | 标签动作 |
|---|---|---|
| 保存11-09计划及回复 | has_response=true，research_template_time=10:00，planned_resolution_at=11-09 00:00，fix_user=甲，原因唯一通过；响应延期false、解决延期true | 摘“响应已延期”，加“解决已延期” |
| 编辑计划为11-15 | 首次时间不变，计划及resolve_deadline_at=11-15 00:00；两延期false | 摘“解决已延期” |
| 作废回复 | fix_user、reason_category=NULL；11-15计划保留，两延期false：响应仍豁免 | 无加标 |
| 再作废唯一响应 | has_response=false，首次/计划为空，默认期限11-19；响应延期true、解决延期false | 加“响应已延期” |

`response_overdue=is_response_delayed`。旧作业暂停于最终authorize前，变更后释放：hash失配/head未消费则HTTP=0；消费后新作业发送。

2. **新MR隔离启用**：两版各1条，分钟10/20、规范2/3、其它0、新增行100→记录2、MR事实1、总分钟30、均15、规范5、密度50/千行，Excel各1行。作废第二条→记录1、均10、规范2、密度20；历史评论规范7仍7。
3. **切换边界**：T前1秒创建的Issue后补评论仍COMMENT；T后1秒为FORM，空表has_response=false，旧评论不补值。T_mr未设时MR登记COMMENT；非法页不变。

## 启用前置

| 缺什么 | 谁提供 | 卡住哪一步 |
|---|---|---|
| 生产源码版本 | 发布负责人 | 吸收已上线依赖、冻结候选 |
| CI_ROBOT投放/认证 | 机器人维护者 | Issue真实链接投放 |
| 真实范围、T、旧写回归属/停复窗口 | 用户/运维 | 权威登记及Issue启用 |
| 目标库当前/默认导出授权 | 权限管理员 | 正式授予；隔离库五角色是样本 |
| 外部接口数据及与旧筛选对照 | 外部平台方/N4实施者 | 走查FORM与非法页整体切换 |

## 取舍

选类型化输入，不拼Markdown，因为首次/周期需同口径。
选逐记录投影，不取max/仅最新，因为多次走查都贡献。
选原队列，不建旁路，因为授权/停止/围栏需连续。
选Issue先启用，不强切走查，因为外部尚缺。

代码基线：表单5cece6ad；主线4084cd33。
