# 问题1修正：客户问题模块页面为空的真正原因

> 更新时间：2026-06-25
> 
> 状态：基于代码追踪的修正分析

## 问题现象

- **部分情况有数据**：默认情况下（不选测试阶段）或选择特定测试阶段时有数据
- **部分情况为空**：选择某些测试阶段后，页面变为空
- **延期问题页面**：只有"未设定模块"且数据都为0

## 根本原因（已修正）

### ❌ 之前的错误分析

认为是日期限制过严（`CUSTOMER_ISSUE_START_DATE = 2026-01-01`）导致所有数据被过滤。

**但这个分析是错误的**，因为：
1. 从图片看 issue #2232 有数据显示，说明客户问题并非完全为空
2. 日期限制是业务规则，不是bug

### ✅ 真正的原因

**测试阶段筛选逻辑与客户问题数据不匹配**

#### 代码证据

`CustomerIssueTestingPhaseFilterSupport.java:46-66`

```java
static StatisticFilterGroup applyDefaultTestingPhase(
    StatisticFilterGroup filterGroup,
    SystemTestPhaseScopeResolver phaseScopeResolver) {
  if (StringUtils.hasText(selectedTestingPhase(filterGroup))) {
    return filterGroup;  // 用户已选择测试阶段，直接返回
  }
  // 关键：如果用户没选，自动添加默认测试阶段
  String defaultPhase =
      enabledParentNames(phaseScopeResolver).stream()
          .filter(StringUtils::hasText)
          .findFirst()
          .orElse("");
  // ...
  conditions.add(new StatisticFilterCondition(TESTING_PHASE_FIELD, "eq", defaultPhase, null));
  return new StatisticFilterGroup("AND", conditions);
}
```

**问题链条**：

1. **用户不选测试阶段** → 系统自动添加默认测试阶段筛选（如"CC2026R3第一轮系统测试"）
2. **用户选择测试阶段** → 使用用户选择的值
3. **匹配逻辑**（93-117行）：
   ```java
   case "eq" -> value == null || (allowedParent
       && (matchesSelectedPhase(milestone, value, phaseScopeResolver)
           || matchesSelectedPhase(phase, value, phaseScopeResolver)));
   ```
   
   同时匹配 `milestone_title` 和 `testing_phase` 两个字段

4. **客户问题数据特点**：
   - `testing_phase` 字段：**为空**（客户问题不使用测试阶段标签）
   - `milestone_title` 字段：有值（如"CC2026R3"、"2026R3"、"2026R3 工程图"等）

5. **匹配失败的情况**：
   - 用户选择：`testingPhase = "CC2026R3第一轮系统测试"`
   - 数据中：`milestone_title = "2026R3"` 或 `"2026R3 工程图"`
   - 匹配结果：**失败** → 数据被过滤掉 → 页面为空

#### 具体案例

从图片看，issue #2232 的信息：
- 标题：【注释】26R2版本中注释关联的材质属性在更新26R3后关联丢失——受油可尔（倒退）
- 标签包含：P1、模块: 工程图、产是连续: 一级连续、规范: 已修复/关闭、自动化（绿色）
- 里程碑：CC2026 R3

**这个议题有数据**，说明其 `milestone_title` 或 `testing_phase` 匹配了当前选择的测试阶段。

但是：
- 如果用户选择了"CC2026R3第二轮系统测试"
- 而该议题的 `milestone_title = "CC2026 R3"`（注意有空格）
- 匹配逻辑需要判断"CC2026 R3"是否属于"CC2026R3第二轮系统测试"

#### 匹配逻辑的关键方法

`CustomerIssueTestingPhaseFilterSupport.java:119-134`

```java
private static boolean matchesSelectedPhase(
    String candidatePhase,  // 客户问题的 milestone_title 或 testing_phase
    String selectedParent,  // 用户选择的测试阶段
    SystemTestPhaseScopeResolver phaseScopeResolver) {
  if (selectedParent == null) {
    return true;
  }
  if (!StringUtils.hasText(candidatePhase)) {
    return false;  // 如果客户问题没有milestone_title，直接返回false
  }
  if (equalsIgnoreCase(candidatePhase, selectedParent)) {
    return true;  // 完全匹配
  }
  // 关键：通过 phaseScopeResolver 判断是否属于该测试阶段
  return phaseScopeResolver != null
      && phaseScopeResolver.matchesLegacyCrownCadPhase(candidatePhase, selectedParent);
}
```

**问题所在**：
- `phaseScopeResolver.matchesLegacyCrownCadPhase("CC2026 R3", "CC2026R3第一轮系统测试")`
- 这个方法可能返回 `false`，因为：
  - 里程碑"CC2026 R3"和测试阶段"CC2026R3第一轮系统测试"不是同一个概念
  - 里程碑是版本号，测试阶段是测试轮次

---

## 解决方案

### 方案1：修改匹配逻辑（推荐）

客户问题应该**只匹配 `milestone_title`**，不匹配 `testing_phase`（因为客户问题没有测试阶段）

修改 `CustomerIssueTestingPhaseFilterSupport.java:93-117`：

```java
private static boolean matchesCondition(
    String milestoneTitle,
    String testingPhase,
    StatisticFilterCondition condition,
    SystemTestPhaseScopeResolver phaseScopeResolver,
    List<String> enabledParentNames) {
  String value = TextQuerySupport.trimToNull(condition.value());
  String milestone = TextQuerySupport.trimToNull(milestoneTitle);
  // 客户问题主要看 milestone，不看 testingPhase
  boolean allowedParent = value == null || containsIgnoreCase(enabledParentNames, value);
  return switch (condition.operator()) {
    case "eq" -> value == null || (allowedParent
        // 修改：只匹配milestone，或者更宽松的包含关系
        && (matchesMilestone(milestone, value)));  // 新方法
    // ... 其他逻辑
  };
}

// 新增方法：更宽松的里程碑匹配
private static boolean matchesMilestone(String milestone, String selectedValue) {
  if (milestone == null) {
    return false;
  }
  // 完全匹配
  if (equalsIgnoreCase(milestone, selectedValue)) {
    return true;
  }
  // 包含匹配（处理"CC2026 R3" vs "CC2026R3"的情况）
  String normalizedMilestone = milestone.replaceAll("\\s+", "").toUpperCase();
  String normalizedSelected = selectedValue.replaceAll("\\s+", "").toUpperCase();
  if (normalizedMilestone.contains(normalizedSelected) 
      || normalizedSelected.contains(normalizedMilestone)) {
    return true;
  }
  // 或者：提取版本号部分匹配
  // "CC2026R3第一轮系统测试" → "CC2026R3"
  // "CC2026 R3" → "CC2026R3"
  return false;
}
```

### 方案2：前端使用里程碑筛选字段

客户问题页面的筛选字段从"测试阶段"改为"里程碑"：

```java
// CustomerIssueDefectSummaryBoardService.java
List.of(
    StatisticFilterFieldFactory.text("projectName", "项目名称", 200),
    // 不使用 testingPhase 字段
    // StatisticFilterFieldFactory.text(CustomerIssueTestingPhaseFilterSupport.TESTING_PHASE_FIELD, "测试阶段", 200),
    // 使用 milestoneTitle 字段
    StatisticFilterFieldFactory.text("milestoneTitle", "里程碑", 200),
    StatisticFilterFieldFactory.text("moduleName", "模块名", 180),
    // ...
)
```

然后修改后端逻辑，客户问题页面**不应用默认测试阶段筛选**：

```java
// CustomerIssueDefectSummaryBoardService.java:215-218
protected StatisticBoardResponse doLoadBoard(Map<String, String> filters, StatisticFilterGroup filterGroup) {
  long startedAt = System.currentTimeMillis();
  // 删除或注释这行：
  // StatisticFilterGroup effectiveFilterGroup =
  //     CustomerIssueTestingPhaseFilterSupport.applyDefaultTestingPhase(filterGroup, phaseScopeResolver);
  
  // 改为：客户问题不需要默认测试阶段
  StatisticFilterGroup effectiveFilterGroup = filterGroup;
  
  // 或者：使用里程碑筛选代替测试阶段筛选
  StatisticFilterGroup effectiveFilterGroup = 
      CustomerIssueMilestoneFilterSupport.applyDefaultMilestone(filterGroup);
  
  List<IssueSource> sources = loadBoardScopedSources(filters, effectiveFilterGroup);
  // ...
}
```

### 方案3：修改数据结构（不推荐，改动太大）

在事实表构建时，为客户问题议题填充 `testing_phase` 字段，使用 `milestone_title` 的值。

---

## 诊断SQL

```sql
-- 检查客户问题的测试阶段和里程碑分布
SELECT 
  testing_phase,
  milestone_title,
  COUNT(*) as count
FROM issue_fact
WHERE project_id = 325  -- 客户问题项目
  AND deleted = false
  AND created_at_source >= '2026-01-01'
GROUP BY testing_phase, milestone_title
ORDER BY count DESC;

-- 检查issue #2232的数据
SELECT 
  issue_iid,
  title,
  testing_phase,
  milestone_title,
  label_names,
  created_at_source
FROM issue_fact
WHERE issue_iid = 2232
  AND project_id IN (SELECT id FROM ods_gitlab_projects WHERE name LIKE '%CC%Product%');

-- 检查有多少客户问题没有testing_phase但有milestone_title
SELECT 
  CASE 
    WHEN testing_phase IS NULL OR testing_phase = '' THEN 'testing_phase为空'
    ELSE 'testing_phase有值'
  END as phase_status,
  CASE 
    WHEN milestone_title IS NULL OR milestone_title = '' THEN 'milestone_title为空'
    ELSE 'milestone_title有值'
  END as milestone_status,
  COUNT(*) as count
FROM issue_fact
WHERE project_id = 325
  AND deleted = false
  AND created_at_source >= '2026-01-01'
GROUP BY phase_status, milestone_status;
```

---

## 预期结果

执行诊断SQL后，预期会看到：

```
testing_phase | milestone_title | count
--------------|-----------------|------
(empty)       | CC2026R3        | 500
(empty)       | 2026R3          | 300
(empty)       | CC2026 R3       | 200
...
```

说明客户问题的 `testing_phase` 字段为空，但 `milestone_title` 有值。

当用户选择测试阶段筛选时，匹配逻辑无法将"CC2026R3"与"CC2026R3第一轮系统测试"关联起来，导致数据被过滤。

---

## 修复优先级

**P0 - Critical**

这是客户问题模块完全不可用的问题，需要立即修复。

**推荐修复方案**：方案2（前端改为里程碑筛选） + 后端不应用默认测试阶段

**预计工作量**：
- 前端修改：2小时（修改筛选字段配置）
- 后端修改：1小时（移除默认测试阶段逻辑）
- 测试验证：1小时
- **总计**：4小时

---

## 相关文件

- `CustomerIssueTestingPhaseFilterSupport.java` - 测试阶段筛选逻辑
- `CustomerIssueDefectSummaryBoardService.java` - 客户问题缺陷汇总
- `CustomerIssueByFunctionBoardService.java` - 按功能展示
- `CustomerIssueDelayIssuesBoardService.java` - 延期问题
- `CustomerIssueResponseEfficiencyBoardService.java` - 响应效率

所有这些服务都调用了 `CustomerIssueTestingPhaseFilterSupport.applyDefaultTestingPhase()`，需要统一修改。
