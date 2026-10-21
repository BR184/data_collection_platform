package com.data.collection.platform.service;

import java.util.List;

/**
 * 一段已绑定参数化的 SQL 谓词与其占位符取值。
 *
 * <p>{@code predicate} 是可以直接放进 WHERE 的布尔表达式（不含前导 and/or），{@code args} 按其中
 * {@code ?} 的出现顺序排列；两者必须成对使用，调用方不得只取其一。
 */
public record SqlPredicate(String predicate, List<Object> args) {}
