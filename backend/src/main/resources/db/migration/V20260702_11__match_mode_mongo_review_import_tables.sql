-- 兼容模式-MatchMode：老平台 MongoDB 评审数据独立原始快照，不与 MySQL 导入表混用。
create table if not exists legacy_mongo_imported_collections (
    id bigserial primary key,
    collection_name varchar(255) not null,
    record_count bigint not null default 0,
    last_synced_at timestamp,
    synced_at timestamp not null default current_timestamp,
    unique (collection_name)
);

create table if not exists legacy_mongo_imported_documents (
    id bigserial primary key,
    collection_name varchar(255) not null,
    document_key varchar(512) not null,
    raw_payload jsonb not null,
    synced_at timestamp not null default current_timestamp,
    unique (collection_name, document_key)
);

create index if not exists idx_legacy_mongo_imported_documents_collection
    on legacy_mongo_imported_documents(collection_name, id);
