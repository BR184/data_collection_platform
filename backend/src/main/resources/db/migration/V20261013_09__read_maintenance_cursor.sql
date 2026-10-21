-- 有界后台维护按域轮转，不让一个大域长期占据所有回收轮次。
alter table read_publication_domains add column last_gc_at timestamptz;
create index idx_read_domains_gc_cursor on read_publication_domains(last_gc_at nulls first, id);
