package com.data.collection.platform.domain.form;

/** 冻结词典条目；key 是稳定业务身份，label 与 group 只用于展示和分类。 */
public record FormDictionaryItem(String key, String label, String group) {}
