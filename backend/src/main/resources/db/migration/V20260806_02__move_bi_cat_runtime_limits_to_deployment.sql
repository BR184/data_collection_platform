-- destructive-migration-reviewed: CAT 运行保护参数从可编辑数据库设置收敛为部署配置。
-- destructive-migration-recovery: 迁移前导出已调整的保护参数到部署环境；恢复时使用迁移前数据库备份。
alter table bi_cat_mirror_configs
    drop constraint ck_bi_cat_mirror_connect_timeout,
    drop constraint ck_bi_cat_mirror_read_timeout,
    drop constraint ck_bi_cat_mirror_response_limit,
    drop constraint ck_bi_cat_mirror_retention,
    drop column connect_timeout_ms,
    drop column read_timeout_ms,
    drop column max_response_bytes,
    drop column retained_snapshot_count;
