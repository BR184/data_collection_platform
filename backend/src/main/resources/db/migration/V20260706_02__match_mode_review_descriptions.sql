-- 兼容模式-MatchMode：老平台评审页数来自 Description.count，不能使用 ReviewReport.defectValue。
create table if not exists review_data_match_mode_descriptions (
    id bigserial primary key,
    legacy_id varchar(128) not null,
    review_product varchar(512),
    version varchar(128),
    author varchar(128),
    review_scale_pages integer,
    unit varchar(64),
    raw_payload jsonb,
    synced_at timestamp not null default current_timestamp,
    unique (legacy_id)
);

create index if not exists idx_review_match_mode_description_legacy
    on review_data_match_mode_descriptions(legacy_id);

update code_review_match_mode_db_settings
   set selected_mongo_collection_names =
       case
         when selected_mongo_collection_names is null
              or trim(selected_mongo_collection_names) = '' then 'reviewReport,problemDetail,description'
         when selected_mongo_collection_names !~ '(^|,)description(,|$)' then selected_mongo_collection_names || ',description'
         else selected_mongo_collection_names
       end
 where id = 1;
