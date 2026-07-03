-- 兼容模式-MatchMode：老平台 spider_crowncad_data 允许同一个 MR 对应多条代码走查记录。
-- 早期表结构曾按 GitLab MR 事实表语义加唯一约束，这里按字段组合查找并清理，兼容 PostgreSQL 自动截断后的约束名。
do $$
declare
  constraint_name text;
begin
  for constraint_name in
    select c.conname
      from pg_constraint c
      join pg_class t on t.oid = c.conrelid
      join pg_namespace n on n.oid = t.relnamespace
     where n.nspname = 'public'
       and t.relname = 'code_review_match_mode_records'
       and c.contype = 'u'
       and exists (
         select 1
           from pg_attribute a
          where a.attrelid = t.oid
            and a.attname = 'source_instance'
            and a.attnum = any(c.conkey)
       )
       and exists (
         select 1
           from pg_attribute a
          where a.attrelid = t.oid
            and a.attname = 'project_id'
            and a.attnum = any(c.conkey)
       )
       and exists (
         select 1
           from pg_attribute a
          where a.attrelid = t.oid
            and a.attname = 'merge_request_id'
            and a.attnum = any(c.conkey)
       )
  loop
    execute format('alter table public.code_review_match_mode_records drop constraint if exists %I', constraint_name);
  end loop;
end $$;

create index if not exists idx_code_review_match_mode_records_legacy_source
    on code_review_match_mode_records(source_instance, legacy_source_id);
