alter table statistic_board_snapshots
    add column if not exists definition_payload jsonb;

