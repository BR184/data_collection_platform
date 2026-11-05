package com.data.collection.platform.domain.form;

import java.util.List;

/** 结构版本冻结的枚举或原因词典。 */
public record FormDictionarySpec(
    String name, String version, String source, String canonicalSha256, List<FormDictionaryItem> items) {}
