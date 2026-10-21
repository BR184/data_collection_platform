-- 一次预热固定业务日；缓存和页面指纹包含业务日，跨午夜不能复用另一日的计算结果。
alter table read_publications add column read_business_date date;
alter table statistic_board_snapshots add column business_date date;
alter table page_record_snapshots add column business_date date;
create index idx_statistic_snapshot_business_date on statistic_board_snapshots(publication_id, business_date);
create index idx_record_snapshot_business_date on page_record_snapshots(publication_id, business_date);

-- 客户端准备请求身份只用于账号/会话内幂等，不接受任意发布 UUID。
alter table read_view_contexts add column client_view_id uuid;
create unique index uq_read_view_client_identity
    on read_view_contexts(subject, session_id, client_view_id) where client_view_id is not null;
