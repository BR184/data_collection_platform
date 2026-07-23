create index if not exists idx_issue_fact_customer_response_times
    on issue_fact(project_id, milestone_title, research_template_time, fixed_label_time)
    where project_id = 325 and deleted = false;
