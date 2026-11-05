package com.data.collection.platform.service.dashboard;

import com.data.collection.platform.service.IssueScopeDimension;
import java.util.List;

/**
 * 看板请求冻结后的版本范围。
 *
 * <p>普通版本是单成员范围；分组看板入口是多个原目录成员组合而成的只读查询范围。
 * 两种形态共用同一模型，调用方不能再按业务键自行猜测组合关系。
 */
public record DashboardVersionScope(
    String businessKey,
    String displayName,
    long projectId,
    IssueScopeDimension dimension,
    long anchorGroupId,
    int sortOrder,
    List<String> memberBusinessKeys,
    List<PhaseMember> phases,
    List<String> reviewProjectNames,
    List<String> integrationTestPhases,
    boolean combined,
    String fingerprint) {
  public DashboardVersionScope {
    memberBusinessKeys =
        memberBusinessKeys == null ? List.of() : List.copyOf(memberBusinessKeys);
    phases = phases == null ? List.of() : List.copyOf(phases);
    reviewProjectNames =
        reviewProjectNames == null ? List.of() : List.copyOf(reviewProjectNames);
    integrationTestPhases =
        integrationTestPhases == null ? List.of() : List.copyOf(integrationTestPhases);
  }

  /** 原测试阶段目录中的稳定轮次成员。 */
  public record PhaseMember(long id, String sourceValue, String displayName, int sortOrder) {}
}
