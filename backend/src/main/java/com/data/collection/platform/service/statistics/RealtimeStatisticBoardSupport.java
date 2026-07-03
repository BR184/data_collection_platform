package com.data.collection.platform.service.statistics;

import com.data.collection.platform.entity.RealtimeWorkspaceStatusResponse;
import java.util.Map;

public interface RealtimeStatisticBoardSupport {
  RealtimeWorkspaceStatusResponse getRealtimeStatus();

  default RealtimeWorkspaceStatusResponse getRealtimeStatus(Map<String, String> filters) {
    return getRealtimeStatus();
  }

  RealtimeWorkspaceStatusResponse requestRealtimeRefresh();
}
