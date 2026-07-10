# Quality Board Data Alignment Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Restore the legacy platform's data categories on the R&D Quality Board and Other Board while keeping DGM compatibility-mode reads isolated and removable.

**Architecture:** Keep the R&D page responsible for the legacy eight headline metrics and five supporting charts. Rebuild the Other Board around the six legacy `PersonalQuality.vue` statistics. Centralize code-review table selection and DGM compatibility aliases in one backend support class so formal reads never depend on compatibility tables, aliases, or MR identity rules.

**Tech Stack:** Spring Boot, JdbcTemplate, PostgreSQL, Vue 3, TypeScript, Element Plus, Apache ECharts, JUnit 5, Mockito, Vitest.

---

## Confirmed legacy contract

### R&D Quality Board (`/ninePersonalQuality`)

- Eight headline metrics: demand/design review density, CC/DGM code-review density, integration pass rate, release leakage rate, development leakage rate, new-issue fix rate.
- Five charts: reviewer defect density, author defect density, defects by fixer/severity, commit frequency, remaining defects by assignee.
- DGM applies only to the DGM headline metric and the three code-review charts.

### Other Board (`/personalQuality`)

- Function defect count.
- Function defect density.
- Quality ranking.
- Member unresolved-defect rate.
- Release defect leakage rate by release.
- Development defect leakage rate by release.
- No DGM selector or DGM endpoint is part of this page.

## Compatibility boundary

- Compatibility CC/DGM code-review reads use `code_review_match_mode_records`.
- Compatibility DGM accepts the selected `CCyyyyRr` name and the legacy `CrownCAD yyyy Rr` alias, and groups by legacy MR IID.
- Formal CC reads use `merge_request_fact` with `source_instance=default` and `deleted=false`.
- Formal mode does not expose or query DGM until a separate formal DGM ingestion contract exists.
- Every temporary branch is marked `//兼容模式-MatchMode` and contained in the code-review read support class.

### Task 1: Lock the compatibility/formal query contract with tests

**Files:**
- Create: `backend/src/test/java/com/data/collection/platform/service/QualityBoardCodeReviewReadSupportTest.java`
- Create: `backend/src/main/java/com/data/collection/platform/service/QualityBoardCodeReviewReadSupport.java`

1. Write a failing test asserting compatibility DGM reads the match table, accepts both project names, groups by MR IID, and omits `deleted`.
2. Run only `QualityBoardCodeReviewReadSupportTest` and confirm the intended assertion fails.
3. Write a failing test asserting formal CC reads only `merge_request_fact`, maps CC to `default`, and applies `deleted=false`.
4. Add a failing test asserting formal DGM returns no rows without querying the compatibility table.
5. Implement the smallest read-support API needed by the tests.

### Task 2: Restore the R&D Quality Board response

**Files:**
- Create: `backend/src/main/java/com/data/collection/platform/entity/QualityBoardRdDashboardResponse.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/QualityBoardRdService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/controller/QualityBoardController.java`
- Test: `backend/src/test/java/com/data/collection/platform/service/QualityBoardRdServiceTest.java`

1. Write failing tests for the eight metrics plus five chart collections.
2. Add a dashboard endpoint accepting `projectName` and `codeReviewSource`.
3. Delegate all code-review table/source/project handling to `QualityBoardCodeReviewReadSupport`.
4. Keep issue and integration-test charts on `issue_fact` and `integration_test_fact`.
5. Preserve the user's existing `open/opened` fix.

### Task 3: Rebuild the Other Board response around the six legacy statistics

**Files:**
- Modify: `backend/src/main/java/com/data/collection/platform/entity/QualityBoardOtherOverviewResponse.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/QualityBoardRdService.java`
- Test: `backend/src/test/java/com/data/collection/platform/service/QualityBoardRdServiceTest.java`

1. Write failing tests for all six response collections.
2. Implement function defect count from `issue_fact` grouped by normalized function.
3. Implement function defect density from CC code-review added lines plus CC issue defects; only this CC input follows the compatibility/formal read boundary.
4. Implement quality ranking from issue defects per fixer divided by CC added lines per author.
5. Implement unresolved-defect rate per fixer.
6. Implement release and development leakage series for enabled releases.

### Task 4: Align frontend API contracts and types

**Files:**
- Modify: `frontend/src/types/api/quality-board.ts`
- Modify: `frontend/src/api-client/quality-board-api.ts`
- Test: `frontend/src/api-client/quality-board-api.test.ts`

1. Write failing request/response contract tests.
2. Add the R&D dashboard call and the six-series Other Board response types.
3. Keep compatibility state out of page components; the backend supplies available code-review sources.

### Task 5: Recompose the R&D page with five appropriate ECharts panels

**Files:**
- Modify: `frontend/src/views/QualityBoardRdView.vue`
- Modify: `frontend/src/views/quality-board.ts`
- Modify: `frontend/src/views/quality-board-rd.mount-smoke.test.ts`

1. Replace the three synthetic comparison panels with the five legacy data categories.
2. Use one local CC/DGM segmented control for the three code-review charts; hide DGM when the backend does not expose it.
3. Use ranked horizontal bars for reviewer/author density and remaining defects, stacked bars for fixer/severity, and a compact column chart for commit frequency.
4. Retain the eight headline cards and existing project selection.

### Task 6: Recompose the Other Board with six appropriate ECharts panels

**Files:**
- Modify: `frontend/src/views/QualityBoardOtherView.vue`
- Modify: `frontend/src/views/quality-board.ts`
- Modify: `frontend/src/views/quality-board-other.mount-smoke.test.ts`

1. Remove the duplicated headline summary and the five misplaced R&D charts.
2. Render function count/density, quality ranking, unresolved rate, release leakage, and development leakage.
3. Use horizontal ranked bars for categorical/person data and compact line/column charts for release series.
4. Do not add a DGM selector.

### Task 7: Correct the business rule and run one focused verification

**Files:**
- Modify: `docs/platform-page-business-rules.md`

1. Correct the page mapping: R&D → `NinePersonalQuality.vue`; Other → `PersonalQuality.vue`.
2. Document which R&D charts use DGM and that Other Board has no DGM contract.
3. Run the focused backend quality-board tests once.
4. Run the focused frontend quality-board tests once.
5. Do not run the full repository verification suite.

No commit is included in this plan because the working tree already contains user-owned uncommitted changes and no commit was requested.
