-- MR 逐记录走查投影：每个有效表单记录一行，绑定到本候选的 merge_request_fact 修订。
--
-- MR 事实仍按资源保持唯一一行；本表保存参与统计的有效记录，供质量/分析按记录求均时与记录数。
-- 行身份跟随父 MR 修订，旧已发布修订不被改写；作废记录不进入投影，因此删除只发生在新候选内。
create table if not exists form_review_fact (
    id bigserial primary key,
    merge_request_revision_id bigint not null references merge_request_fact(id) on delete cascade,
    source_system varchar(64) not null default 'GITLAB',
    source_instance varchar(128) not null,
    project_id bigint not null,
    merge_request_id bigint not null,
    merge_request_iid bigint,
    record_id bigint not null,
    scope_id bigint not null,
    storage_version_id bigint not null,
    record_source_role varchar(32) not null default 'AUTHORITATIVE',
    reviewer_user_id varchar(128),
    reviewer_name varchar(128),
    review_duration_minutes integer,
    specification_count integer,
    logic_count integer,
    performance_count integer,
    design_count integer,
    other_count integer,
    record_created_at timestamp,
    fact_refreshed_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create unique index if not exists uq_form_review_fact_revision_record
    on form_review_fact(merge_request_revision_id, record_id);

create index if not exists idx_form_review_fact_source_root
    on form_review_fact(source_instance, project_id, merge_request_id);

comment on table form_review_fact is
    'MR 逐记录走查投影：有效表单记录绑定到候选 MR 修订，供逐记录统计与导出';
