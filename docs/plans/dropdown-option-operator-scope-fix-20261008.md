# 下拉框设置：关系选项分档重命名与保存期拒绝（工作单元计划）

> **文档状态**：**已实施完成并验证**（2026-10-08，用户指派后由 AI 实施）；代码已提交为 `3b01a7e6`，未跑发布门禁。
> **上游证据**：`docs/plans/dropdown-option-config-20260903.md` 的「2026-09-04 试用反馈调查」第 3 条。
> 本文取代 2026-10-08 早先同文件立稿（旧框架把五个标签组关系误判为"名实不符"，已被用户纠正并撤回，见「决策记录」）。

## 进度与中间物

- 状态：已实施完成并验证，代码已提交为 `3b01a7e6`；是否在打包/部署时跑发布门禁由用户决定。
- 变更清单（全部完成）：
  - `frontend/src/components/StatisticFilterBuilder.vue`：新增 `labelGroupOperatorOptions` / `operatorLabels` / `hideLogicSelectorWhenSingleCondition` 三个可选参数（默认值等于改动前）；关系清单、关系文案、汇总与宽度计算统一走覆盖；逻辑选择器按开关在汇总条与编辑区一并隐藏。
  - `frontend/src/views/DropdownOptionSettingsView.vue`：`OPTION_FIELDS.operators` 收窄为 `['eq','ne','contains','notContains']`；两处筛选组件传入分档配置；规则区新增"关系说明"静态提示。
  - `backend/src/main/java/com/data/collection/platform/service/dropdown/DropdownOptionRuleSupport.java`：标签组条件保存期拒绝 `containsAll` / `notContainsAll`，并给出可读原因。
  - 测试：`frontend/src/components/StatisticFilterBuilder.test.ts`（新建，3 例）、`frontend/src/views/DropdownOptionSettingsView.test.ts`（新增 1 例，组件桩改为可断言 props）、`backend/src/test/java/com/data/collection/platform/service/dropdown/DropdownOptionRuleSupportTest.java`（新增 2 例）。
- 验证状态：
  - 后端 `DropdownOptionRuleSupportTest` 13/0/0；`DropdownOptionRuleSupportTest,DropdownOptionFilterServiceTest,DropdownOptionFieldServiceTest` 合计 33/0/0；全量默认快速套件 **1673 项通过、0 失败 0 错误**（1 跳过，= 既有 1671 基线 + 本单元新增 2 项）。
  - 前端全量套件 154 文件 / 729 用例全绿；`npm run typecheck` 干净。
  - 仓库守卫（`check_worktree_artifacts`、`check_runtime_artifact_locations`、`check_text_whitespace`、`check_backend_test_hygiene`、`check_frontend_api_boundary`）与 `git diff --check` 通过。`check_verification_ledger.py` 因缺 `docs/real-chain-old-platform-comparison-ledger-20260602.md` 报错，与本单元无关。
  - 浏览器复验（开发实例 18181，LDAP 登录）：下拉设置页关系下拉恰为 7 项、三个标签组关系仍为绿色；单条件时"满足全部 / 满足任意"在汇总条与编辑区都不出现；黑名单规则"选项值 属于该组 测试"预览由 25 个候选值变为 23 个（与既有 `intersects` 语义一致）；代码走查非法记录页关系下拉仍是原五个集合关系，未受影响。
- 未做：发布门禁的黄金基线比对（按纪律留到打包/部署时机，预期零差异）；保存期拒绝的端到端复验（需重启开发后端，其行为已由单元测试精确覆盖）。
- 当前进行点：无。代码已提交（`3b01a7e6`），发布门禁待打包/部署时机。

## 恢复线索

- 当前阶段：已实施、验证并提交（`3b01a7e6`）。
- 恢复后建议执行的首条命令：`cd frontend && npx vitest run src/components/StatisticFilterBuilder.test.ts src/views/DropdownOptionSettingsView.test.ts`（复验本单元前端行为）。
- 上一份计划：`docs/plans/dropdown-option-config-20260903.md`（功能本体已完成；本单元是它试用反馈的第 3 条与 2026-10-08 关系选项重设计）。

## 目标与边界

- 用户原始需求（2026-10-08，两条合并）：
  1. 2026-09-04 试用反馈第 3 条：黑名单选了「包含全部」保存成功但不生效；
  2. 关系选项 11 个平铺太臃肿，且「包含 / 不包含」本质上是对单个选项值做文字片段判断（填 2026 可命中 CC2026R1/R2/R3/R4），没有标识容易误解；要求在不影响用户使用的前提下，让关系选项名称与实际功能一致、一眼能看懂。
- 可验证的成功标准：
  1. 下拉框设置页的关系下拉只剩 7 项：文字档「等于 / 不等于 / 包含 / 不包含」+ 标签组档（绿色）「属于该组 / 不属于该组 / 包含组内任一成员」；
  2. 「为空 / 不为空」「包含全部 / 不包含全部」在该页不再出现；后端保存期对标签组条件拒绝「包含全部 / 不包含全部」并给出可读原因；
  3. 页面规则区有一行静态说明，指明「包含 / 不包含」是对单个选项值做文字片段判断；
  4. 单条件规则不再显示「满足全部 / 满足任意」；
  5. 看板与其它使用同一筛选组件的页面（共 7 处调用）行为与外观零变化；
  6. 定向测试、全量快速套件、前端类型检查与守卫脚本全绿；浏览器按下拉设置页的确切操作复验通过。
- 明确不做：
  - 不改共享关系词表 `operatorLabel()` 与共享语义 `LabelGroupFilterOperatorSupport`（看板仍需要全部五个集合关系与既有文案）；
  - 不改判定引擎 `DropdownOptionFilterService` 的求值逻辑；
  - 不改 `SmartSelect` 组件（其选项只渲染一行文字，无说明行能力；提示改用页面级静态文案）；
  - 不把交互改成"先选值形态再选关系"的两步式（现有"选绿色关系词自动变成标签组条件"的交互已成立，保留）；
  - 不做存量配置迁移或归一化（用户说明：该功能已部署但尚未启用，现场为默认空配置）；
  - 不做条件值联想与按值勾选（原反馈第 1 条，另立工作单元）；
  - 不修改黄金快照（预期零差异；若实际出现差异，按门禁纪律停下并向用户展示）。

## 约束与背景

- 判定语义（代码取证）：`DropdownOptionRuleSupport.OPTION_FIELD_KEY = "optionValue"`，判定时每个候选值作为单个文字参与求值。因此「包含全部」（要求一个值与组内每个成员都相同）在成员不少于 2 个时恒不成立，其否定形式恒成立；「为空 / 不为空」因候选值在匹配前已被过滤空白，结果恒为常量。
- 现有交互（代码取证）：`StatisticFilterBuilder.vue:269-277` 的关系清单 = 字段字面关系 + 标签组关系平铺；`:352-359` 选中标签组关系时自动把条件值形态切为 LABEL_GROUP，选中字面关系时切回文字。即"值形态跟随关系词"，本方案沿用。
- 该功能已随 `20260930T044004Z` 包部署到 20001；用户 2026-10-08 说明尚未启用，现场无已存配置。
- 共享筛选组件 `StatisticFilterBuilder.vue` 现有调用方共 7 处（6 个文件）：`components/StatisticBoardToolbar.vue`、`views/CodeReviewIllegalRecordsView.vue`、`views/CustomerIssueRecordsView.vue`、`views/DropdownOptionSettingsView.vue`（两处）、`views/issue-illegal-records/IssueIllegalRecordsPage.vue`、`views/ReviewDataManagementView.vue`、`views/SystemTestIssueSearchView.vue`。新增参数一律带默认值，不传即现状。
- 关系文案词表 `operatorLabel()` 位于共享文件 `components/statistic-board-filters.ts:173-200`，看板也在用，不得直接修改（否则看板文案一起变）。
- 外部参考（仅参照，不构成需求）：2026-10-08 调查阿里云 Quick BI / DataWorks、Jira、Metabase、Notion/Airtable 的关系命名，共同点是"右边是一组值时才出现集合词，且用属于/任一这类明确说法；包含只表示文字片段"。本方案的分档命名与该惯例一致。

## 证据与根因

- 复现一（2026-09-04，用户界面实测）：黑名单用「包含全部」保存成功但 25 个候选值全部保留；改用「包含任意一个」正确剔除（25 → 23）。
- 复现二（2026-10-08，预览接口实测，基线候选池 25 个项目名，标签组 id=16「测试」= {大国工匠, 二次开发平台（2026 R4）研发项目}）：`containsAll` → 0 保留；`notContainsAll` → 25 全保留；`isEmpty` 黑名单 → 25 全保留、`isNotEmpty` 黑名单 → 0 保留（恒常量）；`intersects` → 2、`eq` → 2（别名实证）；`partialContainsAny` → 2（是文字片段匹配，能正常命中）。
- 根因链（三条叠加）：
  1. 判定侧每次只取一个候选值，「包含全部 / 不包含全部」在单值上恒不成立/恒成立；「为空 / 不为空」因空白候选被预先过滤而恒为常量；
  2. 页面把看板的五个集合关系与六个字面关系原样平铺——`StatisticFilterBuilder.vue:66` 硬编码五个集合关系、`DropdownOptionSettingsView.vue:25-33` 的 `OPTION_FIELDS.operators` 含 `isEmpty`/`isNotEmpty`；
  3. 后端保存期只判断"是不是集合关系"（`DropdownOptionRuleSupport.java:147` 调 `LabelGroupFilterOperatorSupport.isSetOperator`，接受 `eq`、`ne`、`intersects`、`notIntersects`、`containsAll`、`notContainsAll`、`partialContainsAny` 共七个）——恒不成立的也能存进库。
- 同一个「包含」字面被用于两种比较（文字片段 vs 与组内成员相同），是本页"容易误解"的直接来源；「包含任意一个 / 不包含任意一个」的名称与功能一致（用户 2026-10-08 明确），问题只在两档之间没有分层。
- 黄金基线影响核对（2026-10-08，只读核对，未跑链路）：`backend/src/test/resources/golden-baseline/endpoint-catalog.yml` 的下拉写用例只发送字面条件（`operator: eq` 配 `logic: OR`）；全部黄金文件（目录、快照、夹具）中不含集合关系 → **预期零快照差异**。保存期拒绝属于写接口行为变化，发布时仍按门禁跑一次 compare。

## 方案与步骤

1. **前端共享组件加三个可选参数**（默认值等于现状，不传参的 6 处调用零变化）：
   - `labelGroupOperatorOptions`：覆盖标签组关系清单（默认现有五个）；
   - `operatorLabels`：覆盖关系显示文案（默认共享 `operatorLabel()`）；汇总区与编辑区所有关系渲染点统一走该覆盖；
   - `hideLogicSelectorWhenSingleCondition`：条件数不大于 1 时隐藏「满足全部 / 满足任意」。
   实现位置：`StatisticFilterBuilder.vue` 的 `defineProps`、`:66` 的 `labelGroupOperators`、`:339-345` 的 `operatorSelectOptions`、逻辑选择器渲染点。
2. **下拉设置页传入收缩配置**（`DropdownOptionSettingsView.vue`）：
   - `OPTION_FIELDS.operators` 由六个收窄为 `['eq','ne','contains','notContains']`（移除 `isEmpty`/`isNotEmpty`）；
   - 两处 `StatisticFilterBuilder`（`:594`、`:647`）传入标签组关系清单 `['intersects','notIntersects','partialContainsAny']`、文案覆盖「属于该组 / 不属于该组 / 包含组内任一成员」、并开启单条件隐藏；
   - 规则区加一行静态说明：「包含 / 不包含：对单个选项值做文字片段判断（如填 2026 可命中 CC2026R1）」。
3. **后端保存期收窄**：`DropdownOptionRuleSupport.normalizeCondition` 的标签组分支拒绝 `containsAll`、`notContainsAll`，抛 `BizException` 并给出可读原因；`intersects`、`notIntersects`、`partialContainsAny` 放行，`eq`/`ne` 经既有 `normalize` 归一化后放行。字面关系白名单不动（`isEmpty`/`isNotEmpty` 接口层仍可存，语义是确定常量、无误导性，界面不再提供即可）。
4. **测试**：
   - 后端 `DropdownOptionRuleSupportTest` 增加：两个被拒关系各自报错且文案可读、`intersects`/`notIntersects`/`partialContainsAny` 正常通过、`eq`/`ne` 归一化后通过；
   - 前端 `DropdownOptionSettingsView.test.ts` 断言两处组件传入的关系清单、文案覆盖与单条件隐藏开关；`StatisticFilterBuilder` 既有测试确认不传新参数时行为不变（必要时补一条默认值用例）；
   - 全量前端套件与后端快速套件；`npm run typecheck`；仓库守卫脚本与 `git diff --check`。
5. **浏览器复验**（按用户界面确切操作）：新增黑名单规则 → 展开关系下拉确认只有 7 项且标签组档为绿色 → 选「属于该组」配标签组验证剔除生效 → 选「包含」填 2026 验证命中 CC2026R 系列 → 确认静态说明行与单条件隐藏 → 顺带打开看板筛选确认未变。

## 决策记录

- 已定（用户 2026-10-08）：第二档命名采用「属于该组 / 不属于该组」；第三个关系不用"名字"一词（选项值里有人名、项目名、阶段名等），定为「包含组内任一成员」——"成员"是平台标签组功能既有术语，不偏向任何一类；如需更短可退为「包含组内任一」。
- 已定（用户 2026-10-08）：「包含组内任一成员」（`partialContainsAny`）**保留**——它是文字片段匹配、实测可命中，与恒不成立的 `containsAll` 性质不同。此前"三个都拒绝"的提案作废。
- 已定（用户 2026-10-08）：「包含任意一个 / 不包含任意一个」名称与功能一致，本方案不把它们当错误处理；改名的目的是与文字档分层，不是纠错。我此前"五个关系名实不符"的判断有误，已撤回。
- 已定（用户 2026-10-08）：单条件时**只隐藏**组合关系选择器，**不改**黑名单/白名单的默认组合方式。
- 实施细化（2026-10-08）：开关按"条件数不多于一条"判定，汇总条上的 `满足全部 / 满足任意` 标签与编辑区的选择器一并隐藏（否则单条件时出现"只有一个条件却标注满足全部"的噪声）；页面初始无规则时同样不显示。默认值 `false` 时行为与改动前完全一致。
- 已定（用户 2026-10-08）：原反馈第 1 条「条件值只能手输」另立工作单元，不并入本次。
- 已定（用户 2026-10-08）：不考虑存量配置，不做迁移与归一化。
- 明确否决：改共享关系词表或判定引擎来"让包含全部在单值上说得通"（会改变看板语义，且单值场景本就不需要该关系）；只改文案不收缩取值（用户仍能选到永不生效的关系）；把交互改成两步式（现有交互已成立，改动面大且无收益）。

## 接口契约

前端 `components/StatisticFilterBuilder.vue` 新增参数（全部可选，默认值保持现状）：

| 参数 | 类型 | 默认 | 作用 |
|---|---|---|---|
| `labelGroupOperatorOptions` | `StatisticFilterOperator[]` | 现有五个 | 覆盖标签组条件下可选的关系清单 |
| `operatorLabels` | `Partial<Record<StatisticFilterOperator, string>>` | 共享 `operatorLabel()` | 覆盖关系显示文案 |
| `hideLogicSelectorWhenSingleCondition` | `boolean` | `false` | 条件数不大于 1 时隐藏「满足全部 / 满足任意」 |

前端下拉设置页常量（实际命名）：
- `OPTION_FIELDS.operators = ['eq', 'ne', 'contains', 'notContains']`
- `LABEL_GROUP_OPERATOR_OPTIONS = ['intersects', 'notIntersects', 'partialContainsAny']`
- `OPERATOR_LABELS = { intersects: '属于该组', notIntersects: '不属于该组', partialContainsAny: '包含组内任一成员' }`
- 页面规则区静态提示：`关系说明：「包含 / 不包含」判断的是单个选项值里有没有这段文字（如填 2026 会命中 CC2026R1）；标签组关系（属于该组 / 不属于该组 / 包含组内任一成员）按标签组成员判断。`

后端 `DropdownOptionRuleSupport` 标签组分支的允许集合：
- 放行：`intersects`、`notIntersects`、`partialContainsAny`（`eq`/`ne` 经既有 `normalize` 归一化后同样放行）
- 拒绝：`containsAll`、`notContainsAll` → 抛 `BizException`，文案示例「该关系在单值判定下恒不成立，请改用『属于该组 / 不属于该组 / 包含组内任一成员』：<operator>」

## 风险与假设

- 共享组件的默认值若写错，会让 7 处调用方的行为漂移。验收点固定为「不传新参数即与改动前一致」，并跑受影响页面的既有测试与全量前端套件。
- 只收缩界面、不做后端拒绝，等于直接调接口仍能存进无效配置；因此前端与后端必须在同一工作单元内完成。
- 「黄金基线预期零差异」是只读核对后的推断，不是已跑结果；发布门禁时以实际比对为准。
- 该功能尚未启用，所以本次不做现场数据核对；若实施前用户开始使用，需要先只读核对是否已存在含被拒关系的配置。
- 「包含组内任一成员」在标签组值类型非 STRING 的字段上不出现（`StatisticFilterBuilder.vue:275` 既有过滤）；本页 `optionValue` 为文字类型，不受影响。
