package com.data.collection.platform.service.sync;

import com.data.collection.platform.entity.GitlabSyncConfig;
import com.data.collection.platform.entity.SyncStatus;
import com.data.collection.platform.entity.SyncType;
import com.data.collection.platform.entity.sync.SyncRun;
import com.data.collection.platform.entity.sync.SyncRunStatus;
import com.data.collection.platform.entity.sync.SyncRunType;
import com.data.collection.platform.service.GitlabSourceInstanceSupport;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SyncRunPolicyService {
  private static final Set<SyncRunStatus> ACTIVE_STATUSES =
      EnumSet.of(
          SyncRunStatus.SUBMITTED,
          SyncRunStatus.QUEUED,
          SyncRunStatus.RUNNING,
          SyncRunStatus.RETRYING,
          SyncRunStatus.CANCELLING);

  private static final String MIRROR_SCOPE_SUFFIX = ":mirror";
  private static final String FACT_SCOPE_SUFFIX = ":fact";
  private static final String COMPENSATION_SCOPE_SUFFIX = ":compensation";

  public SyncRunType toRunType(SyncType type) {
    if (type == null) {
      throw new IllegalArgumentException("Sync type must not be null");
    }
    return switch (type) {
      case FULL -> SyncRunType.FULL_SYNC;
      case INCREMENTAL -> SyncRunType.INCREMENTAL_SYNC;
      case COMPENSATION -> SyncRunType.COMPENSATION_SCAN;
      case SYSTEM_HOOK -> SyncRunType.SYSTEM_HOOK;
      case PURGE -> throw new IllegalArgumentException("PURGE is not a sync run type");
    };
  }

  public SyncType toApiType(SyncRunType type) {
    if (type == null) {
      throw new IllegalArgumentException("Sync run type must not be null");
    }
    return switch (type) {
      case FULL_SYNC -> SyncType.FULL;
      case INCREMENTAL_SYNC -> SyncType.INCREMENTAL;
      case TABLE_REFRESH -> SyncType.INCREMENTAL;
      case SYSTEM_HOOK -> SyncType.SYSTEM_HOOK;
      case COMPENSATION_SCAN -> SyncType.COMPENSATION;
      case FACT_REFRESH -> SyncType.COMPENSATION;
    };
  }

  public int priorityOf(SyncRunType type) {
    if (type == null) {
      return 0;
    }
    return switch (type) {
      case FULL_SYNC -> 100;
      case INCREMENTAL_SYNC -> 70;
      case SYSTEM_HOOK -> 60;
      case TABLE_REFRESH -> 40;
      case COMPENSATION_SCAN -> 20;
      case FACT_REFRESH -> 10;
    };
  }

  public String exclusiveScopeOf(GitlabSyncConfig config, SyncRunType type) {
    String sourceInstance = GitlabSourceInstanceSupport.sourceInstanceOf(config);
    String configId = config == null || config.getId() == null ? "unknown" : String.valueOf(config.getId());
    return switch (type) {
      case FULL_SYNC, INCREMENTAL_SYNC, TABLE_REFRESH, SYSTEM_HOOK ->
          "source:" + configId + ":" + sourceInstance + MIRROR_SCOPE_SUFFIX;
      case FACT_REFRESH -> "source:" + configId + ":" + sourceInstance + FACT_SCOPE_SUFFIX;
      case COMPENSATION_SCAN -> "source:" + configId + ":" + sourceInstance + COMPENSATION_SCOPE_SUFFIX;
    };
  }

  public boolean isActiveStatus(SyncRunStatus status) {
    return status != null && ACTIVE_STATUSES.contains(status);
  }

  public boolean shouldReuseFullRun(SyncRun requestRunType, SyncRun candidate) {
    return candidate != null
        && candidate.getRunType() == SyncRunType.FULL_SYNC
        && candidate.getStatus() != null
        && isActiveStatus(candidate.getStatus())
        && requestRunType != null
        && requestRunType.getRunType() == SyncRunType.FULL_SYNC;
  }

  public boolean shouldMergeTableRefresh(SyncRun candidate) {
    return candidate != null
        && candidate.getRunType() == SyncRunType.FULL_SYNC
        && candidate.getStatus() != null
        && isActiveStatus(candidate.getStatus());
  }

  public SyncStatus toApiStatus(SyncRun run) {
    if (run == null || run.getStatus() == null) {
      return SyncStatus.IDLE;
    }
    return switch (run.getStatus()) {
      case SUBMITTED, QUEUED -> SyncStatus.QUEUED;
      case RUNNING -> SyncStatus.RUNNING;
      case RETRYING -> SyncStatus.RETRYING;
      case CANCELLING -> SyncStatus.CANCELLING;
      case SUCCESS -> SyncStatus.SUCCESS;
      case PARTIAL_SUCCESS -> SyncStatus.PARTIAL_SUCCESS;
      case FAILED -> SyncStatus.FAILED;
      case CANCELLED -> SyncStatus.CANCELLED;
      case TIMEOUT -> SyncStatus.TIMEOUT;
      case MERGED -> SyncStatus.PENDING;
    };
  }
}
