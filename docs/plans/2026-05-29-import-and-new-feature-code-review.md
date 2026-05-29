# 2026-05-29 新增导入与 Excel 能力专项 Code Review

## 审查范围

本次只做审查和记录，不修改业务代码。重点覆盖近期新增或受影响的能力：

- 评审数据管理旧平台 Excel 导入：
  - `ReviewDataLegacyExcelParser`
  - `ReviewDataLegacyExcelImportService`
  - `ReviewDataController`
  - `ReviewDataLegacyExcelImportDialog`
  - `review-data-api`
- 集成测试旧平台样式 Excel 导出：
  - `IntegrationTestExcelExportService`
  - `IntegrationTestController`
  - `IntegrationTestAnalysisView`
  - `integration-tests-api`
- 统计下钻议题编号链接：
  - `StatisticBoardDetailDialog`
  - `useStatisticBoardDetail`
- 系统测试非法数据和议题多元看板演示数据：
  - `SystemTestIllegalRecordsView`
  - `seed_system_test_multi_board_demo.sql`
  - `repair_demo_display_data.sql`

## 验证记录

已运行：

```powershell
cd D:\projects\data_collection_platform\backend
..\tools\maven\apache-maven-3.9.9\bin\mvn.cmd -q "-Dtest=ReviewDataLegacyExcelParserTest,ReviewDataControllerTest,IntegrationTestControllerTest,IntegrationTestExcelExportServiceTest,SystemTestIllegalRecordServiceTest" test

cd D:\projects\data_collection_platform\frontend
& 'C:\Program Files\nodejs\npm.cmd' run typecheck
& 'C:\Program Files\nodejs\npm.cmd' test -- review-data integration-test-analysis system-test-illegal-records StatisticBoardDetailDialog
```

结果：

- 后端 targeted 测试通过。
- 前端 typecheck 通过。
- 前端 15 个相关测试文件、48 个测试用例通过。

说明：

- 测试通过只能证明当前已有断言未失败；下面列出的若干风险没有被现有测试覆盖，仍需要修复或补测试。
- PowerShell 里直接打印中文源码时可能显示为乱码；本次行号确认使用 UTF-8 读取和 Unicode 转义二次核对，避免误判。

## Findings

## 2026-05-29 修复记录

本轮已落地：

- Finding 1：确认导入会重新基于缓存的原始解析行套用 confirm 阶段默认值，避免预览后修改负责人、专家、作者、版本、问题状态不生效。
- Finding 2：preview token 已增加 30 分钟 TTL 和 100 条本地容量上限，并在 confirm 的成功/异常路径清理 token。
- Finding 3：解析阶段不再把负数静默归零，负数计数/工作量会生成可见 ERROR。
- Finding 4：第一版明确只接受旧平台列表导出的 `.xlsx`，前端上传 accept 和后端校验都拒绝 `.xls` 模板。
- Finding 5：评审数据 Excel 上传增加空文件、20MB 大小和扩展名边界校验。
- Finding 7：前端导出/下载路径已统一走 `requestText` / `requestBlob`，复用超时、CSRF 头和 JSON 错误解析。
- 补充修复：新增全局 REST 异常处理，避免导入/上传类业务异常散落成 500 或非统一响应。

本轮保留：

- Finding 8：批量写入性能优化仍保留为后续项；当前已避免重复显式刷新搜索索引之外的主要正确性问题。
- Finding 9：统计下钻链接已有单测覆盖，端到端回归留到页面自动化测试专项中补齐。

第三轮补充：

- Finding 2 的 preview token 缓存已从 `ReviewDataLegacyExcelImportService` 抽离为通用 `PreviewSessionStore<T>`，后续新增导入功能可复用同一套 TTL、容量裁剪和确认后清理语义，避免每个导入服务各自实现一份本地缓存。
- CI 已增加评审导入、集成测试导出、前端 API/路由和统计下钻相关目标测试，新增导入能力后不再只依赖本地手工执行。

已验证：

```powershell
cd D:\projects\data_collection_platform\backend
..\tools\maven\apache-maven-3.9.9\bin\mvn.cmd -q "-Dtest=ReviewDataLegacyExcelParserTest,ReviewDataControllerTest,AuthControllerTest,IntegrationTestControllerTest,IntegrationTestExcelExportServiceTest,SystemTestIllegalRecordServiceTest" test

cd D:\projects\data_collection_platform\frontend
& 'C:\Program Files\nodejs\npm.cmd' run typecheck
& 'C:\Program Files\nodejs\npm.cmd' test -- request integration-test-analysis review-data StatisticBoardDetailDialog code-review issue statistic-board
```

### 1. Critical: 评审数据导入确认阶段复用 preview 快照，用户在预览后修改默认值不会生效

位置：

- `backend/src/main/java/com/data/collection/platform/service/ReviewDataLegacyExcelImportService.java:36-72`
- `backend/src/main/java/com/data/collection/platform/service/ReviewDataLegacyExcelImportService.java:76-107`
- `backend/src/main/java/com/data/collection/platform/service/ReviewDataLegacyExcelConfirmRequest.java:3-5`
- `frontend/src/views/review-data/ReviewDataLegacyExcelImportDialog.vue:85-93`

现象：

- `preview()` 会把已经套用默认值后的 `ReviewDataLegacyExcelPreviewResponse` 存入内存 `previewCache`。
- `confirm()` 只接收 `previewToken` 和 `duplicateStrategy`，然后直接使用 preview 时生成的 `record/problemItems` 入库。
- 前端弹窗允许用户在预览区域仍然看到默认负责人、专家、作者、版本、问题状态等表单，但确认导入时只传 `previewToken` 和 `duplicateStrategy`。

影响：

- 用户如果“先预览，发现默认负责人/状态不对，再改表单并点确认导入”，实际入库仍是旧默认值。
- 这和用户对“预览后确认”的直觉相悖，尤其评审数据管理依赖下拉字段和人工字段，容易造成批量导入脏数据。

建议方案：

- 方案 A：确认导入请求补齐 preview 参数。
  - 扩展 `ReviewDataLegacyExcelConfirmRequest`，加入 `defaultReviewDate/defaultReviewOwner/defaultReviewExperts/defaultAuthorName/defaultReviewVersion/defaultProblemStatus`。
  - `confirm()` 不直接入库 preview 行，而是基于缓存中的原始 parse rows 或可重建模型重新执行 `toPreviewRow(row, confirmRequest)`。
  - 前端 `confirmImport` 传入完整表单。
- 方案 B：预览后锁定默认值。
  - 预览成功后禁用默认值表单，用户修改时必须重新点“解析预览”。
  - 确认按钮旁提示“确认导入使用当前预览结果”。

建议优先采用方案 A；如果要快速降低误用风险，先做方案 B。

补充测试：

- 新增前端测试：预览后修改默认问题状态，确认请求应带上新值或表单被禁用。
- 新增后端测试：confirm 阶段传入不同默认值时，入库模型应使用 confirm 阶段值；若选择锁定方案，则测试前端禁用行为。

### 2. Critical: preview token 存在内存缓存且没有 TTL/容量限制，容易失效或造成内存增长

位置：

- `backend/src/main/java/com/data/collection/platform/service/ReviewDataLegacyExcelImportService.java:25`
- `backend/src/main/java/com/data/collection/platform/service/ReviewDataLegacyExcelImportService.java:72`
- `backend/src/main/java/com/data/collection/platform/service/ReviewDataLegacyExcelImportService.java:81-83`
- `backend/src/main/java/com/data/collection/platform/service/ReviewDataLegacyExcelImportService.java:106`

现象：

- preview 结果保存在 `ConcurrentHashMap<String, ReviewDataLegacyExcelPreviewResponse>`。
- 没有 TTL，没有最大条数，没有定时清理。
- 只有 confirm 成功走到最后才 remove；用户关闭弹窗、确认失败、服务重启、多实例部署都会导致 token 丢失或残留。

影响：

- 旧平台 Excel 每次最多 5000 行，preview response 可能包含大量 record/problem item 对象；多人反复预览会持续占用后端内存。
- 服务重启或多实例部署时，用户拿着 token confirm 会失败，体验上表现为“刚预览完就失效”。
- confirm 中途异常时 token 不移除，用户可能重试造成重复或缓存泄漏。

建议方案：

- 第一阶段：
  - 使用 Caffeine/Guava cache 或本地轻量 TTL cache，设置 `expireAfterWrite(30 minutes)` 和最大条数。
  - confirm 使用 `try/finally` 按策略移除 token，或只在成功导入后移除但给用户明确重试语义。
  - preview response 中只缓存必要的原始解析结果和用户默认值，不缓存完整 UI 展示对象。
- 第二阶段：
  - 如需要支持多实例，把 preview 结果写入临时导入批次表，记录 file hash、创建人、过期时间、状态。

补充测试：

- preview token 过期后返回明确中文错误。
- confirm 抛异常时 token 行为明确：允许幂等重试或明确失效。
- 多次 preview 不应无限增长缓存。

### 3. Important: 导入解析会把负数静默归零，削弱了数据质量校验

位置：

- `backend/src/main/java/com/data/collection/platform/service/ReviewDataLegacyExcelParser.java:278`
- `backend/src/main/java/com/data/collection/platform/service/ReviewDataLegacyExcelParser.java:430-432`
- `backend/src/main/java/com/data/collection/platform/service/ReviewDataLegacyExcelImportService.java:308-313`

现象：

- `integer()` 对 Excel 数字执行 `Math.max(0, round(value))`。
- `safeInt()`、`safeDouble()` 也会把负数归零。

影响：

- 旧平台导出如果存在负数、公式异常、人工修改错误，预览不会暴露真实异常，而是悄悄改成 0。
- 这会让“问题总计与分类合计一致性”“评审规模非负”等校验失真。
- 用户以为导入的是旧平台原始数据，实际新平台已做静默更改。

建议方案：

- `integer()` 和 `decimal()` 保留原始数值，不在读取阶段截断。
- 在 `parseDataRow()` 做显式校验：
  - 计数字段 `< 0` 标记 ERROR。
  - 工作量 `< 0` 标记 ERROR 或 WARNING，根据业务口径决定。
  - 只有展示合成项时才用 `Math.max(0, value)` 防御。
- 预览行增加“原始值”和“修正值”提示，避免无声转换。

补充测试：

- Excel 中 `评审规模=-1` 应返回 ERROR。
- 分类计数为负时不能被当成 0 后继续导入。
- 工作量为负时应有可见错误或警告。

### 4. Important: `.xls` 被文件名允许，但第一版方案只承诺旧平台列表 `.xlsx`

位置：

- `backend/src/main/java/com/data/collection/platform/service/ReviewDataLegacyExcelParser.java:441-445`
- `frontend/src/views/review-data/ReviewDataLegacyExcelImportDialog.vue:106-111`
- `docs/plans/2026-05-28-review-data-legacy-excel-import-plan.md`

现象：

- 前端 `accept=".xlsx,.xls"`。
- 后端 `validateFileName()` 允许 `.xlsx` 和 `.xls`。
- 方案文档写的是第一版优先支持旧平台列表导出的 `.xlsx`；旧模板 `.xls` 是第二格式。

影响：

- 用户会自然上传旧平台模板 `.xls`，但当前解析器按列表导出表头识别；失败提示可能是“未识别表头”，不是“这是模板格式，当前暂不支持”。
- 用户无法区分“文件损坏”“选错导出类型”“模板暂不支持”。

建议方案：

- 第一版如果不支持模板 `.xls`：
  - 前端 accept 改为 `.xlsx`。
  - 后端只允许 `.xlsx`，或识别 `.xls` 后返回“旧平台模板导入暂未支持，请上传列表导出的 AllData.xlsx”。
- 如果决定保留 `.xls`：
  - 加模板识别逻辑，检测 sheet 结构，返回专门错误码和提示。

补充测试：

- 上传 `.xls` 模板结构时，返回明确业务错误。
- 上传非 Excel 伪装文件时，返回文件读取失败而不是 500。

### 5. Important: 旧平台导入的 preview/confirm 没有文件大小和上传边界控制

位置：

- `backend/src/main/java/com/data/collection/platform/controller/ReviewDataController.java:108-118`
- `backend/src/main/java/com/data/collection/platform/service/ReviewDataLegacyExcelParser.java:37-58`

现象：

- 控制器直接 `file.getInputStream()` 交给 POI。
- 解析器有 `MAX_ROWS = 5000`，但这是打开 workbook 之后才生效；超大文件、超大 sheet、压缩炸弹类问题仍可能在 POI 创建 workbook 时消耗大量资源。

影响：

- 管理员误传大文件时可能拖慢后端。
- 如果后续环境开放给更多账号，上传边界不足会成为可用性风险。

建议方案：

- 配置 Spring multipart 上限，例如 10-20MB，结合旧平台导出规模决定。
- 控制器检查 `file.isEmpty()`、`file.getSize()`、文件名扩展。
- POI 增加安全配置或捕获 zip bomb 异常，返回明确错误。

补充测试：

- 空文件、超大文件、错误扩展、损坏 Excel 都返回稳定业务错误。

### 6. Important: demo 修复脚本和议题多元看板种子脚本中仍有真实乱码数据

位置：

- `scripts/repair_demo_display_data.sql`
- `scripts/seed_system_test_multi_board_demo.sql`

现象：

- 两个 SQL 脚本里大量中文内容已经以 mojibake 形式写入，例如 `銆愬伐鍏锋ā鍧椼€...`、`绯荤粺娴嬭瘯...`。
- 这不是 PowerShell 显示问题；脚本文件真实内容就是错误编码。

影响：

- 重新播种演示环境会把乱码写回数据库。
- 已经修好的页面会再次显示乱码，误导用户认为功能又坏了。
- 搜索索引字段也会带乱码，影响筛选、搜索、统计图例。

建议方案：

- 重写这两个脚本，确保文件以 UTF-8 保存并包含真实中文。
- 不要用“乱码映射修复乱码”的 SQL 作为长期演示脚本；保留为一次性迁移脚本时也应标明用途。
- 为 demo seed 增加简单校验脚本：执行后抽查 `issue_fact.title/module_name/testing_phase/author_name` 和 `review_records.title/review_owner` 不包含常见 mojibake 片段。

补充测试：

- 可用 SQL 或脚本断言 demo 数据中不包含 `銆`、`绯荤`、`脙`、`鐧` 等明显 mojibake 片段。

### 7. Important: 前端集成测试导出 API 使用裸 fetch，缺少统一请求头、超时和错误解析

位置：

- `frontend/src/api-client/integration-tests-api.ts:74-79`
- `frontend/src/api-client/integration-tests-api.ts:105-111`

现象：

- `exportIntegrationTestDetails()`、`fetchWorkbook()` 直接调用 `fetch()`。
- 没有走统一 `request()`，因此没有统一超时、CSRF header、错误格式解析。
- 其他 POST/JSON API 已经统一使用 `request()`。

影响：

- 导出接口如果将来加权限或 CSRF 规则，行为可能和页面其他操作不一致。
- 导出卡住时没有统一 timeout。
- 后端返回 `ApiResponse` JSON 错误时，前端可能把整段 JSON 当错误文本展示。

建议方案：

- 抽一个 `fetchFile()` 工具：
  - 复用 `buildRequestHeaders` 或至少支持 timeout 和 CSRF。
  - 根据 `Content-Type` 解析 JSON 错误中的 `message`。
  - 成功时返回 `Blob` 或 `text`。
- CSV 和 XLSX 导出共用这个工具。

补充测试：

- 导出 504/403/业务错误时，前端展示后端 message。
- 导出超时时显示统一超时文案。

### 8. Consider: 导入确认目前逐条写入并刷新搜索索引，批量导入会比较慢

位置：

- `backend/src/main/java/com/data/collection/platform/service/ReviewDataLegacyExcelImportService.java:88-104`

现象：

- 每条记录调用 `commandService.createRecord()`。
- 每个合成问题项调用 `commandService.createProblemItem()`。
- 每条记录创建后又显式 `refreshSearchIndex()`，而 `createRecord()` 内部已经刷新一次搜索索引。

影响：

- 5000 行导入时会产生大量单行 insert/update。
- 搜索索引至少存在重复刷新。

建议方案：

- 第一阶段移除重复 `refreshSearchIndex()`，确认 `createProblemItem()` touch 后是否会影响搜索索引需求。
- 第二阶段增加批量插入 repository 方法，或以导入批次为单位最后统一 backfill search index。

### 9. Consider: 统计下钻链接能力已具备，但需要保留端到端回归

位置：

- `frontend/src/components/StatisticBoardDetailDialog.vue:76-90`
- `frontend/src/components/StatisticBoardDetailDialog.vue:116-126`
- `frontend/src/components/StatisticBoardDetailDialog.test.ts:101-127`

现状：

- 弹窗能把结构化 `{ label, href }` 渲染为 `<a>`。
- 样式已经提供蓝色、下划线、hover 色。
- 单元测试覆盖了 href 和无 href fallback。

建议：

- 保留现有单元测试。
- 额外加一个 useStatisticBoardDetail 或后端 detail API 的 contract test，确保 `iid/issueIid` 字段能稳定返回结构化 link。
- 对“系统测试缺陷汇总”保留一条 Playwright 点击下钻验证，避免未来后端字段名变化让链接退化为纯文本。

## 总体结论

当前实现已经覆盖了第一版主要路径：旧平台评审数据 Excel 预览/确认、集成测试 Excel 导出、统计下钻链接展示。现有 targeted 测试全部通过。

但本次审查不建议直接视为“可无风险上线”。必须优先处理：

1. preview 后修改默认值不生效。
2. preview token 内存缓存无 TTL/容量限制。
3. 负数静默归零导致导入校验失真。
4. demo SQL 真实乱码会反复污染展示环境。

建议按上述 4 个问题先做一轮小修复，再补齐对应回归测试和一次真实页面验证。
