-- 正式走查读模型在发布读取迁移后新增 business_order_id/visible_from。
-- PostgreSQL 视图的 * 在创建时冻结，且 replace 不允许改变已有列顺序；重建后统一暴露业务根与修订可见起点。
drop view if exists code_review_formal_records;
create view code_review_formal_records as
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
