# 评审数据管理旧平台 Excel 导入方案

日期：2026-05-28  
状态：已实现第一版，并完成旧平台口径对比验证  
范围：`评审数据管理` 模块，从内网旧平台导出的 Excel 文件导入历史评审数据。

## 背景

旧平台“评审数据管理”页面支持 Excel 导入、模板下载、评审列表导出和问题明细导出。新平台启用时，需要把旧平台历史评审数据迁移进新平台，避免人工重新录入。

本阶段先做 Excel 导入，不兼容 CSV。优先目标是导入旧平台页面“导出评审列表”生成的 `.xlsx` 汇总文件；旧平台模板下载是 `.xls`，它对应的是“单个评审文件导入模板”，字段更细但格式不同，建议作为第二格式支持，不阻塞第一版。

## 调研结论

来自 `D:\projects\data_collection_platform\数据采集交接文档.xlsx` 的 `评审数据管理` sheet：

- 功能意义：评审人员评审需求说明书和设计说明书后，将评审问题提交到评审管理页面，并督促研发修改评审问题。
- 支持能力：评审数据增删改查、评审数据下载。
- 支持属性：评审的工作产品、评审类别、文档类别、评审缺陷个数、页数、评审工作量、问题类别统计、评审缺陷密度、加权重缺陷密度、评审效率、评审速率。

来自旧平台源码 `D:\projects\spidergitdata-dev`：

- 旧平台列表入口是 `ReviewBoard.vue`，导出接口是 `POST /review/exportByParam`，服务端生成 `AllData.xlsx`。
- 旧平台导入接口是 `POST /review/importFile`，但它读取的是模板评审文件：sheet 1 取项目名，sheet 2 取评审报告，sheet 3 取问题清单；不是列表页导出的 `AllData.xlsx`。
- 旧平台模板文件是 `src/main/resources/review/文档评审.xls`，前端上传支持 `.xls, .xlsx`。
- 旧平台筛选下拉里，项目、模块、负责人来自已入库评审记录的 distinct 值；评审类型是固定枚举；评审专家来自人员接口。
- 旧平台“评审类别”有两层含义：列表主记录的 `sourceType` 是评审类型，例如需求说明书评审、设计说明书评审；问题明细里的 `reviewType` 是评审类别，例如独立评审、会议评审。导出汇总列里的“评审类别”实际是问题明细评审类别的聚合，不应直接写入新平台主记录 `reviewType`。

来自新平台当前实现：

- 新平台主表字段包括 `projectName`、`title`、`moduleName`、`reviewType`、`reviewDate`、`reviewOwner`、`reviewScalePages`、`reviewProduct`、`authorName`、`reviewVersion`。
- 新平台问题项字段包括 `reviewerName`、`workloadHours`、`reviewCategory`、`documentPosition`、`problemCategory`、`problemDescription`、`suggestedSolution`、`ownerName`、`rejectionReason`、`problemStatus`。
- 新平台筛选选项中，项目、模块、负责人、专家来自数据库已有记录；评审类型、评审类别、问题类别、问题状态是固定选项。

## 设计原则

1. 第一版只承接旧平台列表导出的 `.xlsx` 汇总文件，不把旧平台模板 `.xls` 和列表 `.xlsx` 混在同一个解析器里。
2. 导入分为“解析预览”和“确认入库”两步，不允许上传后直接写库。
3. 优先按表头名称识别字段；表头兼容旧平台导出中的换行、单位、同义名称。
4. 导出汇总文件没有逐条问题明细，导入时只能按分类数量生成合成问题项，并在描述中标记“历史汇总导入”。
5. 旧平台密度、效率、速率用于校验和预览，不作为新平台主数据直接入库；新平台统计仍以 `review_records` 和 `review_problem_items` 为准。
6. 下拉字段不是“凭空捏造”：导入值必须符合固定枚举或进入数据库后自然成为项目、模块、负责人、专家筛选来源。

## 字段映射

### 主记录映射

| 旧平台导出字段 | 新平台字段 | 规则 |
| --- | --- | --- |
| 评审的工作产品 / 标题 | `title` | 必填，保留原文 |
| 评审的工作产品 / 标题 | `reviewProduct` | 默认与标题一致 |
| 所属项目 / 项目 | `projectName` | 必填，保留原文，例如 `CC2026R4` |
| 所属项目 / 项目 | `reviewVersion` | 第一版建议默认复用；如用户提供默认版本则优先使用默认版本 |
| 模块 | `moduleName` | 必填；缺失时尝试从标题中的 `【xxx模块】` 提取 |
| 文档类型 / 文档类别 / 评审类型 | `reviewType` | 优先取旧平台 `sourceType` 对应导出列，例如需求说明书评审、设计说明书评审；不要用聚合的“独立评审/会议评审”覆盖 |
| 上传时间 | `reviewDate` | 取日期部分；缺失时由预览页提供默认评审日期 |
| 负责人 | `reviewOwner` | 必填；缺失时由预览页提供默认负责人 |
| 负责人 | `reviewExperts` | 默认作为唯一专家；若用户提供默认专家列表则优先使用默认专家 |
| 页数 / 评审规模 | `reviewScalePages` | 非负整数 |
| 负责人 | `authorName` | 旧平台汇总导出没有稳定作者字段时，默认复用负责人；也可由预览页提供默认作者 |

### 问题项映射

旧平台列表导出是汇总记录，不包含逐条问题明细。第一版按问题类别数量生成合成 `review_problem_items`：

| 旧平台分类字段 | 新平台 `problemCategory` |
| --- | --- |
| 文档规范 | 文档规范 |
| 完整性 / 完整性规范 | 完整性 |
| 功能性 / 功能性规范 | 功能性 |
| 可行性 / 可行性规范 | 可行性 |

合成问题项默认字段：

| 新平台字段 | 规则 |
| --- | --- |
| `reviewerName` | 优先默认专家；否则使用负责人 |
| `reviewCategory` | 优先读取旧平台“有效的独立评审问题数/有效的会议评审问题数”，分别生成“独立评审/会议评审”问题项；没有分类问题数字段时才回退解析“评审类别” |
| `workloadHours` | 有独立/会议问题数时，分别用独立评审工作量、会议评审工作量除以对应问题数；缺少分类问题数时按总工作量均摊；没有工作量时为 0 并提示警告 |
| `documentPosition` | 空 |
| `problemCategory` | 来自分类字段 |
| `problemDescription` | `历史汇总导入：旧平台导出未提供逐条问题明细，按{分类}汇总数量生成。` |
| `suggestedSolution` | 空 |
| `ownerName` | 空 |
| `rejectionReason` | 如导出有“不达标说明”，写入该字段；否则为空 |
| `problemStatus` | 使用现有固定状态，建议默认“已关闭”；如果用户希望保留待处理语义，可在预览页改成“新提交” |

## 校验规则

### 必填校验

以下字段缺失时，该行默认不可导入，除非用户在预览页提供默认值：

- 标题 / 评审的工作产品
- 项目 / 所属项目
- 模块
- 评审类型，即旧平台 `sourceType`
- 页数 / 评审规模
- 上传时间或默认评审日期
- 负责人或默认负责人

### 数值校验

- `问题总计` 必须为非负整数。
- `页数` 必须为非负整数。
- `文档规范 + 完整性 + 功能性 + 可行性` 应等于 `问题总计`；不一致时允许预览但标记为错误，默认不入库。
- `评审缺陷密度` 应约等于 `问题总计 / 页数`，允许 0.01 舍入误差；不一致时标记警告。
- `评审效率` 应约等于 `问题总计 / 总工作量`；总工作量为 0 时只提示警告。
- `评审速率` 应约等于 `页数 / 总工作量`；总工作量为 0 时只提示警告。

### 枚举校验

- `reviewType` 必须落入新平台评审类型固定选项：需求说明书评审、设计说明书评审、产品用户手册、项目计划评审、其他。
- `reviewCategory` 必须落入新平台评审类别固定选项：走查、独立评审、会议评审。
- `problemCategory` 必须落入新平台问题类别固定选项：文档规范、完整性、功能性、可行性、无问题。
- `problemStatus` 必须落入新平台问题状态固定选项：新提交、已修改、已关闭、已拒绝、无问题、未评审。

### 重复校验

第一版建议用以下组合判断重复：

```text
projectName + title + reviewType + reviewDate + reviewVersion
```

确认导入时提供三种策略：

- 跳过重复：默认策略。
- 全量新增：用于旧平台数据确实存在重复评审记录的情况。
- 覆盖旧导入：建议第二阶段实现，需要导入批次或 legacy hash 支撑。

## 导入流程

### Step 1：上传 Excel

前端入口：

- 位置：评审数据管理页面工具栏。
- 按钮：`导入旧平台 Excel`。
- 权限：仅管理员可见。
- 支持文件：第一版 `.xlsx`；若需要支持旧模板 `.xls`，后续新增“模板文件导入”模式。

后端接口建议：

```text
POST /api/review-data/legacy-excel-import/preview
Content-Type: multipart/form-data
```

请求字段：

- `file`：Excel 文件。
- `sheetName`：可选；不传时优先识别 `Data` sheet 或包含“评审”的 sheet。

返回内容：

- 导入批次临时 token。
- sheet 名称。
- 总行数、可导入行数、警告行数、错误行数。
- 预计生成评审记录数、预计生成问题项数。
- 字段识别结果。
- 行级预览和错误明细。

### Step 2：预览与默认值配置

前端展示：

- 导入摘要。
- 字段映射结果。
- 前 50 行预览。
- 错误/警告行列表。
- 默认值表单：默认评审日期、默认负责人、默认专家、默认作者、默认评审版本、默认问题状态、重复处理策略。

### Step 3：确认导入

后端接口建议：

```text
POST /api/review-data/legacy-excel-import/confirm
Content-Type: application/json
```

请求字段：

- `previewToken`
- `defaultReviewDate`
- `defaultReviewOwner`
- `defaultReviewExperts`
- `defaultAuthorName`
- `defaultReviewVersion`
- `defaultProblemStatus`
- `duplicateStrategy`

确认导入时：

1. 读取 preview 暂存结果，必要时校验文件 hash。
2. 在事务中写入 `review_records`。
3. 写入 `review_record_experts`。
4. 按分类数量写入合成 `review_problem_items`。
5. 刷新记录搜索索引。
6. 返回导入结果。

## 实现任务

### Task 1：Excel 解析与格式识别

**说明**：新增旧平台导出列表解析器，优先支持 `.xlsx` 的 `Data` sheet，并识别是否误传旧模板 `.xls`。

**验收**：
- 能读取旧平台导出的表头和数据行。
- 能跳过空行、说明行和 Excel 单位列。
- 误传旧模板文件时返回清晰提示，而不是静默失败。

**可能触碰文件**：
- `backend/pom.xml`
- `backend/src/main/java/com/data/collection/platform/service/ReviewDataLegacyExcelParser.java`
- `backend/src/test/java/com/data/collection/platform/service/ReviewDataLegacyExcelParserTest.java`

### Task 2：字段识别与行模型

**说明**：建立旧平台 Excel 表头到导入模型的映射，并拆清主记录字段、汇总指标字段、合成问题项字段。

**验收**：
- 支持旧平台导出截图和源码中的导出字段。
- 能识别 `sourceType` 作为新平台主记录 `reviewType`。
- 能识别导出“评审类别”只是问题项评审类别聚合，不能覆盖主记录评审类型。
- 缺失关键表头时返回中文错误。

**可能触碰文件**：
- `ReviewDataLegacyExcelColumn.java`
- `ReviewDataLegacyExcelRow.java`
- `ReviewDataLegacyExcelImportIssue.java`

### Task 3：导入预览服务

**说明**：实现 dry-run 解析、字段校验、数值校验和汇总统计。

**验收**：
- 上传 Excel 后不写库。
- 返回行级错误和警告。
- 能计算预计生成的问题项数量。
- 能校验问题总计、分类合计、密度、效率、速率。

**可能触碰文件**：
- `ReviewDataLegacyExcelImportService.java`
- `ReviewDataLegacyExcelPreviewResponse.java`
- `ReviewDataLegacyExcelPreviewRowResponse.java`

### Task 4：确认导入服务

**说明**：将预览通过的数据写入现有评审数据表。

**验收**：
- 成功写入主记录、专家和合成问题项。
- 重复策略 `SKIP` 可用。
- 导入后列表、汇总、筛选、导出能看到数据。
- 整个批次建议事务回滚，避免半写入。

**可能触碰文件**：
- `ReviewDataLegacyExcelImportService.java`
- `ReviewDataRecordWriteRepository.java`
- `ReviewDataProblemItemRepository.java`

### Task 5：导入接口

**说明**：新增管理员接口。

**验收**：
- `preview` 接口支持 multipart 上传。
- `confirm` 接口支持确认导入。
- 非管理员不可调用。
- 错误提示为中文。

**可能触碰文件**：
- `ReviewDataController.java`
- `ReviewDataLegacyExcelImportRequest/Response.java`

### Task 6：前端导入向导

**说明**：在评审数据管理页新增导入按钮和导入弹窗。

**验收**：
- 管理员可上传 Excel。
- 可查看预览、错误、警告和默认值配置。
- 确认导入后刷新列表和筛选选项。
- 导入 loading 不影响现有新增、编辑、导出。

**可能触碰文件**：
- `frontend/src/views/ReviewDataManagementView.vue`
- `frontend/src/views/review-data/ReviewDataLegacyExcelImportDialog.vue`
- `frontend/src/api-client/review-data-api.ts`
- `frontend/src/types/api.ts`

## 测试计划

后端：

```powershell
cd D:\projects\data_collection_platform\backend
..\tools\maven\apache-maven-3.9.9\bin\mvn.cmd -q "-Dtest=ReviewDataLegacyExcelParserTest,ReviewDataLegacyExcelImportServiceTest,ReviewDataControllerTest" test
```

前端：

```powershell
cd D:\projects\data_collection_platform\frontend
npm test -- review-data
npm run typecheck
```

手工验收：

1. 上传旧平台列表导出的 `.xlsx`，能显示预览。
2. 上传旧平台模板 `.xls`，能提示该格式属于模板导入，第一版暂不支持或进入模板模式。
3. 故意删除必填列，能看到中文错误。
4. 用截图中的数据导入后，评审记录数量和问题总数一致。
5. 筛选项目、模块、负责人、专家时，导入值可见。
6. 筛选文档规范、完整性、功能性、可行性问题项可查。
7. 导入后再导出，新平台导出能包含导入记录。

## 2026-05-28 对比验证记录

本轮针对新实现的导入能力与旧平台页面、旧平台导出字段进行了对比。当前项目中确认存在的新增导入功能是评审数据管理旧平台 Excel 导入：

- 后端接口：`POST /api/review-data/legacy-excel-import/preview`、`POST /api/review-data/legacy-excel-import/confirm`
- 前端入口：评审数据管理页 `导入旧平台 Excel`
- 集成测试模块本轮实现的是旧平台样式 Excel 导出，不包含导入入口。

对比发现并已修正的差异：

- 旧平台列表同时展示 `独立评审工作量合计(小时)`、`有效的独立评审问题数合计(个)`、`会议评审工作量(小时)`、`有效的会议评审问题数合计(个)`。
- 第一版导入如果遇到一行同时包含 `[独立评审, 会议评审]`，可能把合成问题项全部归入单一评审类别，影响用户按评审类别筛选、统计的使用习惯。
- 现已补充解析 `有效的独立问题数/有效的独立评审问题数` 和 `有效的会议评审问题数/有效的会议评审问题合计`，并在合成问题项时按数量拆分“独立评审”和“会议评审”。
- 独立/会议问题数合计与问题分类合计不一致时，不直接静默吞掉，而是在预览里给出警告，并按问题分类总数补齐或截断。

验证命令：

```powershell
cd D:\projects\data_collection_platform\backend
..\tools\maven\apache-maven-3.9.9\bin\mvn.cmd -q "-Dtest=ReviewDataLegacyExcelParserTest,ReviewDataControllerTest,IntegrationTestControllerTest,IntegrationTestExcelExportServiceTest" test

cd D:\projects\data_collection_platform\frontend
npm run typecheck
npm test -- review-data
npm test -- integration-test-analysis
```

验证结果：

- 后端评审导入解析、控制器、集成测试导出相关测试通过。
- 前端类型检查通过。
- 前端评审数据管理相关测试通过。
- 前端集成测试分析页相关测试通过。

## 待确认问题

1. 旧平台列表导出的 `AllData.xlsx` 是否一定包含 `文档类型/sourceType` 列？如果某些历史文件没有该列，第一版建议要求用户在预览页统一指定默认评审类型。
2. `所属项目` 是否可以同时作为 `projectName` 和默认 `reviewVersion`？当前建议可以，但如果新平台未来有独立版本口径，应由用户在预览页覆盖。
3. 汇总导入生成的合成问题项，默认 `problemStatus` 用“已关闭”是否符合历史迁移口径？如果希望保留“待核对”语义，只能在现有枚举中选择“新提交”或“未评审”。
4. 是否需要把旧平台 `是否达标` 和 `不达标说明` 做成新平台主表独立字段？当前表结构没有对应字段，第一版只把不达标说明写入合成问题项 `rejectionReason`。
5. 是否需要第二阶段支持旧平台模板 `.xls` 直接导入？该格式能保留真实问题明细，质量会高于汇总导入，但实现复杂度更高。
