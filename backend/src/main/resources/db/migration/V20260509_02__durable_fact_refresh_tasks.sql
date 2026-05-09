alter table fact_build_tasks alter column scope type varchar(128);
alter table fact_build_tasks add column if not exists config_id bigint references gitlab_sync_configs(id) on delete set null;
alter table fact_build_tasks add column if not exists source_instance varchar(128) not null default 'default';
alter table fact_build_tasks add column if not exists fact_type varchar(64) not null default 'ALL';
alter table fact_build_tasks add column if not exists run_after timestamp not null default current_timestamp;
alter table fact_build_tasks add column if not exists heartbeat_at timestamp;
alter table fact_build_tasks add column if not exists lease_until timestamp;
alter table fact_build_tasks add column if not exists retry_count integer not null default 0;
alter table fact_build_tasks add column if not exists max_retry_count integer not null default 3;
alter table fact_build_tasks add column if not exists payload_json text;

create index if not exists idx_fact_build_tasks_dispatch
    on fact_build_tasks(status, trigger_type, run_after, created_at);

create index if not exists idx_fact_build_tasks_source_fact
    on fact_build_tasks(config_id, source_instance, fact_type, status, created_at desc);
