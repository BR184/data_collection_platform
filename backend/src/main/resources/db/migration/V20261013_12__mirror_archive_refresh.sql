-- 通用镜像归档已无现行同步写入者；其本地维护/清理仍必须在提交后生成完整关系版本。
-- 使用既有原生数据集请求，不把正在分页写入的动态 ODS 表当成该归档的来源。
create trigger request_mirror_archive_dataset
    after insert or update or delete on gitlab_mirror_records
    for each statement execute function request_dataset_refresh('FORMAL_RECORD');
