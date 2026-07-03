-- 兼容模式-MatchMode：老平台 CC/DGM 代码走查数据分别在 gitlab_spider / gitlab_spider_dgm 中维护。
alter table code_review_match_mode_db_settings
    add column if not exists dgm_mysql_database varchar(255) not null default 'gitlab_spider_dgm';

update code_review_match_mode_records
   set source_instance = 'dgm'
 where lower(coalesce(repository_name, '')) = 'dgm'
   and lower(coalesce(source_instance, '')) <> 'dgm';
