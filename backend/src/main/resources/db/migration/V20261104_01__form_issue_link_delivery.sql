-- 表单就绪投放记账：响应权威行记录首次投放登记与 GitLab 评论编号。
-- 沿用资源唯一键，只承载投放状态；权威修订、范围/版本与事实链路规则不变。
alter table form_resource_authorities
    add column if not exists entry_registered_at timestamp;

alter table form_resource_authorities
    add column if not exists entry_comment_id bigint;

comment on column form_resource_authorities.entry_registered_at is
    '投放登记时间：仅在完整来源发布事务内为就绪的 FORM 议题写入；空表示未登记';
comment on column form_resource_authorities.entry_comment_id is
    'GitLab 本功能评论编号：一条评论承载响应/回复两个入口；空表示尚未发送成功';

create index if not exists idx_form_resource_authorities_entry_pending
    on form_resource_authorities(entry_registered_at)
    where entry_registered_at is not null and entry_comment_id is null;
