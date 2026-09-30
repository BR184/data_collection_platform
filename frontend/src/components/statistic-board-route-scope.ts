import type { DataScopeProvider } from '../types/data-scope';

export interface StatisticBoardRouteScopeState {
  /** 是否可以按当前范围加载看板数据。 */
  ready: boolean;
  /** 已加载但没有任何启用项：应显示目录状态，而不是按全部范围取数。 */
  catalogMissing: boolean;
}

/**
 * 解析统计板范围就绪状态。
 *
 * <p>目录已加载却没有启用项时既不能声明就绪（否则会按“全部里程碑/全部阶段”放大范围），
 * 也不能继续等待，需要向用户显示目录状态。
 */
export function resolveStatisticBoardRouteScopeState(
  provider: DataScopeProvider | null | undefined,
  options: unknown[] | null | undefined,
  loaded: boolean,
  routeValue: unknown,
): StatisticBoardRouteScopeState {
  if (!provider || provider.defaultStrategy !== 'first-available') {
    return { ready: true, catalogMissing: false };
  }
  const optionCount = options?.length ?? 0;
  if (optionCount === 0) {
    return loaded ? { ready: false, catalogMissing: true } : { ready: false, catalogMissing: false };
  }
  return { ready: String(routeValue ?? '').trim() !== '', catalogMissing: false };
}
