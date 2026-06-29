package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.statistics.StatisticBoardDefinition;
import com.data.collection.platform.entity.statistics.StatisticFilterGroup;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class StatisticBoardSnapshotRequestFactory {
  private final StatisticBoardSnapshotService snapshotService;

  public StatisticBoardSnapshotRequestFactory(StatisticBoardSnapshotService snapshotService) {
    this.snapshotService = snapshotService;
  }

  public StatisticBoardSnapshotService.SnapshotRequest issueRequest(
      String boardKey,
      String ruleVersion,
      String scopeKey,
      Map<String, String> filters,
      StatisticBoardDefinition definition,
      StatisticFilterGroup appliedFilterGroup) {
    return new StatisticBoardSnapshotService.SnapshotRequest(
        boardKey,
        StringUtils.hasText(scopeKey) ? scopeKey : "default",
        ruleVersion,
        snapshotService.issueFactSourceVersion(),
        new LinkedHashMap<>(filters == null ? Map.of() : filters),
        definition,
        appliedFilterGroup);
  }
}
