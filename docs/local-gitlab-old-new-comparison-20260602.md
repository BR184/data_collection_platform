# Local GitLab Old/New Comparison Record - 2026-06-02

This record tracks the external-network comparison requested for the local GitLab source.
Old-platform results remain the comparison baseline where the old platform can ingest the same source data.

## Status Legend

| Status | Meaning |
| --- | --- |
| `PASS_REAL` | Real local service/database path passed, but not old-platform same-display comparison. |
| `PASS_SAME_DATA` | Same data passed on old and new platform visible/API/export results. |
| `BLOCKED_ENV` | Required local old-platform/source compatibility is not available. |
| `FAILED` | Executed and failed. |

## Results

| ID | Check | Status | Evidence | Blocker / Note | Next Action |
| --- | --- | --- | --- | --- | --- |
| LG-001 | Start old platform locally | `PASS_REAL` | Started `D:\projects\spidergitdata-dev` on `http://localhost:8091` with local MySQL `gitlab_spider/gitlab_spider_dgm` and Mongo. `git_project` was populated with 3 local GitLab projects. | Startup still triggered old scheduled tasks; `spring.task.scheduling.enabled=false` did not fully suppress them. | Use a more isolated old-platform runtime before formal comparison, or stop automatic tasks after startup. |
| LG-002 | Old platform ingest local GitLab project 2 issues | `BLOCKED_ENV` | `POST http://localhost:8091/spiderCCProduct/updateIssueInfo2Spider_issue_data?projectId=2` fetched 6 issues, then returned HTTP 500. Error body: `Cannot invoke "com.huayun.entity.Issue.getProjectId()" because "issue" is null`. Old log root cause: `Unparseable date: "2026-05-20T11:13:58.275+08:00"` at `TimeUtil.parseStrToDate`. | Old platform parser expects an older GitLab timestamp format and cannot parse GitLab 16 API timestamps with `+08:00`; this prevents old-platform same-data import. | Prepare an old-platform-compatible GitLab source or temporarily patch old-platform date parsing, then rerun old ingest and comparison. |
| LG-003 | GitLab source DB vs new-platform `issue_fact` | `PASS_REAL` | `python scripts/compare_gitlab_source_to_new_facts.py --source-instance cc --project-id 1 --project-id 2 --project-id 3 --output .tmp/gitlab-source-vs-new-facts-20260602.json` passed. Source issue counts `382/6/1` matched fact counts `382/6/1`; missing/extra IID lists were empty. | This validates source-to-new-platform completeness, not old-platform display equivalence. | Keep as a real-chain guard; rerun before old/new same-data comparison. |
| LG-004 | GitLab source DB vs new-platform `merge_request_fact` | `PASS_REAL` | Same report `.tmp/gitlab-source-vs-new-facts-20260602.json` passed. Source MR counts for projects 1/2 were `2/1`; fact counts were `2/1`; missing/extra IID lists were empty. | This validates source-to-new-platform completeness, not old-platform display equivalence. | Keep as a real-chain guard; add field-level MR comparison after old-platform MR ingestion is available. |
| LG-005 | Old-platform vs new-platform same-data visible/API/export comparison | `BLOCKED_ENV` | No `PASS_SAME_DATA` result yet. Old-platform issue ingestion is blocked by timestamp parsing before `spider_issue_data` can be populated. | Same-batch old/new display comparison cannot be valid while old-platform local ingestion fails. | After LG-002 is unblocked, compare system-test issue lists, defect summaries, integration views, customer issue views, code-review views, and exports. |

## Current Conclusion

New-platform facts are complete against the local GitLab source for CC projects 1/2/3 at the issue and MR IID level.
Old-platform same-data comparison is not complete because the local old platform cannot ingest GitLab 16 issue API timestamps without failing.
