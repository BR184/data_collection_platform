# 2026-06-30 内网问题修复（第二轮）评审报告

> 评审时间：2026-06-30
> 修复范围：P0 恶性问题 + 部分 P1 数据差异问题
> 提交：051c9656 + 9224f012

---

## 📊 总体评估：8.5/10 - 优秀

**修复完成度**：
- ✅ P0-1: 客户问题统计页无法首屏加载 - **部分修复**
- ✅ P0-2: 系统测试缺陷原因分析空表 - **已修复**
- ✅ P1: 代码走查非法数据问题 - **已修复**
- ⚠️ 其他 P1 数据差异 - **未涉及**

---

## ✅ 已修复问题评审

### 修复1: 系统测试缺陷原因分析空表（P0-2）

#### 问题回顾
- 页面只有表头，没有任何数据
- URL 已有 `testingPhase=CC2026R3` 参数

#### 修复方案

**核心改动**（SystemTestDefectCauseBoardService.java）：

```java
// 修改前：只使用 reason_category
and coalesce(reason_category,'') <> ''

// 修改后：reason_category 为空时回退到 raw_payload
private static final String CAUSE_TEXT_SQL = 
    "coalesce(nullif(reason_category,''), raw_payload, '')";

and coalesce(nullif(reason_category,''), raw_payload, '') <> ''
```

**评估**：
- ✅ **根因定位准确** - `reason_category` 为空导致所有记录被过滤
- ✅ **方案合理** - 降级到评论文本兜底，避免数据丢失
- ✅ **SQL 一致性** - 所有相关查询都使用 `CAUSE_TEXT_SQL` 常量
- ✅ **规则版本更新** - `v1 → v2`，标记了口径变化

**对照老平台**：
- 老平台直接从 `spider_issue_data.cause` 字段读取
- 该字段来自修复模板解析
- 新平台优先用解析后的 `reason_category`，解析失败时回退原文
- **策略正确** ✅

**潜在风险**：
- ⚠️ `raw_payload` 包含全部评论，可能匹配到非缺陷原因的文本
- ⚠️ 如果 `reason_category` 解析逻辑有误，应该修复解析逻辑而非依赖兜底

**建议**：
1. 短期：当前方案可用，能让页面显示数据
2. 中期：修复 `reason_category` 的解析逻辑，减少兜底依赖
3. 长期：在规则说明中明确"兜底到评论文本"的行为

**评分**：9/10 - 优秀，快速解决了空表问题

---

### 修复2: 客户问题统计页路由契约问题（P0-1 部分）

#### 问题回顾
- 5个统计页需手动刷新才能显示
- 无法切换里程碑
- 下钻表打不开

#### 修复方案

**前端路由契约修改**（route-contracts.ts）：

```typescript
// 修改前：客户问题统计页使用 testingPhase
const customerIssueStatisticBoardQueryKeys = statisticBoardQueryKeys;

// 修改后：移除 testingPhase，添加 milestoneTitle
const customerIssueStatisticBoardQueryKeys = statisticBoardQueryKeys
  .filter((key) => key !== 'testingPhase')
  .concat('milestoneTitle');
```

**客户问题非法记录视图修改**（CustomerIssueIllegalRecordsView.vue）：

```vue
<!-- 移除 testingPhase 从 allowedQueryKeys -->
```

**数据范围加载顺序修复**（statistic-board-data-scopes.ts）：

```typescript
// 修改前：customerLoaded 在 try 块内设置，异常时不会设置
customerLoaded.value = true;
} finally {

// 修改后：customerLoaded 在 finally 块内设置，确保总是执行
} finally {
  customerLoaded.value = true;
  customerLoading.value = false;
}
```

**评估**：

✅ **路由契约正确化**
- 客户问题统计页不应使用 `testingPhase`
- 正确使用 `milestoneTitle`
- 避免了参数冲突

✅ **加载状态修复**
- `customerLoaded` 移到 `finally` 确保总是设置
- 避免了候选加载失败时永久等待
- **这是关键修复** - 解决了"页面卡住不加载"的根因

**对照诊断报告**：
- 诊断报告 P0-1 原因2：里程碑候选列表加载失败
- 诊断报告 P0-1 原因3：前端路由守卫等待超时
- **当前修复正确解决了原因3** ✅

**未解决部分**：
- ⚠️ 诊断报告 P0-1 原因1：快照未预热 - **未在本轮修复**
- ⚠️ 诊断报告 P0-1 原因2：里程碑候选为空 - **本轮只修复了加载失败时的降级**

**评分**：8/10 - 良好，解决了路由和加载状态问题，但快照预热未涉及

---

### 修复3: 代码走查非法数据问题（P1-1）

#### 问题回顾
- 只有 CrownCAD 一个项目
- CrownCAD 数据差6000条
- 新平台多统计了老平台没有的数据

#### 修复方案

**移除错误的默认项目逻辑**（CodeReviewIllegalRecordService.java）：

```java
// 修改前：默认 projectName=CrownCAD
private static final String LEGACY_DEFAULT_PROJECT_NAME = "CrownCAD";
boolean useLegacyDefaultProject =
    projectName == null && (source == null || "cc".equals(normalizedSource));
return new CodeReviewIllegalRecordQueryRequest(
    useLegacyDefaultProject ? null : request.projectId(),
    ...
    useLegacyDefaultProject ? LEGACY_DEFAULT_PROJECT_NAME : request.projectName(),
    ...);

// 修改后：只默认 source=cc，不默认 projectName
private static final String LEGACY_DEFAULT_SOURCE = "cc";
String normalizedSource = source == null ? LEGACY_DEFAULT_SOURCE : ...;
return new CodeReviewIllegalRecordQueryRequest(
    request.projectId(),        // 不再强制为 null
    ...
    request.projectName(),       // 不再强制为 CrownCAD
    ...
    normalizedSource,            // 只默认 source
    ...);
```

**移除筛选候选的默认项目逻辑**：
- 删除了 `withLegacyDefaultScope(CodeReviewIllegalRecordFilterOptionsRequest)` 方法
- `getFilterOptions()` 直接使用请求参数，不再强制默认

**评估**：

✅ **正确移除了过度限制**
- 老平台前端默认 `source=CC + projectName=CrownCAD`
- 但这是**前端默认值**，不应该在后端强制
- 后端强制会导致：
  - 候选列表永远只有 CrownCAD
  - 用户无法切换到其他项目
  - API 无法通用化

✅ **保留了正确的默认值**
- `source` 默认为 `cc` - 正确 ✅
- `projectName` 不再强制 - 正确 ✅

**对照诊断报告**：
- 诊断报告 P1-1 原因1：项目候选来源错误 - **已修复** ✅
- 诊断报告 P1-1 原因2：非法判定规则不一致 - **未在本轮涉及**
- 诊断报告 P1-1 原因3：字段映射错误 - **未在本轮涉及**

**仍存在的问题**：
- ⚠️ 数据差6000条的根因**不是默认项目逻辑**
- ⚠️ 真正原因是**非法判定规则不一致**或**字段映射错误**
- ⚠️ 本轮修复只解决了"候选列表只有一个项目"的表面问题

**建议后续验证**：
1. 内网测试候选列表是否包含多个项目（DGM、其他项目）
2. 如果仍只有 CrownCAD，说明是镜像同步问题或项目归一化问题
3. 如果有多个项目，再对比各项目的非法数据量是否准确

**评分**：7.5/10 - 良好，解决了候选列表限制，但6000条差异的根因未解决

---

### 修复4: 非法判定 fixed 字段逻辑修正

#### 问题回顾
- 非法判定使用 `fact.isFixed()` 判断是否修复
- 但某些情况下 `isFixed` 可能不准确

#### 修复方案

**FactBuildService.java 修改**：

```java
// 修改前：直接使用 fact.isFixed()
boolean fixed = Boolean.TRUE.equals(fact.getFixed());
fact.setIllegal(...isIllegal(..., fixed));

// 修改后：为非法判定单独提取 bug_status
boolean fixed = Boolean.TRUE.equals(fact.getFixed());
boolean fixedForIllegalCheck =
    StringUtils.hasText(fact.getBugStatus()) && fact.getBugStatus().contains("已修复");
fact.setIllegal(...isIllegal(..., fixedForIllegalCheck));
```

**评估**：

✅ **修正了判定逻辑**
- 非法判定不应依赖 `isFixed` 标记
- 应该依赖实际的 `bug_status` 标签文本
- 避免了 `isFixed` 生成逻辑变化时影响非法判定

✅ **对齐老平台规则**
- 老平台非法判定检查 `bug_status` 是否包含"已修复"
- 新平台现在也按相同规则检查

**潜在问题**：
- ⚠️ `bug_status` 只检查"已修复"，不检查"完成"
- ⚠️ 老平台是"已修复/完成"一起检查
- ⚠️ 需确认 `bug_status` 归一化是否将"完成"归为"已修复"

**评分**：8.5/10 - 优秀，修正了判定依赖，但需确认完整性

---

## ⚠️ 未解决的问题

### P0-1: 客户问题统计页快照预热

**状态**：第三轮已补根治入口，待内网事实重建验证

**已修复**：
- ✅ 路由契约正确化
- ✅ 加载状态降级逻辑
- ✅ 候选加载失败不阻塞页面
- ✅ 第三轮补充：`/api/facts/rebuild` 手动事实重建成功后会触发 `StatisticBoardSnapshotRefreshService.refreshAfterFactBuild(...)`
- ✅ 第三轮补充：`scope=all` 会以 `ALL` 上下文触发 issue / merge request 相关 refresher，避免只有队列式事实重建才预热快照

**未修复**：
- ⚠️ 预热失败的监控和重试仍未实现
- ⚠️ 需要内网在事实层重建后检查 `statistic_board_snapshots` 是否生成客户问题和系统测试默认范围快照

**建议**：
1. **短期**：手动触发快照预热
   ```sql
   -- 调用预热 API
   POST /api/statistic-boards/refresh-all
   ```

2. **中期**：验证 `FactBuildService` 完成后是否调用了 `snapshotRefresher.refreshAll()`
   - 查看后端日志
   - 确认预热是否执行
   - 如果未执行，补充触发逻辑

3. **长期**：将快照预热改为异步后台任务
   - 不阻塞事实重建完成
   - 添加失败重试机制
   - 添加监控告警

---

### P1: 其他数据差异问题

**状态**：未涉及

本轮未修复的问题：
- ❌ 系统测试非法数据多2000+条（130倍差异）
- ❌ 申请延期缺陷分析数据异常差异大
- ❌ 议题阶段统计差1个
- ❌ 议题查询多300条
- ❌ CC_PRODUCT议题多1000条
- ❌ 客户问题非法数据差异

**建议优先级**：
1. **P1-高**：系统测试非法数据（130倍差异）
2. **P1-中**：申请延期缺陷分析
3. **P1-低**：其他小数量差异

---

## 📊 代码质量评估

### 优点

1. ✅ **快速定位根因**
   - 缺陷原因分析空表 → `reason_category` 为空
   - 客户问题路由问题 → `testingPhase` 参数冲突
   - 准确且高效

2. ✅ **降级策略合理**
   - `reason_category` 为空时回退 `raw_payload`
   - 候选加载失败时仍设置 `loaded=true`
   - 避免了完全不可用

3. ✅ **代码复用良好**
   - `CAUSE_TEXT_SQL` 常量统一管理
   - 所有相关查询都使用相同逻辑

4. ✅ **规则版本管理**
   - `v1 → v2` 标记了口径变化
   - 便于追溯和回滚

### 不足

1. ⚠️ **快照预热未完全解决**
   - 只修复了降级逻辑
   - 根本的预热触发问题未解决
   - 内网仍需手动刷新

2. ⚠️ **数据差异根因未深入**
   - 代码走查6000条差异的真正原因未找到
   - 只解决了候选列表限制
   - 非法判定规则未逐条对比

3. ⚠️ **测试覆盖不足**
   - 只添加了路由测试
   - 缺少快照预热的集成测试
   - 缺少数据一致性测试

---

## 🎯 内网验收建议

### 优先验证

1. **系统测试缺陷原因分析**
   - ✅ 确认有数据显示
   - ✅ 确认数据量与老平台接近
   - ⚠️ 抽查几条原因是否准确

2. **客户问题统计页**
   - ⚠️ 是否仍需手动刷新？
   - ✅ 里程碑切换是否正常？
   - ✅ 下钻表是否能打开？

3. **代码走查非法数据**
   - ✅ 候选列表是否有多个项目？
   - ⚠️ CrownCAD 数据量是否仍差6000条？

### 验证脚本

```sql
-- 1. 检查快照表
SELECT board_key, COUNT(*) 
FROM statistic_board_snapshots 
WHERE board_key LIKE 'customer-issue-%'
GROUP BY board_key;

-- 2. 检查缺陷原因数据
SELECT COUNT(*) as total,
       COUNT(*) FILTER (WHERE reason_category IS NOT NULL) as with_category,
       COUNT(*) FILTER (WHERE reason_category IS NULL AND raw_payload IS NOT NULL) as fallback
FROM issue_fact 
WHERE project_id = 9 
  AND testing_phase LIKE '%CC2026R3%';

-- 3. 检查非法判定
SELECT COUNT(*) as total,
       COUNT(*) FILTER (WHERE is_illegal = true) as illegal,
       COUNT(*) FILTER (WHERE bug_status LIKE '%已修复%') as fixed
FROM issue_fact 
WHERE project_id = 9 
  AND testing_phase LIKE '%CC2026R3%';
```

---

## 📋 后续行动建议

### 立即执行

1. **手动触发快照预热**
   ```bash
   # 调用预热 API
   curl -X POST "http://172.22.10.115:18181/api/statistic-boards/refresh-all"
   ```

2. **内网验证本轮修复**
   - 按上述验收脚本执行
   - 记录实际效果
   - 确认问题是否解决

### 短期补充

3. **修复快照预热触发**
   - 已补 `/api/facts/rebuild` 成功后的预热调用
   - 队列式事实重建原本已有 `FactRefreshTaskWorkerService -> StatisticBoardSnapshotRefreshService`
   - 后续根据内网日志再补预热失败重试和监控

### 第三轮根治补充：缺陷原因事实字段

第二轮 `SystemTestDefectCauseBoardService` 使用 `raw_payload` 兜底能解决空表，但可能误命中非原因评论。第三轮已将根治点前移到事实层：

- `IssueTemplateParsingSupport` 按老平台 `IssueServiceImpl.getCause(...)` 解析第一条 `### 1、修复状态` 评论，生成与 `spider_issue_data.cause` 等价的原因段文本。
- `IssueClassificationRules.normalizeFixReasonCategory(...)` 优先写入该原因段文本，而不是只写单一归一化原因枚举。
- 系统测试缺陷原因看板 `RULE_VERSION` 升至 `system-test-defect-cause@2026-06-30-v3`，查询只使用 `issue_fact.reason_category`，不再回退全文 `raw_payload`。
- 客户问题缺陷原因看板 `RULE_VERSION` 升至 `customer-issue-defect-cause@2026-06-30-v3`。
- 该修复必须重建 issue fact 后生效；否则旧 `reason_category` 仍会留在事实表中。

### 第三轮根治补充：非法判定收敛

- 系统测试非法数据继续以 `issue_fact.illegal_reasons` 对齐老平台 `illegal_list`，但底层“未按照模板回复/缺陷原因不唯一”已改用老平台原因段文本计算，避免旧实现因全文兜底导致非法数量膨胀。
- 代码走查“未代码扫描”收敛为老平台字面值 `未进行代码扫描`，不再把 `NOT_SCANNED`、`UNSCANNED`、`未扫描`、`未代码扫描` 等扩展状态纳入默认非法口径。

4. **对比数据差异根因**
   - 导出新旧平台同一范围的数据
   - 逐条对比找出规则差异
   - 特别关注非法判定规则

### 长期改进

5. **添加自动化测试**
   - 快照预热的集成测试
   - 数据一致性回归测试
   - 对照老平台数据集

6. **监控和告警**
   - 快照预热成功率监控
   - 快照生成时间监控
   - 候选加载失败告警

---

## 总结

### ✅ 本轮修复成就

1. ✅ **解决了系统测试缺陷原因分析空表** - P0 问题
2. ✅ **解决了客户问题统计页路由和加载问题** - P0 部分修复
3. ✅ **解决了代码走查候选列表限制** - P1 部分修复
4. ✅ **修正了非法判定 fixed 逻辑** - 潜在 bug 修复

### ⚠️ 仍需关注

1. ⚠️ **客户问题统计页快照预热** - 根本问题未解决
2. ⚠️ **代码走查数据差6000条** - 根因未找到
3. ⚠️ **其他数据差异问题** - 未涉及

### 📊 总体评分：8.5/10

**优点**：
- 快速解决了关键的空表问题
- 修正了路由和加载状态逻辑
- 代码质量良好

**不足**：
- 快照预热根本问题未解决
- 数据差异根因未深入
- 测试覆盖不足

**建议**：
- 立即内网验证本轮修复效果
- 根据验证结果决定是否需要第三轮修复
- 优先解决快照预热和数据差异根因
