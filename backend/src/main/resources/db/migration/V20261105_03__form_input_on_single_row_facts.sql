-- 旧同步引擎的表单接入：签名随既有单行事实与写回作业保存，不引入读发布模型。
alter table issue_fact add column if not exists form_input_hash char(64);
alter table customer_issue_delay_label_writeback_jobs add column if not exists form_input_hash char(64);
create index if not exists idx_cidlw_jobs_form_input
    on customer_issue_delay_label_writeback_jobs(source_instance, project_id, issue_iid, form_input_hash);
