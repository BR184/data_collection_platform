create table if not exists operation_audit_logs (
    id bigserial primary key,
    username varchar(128) not null default 'guest',
    role varchar(32) not null default 'GUEST',
    http_method varchar(16) not null,
    request_path varchar(512) not null,
    remote_address varchar(128),
    response_status integer not null,
    error_message text,
    created_at timestamp not null default current_timestamp
);

create index if not exists idx_operation_audit_logs_created_at
    on operation_audit_logs (created_at desc);

create index if not exists idx_operation_audit_logs_request_path
    on operation_audit_logs (request_path, created_at desc);
