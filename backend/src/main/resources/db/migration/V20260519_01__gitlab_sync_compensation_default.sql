alter table gitlab_sync_configs
    alter column compensation_interval_minutes set default 360;

update gitlab_sync_configs
   set compensation_interval_minutes = 360
 where compensation_interval_minutes = 10;
