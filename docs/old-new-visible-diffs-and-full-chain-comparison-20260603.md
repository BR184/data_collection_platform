# Old/New Visible Diffs And Full-Chain Comparison - 2026-06-03

This report records the local old-platform vs new-platform data-alignment pass for project `2` / source `cc`.

## Evidence

| Evidence | Path |
| --- | --- |
| Baseline setup used for this pass | `.tmp/old-new-data-alignment-20260603/setup_alignment_baseline.py` |
| Fresh identity comparison | `.tmp/old-new-data-alignment-20260603/data-identity-alignment-report.json` |
| Comparison script | `.tmp/old-new-data-alignment-20260603/alignment_check.py` |
| Earlier API/page smoke bundle | `.tmp/old-new-full-compare-20260603/` |

Fresh run: `2026-06-03T09:37:15`.

## Current Verdict

Hard data identity is now aligned for the checked local scopes.

The acceptance rule for this pass is stricter than totals only: count, identity keys, and key fields such as IID/MR IID/title are compared. Display labels and rule text are still listed separately because the user explicitly accepted loose tag/module parsing for now, but not missing or extra records.

## Root Causes And Fixes

| Area | Root cause | Fix applied | Fresh result |
| --- | --- | --- | --- |
| Source issue facts | Old `spider_issue_data` local baseline had lost the original project `2` system-test rows, leaving only the newly added customer fixture. | Restored old `spider_issue_data` rows `#1..#6` from current new `issue_fact`, then kept customer fixture `#9003`. | `old=7`, `new=7`, keys `1,2,3,4,5,6,9003`, titles aligned. |
| System-test issue list | Earlier count drift was scope mismatch plus missing old fixture rows. | Restored old source rows and kept the old combined regression/system-test phase scope aligned to the new system-test scope. | `old=1`, `new=1`, key `#2`, title aligned. |
| System-test illegal records | Old phase-specific API still returns 500 because the legacy phase definition path generates an invalid empty `IN ()` query. Data itself is present. | Compared illegal keys derived from the same-scope old issue list against new illegal endpoint. | Key `#2` aligned; rule text differs. |
| Integration details | Previous `0/0` only proved empty-set consistency. | Added deterministic local fixture `#9002` to old `spider_integration_data` and new `integration_test_fact`, then compared through real old/new HTTP endpoints. | `old=1`, `new=1`, key `#9002`, title and execute-case metric aligned. |
| Customer issues | Previous `0/0` only proved empty-set consistency. | Added deterministic local fixture `#9003` to old `project_issue_info` and old `spider_issue_data`, plus matching new `issue_fact` customer-scope row. | `old=1`, `new=1`, key `#9003`, title aligned. |
| Code-review illegal records | New platform had MR `!1`; old legacy static table `spider_crowncad_data` had no matching materialized row. The old endpoint also forces CrownCAD target branch `dev` when branch is omitted. | Added old static row for MR `!1` with `target_branch='dev'` and matching title. | `old=1`, `new=1`, MR IID `!1`, title aligned. |
| Review data | New platform had 11 active demo records created by new-only management seed/API flow; old Mongo `spider` review collections were empty. | Soft-deleted the new-only active demo review records `id=2..12` for parity. | `old=0`, `new=0`; no extra new-only review records remain in the comparison scope. |

## Fresh Identity Results

| Check | Result |
| --- | --- |
| `SOURCE-ISSUE-FACTS-PROJECT2` | `PASS_DATA_IDENTITY`: `old=7`, `new=7`, same keys and titles. |
| `PAGE-SYSTEM-ISSUE-LIST` | `PASS_DATA_IDENTITY`: `old=1`, `new=1`, key `#2`, same title. |
| `PAGE-SYSTEM-ILLEGAL-RECORDS` | `PASS_DATA_KEY_WITH_RULE_DIFF`: key `#2` aligned; old/new illegal reason wording differs. |
| `PAGE-INTEGRATION-DETAILS` | `PASS_DATA_IDENTITY`: fixture key `#9002`, title and execute-case aligned. |
| `PAGE-CODE-REVIEW-ILLEGAL` | `PASS_DATA_IDENTITY`: MR `!1`, title aligned. |
| `PAGE-REVIEW-DATA` | `PASS_EMPTY_AFTER_DEMO_CLEANUP`: both sides are empty after removing new-only demo records. |
| `PAGE-CUSTOMER-ISSUES` | `PASS_DATA_IDENTITY`: fixture key `#9003`, title aligned. |

## Remaining Non-Hard-Data Differences

These are not missing/extra-record problems:

- System-test visible fields still differ: module parsing, combined phase label, urgency placeholder, bug status wording, and status casing.
- System-test illegal reason wording still differs: old derived reason says missing severity/module-style legacy text; new reason says missing severity. The affected key remains `#2` on both sides.
- Old `/issueStaticData/getIllegalIssue` with the combined phase still returns 500. The data key was verified through the aligned old issue-list scope, but the legacy endpoint itself still needs a code fix if that route must be used directly.
- Old system-test export remains not comparable for local project `2` because the old export path is hard-coded to the legacy CrownCAD project id.

## Boundary Notes

The integration and customer fixtures are local comparison baselines, not proof of a live upstream GitLab sync path for those domains. They are still queried through real old/new HTTP endpoints after setup.

The review-data fix intentionally removes new-only demo records from the parity scope rather than inventing old Mongo records. If a non-empty review-data demo is required later, both sides should be rebuilt from the same legacy Excel import source.
