-- Optional operations script for large existing intranet databases.
-- Run manually with psql outside Flyway when issue_fact/gitlab_mirror_records already contain large data.
-- The Flyway migration V20260624_02 creates the same indexes without CONCURRENTLY for fresh/empty platforms.

create index concurrently if not exists idx_issue_fact_active_testing_phase
    on issue_fact(testing_phase)
    where deleted = false;

create index concurrently if not exists idx_issue_fact_active_reason_category
    on issue_fact(reason_category)
    where deleted = false;

create index concurrently if not exists idx_issue_fact_active_phase_severity_excluded
    on issue_fact(testing_phase, severity_level, is_excluded)
    where deleted = false;

create index concurrently if not exists idx_issue_fact_active_phase_reason_severity
    on issue_fact(testing_phase, reason_category, severity_level)
    where deleted = false;

create index concurrently if not exists idx_gitlab_mirror_records_table_updated
    on gitlab_mirror_records(config_id, table_name, updated_at_source);
