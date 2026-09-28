-- 备份执行资格跨运行唯一，并持久化可验证的子进程身份供失租恢复使用。
alter table backup_state
    add column execution_token varchar(64),
    add column execution_revoked boolean not null default false,
    add column process_id bigint,
    add column process_started_at timestamptz;

update backup_state
   set execution_token = 'legacy-' || active_run_id::text
 where active_run_id is not null;

alter table backup_state
    add constraint ck_backup_state_execution_owner
        check ((active_run_id is null) = (execution_token is null));
