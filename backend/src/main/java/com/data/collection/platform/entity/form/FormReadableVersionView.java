package com.data.collection.platform.entity.form;

/** detail 可读版本元数据：目录/版本身份与冻结规格哈希，不含业务数据。 */
public record FormReadableVersionView(
    String versionId,
    String versionLabel,
    String versionStatus,
    String instanceMode,
    String fieldSpecHash) {}
