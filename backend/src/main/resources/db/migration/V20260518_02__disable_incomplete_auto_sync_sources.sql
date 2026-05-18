update gitlab_sync_configs
   set source_enabled = false,
       enabled = false,
       auto_sync_enabled = false,
       updated_at = now()
 where coalesce(source_enabled, enabled) = true
   and auto_sync_enabled = true
   and (
        (source_mode = 'DIRECT'
         and (
              nullif(trim(coalesce(db_host, '')), '') is null
              or db_port is null
              or db_port <= 0
              or nullif(trim(coalesce(db_name, '')), '') is null
              or nullif(trim(coalesce(db_username, '')), '') is null
              or nullif(trim(coalesce(db_password, '')), '') is null
         ))
        or
        (coalesce(source_mode, 'DOCKER') <> 'DIRECT'
         and (
              nullif(trim(coalesce(docker_container_name, '')), '') is null
              or nullif(trim(coalesce(db_name, '')), '') is null
         ))
   );
