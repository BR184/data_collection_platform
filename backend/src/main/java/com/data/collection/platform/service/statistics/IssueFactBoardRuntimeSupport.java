package com.data.collection.platform.service.statistics;

import com.data.collection.platform.common.exception.BizException;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.service.FactBuildService;
import com.data.collection.platform.service.GitlabMirrorSyncService;
import com.data.collection.platform.service.IssueFactRecord;
import com.data.collection.platform.service.IssueFactRecordRepository;
import com.data.collection.platform.service.RealtimeWorkspaceService;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
// issue_fact 看板运行时集中处理事实读取、空数据自动重建和实时刷新。
// 统计板服务只关心聚合逻辑，镜像刷新和事实重建由这里统一编排。
public class IssueFactBoardRuntimeSupport {
  private final IssueFactRecordRepository issueFactRecordRepository;
  private final GitlabMirrorSyncService gitlabMirrorSyncService;
  private final RealtimeWorkspaceService realtimeWorkspaceService;
  private final FactBuildService factBuildService;

  public IssueFactBoardRuntimeSupport(
      IssueFactRecordRepository issueFactRecordRepository,
      GitlabMirrorSyncService gitlabMirrorSyncService,
      RealtimeWorkspaceService realtimeWorkspaceService,
      FactBuildService factBuildService) {
    this.issueFactRecordRepository = issueFactRecordRepository;
    this.gitlabMirrorSyncService = gitlabMirrorSyncService;
    this.realtimeWorkspaceService = realtimeWorkspaceService;
    this.factBuildService = factBuildService;
  }

  public List<StatisticIssueFactSource> loadFacts(
      Map<String, String> filters,
      Predicate<StatisticIssueFactSource> predicate) {
    List<StatisticIssueFactSource> sources =
        issueFactRecordRepository.findByFilters(filters).stream()
            .map(StatisticIssueFactSource::new)
            .filter(predicate == null ? source -> true : predicate)
            .toList();
    if (!sources.isEmpty()) {
      return sources;
    }
    // 空事实通常意味着新库尚未构建或镜像刚刷新，先触发一次全量事实重建再查询。
    try {
      factBuildService.rebuildIssueFacts(true);
    } catch (BizException error) {
      log.warn("Skipped issue fact rebuild because GitLab source is not ready: {}", error.getMessage());
      return List.of();
    }
    return issueFactRecordRepository.findByFilters(filters).stream()
        .map(StatisticIssueFactSource::new)
        .filter(predicate == null ? source -> true : predicate)
        .toList();
  }

  public RealtimeWorkspaceStatusResponse getRealtimeStatus(String boardKey) {
    return realtimeWorkspaceService.getStatus(boardKey);
  }

  public RealtimeWorkspaceStatusResponse requestRealtimeRefresh(
      String boardKey, List<String> realtimeRefreshTables) {
    return realtimeWorkspaceService.requestRefresh(
        boardKey,
        () -> {
          gitlabMirrorSyncService.refreshTablesOnDemand(realtimeRefreshTables, boardKey);
          factBuildService.rebuildIssueFacts(false);
        });
  }
}
