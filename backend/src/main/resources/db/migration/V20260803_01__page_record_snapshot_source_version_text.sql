-- 事实发布代际由多个范围版本组成，长度不应受固定字符上限限制。
alter table page_record_snapshots
  alter column source_version type text
  using source_version::text;
