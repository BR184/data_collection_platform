package com.data.collection.platform.service.statistics.engine;

/** 统计筛选字段的值形态；决定引擎走多值文本语义还是日期时间语义。 */
public enum StatisticFieldType {
  /** 多值文本字段：所有操作符基于字符串列表求值，单值字段视为单元素列表。 */
  MULTI_VALUE,
  /** 日期时间字段：普通操作符基于 LocalDateTime 求值。 */
  DATETIME
}
