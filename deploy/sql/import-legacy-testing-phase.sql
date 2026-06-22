-- Import old-platform testing_phase.csv into the new platform testing_phase_calendar table.
--
-- Expected CSV columns, in this exact order:
--   原始数据ID,项目代号,优先级/序号,任务名称（简体中文）,状态/标志
--
-- Usage inside the deployed directory, after copying testing_phase.csv next to this file:
--   sudo docker cp testing_phase.csv qaflex-postgres:/tmp/testing_phase.csv
--   sudo docker cp import-legacy-testing-phase.sql qaflex-postgres:/tmp/import-legacy-testing-phase.sql
--   sudo docker exec -it qaflex-postgres psql -U qaflex -d qaflex -f /tmp/import-legacy-testing-phase.sql

create temporary table legacy_testing_phase_import (
    legacy_source_id_text text,
    legacy_phase_name text,
    legacy_sort_order_text text,
    testing_phase text,
    visible_text text
);

\copy legacy_testing_phase_import from '/tmp/testing_phase.csv' with (format csv, header true, encoding 'UTF8');

insert into testing_phase_calendar (
    project_id,
    legacy_source_id,
    legacy_phase_name,
    legacy_sort_order,
    testing_phase,
    phase_start_at,
    phase_end_at,
    enabled,
    remark,
    updated_at
)
select
    9 as project_id,
    nullif(btrim(legacy_source_id_text), '')::numeric::bigint as legacy_source_id,
    nullif(btrim(legacy_phase_name), '') as legacy_phase_name,
    nullif(btrim(legacy_sort_order_text), '')::integer as legacy_sort_order,
    nullif(btrim(testing_phase), '') as testing_phase,
    null as phase_start_at,
    null as phase_end_at,
    coalesce(nullif(btrim(visible_text), '')::integer, 0) = 1 as enabled,
    '从老平台 testing_phase.csv 导入' as remark,
    current_timestamp
from legacy_testing_phase_import
where nullif(btrim(testing_phase), '') is not null
on conflict (project_id, testing_phase) do update
   set legacy_source_id = excluded.legacy_source_id,
       legacy_phase_name = excluded.legacy_phase_name,
       legacy_sort_order = excluded.legacy_sort_order,
       enabled = excluded.enabled,
       remark = excluded.remark,
       updated_at = current_timestamp;

drop table legacy_testing_phase_import;
