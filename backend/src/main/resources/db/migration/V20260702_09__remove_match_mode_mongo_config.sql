-- 兼容模式-MatchMode：注释率、SonarQube 等评论区汇总数据改由老平台 MySQL/外部汇总表提供，不再维护 MongoDB 注释率连接。
alter table code_review_match_mode_db_settings
    drop column if exists mongo_uri,
    drop column if exists mongo_database,
    drop column if exists mongo_annotation_collection,
    drop column if exists review_report_table_name,
    drop column if exists review_problem_table_name;
