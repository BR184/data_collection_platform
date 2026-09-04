# 标签组成员候选全量加载方案

## 进度与中间物

- 状态：**已实施并全量验证通过（2026-09-04）**。实施记录：改动仅 `frontend/src/components/label-groups/LabelGroupMemberPicker.vue` + `.test.ts` 两个文件（接手前次指派遗留的工作树改动并收尾）；实施期修复一处测试缺陷——两个桩的参数 `page` 遮蔽文件级 `page()` 构造助手致 2 用例失败，改名 `pageNumber` 后复绿，组件实现零改动。
- 验证证据：组件测试 9/9；typecheck 绿；全量 vitest 459/459（首轮 1 例未捕获名失败、两轮复跑全绿，与已知偶发模式一致非回归）；UI 实机验证（18181）：候选来源=人员 → values?page=1..4&size=200 共 4 请求（第 4 页空页触发终止兜底，因 461 行含 5 个重名去重后 456 < total）、下拉渲染 456 项与库内去重名精确一致、中文名 140+ 位起滚动可见、关键字"王"远程搜索 34 项命中。bot 保留、carmazhao 空格未动、排序未改，全部符合「明确禁止」约束。
- 提交待用户确认（仅摘取本单元 2 文件 + 本计划文档）。

### 恢复线索

- 当前阶段：已完成（实施+验证+文档收尾全部结束，见「进度与中间物」）。
- 恢复后首条命令：`cd frontend && npx vitest run src/components/label-groups/LabelGroupMemberPicker.test.ts`。
- 上一份计划：`docs/plans/label-group-mirror-candidates-20260904.md`（已完成的镜像直取单元，提交 58e770a7）。

### 目标与边界

- 用户原始需求：新建标签组选“人员”来源时，成员值候选必须**全部显示**（滚动查看），不能只显示一部分让用户误以为缺人；该是中文的就是中文（现状已满足，数据本身如此）。
- 可验证的成功标准：
  1. 选“人员”候选来源，不输关键字，下拉列出**全部 461 条**（含中文名，滚动可见）；
  2. 输入关键字（如“王”）远程搜索，匹配结果全量列出；
  3. 已选成员不出现在候选列表、保存/回显、手动输入创建成员等既有行为不回归；
  4. `LabelGroupMemberPicker.test.ts` 覆盖“多页循环拉全”断言。
- 明确禁止：
  - 不动后端任何文件（排序、分页上限 200、过滤逻辑全部保持现状）；
  - 不剔除 bot/系统账号（Ghost User、CI Robot 等保留）；
  - 不处理 `carmazhao` 前导空格脏值（用户明确要求，待其弄清后另议）；
  - 不改字母排序语义（中文仍排在 ASCII 之后，属可接受现状）；
  - 不引入兼容层/开关/双轨（直接改成目标版本）。

### 约束与背景

- 后端 values 接口：`GET /api/label-groups/dimensions/{dimensionKey}/values`，参数 `page`（≥1）、`size`（1..200，超限自动收敛）、`keyword`；响应 `{ items, total, page, size }`，total 是过滤后全量计数（`LabelValueQueryService.java:77-81`）。
- 前端 API：`api.listLabelDimensionValues(dimensionKey, { keyword, page, size })`（`frontend/src/api-client/label-groups-api.ts:33`），已支持分页参数，无需改动。
- 唯一调用方：`LabelGroupSettingsView.vue:575` 的 `LabelGroupMemberPicker`（仅传 `dimension-key` 与 `value-type`，未传 `fetch-values`，走组件内默认 fetcher）。
- 现状缺陷：组件 `defaultFetchValues` 硬编码 `page: 1, size: 50`（`LabelGroupMemberPicker.vue:101-107`），461 人只显示前 50。

### 证据与根因

- 数据实证（2026-09-04，15432 库 `ods_gitlab_users`）：总数 461 = 过滤后 461（全量在列）；`name` 含中文 315 人、纯 ASCII 146 人；首中文名按 `lower(name)` 排第 148 位；SQL 复现后端排序前 50 条与用户截图逐一吻合（`carmazhao` 带前导空格排第一）。
- 代码链路：`LabelValueQueryService.java:74` 按 `compareToIgnoreCase` 排序（ASCII 先于中文）→ `LabelGroupMemberPicker.vue:106` 只拉第一页 50 条 → 首屏全拼音、中文不可见。
- 相关提交：58e770a7（person 维度镜像直取，本问题所在功能）。

### 方案与步骤

总体策略：仅改前端 `LabelGroupMemberPicker.vue` 一个文件（+测试更新），将“拉一页 50”改为“循环拉全部分页”，上限 200/页 = 后端上限。

1. **扩展 fetchValues 契约**：props 类型从 `(dimensionKey: string, keyword: string) => Promise<LabelValuePage>` 改为 `(dimensionKey: string, keyword: string, page: number, size: number) => Promise<LabelValuePage>`。组件内 `defaultFetchValues` 改为 `(dimensionKey, keyword, page, size) => api.listLabelDimensionValues(dimensionKey, { keyword, page, size })`。
2. **全量加载函数**：`loadCandidates(keyword)` 内循环调 `fetcher(dimensionKey, keyword, page, 200)`，从 page=1 起累加 items 到 `total` 取满或返回空页为止；把全部 items 合并写入 `candidates.value`（去重按 value，后到不覆盖已选逻辑照旧）。循环间无需并发，顺序拉取即可（461 人 = 3 次请求，延迟可忽略）。
3. **并发守卫**：`loadCandidates` 已被 el-select 的 remote-method 高频触发，循环拉页期间若 keyword 变化需中止旧循环——用自增 requestId（或等效闭包令牌）比对，旧请求的结果丢弃。
4. **测试更新**（`LabelGroupMemberPicker.test.ts`）：
   - 既有桩 `vi.fn(async (_d, keyword) => page([...]))` 改为接收 4 参并按 page 返回不同切片，模拟多页；
   - 新增断言：两页数据合并后 `candidateOptions` 含全部条目；`fetchValues` 被以 `page=1,size=200` 与 `page=2,size=200` 调用；
   - “loads candidates for the selected dimension” 用例：全量合并语义下断言不变项（keyword 透传）。
5. **验证**：
   - `cd frontend && npx vitest run src/components/label-groups/LabelGroupMemberPicker.test.ts`；
   - `npx vue-tsc --noEmit -p tsconfig.json`（typecheck 绿）；
   - 全量 `npx vitest run`（确认无其他组件依赖 2 参签名——已排查唯一调用方是设置页，未传自定义 fetchValues；测试文件内桩全部更新）。
6. **UI 实测**（AI 员工若具备浏览器验证条件则执行，否则明确记录未验证）：18080/18181 环境，新建标签组 → 候选来源选“人员” → 断言下拉滚动到底为 461 条、输入“王”命中中文名、已选与保存回显正常。注意浏览器验证需 18080 后端在跑（LDAP 模式），验证完停掉。
7. **文档收尾**：更新本文件状态段；`docs/progress.md` 追加一行该单元条目；提交信息 `feat(label-group): 成员候选改全量分页加载消除缺人误判`。

### 决策记录

- 已选：前端组件循环拉全分页（方案 A）。理由：改动面最小（1 文件）、后端零改动、所有镜像维度（项目 29、里程碑、人员 461）统一受益。
- 已否决：后端新增 size 上限豁免/一次性全量端点——为单一下拉改 API 契约，过度设计且需动黄金基线目录。
- 已否决：前端一次拉 size=461——后端 `Math.min(200, size)` 会静默截断到 200，依赖隐式截断不可靠。
- 已否决：bot 剔除、carmazhao 空格清洗、中文拼音混排——用户明确暂缓或否决（见「明确禁止」）。
- 待定：无。

### 接口契约

- 变更仅前端组件 props 契约：`fetchValues?: (dimensionKey: string, keyword: string, page: number, size: number) => Promise<LabelValuePage>`。后端 API、表结构、路由零变化。
- 无新增后端接口、无迁移。

### 风险与假设

- 假设：候选维度最大规模为人员 461 条，拉全 3 次请求性能无感知延迟；若未来某维度达万级需重新评估（届时可加前端虚拟滚动或保留远程分页），当前不做预防性设计。
- 风险：remote-method 高频触发与循环拉页竞态——步骤 3 的 requestId 守卫消除；测试需覆盖“keyword 变化时旧循环结果被丢弃”（若现有测试框架下难以稳定模拟，至少以单测验证守卫逻辑存在）。
- 敏感只读数据：无（只读下拉候选）。
