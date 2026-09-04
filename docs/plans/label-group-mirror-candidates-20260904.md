# 标签组候选来源改镜像库直取（2026-09-04）

## 进度与中间物

- 状态：**实施完成并全量验证通过（2026-09-04）**。
- 验证结果：
  - 后端 labelgroup 测试 27/27 绿（LabelValueQueryServiceTest 9 + LabelDimensionCatalogServiceTest 4 + LabelGroupControllerTest 14）。
  - 前端受影响测试 16/16 绿；typecheck 零错误；前端全量 vitest **124 文件 / 457 用例全绿**。
  - 后端全量默认套件（注入测试库 env）：**1241 用例 / 1 失败 / 0 错误 / 1 跳过**——唯一失败为同事 untracked WIP `ReviewDataRecordReadSupportTest`，**零回归**。
  - 四项门禁脚本全绿；`git diff --check` 仅报其他单元 frontend 文件的 2 处 EOF 空行（非本单元）。
  - 黄金基线未运行（AGENTS.md 门禁）：`/api/label-groups/dimensions` 快照变化留待打包验收时 `-Dgolden.update=true` 重建 + 用户人工审阅。
- **实施期修订（前端接线扩展，超出原"零前端功能改动"预期）**：调查发现 `labelDimensionKey`（页面筛选字段 ↔ 标签组维度键的 SAME_FIELD 匹配配置）在前端引用了旧维度键，且设置页表单的"来源字段"选项就是维度键——删键必须同步改前端接线：
  - `review-data-management.ts`：reviewOwner/reviewExpert 字段 `labelDimensionKey` 由 `review_owner`/`review_expert`（已删除的键，不改会静默失配）改 `person`。
  - `customer-issue-condition-fields.ts`/`system-test-condition-fields.ts`：人员类字段（authorName/handlerName/assigneeName）新增 `personConditionField` 助手统一携带 `labelDimensionKey: 'person'`——替代原 `issue_assignee`/`customer_assignee` 经别名桥接的匹配路径，使"人员"组在三个页面的人员字段均可匹配。
  - `StatisticFilterBuilder.vue`/`label-group-field-key.ts`：删除指向已删维度键的别名映射（死路径）。
- 原方案要点（已实施）：删除 5 个旧人员维度合并为 person；project/person/milestone 直取镜像仓储（复用 `ReviewDataMirrorOptionRepository`）；模块/测试阶段保持现状；无迁移。
- 用户已锁定决策：
  - 人员：合并删除 5 个旧人员维度（review_owner/review_expert/issue_assignee/customer_author/customer_assignee），新增单一"人员"维度，来源=镜像 users 表全量人名。
  - 项目：改镜像 labels 表"项目："标签全量解析，与"评审数据-新增评审-项目名称"下拉完全同源同值。
  - 里程碑：改镜像 milestones.title 全量直取。
  - 模块：保持现状（需归一化清洗，脏数据多，现状够用）。
  - 测试阶段：保持现状。
  - 存量数据：内外网静态组 `source_field_key` 均为空，无存量迁移。

## 恢复线索

- 当前阶段：实施完成并全量验证通过（2026-09-04）；剩余动作仅为用户确认后的提交。
- 恢复后首步：如需复验，backend 目录注入测试库 env（见「约束与背景」）后运行 `mvn.cmd test`，判读标准 = 唯一失败 `ReviewDataRecordReadSupportTest`（同事 WIP）；frontend 目录 `npm run test` 全绿。
- 实施涉及文件：后端 `labelgroup/LabelDimensionCatalogService.java`、`labelgroup/LabelValueQueryService.java`、测试 `LabelValueQueryServiceTest.java`、`LabelDimensionCatalogServiceTest.java`、`LabelGroupControllerTest.java`；前端 `review-data-management.ts`(+test)、`customer-issues/customer-issue-condition-fields.ts`(+test)、`system-test/system-test-condition-fields.ts`、`components/StatisticFilterBuilder.vue`、`utils/label-group-field-key.ts`、`api-client/label-groups-api.test.ts`。
- 上一份关联计划：`docs/plans/dropdown-option-config-20260903.md`（已完成）。

## 目标与边界

- 用户原始需求（原话）："我们要的不是被页面筛选好的，而是从镜像库的表中直接获取最全的候选值"；人员"为什么不直接从 users 表里直接获取所有的人名呢？非要将人员分成这么多个类"。
- 成功标准：
  1. 候选来源"项目"的值集与新增评审项目名称下拉完全一致（同调 `loadLabelProjectNames()`）。
  2. "人员"维度返回镜像 users 全量人名（本地库 461 人 vs 旧 issue_assignee 仅 214）。
  3. 模块/测试阶段/枚举类维度行为不变。
  4. 后端全量默认套件除已知预存失败（同事 untracked WIP `ReviewDataRecordReadSupportTest`）外全绿；前端 vitest 全绿。
- 明确禁止：为旧 5 人员维度保留兼容键、别名、转发或双轨；改变模块/测试阶段候选语义；新增数据库迁移。

## 约束与背景

- 开发期红线（AGENTS.md）：直接改成目标版本并删除旧路径，不留兼容层。
- 镜像表为单套命名（`ods_gitlab_*`），`GitlabSourceInstanceSupport.buildMirrorTableName` 单参数无实例前缀；直取查询不使用 sourceInstanceId（与评审表单下拉同款）。
- 直取样板已存在，直接复用：`ReviewDataMirrorOptionRepository`（backend/src/main/java/com/data/collection/platform/service/ReviewDataMirrorOptionRepository.java）的 `loadLabelProjectNames()` / `loadUserNames()` / `loadMilestoneTitles()`，均含 `mirror_deleted` 过滤与表缺失容错（返回空列表）。
- 前端 `LabelGroupSettingsView.vue` 动态拉取维度列表并按 `staticSupported` 过滤（:70）——维度清单变更零前端功能改动；仅一个前端测试文件引用旧键（见步骤 4）。
- 验证环境事实（Windows 开发机）：
  - mvn 必须用绝对路径 `/d/projects/data_collection_platform/tools/maven/apache-maven-3.9.9/bin/mvn.cmd`，在 backend 目录执行。
  - 全量套件必须注入测试库 env，否则 @SpringBootTest 回退 `localhost:15433` 级联 60+ 个上下文错误（环境问题，非代码回归）：`TEST_DATASOURCE_URL='jdbc:postgresql://localhost:15432/qaflex?currentSchema=qaflex_test,public' TEST_DATASOURCE_USERNAME=qaflex TEST_DATASOURCE_PASSWORD=<docker inspect qaflex-dev-postgres-15432 取 POSTGRES_PASSWORD>`。
  - 判读以 surefire 报告（backend/target/surefire-reports/）或输出中 `Tests run:` 统计为准（管道会吞退出码）。
  - 黄金基线全链路测试按 AGENTS.md 门禁**严禁主动运行**，仅打包验收或用户明确要求时运行。

## 证据与根因

- 现状语义（`LabelValueQueryService.java:84-106`）：静态候选 = 遍历该维度的兼容页面、取各页面筛选下拉值的并集——即"该页面数据里实际出现过的值"，被页面数据过滤。
- 本地库量化实证（qaflex-dev-postgres-15432，2026-09-04）：评审页 projectNames 5 个、议题页 13 个、mr_fact 19 个 vs 镜像"项目："标签 **29 个**；issue_fact.assignee 214 人 vs 镜像 users **461 人**——"少且不同"即事实层聚合（含归一化清洗）与镜像全量的天然差异。
- 评审表单项目下拉早已走镜像直取（`ReviewDataMirrorOptionRepository.java:25`）——两套候选来源分裂是问题本体。
- `existsStaticCandidateValue`（`LabelValueQueryService.java:73`）全仓零调用——死代码，本次顺带删除。
- 黄金基线已登记 `/api/label-groups/dimensions`（快照含旧维度键，将有意变化）与 `/api/label-groups/dimensions/{dimensionKey}/values`（现仅 module 用例，行为不变）；`review_owner` 在 review-data 表快照里是列名，与本维度键无关，不受影响。

## 方案与步骤

### 改动后的维度全景（权威定义）

静态候选来源（staticSupported=true，候选来源下拉的 8 个选项）：

| key | 名称 | 值来源 | 本次变化 |
|---|---|---|---|
| module | 模块 | 事实层归一化模块值（页面聚合） | 不变 |
| project | 项目 | 镜像 labels"项目："标签全量解析 | **改直取** |
| person | 人员 | 镜像 users.name 全量 | **新增** |
| milestone | 里程碑 | 镜像 milestones.title 全量 | **改直取** |
| test_stage | 测试阶段 | 事实层规则识别值（页面聚合） | 不变 |
| severity_level | 严重程度 | 枚举（页面聚合） | 不变 |
| priority_level | 紧急程度 | 枚举（页面聚合） | 不变 |
| closure_status | 客户问题闭环状态 | 枚举（"需求如此"） | 不变 |

非静态维度（staticSupported=false，不进候选来源下拉，全部不变）：target_branch、round、defect_reason、delay_reason。

删除的维度键（删除后 getDimension 抛"标签维度不存在：xxx"，即 400）：review_owner、review_expert、issue_assignee、customer_author、customer_assignee。

### 步骤 1：LabelDimensionCatalogService（backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelDimensionCatalogService.java）

- createDimensions()：删除 5 个旧人员维度行；新增 `put(dimensions, "person", "人员", "镜像库 users 表全量人名", LabelValueKind.STRING_LITERAL)`；`project` 描述改为"镜像 labels 表项目标签解析"；`milestone` 描述改为"镜像库里程碑标题全量"。
- createCompatiblePages()：删除 5 个旧人员维度的页面绑定；为 `person` 增加每页一条（页面去重，fieldKey 取该页代表性人员字段）：
  - `add(pages, "person", "review-data-home", "评审数据管理", "reviewOwner", "评审负责人", true)`
  - `add(pages, "person", "question-metrics-issue-search", "系统测试议题查询", "assigneeName", "议题处理人", true)`
  - `add(pages, "person", "customer-issues-cc-product-issues", "客户问题列表", "assigneeName", "客户问题处理人", true)`
- project/milestone/test_stage/severity_level/priority_level/closure_status/module 的页面绑定全部不变。

### 步骤 2：LabelValueQueryService（backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelValueQueryService.java）

- 注入 `ReviewDataMirrorOptionRepository`（构造器追加参数）。
- `loadOptions` 顶部增加直取分派（在 closure_status 特判之后、pageKey 分派之前）：
  - `"project"` → `toOptions(mirrorOptionRepository.loadLabelProjectNames())`
  - `"person"` → `toOptions(mirrorOptionRepository.loadUserNames())`
  - `"milestone"` → `toOptions(mirrorOptionRepository.loadMilestoneTitles())`
  - 直取维度带 pageKey 时仍先经 `pageSupportsDimension` 校验（listValues 入口已有），值集不按页面过滤——候选=镜像全量，页面只是使用位置。
- `toOptions` 助手：`List<String>` → `List<OptionItemResponse>`（value=label=原名）。
- 删除 `existsStaticCandidateValue` 方法（全仓零调用死代码）及随之不再使用的 import。
- `reviewOptions`/`issueOptions`/`customerOptions` 三个页面加载器与三个页面服务依赖**保留**（module/test_stage/severity_level/priority_level/closure_status 仍用）；reviewOptions 中 `review_owner`/`review_expert` 分支、issueOptions 中 `issue_assignee` 分支、customerOptions 中 `customer_author`/`customer_assignee` 分支删除（维度键已不存在，不可达）。

### 步骤 3：后端测试

- 现无 `service/labelgroup/` 测试目录；新建 `backend/src/test/java/com/data/collection/platform/service/labelgroup/LabelValueQueryServiceTest.java`（纯 Mockito 单测，mock LabelDimensionCatalogService 为真实实例 + mock 三个页面服务与 ReviewDataMirrorOptionRepository）：
  - project/person/milestone 直取镜像仓储返回值，不调用页面服务；
  - person 带合法 pageKey 返回全量、带不兼容 pageKey 抛 BizException"当前页面不支持该标签维度：人员"；
  - 旧维度键（如 review_owner）经 `getDimension` 抛 BizException"标签维度不存在：review_owner"（用真实 LabelDimensionCatalogService 断言维度清单 = 上表 8 项 + 4 非静态）；
  - module 仍走页面聚合（mock 页面服务返回值透传）；
  - keyword 过滤与分页语义保持（复用现有逻辑，抽 1 个用例覆盖）。
- `LabelGroupControllerTest`（现有，mock 服务层）不受影响，跑一遍确认。

### 步骤 4：前端测试（零功能改动）

- `frontend/src/api-client/label-groups-api.test.ts:107-108` 的 `review_owner` 仅为 URL 构造夹具，改为 `person`（语义上更贴切且键存在）。

### 步骤 5：验证

1. backend 目录：`TEST_DATASOURCE_URL=... TEST_DATASOURCE_USERNAME=qaflex TEST_DATASOURCE_PASSWORD=... /d/projects/data_collection_platform/tools/maven/apache-maven-3.9.9/bin/mvn.cmd test`——判读：唯一允许失败 = `ReviewDataRecordReadSupportTest`（同事 untracked WIP），其余全绿。
2. frontend 目录：`npm run test`（vitest 全绿）+ `npm run type-check`（如脚本存在）。
3. 本地真实链路冒烟（可选，需后端在 18080 运行）：`GET /api/label-groups/dimensions` 返回 8 静态维度；`GET /api/label-groups/dimensions/person/values?page=1&size=200` 返回 users 全量人名；`GET /api/label-groups/dimensions/project/values` 与新增评审项目下拉值集一致。
4. 黄金基线**不在本单元运行**（AGENTS.md 门禁；dimensions 快照变化留待打包验收时更新模式重建 + 用户人工审阅）。

### 步骤 6：文档收尾

- 本文件状态更新为完成并记录验证结果；`docs/progress.md` 新增条目（[完成]/[验证]/[待办]：黄金基线 dimensions 快照待打包验收时重建审阅）。
- 提交：本单元文件独立成一次提交（工作树含他人进行中的解耦改动，只 add 本单元文件），提交信息 `feat(label-group): 标签组静态候选改镜像库直取，人员维度合并为一`；提交与推送动作待用户确认。

## 决策记录

- **已选**：人员合并为单一 person 维度（用户明确要求 users 全量、不分类）。
- **已选**：project/milestone 直取镜像，复用 `ReviewDataMirrorOptionRepository` 现成方法（与评审表单下拉同源，保证两处候选完全一致；不新建查询体系）。
- **已选**：模块保持现状（用户：需归一化清洗，脏数据多，现状够用）。
- **已选**：测试阶段保持现状（用户指定）。
- **已选**：无存量迁移（用户确认内外网静态组 source_field_key 均为空）。
- **已选**：直取维度仍受 pageSupportsDimension 校验（页面只是使用位置，值集不按页面过滤）。
- **已否决**：为旧 5 维度保留兼容键或映射（开发期红线禁止双轨）。
- **已否决**：新建独立镜像查询服务（样板已存在，复用即单一事实源）。

## 接口契约

- `GET /api/label-groups/dimensions`：维度清单变更（12 静态 → 8 静态：-5 旧人员 +1 person；project/milestone 描述更新）——有意语义变更，黄金基线快照待打包验收时重建。
- `GET /api/label-groups/dimensions/{key}/values`：project/person/milestone 返回镜像全量（经 keyword 过滤 + 分页，现有逻辑复用）；旧 5 人员键返回 400 BizException"标签维度不存在：xxx"。
- `GET /api/label-groups/dimensions/{key}/compatible-pages`：person 返回 3 个页面；旧键 400。
- 无数据库迁移；无前端功能改动；无其他端点变更。
- 建议黄金基线 endpoint-catalog.yml 为 values 端点补 `person-page1` 用例（`query: dimensionKey=person&page=1&size=20`）锁定新行为——在打包验收重建快照时一并执行，不在日常套件运行。

## 风险与假设

- 假设：内网镜像 users 表人名质量可接受（用户已明确要求全量直取，不做角色分类过滤）。
- 风险：黄金基线 dimensions 快照变化须在打包验收时经用户审阅（门禁流程已覆盖；严禁在本单元顺手重建）。
- 性能：直取查询与评审表单下拉同款同量级（users 461 / labels 584 / milestones 小表，每请求直查，评审下拉已长期如此，无缓存需求）。
- 敏感边界：镜像表只读（现状语义），本单元不写镜像表。
