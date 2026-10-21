package com.data.collection.platform.entity.read;

import java.util.UUID;

/** 一次读取显式拥有的版本组合；传输租约与会话身份不会存进共享结果缓存。 */
public sealed interface ReadSelection permits FactReadSelection, DatasetReadSelection {
  /** 本组合的数据指纹。 */
  String sourceVersion();
  /** 缓存/定义主发布身份。 */
  UUID primaryPublicationId();
  /** 是否为内部候选预热；公开数据集组合恒为 false。 */
  boolean candidate();
  /** 一次读取/预热固定的业务日，不在半次计算或午夜后重新取系统日期。 */
  java.time.LocalDate businessDate();
}
