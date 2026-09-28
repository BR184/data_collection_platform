package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticBoardDefinition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.service.IssueScopeDimension;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class StatisticBoardSnapshotRequestFactory {
  private final StatisticBoardReadScopeResolver readScopeResolver;

  public StatisticBoardSnapshotRequestFactory(StatisticBoardReadScopeResolver readScopeResolver) {
    this.readScopeResolver = readScopeResolver;
  }

  /**
   * 构造统计快照请求，实际读取的来源与范围由本次请求的来源选择在一致性边界内解析。
   *
   * @param boardKey 统计板标识
   * @param ruleVersion 规则版本
   * @param scopeKey 稳定范围键
   * @param projectId GitLab 项目 ID
   * @param dimension 可选范围维度
   * @param scopeBusinessKey 可选范围业务键
   * @param filters 本次请求的真实筛选，含来源选择
   * @param definition 统计板定义
   * @param appliedFilterGroup 高级筛选展开后的条件
   */
  public StatisticBoardSnapshotService.SnapshotRequest issueRequest(
      String boardKey,
      String ruleVersion,
      String scopeKey,
      long projectId,
      IssueScopeDimension dimension,
      String scopeBusinessKey,
      Map<String, String> filters,
      StatisticBoardDefinition definition,
      StatisticFilterGroup appliedFilterGroup) {
    return new StatisticBoardSnapshotService.SnapshotRequest(
        boardKey,
        StringUtils.hasText(scopeKey) ? scopeKey : "default",
        ruleVersion,
        StatisticBoardSnapshotService.SourceReadPlan.of(
            () -> readScopeResolver.resolve(filters, projectId, dimension, scopeBusinessKey)),
        new LinkedHashMap<>(filters == null ? Map.of() : filters),
        definition,
        appliedFilterGroup);
  }
}
