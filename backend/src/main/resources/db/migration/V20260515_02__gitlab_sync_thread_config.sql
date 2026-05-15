alter table gitlab_sync_configs
    add column if not exists sync_thread_mode varchar(32) not null default 'FIXED';

alter table gitlab_sync_configs
    add column if not exists sync_thread_value numeric(8, 3) not null default 2;

alter table gitlab_sync_configs
    add column if not exists max_sync_threads integer;

update gitlab_sync_configs
   set sync_thread_mode = 'FIXED'
 where sync_thread_mode is null
    or btrim(sync_thread_mode) = '';

update gitlab_sync_configs
   set sync_thread_value = 2
 where sync_thread_value is null
    or sync_thread_value <= 0;
