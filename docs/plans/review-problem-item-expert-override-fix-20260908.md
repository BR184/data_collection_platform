# 评审问题同名专家覆盖缺陷修复方案（2026-09-08）

## 进度与中间物

- 状态：**实施完成，验证全绿**（2026-09-08）。后端单测 `ReviewDataRecordCommandServiceTest` 7/7 绿、`ReviewDataSearchIndexIntegrationTest` 4/4 绿；后端默认全量套件 1250 全绿（0 失败 0 错误，1 预存跳过，BUILD SUCCESS）；前端 vitest 459/459 绿、typecheck 干净。
- 实施变更清单：
  - `backend/.../service/ReviewDataRecordCommandService.java`：`createProblemItem` 改用 `requireProblemStatus`（空/空白直接拒绝）；`defaultPendingStatus` 方法删除；`requireProblemStatus` 空值文案由"编辑评审问题时必须选择问题状态"泛化为"必须选择问题状态"（创建/编辑共用）；`findPendingProblemItem` 劫持谓词由「状态==未评审」替换为 `isDefaultPendingProblemItem`（纯占位行才命中，谓词本身已含状态判定，不叠双重过滤）。
  - `backend/.../entity/ReviewDataProblemItemSaveRequest.java`：`problemStatus` 补 `@NotBlank(message = "必须选择问题状态")`（该 record 其余必填字段本就有校验注解，边界对齐；控制器 `@Valid` 已激活）。
  - `frontend/src/views/review-data/ReviewProblemItemFormDialog.vue`：`problemStatus` 规则改为新增/编辑均必填（删除 editMode 条件展开）；删除 `problemStatusPlaceholder` computed，模板占位固定"请选择问题状态"；候选过滤"未评审"逻辑保持不变；`normalizeEditableProblemStatus`（编辑脏数据时清空强制补选）保持不变。
  - `backend/.../service/ReviewDataRecordCommandServiceTest.java`：重写——占位夹具修正为真实占位形态（独立评审/无问题/待评审）；新增 4 用例：空状态拒绝（null+空白）、手动"未评审"拒绝、同专家已有已完成项走 insert（多条支持）、"未评审+真实内容"脏行走 insert（守卫）；never 校验统一对准服务实际调用的 12 参重载；`ReviewDataSearchIndexIntegrationTest` 传"待整改/已关闭"不受影响，无需改动。
  - `docs/platform-page-business-rules.md` 第 13 条：按定稿文案修订（均必选、候选不含未评审、首次填写语义、同专家可多条、存量脏行不覆盖需人工补选）。
- 影响面核查（实施中补充证据）：黄金基线 golden-create（新提交）/golden-update（处理中）不触达变更路径，夹具零"未评审"行，初判零快照影响；Excel 导入 `ReviewDataLegacyExcelImportService` 恒传"已关闭"或用户指定状态（显式"未评审"改造前即被拒），不受影响；前端无任何测试断言旧占位文案或 editMode 条件必填，无需改动。

## 恢复线索

- 当前阶段：实施完成、默认套件全绿，待用户浏览器验收与提交。恢复后首条命令：`git status`/`git diff` 核对工作树，按「接口契约」核对行为；存量核查 SQL 见「方案与步骤」第 6 步。
- 对应调查发生在 2026-09-08 会话；无实施提交。

## 目标与边界

- 用户原始需求（2026-09-08）：内网评审数据页「新增评审问题」存在缺陷——①选择评审专家自动创建的空评审问题占位项与手动新增的真实问题之间会发生覆盖：已有一条王三的评审问题且编辑好后，再次新建王三的评审问题会**覆盖掉已有那条**；②业务上需要**同一评审专家可有多条评审问题**（如两条王三的问题），新平台当前不支持。
- 成功标准：
  1. 同一评审专家名下已存在**有真实内容**的问题项时，手动新增该专家的问题一律插入新条目，绝不覆盖既有条目。
  2. 同一专家可存在多条评审问题（数据层已支持，需行为层放开）。
  3. 自动占位项的「首次填写即填充占位」语义保留（对齐老平台，避免占位项与真实项并存的双条观感）。
  4. 老平台行为基准对齐：新增问题必须选择问题状态，且状态候选不含「未评审」。
- 明确禁止：不改动占位项自动创建/同步机制（业务规则第 12 条既有确认）；不改动专家表 `unique(review_record_id, expert_name)` 约束；不动「未评审」在有效问题数统计中的排除口径（业务规则第 6 条）。

## 约束与背景

- `docs/platform-page-business-rules.md` 第 12 条：编辑评审时专家表与清单占位项同步规则（新增专家补占位、删除专家只删未填占位、删最后一条问题时移除专家）——占位机制是已确认设计，本缺陷不在占位机制本身。
- 同文件第 13 条（**需修订**）：「新增评审问题时如果用户没有选择问题状态，后端保存时默认标注为‘未评审’」——本次调查证实该默认化正是覆盖缺陷的成因之一，与老平台基准不符。
- 同文件第 6 条：有效问题数排除「已拒绝/未评审/无问题」状态项——「未评审+有内容」的存量项会被统计口径排除，加重数据污染后果。
- 表结构（`V20260506_01__search_and_fact_query_indexes.sql`）：`review_problem_items` **无** (review_record_id, reviewer_name) 唯一约束（多条天然可行）；`review_record_experts` 有 `unique(review_record_id, expert_name)`（专家列表不可重复，与本缺陷无关）。
- 30001 内网为 f66aff80 基线，前端代码与本地 HEAD 零差异（`git diff f66aff80 HEAD -- frontend/src` 为空）。

## 证据与根因

### 覆盖的直接机制（新平台）

`ReviewDataRecordCommandService.createProblemItem`（backend/src/main/java/com/data/collection/platform/service/ReviewDataRecordCommandService.java:111-128）：

```java
ReviewDataProblemItemResponse pendingItem = findPendingProblemItem(recordId, request.reviewerName());
if (pendingItem != null) {
    persistenceSupport.updateProblemItem(recordId, pendingItem.id(), /* 本次表单全部字段 */);
    return pendingItem.id();
}
// 否则 insert 新条
```

`findPendingProblemItem`（同文件:388-398）按 **reviewerName 归一化匹配 + problemStatus = "未评审"** 查找既有项并 `findFirst`。即：只要该专家名下存在任何「未评审」状态的问题项，「新增」就被静默改写为对它的 **update 覆盖**。

### 缺陷复现链（逐环实证）

1. 新增/编辑评审选择专家 → `createPendingProblemItems`（同文件:190-214）与 `syncPendingProblemItemsWithExperts`（:216-268）自动为每专家插入「未评审/独立评审/无问题/待评审」占位项。
2. 前端新增问题表单 `ReviewProblemItemFormDialog.vue`：问题状态候选已过滤「未评审」（:80-86，正确），但**仅编辑模式必填**（:97-99），新增模式 placeholder 明示「可不选择，系统将标注为未评审」（:102-104）。
3. 用户新增问题时填了全部内容但未选状态 → 提交空串 → 后端 `defaultPendingStatus(null)`（同 CommandService:110, 352-355）默认化为 **「未评审」**。
4. 该提交命中占位项 → update 覆盖占位项 → **这条带真实内容的问题项状态仍为「未评审」**。
5. 用户再次新增该专家的问题 → `findPendingProblemItem` **再次命中**第 4 步的项（它仍是「未评审」）→ update 覆盖 → **第 4 步已录入的内容丢失**。如此可无限反复。
6. 伴生后果：只要该专家名下存在「未评审」项（含第 4 步这种污染项），任何「新增」都被劫持为 update → **同专家第二条问题永远建不出来**，表现为「不支持一个专家多条问题」。

### 老平台基准（spidergitdata-dev，只读查证）

老平台有**完全相同**的两个机制，本缺陷不是机制复刻错误，而是两处参数语义偏差：

- 占位自动创建：`ReviewController.java:468-479`，新增评审时按 `reviewExperts` 逐个 save `problemStatus="未评审"` 的 ProblemDetail——新平台占位机制忠实对齐。
- 新增命中即覆盖：`ReviewController.java:358-377`，注释「存在"未评审"的专家评审问题，直接更新」，按 `reviewer == 专家名 && problemStatus == "未评审"` 过滤后取第一条覆盖——新平台 `findPendingProblemItem` 忠实对齐。
- **关键差异 1（老平台）**：新增问题表单 `webapp/.../AddProblemDetail.vue:156-157` 问题状态 **required 必填**，候选列表 `:132` 为 `["新提交","已修复","已关闭","已拒绝","无问题"]`，**不含「未评审」**。
- **关键差异 2（老平台）**：后端把传入 `problemStatus` **原样保存**（`:372 setProblemStatus(problemStatus)`），无「空→未评审」默认化。
- 结论：老平台下占位项被首次填写后**必然脱离「未评审」状态**（用户所选状态被写入），覆盖至多发生一次且语义安全（占位→首次填写）；此后同名专家的新增全部走 insert，**多条天然支持**。新平台引入的「新增可空 + 空默认未评审」组合使覆盖无限反复、多条被永久阻断。

### 既有防线实证（2026-09-08 方案定稿补证）

- 后端已存在 `requireUserSelectableProblemStatus`（同文件:365-370）：手动提交「未评审」会抛「未评审为系统默认状态，不能手动选择」——后端防线已在；前端若加回该选项将与 API 直接矛盾（选中提交必被拒）。
- 同文件 `deletePendingProblemItemsForRemovedExperts`（:270-285）已用 `isDefaultPendingProblemItem` 守卫：专家移除时只删纯占位行、保留真实内容行。「系统自动化只碰纯占位行」不变量已在文件内存在，劫持路径补守卫是补齐同一不变量，非新增补丁。

### 现有测试与黄金基线影响面（已核对）

- `ReviewDataRecordCommandServiceTest` 3 个用例全部显式传 `problemStatus="新提交"`，锁定的是老平台正确语义（填充占位+写状态），方案实施后**不受影响**；缺陷场景（空状态）当前**无测试覆盖**。
- 黄金基线 `endpoint-catalog.yml` POST `/api/review-data/records/{recordId}/problem-items` 的 `golden-create` 用例显式传 `"problemStatus":"新提交"`，不触达变更路径，**初判无需重建快照**（最终以比对模式跑绿为准）。
- 前端 `frontend/src/views/review-data/*.test.ts` 中 `useReviewProblemItemDialog.test.ts` 等引用 problemStatus，实施时逐个核对断言。

## 方案与步骤

**推荐方案 = A + 存量守卫**（对齐老平台基线，并防御 30001 存量脏数据）：

1. 后端收紧新增状态（`ReviewDataRecordCommandService`）：
   - `createProblemItem` 不再走 `defaultPendingStatus` 的「空→未评审」默认化；空状态直接抛业务错误（复用 `requireProblemStatus` 语义：`新增评审问题时必须选择问题状态`）。
   - `defaultPendingStatus` 方法随之删除（开发期破坏性演进，不留双轨）。
2. 后端占位命中守卫（同文件）：
   - `findPendingProblemItem` 命中的候选项增加 `isDefaultPendingProblemItem` 判定（方法已存在，:332-342）：**只允许劫持纯默认占位项**；「未评审但含真实内容」的存量污染项不劫持，走 insert 新条。
   - 正常流下该守卫恒真（「未评审」只可能来自占位创建），仅对 30001 存量污染数据生效——属语义修正，非兼容分支。
3. 前端对齐老平台（`ReviewProblemItemFormDialog.vue`）：
   - `rules.problemStatus` 改为新增与编辑**均必填**；placeholder 统一「请选择问题状态」，删除「可不选择，系统将标注为未评审」。
   - 状态候选**不加回「未评审」**（现状 :80-86 已滤出，保持不变）：若加回，用户显式选它的真实条目将重新成为 `findPendingProblemItem` 的劫持目标，覆盖缺陷原样复发；后端 `requireUserSelectableProblemStatus` 亦必拒该值；「未评审」是系统占位标记而非用户动作状态，老平台候选刻意不含它正是防覆盖闭环的另一半。
4. 测试（与实现同工作单元）：
   - 后端新增：①新增空状态被拒绝；②同专家已有非「未评审」项时新增走 insert（多条支持）；③同专家已有「未评审+非默认内容」存量项时新增走 insert（守卫）；④纯占位仍被首次填写填充（保留既有语义，现有用例已覆盖，可按新断言补充空状态拒绝路径）。
   - 前端：更新新增模式必填断言与 placeholder 断言。
5. 文档修订（同工作单元）：
   - `docs/platform-page-business-rules.md` 第 13 条修订为：「清单里新增或编辑评审问题时，问题状态均必选且候选项不含『未评审』；『未评审』仅由系统在按评审专家自动创建占位项时使用。新增问题时若该专家存在未填写的系统占位项，视为对该占位项的首次填写（复用其条目并写入所选状态）；此后该专家的后续问题一律新增独立条目，同一评审专家可有多条评审问题。」
6. 存量数据核查（内网 30001，修复上线后由用户执行）：
   ```sql
   select r.id, r.title, p.id as item_id, p.reviewer_name, p.problem_description, p.updated_at
     from review_problem_items p
     join review_records r on r.id = p.review_record_id
    where p.deleted = false
      and p.problem_status = '未评审'
      and coalesce(p.problem_description, '') not in ('', '待评审')
    order by p.updated_at desc;
   ```
   命中行为已被覆盖链污染的存量项，需人工逐条编辑补选正确状态（编辑入口状态必填）；被覆盖丢失的历史内容无法从系统恢复，需按原始记录重新录入。

**备选方案 B（否决倾向，仅记录）**：保留「新增可空+空默认未评审」，仅加第 2 步守卫。改动更小，但留下「有内容的问题项停留在『未评审』」的语义瑕疵——该状态被有效问题数统计（规则第 6 条）排除，用户填了问题却不计入统计，且与老平台必填基准不符。若用户坚持不强制选状态再启用。

**明确否决**：取消占位项自动创建机制——违反业务规则第 12 条既有确认，且评审工作量合计、专家未完成提醒等语义均依赖占位项，改动面远超本缺陷所需。

## 决策记录

- 2026-09-08 用户提议「问题状态必填 + 下拉框加回『未评审』」并要求评估；评估结论：必填=采纳（即推荐方案 A 核心）；下拉框加回「未评审」=否决——显式选「未评审」的真实条目会重新成为劫持目标（缺陷复发），后端 `requireUserSelectableProblemStatus` 已显式拒绝该值，老平台候选刻意不含「未评审」正是防覆盖闭环的另一半，「未评审」是系统占位状态非用户动作状态。用户确认按此定稿（不加回）。
- 待用户拍板：实施时机与业务规则第 13 条修订文案。
- 已选：占位机制与「首次填写填充占位」语义保留（老平台对齐 + 第 12 条既有确认）。
- 已否决：读侧按专家名去重展示、前端拦截同名专家（治标，不解决数据层覆盖）。

## 接口契约

- 无新增/删除 API。行为变更点：`POST /api/review-data/records/{recordId}/problem-items` 的 `problemStatus` 由「可空默认未评审」收紧为「必填且不得为『未评审』」（DTO `@NotBlank` 边界拦截 + 服务层 `requireProblemStatus` 权威校验，违规 400）；`PUT .../problem-items/{itemId}` 服务层校验语义不变（空值文案泛化）；命中占位的判定由「未评审即命中」收紧为「纯默认占位行才命中」。
- 前端 `ReviewProblemItemFormDialog` 表单校验规则同步收紧（新增/编辑均必填），无类型变更。

## 风险与假设

- 假设（已实证）：老平台 AddProblemDetail.vue 为新增问题唯一前端入口，其必填+候选不含未评审即为行为基准。
- 风险：30001 存量「未评审+有内容」项在守卫生效后不再被覆盖，但其状态仍需人工补选（第 6 步 SQL 核查）；若不补选，这些项不计入有效问题数。
- 风险：黄金基线 golden-create 用例虽不触达变更路径，仍须在实施后跑默认快速套件确认护栏；若有意外差异按 D-08 纪律停下处理，禁止顺手重建快照。
- 敏感只读数据：spidergitdata-dev 仅为行为基准查证，不进入构建/测试/发布链路。
