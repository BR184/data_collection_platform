# Filter Tag Group Entry Inventory

Date: 2026-06-05

This inventory is the Task 1 baseline for tag group rollout. It records the current filter entry points from code and the phase-one action for each page.

| Page | Current entry | Evidence | Phase-one action | Fields to weaken or remove |
|---|---|---|---|---|
| `frontend/src/views/SystemTestIssueSearchView.vue` | Fixed field filters rendered by `BaseRecordTable` -> `RecordTableFilterFields`; no `StatisticFilterBuilder` import. | `primaryFilters`, `advancedFilters`, `buildCurrentQueryParams`. | Incrementally add tag groups; this is not a builder replacement. | Weaken `综合搜索` and `模块关键词` after compatibility decision. |
| `frontend/src/views/ReviewDataManagementView.vue` | Mixed: `BaseRecordTable` plus default `filter-builder` slot using `StatisticFilterBuilder`. | `<StatisticFilterBuilder :model-value="filterDraft" ... />`. | Replace builder as the default business entry with tag groups + fixed fields; keep builder only if a complex expression entry is explicitly needed later. | Avoid exposing raw dirty module values as the main module entry. |
| `frontend/src/components/StatisticBoardToolbar.vue` | `StatisticFilterBuilder` for statistic board conditions. | Toolbar renders active statistic fields through builder. | Keep builder. Statistic boards continue using `filterGroup`, not business-page `tagSelections`. | None in phase one. |
| `frontend/src/views/CodeReviewIllegalRecordsView.vue` | `StatisticFilterBuilder` in record-table `filter-builder` slot. | Imports and renders `StatisticFilterBuilder`. | Defer phase-one tag group replacement unless code-review illegal records are explicitly brought into Task 4b. | No default removal in checkpoint 1. |
| `frontend/src/views/issue-illegal-records/IssueIllegalRecordsPage.vue` | Shared illegal-record page with `StatisticFilterBuilder` slot. | Imports and renders `StatisticFilterBuilder`. | Defer by domain decision; if connected, list/export/options must all use `tagSelections`. | No default removal in checkpoint 1. |
| `frontend/src/views/CustomerIssueRecordsView.vue` | `StatisticFilterBuilder` in record-table `filter-builder` slot. | Imports and renders `StatisticFilterBuilder`. | Defer phase-one replacement unless customer issue pages are included in Task 4b. | No default removal in checkpoint 1. |

Phase-one scope decision:

- `SystemTestIssueSearchView.vue`: add tag group entry beside existing fixed field filters.
- `ReviewDataManagementView.vue`: first business page to replace the default builder entry.
- Statistic boards: keep `StatisticFilterBuilder` and existing `filterGroup`.
- Illegal records, customer issue records, and code-review illegal records: do not partially connect. If included later, connect list, export, and filter options together.

Module matching decision for checkpoint 1:

- Keep the existing new-platform comma-delimited `issue_fact.module_names` boundary matching.
- Do not parse new-platform `module_names` with the old platform `&` separator.
- Module standardization remains owned by `ModuleDictionaryService` and `module_dictionary`; tag groups must not create a second module alias authority.
