# 兼容模式质量看板导出速率修复

## 进度与中间物

- 阶段：按2026-10-31用户最新指令收敛到兼容模式，代码与测试已拆分，最终验证及经理审批通过，待提交收口。首条恢复命令：`git -C D:/projects/dcp-quality-review-20261030 status --short --branch`。
- 任务目录`D:/projects/dcp-quality-review-20261030`，分支`质量看板版本范围与走查去重修复-20261030-1600`，基线`5bd8d6fd`。前序版本范围修复已本地审批，未合主线或发布。
- 正式扩展全部先保存到`.tmp/formal-scope-saved-20261031/all-changes.patch`及untracked副本，再仅恢复本任务已核实的正式扩展文件；员工已中断，不再写入或编译。补丁不进入提交。
- 旧真实导出证据`.tmp/manager-export-dgm-rate-20261031.xlsx`：MR5001两次各1000行、60/30分钟，原来无汇总。导出缺页与无效IID合组反例分别见`.tmp/manager-export-rate-baseline-red.log`和`.tmp/manager-rate-boundaries-red.log`。
- 范围收敛前64项通过仅保留过程，不代替最终候选验证；最终兼容范围9类103项全通过（失败/错误/跳过0），变更文件Checkstyle 0违规，真实只读查询→3份Excel及BI兼容接口复核通过，无阻塞。

## 需求、成功标准与禁止行为

- 同一MR多次走查新增行数只计一次、各次有效工时累计；1000行、60+30分钟为666.67行/小时。
- 保留原ALL逐次明细及35列顺序，兼容模式新增默认选中的“走查速率汇总”，包含每MR和当前导出范围总体结果。不能篡改原行数、平均单次速率或用冲突行数最大值掩盖异常。
- 仅保证兼容模式；不改正式模式、BI生产算法、密度/人员/频次公式、同步写入或迁移，不把构造夹具外推为内网现场值。

## 数据、API与环境约束

- 固定业务来源+正数MR IID为兼容MR身份，项目显示名、project_id和记录merge_request_id不参与兼容去重。CC与DGM隔离。
- 同一次查询的原始明细同时生成汇总；版本/分支范围与原导出相同，不新增MERGED过滤。行数为`added_lines`，工时为`review_duration_minutes`。
- IID缺失/非正、行数缺失/负数、工时缺失/非正、同MR行数冲突时速率为空，明确原因；任一MR未知则总体为空，完整零行+正工时速率为0。
- 18181→18090→15442为原验收环境，仅只读核验，不修改数据或重启。测试使用独占Testcontainers PostgreSQL，构建仅在本任务backend/target；主树工具只读，内网不使用。
- 无新增端点、权限、表或迁移。新增内部汇总DTO、Exporter私有汇总逻辑及Excel工作表，速率两位显示而不提前截断计算。

## 步骤、职责与验收

1. 已完成调查、员工实现及经理反例复审，随后按用户要求移出正式扩展。
2. 经理独占最终代码/文档与编译：验证兼容CC/DGM组合成员、SP1精确分支、OPENED范围保留、去重、完整性、long累加、Excel格式和默认选中；验证既有BI兼容计算。
3. 完成差异与敏感信息检查、仓库门禁和变更文件Checkstyle，记录结果并提交/推送origin和gitlab25；后续收口计划。

## 风险与边界

- 本地夹具和只读导出是规则证据，不代表20001当前值。服务未切换新代码，未运行默认全套或发布黄金，不合main、不出正式包或部署。
- 正式交接发现和未完成扩展仅作本地恢复材料，不是本轮需求或交付前置条件。已有会话文件、测试凭据和产物仅留.tmp等忽略目录。

## 最终审批证据

- `.tmp/quality-export-compatibility-final-regression.log`：103/0/0/0；其中真实PG7项、速率边界15项、导出4项，BI计算器34项及适配器12项。
- `.tmp/quality-export-changed-checkstyle.log`：0违规；三个仓库Python门禁与diff检查通过，AGENTS为26,521字节。
- `.tmp/manager-compatibility-final-evidence.json`固定最终源码SHA-256、测试结果和兼容API返回；`.tmp/manager-compatibility-export-final.log`记录只读库与真实Excel验收。
- 经理批准本地兼容修复。正式扩展仅留本地恢复材料，不进入本次提交或推送；现有服务未加载新导出，未合主线或部署。
