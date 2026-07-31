-- 只由扫描器显式创建待补齐记录；队列索引以前导来源实例隔离领取范围。
alter table code_review_external_metrics
    alter column enrichment_status set default 'SUCCESS';

drop index if exists idx_code_review_external_metrics_enrichment_queue;

create index idx_code_review_external_metrics_enrichment_queue
    on code_review_external_metrics(source_instance, enrichment_status, enrichment_next_attempt_at, id);
