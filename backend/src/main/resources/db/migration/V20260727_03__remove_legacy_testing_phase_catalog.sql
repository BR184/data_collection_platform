-- destructive-migration-reviewed: 统一议题范围目录目标模型，2026-07-27
-- destructive-migration-recovery: 发布前备份数据库；恢复时使用备份或依据 V20260727_02 从新目录反向生成旧表
drop table testing_phase_calendar;
drop table testing_phase_groups;

