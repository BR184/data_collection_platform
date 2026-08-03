package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticBoardDefinition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import com.data.collection.platform.entity.FactType;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import com.data.collection.platform.service.IssueProjectionScopeResolver;
import com.data.collection.platform.service.IssueScopeDimension;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class StatisticBoardSnapshotRequestFactory {
  private final StatisticBoardSnapshotService snapshotService;
  private final IssueProjectionScopeResolver scopeResolver;

  public StatisticBoardSnapshotRequestFactory(
      StatisticBoardSnapshotService snapshotService,
      IssueProjectionScopeResolver scopeResolver) {
    this.snapshotService = snapshotService;
    this.scopeResolver = scopeResolver;
  }

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
        snapshotService.issueFactSourceVersion(
            scopeResolver.resolve(
                GitlabSourceInstanceSupport.DEFAULT_SOURCE_INSTANCE,
                FactType.ISSUE,
                projectId,
                dimension,
                scopeBusinessKey)),
        new LinkedHashMap<>(filters == null ? Map.of() : filters),
        definition,
        appliedFilterGroup);
  }
}
