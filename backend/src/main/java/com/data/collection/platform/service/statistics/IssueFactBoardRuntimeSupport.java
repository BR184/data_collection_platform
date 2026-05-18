package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.FactBuildResponse;
import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
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
    log.info("Issue fact board returned empty result without triggering synchronous rebuild");
    return List.of();
  }

  public RealtimeWorkspaceStatusResponse getRealtimeStatus(String boardKey) {
    return realtimeWorkspaceService.getStatus(boardKey);
  }

  public RealtimeWorkspaceStatusResponse requestRealtimeRefresh(
      String boardKey, List<String> realtimeRefreshTables) {
    return realtimeWorkspaceService.requestRefreshWithResult(
        boardKey,
        () -> {
          GitlabMirrorSyncService.OnDemandRefreshResult mirrorResult =
              gitlabMirrorSyncService.refreshTablesOnDemandDetailed(realtimeRefreshTables, boardKey);
          FactBuildResponse factResult = factBuildService.rebuildIssueFacts(false);
          return new RealtimeWorkspaceRefreshResult(
              mirrorResult.jobId(),
              mirrorResult.sourceTables(),
              mirrorResult.plannedTasks(),
              mirrorResult.unsupportedTables(),
              true,
              mirrorResult.status().name(),
              "SUCCESS",
              factResult.message());
        });
  }
}
