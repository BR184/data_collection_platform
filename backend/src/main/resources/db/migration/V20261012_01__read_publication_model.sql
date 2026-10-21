-- 读发布模型（第一层：结构）。
--
-- 现状：平台只保存“当前事实”，没有可寻址的历史完整版本，因此读侧一旦遇到未收敛的同步窗口
-- 就只能拒绝读取或退化为最近 READY 快照。本迁移为“行修订 + 半开可见区间 + 原子域发布指针”
-- 建立元数据，让一个一致性域可以同时保留多个完整读版本，把“数据是否新鲜”与“数据能否读”分离。
--
-- 本迁移只做加法：
--   1) 新增读发布元数据表；
--   2) 给三张主事实表补可空修订列与可见区间索引；
--   3) 给关系表补到父修订的绑定列。
-- 既有自然键唯一约束、既有读写路径保持不变。把自然键唯一约束替换为修订唯一约束、并切换到
-- 修订写入与版本读取，属于后续工作单元；此处只约束“已发布开放修订”，既有行因 visible_from 为空
-- 而不进入这些部分索引或外键校验。
--
-- 时间列一律 timestamp with time zone（UTC），业务日与来源时区契约沿现有定义，不在本单元改口径。

-- 不可变定义快照：范围 / 标签组 / 词典等配置的规范化负载，供发布身份引用。
create table read_definition_snapshots (
    id uuid primary key,
    definition_kind varchar(64) not null,
    schema_version varchar(64) not null,
    payload jsonb not null,
    checksum varchar(128) not null,
    created_at timestamp with time zone not null default current_timestamp,
    constraint uk_read_definition_snapshots_checksum unique (definition_kind, checksum)
);

-- 一致性域：当前发布指针与候选序号分配。域键 = producer_kind + source_instance + fact_type + table_key。
create table read_publication_domains (
    id uuid primary key,
    producer_kind varchar(32) not null,
    source_instance varchar(128) not null,
    fact_type varchar(64) not null default '',
    table_key varchar(255) not null default '',
    current_publication_id uuid,
    next_sequence bigint not null default 1,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint uk_read_publication_domains_key
        unique (producer_kind, source_instance, fact_type, table_key),
    constraint ck_read_publication_domains_sequence check (next_sequence > 0)
);

-- 持久完整发布身份。
create table read_publications (
    id uuid primary key,
    domain_id uuid not null references read_publication_domains(id),
    epoch_id uuid not null,
    sequence_no bigint not null,
    base_publication_id uuid,
    source_seal jsonb not null,
    covered_change_version bigint not null,
    definition_snapshot_id uuid references read_definition_snapshots(id),
    state varchar(32) not null,
    producer_run_id varchar(64),
    producer_task_id bigint,
    producer_lease_owner varchar(128),
    data_as_of timestamp with time zone not null,
    published_at timestamp with time zone,
    created_at timestamp with time zone not null default current_timestamp,
    updated_at timestamp with time zone not null default current_timestamp,
    constraint ck_read_publications_state check (state in (
        'BUILDING', 'PROJECTING', 'READY_TO_PUBLISH', 'PUBLISHED', 'ABORTED', 'RETIRING', 'RETIRED')),
    constraint ck_read_publications_sequence check (sequence_no >= 0),
    constraint ck_read_publications_covered check (covered_change_version >= 0)
);

alter table read_publication_domains
    add constraint fk_read_publication_domains_current
    foreign key (current_publication_id) references read_publications(id) on delete set null;

-- 候选期为 0 序号且可并存多条（仅一条处于活动候选状态），发布时才分配域内正序号。
create unique index uk_read_publications_domain_sequence
    on read_publications(domain_id, sequence_no)
    where sequence_no > 0;

-- 单个一致性域最多一个活动候选（BUILDING / PROJECTING / READY_TO_PUBLISH）。
create unique index uq_read_publications_active_candidate
    on read_publications(domain_id)
    where state in ('BUILDING', 'PROJECTING', 'READY_TO_PUBLISH');

create index idx_read_publications_domain_state
    on read_publications(domain_id, state);

create index idx_read_publications_retirable
    on read_publications(domain_id, published_at)
    where state in ('RETIRING', 'RETIRED');

-- 候选变更清单：根级 captured_change_version、删除证明与重试幂等。
-- 唯一键与基线排除都按完整业务身份（来源系统/来源实例/事实类型/项目/根），不同来源或项目的同号根不得互相覆盖。
create table read_publication_fact_roots (
    publication_id uuid not null references read_publications(id) on delete cascade,
    source_system varchar(64) not null default 'GITLAB',
    source_instance varchar(128) not null,
    fact_type varchar(64) not null,
    project_id bigint not null,
    root_id bigint not null,
    captured_change_version bigint not null,
    previous_revision_id bigint,
    next_revision_id bigint,
    operation varchar(16) not null,
    created_at timestamp with time zone not null default current_timestamp,
    primary key (publication_id, source_system, source_instance, fact_type, project_id, root_id),
    constraint ck_read_publication_fact_roots_operation
        check (operation in ('UPSERT', 'DELETE')),
    constraint ck_read_publication_fact_roots_type
        check (fact_type in ('ISSUE', 'MERGE_REQUEST', 'INTEGRATION_TEST')),
    constraint ck_read_publication_fact_roots_covered check (captured_change_version >= 0),
    constraint ck_read_publication_fact_roots_project check (project_id >= 0)
);

create index idx_read_publication_fact_roots_root
    on read_publication_fact_roots(source_system, source_instance, fact_type, project_id, root_id);

-- 候选投影身份，以及发布时才提交的可读 generation 清单（不从最新 generation 猜测旧候选完成）。
create table read_publication_scope_generations (
    publication_id uuid not null references read_publications(id) on delete cascade,
    scope_type varchar(64) not null,
    scope_key varchar(512) not null,
    candidate_generation bigint not null,
    committed_generation bigint,
    primary key (publication_id, scope_type, scope_key),
    constraint ck_read_publication_scope_generations_scope
        check (scope_type in ('FULL_EPOCH', 'GLOBAL_VIEW', 'PROJECT', 'ISSUE_SCOPE_GROUP')),
    constraint ck_read_publication_scope_generations_value check (candidate_generation > 0)
);

-- 页面固定读取上下文与租约；历史页达到安全换版边界时由 rebind_deadline 给出结构化换版信号。
create table read_view_contexts (
    id uuid primary key,
    subject varchar(128) not null,
    session_id varchar(128),
    page_key varchar(128) not null,
    scope_key varchar(512) not null,
    mode varchar(32) not null,
    business_date date,
    rule_version varchar(128),
    created_at timestamp with time zone not null default current_timestamp,
    expires_at timestamp with time zone not null,
    rebind_deadline timestamp with time zone,
    constraint ck_read_view_contexts_mode check (mode in ('PUBLISHED', 'CANDIDATE')),
    constraint ck_read_view_contexts_expiry check (expires_at > created_at)
);

create index idx_read_view_contexts_owner on read_view_contexts(subject, page_key);
create index idx_read_view_contexts_expiry on read_view_contexts(expires_at);
create index idx_read_view_contexts_rebind
    on read_view_contexts(rebind_deadline)
    where rebind_deadline is not null;

-- 一次冻结的多域版本组合。publication_id 不级联删除，避免视图引用随回收误删仍被引用的发布。
create table read_view_publications (
    view_id uuid not null references read_view_contexts(id) on delete cascade,
    domain_id uuid not null references read_publication_domains(id),
    publication_id uuid not null references read_publications(id),
    primary key (view_id, domain_id)
);

create index idx_read_view_publications_publication
    on read_view_publications(publication_id);

-- 主事实表修订列。物理 id 即修订身份；原 source_system/source_instance/project_id/根 仍是业务根身份。
-- 组合含义：未版本化（全空）｜候选修订（epoch/created_by 非空、visible_from 为空）｜已发布修订
-- （epoch/created_by 非空、visible_from 为域内序号，visible_to 为空表示开放）。
alter table issue_fact
    add column if not exists publication_epoch_id uuid,
    add column if not exists created_by_publication_id uuid,
    add column if not exists visible_from bigint,
    add column if not exists visible_to bigint;

alter table issue_fact
    add constraint ck_issue_fact_visible_interval
        check (visible_to is null or (visible_from is not null and visible_to > visible_from)),
    add constraint ck_issue_fact_publication_fields
        check (
            (publication_epoch_id is null and created_by_publication_id is null
                and visible_from is null and visible_to is null)
            or (publication_epoch_id is not null and created_by_publication_id is not null)
        );

-- 同一 epoch 同一业务根最多一个已发布开放修订。既有行 visible_from 为空，不进入该索引。
create unique index uq_issue_fact_open_published_revision
    on issue_fact(publication_epoch_id, source_system, source_instance, project_id, issue_id)
    where visible_from is not null and visible_to is null;

create index idx_issue_fact_epoch_root_interval
    on issue_fact(publication_epoch_id, source_system, source_instance, project_id, issue_id,
                  visible_from, visible_to)
    where visible_from is not null;

alter table merge_request_fact
    add column if not exists publication_epoch_id uuid,
    add column if not exists created_by_publication_id uuid,
    add column if not exists visible_from bigint,
    add column if not exists visible_to bigint;

alter table merge_request_fact
    add constraint ck_merge_request_fact_visible_interval
        check (visible_to is null or (visible_from is not null and visible_to > visible_from)),
    add constraint ck_merge_request_fact_publication_fields
        check (
            (publication_epoch_id is null and created_by_publication_id is null
                and visible_from is null and visible_to is null)
            or (publication_epoch_id is not null and created_by_publication_id is not null)
        );

create unique index uq_merge_request_fact_open_published_revision
    on merge_request_fact(publication_epoch_id, source_system, source_instance, project_id,
                          merge_request_id)
    where visible_from is not null and visible_to is null;

create index idx_merge_request_fact_epoch_root_interval
    on merge_request_fact(publication_epoch_id, source_system, source_instance, project_id,
                          merge_request_id, visible_from, visible_to)
    where visible_from is not null;

alter table integration_test_fact
    add column if not exists publication_epoch_id uuid,
    add column if not exists created_by_publication_id uuid,
    add column if not exists visible_from bigint,
    add column if not exists visible_to bigint;

alter table integration_test_fact
    add constraint ck_integration_test_fact_visible_interval
        check (visible_to is null or (visible_from is not null and visible_to > visible_from)),
    add constraint ck_integration_test_fact_publication_fields
        check (
            (publication_epoch_id is null and created_by_publication_id is null
                and visible_from is null and visible_to is null)
            or (publication_epoch_id is not null and created_by_publication_id is not null)
        );

create unique index uq_integration_test_fact_open_published_revision
    on integration_test_fact(publication_epoch_id, source_system, source_instance, project_id, issue_id)
    where visible_from is not null and visible_to is null;

create index idx_integration_test_fact_epoch_root_interval
    on integration_test_fact(publication_epoch_id, source_system, source_instance, project_id, issue_id,
                             visible_from, visible_to)
    where visible_from is not null;

-- 客户成员关系绑定父修订：查询必须绑定父修订，不能只按 issue_id 联查所有历史关系。
-- 既有行该列为空，外键不校验，原有成员写入与整根删除行为不变。
alter table issue_fact_customer_members
    add column if not exists issue_fact_revision_id bigint;

alter table issue_fact_customer_members
    add constraint fk_issue_fact_customer_members_revision
        foreign key (issue_fact_revision_id) references issue_fact(id);

create index idx_issue_fact_customer_members_parent_revision
    on issue_fact_customer_members(issue_fact_revision_id);

-- MR 提交关系绑定 MR 父修订；同一提交在不同 MR 的既有去重语义不变（仍由原主键与来源条件决定）。
alter table merge_request_commit_fact
    add column if not exists merge_request_revision_id bigint;

alter table merge_request_commit_fact
    add constraint fk_merge_request_commit_fact_revision
        foreign key (merge_request_revision_id) references merge_request_fact(id);

create index idx_merge_request_commit_fact_parent_revision
    on merge_request_commit_fact(merge_request_revision_id);
