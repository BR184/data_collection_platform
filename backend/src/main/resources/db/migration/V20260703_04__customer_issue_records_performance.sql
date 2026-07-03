create index if not exists idx_issue_fact_customer_project_updated
    on issue_fact(project_id, updated_at_source desc, issue_iid desc)
    where deleted = false;

create index if not exists idx_issue_fact_customer_project_created_updated
    on issue_fact(project_id, created_at_source, updated_at_source desc, issue_iid desc)
    where deleted = false;

create index if not exists idx_issue_fact_customer_source_project_updated
    on issue_fact(
        lower(coalesce(source_instance, 'default')),
        project_id,
        updated_at_source desc,
        issue_iid desc)
    where deleted = false;

create index if not exists idx_issue_fact_customer_illegal_project_created_updated
    on issue_fact(project_id, created_at_source, updated_at_source desc, issue_iid desc)
    where deleted = false
      and is_illegal = true
      and is_excluded = false;

create index if not exists idx_issue_fact_customer_illegal_reasons_trgm
    on issue_fact using gin (
        lower(',' || replace(coalesce(nullif(illegal_reasons, ''), illegal_reason, ''), ', ', ',') || ',') public.gin_trgm_ops)
    where deleted = false
      and is_illegal = true
      and is_excluded = false;
