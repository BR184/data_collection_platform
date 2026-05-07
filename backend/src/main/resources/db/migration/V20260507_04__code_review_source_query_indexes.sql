-- 代码走查 CC/DGM 切源后，列表、导出和看板都应继续走 merge_request_fact，不回扫 ODS。
-- 该索引用于约束 source_instance + 常用排序路径，降低大事实表下的切源查询成本。
create index if not exists idx_merge_request_fact_source_list_merged
    on merge_request_fact(source_instance, deleted, merged_at_source desc, merge_request_iid desc);
