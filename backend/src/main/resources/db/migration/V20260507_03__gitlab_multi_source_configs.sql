alter table gitlab_sync_configs
    add column if not exists source_instance varchar(128) not null default 'default';

update gitlab_sync_configs
   set source_instance = 'default'
 where source_instance is null
    or btrim(source_instance) = '';

create unique index if not exists uk_gitlab_sync_configs_source_instance
    on gitlab_sync_configs(source_instance);
