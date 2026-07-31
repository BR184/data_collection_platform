-- 代码评审外部指标直接演进为可恢复的 GitLab diff 补齐模型；历史数据由后台批任务回填。
alter table code_review_external_metrics
    add column if not exists source_instance varchar(128) not null default 'default',
    add column if not exists project_path varchar(1024),
    add column if not exists added_lines integer,
    add column if not exists metric_source_updated_at timestamp,
    add column if not exists enrichment_status varchar(32) not null default 'PENDING',
    add column if not exists enrichment_version integer not null default 1,
    add column if not exists enrichment_attempts integer not null default 0,
    add column if not exists enrichment_error varchar(512),
    add column if not exists enrichment_next_attempt_at timestamp,
    add column if not exists enrichment_attempted_at timestamp,
    add column if not exists enrichment_succeeded_at timestamp;

alter table code_review_external_metrics
    drop constraint if exists code_review_external_metrics_project_id_merge_request_iid_key;

create unique index if not exists uk_code_review_external_metrics_source_mr
    on code_review_external_metrics(source_instance, project_id, merge_request_iid);

create index if not exists idx_code_review_external_metrics_enrichment_queue
    on code_review_external_metrics(enrichment_status, enrichment_next_attempt_at, id);

create table if not exists code_review_metric_enrichment_states (
    source_instance varchar(128) primary key,
    historical_cursor_merge_request_id bigint not null default 0,
    last_processed_sync_run_id bigint not null default 0,
    active_sync_run_id bigint,
    active_run_cursor_merge_request_id bigint not null default 0,
    historical_completed_at timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists quality_board_member_scopes (
    id bigserial primary key,
    topic_key varchar(64) not null,
    business_source varchar(32) not null,
    member_name varchar(128) not null,
    display_order integer not null default 0,
    enabled boolean not null default true,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_quality_board_member_scope unique(topic_key, business_source, member_name)
);

create index if not exists idx_quality_board_member_scope_enabled
    on quality_board_member_scopes(topic_key, business_source, enabled, display_order);
