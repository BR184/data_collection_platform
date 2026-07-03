-- 兼容模式-MatchMode：老平台 SpiderCrowncadQueryBuilder 的默认时间锁使用 spider_crowncad_data.merged_time，
-- 不能用新平台展示用的 merged_local_date_time/merged_at_source 代替，否则 CrownCAD 历史数据会被误排除。
alter table code_review_match_mode_records
    add column if not exists legacy_merged_time_source timestamp;

with raw_merged_time as (
    select
        split_part(table_name, '.', 1) as source_instance,
        raw_payload ->> 'id' as legacy_source_id,
        case
            when nullif(raw_payload ->> 'merged_time', '') ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}'
                then nullif(raw_payload ->> 'merged_time', '')::timestamp
            else null
        end as legacy_merged_time_source
    from legacy_mysql_imported_rows
    where table_name like '%.spider_crowncad_data'
      and raw_payload ? 'id'
      and raw_payload ? 'merged_time'
)
update code_review_match_mode_records r
   set legacy_merged_time_source = coalesce(raw.legacy_merged_time_source, r.merged_at_source)
  from raw_merged_time raw
 where r.source_instance = raw.source_instance
   and r.legacy_source_id = raw.legacy_source_id
   and r.legacy_merged_time_source is null;

update code_review_match_mode_records
   set legacy_merged_time_source = merged_at_source
 where legacy_merged_time_source is null;

create index if not exists idx_code_review_match_mode_records_legacy_merged_time
    on code_review_match_mode_records(source_instance, repository_name, target_branch, legacy_merged_time_source desc);
