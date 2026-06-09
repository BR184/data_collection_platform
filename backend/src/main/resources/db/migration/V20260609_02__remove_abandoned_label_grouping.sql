-- Remove the abandoned label grouping compatibility schema.
-- destructive-migration-reviewed: requested by project owner on 2026-06-09 because the implementation and names were misleading.
-- destructive-migration-recovery: restore from database backup or replay the locked V20260605_01, V20260608_01, and V20260609_01 migrations in a recovery database if historical rows are needed.

drop table if exists tag_value_mapping cascade;
drop table if exists tag_value cascade;
drop table if exists tag_group cascade;
