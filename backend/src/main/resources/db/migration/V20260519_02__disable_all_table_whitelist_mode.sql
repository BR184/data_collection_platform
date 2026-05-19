update gitlab_sync_configs
   set whitelist_mode = 'RECOMMENDED',
       whitelist_tables = '[]',
       updated_at = current_timestamp
 where whitelist_mode = 'ALL';
