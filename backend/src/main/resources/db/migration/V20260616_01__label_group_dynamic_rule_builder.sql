-- Dynamic label group rules now use a controlled rule-builder DSL.
-- Keep legacy columns physically for already-migrated development databases, but stop using them in code.
alter table label_group_dynamic_rules add column if not exists rule_config_json text;
alter table label_group_dynamic_rules alter column rule_template_key drop not null;
alter table label_group_dynamic_rules alter column rule_params_json drop not null;

create table if not exists label_group_rule_relations (
    id bigserial primary key,
    name varchar(100) not null,
    left_source_key varchar(100) not null,
    left_field_key varchar(100) not null,
    right_source_key varchar(100) not null,
    right_field_key varchar(100) not null,
    match_operator varchar(32) not null,
    normalizer varchar(64),
    enabled boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uk_label_group_rule_relations unique (
        left_source_key, left_field_key, right_source_key, right_field_key, match_operator
    )
);

create index if not exists idx_label_group_rule_relations_left
    on label_group_rule_relations(left_source_key, left_field_key, enabled);
create index if not exists idx_label_group_rule_relations_right
    on label_group_rule_relations(right_source_key, right_field_key, enabled);
