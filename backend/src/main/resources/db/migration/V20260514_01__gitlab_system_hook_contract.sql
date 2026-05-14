-- Cut the GitLab hook contract from historical Webhook naming to System Hook naming.
-- This migration is intentionally not backwards-compatible: runtime code no longer reads webhook_* columns or /webhook routes.

alter table gitlab_sync_configs add column if not exists system_hook_secret varchar(255);
alter table gitlab_sync_configs add column if not exists system_hook_enabled boolean not null default false;
alter table gitlab_sync_configs add column if not exists system_hook_project_id bigint;

update gitlab_sync_configs
   set system_hook_secret = webhook_secret
 where system_hook_secret is null
   and exists (
     select 1
       from information_schema.columns
      where table_name = 'gitlab_sync_configs'
        and column_name = 'webhook_secret'
   );

update gitlab_sync_configs
   set system_hook_enabled = webhook_enabled
 where exists (
     select 1
       from information_schema.columns
      where table_name = 'gitlab_sync_configs'
        and column_name = 'webhook_enabled'
   );

update gitlab_sync_configs
   set system_hook_project_id = webhook_project_id
 where system_hook_project_id is null
   and exists (
     select 1
       from information_schema.columns
      where table_name = 'gitlab_sync_configs'
        and column_name = 'webhook_project_id'
   );

drop index if exists uk_gitlab_sync_configs_webhook_secret_enabled;

create unique index if not exists uk_gitlab_sync_configs_system_hook_secret_enabled
    on gitlab_sync_configs(system_hook_secret)
 where source_enabled = true
   and system_hook_enabled = true
   and system_hook_secret is not null
   and btrim(system_hook_secret) <> '';

alter table gitlab_sync_configs drop column if exists webhook_secret;
alter table gitlab_sync_configs drop column if exists webhook_enabled;
alter table gitlab_sync_configs drop column if exists webhook_project_id;

alter table if exists gitlab_webhook_events rename to gitlab_system_hook_events;

update gitlab_sync_logs
   set sync_type = 'SYSTEM_HOOK'
 where sync_type = 'WEBHOOK';

update gitlab_sync_tasks
   set task_type = 'SYSTEM_HOOK'
 where task_type = 'WEBHOOK';

update gitlab_sync_tasks
   set trigger_type = 'SYSTEM_HOOK'
 where trigger_type = 'WEBHOOK';

update gitlab_sync_jobs
   set trigger_type = 'SYSTEM_HOOK'
 where trigger_type = 'WEBHOOK';
