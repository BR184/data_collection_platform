package com.data.collection.platform.domain.form;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** 一个结构版本的实例/记录表定义及可复核摘要。 */
public record FormVersionSchema(
    long scopeId,
    long versionId,
    String templateKey,
    String instanceTable,
    String recordTable,
    List<String> statements,
    List<FormVersionColumn> columns,
    List<FormVersionConstraint> constraints,
    List<FormVersionIndex> indexes,
    String recordIdDefault,
    JsonNode ddlSummary,
    String ddlHash) {}
