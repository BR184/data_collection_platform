-- 代码走查非法数据页需要保留老平台字段语义，事实层集中承载这些稳定值。
alter table code_review_external_metrics add column if not exists scan_status varchar(128);
alter table code_review_external_metrics add column if not exists scan_bug_count integer;
alter table code_review_external_metrics add column if not exists annotation_rate_result varchar(128);
alter table code_review_external_metrics add column if not exists bug_count_result varchar(128);

alter table merge_request_fact add column if not exists annotation_rate_result varchar(128);
alter table merge_request_fact add column if not exists bug_count_result varchar(128);
