do $$
begin
  -- ODS mirror tables are created dynamically. Fresh installations may run Flyway before
  -- the first GitLab mirror, so the historical backfill must be safe to skip in that state.
  if to_regclass('public.issue_fact') is null
      or to_regclass('public.ods_gitlab_issues') is null
      or to_regclass('public.ods_gitlab_notes') is null
      or to_regclass('public.ods_gitlab_users') is null then
    return;
  end if;

  with latest_fix_users as (
    select distinct on (n.noteable_id)
           n.noteable_id::bigint as issue_id,
           i.project_id::bigint as project_id,
           btrim(author.name) as fix_user
      from ods_gitlab_notes n
      join ods_gitlab_issues i
        on i.id = n.noteable_id
       and coalesce(i.mirror_deleted, false) = false
      join ods_gitlab_users author
        on author.id = n.author_id
       and coalesce(author.mirror_deleted, false) = false
     where coalesce(n.mirror_deleted, false) = false
       and n.noteable_type = 'Issue'
       and nullif(btrim(author.name), '') is not null
       and regexp_replace(split_part(coalesce(n.note, ''), E'\n', 1), E'\r$', '') = '### 1、修复状态'
     order by n.noteable_id, n.created_at desc nulls last, n.id desc
  )
  update issue_fact fact
     set fix_user = source.fix_user
    from latest_fix_users source
   where fact.source_instance = 'default'
     and coalesce(fact.deleted, false) = false
     and fact.project_id = source.project_id
     and fact.issue_id = source.issue_id
     and fact.fix_user is distinct from source.fix_user;

  -- 兼容模式-MatchMode：兼容数据源不使用默认 ODS 表，禁止在此迁移中跨源回填。
  -- 后续删除兼容模式时，本隔离说明可随 source_instance 分支一起清理。
end $$;
