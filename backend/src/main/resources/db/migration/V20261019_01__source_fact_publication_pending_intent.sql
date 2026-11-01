-- 全量事实重建意图在运行创建前登记，此时尚无镜像运行可引用。
-- latest_mirror_run_id 因此必须允许为空；就绪/阻塞代际仍由 ready_mirror_run_id、
-- blocked_mirror_run_id 在镜像运行终态后写入，读取资格只依赖 readiness_status 与
-- full_publication_requested，不依赖本列。
alter table source_fact_publication_states
    alter column latest_mirror_run_id drop not null;
