# Module Source Alignment Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use test-driven-development to implement this plan task-by-task.

**Goal:** Restore page-specific module sources and parsing rules without cross-page candidate pollution.

**Architecture:** Keep module semantics at the business-domain boundary: review records and review form catalogs are separate, issue and merge-request label parsers are separate, and compatibility mode is isolated only at source selection. Existing fact tables remain the single query source for record pages, boards, exports, and drill-downs.

**Tech Stack:** Java 21, Spring Boot, JdbcTemplate, Vue 3, TypeScript, Element Plus, Maven, Vitest.

---

### Task 1: Correct the business contract

**Files:**
- Modify: `docs/platform-page-business-rules.md`

1. Replace the global module separator rule with issue-specific and MR-specific rules.
2. Replace the review module suffix-removal rule with raw-value preservation and project 9/79 form candidates.

### Task 2: Lock module parser contracts

**Files:**
- Modify: `backend/src/test/java/com/data/collection/platform/service/IssueFactNormalizationRulesTest.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/IssueLabelRules.java`

1. Change existing issue-module assertions so ASCII colon and hyphen labels are rejected.
2. Change existing MR assertions so pipe/hyphen/full-width-colon labels are accepted and ASCII colon is rejected.
3. Implement two explicit parsers without changing non-module label parsing.

### Task 3: Separate review filter and form module candidates

**Files:**
- Modify: `backend/src/test/java/com/data/collection/platform/service/ReviewDataFilterOptionServiceTest.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/ReviewDataFilterOptionService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/ReviewDataMirrorOptionRepository.java`

1. Make list module options come only from formal records plus Match mode records when compatibility read is enabled.
2. Restrict review form module labels to project IDs 9 and 79 and the exact full-width `模块：` prefix.
3. Preserve the existing project-candidate behavior and all unrelated option lists.

### Task 4: Preserve review module values

**Files:**
- Modify: `backend/src/main/java/com/data/collection/platform/service/ReviewDataModuleNameSupport.java`
- Modify: existing review module support tests if present.

1. Retain trimming/empty normalization.
2. Remove automatic deletion of the trailing `模块` suffix for formal and Match mode paths.

### Task 5: Restore form input behavior

**Files:**
- Modify: `frontend/src/views/review-data/ReviewRecordFormDialog.vue`

1. Enable `allow-create` only for the review module selector.
2. Leave project and other selectors unchanged.

### Task 6: Verification

1. Run the existing focused backend contract test allowed by repository test policy.
2. Run frontend typecheck if Vue code changes.
3. Restart the backend only after successful compilation/verification and inspect the review filter-options response.
