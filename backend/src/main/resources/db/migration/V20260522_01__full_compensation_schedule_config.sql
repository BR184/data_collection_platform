alter table gitlab_sync_configs
    add column if not exists full_compensation_enabled boolean not null default true;

alter table gitlab_sync_configs
    add column if not exists full_compensation_time varchar(5) not null default '02:00';
