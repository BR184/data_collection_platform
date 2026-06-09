insert into semantic_tag_group (
    domain,
    group_key,
    label,
    source_mode,
    rule_policy_key,
    selection_mode,
    match_strategy_name,
    enabled,
    sort_order
)
values
    ('issue', 'severity_level', 'Severity level', 'STATIC', 'issue_severity_policy', 'MULTIPLE', 'EXACT', true, 10),
    ('issue', 'urgency', 'Urgency', 'STATIC', 'customer_issue_urgency_policy', 'MULTIPLE', 'EXACT', true, 20),
    ('issue', 'system_test_exclusion_type', 'System test exclusion type', 'STATIC', 'system_test_exclusion_policy', 'MULTIPLE', 'EXACT', true, 30),
    ('issue', 'delay_cause', 'Delay cause', 'STATIC', 'system_test_delay_cause_policy', 'MULTIPLE', 'EXACT', true, 40),
    ('issue', 'customer_issue_closure_status', 'Customer issue closure status', 'STATIC', 'customer_issue_closure_policy', 'MULTIPLE', 'EXACT', true, 50),
    ('issue', 'illegal_type', 'Illegal data type', 'STATIC', 'system_test_illegal_type_policy', 'MULTIPLE', 'EXACT', true, 60),
    ('issue', 'defect_reason_standard', 'Defect reason standard', 'STATIC', 'defect_reason_policy', 'MULTIPLE', 'EXACT', true, 70),
    ('issue', 'ratio_empty_value_policy', 'Ratio empty value policy', 'STATIC', 'ratio_display_policy', 'SINGLE', 'EXACT', true, 80)
on conflict (domain, group_key) do update set
    label = excluded.label,
    source_mode = excluded.source_mode,
    rule_policy_key = excluded.rule_policy_key,
    selection_mode = excluded.selection_mode,
    match_strategy_name = excluded.match_strategy_name,
    enabled = excluded.enabled,
    sort_order = excluded.sort_order,
    updated_at = current_timestamp;

with seed_values(group_key, value_key, label, value_type, canonical_value, enabled, sort_order) as (
    values
        ('severity_level', 'LEVEL1', 'Level 1 defect', 'STRING', 'LEVEL1', true, 10),
        ('severity_level', 'LEVEL2', 'Level 2 defect', 'STRING', 'LEVEL2', true, 20),
        ('severity_level', 'LEVEL3', 'Level 3 defect', 'STRING', 'LEVEL3', true, 30),
        ('severity_level', 'SUGGESTION', 'Suggestion', 'STRING', 'SUGGESTION', true, 40),
        ('urgency', 'P1', 'P1', 'STRING', 'P1', true, 10),
        ('urgency', 'P2', 'P2', 'STRING', 'P2', true, 20),
        ('urgency', 'P3', 'P3', 'STRING', 'P3', true, 30),
        ('system_test_exclusion_type', 'FUNCTION_BLOCKED', 'Function blocked', 'STRING', 'FUNCTION_BLOCKED', true, 10),
        ('system_test_exclusion_type', 'REJECTED', 'Rejected', 'STRING', 'REJECTED', true, 20),
        ('system_test_exclusion_type', 'SUGGESTION', 'Suggestion', 'STRING', 'SUGGESTION', true, 30),
        ('system_test_exclusion_type', 'CLOSED_REJECTION', 'Closed with rejection', 'STRING', 'CLOSED_REJECTION', true, 40),
        ('system_test_exclusion_type', 'CLOSED_REQUIREMENT_AS_IS', 'Closed as requirement', 'STRING', 'CLOSED_REQUIREMENT_AS_IS', true, 50),
        ('delay_cause', 'TECHNICAL_BLOCKER', 'Technical blocker', 'STRING', 'TECHNICAL_BLOCKER', true, 10),
        ('delay_cause', 'SOLUTION_BLOCKER', 'Solution blocker', 'STRING', 'SOLUTION_BLOCKER', true, 20),
        ('delay_cause', 'RESOURCE_BLOCKER', 'Resource blocker', 'STRING', 'RESOURCE_BLOCKER', true, 30),
        ('delay_cause', 'DATA_ANOMALY', 'Data anomaly', 'STRING', 'DATA_ANOMALY', true, 40),
        ('delay_cause', 'ALGORITHM_ISSUE', 'Algorithm issue', 'STRING', 'ALGORITHM_ISSUE', true, 50),
        ('delay_cause', 'MECHANISM_ISSUE', 'Mechanism issue', 'STRING', 'MECHANISM_ISSUE', true, 60),
        ('delay_cause', 'COMPUTATION_EFFICIENCY', 'Computation efficiency', 'STRING', 'COMPUTATION_EFFICIENCY', true, 70),
        ('customer_issue_closure_status', 'FIXED_DONE', 'Fixed or done', 'STRING', 'FIXED_DONE', true, 10),
        ('customer_issue_closure_status', 'DELAY_REQUESTED', 'Delay requested', 'STRING', 'DELAY_REQUESTED', true, 20),
        ('customer_issue_closure_status', 'DATA_ANOMALY', 'Data anomaly', 'STRING', 'DATA_ANOMALY', true, 30),
        ('customer_issue_closure_status', 'REQUIREMENT_AS_IS', 'Requirement as-is', 'STRING', 'REQUIREMENT_AS_IS', true, 40),
        ('customer_issue_closure_status', 'DESIGN_AS_IS', 'Design as-is', 'STRING', 'DESIGN_AS_IS', true, 50),
        ('customer_issue_closure_status', 'NOT_REPRODUCED', 'Not reproduced', 'STRING', 'NOT_REPRODUCED', true, 60),
        ('illegal_type', 'MISSING_SEVERITY', 'Missing severity', 'STRING', 'MISSING_SEVERITY', true, 10),
        ('illegal_type', 'MISSING_MODULE', 'Missing module', 'STRING', 'MISSING_MODULE', true, 20),
        ('illegal_type', 'MISSING_REQUIRED_REPLY', 'Missing required reply', 'STRING', 'MISSING_REQUIRED_REPLY', true, 30),
        ('illegal_type', 'NON_UNIQUE_DEFECT_REASON', 'Non-unique defect reason', 'STRING', 'NON_UNIQUE_DEFECT_REASON', true, 40),
        ('illegal_type', 'MISSING_DEFECT_INVESTIGATION_TEMPLATE', 'Missing investigation', 'STRING', 'MISSING_DEFECT_INVESTIGATION_TEMPLATE', true, 50),
        ('illegal_type', 'INVALID_PLAN_RESOLVE_TIME', 'Invalid planned resolve time', 'STRING', 'INVALID_PLAN_RESOLVE_TIME', true, 60),
        ('illegal_type', 'LEVEL1_MISSING_OWNER_SIGN', 'Level 1 missing owner sign', 'STRING', 'LEVEL1_MISSING_OWNER_SIGN', true, 70),
        ('defect_reason_standard', 'NEW_UNDERSTANDING_DEVIATION', 'New understanding deviation', 'STRING', 'NEW_UNDERSTANDING_DEVIATION', true, 10),
        ('defect_reason_standard', 'NEW_REQUIREMENT', 'New requirement', 'STRING', 'NEW_REQUIREMENT', true, 20),
        ('defect_reason_standard', 'CODING_BUSINESS_LOGIC_ERROR', 'Coding business logic error', 'STRING', 'CODING_BUSINESS_LOGIC_ERROR', true, 30),
        ('defect_reason_standard', 'BUILD_PACKAGE_DEPLOYMENT_ISSUE', 'Build/package/deployment issue', 'STRING', 'BUILD_PACKAGE_DEPLOYMENT_ISSUE', true, 40),
        ('defect_reason_standard', 'MECHANISM_UNSUPPORTED', 'Mechanism unsupported', 'STRING', 'MECHANISM_UNSUPPORTED', true, 50),
        ('ratio_empty_value_policy', 'DISPLAY_SLASH', 'Display slash', 'STRING', 'DISPLAY_SLASH', true, 10),
        ('ratio_empty_value_policy', 'DISPLAY_ZERO', 'Display zero', 'STRING', 'DISPLAY_ZERO', true, 20)
)
insert into semantic_tag_value (
    group_id,
    value_key,
    label,
    value_type,
    canonical_value,
    enabled,
    sort_order
)
select semantic_tag_group.id,
       seed_values.value_key,
       seed_values.label,
       seed_values.value_type,
       seed_values.canonical_value,
       seed_values.enabled,
       seed_values.sort_order
  from seed_values
  join semantic_tag_group
    on semantic_tag_group.domain = 'issue'
   and semantic_tag_group.group_key = seed_values.group_key
on conflict (group_id, value_key) do update set
    label = excluded.label,
    value_type = excluded.value_type,
    canonical_value = excluded.canonical_value,
    enabled = excluded.enabled,
    sort_order = excluded.sort_order,
    updated_at = current_timestamp;
