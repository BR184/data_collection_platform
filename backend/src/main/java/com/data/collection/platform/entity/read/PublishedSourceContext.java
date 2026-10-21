package com.data.collection.platform.entity.read;

import java.time.Instant;
import java.util.UUID;

/** 已核验的公开来源身份；事实及完整数据集共用发布/租约生命周期，物理读取各自保持强类型。 */
public sealed interface PublishedSourceContext permits PublishedReadContext, PublishedDatasetReadContext {
  /** 来源实例。 */
  String sourceInstance();
  /** 只读完整发布身份。 */
  UUID publicationId();
  /** 数据代际。 */
  UUID epochId();
  /** 域内发布序号。 */
  long sequence();
  /** 完整数据的截止时刻。 */
  Instant dataAsOf();
}
