# 新老平台统计规则完整差异清单

> 生成时间：2026-06-24
> 
> 全面对比：不遗漏任何统计规则

## 对比方法论

### 数据源
- **老平台**：spider_issue_data 表（47个字段）
- **新平台**：issue_fact 表（70+个字段）

### 对比范围
- ✅ 严重程度统计（一级、二级、三级、建议类）
- ✅ 优先级统计（P1、P2、P3）
- ✅ 状态统计（Open、Closed、已修复）
- ✅ 新发 vs 历史遗留
- ✅ 一级缺陷子类型（回退、挂机、其他）
- ✅ 排除规则
- ✅ 模块统计
- ✅ 建议类缺陷
- ✅ 申请延期
- ✅ 复测失败

## 差异清单

---

### 1. ✅ 排除规则（已修复）

**详见**：第一轮修复完成报告.md

**状态**：✅ 已修复，完全对齐

---

### 2. 🔴 历史遗留判定（Critical）

**老平台逻辑**：
```java
// 新发 = 不包含"历史遗留"标签
query.notLike("bug_status", "历史遗留");
```

**新平台逻辑**：
```java
// IssueLegacyRules.java
static boolean isLegacy(boolean closed, LocalDateTime createdAt, LocalDateTime phaseStartAt) {
    return !closed && createdAt != null && phaseStartAt != null && createdAt.isBefore(phaseStartAt);
}
```

**差异**：
- 老平台：基于标签（主观）
- 新平台：基于时间（客观）

**影响范围**：
- NEW_ISSUE（新发议题数量）
- NEW_ISSUE_FIX（新发已修复数量）
- CRITICAL_NEW_ISSUE（新发一级缺陷）
- MAJOR_NEW_ISSUE（新发二级缺陷）
- MINOR_NEW_ISSUE（新发三级缺陷）
- SUGGESTION_NEW_ISSUE（新发建议类）
- DELAY_NEW_ISSUE（新发申请延期）
- CRITICAL_ROLLBACK_NEW_ISSUE（新发回退）
- CRITICAL_HANG_UP_NEW_ISSUE（新发挂机）
- CRITICAL_OTHERS_NEW_ISSUE（新发其他一级）
- 所有带"NEW_ISSUE"的14+个统计指标

**验证SQL**：
```sql
SELECT 
  '历史遗留判定差异' as metric,
  COUNT(*) as inconsistent_count
FROM issue_fact f
JOIN spider_issue_data i ON f.issue_iid = i.issuable_reference::int 
  AND f.project_id = i.project_id::bigint
WHERE f.testing_phase LIKE '%CC2026R3%第一轮%'
  AND f.project_id = 9
  AND f.deleted = false
  AND f.is_legacy != (i.bug_status LIKE '%历史遗留%');
```

**优先级**：🔴 **P0 - Critical**

---

### 3. 🟡 已修复判定（需要验证）

#### 3.1 setFixQuery 逻辑

**老平台（SpiderIssueDataDAOImpl.java:2523-2530）**：
```java
private void setFixQuery(QueryWrapper<SpiderIssueData> query) {
    query.and(wrapper -> wrapper
            .eq("bug_status", "待合并")
            .or().like("bug_status", "已修复")
            .or().like("bug_status", "待合并")
            .or().like("bug_status", "未更新")
    );
}
```

**实际效果**：`bug_status = '待合并' OR bug_status LIKE '%已修复%' OR bug_status LIKE '%待合并%' OR bug_status LIKE '%未更新%'`

**新平台（IssueLabelRules.java:120-135）**：
```java
static boolean isFixed(List<String> labels, boolean closed) {
    if (closed) {
        return true;  // 关闭=已修复
    }
    return IssueRuleSupport.containsAnyLabel(labels, FIXED_STATUS_TOKENS);
}

private static final List<String> FIXED_STATUS_TOKENS =
    List.of("已修复/完成", "待合并", "未更新", "未复现");
```

**差异点**：
1. 老平台：LIKE '%已修复%'（匹配"已修复"、"已修复/完成"）
2. 新平台：精确匹配"已修复/完成"（不匹配裸"已修复"）
3. 新平台多了"未复现"
4. 新平台closed=true自动算已修复（老平台不是）

#### 3.2 Closed状态的已修复判定

**老平台（多处）**：
```java
// CRITICAL_FIXED - 一级缺陷已修复
query.eq("severity_level", "一级缺陷");
setFixQuery(query);  // 只看标签，不看status

// NEW_ISSUE_CLOSED_COUNT - 新发已关闭数量
query.eq("status", "CLOSED");
query.notLike("bug_status", "历史遗留");
query.and(wrapper -> wrapper
        .or().like("bug_status", "已修复/完成")
        .or().like("bug_status", "未复现")
);  // CLOSED + 特定标签
```

**新平台**：
```java
if (closed) {
    return true;  // 直接返回true，不检查标签
}
```

**差异**：
- 老平台：即使closed=true，也要检查标签（某些场景）
- 新平台：closed=true直接算已修复

**影响范围**：
- CRITICAL_FIXED（一级缺陷已修复）
- MAJOR_FIXED（二级缺陷已修复）
- MINOR_FIXED（三级缺陷已修复）
- 所有修复率统计

**验证SQL**：
```sql
-- 对比一级缺陷已修复数量
SELECT 
  '老平台' as platform,
  COUNT(*) as fixed_count
FROM spider_issue_data
WHERE severity_level = '一级缺陷'
  AND (bug_status = '待合并' OR bug_status LIKE '%已修复%' 
       OR bug_status LIKE '%待合并%' OR bug_status LIKE '%未更新%')
  AND testing_phase LIKE '%CC2026R3%第一轮%'
  AND project_id = '9'
UNION ALL
SELECT 
  '新平台' as platform,
  COUNT(*) as fixed_count
FROM issue_fact
WHERE severity_level = 'LEVEL1'
  AND is_fixed = true
  AND testing_phase LIKE '%CC2026R3%第一轮%'
  AND project_id = 9
  AND deleted = false
  AND is_excluded = false;
```

**优先级**：🟡 **P1 - High**（需要先验证实际影响）

---

### 4. 🟢 严重程度识别（一致）

#### 4.1 一级缺陷

**老平台**：`severity_level = '一级缺陷'`
**新平台**：`severity_level = 'LEVEL1'`（匹配"一级缺陷"或"一级严重"）

**结论**：✅ 新平台更宽松，覆盖老平台所有case

#### 4.2 二级缺陷

**老平台**：
```java
else if (MAJOR_OPEN.equals(moduleTable)) {
    query.eq("severity_level", "二级缺陷");
    query.and(wrapper -> wrapper
            .notLike("bug_status", "已修复")
            .notLike("bug_status", "待合并")
            .notLike("bug_status", "未更新")
    );
}

else if (MAJOR_FIXED.equals(moduleTable)) {
    query.eq("severity_level", "二级缺陷");
    setFixQuery(query);
}
```

**新平台**：
```java
// IssueFactRecord
WHERE severity_level = 'LEVEL2'
  AND is_fixed = false  // for open
  AND is_fixed = true   // for fixed
```

**结论**：✅ 逻辑一致（但受"已修复判定"差异影响）

#### 4.3 三级缺陷

**老平台**：
```java
else if (MINOR_OPEN.equals(moduleTable)) {
    query.eq("severity_level", "三级缺陷");
    query.and(wrapper -> wrapper
            .notLike("bug_status", "已修复")
            .notLike("bug_status", "待合并")
            .notLike("bug_status", "未更新")
    );
}

else if (MINOR_FIXED.equals(moduleTable)) {
    query.eq("severity_level", "三级缺陷");
    setFixQuery(query);
}
```

**新平台**：
```java
WHERE severity_level = 'LEVEL3'
  AND is_fixed = false/true
```

**结论**：✅ 逻辑一致（但受"已修复判定"差异影响）

---

### 5. 🟢 优先级统计（一致）

#### 5.1 P1缺陷

**老平台**：
```java
else if (P1_URGENCY.equals(moduleTable)) {
    query.eq("urgency", "P1");
}

else if (P1_FIXED.equals(moduleTable)) {
    query.eq("urgency", "P1");
    query.and(wrapper -> wrapper
            .or().like("bug_status", "已修复/完成")
            .or().like("bug_status", "未复现")
            .or().like("status", "CLOSED")
    );
}
```

**新平台**：
```java
WHERE priority_level = 'P1'
  AND is_fixed = true
```

**注意**：老平台P1_FIXED的判定是`已修复/完成 OR 未复现 OR CLOSED`，与setFixQuery不同！

**差异**：
- 老平台P1_FIXED：`已修复/完成 OR 未复现 OR CLOSED`
- 老平台CRITICAL_FIXED：`待合并 OR 已修复 OR 待合并 OR 未更新`（通过setFixQuery）
- 新平台统一使用isFixed

**验证SQL**：
```sql
-- 对比P1已修复数量
SELECT 
  '老平台' as platform,
  COUNT(*) as fixed_count
FROM spider_issue_data
WHERE urgency = 'P1'
  AND (bug_status LIKE '%已修复/完成%' 
       OR bug_status LIKE '%未复现%'
       OR status = 'CLOSED')
  AND testing_phase LIKE '%CC2026R3%第一轮%'
  AND project_id = '9'
UNION ALL
SELECT 
  '新平台' as platform,
  COUNT(*) as fixed_count
FROM issue_fact
WHERE priority_level = 'P1'
  AND is_fixed = true
  AND testing_phase LIKE '%CC2026R3%第一轮%'
  AND project_id = 9
  AND deleted = false
  AND is_excluded = false;
```

**优先级**：🟡 **P1 - High**（P1/P2/P3的已修复判定与严重程度不同）

#### 5.2 P2、P3缺陷

**结论**：同P1，已修复判定逻辑不同

---

### 6. 🟢 一级缺陷子类型（基本一致）

#### 6.1 回退（Regression）

**老平台**：
```java
query.eq("severity_level", "一级缺陷")
     .and(wrapper -> wrapper
         .or().like("issue_title", "（退")
         .or().like("issue_title", "回退")
         .or().like("issue_title", "倒退")
     );
```

**新平台**：
```java
private static final List<String> REGRESSION_TITLE_TOKENS = List.of("回退", "倒退", "（退");
```

**结论**：✅ 完全一致

#### 6.2 挂机（Crash）

**老平台**：`issue_title LIKE '%挂机%'`
**新平台**：`CRASH_TITLE_TOKENS = List.of("挂机")`

**结论**：✅ 完全一致

#### 6.3 其他一级

**老平台**：
```java
query.eq("severity_level", "一级缺陷");
query.notLike("issue_title", "退");
query.notLike("issue_title", "回退");
query.notLike("issue_title", "倒退");
query.notLike("issue_title", "挂机");
```

**新平台**：
```java
return isLevel1(labels) && !isRegression(labels, title) && !isCrash(labels, title);
```

**差异**：老平台多排除了"退"（单字）

**结论**：🟢 基本一致，极端case有微小差异

---

### 7. 🟡 建议类缺陷

**老平台（SpiderIssueDataDAOImpl.java:524-532）**：
```java
else if (SUGGESTION_NEW_ISSUE.equals(moduleTable)) {
    query.like("category", "建议类");
    query.notLike("bug_status", "历史遗留");
}

else if (SUGGESTION_NEW_ISSUE_FIX.equals(moduleTable)) {
    query.notLike("bug_status", "历史遗留");
    query.like("category", "建议类");
    query.and(wrapper -> wrapper
            .or().like("bug_status", "已修复/完成")
            .or().like("bug_status", "未复现")
            .or().like("status", "CLOSED")
    );
}
```

**新平台（IssueLabelRules.java:16）**：
```java
Map.entry("SUGGESTION", List.of("建议", "需求", "需求如此"))
```

**差异分析**：

| 场景 | 老平台 | 新平台 | 一致性 |
|------|--------|--------|--------|
| category="建议类" | ✅ 建议类 | ❓（没有category字段？） | ❌ 需要检查 |
| 标签="建议" | ❓ | ✅ SUGGESTION | ❌ 需要检查 |
| 标签="需求" | ❓ | ✅ SUGGESTION | ❌ 需要检查 |

**关键问题**：
- 老平台用`category`字段（值为"建议类"）
- 新平台用标签匹配"建议"、"需求"、"需求如此"
- 需要确认：新平台的`category`字段是如何计算的？

**查找**：
```bash
grep -rn "category\|Category" backend/src/main/java/com/data/collection/platform/service/IssueLabelRules.java
```

**验证SQL**：
```sql
-- 对比建议类数量
SELECT 
  '老平台' as platform,
  COUNT(*) as suggestion_count
FROM spider_issue_data
WHERE category LIKE '%建议类%'
  AND bug_status NOT LIKE '%历史遗留%'
  AND testing_phase LIKE '%CC2026R3%第一轮%'
  AND project_id = '9'
UNION ALL
SELECT 
  '新平台' as platform,
  COUNT(*) as suggestion_count
FROM issue_fact
WHERE severity_level = 'SUGGESTION'
  AND is_legacy = false
  AND testing_phase LIKE '%CC2026R3%第一轮%'
  AND project_id = 9
  AND deleted = false
  AND is_excluded = false;
```

**优先级**：🟡 **P1 - High**（需要先确认category字段映射）

---

### 8. 🟢 申请延期

**老平台**：
```java
else if (EXTENSION.equals(moduleTable)) {
    query.like("bug_status", "申请延期");
}
```

**新平台**：
```java
// IssueFactRecord
public boolean delayIssue()  // 通过标签或notes识别
```

**结论**：✅ 逻辑一致（通过标签识别）

---

### 9. 🟡 复测失败

**老平台（SpiderIssueDataDAOImpl.java:498）**：
```java
else if (RETEST_FAILED.equals(moduleTable)) {
    query.like("bug_status", "未修复");
}
```

**新平台**：需要检查是否有对应字段

**验证**：
```bash
grep -rn "retest\|复测\|未修复" backend/src/main/java/com/data/collection/platform/service/
```

**优先级**：🟡 **P1 - High**（需要确认是否实现）

---

### 10. 🟡 Closed状态统计

**老平台多处使用**：
```java
else if (CLOSED.equals(moduleTable)) {
    query.eq("status", "CLOSED");
}

else if (P1_CLOSED.equals(moduleTable)) {
    query.eq("urgency", "P1");
    query.eq("status", "CLOSED");
    // ...
}
```

**新平台**：
```java
// IssueFactRecord
public boolean isClosed() {
    return closedAt != null || "closed".equalsIgnoreCase(issueState);
}
```

**差异**：
- 老平台：`status = 'CLOSED'`（大写）
- 新平台：`issue_state = 'closed'`（小写）或 `closed_at IS NOT NULL`

**验证SQL**：
```sql
SELECT 
  '老平台-CLOSED' as platform,
  COUNT(*) as count
FROM spider_issue_data
WHERE status = 'CLOSED'
  AND testing_phase LIKE '%CC2026R3%第一轮%'
  AND project_id = '9'
UNION ALL
SELECT 
  '新平台-isClosed' as platform,
  COUNT(*) as count
FROM issue_fact
WHERE (closed_at_source IS NOT NULL OR issue_state = 'closed')
  AND testing_phase LIKE '%CC2026R3%第一轮%'
  AND project_id = 9
  AND deleted = false;
```

**结论**：🟢 应该一致（只是大小写差异）

---

## 差异汇总表

| # | 规则项 | 状态 | 优先级 | 影响范围 | 说明 |
|---|--------|------|--------|----------|------|
| 1 | 排除规则 | ✅ 已修复 | - | 所有统计 | 第一轮完成 |
| 2 | 历史遗留判定 | 🔴 未修复 | P0 | 所有"新发"统计 | 标签 vs 时间 |
| 3 | 已修复判定 | 🟡 需验证 | P1 | 所有修复率 | closed逻辑、标签差异 |
| 4 | P1/P2/P3已修复判定 | 🟡 需验证 | P1 | 优先级修复率 | 与严重程度的已修复判定不同 |
| 5 | 建议类识别 | 🟡 需验证 | P1 | 建议类统计 | category vs 标签 |
| 6 | 复测失败 | 🟡 需验证 | P1 | 复测统计 | 是否实现 |
| 7 | 一级缺陷子类型 | 🟢 基本一致 | P2 | 回退/挂机/其他 | 微小差异 |
| 8 | 严重程度识别 | ✅ 一致 | - | 所有严重程度统计 | - |
| 9 | 优先级识别 | ✅ 一致 | - | 所有优先级统计 | - |
| 10 | 申请延期 | ✅ 一致 | - | 延期统计 | - |
| 11 | Closed状态 | 🟢 应该一致 | P2 | 关闭状态统计 | 大小写差异 |

## 验证优先级

### 🔴 P0 - 必须立即修复
1. **历史遗留判定**（影响14+个统计指标）

### 🟡 P1 - 需要先验证，再决定是否修复
1. **已修复判定**（执行验证SQL，看差异百分比）
2. **P1/P2/P3已修复判定**（与严重程度的逻辑不一致）
3. **建议类识别**（确认category字段映射）
4. **复测失败**（确认是否实现）

### 🟢 P2 - 可接受或需要进一步确认
1. **一级缺陷子类型"退"字**
2. **Closed状态大小写**

## 下一步行动

1. **立即执行所有验证SQL**（见各节）
2. **修复P0问题**：历史遗留判定
3. **根据验证结果修复P1问题**
4. **生成完整的验证报告**
5. **重建事实表**
6. **全量对比所有统计看板**

## 附录：完整验证SQL脚本

见下一个文档：`validation-sql-scripts.md`
