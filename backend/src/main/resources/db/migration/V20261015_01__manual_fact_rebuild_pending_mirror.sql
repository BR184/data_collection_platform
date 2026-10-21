-- 手工全量重建可先登记意图，尚无镜像完成证据时保持 BLOCKED，不伪造镜像运行号。
-- 只调整来源发布控制表；真实镜像外键和依赖状态表的非空要求继续生效。
alter table source_fact_publication_states
    alter column latest_mirror_run_id drop not null,
    add constraint ck_source_fact_publication_pending_mirror
        check (
            latest_mirror_run_id is not null
            or (readiness_status = 'BLOCKED' and full_publication_requested)
        );
