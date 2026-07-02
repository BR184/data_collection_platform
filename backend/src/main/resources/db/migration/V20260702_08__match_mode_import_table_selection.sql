-- 兼容模式-MatchMode：允许按白名单选择本次从老平台 MySQL 导入的数据表，并保留通用原始行快照。
alter table code_review_match_mode_db_settings
    add column if not exists selected_table_names text not null default 'spider_crowncad_data';

alter table code_review_match_mode_db_settings
    alter column selected_table_names set default 'spider_crowncad_data';

update code_review_match_mode_db_settings
   set selected_table_names = 'spider_crowncad_data'
 where selected_table_names = 'spider_crowncad_data,review_report,problem_detail';

create table if not exists legacy_mysql_imported_tables (
    id bigserial primary key,
    table_name varchar(255) not null,
    record_count bigint not null default 0,
    last_synced_at timestamp,
    column_names text,
    synced_at timestamp not null default current_timestamp,
    unique (table_name)
);

create table if not exists legacy_mysql_imported_rows (
    id bigserial primary key,
    table_name varchar(255) not null,
    row_key varchar(512) not null,
    raw_payload jsonb not null,
    synced_at timestamp not null default current_timestamp,
    unique (table_name, row_key)
);

create index if not exists idx_legacy_mysql_imported_rows_table
    on legacy_mysql_imported_rows(table_name, id);
