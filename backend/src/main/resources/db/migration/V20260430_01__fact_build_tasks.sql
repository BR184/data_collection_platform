-- 事实构建任务表是 Flyway 纳管的第一张后端调度表，用来记录手动或自动事实重建的执行状态。
-- 下面保留 add column if not exists，是为了兼容已经通过 schema.sql 初始化过的本地或测试库。
create table if not exists fact_build_tasks (
    id bigserial primary key,
    run_id varchar(64) not null,
    scope varchar(32) not null,
    full_build boolean not null default false,
    status varchar(32) not null,
    trigger_type varchar(32) not null default 'MANUAL',
    lock_owner varchar(128),
    affected_rows integer not null default 0,
    message text,
    error_message text,
    started_at timestamp,
    finished_at timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

-- 过渡期迁移保持幂等：新库走 create table，旧库走增量补列，避免切换初始化入口时出现重复建表风险。
alter table fact_build_tasks add column if not exists run_id varchar(64);
alter table fact_build_tasks add column if not exists scope varchar(32);
alter table fact_build_tasks add column if not exists full_build boolean not null default false;
alter table fact_build_tasks add column if not exists status varchar(32);
alter table fact_build_tasks add column if not exists trigger_type varchar(32) not null default 'MANUAL';
alter table fact_build_tasks add column if not exists lock_owner varchar(128);
alter table fact_build_tasks add column if not exists affected_rows integer not null default 0;
alter table fact_build_tasks add column if not exists message text;
alter table fact_build_tasks add column if not exists error_message text;
alter table fact_build_tasks add column if not exists started_at timestamp;
alter table fact_build_tasks add column if not exists finished_at timestamp;
alter table fact_build_tasks add column if not exists created_at timestamp not null default current_timestamp;
alter table fact_build_tasks add column if not exists updated_at timestamp not null default current_timestamp;

-- 调度查询主要按范围、状态和创建时间排序，这两个索引支撑任务列表和最近任务判断。
create index if not exists idx_fact_build_tasks_scope_status on fact_build_tasks(scope, status, created_at desc);
create index if not exists idx_fact_build_tasks_created_at on fact_build_tasks(created_at desc);
