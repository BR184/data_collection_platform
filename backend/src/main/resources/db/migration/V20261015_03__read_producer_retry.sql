-- 安装与镜像发布按读取域持久退避；应用重启不能清掉失败记录或立即反复捕获同一大表。
alter table read_publication_domains
    add column producer_retry_after timestamptz not null default '-infinity',
    add column producer_last_error text;
