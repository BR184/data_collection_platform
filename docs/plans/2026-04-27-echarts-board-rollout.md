# ECharts Board Rollout Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the remaining placeholder board pages with real Apache ECharts dashboards and upgrade the existing code-review board to a chart-first experience that matches the new platform's shell, filters, and data-scope model.

**Architecture:** Reuse the current backend contracts wherever possible. Build a small frontend ECharts wrapper plus board-specific data mappers, then wire quality-board, code-review multi-board, and system-test multi-board pages to existing APIs. Only extend backend contracts where the current payload is missing a metric that the board needs directly.

**Tech Stack:** Vue 3, TypeScript, Vite, Element Plus, Apache ECharts, existing `api-client/*`, existing `useDataScope` / `feature-manifest.ts`.

---

### Task 1: Add chart runtime and shared wrapper

**Files:**
- Modify: `frontend/package.json`
- Create: `frontend/src/components/charts/EChartPanel.vue`
- Create: `frontend/src/components/charts/chart-theme.ts`

**Steps:**
1. Add `echarts` as a frontend dependency.
2. Create a shared `EChartPanel` wrapper that:
   - accepts `option`
   - initializes/destroys chart instances safely
   - resizes on container resize
   - supports loading/empty states
3. Add a small shared color/theme helper so all board pages use one palette.
4. Verify `npm run build`.

### Task 2: Upgrade code-review multi-board to chart-first

**Files:**
- Modify: `backend/src/main/java/com/data/collection/platform/entity/CodeReviewMultiBoardOverviewResponse.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/CodeReviewMultiBoardService.java`
- Modify: `backend/src/test/java/com/data/collection/platform/service/CodeReviewMultiBoardServiceTest.java`
- Modify: `backend/src/test/java/com/data/collection/platform/controller/CodeReviewControllerTest.java`
- Modify: `frontend/src/types/api.ts`
- Modify: `frontend/src/views/CodeReviewMultiBoardView.vue`

**Steps:**
1. Extend the overview payload with the missing aggregate metrics needed by the board, especially total added lines and overall defect density.
2. Keep the existing `source` switch contract unchanged.
3. Replace the current table-heavy first view with:
   - KPI cards
   - module distribution chart
   - owner distribution chart
   - supporting ranked tables below
4. Keep the existing smoke test and update it to assert the new board text structure.
5. Run focused backend tests and frontend build/tests.

### Task 3: Implement system-test multi-board from existing statistic boards

**Files:**
- Create: `frontend/src/views/SystemTestMultiBoardView.vue`
- Create: `frontend/src/views/system-test-multi-board.ts`
- Create: `frontend/src/views/system-test-multi-board.mount-smoke.test.ts`
- Modify: `frontend/src/router.ts`

**Steps:**
1. Build a dedicated page that loads existing statistic board APIs:
   - `system-test-defect-summary`
   - `system-test-phase-statistics`
   - `system-test-defect-cause`
   - `system-test-delay-analysis`
2. Transform current board rows into chart datasets instead of adding a new backend contract.
3. Render a clean chart dashboard with a shared top filter context and links back to the formal statistic-board pages.
4. Cover mount smoke with API mocks.
5. Run frontend build/tests.

### Task 4: Implement quality-board pages with existing domain APIs

**Files:**
- Create: `frontend/src/views/QualityBoardRdView.vue`
- Create: `frontend/src/views/QualityBoardOtherView.vue`
- Create: `frontend/src/views/quality-board-rd.mount-smoke.test.ts`
- Create: `frontend/src/views/quality-board-other.mount-smoke.test.ts`
- Modify: `frontend/src/router.ts`

**Steps:**
1. Replace the two quality-board placeholders with real pages.
2. `QualityBoardRdView` should focus on strong summary signals:
   - review densities
   - code-review density
   - integration pass signal
   - system-test signal
   - 2-3 supporting charts only
3. `QualityBoardOtherView` should host secondary but still useful charts from existing APIs instead of duplicating the formal table pages.
4. Keep the layout chart-first but concise: no long tab ribbons, no nested cards.
5. Cover both views with mount smoke tests.

### Task 5: Update docs and route metadata

**Files:**
- Modify: `docs/project-progress.md`
- Modify: `docs/plans/2026-04-27-data-scope-reuse.md`
- Modify: `frontend/src/feature-manifest.ts`

**Steps:**
1. Update docs to reflect that the placeholder board pages now have real ECharts content.
2. Keep the source/test-phase shell placement documented.
3. Refresh page descriptions so the shell copy matches the new board behavior.

### Task 6: Final verification

**Files:**
- No code changes required unless fixes are found

**Steps:**
1. Run frontend `npm run build`.
2. Run focused Vitest mount smoke for the new/changed views.
3. Run focused backend tests for code-review multi-board.
4. Start the frontend dev server and verify the new board pages manually.
