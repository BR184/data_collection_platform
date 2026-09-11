import { request, requestBlobResponse, EXPORT_REQUEST_TIMEOUT_MS, type BlobResponse } from './request';
import type {
  BiChartTemplateId,
  BiCodingPageData,
  BiDownloadAuthorization,
  BiPageKey,
  BiPageResponse,
  BiProductVersionCatalog,
  BiReviewPageData,
  BiSystemTestPageData,
  BiTestQualityPageData,
} from '../features/bi-dashboard/data/types';

export interface BiCodingQuery {
  granularity: 'day' | 'week';
  source: 'all' | 'cc' | 'dgm';
  repositoryId?: string;
}

/** 后端生成图表 Excel 数据表所需的授权身份与已提取表格内容。 */
export interface BiExcelExportParams {
  productVersionId: number;
  pageKey: BiPageKey;
  chartTemplateId: BiChartTemplateId;
  sourceVersion: string;
  title: string;
  productVersionName: string;
  /** 图表业务口径与达标标准说明，后端写入标题下的说明行；无内容时传空串。 */
  explanation: string;
  headers: string[];
  rows: Array<Array<string | number>>;
}

function pageQuery(productVersionId: number): URLSearchParams {
  return new URLSearchParams({ productVersionId: String(productVersionId) });
}

/** BI 六阶段页面使用的同源 HTTP 客户端。 */
export const biDashboardApi = {
  loadVersions(): Promise<BiProductVersionCatalog> {
    return request('/api/bi/versions', {
      platformProgress: { label: '正在加载产品版本', profile: 'filter', endpointKey: 'bi-versions' },
    });
  },

  loadRequirements(productVersionId: number): Promise<BiPageResponse<BiReviewPageData>> {
    return request(`/api/bi/requirements?${pageQuery(productVersionId)}`, pageProgress('requirements'));
  },

  loadDesign(productVersionId: number): Promise<BiPageResponse<BiReviewPageData>> {
    return request(`/api/bi/design?${pageQuery(productVersionId)}`, pageProgress('design'));
  },

  loadCoding(productVersionId: number, filters: BiCodingQuery): Promise<BiPageResponse<BiCodingPageData>> {
    const query = pageQuery(productVersionId);
    query.set('granularity', filters.granularity);
    query.set('source', filters.source);
    if (filters.repositoryId) {
      query.set('repositoryId', filters.repositoryId);
    }
    return request(`/api/bi/coding?${query}`, pageProgress('coding'));
  },

  loadUnitTest(productVersionId: number): Promise<BiPageResponse<BiTestQualityPageData>> {
    return request(`/api/bi/unit-test?${pageQuery(productVersionId)}`, pageProgress('unit-test'));
  },

  loadIntegrationTest(productVersionId: number): Promise<BiPageResponse<BiTestQualityPageData>> {
    return request(`/api/bi/integration-test?${pageQuery(productVersionId)}`, pageProgress('integration-test'));
  },

  loadSystemTest(productVersionId: number): Promise<BiPageResponse<BiSystemTestPageData>> {
    return request(`/api/bi/system-test?${pageQuery(productVersionId)}`, pageProgress('system-test'));
  },

  authorizeDownload(
    productVersionId: number,
    pageKey: BiPageKey,
    chartTemplateId: BiChartTemplateId,
    sourceVersion: string,
  ): Promise<BiDownloadAuthorization> {
    return request('/api/bi/download/authorize', {
      method: 'POST',
      body: JSON.stringify({ productVersionId, pageKey, chartTemplateId, sourceVersion }),
      platformProgress: { label: '正在准备图表', profile: 'export', endpointKey: 'bi-png-authorize' },
    });
  },

  /** 提交授权身份与已提取的表格内容，由后端序列化为标准 .xlsx 并回传二进制下载。 */
  exportExcel(params: BiExcelExportParams): Promise<BlobResponse> {
    return requestBlobResponse('/api/bi/download/excel', {
      method: 'POST',
      body: JSON.stringify(params),
      timeoutMs: EXPORT_REQUEST_TIMEOUT_MS,
      exportProgress: { label: '正在导出 Excel 数据表', profile: 'export', endpointKey: 'bi-excel-export' },
    });
  },
};

function pageProgress(pageKey: BiPageKey) {
  return {
    platformProgress: {
      label: '正在计算 BI 看板',
      profile: 'statistic' as const,
      endpointKey: `bi-${pageKey}`,
      learnDuration: true,
    },
  };
}
