package com.data.collection.platform.entity.read;

/**
 * 读发布身份的状态机。
 *
 * <p>公开请求只能读取已发布且未被回收的版本；候选状态对公开读不可见。生产者只能沿
 * {@code BUILDING → PROJECTING → READY_TO_PUBLISH → PUBLISHED} 推进，失败走 {@code ABORTED}，
 * 历史回收走 {@code RETIRING → RETIRED}。
 */
public enum ReadPublicationState {
  BUILDING,
  PROJECTING,
  READY_TO_PUBLISH,
  PUBLISHED,
  ABORTED,
  RETIRING,
  RETIRED;

  /** 活动候选：占用该域唯一的候选名额，可被生产者在事务内继续推进。 */
  public boolean isActiveCandidate() {
    return this == BUILDING || this == PROJECTING || this == READY_TO_PUBLISH;
  }

  /** 公开可读：已发布的当前版本，以及仍被在用页面引用、等待回收的历史版本。 */
  public boolean isPubliclyReadable() {
    return this == PUBLISHED || this == RETIRING;
  }
}
