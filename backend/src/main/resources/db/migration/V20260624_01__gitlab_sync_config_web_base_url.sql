alter table gitlab_sync_configs
    add column if not exists web_base_url varchar(255);
