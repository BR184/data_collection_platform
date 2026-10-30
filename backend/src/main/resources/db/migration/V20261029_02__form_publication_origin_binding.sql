-- N3 整改：发布任务持久冻结原范围绑定证据，与拟发布绑定（frozen_binding）分离。
-- 旧协议任务与历史行保留 NULL；v2 新建任务必须写入三项，切换/恢复时在范围锁内复核。
alter table form_template_publications
    add column origin_binding jsonb,
    add column origin_binding_revision bigint,
    add column origin_binding_hash char(64);
