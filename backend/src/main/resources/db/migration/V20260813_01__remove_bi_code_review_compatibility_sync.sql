-- destructive-migration-reviewed: approved by liuxuhao on 2026-08-13
-- destructive-migration-recovery: 三表当前均为空（0 行），DROP 无数据损失；如需回滚需在代码回退前按 V20260805_01 重建同构表
-- BI 人工走查兼容数据已统一复用兼容表 code_review_match_mode_records，
-- 移除重复抓取链路的 BI 独占表（兼容快照、loading 暂存、同步状态）。
-- merge_request_commit_fact 不受影响。
drop table if exists bi_code_review_compatibility_records;
drop table if exists bi_code_review_compatibility_records_loading;
drop table if exists bi_code_review_compatibility_sync_state;
