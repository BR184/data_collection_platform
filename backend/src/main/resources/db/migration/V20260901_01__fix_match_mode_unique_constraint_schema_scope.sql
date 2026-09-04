-- 修正 V20260702_12 在非 public schema 部署时唯一约束清理不生效的问题。
-- 原迁移硬编码 n.nspname = 'public'，但建表迁移 V20260701 的唯一约束
-- (source_instance, project_id, merge_request_id) 落在 Flyway 目标 schema；
-- 在 qaflex_test 等非 public schema 全新部署时约束残留，导致兼容模式
-- "同一 MR 允许多条走查记录"的数据无法写入。这里按 current_schema 重新清理，
-- public 部署已由原迁移处理过时本迁移为幂等 no-op。
do $$
declare
  constraint_name text;
  target_schema text := current_schema();
begin
  for constraint_name in
    select c.conname
      from pg_constraint c
      join pg_class t on t.oid = c.conrelid
      join pg_namespace n on n.oid = t.relnamespace
     where n.nspname = target_schema
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
    execute format('alter table %I.code_review_match_mode_records drop constraint if exists %I',
                   target_schema, constraint_name);
  end loop;
end $$;
