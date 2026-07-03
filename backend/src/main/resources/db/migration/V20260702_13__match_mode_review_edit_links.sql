-- 兼容模式-MatchMode：Mongo 评审数据被用户编辑时转存为正式评审记录，避免下次全量导入覆盖用户修改。
create table if not exists review_data_match_mode_edit_links (
    id bigserial primary key,
    match_mode_report_id bigint not null,
    match_mode_report_legacy_id varchar(128) not null,
    review_record_id bigint not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (match_mode_report_id),
    unique (match_mode_report_legacy_id),
    unique (review_record_id)
);

create table if not exists review_data_match_mode_problem_edit_links (
    id bigserial primary key,
    match_mode_problem_id bigint not null,
    match_mode_problem_legacy_id varchar(128) not null,
    review_record_id bigint not null,
    review_problem_item_id bigint not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (match_mode_problem_id),
    unique (match_mode_problem_legacy_id),
    unique (review_problem_item_id)
);

create index if not exists idx_review_match_mode_edit_links_record
    on review_data_match_mode_edit_links(review_record_id);

create index if not exists idx_review_match_mode_problem_edit_links_record
    on review_data_match_mode_problem_edit_links(review_record_id);
