update review_records
   set module_name = btrim(left(btrim(module_name), length(btrim(module_name)) - length('模块'))),
       search_text = null,
       search_compact = null,
       search_spell = null,
       search_initials = null,
       updated_at = current_timestamp
 where deleted = false
   and module_name is not null
   and btrim(module_name) like '%模块'
   and length(btrim(module_name)) > length('模块');
