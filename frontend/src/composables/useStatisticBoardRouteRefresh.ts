export interface StatisticBoardRouteRefreshDependencies {
  setLoading: (loading: boolean) => void;
  syncTablePaginationFromRoute: () => void;
  loadBoard: (showError?: boolean) => Promise<void>;
  loadRealtimeStatus: () => Promise<void>;
  syncDetailFromRoute: () => Promise<void>;
}

export async function refreshStatisticBoardRouteState(deps: StatisticBoardRouteRefreshDependencies) {
  deps.setLoading(true);
  try {
    deps.syncTablePaginationFromRoute();
    await deps.loadBoard(false);
    await deps.loadRealtimeStatus();
    await deps.syncDetailFromRoute();
  } finally {
    deps.setLoading(false);
  }
}
