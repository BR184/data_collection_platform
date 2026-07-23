-- 兼容评审主记录按报告一次性聚合精确关系，避免 lateral 子查询逐报告扫描明细表。
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
where not exists (
    select 1
    from review_data_match_mode_edit_links link
    where link.match_mode_report_id = report.id
       or link.match_mode_report_legacy_id = report.legacy_id
);

comment on view review_visible_records is
    '评审正式记录与尚未转正式历史快照的统一可见读模型；兼容关系按报告一次聚合';
