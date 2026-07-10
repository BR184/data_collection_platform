import type {
  QualityBoardOtherOverviewResponse,
  QualityBoardProjectOptionsResponse,
  QualityBoardRdDashboardResponse,
  QualityBoardRdOverviewResponse,
} from '../types/api';
import { EXPORT_REQUEST_TIMEOUT_MS, request, requestBlob } from './request';

export const qualityBoardApi = {
  getQualityBoardRdProjectOptions() {
    return request<QualityBoardProjectOptionsResponse>('/api/quality-board/rd/project-options');
  },
  getQualityBoardRdOverview(projectName: string) {
    const query = new URLSearchParams();
    if (projectName) {
      query.set('projectName', projectName);
    }
    const queryString = query.toString();
    return request<QualityBoardRdOverviewResponse>(
      `/api/quality-board/rd/overview${queryString ? `?${queryString}` : ''}`,
      {
        platformProgress: {
          label: '正在加载质量看板',
          profile: 'statistic',
          endpointKey: 'quality-board-rd-overview',
          learnDuration: true,
        },
      },
    );
  },
  getQualityBoardRdDashboard(projectName: string, codeReviewSource = 'cc') {
    const query = new URLSearchParams();
    if (projectName) {
      query.set('projectName', projectName);
    }
    if (codeReviewSource) {
      query.set('codeReviewSource', codeReviewSource);
    }
    const queryString = query.toString();
    return request<QualityBoardRdDashboardResponse>(
      `/api/quality-board/rd/dashboard${queryString ? `?${queryString}` : ''}`,
      {
        platformProgress: {
          label: '正在加载研发质量看板',
          profile: 'statistic',
          endpointKey: 'quality-board-rd-dashboard',
          learnDuration: true,
        },
      },
    );
  },
  getQualityBoardOtherOverview(projectName: string) {
    const query = new URLSearchParams();
    if (projectName) {
      query.set('projectName', projectName);
    }
    const queryString = query.toString();
    return request<QualityBoardOtherOverviewResponse>(
      `/api/quality-board/other/overview${queryString ? `?${queryString}` : ''}`,
      {
        platformProgress: {
          label: '正在加载其他看板',
          profile: 'statistic',
          endpointKey: 'quality-board-other-overview',
          learnDuration: true,
        },
      },
    );
  },
  exportQualityBoardRdChart(projectName: string, codeReviewSource: string, chartKey: string) {
    const query = new URLSearchParams();
    if (projectName) {
      query.set('projectName', projectName);
    }
    if (codeReviewSource) {
      query.set('codeReviewSource', codeReviewSource);
    }
    return requestBlob(`/api/quality-board/rd/charts/${chartKey}/export?${query.toString()}`, {
      errorPrefix: 'Excel 导出失败',
      timeoutMs: EXPORT_REQUEST_TIMEOUT_MS,
      exportProgress: {
        label: '正在导出质量看板图表',
        profile: 'export',
        endpointKey: 'quality-board-rd-chart-export',
        learnDuration: true,
      },
    });
  },
  exportQualityBoardRdCodeReviewRecords(projectName: string, codeReviewSource: string) {
    const query = new URLSearchParams();
    if (projectName) {
      query.set('projectName', projectName);
    }
    if (codeReviewSource) {
      query.set('codeReviewSource', codeReviewSource);
    }
    return requestBlob(`/api/quality-board/rd/code-review-records/export?${query.toString()}`, {
      errorPrefix: 'Excel 导出失败',
      timeoutMs: EXPORT_REQUEST_TIMEOUT_MS,
      exportProgress: {
        label: '正在导出代码走查数据',
        profile: 'heavyExport',
        endpointKey: 'quality-board-rd-code-review-export',
        learnDuration: true,
      },
    });
  },
};
