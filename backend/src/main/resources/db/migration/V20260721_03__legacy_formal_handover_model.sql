-- 兼容模式-MatchMode：老平台数据交接的权威归属、完整评审内容和统一正式读模型。
-- destructive-migration-reviewed: 用户于 2026-07-21 确认直接删除失效评审读源双轨。
-- destructive-migration-recovery: 发布前数据库备份可恢复原列；应用回退时以前向迁移重建该列。

alter table review_data_match_mode_edit_links
    add column if not exists authority varchar(32) not null default 'PLATFORM_OWNED',
    add column if not exists last_handover_at timestamp;

alter table review_data_match_mode_edit_links
    drop constraint if exists ck_review_match_mode_edit_links_authority;

alter table review_data_match_mode_edit_links
    add constraint ck_review_match_mode_edit_links_authority
    check (authority in ('LEGACY_MANAGED', 'PLATFORM_OWNED'));

create table if not exists review_data_match_mode_contents (
    id bigserial primary key,
    match_mode_report_legacy_id varchar(128) not null,
    content_order integer not null default 0,
    reviewer_name varchar(128),
    assignment_content text,
    independent_workload_hours numeric(8, 2),
    independent_problem_count integer,
    meeting_workload_hours numeric(8, 2),
    meeting_problem_count integer,
    raw_payload jsonb,
    synced_at timestamp not null default current_timestamp,
    unique (match_mode_report_legacy_id, content_order)
);

create index if not exists idx_review_match_mode_contents_report
    on review_data_match_mode_contents(match_mode_report_legacy_id, content_order, id);

alter table legacy_platform_formal_import_runs
    add column if not exists operator_username varchar(128) not null default 'system',
    add column if not exists settings_updated_at timestamp,
    add column if not exists review_status varchar(32) not null default 'NOT_REQUESTED',
    add column if not exists code_review_status varchar(32) not null default 'NOT_REQUESTED',
    add column if not exists review_skipped_count bigint not null default 0,
    add column if not exists review_deleted_count bigint not null default 0,
    add column if not exists code_review_deleted_count bigint not null default 0,
    add column if not exists source_summary jsonb not null default '{}'::jsonb,
    add column if not exists finished_at timestamp;

create or replace view code_review_formal_records as
with promoted_sources as (
    select distinct lower(coalesce(source_instance, '')) as business_source
      from merge_request_fact
     where source_system = 'LEGACY_PLATFORM'
       and deleted = false
       and lower(coalesce(source_instance, '')) in ('cc', 'dgm')
), scoped as (
    select fact.*,
           case
             when fact.source_system = 'LEGACY_PLATFORM'
               then lower(coalesce(fact.source_instance, 'cc'))
             when lower(coalesce(fact.source_instance, 'default')) = 'dgm'
               then 'dgm'
             else 'cc'
           end as business_source
      from merge_request_fact fact
     where fact.deleted = false
)
select scoped.*
  from scoped
 where (
       scoped.source_system = 'LEGACY_PLATFORM'
       and scoped.business_source in ('cc', 'dgm')
     )
    or (
       scoped.source_system <> 'LEGACY_PLATFORM'
       and scoped.business_source = 'cc'
       and lower(coalesce(scoped.source_instance, 'default')) = 'default'
       and scoped.project_id = 9
       and not exists (
         select 1 from promoted_sources where business_source = 'cc'
       )
     )
    or (
       scoped.source_system <> 'LEGACY_PLATFORM'
       and scoped.business_source = 'dgm'
       and lower(coalesce(scoped.source_instance, '')) = 'dgm'
       and not exists (
         select 1 from promoted_sources where business_source = 'dgm'
       )
     );

comment on view code_review_formal_records is
    '代码走查正式统一读模型：按 CC/DGM 优先使用已交接老平台事实，否则使用 GitLab 正式事实';

alter table code_review_match_mode_db_settings
    drop column if exists review_data_read_mode;
