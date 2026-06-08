# 标签组筛选进度评审（2026-06-08）

> **评审基线**：commit `b3ca0969` → `ede18a27`（5 个新提交）
>
> **对照方案**：[2026-06-08-filter-tag-groups-modification-plan.md](../plans/2026-06-08-filter-tag-groups-modification-plan.md)

---

## 评审结论

**整体状态**：部分推进，完成 N3 核心（老平台标签解析）+ N1 adapter 抽取 + 新 TagGroupFilterBar 组件，但**有 3 处明确偏离 PM 要求**需要返工。

**通过项**：
- N3 老平台标签解析（`parseLegacyLabelMap`）✅
- 议题域 Flyway seed migration ✅
- N1 通用 adapter（`useTagGroupFilterAdapter`）✅
- 新组件 `TagGroupFilterBar` 替换 `TagGroupFilter` ✅

**不通过项（必须返工）**：
1. §10.2.1 收起规则未按 PM 要求改（仍强制展开）❌
2. §10.2.2 头部小标识未实现❌
3. §10.5 轮次/里程碑双向同步未实现❌

**待 PM 决策后再推进**：
- §10.1 细节复查（英文 module / 反向排除 / 裸标签兜底）— 代码已实现增强版，但 PM 未决策保留/回退
- §10.3 评审域标签组命名（"评审模块" vs "模块"）— 代码保留"评审模块"，但 PM 未确认

---

## 详细评审

### 已完成 ✅

#### 1. N3 — 老平台标签解析核心（`IssueLabelRules.parseLegacyLabelMap`）

**文件**：[IssueLabelRules.java](../../backend/src/main/java/com/data/collection/platform/service/IssueLabelRules.java)

**改动**：
- 新增 `parseLegacyLabelMap(labels) -> Map<String prefix, List<String> values>`，对齐老平台 `parseLabelMapByList` 的三步解析：
  1. 中文冒号前缀拆分（8 种：模块/工具箱/软件/项目/状态/测试阶段/严重程度/类别）
  2. 关键字识别（系统测试/回归测试/集成测试 → 测试阶段）
  3. 枚举命中（紧急程度、延期原因）
- `工具箱：X` 与 `模块：X` 合并到 `Map.get("模块")` 列表，与老平台 `getCombinedLabelValue` 行为一致。
- 内部使用 `LegacyPrefixedLabel` record 保留前缀信息（`source_field`），为未来"工具箱独立"留可逆路径。

**验收符合度**：
- ✅ 三步解析路径完整
- ✅ 工具箱合并到模块
- ⚠️ 单测未明确对齐老平台预期输出（方案 §10.1 要求"逐条引用老平台预期输出"），需要补老平台对照单测

**遗留问题**（待 PM 决策）：
- 代码保留了"英文 module: 前缀"、"反向排除（NON_MODULE_TOKENS / severity / delay / function / phase）"、"裸标签兜底（endsWith('模块') / BARE_MODULE_LABELS）"三处新平台增强。这些**老平台没有**，但 §10.1 / §10.6 要求 PM 决策保留/回退，当前代码未收到决策就保留了增强。

#### 2. N3 — 议题域 Flyway seed migration

**文件**：[V20260609_01__tag_groups_issue_legacy_seed.sql](../../backend/src/main/resources/db/migration/V20260609_01__tag_groups_issue_legacy_seed.sql)

**改动**：
- 幂等 upsert 7 个标签组（`module / software / project_label / status / phase / severity / category`）+ 2 个枚举组（`urgency / delay_cause` — 看 SQL 未包含，需确认是否遗漏）。
- 从 `issue_fact.label_names` 派生已存在标签值，按 `模块：X` / `工具箱：X` 等前缀拆分，`工具箱` 映射到 `module` 组（`source_field='工具箱'`）。
- 跳过 `未设定` 占位值。

**验收符合度**：
- ✅ 幂等 upsert
- ✅ 前缀保留在 `source_field`
- ⚠️ `urgency / delay_cause` 两组在 SQL 里未看到（CTE `seed_groups` 只到 `category`），需确认是否遗漏
- ⚠️ 方案 §5.6 要求"用真实生产数据回归对比：选中 '模块=草图' 标签后查询结果与老平台一致"，未见对比结果或截图

#### 3. N1 — 通用 adapter 抽取

**文件**：[useTagGroupFilterAdapter.ts](../../frontend/src/composables/useTagGroupFilterAdapter.ts)

**改动**：
- 封装"加载标签组 + 维护 tagSelections + storageKey 派生 + auto-restore wiring + active-filter-tag 拼接"。
- `shouldDeferRowsUntilTagSnapshotRestore` 方法解决 §2.3 自动恢复时序问题（延迟首次 loadRows 直到快照恢复完成）。
- `fixedFilterKeys` 等业务字段集合通过 options 注入，adapter 内部不包含业务分支。

**验收符合度**：
- ✅ 最小公约数设计，不含业务分支
- ✅ 解决了自动恢复时序（§2.3）
- ⚠️ 方案 §10.5 要求的"轮次/里程碑双向同步"逻辑**未实现**——adapter 没有"维度互通映射"，标签组与固定字段下拉互不感知（见"不通过项"§3）

#### 4. 新组件 `TagGroupFilterBar` 替换旧 `TagGroupFilter`

**文件**：[TagGroupFilterBar.vue](../../frontend/src/components/TagGroupFilterBar.vue)

**改动**：
- 用 `<el-select>` / `<el-select multiple>` 下拉替换原 chip 按钮式 UI。
- 快照保存要求命名（`snapshotName` 2-30 字符），配合 §10.4 PM 意见"小表单 dialog 输入视图名"。
- 快照恢复下拉展示快照名（`snapshot.name || savedAt`），而非仅时间戳。
- 清空标签时弹确认 dialog（"只清空标签组筛选，不会清空关键词..."）。
- 快照删除功能（`deleteTagGroupSnapshot`）。

**验收符合度**：
- ✅ 替换为下拉式 UI
- ✅ 命名快照保存
- ✅ 快照恢复下拉展示视图名
- ✅ 清空确认 dialog
- ✅ 快照删除

---

### 不通过项（必须返工）❌

#### 1. §10.2.1 — 收起规则未按 PM 要求改（仍强制展开）

**PM 要求**（§10.2.1）：
> 删除 `selectedCount > 0 强制展开` 的拦截。默认 `expanded = false`，用户可以**任意时刻收起**。

**当前代码**：[TagGroupFilterBar.vue](../../frontend/src/components/TagGroupFilterBar.vue) 没有 `expanded` / `collapsed` 状态——组件本身是一个扁平的"筛选条组合"，**不可折叠**。整个组件始终展开。

**事实**：AI 员工把 `TagGroupFilter` 组件（可折叠面板）替换成了 `TagGroupFilterBar`（下拉条组合），不是"改折叠规则"而是"改组件形态"。这与 §10.2.1 要求的"默认折叠 + 任意收起 + 头部小标识"方向不一致——PM 要的是**可折叠的面板 + 头部简短标识**，而不是把整个面板换成下拉条。

**返工要求**：
- 恢复 `TagGroupFilter.vue` 的折叠面板形态（或让 `TagGroupFilterBar` 支持折叠）。
- 删除 `selectedCount > 0 强制展开` 的逻辑。
- 默认 `expanded = false`（除非 PM 在 §10 确认时改了这条）。

#### 2. §10.2.2 — 头部小标识未实现

**PM 要求**（§10.2.2）：
> 折叠状态下，标签组面板头部应当能让用户瞄一眼就知道"我现在用的是什么视图、筛了几个标签、当前看到多少条数据"。形态类似一个简短的标识区：
> ```
> [标签组]  当前视图：用户1的常用筛选   当前 1234 条   1 个已选   [展开]
> ```

**当前代码**：`TagGroupFilterBar.vue` 没有头部标识区域——组件顶部直接是第一个下拉。

**返工要求**：
- 在组件顶部加标识区域，内容包含：
  - 当前视图名（N2 之前用 `savedAt` placeholder）
  - 当前结果数（prop 传入）
  - 已选条件数（`selectedCount`）
  - 展开/收起按钮

#### 3. §10.5 — 轮次/里程碑双向同步未实现（修订版：按需接入）

**PM 修订**（2026-06-08 17:45）：双向同步**不是所有页面都要做**，只在有轮次/里程碑切换器的页面才需要。

**适用页面**：
- ✅ 议题查询页 [SystemTestIssueSearchView.vue](../../frontend/src/views/SystemTestIssueSearchView.vue) — 有"里程碑"下拉 + "测试阶段"下拉
- ❌ 评审数据管理页 — 无切换器，标签组与固定字段各自独立
- ❌ 其他业务页 — 按实际情况逐页确认

**当前代码**：[useTagGroupFilterAdapter.ts](../../frontend/src/composables/useTagGroupFilterAdapter.ts) 没有"维度互通映射"逻辑。标签组与固定字段各自写 URL query，互不感知。

**事实验证**：打开 [SystemTestIssueSearchView.vue](../../frontend/src/views/SystemTestIssueSearchView.vue)（已接入 adapter，commit `f2b3ee2f`）看代码：
- `primaryFilters` 的"里程碑"下拉写 `route.query.milestoneTitle`。
- `tagSelections` 写 `route.query.tagSelections`。
- 两者没有互通逻辑：用户在里程碑下拉选 "M3"，`tagSelections` 不会自动加 `{groupKey: 'milestone', valueKeys: ['M3']}`。

**返工要求**：
- 在 `useTagGroupFilterAdapter` 增加**可选**参数 `dimensionMappings?: Record<string, string>`（如 `{milestone: 'milestoneTitle', phase: 'testingPhase'}`）。
- 只有传了 `dimensionMappings` 的页面才启用双向同步；没传的页面标签组与固定字段各自独立。
- adapter 内部拦截固定字段写 query 时，同步更新 `tagSelections`；拦截 `tagSelections` 写 query 时，同步更新对应固定字段 query。
- 进入页面时若 URL 有里程碑但没有 `tagSelections`，自动生成对应 `tagSelections`；反之亦然。
- **验收范围缩小**：只测议题查询页的里程碑/测试阶段双向同步；其他页面不要求。

---

### 待 PM 决策后再推进 ⚠️

#### 1. §10.1 / §10.6 — N3 细节复查（英文 module / 反向排除 / 裸标签兜底）

当前代码保留了三处新平台增强（老平台没有），但 §10.1 / §10.6 明确列为"待 PM 决策"：

| 代码位置 | 增强内容 | 老平台行为 | 决策问题 |
|---|---|---|---|
| `MODULE_LABEL_PATTERN` | 识别英文 `module:` 前缀 | 只识别中文 `模块：` / `工具箱：` | 保留增强 / 回退？ |
| `normalizeModuleNames` | `NON_MODULE_TOKENS` 反向排除 | 无反向排除，未命中直接丢弃 | 保留 / 回退？ |
| `normalizeModuleNames` | 反向排除 severity / delay / function / phase 别名 | 无反向排除 | 保留 / 回退？ |
| `extractModuleName` | `endsWith("模块")` 兜底 | 无此规则 | 保留 / 回退？ |
| `BARE_MODULE_LABELS` / `normalizeBareModuleName` | 裸标签映射 | 不识别裸标签作为模块 | 保留 / 回退？ |

**当前状态**：AI 员工在没收到决策的情况下**全部保留了增强**。

**下一步**：PM 对 §10 逐条确认时需要明确这 5 条的取舍。如果要严格复刻老平台，全部回退；如果要保留增强，需要在方案里标注"新平台增强项"并给出理由。

#### 2. §10.3 — 评审域标签组命名

当前 [V20260608_01__tag_groups_review_data_seed.sql](../../backend/src/main/resources/db/migration/V20260608_01__tag_groups_review_data_seed.sql) 保留了"评审模块 / 评审类型 / 评审负责人"等带业务前缀的命名。

**PM 意见**（§10.3）：标签分类要**写得宽泛一点**，参考老平台 `LabelName` 的简洁命名（模块 / 状态 / 测试阶段）。

**当前代码**：评审域 seed 未改。

**下一步**：PM 确认是否要重命名为"模块"（与议题域共用 `groupKey`）还是保留"评审模块"（评审域专属）。

---

## 具体返工任务卡

### Task 返工-1：恢复折叠面板 + 头部小标识

**目标**：让标签组面板符合 §10.2.1 / §10.2.2 的形态要求。

**改动**：
1. **选项 A（推荐）**：让 `TagGroupFilterBar` 支持折叠——组件顶部加头部标识区（"当前视图：XX / 当前 N 条 / M 个已选 / [展开]"），点击展开后显示下拉条组合。
2. **选项 B**：回退到 `TagGroupFilter.vue`（chip 按钮式），删除 `selectedCount > 0` 强制展开逻辑，加头部小标识。

**选 A 的理由**：下拉式 UI 更节省空间（PM 原话"对现在的条件查询 UI 摆放方式不满意"），且已有命名快照 + 清空确认等增强。

**改法（按选项 A）**：
- `TagGroupFilterBar.vue` 顶部加：
  ```vue
  <div class="tag-group-filter-bar-header" @click="expanded = !expanded">
    <span>标签组</span>
    <span class="tag-group-filter-bar-summary">
      当前视图：{{ currentViewName }}
      <el-divider direction="vertical" />
      当前 {{ currentTotal }} 条
      <el-divider direction="vertical" />
      {{ selectedCount }} 个已选
    </span>
    <el-icon><ArrowDown v-if="!expanded" /><ArrowUp v-else /></el-icon>
  </div>
  <el-collapse-transition>
    <div v-show="expanded" class="tag-group-filter-bar-body">
      <!-- 原有下拉条组合 -->
    </div>
  </el-collapse-transition>
  ```
- 新增 props：`currentTotal: number`、`currentViewName?: string`（N2 之前传 `savedAt` placeholder）。
- 删除任何"selectedCount > 0 不可收起"的逻辑。
- 默认 `expanded = ref(false)`。

**验收**：
- 进入页面面板默认折叠，头部显示"当前视图：上次保存 2026-06-07 14:30 / 当前 0 条 / 0 个已选"。
- 选了 2 个标签后仍可手动收起，头部更新为"2 个已选"。
- vitest 覆盖"折叠 -> 展开 -> 选标签 -> 仍可收起"。

### Task 返工-2：轮次/里程碑双向同步（按需接入版）

**目标**：在**有轮次/里程碑切换器的页面**让标签组与固定字段互通，其他页面不做。

**适用页面**：
- 议题查询页 [SystemTestIssueSearchView.vue](../../frontend/src/views/SystemTestIssueSearchView.vue) — 有"里程碑"下拉 + "测试阶段"下拉

**改动**：
1. `useTagGroupFilterAdapter` 新增**可选** options 参数：
   ```ts
   dimensionMappings?: Record<string, string>  // { milestone: 'milestoneTitle', phase: 'testingPhase' }
   ```
2. adapter 内部仅在 `dimensionMappings` 存在时启用双向同步。提供两个方法：
   - `syncFixedFilterToTagGroup(queryKey, value)` — 固定字段下拉切换时调用，自动更新 `tagSelections`
   - `syncTagGroupToFixedFilter(groupKey, valueKeys)` — 标签组切换时调用，自动更新对应 query
3. 进入页面时若 URL 有 `milestoneTitle=M3` 但 `tagSelections` 未包含 milestone，自动补；反之亦然。
4. 议题查询页在 `primaryFilters` / `advancedFilters` 的 `onChange` 回调里调用 `syncFixedFilterToTagGroup`；在 `handleTagSelectionsChange` 里调用 `syncTagGroupToFixedFilter`。
5. 议题查询页传 `dimensionMappings: { milestone: 'milestoneTitle', phase: 'testingPhase' }`，只映射这两个有下拉的维度。
6. 评审数据管理页等其他页面**不传** `dimensionMappings`，标签组与固定字段各自独立。

**验收**：
- 议题查询页进入时 URL `?milestoneTitle=M3`，标签组"里程碑"组自动高亮 M3。
- 固定字段下拉切"里程碑：M4"，标签组立即更新为 M4。
- 标签组选"测试阶段：系统测试"，固定字段"测试阶段"下拉立即切到系统测试。
- 评审数据管理页标签组与固定字段**不互通**（各自独立写 query）。
- vitest 覆盖议题查询页的"固定字段 -> 标签组"、"标签组 -> 固定字段"、"URL 直接修改 -> 双方同步"三条路径。

### Task 返工-3：补 N3 老平台对照单测

**目标**：让 N3 的单测明确对齐老平台预期输出，而不是"输出非空即可"。

**改动**：
- 在 `IssueLabelRulesTest.java`（或新建 `LegacyLabelParityTest.java`）里，每条解析路径都引用老平台 `parseLabelMapByList` 的预期：
  ```java
  // 老平台输入：["模块：草图", "工具箱：工具", "严重程度：致命", "紧急"]
  // 老平台输出：{"模块": ["草图", "工具"], "严重程度": ["致命"], "紧急程度": ["紧急"]}
  @Test
  void parseLegacyLabelMap_alignsWithOldPlatform() {
    Map<String, List<String>> result = IssueLabelRules.parseLegacyLabelMap(
      List.of("模块：草图", "工具箱：工具", "严重程度：致命", "紧急")
    );
    assertThat(result.get("模块")).containsExactly("草图", "工具");
    assertThat(result.get("严重程度")).containsExactly("致命");
    assertThat(result.get("紧急程度")).containsExactly("紧急");
  }
  ```
- 至少覆盖 8 类前缀 + 关键字识别 + 枚举命中 + 未识别丢弃 四类路径。

**验收**：
- `mvn test` 全绿。
- 每条断言都明确写明"老平台预期"。

---

## 总结

**可继续推进**（已通过评审）：
- N3 老平台标签解析核心 ✅
- 议题域 Flyway seed ✅（需补 urgency/delay_cause）
- N1 通用 adapter ✅
- 新组件 TagGroupFilterBar ✅

**必须返工**（阻塞后续 Task）：
- 返工-1：恢复折叠面板 + 头部小标识（§10.2.1 / §10.2.2）
- 返工-2：轮次/里程碑双向同步（§10.5）
- 返工-3：补 N3 老平台对照单测（§10.1）

**待 PM 决策后再继续**：
- §10.1 / §10.6 五条细节复查
- §10.3 评审域命名
- §10.2.4 搜索 placeholder 文案

返工完成后再推进 N1 的 Task B / Task C / Task D（其他数据域接入）。
