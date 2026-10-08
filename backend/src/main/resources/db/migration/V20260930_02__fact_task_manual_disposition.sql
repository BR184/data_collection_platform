-- 事实与投影任务的人工处置协议：把"需要维护人员决定"的停放态与自动等待/重试区分开。
-- manual_disposition 记录处置结论；等待原因单独持久化，避免用自由文本推断派发语义。
alter table fact_build_tasks
    add column if not exists manual_disposition varchar(32) not null default 'NONE',
    add column if not exists wait_reason varchar(64),
    add column if not exists resumed_from_task_id bigint references fact_build_tasks(id) on delete set null;

alter table fact_build_tasks
    add constraint ck_fact_build_tasks_manual_disposition
        check (manual_disposition in ('NONE', 'REQUIRES_DECISION', 'RESUMED', 'CANCELLED'));

alter table fact_build_tasks
    add constraint ck_fact_build_tasks_wait_reason
        check (wait_reason is null or wait_reason in ('DEPENDENCY_SETTLING'));

alter table fact_projection_refresh_tasks
    add column if not exists manual_disposition varchar(32) not null default 'NONE';

alter table fact_projection_refresh_tasks
    add constraint ck_fact_projection_refresh_tasks_manual_disposition
        check (manual_disposition in ('NONE', 'REQUIRES_DECISION', 'RESUMED', 'CANCELLED'));

create index if not exists idx_fact_build_tasks_manual_attention
    on fact_build_tasks(config_id, source_instance, fact_type, id)
    where manual_disposition = 'REQUIRES_DECISION';

create index if not exists idx_fact_projection_refresh_tasks_manual_attention
    on fact_projection_refresh_tasks(source_instance, fact_type, id)
    where manual_disposition = 'REQUIRES_DECISION';
