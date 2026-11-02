-- 合并请求事实的冻结输入权威：FORM/COMMENT 随候选事实修订原子发布。
--
-- 统计不再以 form_review_fact 是否存在推断权威；FORM 资源没有记录时仍为 FORM，
-- 样本数为 0 且均时为空，不计入 COMMENT 的合并请求数或分母。
create table if not exists merge_request_fact_authority (
    merge_request_revision_id bigint primary key
        references merge_request_fact(id) on delete cascade,
    form_authority varchar(16) not null,
    created_at timestamp not null default current_timestamp,
    updated_at timestamp not null default current_timestamp,
    constraint ck_merge_request_fact_authority
        check (form_authority in ('FORM', 'COMMENT'))
);

comment on table merge_request_fact_authority is
    'MR 事实修订的冻结输入权威：FORM 或 COMMENT，与事实修订同事务发布';

-- 历史事实缺少冻结权威：已有 FORM 投影行的视为 FORM，其余保持 COMMENT；
-- 新构建由事实持久化在候选事务内写入，不再按投影行数推断。
insert into merge_request_fact_authority(
    merge_request_revision_id, form_authority, created_at, updated_at)
select fact.id,
       case
         when exists (
           select 1
             from form_review_fact review
            where review.merge_request_revision_id = fact.id
         ) then 'FORM'
         else 'COMMENT'
       end,
       current_timestamp,
       current_timestamp
  from merge_request_fact fact
on conflict (merge_request_revision_id) do nothing;
