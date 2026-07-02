-- 兼容模式-MatchMode：评审前短期读取老平台数据库，后续外部工具正式接入后整体删除。
create table if not exists code_review_match_mode_db_settings (
    id smallint primary key default 1,
    enabled boolean not null default true,
    sync_enabled boolean not null default true,
    mysql_host varchar(255) not null default '172.22.10.72',
    mysql_port integer not null default 3306,
    mysql_database varchar(255) not null default 'gitlab_spider',
    mysql_username varchar(255) not null default 'root',
    mysql_password varchar(255) not null default '',
    mysql_table_name varchar(255) not null default 'spider_crowncad_data',
    mysql_fetch_size integer not null default 1000,
    mongo_uri text,
    mongo_database varchar(255) default 'spider',
    mongo_annotation_collection varchar(255) not null default 'annotationRateInfo',
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_code_review_match_mode_db_settings_singleton check (id = 1),
    constraint ck_code_review_match_mode_db_settings_mysql_port check (mysql_port between 1 and 65535),
    constraint ck_code_review_match_mode_db_settings_fetch_size check (mysql_fetch_size between 1 and 100000)
);

insert into code_review_match_mode_db_settings(id, enabled, sync_enabled)
values (1, true, true)
on conflict (id) do nothing;
