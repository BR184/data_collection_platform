import { codeReviewApi } from './api-client/code-review-api';
import { analyticsDashboardApi } from './api-client/analytics-dashboard-api';
import { collectFormsApi } from './api-client/collect-forms-api';
import { databaseBrowserApi } from './api-client/database-browser-api';
import { issueRecordsApi } from './api-client/issue-records-api';
import { labelGroupsApi } from './api-client/label-groups-api';
import { legacyDatabaseApi } from './api-client/legacy-database-api';
import { mirrorApi } from './api-client/mirror-api';
import { qualityBoardApi } from './api-client/quality-board-api';
import { reviewDataApi } from './api-client/review-data-api';
import { statisticBoardsApi } from './api-client/statistic-boards-api';
import { testingPhasesApi } from './api-client/testing-phases-api';

export * from './types/api';

export const api = {
  ...analyticsDashboardApi,
  ...mirrorApi,
  ...statisticBoardsApi,
  ...databaseBrowserApi,
  ...testingPhasesApi,
  ...collectFormsApi,
  ...codeReviewApi,
  ...reviewDataApi,
  ...issueRecordsApi,
  ...labelGroupsApi,
  ...legacyDatabaseApi,
  ...qualityBoardApi,
};
