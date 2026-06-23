<!-- DOC_STATUS_START -->
> 文档状态：常驻索引
> 说明：汇总所有项目自维护 Markdown 的当前有效性、遗留项和废弃说明。
<!-- DOC_STATUS_END -->

# Markdown 状态索引
更新日期：2026-06-23

## 范围

本索引覆盖仓库自维护 Markdown：根目录文档、`docs/*.md`、`docs/current-state/*.md`、`docs/plans/*.md` 和 `docs/archive/` 中的归档入口及主要历史文档。不覆盖 `node_modules`、`.tmp`、`tools` 等第三方或构建/离线包目录中的 Markdown。

## 状态说明

- **常驻**：仍作为当前规则、契约、入口或 runbook 使用。
- **已完成**：计划或修复已落地，保留作历史追溯。
- **遗留 / 待确认**：仍有业务确认、环境确认、验证项或 backlog。
- **已废弃 / 被取代**：旧方案已被后续方案或实现替代，仅作历史参考。
- **审计记录 / 历史报告 / 进度快照**：不是当前执行计划，按记录用途保留。

## 文档清单

| 文档 | 状态 | 说明 |
| --- | --- | --- |
| `AGENTS.md` | 常驻规则 | 继续作为当前工程规则或实现约束使用。 |
| `README.md` | 常驻入口 | 项目启动、运行和模块说明入口，继续维护。 |
| `docs/archive/README.md` | 归档入口 | 说明归档目录用途和归档文档使用边界。 |
| `docs/archive/completed/bug-fix-report-20260520.md` | 历史报告 / 已归档 | 阶段性报告，保留作追溯，不作为当前待办清单。 |
| `docs/archive/completed/deep-test-report-20260519.md` | 历史报告 / 已归档 | 阶段性报告，保留作追溯，不作为当前待办清单。 |
| `docs/current-state/system-test-statistics-review-import-refresh-alignment-20260622.md` | 当前对齐方案 | 记录议题统计、评审导入和首次进入刷新策略的当前修复方案，供后续实现、联调和验收使用。 |
| `docs/current-state/label-group-default-module-scope-20260622.md` | 当前方案 / 实施中 | 固定标签组按值类型应用、模块字段同维度、系统测试默认模块范围通过用户可维护标签组配置实现的方案；另记录客户问题非 CC_PRODUCT 页面后续需补测试阶段切换。 |
| `docs/current-state/testing-phase-two-level-management-20260622.md` | 当前方案 / 实施中 | 固定议题测试阶段定义的系统设置入口、父子两级维护、父级排序驱动下拉和子级展开匹配规则。 |
| `docs/current-state/testing-phase-display-statistics-whitelist-20260623.md` | 当前口径 / 常驻 | 固定测试阶段维护表作为下拉与统计白名单：表外阶段及其议题在下拉、表格统计、明细中全部屏蔽；表格总数严格按当前选中阶段粒度（父级或具体子级）在 `issue_fact` 命中条数实时重算。 |
| `docs/fact-field-contract.md` | 常驻契约 | 字段、数据或交互契约，继续作为实现和验收依据。 |
| `docs/flyway-migration-rules.md` | 常驻规则 | 继续作为当前工程规则或实现约束使用。 |
| `docs/frontend-record-page-rules.md` | 常驻规则 | 继续作为当前工程规则或实现约束使用。 |
| `docs/platform-page-business-rules.md` | 常驻业务规则 | 数据采集平台所有页面、统计口径、筛选、导出、下钻和规则说明的统一业务规则总表；页面开发必须先读。 |
| `docs/intranet-offline-packaging-standard.md` | 常驻发布标准 | 定义内网离线发布包的固定结构、拓扑、镜像标签与验收清单。 |
| `docs/gitlab-direct-sync-system-hook-runbook.md` | 遗留 Runbook / 待降级 | System Hook 仍有历史运维价值，但实时主路径已倾向增量补偿；后续应降级或归档。 |
| `docs/gitlab-sync-orchestrator-runbook.md` | 常驻 Runbook | 当前运维和排查说明，继续有效。 |
| `docs/archive/completed/legacy-table-display-policy-investigation-20260603.md` | 调查报告 / 已归档 | 旧平台表格展示口径、模块行骨架、过滤规则和下钻差异调查；当前口径以业务规则总表和现行方案为准。 |
| `docs/markdown-status-index.md` | 常驻索引 | 汇总所有项目自维护 Markdown 的当前有效性、遗留项和废弃说明。 |
| `docs/plans/2026-04-24-code-review-phase1-design.md` | 已完成 | 方案或修复已落地，保留为历史追溯。 |
| `docs/plans/2026-04-27-data-scope-reuse.md` | 已完成 | 方案或修复已落地，保留为历史追溯。 |
| `docs/plans/2026-04-27-echarts-board-rollout.md` | 已完成 | 方案或修复已落地，保留为历史追溯。 |
| `docs/plans/2026-04-27-system-test-illegal-records.md` | 已完成 | 方案或修复已落地，保留为历史追溯。 |
| `docs/plans/2026-04-29-review-problem-panel-motion.md` | 已完成 | 方案或修复已落地，保留为历史追溯。 |
| `docs/plans/2026-05-08-gitlab-sync-production-plan.md` | 已废弃 / 被取代 | 旧方案已被后续方案或实现替代，仅作历史参考。 |
| `docs/plans/2026-05-13-gitlab-sync-runtime-gap-resolution-plan.md` | 已完成 / 被后续吸收 | 主体已完成，后续由新架构或新方案继续承接。 |
| `docs/plans/2026-05-13-gitlab-sync-stability-accuracy-fix-plan.md` | 已完成 / 被后续吸收 | 主体已完成，后续由新架构或新方案继续承接。 |
| `docs/plans/2026-05-14-gitlab-sync-orchestrator-rebuild-plan.md` | 已完成 | 方案或修复已落地，保留为历史追溯。 |
| `docs/plans/2026-05-14-gitlab-sync-user-acceptance-gap-fix-plan.md` | 已完成 | 方案或修复已落地，保留为历史追溯。 |
| `docs/plans/2026-05-15-gitlab-sync-orchestrator-task12-smoke-report.md` | 遗留待确认 | 仍有环境、验收或业务口径确认项。 |
| `docs/plans/2026-05-18-sync-module-decoupling-plan.md` | 已完成 | 方案或修复已落地，保留为历史追溯。 |
| `docs/plans/2026-05-18-sync-refresh-dedup-priority-plan.md` | 已完成 / 遗留环境项 | 主体已完成；环境相关问题仍作排查参考。 |
| `docs/plans/2026-05-21-intranet-smoke-sync-ux-issues.md` | 已废弃 / 被取代 | 旧方案已被后续方案或实现替代，仅作历史参考。 |
| `docs/plans/2026-05-21-intranet-smoke-sync-ux-resolution-plan.md` | 已完成 | 方案或修复已落地，保留为历史追溯。 |
| `docs/plans/2026-05-21-issue-event-consistency-without-system-hook-plan.md` | 已完成 / 策略记录 | 策略判断已形成，可作为后续方向参考。 |
| `docs/plans/2026-05-27-code-quality-refactor-audit-plan.md` | 长期遗留 / 分阶段执行 | 仍包含可持续优化项，应作为技术债 backlog 分阶段处理。 |
| `docs/plans/2026-05-27-platform-functional-ux-review.md` | 已完成 | 方案或修复已落地，保留为历史追溯。 |
| `docs/plans/2026-05-28-integration-test-legacy-excel-export-plan.md` | 已完成 | 方案或修复已落地，保留为历史追溯。 |
| `docs/plans/2026-05-28-review-data-legacy-excel-import-plan.md` | 已完成 / 遗留业务确认 | 主体已完成；末尾待确认问题属于业务口径确认。 |
| `docs/plans/2026-05-29-import-and-new-feature-code-review.md` | 审计记录 / 大部分已修复 | Code review 记录保留；Consider 类建议按需进入后续 backlog。 |
| `docs/plans/2026-05-29-project-wide-code-review.md` | 审计记录 / 遗留验证项 | 项目级 code review 记录保留；7.1/7.2/7.4 等项可作为后续验证 backlog。 |
| `docs/plans/2026-06-01-project-wide-user-journey-test-report.md` | 发布级用户旅程测试报告 / 已转修复方案 | 本地浏览器发布级用户路径测试报告；问题已转入 `docs/plans/2026-06-01-user-journey-test-fixes-plan.md` 跟踪修复。 |
| `docs/plans/2026-06-01-user-journey-test-fixes-plan.md` | 待实施修复方案 | 针对用户旅程测试报告的代码级修复方案：健康检查 endpoint、同源文案对齐、游客态质量看板请求策略、窄屏登录、镜像设置离页草稿保护、全量同步二次确认（待复测）。仅方案未改代码。 |
| `docs/plans/2026-06-01-sync-source-duplication-log-scroll-compensation-investigation.md` | 已实施并验证 | 同源保护、同步日志滚动条、补偿 planning/缓存优化已落地并通过相关测试。 |
| `docs/plans/2026-06-10-label-group-value-set-design.md` | 常驻设计入口 / 2026-06-23 修订 | 标签组能力唯一设计入口。2026-06-23 修订：适用范围默认全开、组级 applicableScope（SAME_TYPE/SAME_FIELD）、成员允许手动输入、新增 partialContainsAny 关系、标签组入口前移到“关系”控件并以颜色区分去前缀。 |
| `docs/platform-auth-security.md` | 常驻规则 | 继续作为当前工程规则或实现约束使用。 |
| `docs/archive/completed/local-gitlab-old-new-comparison-20260602.md` | 历史对比 / 已归档 | 老新平台阶段性对比记录，保留作追溯。 |
| `docs/archive/completed/old-new-visible-diffs-and-full-chain-comparison-20260603.md` | 历史对比 / 已归档 | 老新平台可见差异和全链路对比记录，保留作追溯。 |
| `docs/archive/completed/platform-smoke-test-matrix-20260520.md` | 历史报告 / 已归档 | 阶段性冒烟矩阵，保留作追溯，不作为当前待办清单。 |
| `docs/archive/completed/real-chain-old-platform-comparison-ledger-20260602.md` | 历史对比 / 已归档 | 真实链路老平台对比台账，保留作追溯。 |
| `docs/archive/completed/system-test-issue-and-integration-gap-investigation-20260603.md` | 调查报告 / 已归档 | 系统测试议题和集成差异调查，当前修复方案已由当前状态文档承接。 |
| `docs/project-progress.md` | 进度快照 | 项目阶段性进展记录，保留作历史，不等同于当前任务列表。 |
| `docs/runtime-artifacts.md` | 常驻 Runbook | 当前运维和排查说明，继续有效。 |
| `数据采集平台议题统计规则.md` | 已归档 / 被取代 | 旧议题统计口径入口，已由 `docs/platform-page-business-rules.md` 统一整合；不再作为实现依据。 |

## 归档审计记录

以下审计记录已移动到 `docs/archive/completed/audits/`，仅作历史追溯，不作为当前修复入口：

- `code-review-illegal-records-gap-audit.md`
- `customer-issue-defect-summary-gap-audit.md`
- `customer-issue-followup-pages-gap-audit.md`
- `customer-issue-illegal-records-gap-audit.md`
- `deprecated-function-remnant-audit.md`
- `quality-board-gap-audit.md`
