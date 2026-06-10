-- destructive-migration-reviewed: deprecated semantic segmentation/tag group runtime removed by PM request on 2026-06-10
-- destructive-migration-recovery: restore previous release code and replay backups before this migration if the deprecated runtime tables are needed

drop table if exists segment_filter_preset cascade;
drop table if exists segment_snapshot_member cascade;
drop table if exists segment_snapshot cascade;
drop table if exists segment_member_stage cascade;
drop table if exists segment_member_current cascade;
drop table if exists segment_member_audit cascade;
drop table if exists segment_compute_run cascade;
drop table if exists segment_definition cascade;
drop table if exists semantic_tag_group_build_run cascade;
drop table if exists semantic_tag_value_mapping cascade;
drop table if exists semantic_tag_value cascade;
drop table if exists semantic_tag_group cascade;
drop table if exists semantic_scope_definition cascade;
