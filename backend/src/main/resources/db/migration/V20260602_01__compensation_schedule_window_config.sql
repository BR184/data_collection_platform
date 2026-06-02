alter table gitlab_sync_configs
    add column if not exists compensation_schedule_mode varchar(32) not null default 'INTERVAL';

alter table gitlab_sync_configs
    add column if not exists compensation_time varchar(5) not null default '03:30';

alter table gitlab_sync_configs
    add column if not exists compensation_window_start varchar(5);

alter table gitlab_sync_configs
    add column if not exists compensation_window_end varchar(5);

alter table gitlab_sync_configs
    add column if not exists compensation_missed_window_policy varchar(32) not null default 'SKIP';
