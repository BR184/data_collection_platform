alter table review_records add column if not exists gitlab_project_id bigint;
alter table review_records add column if not exists gitlab_resource_iid bigint;
alter table review_records add column if not exists gitlab_resource_type varchar(64);

create index if not exists idx_review_records_gitlab_context
    on review_records(gitlab_project_id, gitlab_resource_type, gitlab_resource_iid)
    where deleted = false and gitlab_project_id is not null and gitlab_resource_iid is not null;
