import { codeReviewApi } from './api-client/code-review-api';
import { analyticsDashboardApi } from './api-client/analytics-dashboard-api';
import { catMirrorApi } from './api-client/cat-mirror-api';
import { collectFormsApi } from './api-client/collect-forms-api';
import { databaseBrowserApi } from './api-client/database-browser-api';
import { factBuildApi } from './api-client/fact-build-api';
import { issueRecordsApi } from './api-client/issue-records-api';
import { labelGroupsApi } from './api-client/label-groups-api';
import { legacyDatabaseApi } from './api-client/legacy-database-api';
import { mirrorApi } from './api-client/mirror-api';
import { qualityBoardApi } from './api-client/quality-board-api';
import { reviewDataApi } from './api-client/review-data-api';
import { statisticBoardsApi } from './api-client/statistic-boards-api';
import { issueScopesApi } from './api-client/issue-scopes-api';

export * from './types/api';

export const api = {
  ...analyticsDashboardApi,
  ...catMirrorApi,
  ...mirrorApi,
  ...statisticBoardsApi,
  ...databaseBrowserApi,
  ...factBuildApi,
  ...issueScopesApi,
  ...collectFormsApi,
  ...codeReviewApi,
  ...reviewDataApi,
  ...issueRecordsApi,
  ...labelGroupsApi,
  ...legacyDatabaseApi,
  ...qualityBoardApi,
};
