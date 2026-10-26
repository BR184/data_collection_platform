package com.data.collection.platform.service.form;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/** 已按冻结规格规范化的字段值；列值供持久化，规范节点供首次指纹。 */
public record ValidatedFormValues(
    Map<String, Object> columnValues,
    ObjectNode canonicalFields,
    Map<String, Object> responseFields,
    Map<String, String> fieldNames,
    Map<String, Boolean> snapshotValues) {}
