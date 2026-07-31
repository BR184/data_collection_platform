-- V01 新增状态列时，既有导入指标被默认标记为 PENDING；仅扫描器接管的记录带源更新时间。
update code_review_external_metrics
   set enrichment_status = 'SUCCESS',
       enrichment_error = null,
       enrichment_next_attempt_at = null,
       updated_at = current_timestamp
 where enrichment_status = 'PENDING'
   and metric_source_updated_at is null;
