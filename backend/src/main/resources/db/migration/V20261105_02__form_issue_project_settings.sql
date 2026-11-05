-- 按来源与项目保存响应/回复共用的接入配置；既有资源权威保持不变。
alter table gitlab_sync_configs
    add column form_issue_settings jsonb not null default '{}'::jsonb
    check (jsonb_typeof(form_issue_settings) = 'object');
comment on column gitlab_sync_configs.form_issue_settings is
    '以项目ID为键的表单接入配置：启用、生效时间、链接投放、平台地址及配置修订';
