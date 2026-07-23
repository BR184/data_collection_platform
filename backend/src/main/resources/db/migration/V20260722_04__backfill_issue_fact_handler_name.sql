-- Historical GitLab facts were built before handler_name existed. The mirrored source only
-- exposes the assignment identity for these rows, which is the same source used by the
-- legacy CC_PRODUCT collector for its handler value.
update issue_fact
   set handler_name = assignee_name,
       updated_at = current_timestamp
 where nullif(btrim(handler_name), '') is null
   and nullif(btrim(assignee_name), '') is not null;
