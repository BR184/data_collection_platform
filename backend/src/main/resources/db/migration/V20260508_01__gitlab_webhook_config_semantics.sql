-- Split GitLab source availability, scheduler participation, and Webhook reception.
-- Existing `enabled` is preserved as the legacy source-enabled flag for compatibility.

alter table gitlab_sync_configs
    add column if not exists source_enabled boolean;

alter table gitlab_sync_configs
    add column if not exists webhook_enabled boolean;

update gitlab_sync_configs
   set source_enabled = enabled
 where source_enabled is null;

update gitlab_sync_configs
   set webhook_enabled = (
       webhook_secret is not null
       and btrim(webhook_secret) <> ''
   )
 where webhook_enabled is null;

alter table gitlab_sync_configs
    alter column source_enabled set default true,
    alter column source_enabled set not null,
    alter column webhook_enabled set default false,
    alter column webhook_enabled set not null;

create unique index if not exists uk_gitlab_sync_configs_webhook_secret_enabled
    on gitlab_sync_configs(webhook_secret)
 where source_enabled = true
   and webhook_enabled = true
   and webhook_secret is not null
   and btrim(webhook_secret) <> '';
