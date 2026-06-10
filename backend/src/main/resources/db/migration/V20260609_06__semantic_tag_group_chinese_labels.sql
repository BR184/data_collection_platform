with group_labels(group_key, label) as (
    values
        ('severity_level', '严重程度'),
        ('urgency', '紧急程度'),
        ('system_test_exclusion_type', '系统测试排除类型'),
        ('delay_cause', '延期原因'),
        ('customer_issue_closure_status', '客户问题闭环状态'),
        ('illegal_type', '非法数据类型'),
        ('defect_reason_standard', '缺陷原因标准项'),
        ('ratio_empty_value_policy', '比例空值展示策略')
)
update semantic_tag_group
   set label = group_labels.label,
       updated_at = current_timestamp
  from group_labels
 where semantic_tag_group.domain = 'issue'
   and semantic_tag_group.group_key = group_labels.group_key;

with value_labels(group_key, value_key, label) as (
    values
        ('severity_level', 'LEVEL1', '一级缺陷'),
        ('severity_level', 'LEVEL2', '二级缺陷'),
        ('severity_level', 'LEVEL3', '三级缺陷'),
        ('severity_level', 'SUGGESTION', '建议类'),
        ('urgency', 'P1', 'P1'),
        ('urgency', 'P2', 'P2'),
        ('urgency', 'P3', 'P3'),
        ('system_test_exclusion_type', 'FUNCTION_BLOCKED', '功能屏蔽'),
        ('system_test_exclusion_type', 'REJECTED', '已拒绝'),
        ('system_test_exclusion_type', 'SUGGESTION', '建议'),
        ('system_test_exclusion_type', 'CLOSED_REJECTION', '关闭-申请否决'),
        ('system_test_exclusion_type', 'CLOSED_REQUIREMENT_AS_IS', '关闭-需求如此'),
        ('delay_cause', 'TECHNICAL_BLOCKER', '技术卡点'),
        ('delay_cause', 'SOLUTION_BLOCKER', '方案卡点'),
        ('delay_cause', 'RESOURCE_BLOCKER', '资源卡点'),
        ('delay_cause', 'DATA_ANOMALY', '数据异常'),
        ('delay_cause', 'ALGORITHM_ISSUE', '算法问题'),
        ('delay_cause', 'MECHANISM_ISSUE', '机制问题'),
        ('delay_cause', 'COMPUTATION_EFFICIENCY', '计算效率'),
        ('customer_issue_closure_status', 'FIXED_DONE', '已修复/完成'),
        ('customer_issue_closure_status', 'DELAY_REQUESTED', '申请延期'),
        ('customer_issue_closure_status', 'DATA_ANOMALY', '数据异常'),
        ('customer_issue_closure_status', 'REQUIREMENT_AS_IS', '需求如此'),
        ('customer_issue_closure_status', 'DESIGN_AS_IS', '设计如此'),
        ('customer_issue_closure_status', 'NOT_REPRODUCED', '未复现'),
        ('illegal_type', 'MISSING_SEVERITY', '未设定严重程度'),
        ('illegal_type', 'MISSING_MODULE', '未设定模块'),
        ('illegal_type', 'MISSING_REQUIRED_REPLY', '未按模板回复'),
        ('illegal_type', 'NON_UNIQUE_DEFECT_REASON', '缺陷原因不唯一'),
        ('illegal_type', 'MISSING_DEFECT_INVESTIGATION_TEMPLATE', '未填写缺陷调研模板'),
        ('illegal_type', 'INVALID_PLAN_RESOLVE_TIME', '计划解决时间格式异常'),
        ('illegal_type', 'LEVEL1_MISSING_OWNER_SIGN', '一级缺陷缺少负责人签字'),
        ('defect_reason_standard', 'NEW_UNDERSTANDING_DEVIATION', '新增理解偏差'),
        ('defect_reason_standard', 'NEW_REQUIREMENT', '新增需求'),
        ('defect_reason_standard', 'CODING_BUSINESS_LOGIC_ERROR', '编码逻辑：业务逻辑错误'),
        ('defect_reason_standard', 'BUILD_PACKAGE_DEPLOYMENT_ISSUE', '构建/打包/部署问题'),
        ('defect_reason_standard', 'MECHANISM_UNSUPPORTED', '机制不支持'),
        ('ratio_empty_value_policy', 'DISPLAY_SLASH', '显示为 /'),
        ('ratio_empty_value_policy', 'DISPLAY_ZERO', '显示为 0')
)
update semantic_tag_value
   set label = value_labels.label,
       updated_at = current_timestamp
  from value_labels
  join semantic_tag_group
    on semantic_tag_group.domain = 'issue'
   and semantic_tag_group.group_key = value_labels.group_key
 where semantic_tag_value.group_id = semantic_tag_group.id
   and semantic_tag_value.value_key = value_labels.value_key;
