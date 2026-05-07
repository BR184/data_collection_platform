alter table operation_audit_logs
    add column if not exists request_summary text;
