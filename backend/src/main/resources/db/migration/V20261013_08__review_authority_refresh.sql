-- 兼容记录接管/问题映射也是正式数据所有权的一部分，必须和记录一起生成新版本。
create trigger request_review_authority_dataset after insert or update or delete on review_data_match_mode_edit_links
    for each statement execute function request_dataset_refresh('FORMAL_RECORD');
create trigger request_review_problem_authority_dataset after insert or update or delete on review_data_match_mode_problem_edit_links
    for each statement execute function request_dataset_refresh('FORMAL_RECORD');
