# GitLab Sync User Acceptance Gap Fix Plan

> **For Claude:** REQUIRED SUB-SKILL: Use executing-plans to implement this plan task-by-task.

**Goal:** Fix the gaps found during user-perspective GitLab sync acceptance so operators can trust System Hook, sync logs, source health, and progress states.

**Architecture:** Keep the existing mirror/table-job architecture. The frontend should translate backend technical messages before rendering, while the backend should publish trustworthy log metrics and align source health with the same table-job display source used by `/status`.

**Tech Stack:** Spring Boot, MyBatis Plus, Vue 3, Element Plus, Vitest.

---

## Task 1: Chinese User-Facing Sync Presentation

**Files:**
- Modify: `frontend/src/views/mirror-settings-helpers.ts`
- Modify: `frontend/src/views/useMirrorStatusPresentation.ts`
- Modify: `frontend/src/views/useMirrorStatusPresentation.test.ts`

**Acceptance criteria:**
- [x] English backend messages such as `Full table verification completed with status SUCCESS` are rendered in Chinese.
- [x] A successful finished sync does not show a preparing/scanning hint.
- [x] Idle/running/success labels in the mirror settings status card are valid Chinese, not mojibake.

**Verification:**
- [x] `npm.cmd test -- src/views/useMirrorStatusPresentation.test.ts`
- [x] `npm.cmd run typecheck`

## Task 2: Direct Mode System Hook Status Clarity

**Files:**
- Modify: `frontend/src/views/MirrorSettingsView.vue`
- Modify: `frontend/src/views/mirror-settings.mount-smoke.test.ts`
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabWebhookRegistrationService.java`

**Acceptance criteria:**
- [x] Direct mode displays "需在 GitLab 手动注册，平台无法自动检测" instead of "未注册".
- [x] Direct mode System Hook status uses neutral/info styling.
- [x] Docker mode registration behavior remains unchanged.

**Verification:**
- [x] `npm.cmd test -- src/views/mirror-settings.mount-smoke.test.ts src/views/useMirrorWebhookRegistrationController.test.ts`

## Task 3: Trustworthy Recent Sync Record Count

**Files:**
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabMirrorSyncService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabSyncLogService.java`

**Acceptance criteria:**
- [x] Recent sync log `recordCount` equals the sum of `gitlab_table_sync_tasks.rows_applied` for the completed table job.
- [x] A zero value means the job really applied zero rows.
- [x] Existing log reconciliation fills table and record counts when a completed job is matched.

**Verification:**
- [ ] Backend targeted tests where available.
- [ ] Manual UI check: recent sync log record count matches progress synced records after an incremental sync.

## Task 4: Source Health Uses Latest Table Job

**Files:**
- Modify: `backend/src/main/java/com/data/collection/platform/service/GitlabSourceHealthService.java`

**Acceptance criteria:**
- [x] Source health latest sync status/message/time reflects the latest display table job when it is newer than legacy sync logs.
- [x] Hook compensation success appears in source health after the same refresh cycle as main status.
- [x] Existing fact-layer lag checks compare against the aligned latest sync time.

**Verification:**
- [ ] Backend targeted tests where available.
- [ ] Manual UI check after System Hook compensation.

## Task 5: Multi-Source Duplicate Data Guardrail

**Files:**
- Modify: `frontend/src/views/MirrorSettingsView.vue`
- Modify or create backend diagnostics only if needed.

**Acceptance criteria:**
- [ ] If two enabled sources point to the same physical GitLab DB/container, the settings page warns that business pages may show duplicate fact rows.
- [ ] No source is blocked automatically in this task.

**Verification:**
- [ ] Manual UI check with `default` and `cc` pointing to the same source DB.
