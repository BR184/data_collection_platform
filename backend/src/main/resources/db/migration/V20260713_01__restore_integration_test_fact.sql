-- 研发质量看板继续依赖正式 GitLab 镜像中的集成测试评论。
-- 这里只恢复正式事实表；兼容模式-MatchMode 不导入也不读取该表。
create table if not exists integration_test_fact (
    id bigserial primary key,
    source_system varchar(64) not null default 'GITLAB',
    source_instance varchar(128) not null default 'default',
    ingest_channel varchar(64) not null default 'MIRROR',
    source_summary varchar(255),
    raw_payload text,
    project_id bigint not null,
    project_name varchar(255),
    issue_id bigint not null,
    issue_iid bigint not null,
    issuable_reference varchar(128),
    title varchar(512) not null default '',
    issue_state varchar(64),
    author_name varchar(128),
    assignee_name varchar(128),
    created_at_source timestamp,
    updated_at_source timestamp,
    ods_updated_at timestamp,
    note_id bigint,
    note_created_at_source timestamp,
    note_updated_at_source timestamp,
    module_name varchar(255),
    function_name varchar(255),
    executor varchar(128),
    testing_phase varchar(128),
    execute_case integer,
    pass_case integer,
    not_pass_case integer,
    not_pass_case_now integer,
    problem_case integer,
    exception_count integer,
    pass_rate numeric(8, 2),
    legal boolean not null default false,
    parse_status varchar(32) not null default 'PARTIAL',
    validation_reason varchar(255),
    label_names text,
    function_labels text,
    deleted boolean not null default false,
    fact_refreshed_at timestamp not null default current_timestamp,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    unique (source_system, source_instance, project_id, issue_id)
);

create index if not exists idx_integration_test_fact_phase
    on integration_test_fact(source_instance, project_id, testing_phase)
    where deleted = false;

create index if not exists idx_integration_test_fact_module
    on integration_test_fact(source_instance, project_id, testing_phase, module_name)
    where deleted = false;

create index if not exists idx_integration_test_fact_issue
    on integration_test_fact(source_instance, project_id, issue_iid);
