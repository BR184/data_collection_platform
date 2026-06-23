create table if not exists testing_phase_groups (
    id bigserial primary key,
    project_id bigint not null,
    name varchar(128) not null,
    sort_order integer not null default 0,
    enabled boolean not null default true,
    remark varchar(255),
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint uk_testing_phase_groups_project_name unique (project_id, name)
);

alter table testing_phase_calendar
    add column if not exists phase_group_id bigint;

alter table testing_phase_calendar
    add column if not exists child_sort_order integer;

insert into testing_phase_groups (project_id, name, sort_order, enabled, remark, updated_at)
select c.project_id,
       c.legacy_phase_name,
       coalesce(min(c.legacy_sort_order), 0) as sort_order,
       bool_or(c.enabled) as enabled,
       '从 testing_phase_calendar 自动生成父级阶段',
       current_timestamp
  from testing_phase_calendar c
 where c.legacy_phase_name is not null
   and btrim(c.legacy_phase_name) <> ''
 group by c.project_id, c.legacy_phase_name
on conflict (project_id, name) do update
   set sort_order = excluded.sort_order,
       enabled = excluded.enabled,
       updated_at = current_timestamp;

update testing_phase_calendar c
   set phase_group_id = g.id,
       child_sort_order = ranked.child_sort_order,
       updated_at = current_timestamp
  from testing_phase_groups g,
       (
        select id,
               row_number() over (
                   partition by project_id, legacy_phase_name
                   order by
                       case
                           when testing_phase like '%第一轮系统测试' then 1
                           when testing_phase like '%第二轮系统测试' then 2
                           when testing_phase like '%第三轮系统测试' then 3
                           when testing_phase like '%第四轮系统测试' then 4
                           when testing_phase like '%第五轮系统测试' then 5
                           when testing_phase like '%第六轮系统测试' then 6
                           when testing_phase like '%第七轮系统测试' then 7
                           when testing_phase like '%第八轮系统测试' then 8
                           when testing_phase like '%第九轮系统测试' then 9
                           when testing_phase like '%第十轮系统测试' then 10
                           when testing_phase like '%回归测试' then 99
                           else 50
                       end,
                       legacy_source_id asc nulls last,
                       testing_phase asc
               ) as child_sort_order
          from testing_phase_calendar
         where legacy_phase_name is not null
           and btrim(legacy_phase_name) <> ''
       ) ranked
 where g.project_id = c.project_id
   and g.name = c.legacy_phase_name
   and ranked.id = c.id;

create index if not exists idx_testing_phase_groups_context
    on testing_phase_groups(project_id, enabled, sort_order, name);

create index if not exists idx_testing_phase_calendar_group
    on testing_phase_calendar(phase_group_id, enabled, child_sort_order);
