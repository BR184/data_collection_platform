-- 评论展示复用响应权威行；新增列不影响旧应用读取，应用回滚保留列及待同步状态。
alter table form_resource_authorities
    add column entry_comment_revision bigint not null default 1,
    add column entry_comment_synced_revision bigint not null default 0,
    add column entry_comment_changed_at timestamp;

comment on column form_resource_authorities.entry_comment_revision is '完整表单评论期望修订，表单写事务内推进；初值1使既有评论首次补齐';
comment on column form_resource_authorities.entry_comment_synced_revision is 'GitLab已成功写入的捕获修订，不得确认执行期间新增修订';
comment on column form_resource_authorities.entry_comment_changed_at is '最近表单变更时间；尚未填写使用入口登记时间';

create index idx_form_authority_comment_refresh on form_resource_authorities(id)
    where entry_registered_at is not null and entry_comment_id is not null
      and entry_comment_revision > entry_comment_synced_revision;
