-- 兼容模式-MatchMode：评审数据来自老平台 MongoDB，按集合白名单读取 reviewReport/problemDetail。
alter table code_review_match_mode_db_settings
    add column if not exists mongo_uri text default 'mongodb://172.22.10.72/?waitQueueMultiple=20',
    add column if not exists mongo_database varchar(255) not null default 'spider',
    add column if not exists selected_mongo_collection_names text not null default 'reviewReport,problemDetail',
    add column if not exists review_report_collection_name varchar(255) not null default 'reviewReport',
    add column if not exists review_problem_collection_name varchar(255) not null default 'problemDetail';

update code_review_match_mode_db_settings
   set mongo_uri = coalesce(nullif(btrim(mongo_uri), ''), 'mongodb://172.22.10.72/?waitQueueMultiple=20'),
       mongo_database = coalesce(nullif(btrim(mongo_database), ''), 'spider'),
       selected_mongo_collection_names = coalesce(nullif(btrim(selected_mongo_collection_names), ''), 'reviewReport,problemDetail'),
       review_report_collection_name = coalesce(nullif(btrim(review_report_collection_name), ''), 'reviewReport'),
       review_problem_collection_name = coalesce(nullif(btrim(review_problem_collection_name), ''), 'problemDetail')
 where id = 1;

alter table code_review_match_mode_db_settings
    alter column mongo_uri set default 'mongodb://172.22.10.72/?waitQueueMultiple=20',
    alter column mongo_database set default 'spider',
    alter column selected_mongo_collection_names set default 'reviewReport,problemDetail',
    alter column review_report_collection_name set default 'reviewReport',
    alter column review_problem_collection_name set default 'problemDetail';
