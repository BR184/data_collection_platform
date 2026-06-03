# System Test Issue And Integration Gap Investigation - 2026-06-03

## Scope

This note records the investigation for two reported production/intranet symptoms:

- Old/new integration-test comparison and Excel export show different module names and counts.
- ODS `issue` has `iid = 22637`, but the new platform `系统测试 -> 议题查询` page cannot find it. Intranet issue query is about 30k records while the new platform shows about 24k, with apparent data gaps. Search may also return empty results.

No production code was changed in this investigation. One local DB probe was attempted only for orientation; the local platform DB is not representative of the intranet dataset.

## Executive Findings

| Finding | Status | Impact |
| --- | --- | --- |
| `系统测试 -> 议题查询` reads `issue_fact`, not ODS `issue` directly. | Confirmed by code | An ODS issue can exist and still not appear if it was not mirrored into facts, was not rebuilt, is marked deleted, or does not match the system-test scope. |
| The issue query page is scoped to system/regression test labels/phases. | Confirmed by code | Comparing all ODS issues or an old all-issue page to this page will naturally show fewer rows. |
| Keyword search in the SQL page path depends on `issue_fact.search_*` and `title_search_*` shadow fields. | Confirmed by code | If these columns are null/stale, general keyword search can return empty even when title/module fields are populated. |
| `iid=22637` must be checked against `issue_fact.issue_iid`, not `issue_fact.issue_id`. | Confirmed by code | Looking up the wrong identifier column can produce false negatives. |
| Integration-test aggregation/export is sourced from `integration_test_fact`, and that fact table only includes issues with notes containing the integration-test data marker. | Confirmed by code | Old/new module/count differences are expected if the old page used its materialized `spider_integration_data` table and the new fact rebuild missed or filtered notes/modules. |
| Integration-test summary/export filters out blank `module_name`. | Confirmed by code | Records parsed from notes but without recognized module labels do not contribute to module counts/exports. |
| Backend supports `sourceInstance` for both issue-query and integration-test APIs, but the current frontend calls do not send it. | Confirmed by code | In multi-source environments such as CC/DGM/default, counts can mix or omit source-specific data depending on how facts were built and queried. |

## New Platform Code Path: System-Test Issue Query

The frontend page is `frontend/src/views/SystemTestIssueSearchView.vue`.

- It calls `api.getSystemTestIssueSearchRecords(buildCurrentQueryParams(true))`.
- `buildCurrentQueryParams` sends filters such as `keyword`, `issueIid`, `title`, `projectName`, `moduleName`, `testingPhase`, dates, sort and pagination.
- It does not send `sourceInstance`.

The frontend API wrapper is `frontend/src/api-client/issue-records-api.ts`.

- `getSystemTestIssueSearchRecords` calls `GET /api/question-metrics/issues`.
- The query builder includes many filters, but not `sourceInstance`.

The backend endpoint is `QuestionMetricsController`.

- `GET /api/question-metrics/issues` maps to `SystemTestIssueSearchService.listRecords(...)`.
- The request model can carry `sourceInstance` through `IssueFactRecordListWebRequest -> IssueFactRecordListRequest`, but the frontend currently does not populate it.

The actual data read path is `IssueFactRecordRepository`.

- Base table: `issue_fact`.
- Base predicate: `where deleted = false`.
- System-test scope predicate checks whether `testing_phase`, `system_test_label`, or `label_names` contains system-test/regression-test tokens.
- Keyword SQL search uses `search_text`, `search_compact`, `search_spell`, `search_initials`.
- Title SQL search uses `title_search_text`, `title_search_compact`, `title_search_spell`, `title_search_initials`.
- `issueIid` filter uses `cast(issue_iid as varchar) like ?`.

Important implication: `iid=22637` should be investigated in this order:

1. Does any `issue_fact` row have `issue_iid = 22637`?
2. If yes, is `deleted = false`?
3. If yes, does it match system-test scope through `testing_phase`, `system_test_label`, or `label_names`?
4. If yes, are the `search_*` fields populated?
5. If multiple `source_instance` values exist, is the row under the source instance the UI is intended to query?

## New Platform Code Path: Issue Fact Build

`FactBuildService` builds `issue_fact` from ODS mirror tables.

Confirmed mappings:

- ODS `ods_gitlab_issues.id` -> `issue_fact.issue_id`
- ODS `ods_gitlab_issues.iid` -> `issue_fact.issue_iid`
- ODS labels -> normalized module, severity, phase, exclusion/fixed/illegal fields
- ODS notes -> raw payload and several derived fields

Confirmed source SQL behavior:

- Main source is `ods_gitlab_issues i`.
- The issue source query filters `where coalesce(i.mirror_deleted, false) = false`.
- Labels, assignees, notes, projects and milestones are left-joined or CTE-joined with their own `mirror_deleted = false` checks.

Confirmed search-index behavior:

- After upserting issue facts, `FactBuildService.refreshIssueFactSearchIndexes(...)` updates the shadow columns.
- If an incremental rebuild detects existing facts with missing key search fields, `getIssueFactChangedSince(...)` returns null and forces a broader rebuild for that source instance.
- This safeguard only helps when fact rebuild is actually run successfully for the relevant source instance.

## New Platform Code Path: Integration Test

The frontend page is `frontend/src/views/IntegrationTestAnalysisView.vue`.

- It loads phase options, summary, details and exports through `integration-tests-api.ts`.
- Current frontend calls do not send `sourceInstance`.
- Current UI normalizes away `projectId` in `syncPageData`, so summary/export is primarily phase-scoped unless project is later restored through another path.

The backend endpoint is `IntegrationTestController`.

- Backend supports optional `sourceInstance` on project options, phase options, summary, details, detail export, module-function workbook export and comparison workbook export.

The fact build is `IntegrationTestFactBuildService`.

- Source SQL starts from `ods_gitlab_issues`.
- It joins only the latest issue note whose note text matches the integration-test data marker.
- If an issue has integration-test labels but does not have a parseable integration-test note, it does not enter `integration_test_fact`.
- Module is derived from normalized issue labels. The first recognized module becomes `module_name`.

The query/export service is `IntegrationTestQueryService`.

- Summary, details and exports read `integration_test_fact`.
- All summary/detail/export paths call `appendRecognizedModuleFilter`, which adds:

```sql
and nullif(btrim(coalesce(module_name, '')), '') is not null
```

Therefore records with blank/unrecognized module names are excluded from module summaries and exports.

## Old Platform Integration Source

Old platform path inspected: `D:/projects/spidergitdata-dev`.

The old integration endpoints use `IntegrationDataController`.

- `/integration/getTestingPhase` reads phase options from `DropDownService.getTestingPhaseFromSpiderIntegration()`.
- `/integration/getSearchByPage` queries `SpiderIntegrationData` through `IntegrationQueryBuilder`.
- module and phase filters are simple equality filters on `spider_integration_data.module_name` and `spider_integration_data.testing_phase`.
- old full/export paths also read `spider_integration_data` and convert rows to integration DTOs.

The old materialized table/entity is `SpiderIntegrationData`, mapped to `spider_integration_data`.

This means the old integration page is not a direct query of live GitLab ODS issue facts. It is a materialized/static integration dataset. The new platform uses `integration_test_fact`, rebuilt from mirrored GitLab ODS notes/labels. These are different upstream materialization chains unless explicitly aligned.

## Why The Reported Symptoms Can Happen

### 1. ODS has `iid=22637`, but UI cannot find it

This is plausible without contradicting the ODS observation, because the UI does not query ODS directly. It queries `issue_fact` and applies system-test scope.

Likely causes to verify:

- the row exists in ODS but was never rebuilt into `issue_fact`;
- `issue_fact.issue_iid = 22637` exists but `deleted = true`;
- the row exists but has no system-test/regression-test marker in `testing_phase`, `system_test_label`, or `label_names`;
- the row exists in a different `source_instance` than the intended UI scope;
- keyword search misses because `search_*` fields are null/stale;
- the user searched `22637` in the wrong field/path, or backend lookup was accidentally checked against `issue_id` rather than `issue_iid`.

### 2. Intranet issue query about 30k vs new platform about 24k

This may be a real gap, but raw totals alone do not identify the gap. The new system-test issue page is not "all ODS issues"; it is `issue_fact where deleted=false` plus system/regression-test scope. A 30k all-issue source compared with a 24k scoped fact page can differ legitimately.

The gap becomes actionable if the comparison is made on the same source instance, same deletion semantics, same system/regression labels, and same `issue_iid/project_id` identity keys.

### 3. Integration-test module names/counts differ

Confirmed likely causes:

- old platform reads `spider_integration_data`, not the new `integration_test_fact`;
- new fact build requires an integration-test note marker;
- new summary/export drops rows without recognized `module_name`;
- module normalization dictionary/rules can rename or drop dirty labels;
- frontend does not pass `sourceInstance`, so multi-source data can be mixed or silently scoped differently than expected.

### 4. "Searching anything returns nothing"

For the SQL page path, the general keyword filter does not search raw title/module columns directly. It searches generated shadow fields. The fallback method in `SystemTestIssueSearchService.matchesKeyword(...)` does search raw fields, but `canUseSqlPage(...)` currently returns true whenever the request is non-null, so the SQL path is the effective path.

If `search_text/search_compact/search_spell/search_initials` are null or stale for the relevant rows, general keyword search can return no rows.

## DB Verification SQL For Intranet

Run these on the new platform database. Replace `cc` with the intended source instance if applicable.

### A. Locate `iid=22637` in facts

```sql
select source_instance,
       project_id,
       issue_id,
       issue_iid,
       title,
       deleted,
       testing_phase,
       system_test_label,
       label_names,
       search_text is null as search_text_null,
       search_compact is null as search_compact_null,
       search_spell is null as search_spell_null,
       search_initials is null as search_initials_null,
       fact_refreshed_at,
       ods_updated_at
  from issue_fact
 where issue_iid = 22637
 order by source_instance, project_id, issue_id;
```

### B. Check whether the row would pass the page scope

```sql
select source_instance,
       project_id,
       issue_iid,
       deleted,
       (
         lower(coalesce(testing_phase, '')) like '%系统测试%'
         or lower(coalesce(system_test_label, '')) like '%系统测试%'
         or lower(coalesce(label_names, '')) like '%系统测试%'
         or lower(coalesce(testing_phase, '')) like '%回归测试%'
         or lower(coalesce(system_test_label, '')) like '%回归测试%'
         or lower(coalesce(label_names, '')) like '%回归测试%'
       ) as matches_system_test_scope
  from issue_fact
 where issue_iid = 22637;
```

### C. Compare ODS issue count vs fact count by source

```sql
select source_instance,
       count(*) as fact_rows,
       count(*) filter (where deleted = false) as active_fact_rows,
       count(*) filter (
         where deleted = false
           and (
             lower(coalesce(testing_phase, '')) like '%系统测试%'
             or lower(coalesce(system_test_label, '')) like '%系统测试%'
             or lower(coalesce(label_names, '')) like '%系统测试%'
             or lower(coalesce(testing_phase, '')) like '%回归测试%'
             or lower(coalesce(system_test_label, '')) like '%回归测试%'
             or lower(coalesce(label_names, '')) like '%回归测试%'
           )
       ) as system_test_scope_rows,
       count(*) filter (
         where deleted = false
           and (search_text is null or search_compact is null or search_spell is null or search_initials is null)
       ) as missing_search_index_rows
  from issue_fact
 group by source_instance
 order by source_instance;
```

### D. Check source-instance distribution

```sql
select source_instance,
       count(*) as issue_fact_rows,
       min(fact_refreshed_at) as first_fact_refresh,
       max(fact_refreshed_at) as last_fact_refresh
  from issue_fact
 group by source_instance
 order by source_instance;
```

### E. Check integration facts excluded by missing module

```sql
select source_instance,
       testing_phase,
       count(*) as fact_rows,
       count(*) filter (where nullif(btrim(coalesce(module_name, '')), '') is null) as missing_module_rows
  from integration_test_fact
 where deleted = false
 group by source_instance, testing_phase
 order by source_instance, testing_phase;
```

### F. Compare integration summary/export source size

```sql
select source_instance,
       testing_phase,
       module_name,
       count(*) as issue_count,
       sum(coalesce(execute_case, 0)) as execute_case_sum,
       sum(coalesce(pass_case, 0)) as pass_case_sum
  from integration_test_fact
 where deleted = false
   and nullif(btrim(coalesce(module_name, '')), '') is not null
 group by source_instance, testing_phase, module_name
 order by source_instance, testing_phase, lower(module_name);
```

## Local Probe Result

Local platform DB was probed through `127.0.0.1:15432/qaflex` using `qaflex/change_this_password`.

- `integration_test_fact` local probe returned only one active `cc` row and zero missing-module rows.
- Broader local `issue_fact` count probes timed out in this shell.
- Because this local DB is small/dev-shaped and not the intranet dataset, these results are not used as production evidence.

## Current Root-Cause Hypotheses

Most likely there are multiple issues, not one:

1. The integration-test mismatch is primarily a data-source/materialization mismatch: old `spider_integration_data` vs new `integration_test_fact`.
2. The missing `iid=22637` is likely in the ODS -> `issue_fact` -> system-test scope chain, not in the frontend table rendering itself.
3. The "search returns nothing" symptom is likely tied to missing/stale `issue_fact.search_*` fields or to querying the wrong source instance.
4. The 30k vs 24k gap needs same-scope identity comparison before it can be classified as a true data-loss gap.

## Recommended Next Investigation Steps

1. Run SQL sections A-F on the intranet platform DB.
2. For `iid=22637`, capture the ODS row, matching `issue_fact` row if any, and whether it passes the system-test scope predicate.
3. Compare by identity key `(source_instance, project_id, issue_iid)`, not by count only.
4. Split integration comparison into:
   - old `spider_integration_data` rows by phase/module;
   - new `integration_test_fact` rows by source/phase/module;
   - rows excluded only because `module_name` is blank;
   - rows absent because no integration-test note marker was parsed.
5. Decide whether the frontend should expose/select `sourceInstance` for issue-query and integration-test pages before making code changes.
