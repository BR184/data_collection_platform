-- 兼容模式-MatchMode：评审前短期读取老平台数据库，三类旧平台数据独立落表，后续整体删除。
alter table code_review_match_mode_db_settings
    add column if not exists review_report_table_name varchar(255) not null default 'review_report',
    add column if not exists review_problem_table_name varchar(255) not null default 'problem_detail';

alter table code_review_match_mode_records
    add column if not exists legacy_source_id varchar(128);

alter table code_review_match_mode_records
    drop constraint if exists code_review_match_mode_records_source_instance_project_id_merge_request_id_key;

create table if not exists review_data_match_mode_reports (
    id bigserial primary key,
    legacy_id varchar(128) not null,
    project_name varchar(255),
    title varchar(512),
    module_name varchar(255),
    source_type varchar(128),
    doc_type varchar(128),
    review_type_str varchar(255),
    review_time timestamp,
    review_charger varchar(128),
    review_experts text,
    defect_value integer,
    defect_count_sum integer,
    review_defect_density numeric(10, 2),
    weighted_defect_density numeric(10, 2),
    review_efficiency numeric(10, 2),
    review_rate numeric(10, 2),
    doc_specification integer,
    integrity integer,
    functionality integer,
    feasibility integer,
    not_reach_stand_cause text,
    problem_detail_ids text,
    description_ids text,
    content_ids text,
    create_time timestamp,
    raw_payload jsonb,
    synced_at timestamp not null default current_timestamp,
    unique (legacy_id)
);

create table if not exists review_data_match_mode_problem_details (
    id bigserial primary key,
    legacy_id varchar(128) not null,
    reviewer varchar(128),
    workload numeric(8, 2),
    review_type varchar(128),
    position varchar(255),
    problem_type varchar(128),
    description text,
    suggestion text,
    liable_person varchar(128),
    reason_for_not_accepting text,
    problem_status varchar(128),
    close_time date,
    create_time timestamp,
    update_time date,
    raw_payload jsonb,
    synced_at timestamp not null default current_timestamp,
    unique (legacy_id)
);

create index if not exists idx_review_match_mode_reports_query
    on review_data_match_mode_reports(project_name, module_name, review_time desc, id desc);

create index if not exists idx_review_match_mode_problem_legacy
    on review_data_match_mode_problem_details(legacy_id);
