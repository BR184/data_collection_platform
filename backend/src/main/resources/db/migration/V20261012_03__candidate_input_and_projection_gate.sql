-- 固定候选输入独立于输出根清单：来源头随后推进不能让已捕获输入从待完成集合消失。
create table read_publication_root_inputs (
    publication_id uuid not null references read_publications(id) on delete cascade,
    source_instance varchar(255) not null,
    fact_type varchar(64) not null,
    root_id bigint not null check (root_id > 0),
    captured_change_version bigint not null check (captured_change_version >= 0),
    built_by_task_id bigint references fact_build_tasks(id),
    built_at timestamp with time zone,
    primary key (publication_id, source_instance, fact_type, root_id),
    constraint ck_read_publication_root_inputs_completion
        check ((built_by_task_id is null) = (built_at is null))
);

-- 每批仅判定是否仍有未完成输入，不重复全表计数；阻塞明细查询也使用相同访问边界。
create index idx_read_publication_unbuilt_inputs
    on read_publication_root_inputs(publication_id, root_id) where built_at is null;

-- 完成证明只能针对本候选冻结身份；P3 预热成功后登记，事实生产者不能代填。
alter table read_publication_scope_generations
    add column prepared_at timestamp with time zone,
    add column prepared_source_seal jsonb,
    add column prepared_definition_snapshot_id uuid references read_definition_snapshots(id);

alter table read_publication_scope_generations
    add constraint ck_read_publication_projection_proof
        check ((prepared_at is null) = (prepared_source_seal is null));
