create unique index uk_sync_run_authoritative_scopes_canonical_scope
    on sync_run_authoritative_scopes(run_id, child_table, scope_signature);
