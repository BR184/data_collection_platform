alter table testing_phase_calendar add column if not exists legacy_source_id bigint;
alter table testing_phase_calendar add column if not exists legacy_phase_name varchar(128);
alter table testing_phase_calendar add column if not exists legacy_sort_order integer;
alter table testing_phase_calendar alter column phase_start_at drop not null;

update testing_phase_calendar
   set legacy_phase_name = coalesce(nullif(btrim(legacy_phase_name), ''), regexp_replace(testing_phase, '(第[一二三四五六七八九十0-9]+轮系统测试|回归测试|系统测试)$', ''))
 where legacy_phase_name is null
   and testing_phase is not null;

create index if not exists idx_testing_phase_calendar_legacy_name
    on testing_phase_calendar(project_id, legacy_phase_name, enabled, legacy_sort_order);
