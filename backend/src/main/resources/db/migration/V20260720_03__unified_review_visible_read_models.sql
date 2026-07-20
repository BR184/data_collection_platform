-- 评审统一可见读模型只负责合并正式数据与尚未转正式的历史快照。
-- 页面、看板和导出中的派生指标仍由各自统一计算器重算，避免在数据库视图中复制统计口径。
create or replace view review_visible_records as
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
left join lateral (
    select
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
    from review_data_match_mode_descriptions description
    where coalesce(report.description_ids, '') like '%' || description.legacy_id || '%'
) description_summary on true
left join lateral (
    select coalesce(sum(problem.workload), 0) as workload_hours
    from review_data_match_mode_problem_details problem
    where coalesce(report.problem_detail_ids, '') like '%' || problem.legacy_id || '%'
) problem_summary on true
where not exists (
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
join review_data_match_mode_problem_details problem
  on coalesce(report.problem_detail_ids, '') like '%' || problem.legacy_id || '%'
where not exists (
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
    '评审正式记录与尚未转正式历史快照的统一可见读模型';
comment on view review_visible_problem_items is
    '评审正式问题项与尚未转正式历史快照的统一可见读模型';
