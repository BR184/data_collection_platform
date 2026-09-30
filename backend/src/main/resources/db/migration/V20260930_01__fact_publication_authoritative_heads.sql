-- 待发布权威收敛到 fact_change_heads。
--
-- 背景：sync_run_fact_targets 是按镜像运行累积的登记日志（主键含 mirror_run_id），
-- 长时间反复反查会把它推到数十 GB，而控制面（领取、结算、门控、收敛判据）本只需要
-- 每个事实根一行、行数有界的权威状态。本迁移为权威表补齐围栏所需列与待发布部分索引，
-- 并新增事实任务的根批次关联表以取代目标表的控制面职责；目标表保留为审计登记日志。
--
-- 待发布部分索引在稳态下近乎为空（仅未发布根），因此门控与收敛计数不再随历史规模退化。

alter table fact_change_heads add column project_id bigint;

create index idx_fact_change_heads_pending
    on fact_change_heads(source_instance, fact_type)
    where published_version < latest_change_version;

create table fact_build_task_roots (
    task_id bigint not null references fact_build_tasks(id) on delete cascade,
    source_instance varchar(128) not null,
    fact_type varchar(64) not null,
    root_id bigint not null,
    primary key (task_id, root_id),
    constraint ck_fact_build_task_roots_type
        check (fact_type in ('ISSUE', 'MERGE_REQUEST', 'INTEGRATION_TEST'))
);
