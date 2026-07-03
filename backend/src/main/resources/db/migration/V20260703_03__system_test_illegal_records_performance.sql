create index if not exists idx_issue_fact_system_illegal_project_phase_updated
    on issue_fact(
        project_id,
        lower(coalesce(testing_phase, '')),
        updated_at_source desc,
        issue_iid desc)
    where deleted = false
      and is_illegal = true
      and is_excluded = false;

create index if not exists idx_issue_fact_system_illegal_reasons_trgm
    on issue_fact using gin (
        lower(',' || replace(coalesce(nullif(illegal_reasons, ''), illegal_reason, ''), ', ', ',') || ',') public.gin_trgm_ops)
    where deleted = false
      and is_illegal = true
      and is_excluded = false;
