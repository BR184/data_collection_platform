package com.data.collection.platform.service;

import java.time.LocalDateTime;

/**
 * 议题业务输入的来源无关快照（N0 §7.2）。
 *
 * <p>表单来源按 {@code (createdAt, recordId)} 从权威目录选出“最新整份响应、最早响应、最新有效
 * 回复”，再把所选记录投影成这份不可变快照；SLA 判定只消费快照，不区分表单或评论，也不逐字段
 * 向更早记录回退。缺字段保持缺项，不用空串或 0 补齐。
 *
 * @param hasResponse 是否存在有效响应记录
 * @param firstResponseAt 最早响应的创建时间（首次调研时间）；无响应时为 {@code null}
 * @param plannedResolutionAt 最新整份响应的计划解决时间（当日零点）；该份缺字段时为 {@code null}
 * @param plannedResolutionText 最新整份响应的计划解决时间原文；缺字段时为空串
 * @param plannedMergeVersionBranch 最新整份响应的计划合并版本分支；缺字段时为 {@code null}
 * @param responsePlanExempt 任一份有效响应提供合法计划解决时间（仅用于 D-21 响应延期豁免）
 * @param hasReply 是否存在有效回复记录
 * @param fixUser 最新有效回复的创建人姓名快照；无回复时为 {@code null}
 */
record IssueBusinessInput(
    boolean hasResponse,
    LocalDateTime firstResponseAt,
    LocalDateTime plannedResolutionAt,
    String plannedResolutionText,
    String plannedMergeVersionBranch,
    boolean responsePlanExempt,
    boolean hasReply,
    String fixUser) {

  static IssueBusinessInput empty() {
    return new IssueBusinessInput(false, null, null, "", null, false, false, null);
  }

  /** 表单路径是否给出了任何业务证据；无记录时仍是一份合法输入（表示尚未填写）。 */
  boolean hasBusinessEvidence() {
    return hasResponse || hasReply;
  }
}
