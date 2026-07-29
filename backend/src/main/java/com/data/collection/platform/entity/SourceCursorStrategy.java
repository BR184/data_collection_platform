package com.data.collection.platform.entity;

/** 源表增量分页使用的索引友好游标策略。 */
public enum SourceCursorStrategy {
  /** 更新时间列存在可覆盖全表的 B-tree 前导索引，按时间与主键分页。 */
  TIMESTAMP_KEYSET,

  /** 更新时间列没有合适索引，在固定时间窗口内按真实主键分页。 */
  PRIMARY_KEY_KEYSET,

  /** 源表没有可用于增量发现的时间列，只能参与全量扫描。 */
  NONE
}
