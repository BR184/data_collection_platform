# System Test Illegal Records Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement the `系统测试非法数据` page using the new platform architecture and existing reusable services/components.

**Architecture:** Use `issue_fact` as the only data source, reuse `SystemTestScopeProfile` for system-test scope, and reuse existing illegal-record/list-page infrastructure instead of recreating old-platform behavior. The page should expose a record-list API under `/api/question-metrics/illegal-records`, render through shared frontend record-page building blocks, and keep rule explanation consistent with the handover document.

**Tech Stack:** Java 21, Spring Boot, MyBatis/JdbcTemplate-backed fact query services, Vue 3, Element Plus, Vitest, JUnit 5.

---

## Product Scope

Implement `系统测试非法数据` as a formal page for `/question-metrics/illegal-records`.

Use the handover rules only as functional requirements:

- Scope: CrownCAD system/regression test issues, represented in this codebase by `SystemTestScopeProfile`.
- Exclusion: apply the same exclusion rule used by system-test statistic boards, so `issue_fact.is_excluded = true` rows do not appear.
- Illegal source: consume `issue_fact.is_illegal` and `issue_fact.illegal_reason`; do not recompute from labels/comments in the page service.
- Supported display illegal reasons:
  - `未设定严重程度`
  - `未设定模块`
  - `未按照模板回复`
  - `缺陷原因不唯一`
- Alias handling: map existing fact-layer values `缺失严重程度` -> `未设定严重程度` and `缺失模块` -> `未设定模块` at the system-test illegal-record API boundary. Do not change customer-issue behavior in this feature.
- Filtering: support project, testing phase, module, illegal reason, issue state, severity, bug status, category, milestone, author, assignee, created time, updated time, keyword, sort and pagination.
- Rule explanation: include scope, exclusion, illegal filtering, and reason normalization steps.

Out of scope:

- No old-platform code porting.
- No new external data-source/sync logic.
- No new crawler/scheduled task.
- No GitLab write-back/commenting.

## Reuse Targets

Backend:

- `IssueFactRecordRepository`
- `AbstractIssueFactRecordListService`
- `SystemTestScopeProfile`
- `IssueFactRecordListRequest`
- `IssueFactRecordFilterGroupSupport`
- `PageSliceSupport`
- `SortSupport`
- `TextQuerySupport`
- `StatisticBoardRuleExplanationResponse`

Frontend:

- `BaseRecordTable`
- `PageStateShell`
- `StatisticFilterBuilder`
- `useRouteTableState`
- `useConditionFilterGroupState`
- `useRecordPageController`
- `useRuleExplanationPanel`
- `buildIssueIidCellValue`
- Existing `CustomerIssueIllegalRecordsView.vue` patterns, preferably via extraction instead of copying.

---

## Task 1: Add Backend Contract Types

**Files:**

- Create: `backend/src/main/java/com/data/collection/platform/entity/SystemTestIllegalRecordRowResponse.java`
- Create: `backend/src/main/java/com/data/collection/platform/entity/SystemTestIllegalRecordListResponse.java`
- Create: `backend/src/main/java/com/data/collection/platform/entity/SystemTestIllegalRecordFilterOptionsResponse.java`
- Create: `backend/src/main/java/com/data/collection/platform/service/SystemTestIllegalRecordQueryRequest.java`

**Implementation Notes:**

`SystemTestIllegalRecordRowResponse` should mirror the fields needed by existing record pages:

- `issueId`
- `issueIid`
- `issueLink`
- `projectId`
- `projectName`
- `title`
- `issueState`
- `testingPhase`
- `illegalReason`
- `severityLevel`
- `bugStatus`
- `category`
- `milestoneTitle`
- `authorName`
- `assigneeName`
- `moduleNames`
- `createdAt`
- `updatedAt`
- `closedAt`
- `labels`

`SystemTestIllegalRecordFilterOptionsResponse` should include:

- `projectNames`
- `moduleNames`
- `testingPhases`
- `illegalReasons`
- `authorNames`
- `assigneeNames`
- `issueStates`
- `severityLevels`
- `bugStatuses`
- `categories`
- `milestoneTitles`

`SystemTestIllegalRecordQueryRequest` should contain:

- `IssueFactRecordListRequest listRequest`
- `String testingPhase`
- `String illegalReason`
- `String authorName`
- `String assigneeName`
- `String filterGroupJson`

**Tests:** None yet; compile after Task 2.

---

## Task 2: Add System-Test Illegal Service

**Files:**

- Create: `backend/src/main/java/com/data/collection/platform/service/SystemTestIllegalRecordService.java`
- Create: `backend/src/main/java/com/data/collection/platform/service/SystemTestIllegalReasonSupport.java`
- Test: `backend/src/test/java/com/data/collection/platform/service/SystemTestIllegalRecordServiceTest.java`

**Service Behavior:**

`SystemTestIllegalRecordService` should extend `AbstractIssueFactRecordListService`.

Pipeline:

1. `loadFacts(projectId)`
2. filter by `systemTestScopeProfile.matches(view.scopeContext())`
3. filter `!view.excluded()`
4. filter `view.illegal()`
5. normalize and allow only system-test illegal reasons through `SystemTestIllegalReasonSupport`
6. apply base filters from `IssueFactRecordListRequest`
7. apply `testingPhase`, `illegalReason`, `authorName`, `assigneeName`
8. apply `IssueFactRecordFilterGroupSupport.matches`
9. sort and paginate
10. map to `SystemTestIllegalRecordRowResponse`

`SystemTestIllegalReasonSupport`:

- `normalize("缺失严重程度") = "未设定严重程度"`
- `normalize("缺失模块") = "未设定模块"`
- allow `"未按照模板回复"` and `"缺陷原因不唯一"`
- return `null` for unsupported values such as `"流程越位"` so the system-test page stays aligned with the handover function.

Rule explanation should say this page consumes `issue_fact` and does not recalculate comments/labels in the query service.

**Test Cases:**

- service returns only system-test scoped, non-excluded, illegal rows.
- service excludes unsupported illegal reason `"流程越位"`.
- service maps aliases to handover labels.
- `illegalReason=未设定模块` matches a stored row with `缺失模块`.
- `testingPhase`, `moduleName`, keyword and sort are applied.
- rule explanation contains the four expected reasons.

**Command:**

```powershell
$projectRoot = 'D:\projects\data_collection_platform'
$env:JAVA_HOME = (Resolve-Path (Join-Path $projectRoot 'tools\jdk\jdk-21.0.10+7')).Path
$env:MAVEN_HOME = (Resolve-Path (Join-Path $projectRoot 'tools\maven\apache-maven-3.9.9')).Path
$env:PATH = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:PATH"
mvn -q "-Dtest=SystemTestIllegalRecordServiceTest" test
```

Expected: test passes.

---

## Task 3: Expose Question Metrics API

**Files:**

- Modify: `backend/src/main/java/com/data/collection/platform/controller/QuestionMetricsController.java`
- Test: `backend/src/test/java/com/data/collection/platform/controller/QuestionMetricsControllerTest.java`

**Endpoints:**

- `GET /api/question-metrics/illegal-records`
- `GET /api/question-metrics/illegal-records/filter-options`
- `GET /api/question-metrics/illegal-records/rule-explanation`

Keep parameter naming aligned with existing record APIs:

- `projectId`
- `keyword`
- `issueIid`
- `title`
- `projectName`
- `moduleName`
- `testingPhase`
- `illegalReason`
- `authorName`
- `assigneeName`
- `issueState`
- `severityLevel`
- `bugStatus`
- `category`
- `milestoneTitle`
- `createdAtStart`
- `createdAtEnd`
- `updatedAtStart`
- `updatedAtEnd`
- `filterGroup`
- `page`
- `size`
- `sortBy`
- `sortOrder`

**Tests:**

- Add controller test for list endpoint parameter mapping.
- Add controller test for filter options.
- Add controller test for rule explanation.
- Keep existing `/api/question-metrics/issues` tests unchanged.

**Command:**

```powershell
mvn -q "-Dtest=QuestionMetricsControllerTest,SystemTestIllegalRecordServiceTest" test
```

Expected: all tests pass.

---

## Task 4: Add Frontend API Types And Client Methods

**Files:**

- Modify: `frontend/src/types/api.ts`
- Modify: `frontend/src/api-client/issue-records-api.ts`

**Add Types:**

- `SystemTestIllegalRecordRowResponse`
- `SystemTestIllegalRecordListResponse`
- `SystemTestIllegalRecordFilterOptionsResponse`

**Add API Methods:**

- `getSystemTestIllegalRecords(params)`
- `getSystemTestIllegalRecordFilterOptions(projectId?)`
- `getSystemTestIllegalRecordRuleExplanation(projectId?)`

Use the same query construction pattern as `getCustomerIssueIllegalRecords`.

**Command:**

```powershell
cd frontend
yarn.cmd typecheck
```

Expected: typecheck passes after the page is wired in later tasks.

---

## Task 5: Add Shared System-Test Condition Fields

**Files:**

- Create: `frontend/src/views/system-test/system-test-condition-fields.ts`

**Behavior:**

Build `StatisticFilterField[]` for system-test issue pages with:

- keyword
- issueIid
- title
- moduleName
- projectName
- testingPhase
- illegalReason
- severityLevel
- issueState
- bugStatus
- category
- milestoneTitle
- authorName
- assigneeName
- createdAt
- updatedAt

This should be used by the new illegal page. It may later be reused to migrate `SystemTestIssueSearchView` away from its old filter model, but that migration is not required in this feature.

---

## Task 6: Extract Reusable Illegal Records Page Shell

**Files:**

- Create: `frontend/src/views/issue-illegal-records/IssueIllegalRecordsPage.vue`
- Create: `frontend/src/views/issue-illegal-records/issue-illegal-records-types.ts`
- Modify: `frontend/src/views/CustomerIssueIllegalRecordsView.vue`
- Test: `frontend/src/views/customer-issue-illegal-records.mount-smoke.test.ts`

**Goal:**

Avoid copying `CustomerIssueIllegalRecordsView.vue` for system-test. Extract the reusable layout and behavior into a shared page shell.

**Shared Shell Responsibilities:**

- route table state
- condition filter draft
- table load
- filter option load
- rule explanation drawer
- detail drawer
- `BaseRecordTable` rendering
- refresh/query/reset/page/sort handling

**Config Passed By Domain Page:**

- page labels and empty text
- API loader functions
- condition field builder
- columns
- row mapper
- detail metadata
- rule fallback title
- reset/query clear keys

**Customer Page Refactor:**

`CustomerIssueIllegalRecordsView.vue` should become a thin config wrapper around `IssueIllegalRecordsPage.vue`.

**Risk Control:**

Run existing customer smoke test immediately after refactor before adding system-test page.

**Command:**

```powershell
cd frontend
yarn.cmd test src/views/customer-issue-illegal-records.mount-smoke.test.ts
```

Expected: existing customer page still mounts and opens detail drawer.

---

## Task 7: Implement System-Test Illegal Records Page

**Files:**

- Create: `frontend/src/views/SystemTestIllegalRecordsView.vue`
- Modify: `frontend/src/router.ts`
- Modify: `frontend/src/feature-manifest.ts`
- Test: `frontend/src/views/system-test-illegal-records.mount-smoke.test.ts`
- Test: `frontend/src/router.test.ts`

**Routing:**

Replace `buildPlaceholderRoute('question-metrics-illegal-records')` with `SystemTestIllegalRecordsView`.

**Route Query Contract:**

Add allowed query keys for `question-metrics-illegal-records`:

- `page`
- `pageSize`
- `sortBy`
- `sortOrder`
- `projectId`
- `keyword`
- `issueIid`
- `title`
- `projectName`
- `moduleName`
- `testingPhase`
- `illegalReason`
- `authorName`
- `assigneeName`
- `issueState`
- `severityLevel`
- `bugStatus`
- `category`
- `milestoneTitle`
- `createdAtStart`
- `createdAtEnd`
- `updatedAtStart`
- `updatedAtEnd`
- `filterGroup`
- `filterLogic`

**Page Columns:**

- issueIid
- title
- illegalReason
- projectName
- moduleNames
- testingPhase
- severityLevel
- bugStatus
- issueState
- assigneeName
- updatedAt

**Smoke Test:**

- Stub filter options API.
- Stub list API with one illegal row.
- Stub rule explanation API if the test opens rules.
- Mount `/question-metrics/illegal-records?projectId=1001`.
- Assert row text and illegal reason render.
- Open detail drawer and assert labels/details render.

**Commands:**

```powershell
cd frontend
yarn.cmd test src/views/system-test-illegal-records.mount-smoke.test.ts src/router.test.ts
```

Expected: tests pass.

---

## Task 8: End-To-End Verification

**Backend Commands:**

```powershell
cd backend
$projectRoot = 'D:\projects\data_collection_platform'
$env:JAVA_HOME = (Resolve-Path (Join-Path $projectRoot 'tools\jdk\jdk-21.0.10+7')).Path
$env:MAVEN_HOME = (Resolve-Path (Join-Path $projectRoot 'tools\maven\apache-maven-3.9.9')).Path
$env:PATH = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:PATH"
$env:TEST_DATASOURCE_PASSWORD = 'qaflex_dev_2026'
$env:DATASOURCE_PASSWORD = 'qaflex_dev_2026'
mvn -q "-Dtest=SystemTestIllegalRecordServiceTest,QuestionMetricsControllerTest,CustomerIssueIllegalRecordServiceTest,CustomerIssueControllerTest" test
```

**Frontend Commands:**

```powershell
cd frontend
yarn.cmd test src/views/system-test-illegal-records.mount-smoke.test.ts src/views/customer-issue-illegal-records.mount-smoke.test.ts src/router.test.ts
yarn.cmd typecheck
npm run build
```

**Runtime Checks:**

With backend running:

```powershell
Invoke-WebRequest -Uri "http://localhost:18080/api/question-metrics/illegal-records?projectId=1001&page=1&size=20" -UseBasicParsing
Invoke-WebRequest -Uri "http://localhost:18080/api/question-metrics/illegal-records/filter-options?projectId=1001" -UseBasicParsing
Invoke-WebRequest -Uri "http://localhost:18080/api/question-metrics/illegal-records/rule-explanation?projectId=1001" -UseBasicParsing
```

Expected: all return HTTP 200 with `success=true`.

---

## Task 9: Update Project Progress Documentation

**Files:**

- Modify: `docs/project-progress.md`

**Changes:**

- Move `系统测试非法数据` from placeholder/test-only to implemented formal page.
- Note that it reuses `issue_fact`, `SystemTestScopeProfile`, shared record-page shell, and rule explanation.
- Add verification commands and date.

---

## Acceptance Checklist

- `/question-metrics/illegal-records` is no longer a placeholder.
- Page uses new-platform API and reusable components only.
- No old-platform code is copied.
- API filters to system-test scope and excludes `is_excluded=true`.
- API returns only the four handover-supported illegal reasons, with alias normalization.
- Customer issue illegal page still behaves the same after shared shell extraction.
- Backend targeted tests pass.
- Frontend smoke/router tests pass.
- Frontend typecheck/build passes.
