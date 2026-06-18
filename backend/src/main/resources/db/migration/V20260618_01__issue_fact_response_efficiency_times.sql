alter table issue_fact add column if not exists research_template_time timestamp;
alter table issue_fact add column if not exists fixed_label_time timestamp;

create index if not exists idx_issue_fact_response_efficiency
    on issue_fact(project_id, milestone_title, research_template_time, fixed_label_time)
    where deleted = false;
