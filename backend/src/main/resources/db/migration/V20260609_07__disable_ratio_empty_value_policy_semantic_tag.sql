update semantic_tag_value
   set enabled = false,
       updated_at = current_timestamp
 where group_id in (
       select id
         from semantic_tag_group
        where domain = 'issue'
          and group_key = 'ratio_empty_value_policy'
   );

update semantic_tag_group
   set enabled = false,
       updated_at = current_timestamp
 where domain = 'issue'
   and group_key = 'ratio_empty_value_policy';
