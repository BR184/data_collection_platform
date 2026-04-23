# Data Collection Platform Progress

## Current Architecture Work

- Added a project-wide decoupling review and migration blueprint
- Confirmed the next architecture sequence:
  - build shared fact-read layer first
  - split oversized services second
  - template repeated statistic-board patterns third
  - unify frontend page controllers and API domains last
- Selected `issue_fact` shared read abstraction as the first implementation slice
- Completed review-data service split as the second architecture slice:
  - extracted query / command / filter-option / summary services
  - kept the original facade and controller contract stable
- Started the statistic-board decoupling foundation for phase three:
  - expanded `IssueFactRecord` / repository to cover board-specific issue_fact semantics
  - added shared runtime support for issue_fact statistic boards
  - added reusable summary-board support models for later service migration
- Continued phase-three migration on defect-summary boards:
  - switched system-test / customer-issue defect summary boards to shared realtime refresh support
  - switched both boards to read issue facts through `IssueFactBoardRuntimeSupport`
  - kept existing board definitions, detail schema, and aggregation behavior unchanged for safety
  - removed direct service dependencies on mirror sync / realtime workspace / fact rebuild / raw issue_fact query services
  - removed obsolete local JDBC fact SQL and mapper code from both defect-summary services
- Kept the existing direction that is already correct:
  - fact-first architecture
  - scope profiles
  - reusable frontend base components

## Completed

- Initialized Spring Boot 3 + Java 21 backend
- Initialized Vue 3 + Element Plus frontend
- Added local Java 21 and Maven toolchain under `tools`
- Added local PostgreSQL 14 development database
- Configured backend datasource to local platform database
- Added common backend dependencies:
  - Lombok
  - Hutool
  - MyBatis-Plus
  - Testcontainers
  - logstash-logback-encoder
- Added unified backend error handling and frontend error display
- Implemented GitLab mirror first version:
  - full sync by DB
  - incremental sync by webhook trigger
  - compensation scheduler
  - whitelist modes
- Added Docker mode for GitLab source database reads using `gitlab-psql`
- Added home dashboard page with a dedicated entry to mirror settings
- Added visual sync progress on both dashboard and settings page
- Changed frontend polling to run only while sync status is `RUNNING`
- Added backend controller tests for status/progress payload
- Added real local GitLab integration test covering:
  - full sync
  - webhook incremental sync
  - compensation sync
- Added higher-load GitLab stress integration test covering:
  - bulk full sync
  - bulk webhook incremental sync
  - bulk compensation sync
- Fixed compensation sync strategy to perform true full-scan compensation
- Fixed incremental timestamp conversion for local GitLab source timezone handling
- Optimized mirror write performance with batch upsert instead of per-row upsert
- Upgraded mirror dashboard and settings page:
  - cleaner progress card
  - richer progress metadata
  - improved sync log readability
  - readable whitelist labels and status texts
- Fixed `ALL` whitelist mode:
  - dynamically discovers real GitLab tables from `information_schema`
  - no longer falls back to the 21 recommended tables when `ALL` is selected
- Fixed incremental sync failure in Docker mode:
  - source table metadata is now discovered dynamically
  - avoids querying non-existent `updated_at` columns such as on `user_details`
  - Docker SQL errors are now surfaced as backend business errors instead of JSON parse failures
- Verified against local GitLab:
  - `ALL` mode now scans `725` source tables
  - compensation sync completed successfully with `725` tables / `3231` mirrored rows processed
  - manual incremental sync completed successfully with `725` tables / `1638` mirrored rows processed
  - current local mirror storage contains `67` tables with `3483` records

## Current Limitations

- Mirror storage is still generic JSONB, not strong typed per GitLab table
- Incremental sync still uses table-level strategy rather than fine-grained event routing
- Docker-mode source reads still dominate end-to-end stress test wall time when creating test fixtures inside GitLab

## Next Recommended Steps

1. Run a real full-sync verification flow from the settings page
2. Add webhook event-level routing improvements
3. Decide whether mirrored data should stay generic or move toward typed local tables
4. Add first user-facing sync cancellation support if needed
