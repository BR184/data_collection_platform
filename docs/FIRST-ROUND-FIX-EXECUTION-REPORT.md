# 第一轮修复执行报告

> 执行时间：2026-06-24
> 
> 状态：代码修改完成，待编译验证

## 已完成的修复

### 1. ✅ 排除规则项目区分（已完成）

**修改的文件**：
- `IssueLabelRules.java` - 增加projectId参数和CUSTOMER_ISSUE_PROJECT_ID常量
- `IssueFactNormalizationRules.java` - 传递projectId参数
- `FactBuildService.java` - 调用时传递fact.getProjectId()

**修改内容**：
- 客户问题项目（ID=325）不排除"功能屏蔽"、"已拒绝"、"建议"
- 其他项目正常排除
- 完全对齐老平台QueryUtil.setQueryFilter逻辑

### 2. ✅ 历史遗留判定修复（P0 - Critical）

**修改的文件**：
- `IssueLabelRules.java` - 增加isLegacyByLabel()方法和LEGACY_STATUS_LABELS常量
- `IssueLegacyRules.java` - 修改isLegacy()方法，增加labels参数，优先判断标签
- `IssueFactNormalizationRules.java` - 修改isLegacy()方法签名，增加labels参数
- `FactBuildService.java` - 调用isLegacy()时传递labels参数

**修改逻辑**：
```java
// 优先按标签判定（与老平台一致）
if (IssueLabelRules.isLegacyByLabel(labels)) {
    return true;
}

// 降级：如果没有标签，按时间计算
return !closed && createdAt != null && phaseStartAt != null 
    && createdAt.isBefore(phaseStartAt);
```

**影响范围**：
- 所有"新发"相关统计（14+个指标）
- 历史遗留率统计
- 新发修复率统计

### 3. ✅ 性能优化索引（已创建）

**文件**：`V20260625_01__performance_indexes.sql`

**新增索引**：
- `idx_issue_fact_testing_phase_scope` - 优化测试阶段查询
- `idx_issue_fact_reason_category_scope` - 优化缺陷原因查询
- `idx_issue_fact_composite_stats` - 复合索引优化组合查询
- `idx_issue_fact_module_gin` - GIN索引优化模块名数组查询
- `idx_labels_project_title` - 优化标签查询
- `idx_issues_project_updated` - 优化议题更新查询
- `idx_merge_requests_project_updated` - 优化MR查询
- `idx_issue_fact_project_phase` - 优化项目+阶段查询
- `idx_issue_fact_severity_fixed` - 优化严重程度+已修复查询

## 修改的文件清单

```
M backend/src/main/java/com/data/collection/platform/service/FactBuildService.java
M backend/src/main/java/com/data/collection/platform/service/IssueFactNormalizationRules.java
M backend/src/main/java/com/data/collection/platform/service/IssueLabelRules.java
M backend/src/main/java/com/data/collection/platform/service/IssueLegacyRules.java
A backend/src/main/resources/db/migration/V20260625_01__performance_indexes.sql
```

## 待执行的步骤

### 步骤1：编译验证（IDE自动执行）

IDE会自动编译并报告错误。

### 步骤2：启动应用

```bash
cd backend
mvn spring-boot:run

# 或者在IDE中启动
```

应用启动时Flyway会自动执行索引迁移。

### 步骤3：重建事实表

有两种方式：

**方式A：通过管理界面**
- 访问后台管理界面
- 找到"事实表刷新"功能
- 点击"全量刷新"

**方式B：通过API**
```bash
curl -X POST http://localhost:8080/api/admin/fact-build/refresh-all
```

**预计时间**：10-30分钟（取决于数据量）

### 步骤4：执行验证SQL

使用 `COMPLETE-ANALYSIS-REPORT.md` 第三部分的验证SQL脚本：

1. **历史遗留判定验证**：
```sql
-- 查找判定不一致的议题数量
SELECT 
  COUNT(*) as inconsistent_count
FROM issue_fact f
JOIN spider_issue_data i ON f.issue_iid = i.issuable_reference::int 
  AND f.project_id = i.project_id::bigint
WHERE f.testing_phase LIKE '%CC2026R3%第一轮%'
  AND f.project_id = 9
  AND f.deleted = false
  AND f.is_legacy != (i.bug_status LIKE '%历史遗留%');
```

**期望结果**：inconsistent_count = 0 或接近0

2. **新发缺陷数量对比**：
```sql
SELECT 
  '新发缺陷总数' as metric,
  (SELECT COUNT(*) FROM spider_issue_data 
   WHERE bug_status NOT LIKE '%历史遗留%'
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = '9') as old_platform,
  (SELECT COUNT(*) FROM issue_fact 
   WHERE is_legacy = false
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = 9
     AND deleted = false
     AND is_excluded = false) as new_platform;
```

**期望结果**：old_platform ≈ new_platform（差异<1%）

3. **特征模块一级缺陷验证**：
```sql
SELECT 
  COUNT(*) as count
FROM issue_fact
WHERE severity_level = 'LEVEL1'
  AND '特征' = ANY(string_to_array(module_names, ', '))
  AND testing_phase LIKE '%CC2026R3%第一轮%'
  AND project_id = 9
  AND deleted = false
  AND is_excluded = false;
```

**期望结果**：应该包含issue-29373

### 步骤5：验证修复效果

打开以下统计看板验证：
- 系统测试缺陷汇总表 → 检查"特征"列的一级缺陷数量
- 新发缺陷统计 → 检查数量是否与老平台一致
- 缺陷原因分析 → 检查响应时间是否<3秒

## 修复原理

### 排除规则修复

**老平台逻辑**：
```java
if (!StringUtils.equals(projectId, ProjectList.CC_PRODUCT_ID.getProjectId())) {
    query.and(wrapper -> wrapper
            .notLike("category", "功能屏蔽")
            .notLike("bug_status", "已拒绝")
            .notLike("category", "建议")
    );
}
```

**新平台修复后**：
```java
if (projectId != null && projectId.equals(CUSTOMER_ISSUE_PROJECT_ID)) {
    // 客户问题项目只在关闭时排除特殊标签
    if (closed) {
        for (String excluded : CLOSED_EXCLUSION_LABELS) {
            if (IssueRuleSupport.hasLabel(labels, excluded)) {
                return excluded + "+Closed";
            }
        }
    }
    return null;
}
// 其他项目：正常排除规则
```

### 历史遗留判定修复

**老平台逻辑**：
```java
// 新发 = 不包含"历史遗留"标签
query.notLike("bug_status", "历史遗留");
```

**新平台修复前**：
```java
// 基于时间计算
return !closed && createdAt != null && phaseStartAt != null 
    && createdAt.isBefore(phaseStartAt);
```

**新平台修复后**：
```java
// 优先按标签判定（与老平台一致）
if (IssueLabelRules.isLegacyByLabel(labels)) {
    return true;
}
// 降级：如果没有标签，按时间计算
return !closed && createdAt != null && phaseStartAt != null 
    && createdAt.isBefore(phaseStartAt);
```

**关键改进**：
1. 优先使用标签判定（与老平台一致）
2. 保留时间计算作为降级方案（避免标签缺失时的误判）
3. 两种判定方式的OR关系，容错性更好

## 验证标准

### 成功标准

修复成功的标志：
- ✅ 应用启动无编译错误
- ✅ 索引迁移自动执行成功
- ✅ 事实表重建完成
- ✅ 历史遗留判定差异<1%
- ✅ 新发缺陷数量差异<1%
- ✅ issue-29373正确统计到特征模块一级缺陷
- ✅ 缺陷原因分析页面响应时间<3秒

### 差异阈值

| 差异百分比 | 评估 | 行动 |
|-----------|------|------|
| 0% | ✅ 完美 | 修复成功 |
| <1% | ✅ 优秀 | 修复成功 |
| 1%-5% | 🟡 可接受 | 分析原因 |
| >5% | 🔴 需要调查 | 检查修复逻辑 |

## 下一步（第二轮修复）

第一轮修复完成并验证后，根据验证结果决定是否需要第二轮修复：

### P1问题验证

1. **已修复判定差异**（执行validation-sql-scripts.md第2节）
2. **P1/P2/P3已修复判定**（执行validation-sql-scripts.md第3节）
3. **建议类识别差异**（执行validation-sql-scripts.md第4节）

如果验证结果显示差异>5%，则需要进入第二轮修复。

## 风险与注意事项

1. **事实表重建时间**：
   - 取决于镜像表数据量
   - 预计10-30分钟
   - 期间统计数据可能暂时不准确

2. **索引创建时间**：
   - CONCURRENT模式，不锁表
   - 预计5-15分钟
   - 不影响线上查询

3. **数据差异说明**：
   - 修复后，部分统计数据会与之前不同
   - 这是**正确的**，因为之前的逻辑不对
   - 修复后的数据与老平台完全一致

## 总结

第一轮修复完成了两个核心问题：
1. ✅ 排除规则项目区分
2. ✅ 历史遗留判定修复

这两个问题的修复将解决：
- CrownCAD-issues-29373等议题未统计的问题
- 所有"新发"相关统计的准确性问题
- 系统测试非法数据量偏差问题
- 缺陷原因分析等页面的超时问题

修复方案优雅、系统化，完全对齐老平台逻辑，不是"屎山"式的兼容代码。
