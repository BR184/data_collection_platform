package com.data.collection.platform.service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 议题业务输入的来源无关快照（N0 §7.2）。
 *
 * <p>响应与回复分别按持久权威决定是否采用表单：采用时按 {@code (createdAt, recordId)} 选“最新整份
 * 响应、最早响应、最新有效回复”，整份取用；未采用时该侧保持评论列，不把表单与评论拼成一份输入。
 * 回复侧同时保留类型化详情与稳定原因 ID 集合，供原因/合规投影与后续导出消费；不解析 Markdown、
 * 不逐字段向更早记录回退。
 */
record IssueBusinessInput(
    boolean responseAdopted,
    ResponseSnapshot response,
    boolean replyAdopted,
    ReplySnapshot reply) {

  IssueBusinessInput {
    response = response == null ? ResponseSnapshot.absent() : response;
    reply = reply == null ? ReplySnapshot.absent() : reply;
  }

  static IssueBusinessInput empty(boolean responseAdopted, boolean replyAdopted) {
    return new IssueBusinessInput(
        responseAdopted,
        ResponseSnapshot.absent(),
        replyAdopted,
        ReplySnapshot.absent());
  }

  static IssueBusinessInput commentOnly() {
    return empty(false, false);
  }

  boolean anyAdopted() {
    return responseAdopted || replyAdopted;
  }

  /** 采用表单的侧是否给出了任何业务证据；空目录仍是一份合法输入（表示尚未填写）。 */
  boolean hasBusinessEvidence() {
    return response.present() || reply.present();
  }

  /**
   * 最新整份响应快照。
   *
   * @param present 是否存在有效响应
   * @param latestRecordId 最新整份响应的记录 ID；无响应时为 {@code null}
   * @param firstResponseAt 最早响应的创建时间（首次调研时间）
   * @param plannedResolutionAt 最新整份响应的计划解决时间（当日零点）
   * @param plannedResolutionText 计划解决时间原文；缺字段时为空串
   * @param plannedMergeVersionBranch 计划合并版本分支
   * @param planExempt 任一份有效响应提供合法计划解决时间（D-21）
   */
  record ResponseSnapshot(
      boolean present,
      Long latestRecordId,
      LocalDateTime firstResponseAt,
      LocalDateTime plannedResolutionAt,
      String plannedResolutionText,
      String plannedMergeVersionBranch,
      boolean planExempt) {

    static ResponseSnapshot absent() {
      return new ResponseSnapshot(false, null, null, null, "", null, false);
    }
  }

  /**
   * 最新有效回复的类型化快照。
   *
   * <p>原因集合保存冻结词典的稳定 key 与规范标签；合规按 key 去重后单独判断，多选采集与唯一性
   * 分层。其余详情原样保留，供后续投影/导出按所选回复取值，不重新解析评论。
   */
  record ReplySnapshot(
      boolean present,
      Long latestRecordId,
      LocalDateTime createdAt,
      String createdBy,
      String fixUser,
      String fixStatus,
      List<String> causeKeys,
      List<String> causeLabels,
      String specificReason,
      String modificationPlan,
      Boolean causedByOtherDefect,
      List<String> affectedKinds,
      String knownAffectedFunctions,
      String newAffectedFunctions,
      String impactTestStatus,
      Boolean hasResidualImpact,
      String residualImpactDetail,
      String relationTableUpdated,
      String supplementNote) {

    ReplySnapshot {
      causeKeys = causeKeys == null ? List.of() : List.copyOf(causeKeys);
      causeLabels = causeLabels == null ? List.of() : List.copyOf(causeLabels);
      affectedKinds = affectedKinds == null ? List.of() : List.copyOf(affectedKinds);
    }

    static ReplySnapshot absent() {
      return new ReplySnapshot(
          false, null, null, null, null, null, List.of(), List.of(), null, null, null,
          List.of(), null, null, null, null, null, null, null);
    }

    /** 去重后的稳定原因数量；0 或大于 1 在模板合规层单独判为不唯一。 */
    int distinctCauseCount() {
      return (int) causeKeys.stream().filter(key -> key != null && !key.isBlank()).distinct().count();
    }

    /** 去重后的原因规范标签，保持首次出现顺序。 */
    String canonicalReasonText() {
      java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<>();
      for (String label : causeLabels) {
        if (label != null && !label.isBlank()) {
          labels.add(label);
        }
      }
      return String.join(", ", labels);
    }
  }
}
