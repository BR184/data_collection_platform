-- 兼容模式-MatchMode：恢复评审的读源隔离，撤销提前将老平台评审核心数据物化到正式表的错误模型。
-- destructive-migration-reviewed: 删除 authority=LEGACY_MANAGED 的错误正式副本及已废弃的评审交接审计字段；PLATFORM_OWNED、未关联正式记录和代码走查审计保留。
-- destructive-migration-recovery: 按发布流程完成 PostgreSQL 备份；回退使用备份恢复，已执行迁移不改写。

alter table code_review_match_mode_db_settings
    add column if not exists review_data_read_mode varchar(32) not null default 'compatibility';

alter table code_review_match_mode_db_settings
    drop constraint if exists ck_code_review_match_mode_review_data_read_mode;

alter table code_review_match_mode_db_settings
    add constraint ck_code_review_match_mode_review_data_read_mode
    check (review_data_read_mode in ('compatibility', 'formal'));

-- 评审正式副本只在用户首次编辑兼容快照时产生。批量交接产生的 LEGACY_MANAGED 副本会遮蔽实时快照，必须整体移除。
delete from review_data_match_mode_problem_edit_links problem_link
using review_data_match_mode_edit_links link
where problem_link.review_record_id = link.review_record_id
  and link.authority = 'LEGACY_MANAGED';

delete from review_records record
using review_data_match_mode_edit_links link
where record.id = link.review_record_id
  and link.authority = 'LEGACY_MANAGED';

delete from review_data_match_mode_edit_links
where authority = 'LEGACY_MANAGED';

alter table review_data_match_mode_edit_links
    drop constraint if exists ck_review_match_mode_edit_links_authority;

alter table review_data_match_mode_edit_links
    add constraint ck_review_match_mode_edit_links_authority
    check (authority = 'PLATFORM_OWNED');

alter table review_data_match_mode_edit_links
    drop column if exists last_handover_at;

-- “评审批量转正式”已撤销，运行审计只服务代码走查交接，不能继续保存固定的“未请求”评审列。
alter table legacy_platform_formal_import_runs
    drop column if exists review_requested,
    drop column if exists review_inserted_count,
    drop column if exists review_updated_count,
    drop column if exists review_skipped_count,
    drop column if exists review_deleted_count,
    drop column if exists review_status;

-- 统一视图是 BI 与动态标签的评审读源。兼容快照仅在已启用且显式选择 compatibility 时可见。
create or replace view review_visible_records as
with description_summaries as (
    select
        description_ref.report_id,
        sum(greatest(coalesce(description.review_scale_pages, 0), 0))::integer
            as review_scale_pages,
        (array_agg(nullif(btrim(description.review_product), '') order by description.id)
            filter (where nullif(btrim(description.review_product), '') is not null))[1]
            as review_product,
        (array_agg(nullif(btrim(description.author), '') order by description.id)
            filter (where nullif(btrim(description.author), '') is not null))[1]
            as author_name,
        (array_agg(nullif(btrim(description.version), '') order by description.id)
            filter (where nullif(btrim(description.version), '') is not null))[1]
            as review_version
    from review_data_match_mode_report_description_refs description_ref
    join review_data_match_mode_descriptions description
      on description.legacy_id = description_ref.description_legacy_id
    group by description_ref.report_id
),
problem_summaries as (
    select
        problem_ref.report_id,
        coalesce(sum(problem.workload), 0) as workload_hours
    from review_data_match_mode_report_problem_refs problem_ref
    join review_data_match_mode_problem_details problem
      on problem.legacy_id = problem_ref.problem_legacy_id
    group by problem_ref.report_id
)
select
    record.id,
    record.project_name,
    record.title,
    record.module_name,
    record.review_type,
    record.review_date,
    record.review_owner,
    record.review_scale_pages,
    record.review_product,
    record.author_name,
    record.review_version,
    record.deleted,
    record.created_at,
    record.updated_at
from review_records record
union all
select
    -abs(report.id) as id,
    coalesce(report.project_name, '') as project_name,
    coalesce(report.title, '') as title,
    coalesce(report.module_name, '') as module_name,
    coalesce(
        nullif(btrim(report.doc_type), ''),
        nullif(btrim(report.source_type), ''),
        nullif(btrim(report.review_type_str), ''),
        '') as review_type,
    report.review_time::date as review_date,
    coalesce(report.review_charger, '') as review_owner,
    coalesce(
        nullif(description_summary.review_scale_pages, 0),
        case
          when problem_summary.workload_hours > 0 and report.review_rate > 0
            then round(report.review_rate * problem_summary.workload_hours)::integer
          else 0
        end,
        0) as review_scale_pages,
    coalesce(description_summary.review_product, report.title, '') as review_product,
    coalesce(description_summary.author_name, '') as author_name,
    coalesce(description_summary.review_version, '') as review_version,
    false as deleted,
    coalesce(report.create_time, report.synced_at) as created_at,
    report.synced_at as updated_at
from review_data_match_mode_reports report
left join description_summaries description_summary
  on description_summary.report_id = report.id
left join problem_summaries problem_summary
  on problem_summary.report_id = report.id
where exists (
    select 1
    from code_review_match_mode_db_settings settings
    where settings.id = 1
      and settings.enabled = true
      and settings.review_data_read_mode = 'compatibility'
)
and not exists (
    select 1
    from review_data_match_mode_edit_links link
    where link.match_mode_report_id = report.id
       or link.match_mode_report_legacy_id = report.legacy_id
);

create or replace view review_visible_problem_items as
select
    problem.id,
    problem.review_record_id,
    problem.reviewer_name,
    problem.workload_hours,
    problem.review_category,
    problem.document_position,
    problem.problem_category,
    problem.problem_description,
    problem.suggested_solution,
    problem.owner_name,
    problem.rejection_reason,
    problem.problem_status,
    problem.deleted,
    problem.created_at,
    problem.updated_at
from review_problem_items problem
union all
select
    -abs(problem.id) as id,
    -abs(report.id) as review_record_id,
    coalesce(problem.reviewer, '') as reviewer_name,
    coalesce(problem.workload, 0) as workload_hours,
    coalesce(problem.review_type, '') as review_category,
    problem.position as document_position,
    coalesce(problem.problem_type, '') as problem_category,
    coalesce(problem.description, '') as problem_description,
    problem.suggestion as suggested_solution,
    problem.liable_person as owner_name,
    problem.reason_for_not_accepting as rejection_reason,
    coalesce(problem.problem_status, '') as problem_status,
    false as deleted,
    coalesce(problem.create_time, report.create_time, report.synced_at) as created_at,
    coalesce(problem.update_time::timestamp, problem.create_time, report.synced_at) as updated_at
from review_data_match_mode_reports report
join review_data_match_mode_report_problem_refs problem_ref
  on problem_ref.report_id = report.id
join review_data_match_mode_problem_details problem
  on problem.legacy_id = problem_ref.problem_legacy_id
where exists (
    select 1
    from code_review_match_mode_db_settings settings
    where settings.id = 1
      and settings.enabled = true
      and settings.review_data_read_mode = 'compatibility'
)
and not exists (
    select 1
    from review_data_match_mode_edit_links link
    where link.match_mode_report_id = report.id
       or link.match_mode_report_legacy_id = report.legacy_id
)
and not exists (
    select 1
    from review_data_match_mode_problem_edit_links problem_link
    where problem_link.match_mode_problem_id = problem.id
       or problem_link.match_mode_problem_legacy_id = problem.legacy_id
);

comment on view review_visible_records is
    '评审当前读源：正式记录始终可见，兼容快照仅在 compatibility 模式显示';
comment on view review_visible_problem_items is
    '评审问题当前读源：正式问题项始终可见，兼容快照仅在 compatibility 模式显示';
