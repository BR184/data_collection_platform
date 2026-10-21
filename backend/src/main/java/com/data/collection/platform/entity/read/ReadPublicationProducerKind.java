package com.data.collection.platform.entity.read;

/**
 * 读发布域的生产者种类。
 *
 * <p>域键由生产者种类、来源实例、事实类型或表键共同确定。同一物化来源只允许一个生产者种类，
 * 避免把不同所有权的读模型混进同一一致性域。
 */
public enum ReadPublicationProducerKind {
  /** GitLab 事实族，按 source_instance + FactType 划分域。 */
  GITLAB_FACT,
  /** 兼容模式评审数据（MySQL/Mongo 归一后的完整数据集）。 */
  COMPAT_REVIEW,
  /** 兼容模式代码走查数据。 */
  COMPAT_WALKTHROUGH,
  /** 正式人工评审/走查业务记录。 */
  FORMAL_RECORD,
  /** 数据库浏览器的镜像表，按表键划分域。 */
  MIRROR_TABLE,
  /** CAT 已完整发布的本地不可变快照，复用原快照内容，不再复制一份测试数据。 */
  CAT_MIRROR
}
