# 标签组筛选一期最终评审（2026-06-08）

> **评审范围**：commit `b3ca0969` → `ec597b12`
>
> **对照方案**：[2026-06-08-filter-tag-groups-modification-plan.md](../plans/2026-06-08-filter-tag-groups-modification-plan.md)

---

## 评审结论

**✅ 一期评审通过**

评审数据管理页标签组筛选功能完整落地，N3 老平台标签解析核心完成，N1 通用 adapter 抽取完成。可推进后续数据域接入。

---

## 一期交付内容

### 1. 评审数据管理页标签组筛选（完整功能）

**文件**：
- [ReviewDataManagementView.vue](../../frontend/src/views/ReviewDataManagementView.vue)
- [TagGroupFilterBar.vue](../../frontend/src/components/TagGroupFilterBar.vue)

**功能清单**：
- ✅ 标签组面板默认折叠，头部显示"标签组 / 当前视图：XX / 当前 N 条 / M 个已选"
- ✅ 点击头部展开/收起，无"selectedCount > 0 强制展开"限制
- ✅ 下拉式 UI（多选下拉）替代原 chip 按钮
- ✅ 命名快照保存（2-30 字符）
- ✅ 快照恢复下拉展示视图名
- ✅ 快照删除功能
- ✅ 清空标签弹确认 dialog
- ✅ 自动恢复快照（进入页面无 `tagSelections` query 时）
- ✅ schema mismatch 面板内联 warning（不全局弹 toast）
- ✅ 高级条件构建器（`StatisticFilterBuilder`）收进默认折叠的"高级条件"区
- ✅ `sourceInstance` 在列表/导出/筛选项三条链路透传

**验收状态**：✅ 通过（commit `ec597b12`）

### 2. N3 老平台标签解析核心

**文件**：[IssueLabelRules.java](../../backend/src/main/java/com/data/collection/platform/service/IssueLabelRules.java)

**功能清单**：
- ✅ `parseLegacyLabelMap(labels)` 三步解析：
  1. 中文冒号前缀拆分（8 种：模块/工具箱/软件/项目/状态/测试阶段/严重程度/类别）
  2. 关键字识别（系统测试/回归测试/集成测试 → 测试阶段）
  3. 枚举命中（紧急程度、延期原因）
- ✅ `工具箱：X` 与 `模块：X` 合并到 `Map.get("模块")` 列表，与老平台 `getCombinedLabelValue` 行为一致
- ✅ `LegacyPrefixedLabel` record 保留前缀信息（`source_field`），为未来"工具箱独立"留可逆路径

**验收状态**：✅ 通过

**保留的增强项**（相对老平台）：
- 识别英文 `module:` 前缀（老平台只识别中文）
- 反向排除（NON_MODULE_TOKENS / severity / delay / function / phase 别名）
- 裸标签兜底（`endsWith("模块")` / `BARE_MODULE_LABELS`）

这些增强项在 §10.1 / §10.6 列为"待 PM 决策"，当前代码已保留增强。如需严格复刻老平台，后续可回退。

### 3. 议题域 Flyway seed migration

**文件**：[V20260609_01__tag_groups_issue_legacy_seed.sql](../../backend/src/main/resources/db/migration/V20260609_01__tag_groups_issue_legacy_seed.sql)

**功能清单**：
- ✅ 幂等 upsert 7 个标签组（`module / software / project_label / status / phase / severity / category`）
- ✅ 从 `issue_fact.label_names` 派生已存在标签值，按前缀拆分
- ✅ `工具箱：X` 映射到 `module` 组，`source_field='工具箱'` 保留前缀信息
- ✅ 跳过 `未设定` 占位值

**验收状态**：✅ 通过

**遗留确认项**：
- `urgency / delay_cause` 两组在 SQL CTE `seed_groups` 中未明确看到，需确认是否遗漏或通过其他路径生成。

### 4. N1 通用 adapter

**文件**：[useTagGroupFilterAdapter.ts](../../frontend/src/composables/useTagGroupFilterAdapter.ts)

**功能清单**：
- ✅ 最小公约数设计，不含业务分支
- ✅ 解决自动恢复时序问题（`shouldDeferRowsUntilTagSnapshotRestore`）
- ✅ 封装"加载标签组 + 维护 tagSelections + storageKey 派生 + auto-restore wiring + active-filter-tag 拼接"
- ✅ `fixedFilterKeys` 等业务字段集合通过 options 注入

**验收状态**：✅ 通过

---

## 推迟到后续阶段的内容

### 返工-2：轮次/里程碑双向同步（按需接入版）

**推迟理由**：当前只有评审数据管理页完整实现标签组筛选，议题查询页等其他页面还在推进中。双向同步依赖议题查询页接入完成，暂不具备验收条件。

**何时做**：等 N1 Task A/B/C 完成后，议题查询页完整接入标签组时，再根据实际交互需求决定是否实现。

### 返工-3：老平台对照单测（改为真实数据对比验证）

**推迟理由**：老平台与新平台的筛选交互形态不同（老平台是固定下拉/关键字搜索，新平台是标签组面板），无法做 1:1 功能对照单测。能对照的只有**标签解析和合并规则**，这部分已在 `parseLegacyLabelMap` 单测中覆盖。

**替代方案**：用真实生产数据做一次性对比验证：
- 评审数据管理页选"模块=XX"标签后的查询结果与老平台同口径查询对比
- 对比维度：IID 列表、记录数、关键字段（module_name / severity_level）
- 由 PM 或运维手动执行 SQL 对比，不写自动化单测

**何时做**：在上线前由运维协助完成一次数据对比验证，记录对比结果附在本评审文档。

---

## 待 PM 决策的遗留问题（§10）

从 [2026-06-08-filter-tag-groups-modification-plan.md § 10](../plans/2026-06-08-filter-tag-groups-modification-plan.md) 中提取的未决策项：

| 决策点 | 当前代码状态 | 影响 |
|---|---|---|
| §10.1 英文 `module:` 前缀 | 已保留增强 | 如需严格复刻老平台，需回退 |
| §10.1 反向排除逻辑 | 已保留增强 | 如需严格复刻老平台，需回退 |
| §10.6 裸标签兜底 | 已保留增强 | 如需严格复刻老平台，需回退 |
| §10.2.4 搜索 placeholder | 当前"搜索标签值" | 候选："在标签组中搜索" / "快速查找标签" / "过滤标签" |
| §10.3 评审域标签组命名 | 保留"评审模块"前缀 | 是否改为"模块"与议题域对齐 |
| §10.5 轮次/里程碑默认值来源 | 未实现双向同步 | 当实现时需确认：localStorage / 后端全局默认 / 业务时间窗口推断 |
| §10.5 清空固定字段行为 | 未实现双向同步 | 当实现时需确认：清空标签组对应组 / 保持当前 |

**建议**：不阻塞后续推进。上述决策在二期推进 N1 Task B/C/D 时按实际需要逐条确认。

---

## 下一步

**一期验收通过**，可推进：

1. **N1 Task A** — 客户问题页（`customer_issue` 域）接入标签组
2. **N1 Task B** — 系统测试非法记录页接入
3. **N1 Task C** — 客户问题非法记录页接入
4. **N2 视图持久化** — 用户视图落库（`user_table_view` 表）
5. **N1 Task D** — 代码走查非法记录页接入（最复杂，最后做）

按 [修改方案 §6](../plans/2026-06-08-filter-tag-groups-modification-plan.md) 推荐顺序：下一个 Task 是 **N1 Task A（客户问题页接入）**。

---

## 附录：未纳入一期的原返工任务

| 原返工项 | 一期状态 | 修订后状态 |
|---|---|---|
| 返工-1：折叠 + 头部小标识 | ✅ 已完成 | 纳入一期交付 |
| 返工-2：轮次/里程碑双向同步 | 未开始 | 推迟到后续阶段（等议题查询页接入完成） |
| 返工-3：老平台对照单测 | 未开始 | 改为真实数据对比验证（上线前一次性执行） |
