create index if not exists idx_code_review_match_mode_records_scope_repository_merged
    on code_review_match_mode_records(
        lower(coalesce(source_instance, 'default')),
        lower(coalesce(repository_name, '')),
        lower(coalesce(project_name, '')),
        merged_at_source desc,
        merge_request_iid desc)
    where upper(coalesce(merge_request_state, '')) = 'MERGED';

create index if not exists idx_code_review_match_mode_records_scope_fields
    on code_review_match_mode_records(
        lower(coalesce(source_instance, 'default')),
        lower(coalesce(target_branch, '')),
        lower(coalesce(module_name, '')),
        lower(coalesce(author_name, '')),
        lower(coalesce(merge_user_name, '')))
    where upper(coalesce(merge_request_state, '')) = 'MERGED';
