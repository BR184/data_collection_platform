# 新老平台统计规则验证SQL脚本

> 生成时间：2026-06-24
> 
> 用于全面验证新老平台统计数据一致性

## 使用说明

1. **执行环境**：需要同时访问老平台和新平台数据库
2. **测试阶段**：CC2026R3第一轮（可根据实际情况修改）
3. **项目ID**：老平台='9'，新平台=9
4. **执行顺序**：按优先级从高到低执行

## P0 - 历史遗留判定差异验证

### 1.1 统计判定不一致的议题数量

```sql
-- 查找新老平台对"历史遗留"判定不一致的议题
WITH inconsistent AS (
  SELECT 
    f.issue_iid,
    f.title,
    f.is_legacy as new_platform_legacy,
    (i.bug_status LIKE '%历史遗留%') as old_platform_legacy,
    i.bug_status,
    f.created_at_source,
    i.created_at
  FROM issue_fact f
  JOIN spider_issue_data i ON f.issue_iid = i.issuable_reference::int 
    AND f.project_id = i.project_id::bigint
  WHERE f.testing_phase LIKE '%CC2026R3%第一轮%'
    AND f.project_id = 9
    AND f.deleted = false
    AND f.is_legacy != (i.bug_status LIKE '%历史遗留%')
)
SELECT 
  '历史遗留判定差异' as metric,
  COUNT(*) as inconsistent_count,
  ROUND(COUNT(*) * 100.0 / NULLIF((
    SELECT COUNT(*) FROM issue_fact 
    WHERE testing_phase LIKE '%CC2026R3%第一轮%' 
      AND project_id = 9 AND deleted = false
  ), 0), 2) as diff_percentage,
  SUM(CASE WHEN new_platform_legacy = true THEN 1 ELSE 0 END) as new_says_legacy,
  SUM(CASE WHEN old_platform_legacy = true THEN 1 ELSE 0 END) as old_says_legacy
FROM inconsistent;
```

### 1.2 列出不一致的具体议题（前20条）

```sql
SELECT 
  f.issue_iid,
  f.title,
  f.is_legacy as new_platform,
  (i.bug_status LIKE '%历史遗留%') as old_platform,
  i.bug_status,
  f.created_at_source::date as created_date,
  i.testing_phase
FROM issue_fact f
JOIN spider_issue_data i ON f.issue_iid = i.issuable_reference::int 
  AND f.project_id = i.project_id::bigint
WHERE f.testing_phase LIKE '%CC2026R3%第一轮%'
  AND f.project_id = 9
  AND f.deleted = false
  AND f.is_legacy != (i.bug_status LIKE '%历史遗留%')
ORDER BY f.issue_iid
LIMIT 20;
```

### 1.3 对比新发缺陷总数

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
     AND is_excluded = false) as new_platform,
  (SELECT COUNT(*) FROM issue_fact 
   WHERE is_legacy = false
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = 9
     AND deleted = false
     AND is_excluded = false) - 
  (SELECT COUNT(*) FROM spider_issue_data 
   WHERE bug_status NOT LIKE '%历史遗留%'
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = '9') as diff;
```

---

## P1 - 已修复判定差异验证

### 2.1 一级缺陷已修复数量对比

```sql
WITH old_critical_fixed AS (
  SELECT 
    issuable_reference::int as iid,
    severity_level,
    bug_status,
    status
  FROM spider_issue_data
  WHERE severity_level = '一级缺陷'
    AND (bug_status = '待合并' 
         OR bug_status LIKE '%已修复%' 
         OR bug_status LIKE '%待合并%' 
         OR bug_status LIKE '%未更新%')
    AND testing_phase LIKE '%CC2026R3%第一轮%'
    AND project_id = '9'
),
new_critical_fixed AS (
  SELECT 
    issue_iid as iid,
    severity_level,
    bug_status,
    issue_state
  FROM issue_fact
  WHERE severity_level = 'LEVEL1'
    AND is_fixed = true
    AND testing_phase LIKE '%CC2026R3%第一轮%'
    AND project_id = 9
    AND deleted = false
    AND is_excluded = false
)
SELECT 
  '一级缺陷已修复' as metric,
  (SELECT COUNT(*) FROM old_critical_fixed) as old_platform,
  (SELECT COUNT(*) FROM new_critical_fixed) as new_platform,
  ABS((SELECT COUNT(*) FROM old_critical_fixed) - 
      (SELECT COUNT(*) FROM new_critical_fixed)) as diff,
  ROUND(ABS((SELECT COUNT(*) FROM old_critical_fixed) - 
            (SELECT COUNT(*) FROM new_critical_fixed)) * 100.0 / 
        NULLIF((SELECT COUNT(*) FROM old_critical_fixed), 0), 2) as diff_percentage;
```

### 2.2 找出已修复判定不一致的议题

```sql
WITH old_fixed AS (
  SELECT issuable_reference::int as iid, bug_status
  FROM spider_issue_data
  WHERE severity_level = '一级缺陷'
    AND (bug_status = '待合并' OR bug_status LIKE '%已修复%' 
         OR bug_status LIKE '%待合并%' OR bug_status LIKE '%未更新%')
    AND testing_phase LIKE '%CC2026R3%第一轮%'
    AND project_id = '9'
),
new_fixed AS (
  SELECT issue_iid as iid, bug_status, issue_state, closed_at_source
  FROM issue_fact
  WHERE severity_level = 'LEVEL1'
    AND is_fixed = true
    AND testing_phase LIKE '%CC2026R3%第一轮%'
    AND project_id = 9
    AND deleted = false
    AND is_excluded = false
)
SELECT 
  COALESCE(o.iid, n.iid) as issue_iid,
  CASE WHEN o.iid IS NULL THEN '新平台多' 
       WHEN n.iid IS NULL THEN '老平台多'
       ELSE '都有' END as status,
  o.bug_status as old_bug_status,
  n.bug_status as new_bug_status,
  n.issue_state as new_issue_state,
  n.closed_at_source as new_closed_at
FROM old_fixed o
FULL OUTER JOIN new_fixed n ON o.iid = n.iid
WHERE o.iid IS NULL OR n.iid IS NULL
ORDER BY COALESCE(o.iid, n.iid)
LIMIT 50;
```

### 2.3 二级、三级缺陷已修复对比

```sql
SELECT 
  '二级缺陷已修复' as metric,
  (SELECT COUNT(*) FROM spider_issue_data 
   WHERE severity_level = '二级缺陷'
     AND (bug_status = '待合并' OR bug_status LIKE '%已修复%' 
          OR bug_status LIKE '%待合并%' OR bug_status LIKE '%未更新%')
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = '9') as old_platform,
  (SELECT COUNT(*) FROM issue_fact 
   WHERE severity_level = 'LEVEL2' AND is_fixed = true
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = 9 AND deleted = false AND is_excluded = false) as new_platform

UNION ALL

SELECT 
  '三级缺陷已修复' as metric,
  (SELECT COUNT(*) FROM spider_issue_data 
   WHERE severity_level = '三级缺陷'
     AND (bug_status = '待合并' OR bug_status LIKE '%已修复%' 
          OR bug_status LIKE '%待合并%' OR bug_status LIKE '%未更新%')
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = '9') as old_platform,
  (SELECT COUNT(*) FROM issue_fact 
   WHERE severity_level = 'LEVEL3' AND is_fixed = true
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = 9 AND deleted = false AND is_excluded = false) as new_platform;
```

---

## P1 - P1/P2/P3已修复判定验证

### 3.1 P1已修复数量对比

```sql
-- 老平台P1已修复判定：已修复/完成 OR 未复现 OR CLOSED
SELECT 
  'P1已修复' as metric,
  (SELECT COUNT(*) FROM spider_issue_data
   WHERE urgency = 'P1'
     AND (bug_status LIKE '%已修复/完成%' 
          OR bug_status LIKE '%未复现%'
          OR status = 'CLOSED')
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = '9') as old_platform,
  (SELECT COUNT(*) FROM issue_fact
   WHERE priority_level = 'P1' AND is_fixed = true
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = 9 AND deleted = false AND is_excluded = false) as new_platform,
  (SELECT COUNT(*) FROM issue_fact
   WHERE priority_level = 'P1' AND is_fixed = true
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = 9 AND deleted = false AND is_excluded = false) -
  (SELECT COUNT(*) FROM spider_issue_data
   WHERE urgency = 'P1'
     AND (bug_status LIKE '%已修复/完成%' 
          OR bug_status LIKE '%未复现%'
          OR status = 'CLOSED')
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = '9') as diff;
```

### 3.2 P2、P3已修复对比

```sql
SELECT 
  'P2已修复' as metric,
  (SELECT COUNT(*) FROM spider_issue_data
   WHERE urgency = 'P2'
     AND (bug_status LIKE '%已修复/完成%' OR bug_status LIKE '%未复现%' OR status = 'CLOSED')
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9') as old_platform,
  (SELECT COUNT(*) FROM issue_fact
   WHERE priority_level = 'P2' AND is_fixed = true
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = 9 AND deleted = false AND is_excluded = false) as new_platform

UNION ALL

SELECT 
  'P3已修复' as metric,
  (SELECT COUNT(*) FROM spider_issue_data
   WHERE urgency = 'P3'
     AND (bug_status LIKE '%已修复/完成%' OR bug_status LIKE '%未复现%' OR status = 'CLOSED')
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9') as old_platform,
  (SELECT COUNT(*) FROM issue_fact
   WHERE priority_level = 'P3' AND is_fixed = true
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = 9 AND deleted = false AND is_excluded = false) as new_platform;
```

---

## P1 - 建议类缺陷验证

### 4.1 建议类总数对比

```sql
SELECT 
  '建议类缺陷总数' as metric,
  (SELECT COUNT(*) FROM spider_issue_data 
   WHERE category LIKE '%建议类%'
     AND bug_status NOT LIKE '%历史遗留%'
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = '9') as old_platform,
  (SELECT COUNT(*) FROM issue_fact 
   WHERE severity_level = 'SUGGESTION'
     AND is_legacy = false
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = 9
     AND deleted = false
     AND is_excluded = false) as new_platform,
  (SELECT COUNT(*) FROM issue_fact 
   WHERE severity_level = 'SUGGESTION'
     AND is_legacy = false
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = 9
     AND deleted = false
     AND is_excluded = false) -
  (SELECT COUNT(*) FROM spider_issue_data 
   WHERE category LIKE '%建议类%'
     AND bug_status NOT LIKE '%历史遗留%'
     AND testing_phase LIKE '%CC2026R3%第一轮%'
     AND project_id = '9') as diff;
```

### 4.2 检查新平台category字段

```sql
-- 查看新平台category字段的值分布
SELECT 
  category,
  COUNT(*) as count
FROM issue_fact
WHERE testing_phase LIKE '%CC2026R3%第一轮%'
  AND project_id = 9
  AND deleted = false
GROUP BY category
ORDER BY count DESC;
```

---

## 全量关键指标对比

### 5.1 主要统计指标对比

```sql
SELECT 
  '指标名称' as metric,
  '老平台' as old_platform,
  '新平台' as new_platform,
  '差异' as diff,
  '差异%' as diff_pct

UNION ALL

-- 一级缺陷总数
SELECT 
  '一级缺陷总数',
  (SELECT COUNT(*)::text FROM spider_issue_data 
   WHERE severity_level = '一级缺陷' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' 
     AND project_id = '9'),
  (SELECT COUNT(*)::text FROM issue_fact 
   WHERE severity_level = 'LEVEL1' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' 
     AND project_id = 9 AND deleted = false AND is_excluded = false),
  ((SELECT COUNT(*) FROM issue_fact WHERE severity_level = 'LEVEL1' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = 9 
     AND deleted = false AND is_excluded = false) -
   (SELECT COUNT(*) FROM spider_issue_data WHERE severity_level = '一级缺陷' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9'))::text,
  ROUND(((SELECT COUNT(*) FROM issue_fact WHERE severity_level = 'LEVEL1' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = 9 
     AND deleted = false AND is_excluded = false) -
   (SELECT COUNT(*) FROM spider_issue_data WHERE severity_level = '一级缺陷' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9'))::numeric * 100.0 /
   NULLIF((SELECT COUNT(*) FROM spider_issue_data WHERE severity_level = '一级缺陷' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9'), 0), 2)::text || '%'

UNION ALL

-- 二级缺陷总数
SELECT 
  '二级缺陷总数',
  (SELECT COUNT(*)::text FROM spider_issue_data 
   WHERE severity_level = '二级缺陷' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9'),
  (SELECT COUNT(*)::text FROM issue_fact 
   WHERE severity_level = 'LEVEL2' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' 
     AND project_id = 9 AND deleted = false AND is_excluded = false),
  ((SELECT COUNT(*) FROM issue_fact WHERE severity_level = 'LEVEL2' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = 9 
     AND deleted = false AND is_excluded = false) -
   (SELECT COUNT(*) FROM spider_issue_data WHERE severity_level = '二级缺陷' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9'))::text,
  ROUND(((SELECT COUNT(*) FROM issue_fact WHERE severity_level = 'LEVEL2' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = 9 
     AND deleted = false AND is_excluded = false) -
   (SELECT COUNT(*) FROM spider_issue_data WHERE severity_level = '二级缺陷' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9'))::numeric * 100.0 /
   NULLIF((SELECT COUNT(*) FROM spider_issue_data WHERE severity_level = '二级缺陷' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9'), 0), 2)::text || '%'

UNION ALL

-- 三级缺陷总数
SELECT 
  '三级缺陷总数',
  (SELECT COUNT(*)::text FROM spider_issue_data 
   WHERE severity_level = '三级缺陷' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9'),
  (SELECT COUNT(*)::text FROM issue_fact 
   WHERE severity_level = 'LEVEL3' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' 
     AND project_id = 9 AND deleted = false AND is_excluded = false),
  ((SELECT COUNT(*) FROM issue_fact WHERE severity_level = 'LEVEL3' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = 9 
     AND deleted = false AND is_excluded = false) -
   (SELECT COUNT(*) FROM spider_issue_data WHERE severity_level = '三级缺陷' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9'))::text,
  ROUND(((SELECT COUNT(*) FROM issue_fact WHERE severity_level = 'LEVEL3' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = 9 
     AND deleted = false AND is_excluded = false) -
   (SELECT COUNT(*) FROM spider_issue_data WHERE severity_level = '三级缺陷' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9'))::numeric * 100.0 /
   NULLIF((SELECT COUNT(*) FROM spider_issue_data WHERE severity_level = '三级缺陷' 
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9'), 0), 2)::text || '%'

UNION ALL

-- 特征模块一级缺陷
SELECT 
  '特征-一级缺陷',
  (SELECT COUNT(*)::text FROM spider_issue_data 
   WHERE severity_level = '一级缺陷' 
     AND module_name LIKE '%特征%'
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9'),
  (SELECT COUNT(*)::text FROM issue_fact 
   WHERE severity_level = 'LEVEL1' 
     AND '特征' = ANY(string_to_array(module_names, ', '))
     AND testing_phase LIKE '%CC2026R3%第一轮%' 
     AND project_id = 9 AND deleted = false AND is_excluded = false),
  ((SELECT COUNT(*) FROM issue_fact WHERE severity_level = 'LEVEL1' 
     AND '特征' = ANY(string_to_array(module_names, ', '))
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = 9 
     AND deleted = false AND is_excluded = false) -
   (SELECT COUNT(*) FROM spider_issue_data WHERE severity_level = '一级缺陷' 
     AND module_name LIKE '%特征%'
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9'))::text,
  ROUND(((SELECT COUNT(*) FROM issue_fact WHERE severity_level = 'LEVEL1' 
     AND '特征' = ANY(string_to_array(module_names, ', '))
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = 9 
     AND deleted = false AND is_excluded = false) -
   (SELECT COUNT(*) FROM spider_issue_data WHERE severity_level = '一级缺陷' 
     AND module_name LIKE '%特征%'
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9'))::numeric * 100.0 /
   NULLIF((SELECT COUNT(*) FROM spider_issue_data WHERE severity_level = '一级缺陷' 
     AND module_name LIKE '%特征%'
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9'), 0), 2)::text || '%';
```

### 5.2 一级缺陷子类型对比

```sql
SELECT 
  '一级缺陷-回退' as metric,
  (SELECT COUNT(*) FROM spider_issue_data 
   WHERE severity_level = '一级缺陷'
     AND (issue_title LIKE '%（退%' OR issue_title LIKE '%回退%' OR issue_title LIKE '%倒退%')
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9') as old_platform,
  (SELECT COUNT(*) FROM issue_fact 
   WHERE severity_level = 'LEVEL1' AND is_regression = true
     AND testing_phase LIKE '%CC2026R3%第一轮%' 
     AND project_id = 9 AND deleted = false AND is_excluded = false) as new_platform

UNION ALL

SELECT 
  '一级缺陷-挂机' as metric,
  (SELECT COUNT(*) FROM spider_issue_data 
   WHERE severity_level = '一级缺陷' AND issue_title LIKE '%挂机%'
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9') as old_platform,
  (SELECT COUNT(*) FROM issue_fact 
   WHERE severity_level = 'LEVEL1' AND is_crash = true
     AND testing_phase LIKE '%CC2026R3%第一轮%' 
     AND project_id = 9 AND deleted = false AND is_excluded = false) as new_platform

UNION ALL

SELECT 
  '一级缺陷-其他' as metric,
  (SELECT COUNT(*) FROM spider_issue_data 
   WHERE severity_level = '一级缺陷'
     AND issue_title NOT LIKE '%退%'
     AND issue_title NOT LIKE '%回退%'
     AND issue_title NOT LIKE '%倒退%'
     AND issue_title NOT LIKE '%挂机%'
     AND testing_phase LIKE '%CC2026R3%第一轮%' AND project_id = '9') as old_platform,
  (SELECT COUNT(*) FROM issue_fact 
   WHERE severity_level = 'LEVEL1' AND is_level1_other = true
     AND testing_phase LIKE '%CC2026R3%第一轮%' 
     AND project_id = 9 AND deleted = false AND is_excluded = false) as new_platform;
```

---

## 验证结果解读指南

### 差异阈值建议

| 差异百分比 | 评估 | 行动 |
|-----------|------|------|
| 0% | ✅ 完全一致 | 无需修复 |
| <1% | 🟢 可接受 | 了解原因，可不修复 |
| 1%-5% | 🟡 需要关注 | 详细调查，考虑修复 |
| 5%-10% | 🟠 需要修复 | 必须修复 |
| >10% | 🔴 严重问题 | 立即修复 |

### 验证顺序

1. **先执行P0验证**：历史遗留判定
2. **再执行P1验证**：已修复判定、P1/P2/P3、建议类
3. **最后执行全量对比**：所有关键指标

### 执行后行动

1. **记录所有差异百分比**
2. **标记>5%的差异为必修复项**
3. **分析差异原因**
4. **制定修复计划**
5. **修复后重新验证**

---

## 注意事项

1. **测试阶段筛选**：根据实际情况修改 `%CC2026R3%第一轮%`
2. **项目ID**：老平台用字符串'9'，新平台用数字9
3. **NULL处理**：使用NULLIF避免除零错误
4. **性能**：某些SQL可能较慢，建议逐个执行
5. **结果保存**：建议将结果导出到Excel进行对比分析
