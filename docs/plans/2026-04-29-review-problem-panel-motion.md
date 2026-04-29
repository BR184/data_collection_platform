# Review Problem Panel Motion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the review problem list feel like a smooth drawer when it opens from the review data table.

**Architecture:** Keep the current expanded-row state and data loading unchanged. Add a local CSS entrance animation to `ReviewProblemPanel.vue`, so only this panel gains the drawer-like motion and no shared table behavior changes.

**Tech Stack:** Vue 3, scoped CSS, Element Plus table.

---

### Task 1: Add Local Panel Motion

**Files:**
- Modify: `frontend/src/views/review-data/ReviewProblemPanel.vue`
- Test: `frontend/src/views/review-data/ReviewProblemPanel.test.ts`

**Step 1: Add a stable motion class**

Keep the panel root as `class="problem-panel"` and add CSS only. Do not change props, events, or data loading.

**Step 2: Implement drawer-like entrance CSS**

Add an animation that uses `opacity`, `transform`, and a soft shadow/background transition. Include `prefers-reduced-motion` support.

**Step 3: Verify component behavior**

Run:

```powershell
cd frontend
npm run test -- src/views/review-data/ReviewProblemPanel.test.ts
```

Expected: PASS.

**Step 4: Verify broader review page mount**

Run:

```powershell
cd frontend
npm run test -- src/views/review-data-management.mount-smoke.test.ts
```

Expected: PASS.
