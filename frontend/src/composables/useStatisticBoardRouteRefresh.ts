export interface StatisticBoardRouteRefreshDependencies {
  syncTablePaginationFromRoute: () => void;
  loadBoard: (showError?: boolean) => Promise<void>;
  loadRealtimeStatus: () => Promise<void>;
  syncDetailFromRoute: () => Promise<void>;
}

/**
 * 顺序刷新路由对应的看板、同步状态与明细。
 *
 * <p>每个异步步骤后都会重新检查路由任务是否仍有效；失效任务不会继续触发后续状态同步。省略检查器时，
 * 调用方声明任务在整个刷新期间有效。
 *
 * @param deps 当前路由刷新需要调用的同步操作
 * @param isCurrent 判断当前路由任务是否仍可写入页面状态
 */
export async function refreshStatisticBoardRouteState(
  deps: StatisticBoardRouteRefreshDependencies,
  isCurrent: () => boolean = () => true,
) {
  if (!isCurrent()) {
    return;
  }
  deps.syncTablePaginationFromRoute();
  await deps.loadBoard(false);
  if (!isCurrent()) {
    return;
  }
  await deps.loadRealtimeStatus();
  if (!isCurrent()) {
    return;
  }
  await deps.syncDetailFromRoute();
}
