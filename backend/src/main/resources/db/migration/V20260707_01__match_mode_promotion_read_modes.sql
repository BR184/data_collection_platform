-- 兼容模式-MatchMode：转正式交接阶段允许评审数据和代码走查数据分别切换读取来源。
alter table code_review_match_mode_db_settings
    add column if not exists review_data_read_mode varchar(32) not null default 'compatibility',
    add column if not exists code_review_read_mode varchar(32) not null default 'compatibility';

create table if not exists legacy_platform_formal_import_runs (
    id bigserial primary key,
    import_type varchar(64) not null,
    confirmation_text varchar(255) not null,
    review_requested boolean not null default false,
    code_review_requested boolean not null default false,
    review_inserted_count bigint not null default 0,
    review_updated_count bigint not null default 0,
    code_review_inserted_count bigint not null default 0,
    code_review_updated_count bigint not null default 0,
    status varchar(32) not null default 'SUCCESS',
    message text,
    created_at timestamp not null default current_timestamp
);

create index if not exists idx_legacy_formal_import_runs_created
    on legacy_platform_formal_import_runs(created_at desc);
