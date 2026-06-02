# Local GitLab Old/New Comparison Record - 2026-06-02

This record tracks the external-network comparison requested for the local GitLab source.
Old-platform results remain the comparison baseline where the old platform can ingest the same source data.

## Status Legend

| Status | Meaning |
| --- | --- |
| `PASS_REAL` | Real local service/database path passed, but not old-platform same-display comparison. |
| `PASS_SAME_DATA` | Same data passed on old and new platform visible/API/export results. |
| `FAILED` | Executed and failed. |

## Results

| ID | Check | Status | Evidence | Blocker / Note | Next Action |
| --- | --- | --- | --- | --- | --- |
| LG-001 | Start old platform locally | `PASS_REAL` | Started `D:\projects\spidergitdata-dev` on `http://localhost:8091` with local MySQL `gitlab_spider/gitlab_spider_dgm` and Mongo. `git_project` was populated with 3 local GitLab projects. | Startup still triggered old scheduled tasks; `spring.task.scheduling.enabled=false` did not fully suppress them. | Use a more isolated old-platform runtime before formal comparison, or stop automatic tasks after startup. |
| LG-002 | Old platform ingest local GitLab project 2 issues | `PASS_REAL` | Temporarily patched old-platform `TimeUtil.parseStrToDate` to accept GitLab 16 `+08:00` timestamps. `POST http://localhost:8091/spiderCCProduct/updateIssueInfo2Spider_issue_data?projectId=2` returned 200. Old MySQL `spider_issue_data` has 6 rows for project 2. | Patch is only a local comparison-enabling compatibility patch in `D:\projects\spidergitdata-dev`; it is not part of the new-platform repo. | Keep the patch documented when rerunning external comparison; do not treat it as a production old-platform change. |
| LG-003 | GitLab source DB vs new-platform `issue_fact` | `PASS_REAL` | `python scripts/compare_gitlab_source_to_new_facts.py --source-instance cc --project-id 1 --project-id 2 --project-id 3 --output .tmp/gitlab-source-vs-new-facts-20260602.json` passed. Source issue counts `382/6/1` matched fact counts `382/6/1`; missing/extra IID lists were empty. | This validates source-to-new-platform completeness, not old-platform display equivalence. | Keep as a real-chain guard; rerun before old/new same-data comparison. |
| LG-004 | GitLab source DB vs new-platform `merge_request_fact` | `PASS_REAL` | Same report `.tmp/gitlab-source-vs-new-facts-20260602.json` passed. Source MR counts for projects 1/2 were `2/1`; fact counts were `2/1`; missing/extra IID lists were empty. | This validates source-to-new-platform completeness, not old-platform display equivalence. | Keep as a real-chain guard; add field-level MR comparison after old-platform MR ingestion is available. |
| LG-005 | Old-platform vs new-platform same-data issue identity comparison | `PASS_REAL` | `python scripts/compare_old_platform_issue_data_to_new_facts.py --project-id 1 --source-instance cc --output .tmp/old-vs-new-issue-project1-20260602.json` and project 2 reruns show old/new counts match: project 1 `382/382`, project 2 `6/6`; missing/extra IID lists are empty. | This proves same-batch identity completeness, not display equivalence. | Keep as guard before field/display comparison. |
| LG-006 | Old-platform vs new-platform same-data issue display fields | `FAILED` | `.tmp/old-vs-new-issue-project2-20260602-after-assignee-fix.json` still fails display-normalized comparison. After fixing new `assignee_name`, remaining project 2 display mismatches are `moduleName=2`, `urgency=2`, `bugStatus=6`, `testingPhase=1`. Project 1 identity passes but all 382 rows still differ on `bugStatus` display baseline. | New platform parses `模块::` / `严重度::` labels and reports open state as `未关闭`; old platform local parser leaves those as `未设定*` placeholders. User-visible semantics must be decided against old-platform baseline before marking `PASS_SAME_DATA`. | Decide whether old placeholder behavior is the required baseline or whether new parsed values count as superior display; then adjust comparison rules or new display fields and rerun. |
| LG-007 | New-platform system-test issue API after assignee fix | `PASS_REAL` | `python scripts/real_chain_api_smoke.py --base-url http://localhost:18080 --output-dir .tmp/api-real-smoke-18080-compare-20260602` passed 28 endpoints. Authenticated `GET /api/question-metrics/issues?projectId=2&sourceInstance=cc&page=1&size=10` returns one system-test row for issue `#2` with `assigneeName=Administrator`. | Old-platform public API `/issueStaticData/filter` is hard-coded for legacy project IDs `9/325`, so local project IDs `1/2` cannot be compared through that endpoint without another old-platform test patch. | Continue DB-backed comparison for local GitLab project IDs, or add a clearly documented old-platform project-ID compatibility patch for API/page-level comparison. |

## Current Conclusion

New-platform facts are complete against the local GitLab source for CC projects 1/2/3 at the issue and MR IID level.
Old-platform issue ingestion is now running under a documented local compatibility patch, and same-batch issue identity matches for projects 1/2.
The comparison is not yet `PASS_SAME_DATA` because user-visible issue fields still differ from the old-platform baseline.
