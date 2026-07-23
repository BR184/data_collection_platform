alter table issue_fact
    add column if not exists handler_name text;
