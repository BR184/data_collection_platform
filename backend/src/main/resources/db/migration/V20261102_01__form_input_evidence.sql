-- N4 R04-a/P3：表单输入证据与持久签名。
--
-- 发布候选记录输入清单计数/哈希，最终公开事务据此复核 FORM 侧覆盖完整性；
-- 议题事实与写回作业保存同一份规范输入签名，发送前做当前/事实/作业三方比对。

alter table read_publications
    add column if not exists form_input_manifest_hash char(64),
    add column if not exists form_input_manifest_count integer not null default 0;

alter table issue_fact
    add column if not exists form_input_hash char(64);

alter table customer_issue_delay_label_writeback_jobs
    add column if not exists form_input_hash char(64);

create index if not exists idx_cidlw_jobs_form_input
    on customer_issue_delay_label_writeback_jobs(source_instance, project_id, issue_iid,
        form_input_hash);
