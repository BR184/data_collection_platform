-- 候选投影任务以发布身份为唯一边界；无父事实运行的旁路由同一投影工作器执行。
alter table fact_projection_refresh_tasks
    add column publication_id uuid references read_publications(id),
    alter column fact_run_id drop not null,
    alter column fact_build_task_id drop not null;
alter table fact_projection_refresh_tasks
    add constraint uq_projection_task_publication_scope unique (publication_id, scope_type, scope_key);
alter table read_publications add column projection_input_captured_at timestamptz;

-- 多源候选预热固定其它域的已发布输入；活动引用同时参与历史回收保护。
create table read_publication_dependencies (
    publication_id uuid not null references read_publications(id) on delete cascade,
    domain_id uuid not null references read_publication_domains(id),
    dependency_publication_id uuid not null references read_publications(id),
    primary key (publication_id, domain_id)
);
create index idx_read_publication_dependency_version
    on read_publication_dependencies(dependency_publication_id);

-- 真实预热产物清单，匹配候选/冻结定义/封印/目标代际；它不是任务 SUCCESS 的别名。
create table read_projection_artifacts (
    publication_id uuid not null references read_publications(id) on delete cascade,
    scope_type varchar(64) not null,
    scope_key varchar(512) not null,
    generation bigint not null check (generation > 0),
    definition_snapshot_id uuid not null references read_definition_snapshots(id),
    source_seal jsonb not null,
    consumers jsonb not null,
    artifact_checksum varchar(64) not null,
    prepared_at timestamptz not null default current_timestamp,
    primary key (publication_id, scope_type, scope_key)
);

-- 缓存必须能并存多个完整版本。候选只写 PREPARED，唯一发布事务才提升为公开 READY。
alter table statistic_board_snapshots
    add column publication_id uuid references read_publications(id),
    drop constraint uk_statistic_board_snapshots_scope,
    add constraint uk_statistic_board_snapshots_scope
        unique (board_key, scope_key, rule_version, filter_hash, source_version);
alter table page_record_snapshots
    add column publication_id uuid references read_publications(id),
    drop constraint uk_page_record_snapshots_scope,
    add constraint uk_page_record_snapshots_scope
        unique (page_key, snapshot_type, scope_key, rule_version, request_hash, source_version);
create index idx_statistic_snapshot_publication on statistic_board_snapshots(publication_id);
create index idx_record_snapshot_publication on page_record_snapshots(publication_id);
