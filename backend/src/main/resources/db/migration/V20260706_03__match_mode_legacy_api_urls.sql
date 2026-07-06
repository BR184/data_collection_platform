-- 兼容模式-MatchMode：单条代码走查刷新需要调用老平台后端接口。
alter table code_review_match_mode_db_settings
    add column if not exists legacy_api_base_url varchar(512) not null default 'http://172.22.10.72:8091';

alter table code_review_match_mode_db_settings
    add column if not exists dgm_legacy_api_base_url varchar(512) not null default '';
