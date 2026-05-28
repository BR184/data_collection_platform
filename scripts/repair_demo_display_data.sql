BEGIN;

INSERT INTO ods_gitlab_namespaces (
  mirror_id, id, name, path, owner_id, created_at, updated_at, type,
  description, visibility_level, request_access_enabled, ldap_sync_status,
  require_two_factor_authentication, two_factor_grace_period, parent_id,
  shared_runners_enabled, allow_descendants_override_disabled_shared_runners,
  traversal_ids, mirror_synced_at, mirror_deleted, mirror_created_at, mirror_updated_at
)
VALUES
  (202600, 202600, 'demo', 'demo', null, now(), now(), 'Group',
   '', 20, true, '', false, 48, null, true, false, array[202600], now(), false, now(), now())
ON CONFLICT (mirror_id) DO UPDATE SET
  name = excluded.name,
  path = excluded.path,
  mirror_deleted = false,
  mirror_updated_at = now();

INSERT INTO ods_gitlab_projects (
  mirror_id, id, name, path, created_at, updated_at, creator_id, namespace_id,
  visibility_level, archived, star_count, approvals_before_merge, mirror,
  shared_runners_enabled, build_allow_git_fetch, build_timeout, mirror_trigger_builds,
  public_builds, only_allow_merge_if_pipeline_succeeds, repository_storage,
  request_access_enabled, printing_merge_request_link_enabled, auto_cancel_pending_pipelines,
  hidden, mirror_synced_at, mirror_deleted, mirror_created_at, mirror_updated_at
)
VALUES
  (202603, 202603, '2026R3', '2026r3', now(), now(), null, 202600,
   20, false, 0, 0, false, true, true, 3600, false, true, false, 'default',
   true, true, 1, false, now(), false, now(), now()),
  (202604, 202604, '2026R4', '2026r4', now(), now(), null, 202600,
   20, false, 0, 0, false, true, true, 3600, false, true, false, 'default',
   true, true, 1, false, now(), false, now(), now())
ON CONFLICT (mirror_id) DO UPDATE SET
  name = excluded.name,
  path = excluded.path,
  namespace_id = excluded.namespace_id,
  mirror_deleted = false,
  mirror_updated_at = now();

UPDATE review_records
SET
  title = CASE id
    WHEN 2 THEN '【工具模块】需求规格说明书评审'
    WHEN 3 THEN '【草图模块】设计说明书评审'
    WHEN 4 THEN '【BOM模块】需求规格说明书评审'
    WHEN 5 THEN '【渲染模块】设计说明书评审'
    WHEN 6 THEN '【看板模块】需求规格说明书评审'
    WHEN 7 THEN '【报表模块】产品用户手册评审'
    WHEN 8 THEN '【用户管理】项目计划评审'
    WHEN 9 THEN '【装配模块】设计说明书评审'
    WHEN 10 THEN '【工程图模块】需求规格说明书评审'
    WHEN 11 THEN '【同步模块】其他评审'
    WHEN 12 THEN '【权限模块】需求规格说明书评审'
    ELSE title
  END,
  module_name = CASE id
    WHEN 2 THEN '工具'
    WHEN 3 THEN '草图'
    WHEN 4 THEN 'BOM'
    WHEN 5 THEN '渲染'
    WHEN 6 THEN '看板'
    WHEN 7 THEN '报表'
    WHEN 8 THEN '用户管理'
    WHEN 9 THEN '装配'
    WHEN 10 THEN '工程图'
    WHEN 11 THEN '同步'
    WHEN 12 THEN '权限'
    ELSE module_name
  END,
  review_type = CASE id
    WHEN 2 THEN '需求规格说明书评审'
    WHEN 3 THEN '设计说明书评审'
    WHEN 4 THEN '需求规格说明书评审'
    WHEN 5 THEN '设计说明书评审'
    WHEN 6 THEN '需求规格说明书评审'
    WHEN 7 THEN '产品用户手册'
    WHEN 8 THEN '项目计划评审'
    WHEN 9 THEN '设计说明书评审'
    WHEN 10 THEN '需求规格说明书评审'
    WHEN 11 THEN '其他'
    WHEN 12 THEN '需求规格说明书评审'
    ELSE review_type
  END,
  review_owner = CASE id
    WHEN 2 THEN '张三'
    WHEN 3 THEN '李四'
    WHEN 4 THEN '王五'
    WHEN 5 THEN '赵六'
    WHEN 6 THEN '钱七'
    WHEN 7 THEN '孙八'
    WHEN 8 THEN '周九'
    WHEN 9 THEN '吴十'
    WHEN 10 THEN '冯二'
    WHEN 11 THEN '陈三'
    WHEN 12 THEN '蒋四'
    ELSE review_owner
  END,
  review_product = CASE id
    WHEN 2 THEN '工具模块需求规格说明书'
    WHEN 3 THEN '草图模块设计说明书'
    WHEN 4 THEN 'BOM模块需求规格说明书'
    WHEN 5 THEN '渲染模块设计说明书'
    WHEN 6 THEN '质量看板需求规格说明书'
    WHEN 7 THEN '报表模块用户手册'
    WHEN 8 THEN '用户管理迭代计划'
    WHEN 9 THEN '装配模块设计说明书'
    WHEN 10 THEN '工程图模块需求规格说明书'
    WHEN 11 THEN '同步模块评审材料'
    WHEN 12 THEN '权限模块需求规格说明书'
    ELSE review_product
  END,
  author_name = CASE id
    WHEN 2 THEN '赵六'
    WHEN 3 THEN '吴十'
    WHEN 4 THEN '陈三'
    WHEN 5 THEN '刘六'
    WHEN 6 THEN '钱七'
    WHEN 7 THEN '周九'
    WHEN 8 THEN '吴十'
    WHEN 9 THEN '冯二'
    WHEN 10 THEN '蒋四'
    WHEN 11 THEN '张三'
    WHEN 12 THEN '韩七'
    ELSE author_name
  END,
  search_text = concat_ws(' ', project_name, title, module_name, review_type, review_owner, review_product, author_name),
  search_compact = regexp_replace(concat_ws('', project_name, title, module_name, review_type, review_owner, review_product, author_name), '\s+', '', 'g'),
  title_search_text = title,
  title_search_compact = regexp_replace(title, '\s+', '', 'g'),
  updated_at = now()
WHERE id BETWEEN 2 AND 12;

UPDATE review_record_experts
SET expert_name = CASE expert_name
  WHEN 'èµµå\u0085­' THEN '赵六'
  WHEN 'å­å\u0085«' THEN '孙八'
  WHEN 'æå' THEN '李四'
  WHEN 'çäº' THEN '王五'
  WHEN 'å¼ ä¸' THEN '张三'
  WHEN 'éä¸' THEN '陈三'
  WHEN 'é±ä¸' THEN '钱七'
  WHEN 'å¨ä¹' THEN '周九'
  WHEN 'å´å' THEN '吴十'
  WHEN 'å¯äº' THEN '冯二'
  ELSE expert_name
END
WHERE review_record_id BETWEEN 2 AND 12;

UPDATE review_problem_items
SET
  reviewer_name = CASE reviewer_name
    WHEN 'Ã¥Â¼Â Ã¤Â¸Â' THEN '张三'
    WHEN 'Ã¦ÂÂÃ¥ÂÂ' THEN '李四'
    WHEN 'Ã§ÂÂÃ¤ÂºÂ' THEN '王五'
    WHEN 'Ã¨ÂµÂµÃ¥Â\u0085Â­' THEN '赵六'
    WHEN 'Ã©ÂÂ±Ã¤Â¸Â' THEN '钱七'
    WHEN 'Ã¥Â­ÂÃ¥Â\u0085Â«' THEN '孙八'
    WHEN 'Ã¥ÂÂ¨Ã¤Â¹Â' THEN '周九'
    WHEN 'Ã¥ÂÂ´Ã¥ÂÂ' THEN '吴十'
    WHEN 'Ã¥ÂÂ¯Ã¤ÂºÂ' THEN '冯二'
    WHEN 'Ã©ÂÂÃ¤Â¸Â' THEN '陈三'
    WHEN 'Ã¨ÂÂÃ¥ÂÂ' THEN '蒋四'
    WHEN 'Ã©ÂÂ©Ã¤Â¸Â' THEN '韩七'
    ELSE reviewer_name
  END,
  owner_name = CASE owner_name
    WHEN 'Ã¥Â¼Â Ã¤Â¸Â' THEN '张三'
    WHEN 'Ã¦ÂÂÃ¥ÂÂ' THEN '李四'
    WHEN 'Ã§ÂÂÃ¤ÂºÂ' THEN '王五'
    WHEN 'Ã¨ÂµÂµÃ¥Â\u0085Â­' THEN '赵六'
    WHEN 'Ã©ÂÂ±Ã¤Â¸Â' THEN '钱七'
    WHEN 'Ã¥Â­ÂÃ¥Â\u0085Â«' THEN '孙八'
    WHEN 'Ã¥ÂÂ¨Ã¤Â¹Â' THEN '周九'
    WHEN 'Ã¥ÂÂ´Ã¥ÂÂ' THEN '吴十'
    WHEN 'Ã¥ÂÂ¯Ã¤ÂºÂ' THEN '冯二'
    WHEN 'Ã©ÂÂÃ¤Â¸Â' THEN '陈三'
    WHEN 'Ã¨ÂÂÃ¥ÂÂ' THEN '蒋四'
    WHEN 'Ã©ÂÂ©Ã¤Â¸Â' THEN '韩七'
    ELSE owner_name
  END,
  review_category = CASE review_category
    WHEN 'Ã§ÂÂ¬Ã§Â«ÂÃ¨Â¯ÂÃ¥Â®Â¡' THEN '独立评审'
    WHEN 'Ã¤Â¼ÂÃ¨Â®Â®Ã¨Â¯ÂÃ¥Â®Â¡' THEN '会议评审'
    WHEN 'Ã¨ÂµÂ°Ã¦ÂÂ¥' THEN '走查'
    ELSE review_category
  END,
  problem_category = CASE problem_category
    WHEN 'Ã¦ÂÂÃ¦Â¡Â£Ã¨Â§ÂÃ¨ÂÂ' THEN '文档规范'
    WHEN 'Ã¥Â®ÂÃ¦ÂÂ´Ã¦ÂÂ§' THEN '完整性'
    WHEN 'Ã¥ÂÂÃ¨ÂÂ½Ã¦ÂÂ§' THEN '功能性'
    WHEN 'Ã¥ÂÂ¯Ã¨Â¡ÂÃ¦ÂÂ§' THEN '可行性'
    ELSE problem_category
  END,
  problem_status = CASE problem_status
    WHEN 'Ã¦ÂÂ°Ã¦ÂÂÃ¤ÂºÂ¤' THEN '新提交'
    WHEN 'Ã¥Â·Â²Ã¤Â¿Â®Ã¦ÂÂ¹' THEN '已修改'
    WHEN 'Ã¥Â·Â²Ã¥ÂÂ³Ã©ÂÂ­' THEN '已关闭'
    WHEN 'Ã¥Â·Â²Ã¦ÂÂÃ§Â»Â' THEN '已拒绝'
    WHEN 'Ã¦ÂÂªÃ¨Â¯ÂÃ¥Â®Â¡' THEN '未评审'
    ELSE problem_status
  END,
  document_position = CASE
    WHEN document_position LIKE '%.%' THEN document_position
    ELSE '第' || review_record_id || '章 / 第' || id || '页'
  END,
  problem_description = CASE
    WHEN problem_description LIKE 'Ã%' THEN '评审发现表述不清或边界条件缺失，需要补充说明。'
    WHEN problem_description IS NULL OR btrim(problem_description) = '' THEN '评审发现表述不清或边界条件缺失，需要补充说明。'
    ELSE problem_description
  END,
  suggested_solution = CASE
    WHEN suggested_solution LIKE 'Ã%' THEN '补充约束条件、异常处理和验收标准。'
    WHEN suggested_solution IS NULL OR btrim(suggested_solution) = '' THEN '补充约束条件、异常处理和验收标准。'
    ELSE suggested_solution
  END,
  updated_at = now()
WHERE review_record_id BETWEEN 2 AND 12;

DELETE FROM issue_fact
WHERE source_system = 'DEMO'
  AND source_instance = 'system-test-illegal-demo';

INSERT INTO issue_fact (
  id, source_system, source_instance, ingest_channel, source_summary, raw_payload,
  project_id, project_name, issue_id, issue_iid, title, issue_state, issue_type,
  milestone_title, author_name, assignee_name,
  created_at_source, updated_at_source, ods_updated_at, closed_at_source,
  module_name, primary_module_name, module_names, function_name,
  testing_phase, severity_level, severity_alias, priority_level, urgency,
  bug_status, category, reason_category, system_test_label, label_names,
  primary_phase_label, phase_filter_value,
  search_text, search_compact, title_search_text, title_search_compact,
  module_search_text, module_search_compact, milestone_search_text, milestone_search_compact,
  author_search_text, author_search_compact, assignee_search_text, assignee_search_compact,
  phase_search_text, phase_search_compact,
  is_excluded, exclusion_reason, is_fixed, delay_issue, delay_reason, delay_cause,
  is_regression, is_crash, is_level1_other, is_illegal, illegal_reason,
  has_response, response_overdue, is_response_delayed, resolve_sla_days, resolve_deadline_at,
  is_resolve_delayed, is_legacy, deleted, fact_refreshed_at, created_at, updated_at
)
VALUES
  (29101, 'DEMO', 'system-test-illegal-demo', 'MANUAL', 'system-test-illegal demo', '{}', 202603, '2026R3', 29101, 501, '登录失败后未展示错误提示', 'opened', 'BUG', '系统测试R3', '张三', '李四', '2026-05-24 09:00:00', '2026-05-27 09:30:00', now(), null, '登录', '登录', '登录,认证', '登录流程', '系统测试 第一轮', '', '', 'P2', '中', '未关闭', '功能', '需求理解偏差', '系统测试 第一轮', '系统测试,模块::登录', '系统测试 第一轮', '系统测试', '登录失败后未展示错误提示 2026R3 张三 登录 认证', '登录失败后未展示错误提示2026R3张三登录认证', '登录失败后未展示错误提示', '登录失败后未展示错误提示', '登录 认证', '登录认证', '系统测试R3', '系统测试R3', '张三', '张三', '李四', '李四', '系统测试 第一轮', '系统测试第一轮', false, '', false, false, '', '', false, false, false, true, '未设定严重程度', true, false, false, 5, '2026-05-29 18:00:00', false, false, false, now(), now(), now()),
  (29102, 'DEMO', 'system-test-illegal-demo', 'MANUAL', 'system-test-illegal demo', '{}', 202603, '2026R3', 29102, 502, '报表导出缺少模块归属标签', 'opened', 'BUG', '系统测试R3', '李四', '王五', '2026-05-24 10:00:00', '2026-05-27 10:30:00', now(), null, '', '', '', '报表导出', '系统测试 第二轮', 'LEVEL2', '二级缺陷', 'P2', '中', '处理中', '功能', '编码逻辑错误', '系统测试 第二轮', '系统测试', '系统测试 第二轮', '系统测试', '报表导出缺少模块归属标签 2026R3 李四', '报表导出缺少模块归属标签2026R3李四', '报表导出缺少模块归属标签', '报表导出缺少模块归属标签', '', '', '系统测试R3', '系统测试R3', '李四', '李四', '王五', '王五', '系统测试 第二轮', '系统测试第二轮', false, '', false, false, '', '', false, false, false, true, '未设定模块', true, false, false, 5, '2026-05-29 18:00:00', false, false, false, now(), now(), now()),
  (29103, 'DEMO', 'system-test-illegal-demo', 'MANUAL', 'system-test-illegal demo', '{}', 202604, '2026R4', 29103, 601, '同步失败已关闭但未按模板回复', 'closed', 'BUG', '系统测试R4', '王五', '赵六', '2026-05-25 11:00:00', '2026-05-27 11:30:00', now(), '2026-05-27 18:00:00', '同步服务', '同步服务', '同步服务,队列', '任务同步', '回归测试', 'LEVEL2', '二级缺陷', 'P2', '中', '已关闭', '功能', '环境部署问题', '回归测试', '回归测试,已修复', '回归测试', '回归测试', '同步失败已关闭但未按模板回复 2026R4 王五 同步服务 队列', '同步失败已关闭但未按模板回复2026R4王五同步服务队列', '同步失败已关闭但未按模板回复', '同步失败已关闭但未按模板回复', '同步服务 队列', '同步服务队列', '系统测试R4', '系统测试R4', '王五', '王五', '赵六', '赵六', '回归测试', '回归测试', false, '', true, false, '', '', false, false, false, true, '未按照模板回复', true, false, false, 3, '2026-05-28 18:00:00', false, false, false, now(), now(), now()),
  (29104, 'DEMO', 'system-test-illegal-demo', 'MANUAL', 'system-test-illegal demo', '{}', 202604, '2026R4', 29104, 602, '移动端问题同时标记多个缺陷原因', 'opened', 'BUG', '系统测试R4', '赵六', '钱七', '2026-05-25 12:00:00', '2026-05-27 12:30:00', now(), null, '移动端', '移动端', '移动端,布局', '横竖屏切换', '系统测试 第二轮', 'LEVEL3', '三级缺陷', 'P3', '低', '待修复', '体验', '其他原因', '系统测试 第二轮', '系统测试,原因::环境问题,原因::设计遗漏', '系统测试 第二轮', '系统测试', '移动端问题同时标记多个缺陷原因 2026R4 赵六 移动端 布局', '移动端问题同时标记多个缺陷原因2026R4赵六移动端布局', '移动端问题同时标记多个缺陷原因', '移动端问题同时标记多个缺陷原因', '移动端 布局', '移动端布局', '系统测试R4', '系统测试R4', '赵六', '赵六', '钱七', '钱七', '系统测试 第二轮', '系统测试第二轮', false, '', false, false, '', '', false, false, false, true, '缺陷原因不唯一', true, false, false, 4, '2026-05-30 12:00:00', false, false, false, now(), now(), now());

COMMIT;
