alter table gitlab_sync_configs
    add column if not exists api_token varchar(255);

alter table gitlab_sync_configs
    add column if not exists delay_label_writeback_enabled boolean not null default false;
