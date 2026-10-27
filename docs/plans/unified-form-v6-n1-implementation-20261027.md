# 统一表单 v6 — N1 基础实施活动计划（2026-10-27）

## 进度与中间物

- [当前阶段] N1 已实现并进入验收：P1—P4 完成，P5 收口中。N1 未送审。
- [已完成] P1 `V20261027_01__form_v6_directory.sql`（范围集合/版本角色/实例模式/来源表、定位目录、可比登记表、目录事件/游标/水位、`form_spec_hash`/`form_source_set_hash`/`form_create_fingerprint`/`form_advance_record_sequence`、可读集合视图），校验和已登记（159→160，仅新增）；P2 `FormV6RegistrationService`（路径A/B、precheck 一致性强制、守恒断言、幂等重入、漂移/重复归属/证据缺失整体拒绝）与 `FormRecordDirectoryService`（目录写入接入六步创建、跨版重放按 origin 证据复算 v5 指纹）；P3 `FormRecordDirectoryReader`（find/listByResource/captureSourceSet，排除 TARGET/LEGACY_SNAPSHOT，延迟回表）；稳定语义登记 `FormComparableRegistrationService`（声明=冻结类型校验、身份冲突拒绝）。
- [验证] 定向真实 PG 测试 12 项全绿（`FormV6DirectoryMigrationTest` 迁移两路径+序列 is_called 全边界；`FormV6RegistrationIntegrationTest` 路径A/B守恒/幂等/漂移/重复归属/证据缺失/创建写目录/跨版重放 PAYLOAD_MISMATCH/异账号/完整C/作废拒绝；`FormComparableRegistrationIntegrationTest` 类型冲突/未知类型/身份冲突/拒绝覆盖/reader角色与完整C隔离/源集合捕获）。默认后端套件运行中。
- [阻塞] 无。

### 恢复信息

1. **阶段与首条恢复命令**：本计划为 N1 唯一活动文档；恢复先读 AGENTS、`docs/progress.md`、N0 合同（`docs/plans/v6-n0-technical-admission-20261027.md`，五审批准节）与本文件，执行 `git status --short --branch` 与 `git worktree list`。基线 commit `13985b7a01ecc46a4193506106605e2c870968bb`，分支 `codex/unified-form-w4-isolation-20261026`，worktree `D:/projects/dcp-form-w4-20261026`。前序计划：`v6-n0-technical-admission-20261027.md`（N0 已结，历史保留）。
2. **原始需求、成功标准与禁止行为**：按执行单“五审通过后N1实施指令”（`unified-form-system-implementation-plan-20261021.md`）实施多版本定位目录/源集合、稳定元数据与真实结构校验、旧Demo/v5唯一承载原位登记及幂等/回滚、统一内部多版权威读源和必要前向迁移/正式测试。成功标准：定向真实 PG 测试 + 一次最小充分默认后端套件全绿；原行/规格/指纹守恒；迁移 159 条旧锁零差异；frontend 相对 `94749f3a` 零差异。禁止：改 frontend/UI；N3 发布执行器、N4 事实公开/统计/导出、正式权限授予；自动在启动时登记真实旧库或启用旧复制器改已登记源；暴露不完整读写组合；共享/真实业务库（禁 15432/15433 及共享服务）；合 main、出包；改 159 条既有迁移或锁条目。
3. **业务/数据/环境约束**：登记事务前确认旧写停止、选集稳定；原业务行、原 `field_spec`、origin/首次 hash/创建归属/时间不改写；重复/未覆盖/冲突/漂移整体拒绝；稳定 ID/语义声明的一次性登记位置为独立登记表（不原地改写旧规格）。隔离数据库：Testcontainers `postgres:16-alpine`（既有模式）或显式专属库；不预建前端/业务服务。
4. **提交、日志、复现与根因证据**：证据目录 `.tmp/w4-n1-20261027/`；迁移校验和登记 `scripts/flyway-migration-checksums.json`。无缺陷根因记录。
5. **步骤与依赖**：P1 DDL → P2 登记与定位服务 → P3 内部 reader → P4 正式测试 → P5 验证/门禁/送审。P2 依赖 P1；P3 依赖 P1；P4 依赖 P2/P3；P5 收口。
6. **选定/否决方案与待定项**：稳定语义登记选独立表 `form_field_comparable_registrations`（绑定 scope/version/字段 stableId/semanticKey/declaredType/unit，实际类型取该版冻结 `field_spec` 的 `field.type`，冲突拒绝；不改写 `field_spec`）。否决：把可比元数据写回 `field_spec`（违反“不借元数据升级改旧规格”）。待定：无。
7. **API/表结构/关键常量**：新增 DDL 按 N0 合同 §1.2—§1.4（`V20261027_01__form_v6_directory.sql`，仅依赖 `V20261022_01`；实际基线中 `form_template_versions.instance_table` 为 `not null`，迁移先 `drop not null`）。Java 新增：`FormRecordDirectoryReader`（find/listByResource/captureSourceSet）、`FormRecordDirectoryWriter`（§2.1 六步事务中的目录写入与重放判重）、`FormV6RegistrationService`（§3.1 路径 A/B + §3.2 precheck/register）、`FormComparableRegistrationService`（稳定 ID/语义声明登记与实际类型校验）。复用：`FormFieldValueService.fingerprint`（v5 首次指纹）、`FormHashSupport`、`FormVersionStructureVerifier`、`FormRecordStore.bumpHead`。
8. **风险、假设与敏感只读数据**：假设旧 Demo 停写窗口由调用方保证（N1 只在隔离夹具执行登记）。风险：v5 多副本承载判定歧义 → 按 §3.1 规则阻断并输出报告，不自动裁决。无敏感数据。

### 阶段

- **P1 DDL**（任务 #48）：`V20261027_01__form_v6_directory.sql` = 范围集合列 + 版本角色/实例模式/来源表 + 形状约束 + `form_spec_hash`/`form_source_set_hash`/`form_create_fingerprint`/`form_advance_record_sequence` + `form_scope_readable_versions` 视图 + `form_record_locations` + `form_directory_events`/`cursors` + `form_record_id_watermarks`。空平台与非空旧库升级两路径验证（并入 P4 迁移测试）。
- **P2 登记与定位**（任务 #49）：`FormV6RegistrationService`（precheck 只读 rowHash/count/承载报告/field_loss_report；register 单事务：咨询锁 → `lock table ... in share mode` → scope FOR UPDATE → 重算断言 → 路径A/B → 目录插入 `on conflict do nothing` + 逐字段比对 → 守恒断言 → 修订+1 → `LEGACY_REGISTERED` 事件 → 水位）；`FormRecordDirectoryWriter` 接入 `FormRecordWriteService` 创建路径（六步：序列咨询锁 → 范围 FOR SHARE → 目录判重/重放 → 插入 → 目录 → 审计 → head）；重放按目录 origin 版本 + v5 指纹核验。
- **P3 内部 reader**（任务 #50）：`FormRecordDirectoryReader` 只读 AUTHORITATIVE 且 PUBLISHED/RETIRED；排除 LEGACY_SNAPSHOT/TARGET；`listByResource` 走 `idx_frl_resource`；`captureSourceSet` 返回修订/哈希/版本集合。不改 `GitlabFactSourceSqlProvider`（N4 才切换）。
- **P4 正式测试**（任务 #51）：迁移两路径；登记路径 A/B、唯一/重复/缺失归属、原行/规格/hash/目录守恒、首次 hash 与 origin/storage 不同、NULL 实例/未知账号、重入/回滚/单调补偿、`form_advance_record_sequence` is_called 全边界与跨范围竞争、合法与漂移结构、reader 隔离、可比登记类型冲突/稳定身份校验。
- **P5 验证与送审**（任务 #52）：定向 PG 测试 + 默认后端套件一次；迁移锁 159 零差异；frontend 零差异；仓库护栏；阶段提交推双远端回读；验收包。
