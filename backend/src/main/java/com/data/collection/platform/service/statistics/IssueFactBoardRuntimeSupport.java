package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.RealtimeWorkspaceRefreshResult;
import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import com.data.collection.platform.service.IssueFactRecordRepository;
import com.data.collection.platform.service.RealtimeWorkspaceService;
import com.data.collection.platform.service.RealtimeIncrementalRefreshService;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class IssueFactBoardRuntimeSupport {
  private final IssueFactRecordRepository issueFactRecordRepository;
  private final RealtimeWorkspaceService realtimeWorkspaceService;
  private final RealtimeIncrementalRefreshService realtimeIncrementalRefreshService;

  public IssueFactBoardRuntimeSupport(
      IssueFactRecordRepository issueFactRecordRepository,
      RealtimeWorkspaceService realtimeWorkspaceService,
      RealtimeIncrementalRefreshService realtimeIncrementalRefreshService) {
    this.issueFactRecordRepository = issueFactRecordRepository;
    this.realtimeWorkspaceService = realtimeWorkspaceService;
    this.realtimeIncrementalRefreshService = realtimeIncrementalRefreshService;
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

  public RealtimeWorkspaceStatusResponse getRealtimeStatus(String boardKey, Map<String, String> filters) {
    return realtimeWorkspaceService.getStatus(boardKey, filters);
  }

  public RealtimeWorkspaceStatusResponse requestRealtimeRefresh(String boardKey) {
    return realtimeWorkspaceService.requestRefreshWithResult(
        boardKey,
        () -> {
          RealtimeWorkspaceRefreshResult result =
              realtimeIncrementalRefreshService.requestIncrementalRefresh(
                  com.data.collection.platform.entity.WorkspaceRefreshRequest.global(boardKey));
          return result;
        });
  }
}
