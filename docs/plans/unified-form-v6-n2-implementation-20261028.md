# 统一表单 v6 — N2 原版记录服务与填写页活动计划（2026-10-28）

## 进度与中间物

- [当前阶段] N1 输入`369194aa`通过，R01—R05 全部关闭，N2 准入。按[N2 准入与执行指令](unified-form-system-implementation-plan-20261021.md#n2-admission)实施当前版新建、所属版编辑/作废、跨版重放、多版本 detail 与草稿 v2；管理页 UI、后台壳/样式维持`94749f3a`。N3—N5/W5/main/正式包未准入。
- [已完成] 已快进吸收审核回执`6329590a`；N1 活动计划按规则收口删除，过程可用`git show 369194aa:docs/plans/unified-form-v6-n1-implementation-20261027.md`恢复。
- [进行中] P0 已完成；P1 后端多版记录服务与 DTO 已完成并通过`FormRecordServiceIntegrationTest`29/0/0/0、`FormTemplateAdminIntegrationTest`17/0/0/0（含新增两版编辑/作废、多版 detail、跨版同人提示回归）。P2 填写页多版本与草稿 v2、P3 验证/浏览器验收/送审待做。
- [阻塞] 无。
- [证据] 本计划与`.tmp/w4-n2-20261028/`；N1 证据历史保留。

### 恢复信息

1. **阶段与首条恢复命令**：先读 AGENTS、`docs/progress.md`、主方案与 N0 合同（`v6-n0-technical-admission-20261027.md` §2/§6）及本文件，执行 `git status --short --branch`、`git worktree list`。基线 `6329590a`，分支 `codex/unified-form-w4-isolation-20261026`，worktree `D:/projects/dcp-form-w4-20261026`。前序：N1（已通过，计划已删）。
2. **原始需求与成功标准**：按主方案 N2 行与 N0 §2/§6：当前版新建；编辑/作废按目录`storage_version_id`与`expectedStorageVersionId`定位并按该版冻结规格校验；跨版 key 重放按 origin 证据；detail 返回`currentVersionId/readableVersions/versionSpecs/sourceSetRevision/sourceSetHash`及每条`storageVersionId/originVersionId`；bigint 一律十进制字符串，`recordVersion`为 number；草稿 v1→v2 迁移；真实 PG + 浏览器验收。禁止：改管理页/后台壳/样式；N3—N5/W5；合 main、出包、黄金运行；改 160 条迁移；默认 15432/15433 与共享服务。
3. **业务/API/数据/环境约束**：编辑/作废只接受`AUTHORITATIVE`承载版本；`originVersionId`仅展示/重放；NONE/旧 Demo 缺创建人只读；退役范围只读；作废从业务读取排除；新建/编辑/作废失败全回滚，重放零新增记录/审计/head；未知结果先核验；401 不删草稿。
4. **提交、日志与根因证据**：N1 实现`4985c53c`、审核回执`6329590a`；N2 证据待生成；MySQL 就绪竞态为已知范围外环境问题，独立记录不掩盖。
5. **步骤与依赖**：P1 DTO/读侧/写侧 → P2 前端会话/卡片/草稿 → P3 测试与浏览器验收。前端依赖 P1 契约；P3 依赖 P1/P2。
6. **选定/否决方案**：detail 复用`FormRecordDirectoryReader.captureSourceSet`与按版本冻结规格读取，不新增平行读源；编辑/作废新增`expectedStorageVersionId`并复用目录定位，不保留旧“当前表+current pointer”写路径（内部破坏性调整，删除旧路径与调用者）。否决：为兼容保留 `_V2` 别名或双轨。
7. **API/表结构/关键常量**：DTO 扩展至 N0 §6.1（`FormDetailResponse`增加 currentVersionId/readableVersions/versionSpecs/sourceSetRevision/sourceSetHash；`FormRecordView`增加 storageVersionId；`FormUpdateRequest/FormVoidRequest`增加 expectedStorageVersionId）。无新增迁移（如确需另行申请）。历史测试夹具`FormV6TestSupport`继续复用。
8. **风险、假设与敏感只读数据**：两个版本不同字段的历史记录必须按自身规格展示/编辑；草稿分叉与未知状态不得合并；浏览器验收端口先查归属，仅停本任务资源。无敏感数据。

## 阶段

### P0 计划与现状（任务 #60）
梳理后端 Controller/DTO/服务与前端填写页/草稿现状，确定改动清单与删除路径；本文件即恢复入口。

### P1 后端多版记录服务与 DTO（任务 #61）
- detail：范围→权威版本集合（reader.captureSourceSet）→逐版本冻结规格→按目录读取全部有效记录（含跨版本），每条带 storage/origin；bigint 字符串。
- create：保持六步事务与目录优先重放；旧版 key 仅允许重放，新提交必须=currentVersionId。
- update/void：新增`expectedStorageVersionId`；按目录定位 storage 版本，校验 AUTHORITATIVE、scope/C/创建人/CAS，使用该版规格与表；拒绝旧路径。
- 真实 PG：三模板两版不同字段、原版编辑/作废不搬表、origin≠storage、旧字段保留/新字段不补、同 key 重放、同人跨版提示、并行不同记录、同记录 CAS、栅栏/绑定/权限/回滚、大 ID、NONE/退役只读、指针变化场景。

### P2 填写页与草稿 v2（任务 #62）
- 每条记录按`versionSpecs[storageVersionId]`渲染/编辑；新建用 currentVersionId；旧链接访问原记录原值。
- 编辑/作废提交`expectedStorageVersionId`；unknown/401/分叉/离开保护语义保持。
- 草稿键前缀 v2、新增`originVersionId/baseStorageVersionId/versionSpecHash`；读取兼容 v1，写 v2 成功后删 v1；多标签只克隆最新会话。
- Vitest 覆盖大 ID、v1→v2、旧版 CAS、规格变化只读、401/换账号、未知核验。

### P3 验证与送审（任务 #63）
真实 PG 定向、一次默认后端套件、前端 typecheck/lint/Vitest/build、真实服务浏览器验收（MR、Issue 响应/回复、双用户/多标签、v1→v2 草稿、旧版编辑、401/换账号、未知核验与迟到响应）与截图；护栏；文档回填；双远端提交回读后送 N2 验收。
