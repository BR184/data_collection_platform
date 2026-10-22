-- 延期标签写回接替：按来源复用的持久进度，以及队列领取/修订围栏。
--
-- 进度表只保存本轮运行号、阶段、修订、游标、授权版本与租约，不保存事实或每轮历史副本；
-- 进程重启后从该行恢复同一工作，不再依赖内存中的在飞状态。
create table if not exists customer_issue_delay_closure_requests (
    id bigserial primary key,
    source_instance varchar(128) not null,
    config_id bigint not null references gitlab_sync_configs(id) on delete cascade,
    stage varchar(32) not null default 'IDLE',
    request_revision bigint not null default 0,
    processed_revision bigint not null default 0,
    completed_revision bigint not null default 0,
    mirror_run_id bigint,
    authorized_fact_version bigint,
    authorization_revision bigint,
    enqueue_cursor_issue_id bigint,
    failure_count integer not null default 0,
    next_check_at timestamp,
    lease_owner varchar(128),
    lease_token varchar(64),
    lease_until timestamp,
    last_error text,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uq_customer_issue_delay_closure_source unique (source_instance),
    constraint ck_customer_issue_delay_closure_stage
        check (stage in ('REQUESTED', 'WAIT_MIRROR', 'WAIT_FACT', 'ENQUEUE', 'IDLE'))
);

create index if not exists idx_customer_issue_delay_closure_due
    on customer_issue_delay_closure_requests(next_check_at, id);

-- 作业行追加领取身份与任务修订：结算必须同时匹配令牌与修订，旧 worker 不能清除后续工作。
alter table customer_issue_delay_label_writeback_jobs
    add column if not exists config_id bigint,
    add column if not exists round_mirror_run_id bigint,
    add column if not exists authorization_revision bigint,
    add column if not exists required_fact_version bigint,
    add column if not exists claim_token varchar(64),
    add column if not exists claim_revision bigint not null default 0,
    add column if not exists task_revision bigint not null default 0;

create index if not exists idx_customer_issue_delay_label_jobs_source_claim
    on customer_issue_delay_label_writeback_jobs(source_instance, status, next_run_at, id);
