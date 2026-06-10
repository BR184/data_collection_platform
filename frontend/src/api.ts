import { codeReviewApi } from './api-client/code-review-api';
import { collectFormsApi } from './api-client/collect-forms-api';
import { databaseBrowserApi } from './api-client/database-browser-api';
import { integrationTestsApi } from './api-client/integration-tests-api';
import { issueRecordsApi } from './api-client/issue-records-api';
import { mirrorApi } from './api-client/mirror-api';
import { reviewDataApi } from './api-client/review-data-api';
import { statisticBoardsApi } from './api-client/statistic-boards-api';
import { testingPhasesApi } from './api-client/testing-phases-api';

export * from './types/api';

export const api = {
  ...mirrorApi,
  ...integrationTestsApi,
  ...statisticBoardsApi,
  ...databaseBrowserApi,
  ...testingPhasesApi,
  ...collectFormsApi,
  ...codeReviewApi,
  ...reviewDataApi,
  ...issueRecordsApi,
};
