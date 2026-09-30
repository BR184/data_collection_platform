import type { BiChartDownloadContext, BiPageKey } from './types';

/** 为现有 BI 阶段图表生成只含产品版本范围的下载身份。 */
export function productVersionDownloadContext(
  pageKey: BiPageKey,
  chartInstanceId: string,
  productVersionId: number,
  sourceVersion: string,
): BiChartDownloadContext {
  return {
    pageKey,
    chartInstanceId,
    sourceVersion,
    scope: { rangeType: 'PRODUCT_VERSION', productVersionId },
  };
}
