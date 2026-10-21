-- 读发布模型（第二层：写入身份切换）。
--
-- P1（V20261012_01）建立了行修订列与半开可见区间，但事实仍按业务自然键原地 upsert：同一业务根
-- 只能有一行，无法并存“上一已发布修订”与“本候选新修订”，也无法保证已发布内容不被原地改写。
-- 本迁移把三张主事实表与两张关系表切换到**修订身份**：
--   * 事实表：删除“每业务根一行”的自然键唯一约束，改为“同一发布身份内每业务根一条修订”的唯一索引；
--     未版本化行（回填前的过渡态，created_by_publication_id 为空）保留一条等价的唯一索引，
--     因此约束覆盖范围不少于原自然键。
--   * 关系表：去掉业务自然键主键，改为“父修订 + 成员/提交”的唯一索引（父修订为空时回退到业务自然键）。
--     关系通过父修订身份与事实版本绑定，原业务键列全部保留。
--   * 任务与全量意图：fact_build_tasks 绑定候选发布身份；来源发布状态与候选分别保存 full_request_revision。
-- 既有已提交迁移零改动；本迁移只替换约束、建索引与增列，不搬运业务数据（回填由独立幂等工具执行）。
-- 删除的唯一约束不是外键依赖对象，删除后原业务键查找由新增的普通索引继续支撑。

-- 1) issue_fact：自然键唯一 → 修订唯一
alter table issue_fact
    drop constraint if exists issue_fact_source_system_source_instance_project_id_issue_i_key;
create unique index uq_issue_fact_revision_identity
    on issue_fact(created_by_publication_id, source_system, source_instance, project_id, issue_id);
create unique index uq_issue_fact_unversioned_identity
    on issue_fact(source_system, source_instance, project_id, issue_id)
    where created_by_publication_id is null;
create index idx_issue_fact_business_identity
    on issue_fact(source_system, source_instance, project_id, issue_id);

-- 2) merge_request_fact：自然键唯一 → 修订唯一
alter table merge_request_fact
    drop constraint if exists merge_request_fact_source_system_source_instance_project_id_key;
create unique index uq_merge_request_fact_revision_identity
    on merge_request_fact(created_by_publication_id, source_system, source_instance, project_id,
                          merge_request_id);
create unique index uq_merge_request_fact_unversioned_identity
    on merge_request_fact(source_system, source_instance, project_id, merge_request_id)
    where created_by_publication_id is null;
create index idx_merge_request_fact_business_identity
    on merge_request_fact(source_system, source_instance, project_id, merge_request_id);

-- 3) integration_test_fact：自然键唯一 → 修订唯一
alter table integration_test_fact
    drop constraint if exists integration_test_fact_source_system_source_instance_project_key;
create unique index uq_integration_test_fact_revision_identity
    on integration_test_fact(created_by_publication_id, source_system, source_instance, project_id,
                             issue_id);
create unique index uq_integration_test_fact_unversioned_identity
    on integration_test_fact(source_system, source_instance, project_id, issue_id)
    where created_by_publication_id is null;
create index idx_integration_test_fact_business_identity
    on integration_test_fact(source_system, source_instance, project_id, issue_id);

-- 4) issue_fact_customer_members：业务自然键主键 → 父修订 + 成员
alter table issue_fact_customer_members
    drop constraint if exists issue_fact_customer_members_pkey;
create unique index uq_issue_fact_customer_members_revision_identity
    on issue_fact_customer_members(issue_fact_revision_id, customer_name)
    where issue_fact_revision_id is not null;
create unique index uq_issue_fact_customer_members_unversioned_identity
    on issue_fact_customer_members(source_system, source_instance, project_id, issue_id, customer_name)
    where issue_fact_revision_id is null;
create index idx_issue_fact_customer_members_business_identity
    on issue_fact_customer_members(source_system, source_instance, project_id, issue_id);

-- 5) merge_request_commit_fact：业务自然键主键 → 父修订 + 提交
alter table merge_request_commit_fact
    drop constraint if exists merge_request_commit_fact_pkey;
create unique index uq_merge_request_commit_fact_revision_identity
    on merge_request_commit_fact(merge_request_revision_id, commit_sha)
    where merge_request_revision_id is not null;
create unique index uq_merge_request_commit_fact_unversioned_identity
    on merge_request_commit_fact(source_system, source_instance, project_id, merge_request_id, commit_sha)
    where merge_request_revision_id is null;
create index idx_merge_request_commit_fact_business_identity
    on merge_request_commit_fact(source_system, source_instance, project_id, merge_request_id);

-- 6) 事实构建任务绑定候选发布身份；目标任务写入候选修订，不再原地改写已发布内容。
alter table fact_build_tasks
    add column if not exists publication_id uuid;
create index idx_fact_build_tasks_publication
    on fact_build_tasks(publication_id)
    where publication_id is not null;

-- 7) 全量发布意图带修订号（CAS 结算，防止较旧全量清掉较新意图）；候选保存其捕获值。
alter table source_fact_publication_states
    add column if not exists full_request_revision bigint not null default 0;
alter table read_publications
    add column if not exists captured_full_request_revision bigint not null default 0;
