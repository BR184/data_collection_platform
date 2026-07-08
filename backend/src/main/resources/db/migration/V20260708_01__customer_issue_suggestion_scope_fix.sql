-- Align CC_Product customer issue scope with the legacy platform:
-- customer issue statistics do not exclude suggestion issues. Only closed
-- 申请否决 / 需求如此 / 设计如此 issues are excluded.
update issue_fact
   set is_excluded = case
         when (lower(coalesce(issue_state, '')) = 'closed' or closed_at_source is not null)
              and (
                coalesce(label_names, '') like '%申请否决%'
                or coalesce(bug_status, '') like '%申请否决%'
              )
           then true
         when (lower(coalesce(issue_state, '')) = 'closed' or closed_at_source is not null)
              and (
                coalesce(label_names, '') like '%需求如此%'
                or coalesce(bug_status, '') like '%需求如此%'
              )
           then true
         when (lower(coalesce(issue_state, '')) = 'closed' or closed_at_source is not null)
              and (
                coalesce(label_names, '') like '%设计如此%'
                or coalesce(bug_status, '') like '%设计如此%'
              )
           then true
         else false
       end,
       exclusion_reason = case
         when (lower(coalesce(issue_state, '')) = 'closed' or closed_at_source is not null)
              and (
                coalesce(label_names, '') like '%申请否决%'
                or coalesce(bug_status, '') like '%申请否决%'
              )
           then '申请否决+Closed'
         when (lower(coalesce(issue_state, '')) = 'closed' or closed_at_source is not null)
              and (
                coalesce(label_names, '') like '%需求如此%'
                or coalesce(bug_status, '') like '%需求如此%'
              )
           then '需求如此+Closed'
         when (lower(coalesce(issue_state, '')) = 'closed' or closed_at_source is not null)
              and (
                coalesce(label_names, '') like '%设计如此%'
                or coalesce(bug_status, '') like '%设计如此%'
              )
           then '设计如此+Closed'
         else null
       end,
       updated_at = current_timestamp
 where project_id = 325
   and deleted = false;

update statistic_board_snapshots
   set status = 'STALE',
       updated_at = current_timestamp
 where board_key in (
       'customer-issue-defect-summary',
       'customer-issue-defect-cause',
       'customer-issue-delay-issues',
       'customer-issue-response-efficiency',
       'customer-issue-by-function'
     )
   and status = 'READY';
