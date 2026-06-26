-- MR#29874 诊断SQL
-- 目标：找出为什么该MR没有出现在代码走查非法数据页面

-- ========================================
-- 步骤1：检查MR#29874在事实表中的基础数据
-- ========================================
SELECT
  merge_request_id,
  merge_request_iid,
  project_id,
  project_name,
  title,
  module_name,  -- 关键字段1：是否为空或"无需标注"
  label_names,
  merge_request_state,  -- 关键字段2：必须是'merged'
  merged_at_source,  -- 关键字段3：必须 > 2024-04-01
  deleted,  -- 关键字段4：必须是false
  -- 非法判定相关字段
  review_status,
  review_duration_minutes,
  scan_status,
  scan_bug_count,
  comment_rate,
  annotation_rate_result,
  bug_count_result,
  -- 其他
  created_at,
  updated_at
FROM merge_request_fact
WHERE merge_request_iid = 29874
  AND project_name LIKE '%CrownCAD%';

-- ========================================
-- 步骤2：检查是否满足基础WHERE条件
-- ========================================
-- 基础条件（来自 LEGACY_ILLEGAL_BASE_WHERE）：
-- 1. deleted = false
-- 2. merge_request_state = 'merged'
-- 3. merged_at_source > timestamp '2024-04-01 00:00:00'
-- 4. coalesce(module_name, '') <> '无需标注'

SELECT
  merge_request_iid,
  CASE
    WHEN deleted = true THEN '❌ 被标记为删除'
    ELSE '✓ deleted = false'
  END as check_deleted,
  CASE
    WHEN merge_request_state <> 'merged' THEN '❌ 状态不是merged: ' || merge_request_state
    ELSE '✓ state = merged'
  END as check_state,
  CASE
    WHEN merged_at_source IS NULL THEN '❌ merged_at_source 为空'
    WHEN merged_at_source <= '2024-04-01'::timestamp THEN '❌ merged_at <= 2024-04-01: ' || merged_at_source::text
    ELSE '✓ merged_at > 2024-04-01'
  END as check_merged_at,
  CASE
    WHEN coalesce(module_name, '') = '无需标注' THEN '❌ module_name = 无需标注'
    WHEN coalesce(module_name, '') = '' THEN '❌ module_name 为空'
    ELSE '✓ module_name 有值: ' || module_name
  END as check_module_name
FROM merge_request_fact
WHERE merge_request_iid = 29874
  AND project_name LIKE '%CrownCAD%';

-- ========================================
-- 步骤3：检查镜像库中的原始标签
-- ========================================
SELECT
  mr.iid,
  mr.title,
  mr.state,
  mr.merged_at,
  l.id as label_id,
  l.title as label_title,
  l.color as label_color,
  length(l.title) as label_length,
  -- 显示标签的十六进制编码（用于分析特殊字符）
  encode(l.title::bytea, 'hex') as label_hex
FROM ods_gitlab_merge_requests mr
LEFT JOIN ods_gitlab_label_links ll ON ll.target_id = mr.id AND ll.target_type = 'MergeRequest'
LEFT JOIN ods_gitlab_labels l ON l.id = ll.label_id
WHERE mr.iid = 29874
  AND mr.project_id IN (SELECT id FROM ods_gitlab_projects WHERE name = 'CrownCAD')
ORDER BY l.title;

-- ========================================
-- 步骤4：检查该MR是否被判定为"非法"
-- ========================================
-- 非法类型判定规则（来自 CodeReviewIllegalRuleRegistry）：
-- 1. 未标注项目名称 - project_name 为空
-- 2. 未标注模块名称 - module_name 为空或"未标注模块名"
-- 3. 无代码走查 - review_status 为空或异常
-- 4. 未代码扫描 - scan_status 为"未扫描"
-- 5. 静态扫描问题未关闭 - scan_bug_count > 0
-- 6. 代码注释量未达标 - annotation_rate_result 不达标
-- 7. 静态扫描失败 - bug_count_result 失败
-- 8. Clang分析错误 - clang相关字段错误
-- 9. GitLab接口报错 - 特定错误标记

SELECT
  merge_request_iid,
  title,
  -- 判定各项非法类型
  CASE WHEN coalesce(project_name, '') = '' THEN '未标注项目名称' ELSE NULL END as illegal_1,
  CASE WHEN coalesce(module_name, '') IN ('', '未标注模块名') THEN '未标注模块名称' ELSE NULL END as illegal_2,
  CASE WHEN coalesce(review_status, '') = '' OR review_duration_minutes IS NULL THEN '无代码走查' ELSE NULL END as illegal_3,
  CASE WHEN scan_status = '未扫描' OR scan_status LIKE '%未进行%' THEN '未代码扫描' ELSE NULL END as illegal_4,
  CASE WHEN scan_bug_count > 0 THEN '静态扫描问题未关闭' ELSE NULL END as illegal_5,
  CASE WHEN annotation_rate_result LIKE '%未达标%' THEN '代码注释量未达标' ELSE NULL END as illegal_6,
  CASE WHEN bug_count_result LIKE '%失败%' THEN '静态扫描失败' ELSE NULL END as illegal_7,
  -- 显示所有相关字段值
  project_name,
  module_name,
  review_status,
  review_duration_minutes,
  scan_status,
  scan_bug_count,
  annotation_rate_result,
  bug_count_result
FROM merge_request_fact
WHERE merge_request_iid = 29874
  AND project_name LIKE '%CrownCAD%';

-- ========================================
-- 步骤5：检查有多少类似的MR（有标签但module_name为空）
-- ========================================
SELECT
  COUNT(*) as total_count,
  COUNT(CASE WHEN coalesce(module_name, '') = '' THEN 1 END) as empty_module_count,
  COUNT(CASE WHEN coalesce(module_name, '') = '无需标注' THEN 1 END) as no_annotation_count,
  COUNT(CASE WHEN label_names LIKE '%模块%' AND coalesce(module_name, '') = '' THEN 1 END) as has_label_but_empty_count
FROM merge_request_fact
WHERE deleted = false
  AND merge_request_state = 'merged'
  AND merged_at_source > '2024-04-01'::timestamp
  AND project_name = 'CrownCAD';

-- ========================================
-- 步骤6：对比：找出所有应该出现但没出现的MR
-- ========================================
-- 这些MR有"模块"标签，但module_name为空，因此被基础WHERE过滤掉了
SELECT
  merge_request_iid,
  title,
  module_name,
  label_names,
  merged_at_source
FROM merge_request_fact
WHERE deleted = false
  AND merge_request_state = 'merged'
  AND merged_at_source > '2024-04-01'::timestamp
  AND project_name = 'CrownCAD'
  AND label_names LIKE '%模块%'
  AND coalesce(module_name, '') = ''
ORDER BY merge_request_iid DESC
LIMIT 100;

-- ========================================
-- 步骤7：检查module_name的提取逻辑是否正确
-- ========================================
-- 查看所有包含"模块"标签的MR，看看哪些被成功解析，哪些没有
SELECT
  substring(label_names from '模块[^,]*') as module_label_text,
  module_name,
  COUNT(*) as count
FROM merge_request_fact
WHERE deleted = false
  AND merge_request_state = 'merged'
  AND merged_at_source > '2024-04-01'::timestamp
  AND project_name = 'CrownCAD'
  AND label_names LIKE '%模块%'
GROUP BY module_label_text, module_name
ORDER BY count DESC;
