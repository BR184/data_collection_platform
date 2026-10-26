package com.data.collection.platform.domain.form;

/** 版本表索引契约；索引名与定义必须与冻结版本一致。 */
public record FormVersionIndex(String tableName, String indexName, String definition) {}
