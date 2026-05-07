create table if not exists collect_form_record_audit_logs (
    id bigserial primary key,
    record_id bigint references collect_form_records(id) on delete set null,
    action varchar(32) not null,
    editor_id varchar(128),
    editor_username varchar(128),
    reviewer varchar(128),
    remote_address varchar(128),
    user_agent text,
    snapshot_json jsonb not null,
    created_at timestamp not null default current_timestamp
);

create index if not exists idx_collect_form_record_audit_logs_record
    on collect_form_record_audit_logs(record_id, created_at desc);

create index if not exists idx_collect_form_record_audit_logs_editor
    on collect_form_record_audit_logs(editor_username, editor_id, created_at desc);
