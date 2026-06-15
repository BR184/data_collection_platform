-- 老平台把代码走查解析异常落在“走查人/assignee”字段里；新平台事实层单独保留异常原因。
alter table merge_request_fact add column if not exists review_exception_reason varchar(128);
