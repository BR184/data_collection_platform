package com.data.collection.platform.service;

/** 按来源复用的延期标签写回进度阶段。 */
public enum CustomerIssueDelayClosureStage {
  /** 已收到请求，等待取得配置并提交本轮前置镜像。 */
  REQUESTED,
  /** 等待绑定的前置镜像完整成功。 */
  WAIT_MIRROR,
  /** 等待来源事实收敛、资格完整且没有在途改写相关输入。 */
  WAIT_FACT,
  /** 已取得本轮授权，按 issue_id 有界分页登记写回候选。 */
  ENQUEUE,
  /** 本轮已结算；保留授权供已入队作业发送，等待下一次请求。 */
  IDLE
}
