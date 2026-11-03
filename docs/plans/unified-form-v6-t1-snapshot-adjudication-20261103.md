# 表单 v6 T1 文件级快照判定（2026-11-03）

> 状态：吸收审核回执 `4812dbec` 后修订；分类框架保持。当前源代码 HEAD 为候选修复提交 `56004c2e`（其下为资料提交 `30341633`），本轮只读比对 main `f74fdef7` 与功能分支 HEAD 的黄金快照，并额外用冻结夹具离线重放两处人员顺序、用运行期证据核定 16 表规划。**资料提交 `30341633` 不作为黄金源码输入；`56004c2e` 需在生成前单独直接验证。未运行黄金/update、未改快照/夹具/掩码。** 本文件交审核方裁定；T2/T3 另见发布门禁资料。

## 方法与口径

- JSON 比较忽略对象键序，保留数组顺序、值类型与字段缺失；按端点目录声明的 `ignore-paths`、`ignore-array-order` 与表 `ignore-columns` 应用既有掩码。
- 读取帧按 `GoldenBaselineChainTest.canonicalReadView` 的真实来源核验口径处理：`readContextId/sourceVersion/leaseExpiresAt/dataAsOf` 先核验租约与发布域，再规范化为占位；`data` 内 `publication:` 来源版本同样核验后规范化。不递归删除版本/时间字段。
- 数组顺序是按端点契约比较的字段，不以“成员集合相同”替代。两处人员/选项顺序在第三节给出实际 SQL、冻结排序输入和 tie-breaker，并给出有序结果；成员集合相等只用于证明没有增删。
- 31 份业务差异均可判定：人员顺序与表数量/同步按 `3fd8d2bc` 归因；其余无单一提交来源的 2 CAT 无完整点、2 物理发布列、2 协议字段、1 构建状态消息按端点契约、冻结数据集和读发布协议归因，不冒充提交归因，均无未归因项。
- 144 份存量差异**不作为整批 update 范围**；每行必须可判定为保留/生成/删除/修实现，存在未归因业务变化即停止。

## 汇总

| 项 | 数量 | 说明 |
| --- | ---: | --- |
| 新增 | 1 | 读取上下文快照恢复 |
| 删除 | 8 | 3 个旧写入口 6 份 + 2 个替换用例 2 份 |
| 修改 | 135 | 66 份读取帧、38 份掩码/键序后等价、31 份业务差异 |
| 未归因业务差异 | 0 | 31 份均已归入 main 已批准/读发布协议/人员顺序/CAT/表数量 |

## 三、人员顺序与 16 表规划依据

### 3.1 评审数据筛选项（`review-data/get___records_filter-options__default.json`）

**实际 SQL 与排序输入**

| 来源 | 代码/SQL | 排序键与 tie-breaker |
| --- | --- | --- |
| GitLab 镜像用户 | `ReviewDataMirrorOptionRepository.loadUserNames()`：`select name from <mirror_users> where coalesce(mirror_deleted,false)=false and nullif(trim(name),'') is not null order by coalesce(last_activity_on, created_at, updated_at) desc nulls last, name` | 最近活动时间倒序，`name` 升序为最终 tie-breaker；读取绑定 `DatasetReadSelection`，由 `VersionedDatasetSql` 固定到页面冻结数据集，不读后来原表 |
| 历史负责人 | `select review_owner from review_records where deleted=false and nullif(btrim(review_owner),'') is not null group by review_owner order by max(created_at) desc nulls last, review_owner limit 200` | 最近创建时间倒序，`review_owner` 升序 |
| 历史专家 | `select expert_name from review_record_experts ... group by expert_name order by count(*) desc, expert_name limit 200` | 出现次数倒序，`expert_name` 升序 |
| 历史作者 | `author_name ∪ review_problem_items.owner_name ... group by user_name order by max(created_at) desc nulls last, user_name limit 200` | 最近创建时间倒序，`user_name` 升序 |
| 兼容记录 | `review_data_match_mode_reports order by review_time desc nulls last, id desc`，排除 `authority='PLATFORM_OWNED'` 的 `match_mode_report_legacy_id` | 报告时间倒序、`id` 倒序；负责人取 `review_charger`，专家取 `review_experts` 经 `splitLooseList` 展开后以 `、` 连接 |

合并顺序（`ReviewDataFilterOptionService`）：

- 负责人 = 镜像用户 → 历史负责人 → 兼容负责人。
- 专家/作者/责任人 = 镜像用户 → 历史负责人 → 历史专家 → 历史作者 → 兼容负责人 → 兼容专家。
- `mergeValues` 用 `LinkedHashSet` 保留首次出现；`toOptions` 只做 trim/去空/去重，**不再排序**。

**冻结输入重放结果**（离线只读核验，未运行黄金）

在临时 PostgreSQL 16 中恢复 `gitlab-schema.sql.gz`、`gitlab-source-slice.sql.gz` 与 `platform-seed.sql.gz` 的本 5 张评审表，按上表 SQL 与合并顺序重放：

| 数组 | 长度 | 与 head 快照逐元素相等 | head SHA-256（数组 JSON） | main SHA-256 |
| --- | ---: | --- | --- | --- |
| `reviewOwners` | 459 | 是 | `62232c404132e9f8ecab7a2f6a13a1fda91f521f6ec2bb61ce69f7fa064eaab9` | `5481ad04291bcd4dd62edf27d85e9592d1f001464977d884c6075b57047399f6` |
| `reviewExperts` | 479 | 是 | `f5ce13e41aecbb125ce218ec15991143e6f22c0302b583137c8b8a6ed7789dd5` | `007cffa2cf07fc0938280be4cdf081c0949ea5b0d713095570a1ec16bff72f61` |

- 镜像用户去重后 456 个，严格是两数组的前 456 项；其余负责人 3 个、专家 23 个来自历史/兼容追加段。head 前 10 位依次为 `Administrator,test,CI Robot,cuirusu,fengxuanming,guanlixiang,guosenhui,huanglanying,integratedhost,jiangbensheng`；main 第 3 位起为历史负责人顺序（`刘纪凯…`）。
- 归因：`git diff main...HEAD` 显示镜像与历史查询均新增上述 tie-breaker，并改用冻结数据集读取；main 的 SQL 缺少 tie-breaker，顺序由数据库物理行序决定，不是业务契约。head 顺序可由冻结夹具完全复现，因此保留 head、不新增 `ignore-array-order`。
- 停止条件：重放与快照不再逐元素相等，或出现任一未登记的排序分支，即停止并按真实契约修实现。

### 3.2 客户问题缺陷原因统计板人员筛选（`statistic-boards/get___{boardKey}__customer-issue-defect-cause.json`）

**实际 SQL 与排序输入**

- `CustomerIssueDefectCauseBoardService.FACT_SQL_ORDER = "order by business_order_id, id"`；`FACT_SQL` 固定 `issue_fact.deleted=false`、页面范围谓词与 `created_at_source is not null`。
- 候选由 `loadedSources` 的首次出现顺序生成：`authorName`、`assigneeName` 各自传入 `StatisticPersonCandidates.of` → `OptionItemResponseFactory.fromLegacyBusinessValues`；该入口按 `\s+&\s+` 拆分组合值、trim、丢弃空值与 `未设定*/未标注*/未识别*/未标记/无需标注/GitLab接口报错` 占位，再用 `LinkedHashSet` 去重并以 `SOURCE_ORDER` 原样输出，不再排序。
- `business_order_id` 契约（`V20261013_06__fact_business_order.sql`）：新增列并置为 `id`；触发器 `preserve_fact_business_order` 禁止更新该列，新修订取同一业务根（`source_system/source_instance/project_id/根 ID`）的最小 `business_order_id`，即“业务根首次出现顺序”，跨重建保持不变；`id` 仅作为同根多修订的 tie-breaker。
- main 使用 `order by id`（物理修订行号）；head 改为业务根首次出现顺序，两者对同一构建都可确定，但 main 的顺序随修订插入次序变化，head 才是读发布模型固定下来的契约（提交 `3fd8d2bc`）。

**有序结果**（head 快照原值）

- `authorName` 19 项，SHA-256 `946a97cda9b66b963d5b429d4506f85d172e59d4be93264626d6935f50832ccf`：
  `张月、杨依景、焦方昭、张金花、李尚玉、曹巧元、刘汝宁、田松、陈嘉乐、董鹏宇、孔帆、陈艺珲、刘威麟、王丽娟、杨晓雨、赵成名、曹晋伟、王旭、白欣慧`
- `assigneeName` 74 项，SHA-256 `885c0c8379cc204531e659165bc0a9312b2ee880a8c8a57adc34b3aa863d4d94`：
  `高士亮、牟敬芳、杨晓雨、白云涛、潘广磊、庄晓、梁春阳、李焕坤、焦桦坤、王俏月、杨哲、王馨宇、马泽坤、崔雪峰、麻荣雨、李令博、李硕彦、狄林林、隋天宝、吕志金、冯钊、孟秀平、韩潞钦、陈魁、刘悦、彭环环、王苏晓、秦龙飞、王楠、高贯孟、傅培钰、侯燕申、郭英科、郎琳琳、付守帅、徐凯强、姚辉彩、潘慧敏、陈明、艾文涛、付崇莱、赵冬军、常根、陈荣昌、张宽、谭海燕、訾义龙、柳冰旸、杜丙瑜、张明霞、马传超、潘影丽、郑振松、王志阳、杨依景、席莎、张硕、刘旭阳、耿亚慧、高扬、郝晨凯、陈晖、郭良星、张丽丽、李永正、任国瑞、武晓旭、刘露、张晓涵、李东尧、徐昊2、程振昌、曹步勇、王念文`
- main 成员集合相同但顺序不同：`authorName` SHA-256 `c5dc1ed365ce751f66256217abd121a5b9d716b5d57164fa1ad823162764bb5d`，`assigneeName` SHA-256 `741f4ffcbec7398844dcbb5090a72d8200e1c7d5bbad52aae0c682699e373387`。
- 离线成员重放：本地事实切片（project 325、`CC2026R3`、352 行）按同一入口还原出 19/74 项且成员集合与 head 完全一致；其顺序不同只因该切片物理插入顺序不同，正好说明必须固定 `business_order_id` 而不能退回 `order by id`。
- 停止条件：T2 基线生成后按上述 SQL 重放，顺序指纹必须与 head 快照一致；不一致说明排序契约被实现破坏，先修实现，不生成快照。

### 3.3 `gitlab-sync/get___status__by-config.json` 的 6→16

**16 不是 ODS 来源表数量，而是该次 `FACT_REFRESH` 的任务数**：`SyncFactRefreshRunExecutor` 在排空后取 `planned = factSummary.totalTasks() + projectionSummary.totalTasks()`，完成数只统计 `SUCCESS`。

| 组成 | 数量 | 依据 |
| --- | ---: | --- |
| 全量事实任务 | 3 | 黄金配置 `whitelistMode=RECOMMENDED`，`supportedFactTypes` 返回 ISSUE/MERGE_REQUEST/INTEGRATION_TEST，每种 1 个 |
| 基础投影 | 6 | `requireProjections` 对每种事实固定要求 `GLOBAL_VIEW`，`publishFull` 再要求 `FULL_EPOCH`（3×2） |
| 客户里程碑范围组投影 | 7 | full ISSUE 构建的 `prepareProjectionPhase` 对 project 325 的每个启用 MILESTONE 组各要求 1 个 `ISSUE_SCOPE_GROUP`；黄金目录快照 `issue-scopes/get___catalogs__default.json` 中 catalog 2 的 `groupCount=7` |
| 合计 | 16 | 3+6+7=16 |

- 完成判据：`summarizeFactRun`/投影汇总按 `SUCCESS` 计数；运行只有在无 `FAILED`、无在途/依赖等待、无人工待决策时才返回 `SUCCESS`。快照证据：`tableCount=16`、`completedTableCount=16`、`runStatus=SUCCESS`、`failureCount=0`、`manualAttentionCount=0`、`diagnosticCount=0`、`diagnostics=[]`。
- main 为 6 = 3 个事实任务 + 3 个 `FULL_EPOCH` 投影：main 的 `FactTargetPublicationService` 只要求 `FULL_EPOCH`，没有 `GLOBAL_VIEW`，也没有客户里程碑范围组投影；两处均由 `3fd8d2bc` 引入。故 6→16 是读发布模型的任务口径变化，不是来源表清单回归。
- 生成/复核时在同一运行库执行（只读；`<run>` 为该 FACT_REFRESH 的主键）：
  ```sql
  select 'fact' as kind, fact_type, null as scope_type, null as scope_key, status
    from fact_build_tasks where run_id = '<run>' and trigger_type = 'MIRROR_SYNC'
  union all
  select 'projection', fact_type, scope_type, scope_key, status
    from fact_projection_refresh_tasks where fact_run_id = <run>
  order by 1, 2, 3, 4;
  ```
  期望 3 行 fact + 6 行 `GLOBAL_VIEW/FULL_EPOCH` + 7 行 `ISSUE_SCOPE_GROUP`（catalog 2 的 7 个组），全部 `SUCCESS`。若实际行数与组成不同，先查来源/目录变化，不把 16 当固定业务值写入掩码。
- 停止条件：出现未登记的来源或范围组、任一任务非 `SUCCESS`、或 `completedTableCount <> tableCount`；保留计数严格比较，不新增掩码。

## 新增（1 份）

| 文件 | 字段 | 原因 | 既有掩码/规范化入口 | 处理结论 | 预期 |
| --- | --- | --- | --- | --- | --- |
| `code-review/get___illegal-records_context__default.json` | compatibilityRead、sources[CC/DGM]、readView | 共同祖先 `84e7c023` 已有、main `59cd5587` 删除、分支原样恢复；与 fork 内容无差异 | canonicalReadView；目录 OPTIONS | 保留，按实际目录登记 | 严格 compare 无差异 |

## 删除（8 份）

| 文件 | 端点/用例 | 原因 | 处理结论 | 预期 |
| --- | --- | --- | --- | --- |
| `collect-forms/get___detail__missing-record.json` | GET /api/collect-forms/detail / missing-record | 用例身份变更（missing-record → missing-scope/unregistered-source） | 删除 | 旧快照删除，新身份快照由 T2 生成 |
| `collect-forms/get___notification-payload__missing-record.json` | GET /api/collect-forms/notification-payload / missing-record | 用例身份变更（missing-record → missing-scope/unregistered-source） | 删除 | 旧快照删除，新身份快照由 T2 生成 |
| `collect-forms/post___delete__missing-record.json` | POST /api/collect-forms/delete / missing-record | 旧写入口已被 v6 记录服务替代，快照随端点删除 | 删除 | 目录不再登记该端点，严格 compare 不要求快照 |
| `collect-forms/post___delete__missing-record__collect_form_records.json` | POST /api/collect-forms/delete / missing-record | 旧写入口已被 v6 记录服务替代，快照随端点删除 | 删除 | 目录不再登记该端点，严格 compare 不要求快照 |
| `collect-forms/post___save__golden-save.json` | POST /api/collect-forms/save / golden-save | 旧写入口已被 v6 记录服务替代，快照随端点删除 | 删除 | 目录不再登记该端点，严格 compare 不要求快照 |
| `collect-forms/post___save__golden-save__collect_form_records.json` | POST /api/collect-forms/save / golden-save | 旧写入口已被 v6 记录服务替代，快照随端点删除 | 删除 | 目录不再登记该端点，严格 compare 不要求快照 |
| `collect-forms/post___update-record__missing-record.json` | POST /api/collect-forms/update-record / missing-record | 旧写入口已被 v6 记录服务替代，快照随端点删除 | 删除 | 目录不再登记该端点，严格 compare 不要求快照 |
| `collect-forms/post___update-record__missing-record__collect_form_records.json` | POST /api/collect-forms/update-record / missing-record | 旧写入口已被 v6 记录服务替代，快照随端点删除 | 删除 | 目录不再登记该端点，严格 compare 不要求快照 |

## 修改（135 份）

### A. 仅读取帧（66 份）

| 文件 | 差异字段 | 原因 | 既有掩码/规范化入口 | 处理结论 | 预期 |
| --- | --- | --- | --- | --- | --- |
| `analytics-dashboards/get___{dashboardKey}__code-review-multi-cc.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `analytics-dashboards/get___{dashboardKey}__code-review-multi-dgm.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `analytics-dashboards/get___{dashboardKey}__quality-board-other.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `analytics-dashboards/get___{dashboardKey}_details_{viewKey}__code-review-multi-statistics.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `analytics-dashboards/get___{dashboardKey}_details_{viewKey}__quality-board-other-function-defect-count.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `analytics-dashboards/get___{dashboardKey}_details_{viewKey}__quality-rd-fix-user-defects.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `analytics-dashboards/get___{dashboardKey}_details_{viewKey}__quality-rd-integration-test-results.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `analytics-dashboards/get___{dashboardKey}_details_{viewKey}__quality-rd-release-leakage-defects.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `analytics-dashboards/get___{dashboardKey}_rules__code-review-multi.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `bi/get___coding__day-all.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `bi/get___design__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `bi/get___requirements__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `bi/get___system-test__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `code-review/get___illegal-records__cc-page1.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `code-review/get___illegal-records__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `code-review/get___illegal-records__project-9.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `code-review/get___illegal-records_filter-options__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `code-review/get___illegal-records_rule-explanation__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `code-review/get___illegal-records_{sourceInstance}_{projectId}_{mergeRequestId}__absent.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `code-review/get___multi-board_overview__cc-default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `code-review/get___multi-board_overview__cc-project-9.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `code-review/get___multi-board_overview__dgm-default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `code-review/get___multi-board_project-options__cc.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `code-review/get___multi-board_source-options__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `code-review/post___illegal-records_rule-config_preview__default-rule.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `customer-issues/get___illegal-records__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `customer-issues/get___illegal-records_filter-options__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `customer-issues/get___illegal-records_rule-explanation__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `customer-issues/get___illegal-records_{sourceInstance}_{projectId}_{issueId}__absent-record-in-complete-view.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `customer-issues/get___records_filter-options__cc-product.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `customer-issues/get___records_{sourceInstance}_{projectId}_{issueId}__absent-record-in-complete-view.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `customer-issues/post___illegal-records_refresh-one__first-issue.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `quality-board/get___other_overview__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `quality-board/get___other_project-options__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `quality-board/get___rd_dashboard__dgm-default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `quality-board/get___rd_filter-options__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `quality-board/get___rd_overview__cc2026r4.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `quality-board/get___rd_overview__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `quality-board/get___rd_project-options__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `question-metrics/get___illegal-records__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `question-metrics/get___illegal-records__testing-phase.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `question-metrics/get___illegal-records_filter-options__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `question-metrics/get___illegal-records_filter-options__project-9.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `question-metrics/get___illegal-records_rule-explanation__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `question-metrics/get___illegal-records_{sourceInstance}_{projectId}_{issueId}__absent-record-in-complete-view.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `question-metrics/get___issues__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `question-metrics/get___issues__project-9-keyword.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `question-metrics/get___issues__project-9-page1.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `question-metrics/get___issues_filter-options__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `question-metrics/get___issues_filter-options__project-9.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `question-metrics/get___multi-board__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `question-metrics/get___multi-board__project-9.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `question-metrics/post___illegal-records_refresh-one__first-issue.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `review-data/get___records__default.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `review-data/get___records__keyword.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `review-data/get___records__page1-size50.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `review-data/get___records_{recordId}__first-record.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `review-data/get___records_{recordId}_problem-items__first-record.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `statistic-boards/get___{boardKey}__customer-issue-by-function.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `statistic-boards/get___{boardKey}__customer-issue-response-efficiency.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `statistic-boards/get___{boardKey}__system-test-defect-cause.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `statistic-boards/get___{boardKey}__system-test-defect-summary.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `statistic-boards/get___{boardKey}__system-test-delay-analysis.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `statistic-boards/get___{boardKey}__system-test-phase-statistics.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `statistic-boards/get___{boardKey}_details__system-test-defect-summary-total.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |
| `statistic-boards/get___{boardKey}_rule-explanation__system-test-defect-summary.json` | readView | 分支读取帧新增；业务字段与 main 一致 | canonicalReadView + 目录已声明掩码 | 保留读取帧，业务值沿用 main | 来源/租约核验通过，严格 compare 只保留规范化占位 |

### B. 掩码/键序后等价（38 份）

| 文件 | 差异字段 | 原因 | 既有掩码/规范化入口 | 处理结论 | 预期 |
| --- | --- | --- | --- | --- | --- |
| `code-review/get___illegal-records_status__cc.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data.lastSyncedAt, $.data.lastRefreshStartedAt, $.data.lastRefreshFinishedAt | 保留（无需生成） | 严格 compare 无差异 |
| `code-review/get___match-mode-db-settings__default.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data.syncStartedAt, $.data.syncFinishedAt, $.data.updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `code-review/get___match-mode-db-settings_dgm-gitlab-project-source__default.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data.lastSyncStartedAt, $.data.lastSyncFinishedAt, $.data.updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `code-review/get___multi-board_status__default.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data.lastSyncedAt, $.data.lastRefreshStartedAt, $.data.lastRefreshFinishedAt | 保留（无需生成） | 严格 compare 无差异 |
| `code-review/put___match-mode-db-settings__golden-update.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.syncStartedAt, $.body.data.syncFinishedAt, $.body.data.updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `code-review/put___match-mode-db-settings_dgm-gitlab-project-source__golden-update.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.lastSyncStartedAt, $.body.data.lastSyncFinishedAt, $.body.data.updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `customer-issues/get___illegal-records_status__default.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data.lastSyncedAt, $.data.lastRefreshStartedAt, $.data.lastRefreshFinishedAt | 保留（无需生成） | 严格 compare 无差异 |
| `customer-issues/get___records_status__delay.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data.lastSyncedAt, $.data.lastRefreshStartedAt, $.data.lastRefreshFinishedAt | 保留（无需生成） | 严格 compare 无差异 |
| `database-backup/put___settings__golden-save-local-defaults.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `database-browser/get___rows__sync-configs-page1.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data.lastSyncTime, $.data.rows[*].created_at, $.data.rows[*].updated_at, $.data.rows[*].db_port, $.data.rows[*].last_full_sync_at | 保留（无需生成） | 严格 compare 无差异 |
| `facts/get___issue-source-readiness__default.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data.generatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `gitlab-sync/get___configs__default.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data[*].createdAt, $.data[*].updatedAt, $.data[*].dbPort, $.data[*].lastFullSyncAt | 保留（无需生成） | 严格 compare 无差异 |
| `gitlab-sync/get___source-health__by-config.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data[*].currentStartedAt, $.data[*].latestLogFinishedAt, $.data[*].latestFactUpdatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `gitlab-sync/get___table-sync-diagnostics__by-config.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data.generatedAt, $.data.directPoolMetrics, $.data.tables[*].lastAppliedAt, $.data.tables[*].lastVerifiedAt | 保留（无需生成） | 严格 compare 无差异 |
| `gitlab-sync/put___config__golden-update.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.dbPort, $.body.data.createdAt, $.body.data.updatedAt, $.body.data.lastFullSyncAt, $.body.data.lastIncrementalSyncAt | 保留（无需生成） | 严格 compare 无差异 |
| `issue-scopes/get___catalogs__default.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data[*].createdAt, $.data[*].updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `issue-scopes/get___catalogs_{catalogId}_groups__first-catalog.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data[*].createdAt, $.data[*].updatedAt, $.data[*].members[*].createdAt, $.data[*].members[*].updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `issue-scopes/patch___catalogs_{id}_enabled__golden-disable.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.createdAt, $.body.data.updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `issue-scopes/patch___groups_{id}_enabled__golden-disable.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.createdAt, $.body.data.updatedAt, $.body.data.members[*].createdAt, $.body.data.members[*].updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `issue-scopes/patch___members_{id}_enabled__golden-disable.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.createdAt, $.body.data.updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `issue-scopes/post___catalogs__golden-create.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.createdAt, $.body.data.updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `issue-scopes/post___groups__golden-create.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.createdAt, $.body.data.updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `issue-scopes/post___members__golden-create.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.createdAt, $.body.data.updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `issue-scopes/put___catalogs_{id}__golden-update.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.createdAt, $.body.data.updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `issue-scopes/put___groups_{id}__golden-update.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.createdAt, $.body.data.updatedAt, $.body.data.members[*].createdAt, $.body.data.members[*].updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `issue-scopes/put___members_{id}__golden-update.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.createdAt, $.body.data.updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `label-groups/get____default.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data[*].createdAt, $.data[*].updatedAt, $.data[*].members[*].createdAt, $.data[*].members[*].updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `label-groups/get___{groupId}__first-group.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data.createdAt, $.data.updatedAt, $.data.members[*].createdAt, $.data.members[*].updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `label-groups/post____golden-create.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.createdAt, $.body.data.updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `label-groups/put___{groupId}__golden-update.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.createdAt, $.body.data.updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `question-metrics/get___illegal-records_status__default.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data.lastSyncedAt, $.data.lastRefreshStartedAt, $.data.lastRefreshFinishedAt | 保留（无需生成） | 严格 compare 无差异 |
| `question-metrics/get___issues_status__default.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data.lastSyncedAt, $.data.lastRefreshStartedAt, $.data.lastRefreshFinishedAt | 保留（无需生成） | 严格 compare 无差异 |
| `review-data/post___records__golden-create.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.record.createdAt, $.body.data.record.updatedAt, $.body.data.descriptions[*].updatedAt, $.body.data.problemItems[*].updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `review-data/post___records_{recordId}_problem-items__golden-create.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `review-data/put___records_{recordId}__golden-update.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.record.createdAt, $.body.data.record.updatedAt, $.body.data.descriptions[*].updatedAt, $.body.data.problemItems[*].updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `review-data/put___records_{recordId}_problem-items_{itemId}__golden-update.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.body.data.updatedAt | 保留（无需生成） | 严格 compare 无差异 |
| `statistic-boards/get___{boardKey}_status__customer-issue-customer-statistics.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data.lastSyncedAt, $.data.lastRefreshStartedAt, $.data.lastRefreshFinishedAt | 保留（无需生成） | 严格 compare 无差异 |
| `statistic-boards/get___{boardKey}_status__system-test-defect-summary.json` | — | 目录掩码/对象键序/读取帧规范化后语义等价 | 目录掩码: $.data.lastSyncedAt, $.data.lastRefreshStartedAt, $.data.lastRefreshFinishedAt | 保留（无需生成） | 严格 compare 无差异 |

### C. 业务/结构差异（31 份，已归因）

| 文件 | 差异字段（组×数） | 归因 | 原因 | 既有掩码/规范化入口 | 处理结论 | 预期 |
| --- | --- | --- | --- | --- | --- | --- |
| `analytics-dashboards/get___{dashboardKey}__quality-rd.json` | $.data.charts[*].option.series[*].data.length×2; $.data.charts[*].option.series[*].data[*].detailParams.authorName×134; $.data.charts[*].option.series[*].data[*].pointKey×134; $.data.charts[*].option.series[*].data[*].value×107; $.data.charts[*].option.yAxis.data.length×2; $.data.charts[*].option.yAxis.data[*]×134 | main 已批准业务值 | main 已批准 b07e1985 的质量看板记录集/作者密度（73→75 行） | canonicalReadView；目录掩码: $.data.generatedAt, $.data.meta.generatedAt | 保留 main 业务值并叠加读取帧；不得整文件取 head | 合并后重新生成，预期作者/密度值等于 main，另加 readView |
| `analytics-dashboards/get___{dashboardKey}_details_{viewKey}__quality-rd-quality-code-review-records.json` | $.data.records[*].addedLines×20; $.data.records[*].authorName×19; $.data.records[*].defectCount×4; $.data.records[*].defectDensity×4; $.data.records[*].mergeRequestIid×20; $.data.records[*].mergeRequestState×1; $.data.records[*].reviewerName×20; $.data.records[*].targetBranch×19; $.data.records[*].title×20; $.data.total×1 | main 已批准业务值 | main 已批准 b07e1985/d3aa90f0 的走查明细记录集（total 2629→2942） | canonicalReadView | 保留 main 业务值并叠加读取帧；不得整文件取 head | 合并后重新生成，预期记录集等于 main，另加 readView |
| `analytics-dashboards/get___{dashboardKey}_rules__quality-rd.json` | $.data.rules[*].scope×2 | main 已批准业务值 | main 已批准 b07e1985 的规则 scope 文案（SP/组合版本表述） | canonicalReadView | 保留 main 文案并叠加读取帧 | 严格 compare 保留 main 文案，另加 readView |
| `bi/get___customer-issues__default.json` | $.data.data.defectMetrics[*].denominator×2; $.data.data.defectMetrics[*].numerator×3; $.data.data.defectMetrics[*].percentage×2; $.data.data.defectMetrics[*].value×1 | main 已批准业务值 | main 已批准 d3aa90f0/eb9b2f8b 的 BI 指标值（5→0、164→169 等） | canonicalReadView；目录掩码: $.data.generatedAt, $.data.sourceVersion, $.data.snapshotId | 保留 main 业务值并叠加读取帧 | 合并后重新生成，指标值等于 main |
| `bi/get___integration-test__default.json` | $.code×1; $.data×1; $.message×1; $.success×1 | CAT 无完整点 | CAT 无完整点，返回 READ_VIEW_UNAVAILABLE/HTTP409 | 目录掩码: $.data.generatedAt, $.data.sourceVersion, $.data.snapshotId | 目录用例声明 expected-http-status=409；快照记录错误信封，不伪装成功 | 首次 compare 以 409 通过；不生成成功数据 |
| `bi/get___unit-test__default.json` | $.code×1; $.data×1; $.message×1; $.success×1 | CAT 无完整点 | CAT 无完整点，返回 READ_VIEW_UNAVAILABLE/HTTP409 | 目录掩码: $.data.generatedAt, $.data.sourceVersion, $.data.snapshotId | 目录用例声明 expected-http-status=409；快照记录错误信封，不伪装成功 | 首次 compare 以 409 通过；不生成成功数据 |
| `customer-issues/get___records__cc-product-default.json` | $.data.records[*].resolveDelayed×11; $.data.records[*].responseDelayed×12 | main 已批准业务值 | main 已批准 cbca4eeb/d3aa90f0 的 resolveDelayed/responseDelayed 判定 | canonicalReadView；目录掩码: $.data.records[*].retentionHours | 保留 main 业务值并叠加读取帧 | 合并后重新生成，延期布尔值等于 main |
| `customer-issues/get___records__delay-default.json` | $.data.records[*].assigneeName×17; $.data.records[*].authorName×16; $.data.records[*].bugStatus×8; $.data.records[*].category×2; $.data.records[*].createdAt×17; $.data.records[*].customerNames×15; $.data.records[*].delayIssue×4; $.data.records[*].functionName×17; $.data.records[*].handlerName×17; $.data.records[*].illegal×10; $.data.records[*].illegalReason×12; $.data.records[*].issueId×17; $.data.records[*].issueIid×17; $.data.records[*].issueLink×17; $.data.records[*].labels.length×12; $.data.records[*].labels[*]×53; $.data.records[*].milestoneTitle×9; $.data.records[*].moduleNames×14; $.data.records[*].plannedMergeVersionBranch×2; $.data.records[*].priorityLevel×6; $.data.records[*].resolveDelayed×4; $.data.records[*].responseDelayed×3; $.data.records[*].severityLevel×14; $.data.records[*].title×17; $.data.records[*].updatedAt×17; $.data.total×1 | main 已批准业务值 | main 已批准 cbca4eeb/d3aa90f0 的延期记录集与字段（total 474→795） | canonicalReadView；目录掩码: $.data.records[*].retentionHours | 保留 main 业务值并叠加读取帧；不得整文件取 head | 合并后重新生成，记录集/字段等于 main |
| `customer-issues/get___records__delay-page1.json` | $.data.records[*].assigneeName×17; $.data.records[*].authorName×16; $.data.records[*].bugStatus×8; $.data.records[*].category×2; $.data.records[*].createdAt×17; $.data.records[*].customerNames×15; $.data.records[*].delayIssue×4; $.data.records[*].functionName×17; $.data.records[*].handlerName×17; $.data.records[*].illegal×10; $.data.records[*].illegalReason×12; $.data.records[*].issueId×17; $.data.records[*].issueIid×17; $.data.records[*].issueLink×17; $.data.records[*].labels.length×12; $.data.records[*].labels[*]×53; $.data.records[*].milestoneTitle×9; $.data.records[*].moduleNames×14; $.data.records[*].plannedMergeVersionBranch×2; $.data.records[*].priorityLevel×6; $.data.records[*].resolveDelayed×4; $.data.records[*].responseDelayed×3; $.data.records[*].severityLevel×14; $.data.records[*].title×17; $.data.records[*].updatedAt×17; $.data.total×1 | main 已批准业务值 | main 已批准 cbca4eeb/d3aa90f0 的延期记录集与字段（total 474→795） | canonicalReadView；目录掩码: $.data.records[*].retentionHours | 保留 main 业务值并叠加读取帧；不得整文件取 head | 合并后重新生成，记录集/字段等于 main |
| `customer-issues/get___records_export__cc-product.json` | $.sheets[*].rows.*.J×36 | main 已批准业务值 | main 已批准 eb9b2f8b 的 J 列“数据异常”状态（37 行） | 目录掩码: $..U | 保留 main 单元格值；U 列墙钟掩码不变 | 严格 compare 保留 main J 列值 |
| `customer-issues/get___records_export__delay.json` | $.sheets[*].rows.*×321; $.sheets[*].rows.*.A×11; $.sheets[*].rows.*.B×463; $.sheets[*].rows.*.C×430; $.sheets[*].rows.*.D×471; $.sheets[*].rows.*.E×471; $.sheets[*].rows.*.F×432; $.sheets[*].rows.*.G×466; $.sheets[*].rows.*.H×5; $.sheets[*].rows.*.I×311; $.sheets[*].rows.*.K×340; $.sheets[*].rows.*.L×69; $.sheets[*].rows.*.M×307; $.sheets[*].rows.*.N×466; $.sheets[*].rows.*.O×214; $.sheets[*].rows.*.P×111; $.sheets[*].rows.*.R×470 | main 已批准业务值 | main 已批准 cbca4eeb/eb9b2f8b 的延期导出记录/字段 | 目录掩码: $..U | 保留 main 业务值；U 列墙钟掩码不变 | 合并后重新生成，导出记录/字段等于 main |
| `customer-issues/get___records_filter-options__delay.json` | $.data.assigneeNames.length×1; $.data.assigneeNames[*].label×86; $.data.assigneeNames[*].value×86; $.data.bugStatuses.length×1; $.data.bugStatuses[*].label×4; $.data.bugStatuses[*].value×4; $.data.fixUsers.length×1; $.data.fixUsers[*].label×6; $.data.fixUsers[*].value×6; $.data.functionNames.length×1; $.data.functionNames[*].label×170; $.data.functionNames[*].value×170; $.data.handlerNames.length×1; $.data.handlerNames[*].label×86; $.data.handlerNames[*].value×86; $.data.moduleNames.length×1; $.data.moduleNames[*].label×13; $.data.moduleNames[*].value×13; $.data.reasonCategories.length×1; $.data.reasonCategories[*].label×12; $.data.reasonCategories[*].value×12 | main 已批准业务值 | main 已批准 cbca4eeb/eb9b2f8b 的延期过滤口径（负责人 98→106、functionNames 170→296） | canonicalReadView | 保留 main 业务值并叠加读取帧 | 合并后重新生成，候选集合等于 main |
| `customer-issues/get___records_rule-explanation__delay.json` | $.data.flowSteps[*].outputCount×1 | main 已批准业务值 | main 已批准 cbca4eeb/d3aa90f0 的延期规则 outputCount（474→795） | canonicalReadView | 保留 main 业务值并叠加读取帧 | 合并后重新生成，outputCount 等于 main |
| `customer-issues/post___illegal-records_refresh-one__first-issue__issue_fact.json` | $.rows[*].business_order_id×1; $.rows[*].created_by_publication_id×1; $.rows[*].publication_epoch_id×1; $.rows[*].visible_from×1; $.rows[*].visible_to×1 | 读发布协议/物理列 | 物理表新增 5 个发布列（business_order_id/publication_epoch_id/created_by_publication_id/visible_from/visible_to） | 表列掩码: fact_refreshed_at, created_at, updated_at | 保留 head；物理历史与当前可见行分开 | 严格 compare 保留列；响应可见行数不变 |
| `database-browser/get___tables__default.json` | $.data[*].publishedReadRequired×43 | 读发布协议/物理列 | 新增 publishedReadRequired 协议字段（43 张表） | 目录掩码: $.data[*].lastSyncTime | 保留 head 协议字段；逐表核对必需表标记 | 严格 compare 保留字段；非业务值 |
| `facts/get___build-tasks_latest__default.json` | $.data.message×1 | 读发布协议/物理列 | 消息新增“候选事实已构建，等待统一发布”前缀 | 目录掩码: $.data.startedAt, $.data.finishedAt, $.data.createdAt, $.data.updatedAt | 保留 head；由发布流程状态决定 | 严格 compare 保留消息；确认状态语义 |
| `facts/get___issue-diagnostics__default.json` | $.data.customerIssue.resolveDelayedCount×1; $.data.customerIssue.responseDelayedCount×1; $.data.overall.resolveDelayedCount×1; $.data.overall.responseDelayedCount×1 | main 已批准业务值 | main 已批准 cbca4eeb 的延期诊断计数（280/261→582/754） | 目录掩码: $.data.generatedAt | 保留 main 业务值 | 合并后重新生成，计数等于 main |
| `gitlab-sync/get___status__by-config.json` | $.data.logs[*].completedTableCount×1; $.data.logs[*].eventTrail[*].message×3; $.data.logs[*].message×1; $.data.logs[*].tableCount×1 | 表数量/同步（`3fd8d2bc` 读发布任务口径） | 6→16 = 3 事实 + 6 基础投影 + 7 客户里程碑范围组投影；main 仅 3+3 | 目录掩码: $.data.config.createdAt, $.data.config.updatedAt, $.data.config.dbPort, $.data.config.lastFullSyncAt, $.data.logs[*].runId, $.data.logs[*].queuedAt, $.data.logs[*].startedAt, $.data.logs[*].finishedAt, $.data.logs[*].latestProgressAt, $.data.logs[*].eventTrail[*].createdAt | 保留 head；按 3.3 的任务组成核对，不新增掩码 | 严格 compare 保留 16/16，逐行任务均 SUCCESS |
| `quality-board/get___rd_code-review-records_export__cc2026r4.json` | $.sheets[*].rows.*×158; $.sheets[*].rows.*.A×346; $.sheets[*].rows.*.AC×272; $.sheets[*].rows.*.AD×331; $.sheets[*].rows.*.AE×25; $.sheets[*].rows.*.AF×4; $.sheets[*].rows.*.AG×11; $.sheets[*].rows.*.C×317; $.sheets[*].rows.*.D×35; $.sheets[*].rows.*.E×353; $.sheets[*].rows.*.F×353; $.sheets[*].rows.*.G×340; $.sheets[*].rows.*.H×311; $.sheets[*].rows.*.I×247; $.sheets[*].rows.*.J×353; $.sheets[*].rows.*.K×256; $.sheets[*].rows.*.L×230; $.sheets[*].rows.*.M×345; $.sheets[*].rows.*.N×338; $.sheets[*].rows.*.O×51; $.sheets[*].rows.*.P×47; $.sheets[*].rows.*.Q×2; $.sheets[*].rows.*.R×29; $.sheets[*].rows.*.S×11; $.sheets[*].rows.*.T×87; $.sheets[*].rows.*.U×351; $.sheets[*].rows.*.V×350; $.sheets[*].rows.*.W×90; $.sheets[*].rows.*.X×90; $.sheets[*].rows.*.Y×128; $.sheets[*].rows.*.Z×8 | main 已批准业务值 | main 已批准 b07e1985 的质量看板导出记录集 | 无（对象键序/语义比较） | 保留 main 单元格值；不整文件取 head | 合并后重新生成，导出值等于 main |
| `quality-board/get___rd_code-review-records_export__default.json` | $.sheets[*].rows.*×335; $.sheets[*].rows.*.A×2890; $.sheets[*].rows.*.AC×2594; $.sheets[*].rows.*.AD×2567; $.sheets[*].rows.*.AE×237; $.sheets[*].rows.*.AF×7; $.sheets[*].rows.*.AG×41; $.sheets[*].rows.*.C×2651; $.sheets[*].rows.*.D×40; $.sheets[*].rows.*.E×2896; $.sheets[*].rows.*.F×2896; $.sheets[*].rows.*.G×2795; $.sheets[*].rows.*.H×2424; $.sheets[*].rows.*.I×2227; $.sheets[*].rows.*.J×2896; $.sheets[*].rows.*.K×2236; $.sheets[*].rows.*.L×1740; $.sheets[*].rows.*.M×2812; $.sheets[*].rows.*.N×2697; $.sheets[*].rows.*.O×379; $.sheets[*].rows.*.P×307; $.sheets[*].rows.*.Q×26; $.sheets[*].rows.*.R×171; $.sheets[*].rows.*.S×239; $.sheets[*].rows.*.T×807; $.sheets[*].rows.*.U×2823; $.sheets[*].rows.*.V×2823; $.sheets[*].rows.*.W×840; $.sheets[*].rows.*.X×836; $.sheets[*].rows.*.Y×328; $.sheets[*].rows.*.Z×6 | main 已批准业务值 | main 已批准 b07e1985 的质量看板导出记录集 | 无（对象键序/语义比较） | 保留 main 单元格值；不整文件取 head | 合并后重新生成，导出值等于 main |
| `quality-board/get___rd_dashboard__cc-cc2026r4.json` | $.data.authorDefectDensityRows.length×1; $.data.authorDefectDensityRows[*].name×45; $.data.authorDefectDensityRows[*].value×26; $.data.frequencyCodeSubmissionRows.length×1; $.data.frequencyCodeSubmissionRows[*].name×43; $.data.frequencyCodeSubmissionRows[*].value×45 | main 已批准业务值 | main 已批准 b07e1985 的作者缺陷密度/频次行（49→50） | canonicalReadView | 保留 main 业务值并叠加读取帧 | 合并后重新生成，行/值等于 main |
| `quality-board/get___rd_dashboard__cc-default.json` | $.data.authorDefectDensityRows.length×1; $.data.authorDefectDensityRows[*].name×68; $.data.authorDefectDensityRows[*].value×48; $.data.frequencyCodeSubmissionRows.length×1; $.data.frequencyCodeSubmissionRows[*].name×66; $.data.frequencyCodeSubmissionRows[*].value×59 | main 已批准业务值 | main 已批准 b07e1985 的作者缺陷密度/频次行（73→75） | canonicalReadView | 保留 main 业务值并叠加读取帧 | 合并后重新生成，行/值等于 main |
| `question-metrics/post___illegal-records_refresh-one__first-issue__issue_fact.json` | $.rows.length×1; $.rows[*].business_order_id×1; $.rows[*].created_by_publication_id×1; $.rows[*].id×1; $.rows[*].publication_epoch_id×1; $.rows[*].visible_from×1; $.rows[*].visible_to×1 | 读发布协议/物理列 | 物理表新增发布列，且物理行 1→2（历史行 id=1201 + 当前可见行 id=1） | 表列掩码: fact_refreshed_at, created_at, updated_at | 保留 head；响应仅可见 1 行，旧业务值不改 | 严格 compare 保留物理历史；页面/接口可见 1 行 |
| `review-data/get___records_filter-options__default.json` | $.data.reviewExperts[*].label×286; $.data.reviewExperts[*].value×286; $.data.reviewOwners[*].label×284; $.data.reviewOwners[*].value×284 | 人员/顺序（`3fd8d2bc` 固定 tie-breaker 并绑定冻结数据集） | 评审专家 479、负责人 459 成员集合一致，顺序变化；按 3.1 的冻结夹具重放与 head 逐元素相等 | canonicalReadView；镜像/历史查询的 name tie-breaker 与 `LinkedHashSet` 首现顺序 | 保留 head 顺序，不新增 ignore-array-order | 严格 compare 通过；重放指纹 `f5ce13e4…`/`62232c40…` 不变 |
| `statistic-boards/get___{boardKey}__customer-issue-customer-statistics.json` | $.data.rows[*].cells[*].detailParams.sourceVersion×5680; $.data.rows[*].cells[*].displayValue×55; $.data.rows[*].cells[*].numericValue×55 | main 已批准业务值 | main 已批准 cbca4eeb/d3aa90f0/eb9b2f8b 的 38 个单元格值（3→0 等），另有 detailParams.sourceVersion 读发布绑定 | canonicalReadView；目录掩码: $.data.meta.generatedAt, $.data.meta.queryDurationMs | 保留 main 业务值；sourceVersion 使用真实绑定占位，不递归删除 | 合并后重新生成，业务值等于 main，sourceVersion 经真库绑定核验 |
| `statistic-boards/get___{boardKey}__customer-issue-defect-cause.json` | $.data.definition.filters[*].options[*].label×63; $.data.definition.filters[*].options[*].value×63 | 人员/顺序（`3fd8d2bc` 改为 `business_order_id, id`） | 人员筛选项集合一致（filters[9] 19 项、filters[10] 74 项）；按 3.2 的业务根首现顺序与首现去重得到 head 序列 | canonicalReadView；目录掩码: $.data.meta.generatedAt, $.data.meta.queryDurationMs；`business_order_id` 不可变触发器 | 保留 head 顺序，不新增 ignore-array-order | 严格 compare 通过；顺序指纹 `946a97cd…`/`885c0c83…` 不变 |
| `statistic-boards/get___{boardKey}__customer-issue-defect-summary.json` | $.data.definition.filters[*].options.length×1; $.data.definition.filters[*].options[*].label×4; $.data.definition.filters[*].options[*].value×4 | main 已批准业务值 | main 已批准 eb9b2f8b 新增“数据异常”筛选项（15→14） | canonicalReadView；目录掩码: $.data.meta.generatedAt, $.data.meta.queryDurationMs | 保留 main 业务值并叠加读取帧 | 合并后重新生成，筛选项等于 main |
| `statistic-boards/get___{boardKey}__customer-issue-delay-issues.json` | $.data.rows[*].cells[*].displayValue×28; $.data.rows[*].cells[*].numericValue×28 | main 已批准业务值 | main 已批准 cbca4eeb 的延期看板计数 | canonicalReadView；目录掩码: $.data.meta.generatedAt, $.data.meta.queryDurationMs | 保留 main 业务值并叠加读取帧 | 合并后重新生成，计数等于 main |
| `statistic-boards/get___{boardKey}_control-options__customer-issue-customer-statistics.json` | $.data.sourceVersion×1 | 读发布协议/物理列 | data.sourceVersion 由 fact-projection 改为 publication 绑定占位 | canonicalReadView | 保留 head；按真实绑定来源核验，不递归删除版本/时间字段 | 严格 compare 保留占位；真库绑定核验通过 |
| `statistic-boards/get___{boardKey}_export__customer-issue-delay-issues.json` | $.sheets[*].rows.*.B×9; $.sheets[*].rows.*.C×2; $.sheets[*].rows.*.E×9; $.sheets[*].rows.*.F×4; $.sheets[*].rows.*.I×4 | main 已批准业务值 | main 已批准 cbca4eeb 的延期导出计数 | 无（对象键序/语义比较） | 保留 main 单元格值 | 合并后重新生成，导出值等于 main |
| `statistic-boards/get___{boardKey}_rule-explanation__customer-issue-customer-statistics.json` | $.data.flowSteps[*].outputCount×1; $.data.flowSteps[*].samples[*].detail×2; $.data.flowSteps[*].samples[*].label×2 | main 已批准业务值 | main 已批准 cbca4eeb/d3aa90f0/eb9b2f8b 的规则 outputCount（110→142）与样本 | canonicalReadView | 保留 main 业务值并叠加读取帧 | 合并后重新生成，outputCount/样本等于 main |

## 重点证据（旧值 → 新值）

仅列各组代表值；完整字段组见上表与 `.tmp/n5-t1-20261103/business-details.txt`（不提交）。

| 文件 | 代表字段与值 |
| --- | --- |
| `analytics-dashboards/get___{dashboardKey}__quality-rd.json` | `$.data.charts[*].option.series[*].data.length` ×2：main=`73` → head=`75`<br>`$.data.charts[*].option.series[*].data[*].detailParams.authorName` ×134：main=`'guanlixiang'` → head=`'张明霞'`<br>`$.data.charts[*].option.series[*].data[*].pointKey` ×134：main=`'author-density:guanlixiang'` → head=`'author-density:张明霞'` |
| `analytics-dashboards/get___{dashboardKey}_details_{viewKey}__quality-rd-quality-code-review-records.json` | `$.data.records[*].addedLines` ×20：main=`2` → head=`1`<br>`$.data.records[*].authorName` ×19：main=`'曹步勇'` → head=`'李令博'`<br>`$.data.records[*].defectCount` ×4：main=`1` → head=`0` |
| `analytics-dashboards/get___{dashboardKey}_rules__quality-rd.json` | `$.data.rules[*].scope` ×2：main=`'CC2026R3 的 CC：已合并代码走查数据，普通版本读取 dev，SP 版本读取对应精确发布分支，组合版本按成员共享；按合并请求去重，重复行缺陷数相加、新增行数取第一条'` → head=`'CC2026R3 的 CC 已合并 dev 分支数据；按合并请求去重，重复行缺陷数相加、新增行数取第一条'` |
| `bi/get___customer-issues__default.json` | `$.data.data.defectMetrics[*].denominator` ×2：main=`164` → head=`169`<br>`$.data.data.defectMetrics[*].numerator` ×3：main=`5` → head=`0`<br>`$.data.data.defectMetrics[*].percentage` ×2：main=`19.51` → head=`19.53` |
| `bi/get___integration-test__default.json` | `$.code` ×1：main=`'00000'` → head=`'READ_VIEW_UNAVAILABLE'`<br>`$.data` ×1：main=`{'pageKey': 'integration-test', 'status': 'INCOMPLETE', 'ruleVersion': 'bi-cat-test-v2', 'sections': [{'key': 'test-quality', 'label': '测试质量达成', 'status': 'INCOMPLETE', 'message': ` → head=`None`<br>`$.message` ×1：main=`'成功'` → head=`'该页面尚无完整产出，请联系管理员完成首次数据更新'` |
| `bi/get___unit-test__default.json` | `$.code` ×1：main=`'00000'` → head=`'READ_VIEW_UNAVAILABLE'`<br>`$.data` ×1：main=`{'pageKey': 'unit-test', 'status': 'INCOMPLETE', 'ruleVersion': 'bi-cat-test-v2', 'sections': [{'key': 'test-quality', 'label': '测试质量达成', 'status': 'INCOMPLETE', 'message': '当前产品版本` → head=`None`<br>`$.message` ×1：main=`'成功'` → head=`'该页面尚无完整产出，请联系管理员完成首次数据更新'` |
| `customer-issues/get___records__cc-product-default.json` | `$.data.records[*].resolveDelayed` ×11：main=`False` → head=`True`<br>`$.data.records[*].responseDelayed` ×12：main=`False` → head=`True` |
| `customer-issues/get___records__delay-default.json` | `$.data.records[*].assigneeName` ×17：main=`''` → head=`'杨晓雨'`<br>`$.data.records[*].authorName` ×16：main=`'Administrator'` → head=`'张金花'`<br>`$.data.records[*].bugStatus` ×8：main=`'历史遗留 & 申请延期'` → head=`'未设定议题状态'` |
| `customer-issues/get___records__delay-page1.json` | `$.data.records[*].assigneeName` ×17：main=`''` → head=`'杨晓雨'`<br>`$.data.records[*].authorName` ×16：main=`'Administrator'` → head=`'张金花'`<br>`$.data.records[*].bugStatus` ×8：main=`'历史遗留 & 申请延期'` → head=`'未设定议题状态'` |
| `customer-issues/get___records_export__cc-product.json` | `$.sheets[*].rows.*.J` ×36：main=`'数据异常'` → head=`'未设定议题状态'` |
| `customer-issues/get___records_export__delay.json` | `$.sheets[*].rows.*` ×321：main=`'<missing>'` → head=`{'A': '2026-07-06', 'B': '2026-04-24', 'C': '工程图、数据交换', 'D': '#2017', 'E': '【剖视图】特定模型剖视图中存在多余线——A7 Solutions', 'F': '陈艺珲', 'G': '谭海燕', 'H': '未关闭', 'I': '未设定议题状态', 'J': '', 'K': '二级`<br>`$.sheets[*].rows.*.A` ×11：main=`'2026-07-01'` → head=`'2026-07-06'`<br>`$.sheets[*].rows.*.B` ×463：main=`'2026-06-02'` → head=`'2026-07-05'` |
| `customer-issues/get___records_filter-options__delay.json` | `$.data.assigneeNames.length` ×1：main=`98` → head=`106`<br>`$.data.assigneeNames[*].label` ×86：main=`'刘旭阳'` → head=`'刘悦'`<br>`$.data.assigneeNames[*].value` ×86：main=`'刘旭阳'` → head=`'刘悦'` |
| `customer-issues/get___records_rule-explanation__delay.json` | `$.data.flowSteps[*].outputCount` ×1：main=`474` → head=`795` |
| `customer-issues/post___illegal-records_refresh-one__first-issue__issue_fact.json` | `$.rows[*].business_order_id` ×1：main=`'<missing>'` → head=`'1'`<br>`$.rows[*].created_by_publication_id` ×1：main=`'<missing>'` → head=`'GITLAB_FACT/default/ISSUE//sequence:1'`<br>`$.rows[*].publication_epoch_id` ×1：main=`'<missing>'` → head=`'GITLAB_FACT/default/ISSUE//epoch-start:0'` |
| `database-browser/get___tables__default.json` | `$.data[*].publishedReadRequired` ×43：main=`'<missing>'` → head=`True` |
| `facts/get___build-tasks_latest__default.json` | `$.data.message` ×1：main=`'集成测试事实已完成一致性重建，当前源无有效数据'` → head=`'候选事实已构建，等待统一发布；集成测试事实已完成一致性重建，当前源无有效数据'` |
| `facts/get___issue-diagnostics__default.json` | `$.data.customerIssue.resolveDelayedCount` ×1：main=`280` → head=`582`<br>`$.data.customerIssue.responseDelayedCount` ×1：main=`261` → head=`754`<br>`$.data.overall.resolveDelayedCount` ×1：main=`280` → head=`582` |
| `gitlab-sync/get___status__by-config.json` | `$.data.logs[*].completedTableCount` ×1：main=`6` → head=`16`<br>`$.data.logs[*].eventTrail[*].message` ×3：main=`'全量事实构建完成（1200 行）'` → head=`'全量候选事实构建完成，等待必需投影（1200 行）'`<br>`$.data.logs[*].message` ×1：main=`'全量事实构建完成（0 行）'` → head=`'全量候选事实构建完成，等待必需投影（0 行）'` |
| `quality-board/get___rd_code-review-records_export__cc2026r4.json` | `$.sheets[*].rows.*` ×158：main=`'<missing>'` → head=`{'A': '2026-05-09', 'B': 'CC2026R4', 'C': '工程图', 'D': 'MERGED', 'E': '29337', 'F': 'fix: [工程图]修改注解数据解析', 'G': '郎琳琳', 'H': '无需走查扫描', 'I': '张华磊', 'J': '2026-05-09 13:30:48', 'K': '张华`<br>`$.sheets[*].rows.*.A` ×346：main=`'2026-06-09'` → head=`'2026-06-22'`<br>`$.sheets[*].rows.*.AC` ×272：main=`'大国工匠'` → head=`'工程图'` |
| `quality-board/get___rd_code-review-records_export__default.json` | `$.sheets[*].rows.*` ×335：main=`'<missing>'` → head=`{'A': '2025-11-18', 'B': 'CC2026R3', 'C': '装配', 'D': 'MERGED', 'E': '24787', 'F': 'refactor: [装配]坐标系', 'G': '傅培钰', 'H': '田川', 'I': '田川', 'J': '2025-11-18 10:41:37', 'K': '田川', 'L':`<br>`$.sheets[*].rows.*.A` ×2890：main=`'2026-06-02'` → head=`'2026-06-23'`<br>`$.sheets[*].rows.*.AC` ×2594：main=`'--'` → head=`'博诚电气'` |
| `quality-board/get___rd_dashboard__cc-cc2026r4.json` | `$.data.authorDefectDensityRows.length` ×1：main=`49` → head=`50`<br>`$.data.authorDefectDensityRows[*].name` ×45：main=`'张佳旺'` → head=`'张明霞'`<br>`$.data.authorDefectDensityRows[*].value` ×26：main=`171.5` → head=`145.29` |
| `quality-board/get___rd_dashboard__cc-default.json` | `$.data.authorDefectDensityRows.length` ×1：main=`73` → head=`75`<br>`$.data.authorDefectDensityRows[*].name` ×68：main=`'guanlixiang'` → head=`'张明霞'`<br>`$.data.authorDefectDensityRows[*].value` ×48：main=`90.91` → head=`75.69` |
| `question-metrics/post___illegal-records_refresh-one__first-issue__issue_fact.json` | `$.rows.length` ×1：main=`1` → head=`2`<br>`$.rows[*].business_order_id` ×1：main=`'<missing>'` → head=`'1'`<br>`$.rows[*].created_by_publication_id` ×1：main=`'<missing>'` → head=`'GITLAB_FACT/default/ISSUE//sequence:1'` |
| `review-data/get___records_filter-options__default.json` | `$.data.reviewExperts[*].label` ×286：main=`'刘纪凯'` → head=`'CI Robot'`<br>`$.data.reviewExperts[*].value` ×286：main=`'刘纪凯'` → head=`'CI Robot'`<br>`$.data.reviewOwners[*].label` ×284：main=`'刘纪凯'` → head=`'CI Robot'` |
| `statistic-boards/get___{boardKey}__customer-issue-customer-statistics.json` | `$.data.rows[*].cells[*].detailParams.sourceVersion` ×5680：main=`'fact-projection:v1:10e48c775d731d1001e598b7033308161d37e80a762d4bdc46af3c6aa4f0fbc2'` → head=`'<verified-runtime-sourceVersion>'`<br>`$.data.rows[*].cells[*].displayValue` ×55：main=`'3'` → head=`'0'`<br>`$.data.rows[*].cells[*].numericValue` ×55：main=`3` → head=`0` |
| `statistic-boards/get___{boardKey}__customer-issue-defect-cause.json` | `$.data.definition.filters[*].options[*].label` ×63：main=`'杨依景'` → head=`'张月'`<br>`$.data.definition.filters[*].options[*].value` ×63：main=`'杨依景'` → head=`'张月'` |
| `statistic-boards/get___{boardKey}__customer-issue-defect-summary.json` | `$.data.definition.filters[*].options.length` ×1：main=`15` → head=`14`<br>`$.data.definition.filters[*].options[*].label` ×4：main=`'数据异常'` → head=`'未复现'`<br>`$.data.definition.filters[*].options[*].value` ×4：main=`'数据异常'` → head=`'未复现'` |
| `statistic-boards/get___{boardKey}__customer-issue-delay-issues.json` | `$.data.rows[*].cells[*].displayValue` ×28：main=`'3'` → head=`'4'`<br>`$.data.rows[*].cells[*].numericValue` ×28：main=`3` → head=`4` |
| `statistic-boards/get___{boardKey}_control-options__customer-issue-customer-statistics.json` | `$.data.sourceVersion` ×1：main=`'fact-projection:v1:10e48c775d731d1001e598b7033308161d37e80a762d4bdc46af3c6aa4f0fbc2'` → head=`'<verified-runtime-sourceVersion>'` |
| `statistic-boards/get___{boardKey}_export__customer-issue-delay-issues.json` | `$.sheets[*].rows.*.B` ×9：main=`'7'` → head=`'10'`<br>`$.sheets[*].rows.*.C` ×2：main=`'1'` → head=`'2'`<br>`$.sheets[*].rows.*.E` ×9：main=`'8'` → head=`'11'` |
| `statistic-boards/get___{boardKey}_rule-explanation__customer-issue-customer-statistics.json` | `$.data.flowSteps[*].outputCount` ×1：main=`110` → head=`142`<br>`$.data.flowSteps[*].samples[*].detail` ×2：main=`'【分割】创建分割3，装饰螺纹效果丢失——售前 \| 模块: 特征 \| 严重程度: LEVEL2 \| 优先级: P1'` → head=`'【退出编辑零部件】Topdown下编辑零部件尺寸，退出时加载超时，页面崩溃——新佰人（自测） \| 模块: 工具、装配 \| 严重程度: LEVEL1 \| 优先级: P1'`<br>`$.data.flowSteps[*].samples[*].label` ×2：main=`'#1963 CCProduct'` → head=`'#2072 CCProduct'` |

## 结论与停止条件

- 31 份业务差异全部归因：21 份 main 已批准业务值（保留 main + 叠加读取帧）、2 份人员/顺序（按 3.1/3.2 的实际 SQL 与 tie-breaker 得到 head 有序结果）、5 份读发布协议/物理列、2 份 CAT 无完整点（409）、1 份表数量/同步（6→16，按 3.3 的任务组成与完成判据）。
- 66 份仅读取帧、38 份掩码/键序后等价；无未归因业务变化。
- 停止条件：任一 main 已批准值被整文件覆盖、CAT 用例未声明 409、人员/选项集合或顺序指纹与 3.1/3.2 不一致、16 表组成或完成数不符、sourceVersion 未按真实绑定核验、或出现未列入本表的业务字段变化。
- 本文件只交裁定，不执行 golden/update；T2 成功样本与 T3 升级开关另行提交。