create table if not exists customer_issue_delay_label_writeback_jobs (
    id bigserial primary key,
    source_instance varchar(128) not null,
    project_id bigint not null,
    issue_iid bigint not null,
    issue_id bigint,
    desired_response_delayed boolean not null default false,
    desired_resolve_delayed boolean not null default false,
    current_label_names text,
    add_labels text,
    remove_labels text,
    status varchar(32) not null default 'PENDING',
    attempt_count integer not null default 0,
    max_attempts integer not null default 10,
    next_run_at timestamp not null default current_timestamp,
    lease_owner varchar(128),
    lease_until timestamp,
    last_error text,
    last_http_status integer,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    finished_at timestamp,
    unique (source_instance, project_id, issue_iid)
);

create index if not exists idx_customer_issue_delay_label_jobs_status_next_run
    on customer_issue_delay_label_writeback_jobs(status, next_run_at, id);

create index if not exists idx_customer_issue_delay_label_jobs_issue
    on customer_issue_delay_label_writeback_jobs(project_id, issue_iid);
