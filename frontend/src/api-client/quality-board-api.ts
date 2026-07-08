import type { QualityBoardProjectOptionsResponse, QualityBoardRdOverviewResponse } from '../types/api';
import { request } from './request';

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
};
