-- 旧平台“评审数据管理”兼容字段。
-- 主记录继续作为新平台事实入口；旧平台的工作产品描述和评审分工拆成子表保存。
alter table review_records add column if not exists source_file_name varchar(512);
alter table review_records add column if not exists weighted_defect_density numeric(12, 4);

create table if not exists review_record_descriptions (
    id bigserial primary key,
    review_record_id bigint not null references review_records(id) on delete cascade,
    review_product varchar(255) not null default '',
    review_version varchar(128) not null default '',
    author_name varchar(128) not null default '',
    review_scale_pages integer not null default 0,
    unit varchar(32) not null default '页',
    sort_order integer not null default 0,
    deleted boolean not null default false,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create table if not exists review_record_contents (
    id bigserial primary key,
    review_record_id bigint not null references review_records(id) on delete cascade,
    reviewer_name varchar(128) not null default '',
    assignment_content text,
    independent_workload_hours numeric(8, 2) not null default 0,
    independent_problem_count integer not null default 0,
    meeting_workload_hours numeric(8, 2) not null default 0,
    meeting_problem_count integer not null default 0,
    sort_order integer not null default 0,
    deleted boolean not null default false,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp
);

create index if not exists idx_review_record_descriptions_record
    on review_record_descriptions(review_record_id, deleted, sort_order, id);
create index if not exists idx_review_record_contents_record
    on review_record_contents(review_record_id, deleted, sort_order, id);
