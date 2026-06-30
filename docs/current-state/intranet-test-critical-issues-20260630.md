# 2026-06-30 内网测试关键问题诊断报告

> 状态：待修复
> 测试环境：内网（172.22.10.72 / 172.22.10.115:18181）
> 测试时间：2026-06-30
> 问题级别：P0（恶性bug）、P1（数据差异）

## 问题分类总览

### P0 恶性问题（阻塞使用）

1. **客户问题模块统计页无法首屏加载**
   - 5个统计页需要手动刷新才能显示
   - 无法切换里程碑
   - 下钻表打不开

2. **系统测试缺陷原因分析空表**
   - 只有表头，没有任何数据

### P1 数据差异问题（功能可用但数据不准）

3. 代码走查非法数据差6000条
4. 系统测试非法数据多2000+条
5. 申请延期缺陷分析数据异常个数差异大
6. 其他数量级差异（议题查询、CC_PRODUCT议题等）

---

## P0-1: 客户问题统计页无法首屏加载

### 问题现象

**影响范围**：客户问题模块5个统计页
- 缺陷汇总 `#/moduleTableCCProduct`
- 缺陷原因分析 `#/getModuleAndCauseTableCCProduct`
- 延期问题 `#/getDelayIssueCCProduct`
- 缺陷响应效率 `#/customer-issues/response-efficiency`
- 按功能展示缺陷数量 `#/getIissueByFunction`

**具体表现**：
- 需要手动点击"单表刷新"才能显示数据
- 无法切换里程碑（里程碑选择器不可用或无选项）
- 下钻表打不开
- 页面默认显示 CC2026R3 的数据

### 根因分析

#### 原因1：快照未预热导致首屏等待超时

**代码位置**：
- `CustomerIssue*BoardService.refreshSnapshots()`
- `StatisticBoardSnapshotService.get()`

**推断**：
1. 事实层重建后，快照预热逻辑可能未触发或失败
2. 前端首次请求时，后端同步计算快照
3. 由于数据量大，计算超过前端15秒超时
4. 前端显示空白或加载失败
5. 手动刷新触发了单表增量同步，数据量变小，快照生成成功

**验证方法**：
```sql
-- 检查快照表是否为空
SELECT board_key, COUNT(*) 
FROM statistic_board_snapshots 
WHERE board_key LIKE 'customer-issue-%'
GROUP BY board_key;

-- 如果为空，说明预热未执行
```

**解决方案**：
1. 确认事实重建完成后是否触发了快照预热
2. 检查 `FactBuildService` 是否调用了 `snapshotRefresher.refreshAll()`
3. 检查快照预热是否因为超时而失败（查看后端日志）
4. 考虑将快照预热改为异步后台任务，不阻塞事实重建完成

#### 原因2：里程碑候选列表加载失败

**代码位置**：
- `CustomerIssueMilestoneCatalogService.listMilestones()`
- 前端 `statistic-board-data-scopes.ts` 的 `CUSTOMER_ISSUE_MILESTONE_SCOPE_PROVIDER`

**推断**：
1. `CustomerIssueMilestoneCatalogService` 查询里程碑时依赖 `issue_fact` 或 `ods_gitlab_milestones`
2. 如果事实表为空或未正确关联，返回空列表
3. 前端收到空列表后，里程碑选择器无可用选项
4. `useDataScope` 的 `first-available` 策略无法写入默认值
5. 页面请求没有 `milestoneTitle` 参数，后端拒绝服务或返回空

**验证方法**：
```bash
# 检查API返回
curl "http://172.22.10.115:18181/api/customer-issues/records/filter-options/cc-product?projectId=325" | jq '.milestoneTitles'

# 应返回里程碑列表，如果为空则确认是此问题
```

**解决方案**：
1. 检查 `CustomerIssueMilestoneCatalogService.listMilestones()` 的 SQL 查询
2. 确认 `issue_fact` 表中 `project_id=325` 且 `created_at >= 2026-01-01` 的数据存在
3. 确认 `ods_gitlab_milestones` 与 `ods_gitlab_issues` 的关联正确
4. 如果是内网镜像未同步，先手动触发一次全量同步

#### 原因3：前端路由守卫等待超时

**代码位置**：
- `StatisticBoardView.vue` 的 `routeScopeReady` 逻辑
- `useDataScope` 的异步写入路由

**推断**：
1. 前端等待里程碑候选加载完成 → 写入默认值到 URL → 触发主表加载
2. 如果候选加载失败或超时，`routeScopeReady` 永远不为 true
3. 主表加载逻辑永远不触发
4. 页面显示空白或加载中状态

**验证方法**：
- 浏览器开发者工具查看 Network 面板
- 确认是否只有候选接口请求，没有主表接口请求
- 确认 URL 中是否缺少 `milestoneTitle` 参数

**解决方案**：
1. 为 `routeScopeReady` 添加超时兜底，避免永久等待
2. 候选加载失败时，应降级为"无默认值"而非阻塞页面
3. 在前端添加明确的错误提示："里程碑候选加载失败，请刷新页面"

#### 原因4：下钻表打不开的独立原因

**代码位置**：
- `StatisticBoardView.vue` 的下钻详情逻辑
- `CustomerIssue*BoardService.loadDetail()`

**推断**：
1. 下钻请求依赖当前 `filterGroup` 中的 `milestoneTitle`
2. 如果主表是通过"手动刷新"显示的，可能 `filterGroup` 未正确更新
3. 下钻请求缺少 `milestoneTitle`，后端拒绝或返回空

**解决方案**：
1. 确认主表显示后，前端 `filterGroup` 状态是否包含 `milestoneTitle`
2. 下钻请求应携带主表当前的完整 `filterGroup`

---

## P0-2: 系统测试缺陷原因分析空表

### 问题现象

**URL**：`http://172.22.10.115:18181/#/question-metrics/defect-cause?testingPhase=CC2026R3&tablePage=1`

**表现**：
- 页面只有表头，没有任何数据
- URL 中已有 `testingPhase=CC2026R3` 参数

### 根因分析

#### 原因1：reason_category 事实字段未生成

**代码位置**：
- `IssueFactBuilder` 中生成 `reason_category` 的逻辑
- 老平台 `spider_issue_data.cause` 的来源

**推断**：
1. 老平台 `cause` 字段来自修复模板 `### 1、修复状态`
2. 新平台 `reason_category` 应按相同规则从评论中提取
3. 如果提取逻辑有误或模板匹配失败，`reason_category` 为空
4. `SystemTestDefectCauseBoardService` 只统计 `reason_category` 非空的记录
5. 所有记录被过滤，导致空表

**验证方法**：
```sql
-- 检查系统测试范围内是否有 reason_category
SELECT COUNT(*) 
FROM issue_fact 
WHERE project_id = 9 
  AND testing_phase LIKE '%CC2026R3%'
  AND reason_category IS NOT NULL 
  AND reason_category <> '';

-- 如果为0，确认是此问题
```

**解决方案**：
1. 检查 `IssueFactBuilder` 中 `reason_category` 的生成逻辑
2. 确认修复模板的正则表达式是否正确
3. 对照老平台 `cause` 提取逻辑，确保一致性
4. 如果模板格式有变化，更新匹配规则
5. 重建事实表

#### 原因2：测试阶段过滤条件过严

**代码位置**：
- `SystemTestDefectCauseBoardService.loadSources()`
- `SystemTestPhaseSqlPredicateSupport`

**推断**：
1. SQL 查询中的阶段过滤可能过严
2. 例如：要求 `testing_phase = 'CC2026R3'`（精确匹配）
3. 但实际数据是 `'CC2026R3 第六轮系统测试'`（包含额外文本）
4. 导致没有记录命中

**验证方法**：
```sql
-- 查看实际的 testing_phase 值
SELECT DISTINCT testing_phase 
FROM issue_fact 
WHERE project_id = 9 
  AND testing_phase LIKE '%CC2026R3%'
LIMIT 10;

-- 确认是否需要 LIKE 匹配而非精确等值
```

**解决方案**：
1. 确认 `SystemTestPhaseSqlPredicateSupport` 使用 `LIKE %phase%` 而非 `=`
2. 对照老平台 `SpiderIssueDataQueryBuilder.setTestingPhases()` 的 LIKE 逻辑

---

## P1-1: 代码走查非法数据差6000条

### 问题现象

**URL**：`http://172.22.10.72/#/codeThroughDataTable`

**表现**：
- 只有 CrownCAD 一个项目，下拉框中没有其他项目
- CrownCAD 数据与老平台差6000条
- 新平台的非法数据有很多在老平台根本没有统计进去

### 根因分析

#### 原因1：项目候选来源错误

**代码位置**：
- `CodeReviewIllegalRecordService.getFilterOptions()`
- `merge_request_fact.project_name` 的枚举

**推断**：
1. 老平台代码走查支持多个项目（CrownCAD、DGM等）
2. 新平台只从 `merge_request_fact` 中枚举 `project_name`
3. 如果只有 CrownCAD 的 MR 被同步，候选列表就只有 CrownCAD
4. 或者 `merge_request_fact.project_name` 的归一化规则有误

**验证方法**：
```sql
-- 检查 merge_request_fact 中的项目分布
SELECT project_name, COUNT(*) 
FROM merge_request_fact 
WHERE source_instance = 'cc' 
  AND merge_request_state = 'merged'
GROUP BY project_name;

-- 如果只有 CrownCAD，说明其他项目未同步或归一化错误
```

**解决方案**：
1. 检查 GitLab 镜像同步配置，确认是否只监听了 CrownCAD 项目
2. 检查 `merge_request_fact` 构建逻辑，确认 `project_name` 是否正确映射
3. 如果是内网环境限制，至少要在文档中说明

#### 原因2：非法判定规则不一致

**代码位置**：
- `CodeReviewIllegalRecordSourceLoader` 的非法判定逻辑
- 老平台 `SpiderCrowncadQueryBuilder.setSearchIllegalQuery()`

**推断**：
1. 老平台非法类型包括：无代码走查、未代码扫描、未标注项目名、未标注模块名、注释率/bug count/扫描失败等
2. 新平台判定可能遗漏某些类型或判定条件不严格
3. 导致新平台统计了老平台不认为是"非法"的记录

**对照老平台规则**（legacy-source-direct-comparison-review-20260630.md Section 5.1）：
- `status = MERGED`
- 命中任一非法类型
- `module_name <> '无需标注'`
- 默认 `merged_time > 2024-04-01`

**具体差异点**：
1. **无代码走查判定**
   - 老平台：`assignee in (没有合法评论, 代码走查时间或缺陷数异常, ...)`
   - 新平台：需确认是否检查了所有4种占位值

2. **静态扫描问题未关闭**
   - 老平台：`bug_count_result = 静态扫描问题未关闭`
   - 新平台：不应该仅因 `scan_bug_count > 0` 就判定

3. **GitLab 接口报错**
   - 老平台：`sonar_qube_result / target_branch / assignee` 三处来源
   - 新平台：需确认是否检查了所有三处

**解决方案**：
1. 逐条对照老平台 `SpiderCrowncadQueryBuilder` 的非法判定规则
2. 审查 `CodeReviewIllegalRecordSourceLoader` 的每个非法类型判定
3. 导出新旧平台各100条非法数据，逐条对比哪些是新平台多出来的
4. 按差异调整判定规则

#### 原因3：merge_request_fact 字段映射错误

**代码位置**：
- `MergeRequestFactBuilder` 的字段映射
- 老平台 `spider_crowncad_data` 字段到新平台事实表的对应关系

**关键字段映射**（需逐一验证）：
- `project_name` → `merge_request_fact.project_name`
- `module_name` → `merge_request_fact.module_name`
- `target_branch` → `merge_request_fact.target_branch`
- `owner` → `merge_request_fact.author_name`
- `merged_by` → `merge_request_fact.merge_user_name`
- `assignee` → `merge_request_fact.reviewer_names` 或其他字段

**解决方案**：
1. 从老平台导出一条具体的非法 MR（例如 MR 29874）
2. 在新平台查询同一 MR 的 `merge_request_fact` 记录
3. 逐字段对比，找出映射错误

---

## P1-2: 系统测试非法数据多2000+条

### 问题现象

**URL**：`http://172.22.10.72/#/illegalIssueSearch`

**表现**：
- CC2026R3 新平台统计2221条
- 老平台仅17条
- 差距巨大（130倍）

### 根因分析

#### 原因1：范围过滤未生效

**代码位置**：
- `SystemTestIllegalRecordService`
- `IssueFactRecordPageQuery.Scope.SYSTEM_TEST`

**推断**：
1. 虽然代码中使用了 `Scope.SYSTEM_TEST`
2. 但实际 SQL 查询可能仍然是 `Scope.ALL` 或范围过宽
3. 导致统计了非系统测试范围的记录

**验证方法**：
```sql
-- 检查老平台 CC2026R3 非法数据的实际范围
SELECT COUNT(*) 
FROM spider_issue_data 
WHERE project_id = 9 
  AND testing_phase LIKE '%CC2026R3%'
  AND illegal_list IS NOT NULL;

-- 如果接近17，说明老平台确实只有这么多

-- 检查新平台查询范围
SELECT COUNT(*) 
FROM issue_fact 
WHERE project_id = 9 
  AND testing_phase LIKE '%CC2026R3%'
  AND (is_illegal = true OR illegal_reasons IS NOT NULL);

-- 如果接近2221，说明新平台判定过宽
```

**解决方案**：
1. 确认 `Scope.SYSTEM_TEST` 的定义是否正确
2. 检查 `testing_phase` 过滤是否生效
3. 检查是否误用了 `label_names` 作为兜底范围扩大
4. 确认 `is_illegal` 和 `illegal_reasons` 的生成规则与老平台一致

#### 原因2：非法判定规则过宽

**代码位置**：
- `IssueFactBuilder` 中生成 `is_illegal` 和 `illegal_reasons` 的逻辑
- 老平台 `findIllegalIssue` 的判定条件

**老平台非法类型**：
- 未按照模板回复
- 缺陷原因不唯一
- 模块名不规范
- 功能名不规范
- 严重程度不规范
- 议题状态不规范
- 等等...

**推断**：
1. 新平台非法判定可能包含了老平台不认为是"非法"的类型
2. 或者判定条件不够严格

**解决方案**：
1. 导出新平台2221条中的前100条
2. 在老平台查询这100条是否被标记为非法
3. 找出新平台多判定的非法类型
4. 调整判定规则

---

## P1-3: 申请延期缺陷分析数据异常差异大

### 问题现象

**URL**：`http://172.22.10.72/#/delayCauseInfo`

**表现**：
- "数据异常"统计个数：老平台几十个，新平台几百上千
- 其他延期原因也有数量级差异

### 根因分析

#### 原因1：delay_cause 归一化规则不一致

**代码位置**：
- `IssueFactBuilder` 中生成 `delay_cause` 的逻辑
- 老平台 `DelayEnum` 的固定枚举值

**老平台延期原因**：
- 技术卡点
- 方案卡点
- 资源卡点
- 数据异常
- 算法问题
- 机制问题
- 计算效率

**推断**：
1. 老平台延期原因来自标签，且必须精确匹配 `DelayEnum` 枚举值
2. 新平台可能把"数据异常"相关的多个标签都归为"数据异常"
3. 或者提取逻辑错误，把非延期原因的标签也提取进来了

**验证方法**：
```sql
-- 检查新平台 delay_cause 的实际值分布
SELECT delay_cause, COUNT(*) 
FROM issue_fact 
WHERE project_id = 9 
  AND testing_phase LIKE '%CC2026R3%'
  AND delay_cause IS NOT NULL
GROUP BY delay_cause
ORDER BY COUNT(*) DESC;

-- 如果"数据异常"数量异常大，说明归一化规则有误
```

**解决方案**：
1. 对照老平台 `DelayEnum.getFindName()` 的匹配规则
2. 确认新平台只提取标签中精确匹配枚举值的部分
3. 不应该做模糊匹配或包含匹配

#### 原因2：延期判定条件不一致

**代码位置**：
- 老平台 `getDefectAndDelayCauseTable` 的查询条件
- 新平台 `SystemTestDelayAnalysisBoardService` 的数据范围

**推断**：
1. 老平台可能要求议题同时满足：有延期原因标签 + 特定状态
2. 新平台可能只检查延期原因标签，不检查状态
3. 导致新平台统计了更多记录

**解决方案**：
1. 检查老平台 `getDefectAndDelayCauseTable` 的完整查询条件
2. 确认是否有隐含的状态过滤或其他过滤条件

---

## P1-4: 其他数量差异汇总

### 议题阶段统计差1个

**原因推断**：
- 边界样本问题
- 可能是某条议题的阶段归属判定不同
- 或者一级/三级缺陷的严重程度标签归一化差异

**解决方案**：
- 导出新旧平台的完整列表
- 找出差异的那1条记录
- 分析其字段值差异

### 议题查询多300条

**原因推断**：
- 老平台默认 `queryFilter=false`，只排除"已拒绝/功能屏蔽"
- 新平台可能排除条件不够严格
- 或者多统计了某些状态的记录

**解决方案**：
- 对照老平台 `SpiderIssueDataQueryBuilder.setFilterRejected(true)` 的逻辑
- 确认新平台只排除了这两种，没有多排除或少排除

### CC_PRODUCT议题多1000条

**原因推断**：
- 老平台默认 `submissionDate=2026-01-01`
- 新平台可能包含了2026年之前的数据
- 或者"已拒绝"状态的过滤不一致

**解决方案**：
- 检查 `CustomerIssueRecordService` 的默认创建日期过滤
- 确认 `excludeRejectedBugStatus` 是否生效

### 客户问题非法数据差异

**原因推断**：
- 里程碑不同，数据范围不同
- 或者非法判定规则不一致

**解决方案**：
- 确保同一里程碑下对比
- 对照老平台客户问题非法规则

---

## 修复优先级建议

### 🚨 P0 立即修复（阻塞使用）

1. **客户问题统计页无法首屏加载**
   - 优先级：P0-1
   - 影响：5个统计页无法使用
   - 建议方案：
     - 短期：手动触发快照预热脚本
     - 中期：修复快照预热触发逻辑
     - 长期：快照预热改为异步后台任务

2. **系统测试缺陷原因分析空表**
   - 优先级：P0-2
   - 影响：关键统计功能不可用
   - 建议方案：
     - 检查 `reason_category` 事实字段生成
     - 确认测试阶段过滤条件
     - 重建事实表

### ⚠️ P1 优先修复（数据准确性）

3. **系统测试非法数据多2000+条**
   - 优先级：P1-高
   - 影响：130倍差异，数据完全不可信
   - 建议方案：
     - 逐条对照老平台非法判定规则
     - 导出样本数据对比

4. **代码走查非法数据差6000条**
   - 优先级：P1-高
   - 影响：数据差异大，影响决策
   - 建议方案：
     - 逐条对照非法判定规则
     - 检查字段映射

5. **申请延期缺陷分析数据异常差异**
   - 优先级：P1-中
   - 建议方案：
     - 检查 `delay_cause` 归一化规则

6. **其他数量级差异**
   - 优先级：P1-低
   - 建议方案：
     - 按差异大小排序修复

---

## 验证脚本

### 快照状态检查
```sql
-- 检查快照表
SELECT board_key, 
       COUNT(*) as snapshot_count,
       MAX(last_updated_at) as last_update
FROM statistic_board_snapshots 
WHERE board_key LIKE 'customer-issue-%'
   OR board_key LIKE 'system-test-%'
GROUP BY board_key
ORDER BY board_key;
```

### 事实表数据完整性检查
```sql
-- 客户问题范围
SELECT COUNT(*) as total,
       COUNT(DISTINCT milestone_title) as milestones,
       COUNT(*) FILTER (WHERE reason_category IS NOT NULL) as with_reason
FROM issue_fact 
WHERE project_id = 325 
  AND created_at_source >= '2026-01-01';

-- 系统测试范围
SELECT COUNT(*) as total,
       COUNT(*) FILTER (WHERE reason_category IS NOT NULL) as with_reason,
       COUNT(*) FILTER (WHERE is_illegal = true) as illegal
FROM issue_fact 
WHERE project_id = 9 
  AND testing_phase LIKE '%CC2026R3%';
```

### 里程碑候选检查
```bash
# 客户问题里程碑
curl "http://172.22.10.115:18181/api/customer-issues/records/filter-options/cc-product?projectId=325" \
  | jq '.milestoneTitles | length'

# 如果返回0，说明候选为空
```

---

## 后续行动建议

1. **立即处理 P0 问题**
   - 手动触发快照预热
   - 检查 `reason_category` 生成逻辑

2. **按优先级修复 P1 问题**
   - 先修系统测试非法数据（130倍差异）
   - 再修代码走查非法数据（6000条差异）

3. **建立数据对比流程**
   - 导出新旧平台同一范围的数据
   - 逐条对比找出规则差异
   - 按差异调整代码

4. **补充自动化测试**
   - 添加数据一致性测试
   - 对照老平台数据集进行回归测试
