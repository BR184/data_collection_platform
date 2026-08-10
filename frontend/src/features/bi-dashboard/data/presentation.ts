import type { BiDataStatus, BiPageKey, BiPageResponse, BiPageSection } from './types';

export interface SectionPresentation {
  status: BiDataStatus;
  message: string;
}

/** 只允许当前路由消费属于同一阶段的响应，避免阶段切换期间误读上一页 DTO。 */
export function responseMatchesPage(
  response: BiPageResponse<unknown> | null,
  pageKey: BiPageKey,
): response is BiPageResponse<unknown> {
  return response?.pageKey === pageKey;
}

/** 提取页面级失败信息；业务区块状态不得覆盖或掩盖页面失败。 */
export function pageErrorMessage(response: BiPageResponse<unknown> | null): string | null {
  if (response?.status !== 'ERROR') return null;
  return response.sections.find((item: BiPageSection) => item.key === 'page')?.message
    || 'BI 页面数据加载失败，请稍后重试';
}

/** 从页面信封中提取区块状态；页面失败优先，普通响应缺失契约按不完整处理。 */
export function sectionPresentation(response: BiPageResponse<unknown>, key: string): SectionPresentation {
  const pageError = pageErrorMessage(response);
  if (pageError) return { status: 'ERROR', message: pageError };
  const section = response.sections.find((item: BiPageSection) => item.key === key);
  return section
    ? { status: section.status, message: section.message }
    : { status: 'INCOMPLETE', message: '页面响应缺少该区块状态契约' };
}

export function formatNumber(value: number | null | undefined, digits = 0): string {
  return value == null ? '--' : value.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

export function formatPercent(value: number | null | undefined): string {
  return value == null ? '--' : `${value.toFixed(2)}%`;
}

export function metricStatus(value: boolean | null | undefined): 'success' | 'danger' | 'neutral' {
  return value == null ? 'neutral' : value ? 'success' : 'danger';
}

/** 按稳定质量目标键明确区分缺陷严重程度与优先级。 */
export function systemTestTargetLabel(key: string, fallbackLabel: string): string {
  if (key === 'level-one') return '一级缺陷修复率（严重程度）';
  if (key === 'p1' || key === 'p2') return `${key.toUpperCase()}修复率（优先级）`;
  return `${fallbackLabel}修复率`;
}
