-- N4 R04 前向修正：按事实所属发布的冻结捕获证据回填历史权威。
--
-- V20261102_04 只按是否存在 form_review_fact 投影行回填，会把“发布时已有 CONSUMED FORM
-- 捕获证据、但走查记录为空或全部作废”的历史事实误标为 COMMENT。本迁移只修正权威标记：
-- 同一 created_by_publication_id 下存在按完整来源/项目/MR 身份及 code_review 模板匹配的
-- CONSUMED 捕获时，保留 FORM；没有 FORM 发布证据的历史 COMMENT 不变。事实、记录与捕获
-- 证据均不改写。

update merge_request_fact_authority authority
   set form_authority = 'FORM',
       updated_at = current_timestamp
  from merge_request_fact fact
  join form_publication_captures capture
    on capture.publication_id = fact.created_by_publication_id
  join form_change_heads head
    on head.id = capture.head_id
 where authority.merge_request_revision_id = fact.id
   and authority.form_authority = 'COMMENT'
   and capture.status = 'CONSUMED'
   and head.resource_type = 'merge_request'
   and head.template_key = 'code_review'
   and head.source_instance = fact.source_instance
   and head.project_id = fact.project_id
   and head.resource_id = fact.merge_request_iid;
