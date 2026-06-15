-- 老平台代码走查非法数据导出第一列为独立“走查时间”，不能用合并时间替代。
alter table merge_request_fact add column if not exists code_walkthrough_date timestamp;
