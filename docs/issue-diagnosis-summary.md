# 内网测试问题根因同步

> 更新时间：2026-06-26
> 依据范围：本文件只同步本次按代码重新确认的原因和用户已补充的信息，不沿用旧诊断文档中的未验证推测。

## 总览

| 问题 | 当前原因判断 | 状态 |
| --- | --- | --- |
| 客户问题模块大部分页面空表格、连模块名也没有 | 客户问题页面默认注入顶部“测试阶段”筛选，筛选按 `milestone_title` / `testing_phase` 匹配；如果内网 CC_Product 议题的里程碑与系统设置父级阶段不一致，`CustomerIssueTestingPhaseFilterSupport` 会把数据全部过滤掉。部分页面又从过滤后的结果生成模块行，导致模块目录也为空。 | 已确认代码原因 |
| 客户问题延期问题只有“未设定模块”且数量为 0 | 延期问题页先用客户问题范围取 `rowSources`，再按默认测试阶段筛选；若默认测试阶段不匹配，只有兜底的“未设定模块”行会被保留，延期计数来自 `finalSources`，因此全为 0。 | 已确认代码原因 |
| 系统测试/系统测试非法数据比老平台多 | 新平台系统测试范围同时命中 `testing_phase`、`system_test_label` 和 `label_names` 中包含“系统测试/回归测试”的记录；老平台记录列表主要按项目和测试阶段定义展开后的阶段过滤。范围更宽会导致非法记录偏多。 | 已确认代码原因 |
| 系统测试/议题查询少约 2000 条 | 议题查询使用 `Scope.ALL`，但测试阶段条件走 `phase_filter_value`，和系统测试非法数据使用 `Scope.SYSTEM_TEST + testing_phase` 的口径不同；同时默认项目和阶段展开路径不完全一致，导致同一测试阶段下数量不一致。 | 已确认代码原因 |
| 系统测试/缺陷原因分析、议题阶段统计 15 秒超时 | 这两个统计看板不是 SQL 分页加载。接口先从 `issue_fact` 全量加载符合过滤的事实数据，再在 Java 内存中做规则流、模块/阶段聚合和明细分页；前端请求默认 15 秒超时，因此数据量大时会超时。 | 已确认代码原因 |
| 单表刷新、手动增量同步突然很慢、拉取量很大 | 增量任务只带 `last_watermark_at`，没有带 `last_cursor_pk`，SQL 边界为 `updated_at >= watermark`，会反复拉取同一水位时间戳上的历史行。手动增量同步还会规划全部白名单表，不是只刷新当前页面表。 | 已确认并已修正同步边界 |
| 页面“刷新最新数据”比预期重 | 统计页刷新不是只刷新表格结果，而是先刷新背后的 GitLab 镜像原始表，再重建事实表。例如系统测试/客户问题会刷新 `issues/projects/users/label_links/labels/notes`，代码走查会刷新 MR 相关镜像表，然后重建 `issue_fact` 或 `merge_request_fact`。 | 已确认代码原因 |
| 事实重建有时从增量退化成全量 | `FactBuildService` 在发现既有事实缺少搜索索引或阶段派生字段时，会让 `changedSince = null`，下一次事实构建就不带增量谓词，表现为全量重建。升级后旧事实表缺字段或索引为空时尤其容易触发。 | 已确认代码原因 |
| 代码走查非法数据少约 6000 条 | 不能再归因于 MR 29874 模块为空。用户已确认 MR 29874 在镜像库 `merge_request` 和 `merge_request_fact` 中存在，且模块名为“平台”。当前只能确认新平台非法判断依赖 `review_exception_reason`、`scan_status`、`scan_bug_count`、`annotation_rate_result`、`bug_count_result`、`project_name/module_name` 占位值和 GitLab 报错字段；需要内网按这些字段核验缺失样本是否命中老平台非法条件但未命中新平台谓词。 | 已纠正旧结论，需内网字段核验 |

## 关键代码证据

### 1. 加载超时不是默认分页失效，而是统计看板全量聚合

- 前端默认请求超时是 `15_000ms`：`frontend/src/api-client/request.ts`。
- 统计看板主接口没有 `page/size` 参数；表格分页是前端本地 `slice`：`frontend/src/composables/useStatisticBoardTableState.ts`。
- `SystemTestDefectCauseBoardService` 和 `SystemTestPhaseStatisticsBoardService` 都是 `loadSources(...) -> buildRuleFlowSnapshot(...) -> PageSliceSupport.slice(...)`，即先全量加载事实再聚合。
- 记录类页面不一样：`IssueFactRecordRepository.findPage(...)` 会先 `count(*)`，再 SQL `limit/offset`。

结论：缺陷原因分析、议题阶段统计的 15 秒超时主要来自统计看板全量事实加载和 Java 聚合，不是“所有表格默认分页失效”。

### 2. 刷新慢来自两层放大

第一层是镜像刷新放大：

- `IssueFactRealtimeRefreshService`、系统测试统计看板和客户问题统计看板会刷新 `issues/projects/users/label_links/labels/notes`。
- `CodeReviewIllegalRecordService` 会刷新 `merge_requests/merge_request_metrics/merge_request_reviewers/merge_request_assignees/label_links/labels/projects/namespaces/users`。
- 手动增量同步 `INCREMENTAL_SYNC` 会在 `SyncRunTablePlanningService.shouldPlanFromWhitelist(...)` 中规划全部白名单表。

第二层是水位边界放大：

- 原逻辑新建增量任务时只设置 `watermark_at = state.lastWatermarkAt`，没有设置 `cursor_updated_at/cursor_pk`。
- 源库扫描 SQL 使用 `updated_at >= watermark`，导致同一 `updated_at` 水位上的历史行被重复拉取。
- 本次已改为：任务规划时带上 `lastWatermarkAt + lastCursorPk`；执行时无更新先用 `max(updated_at)` 直接 0 行返回；无游标的首批增量改成 `updated_at > watermark`；SQL 时间字面量保留到微秒，避免把同一秒内旧数据重新扫入。

### 3. 客户问题空表格的直接触发点

客户问题页面按规则需要顶部“测试阶段”切换，但当前实现默认选择系统设置中第一个启用父级阶段：

- `CustomerIssueTestingPhaseFilterSupport.applyDefaultTestingPhase(...)`
- 匹配逻辑：`milestone_title` 或 `testing_phase` 等于父级，或能通过父级展开匹配子级。

如果内网 CC_Product 的里程碑不是这个父级阶段，默认筛选会把客户问题数据过滤空。客户问题缺陷汇总、按功能展示等页面又从 `finalSources` 生成模块行，所以不是“只有数量为 0”，而是连模块行都没有。延期问题页因为有“未设定模块”兜底，所以表现为只剩一行且全 0。

### 4. 系统测试非法数据偏多、议题查询偏少的口径差

- 系统测试非法数据走 `IssueFactRecordPageQuery.Scope.SYSTEM_TEST`，范围条件会匹配 `testing_phase/system_test_label/label_names` 里的系统测试或回归测试。
- 议题查询走 `IssueFactRecordPageQuery.Scope.ALL`，默认项目是 CrownCAD，但不使用同一套系统测试 scope；阶段筛选默认走 `phase_filter_value`。

因此这两个页面在新平台内部也不是完全同一口径，更不用说和老平台记录列表口径比较。非法数据偏多和议题查询少 2000 条都可以由这组范围差异解释。

### 5. 代码走查 MR 29874 的纠正

旧结论“MR 29874 模块为空导致缺失”已经作废。

已确认事实：

- MR 29874 在镜像库 `merge_request` 和 `merge_request_fact` 表中存在。
- MR 29874 的模块名是“平台”。

因此它不会因为 `module_name = '未标注模块名'` 或模块为空而被解释为缺失。下一步只应核对该 MR 在 `merge_request_fact` 中以下字段：

```sql
select source_instance,
       project_id,
       merge_request_iid,
       module_name,
       project_name,
       target_branch,
       merge_request_state,
       merged_at_source,
       review_exception_reason,
       scan_status,
       scan_bug_count,
       annotation_rate_result,
       bug_count_result,
       owner_name,
       reviewer_names
  from merge_request_fact
 where merge_request_iid = 29874;
```

若老平台统计到了它，而新平台没有，优先比较：

- 老平台 `assignee` 中的走查异常值是否正确映射到新平台 `review_exception_reason`。
- 老平台 `sonar_qube_result = 未进行代码扫描/GitLab 接口报错` 是否正确映射到新平台 `scan_status`。
- 老平台 `bug_count_result`、`annotation_rate_result` 是否在新平台事实表中为空、文案不同或来源缺失。
- GitLab 接口报错在老平台检查 `sonar_qube_result/target_branch/assignee`，新平台检查 `scan_status/target_branch/owner_name/reviewer_names`，字段替换可能漏掉部分样本。

## 本次已落地的同步修正

已修改：

- `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunTablePlanningService.java`
- `backend/src/main/java/com/data/collection/platform/service/sync/SyncRunTableTaskExecutor.java`
- `backend/src/main/java/com/data/collection/platform/service/GitlabSourceScanSqlBuilder.java`
- `backend/src/test/java/com/data/collection/platform/service/GitlabExternalDbServiceTest.java`

行为变化：

1. 手动单表刷新、手动增量同步的新任务会从 `sync_run_table_states.last_watermark_at + last_cursor_pk` 之后继续。
2. 非分页中的增量任务会先查询源表 `max(updated_at)`；如果源表最大更新时间没有超过平台保存水位，直接 0 行成功。
3. 无历史游标的增量首批扫描使用 `updated_at > watermark`，避免历史边界行反复被拉取。
4. 增量扫描 SQL 保留 `updated_at` 微秒精度，避免把水位截断到秒后扩大扫描窗口。
5. 已排队但没有 cursor 的旧任务，执行时会从当前表状态补 cursor，降低增量更新包部署后的旧任务风险。

## 暂未在本次修复中处理的项

这些原因已经确认，但本次只修正同步水位边界：

- 统计看板全量加载导致的 15 秒超时，需要改成 SQL 聚合、服务端分页明细或缓存快照。
- 客户问题默认测试阶段把数据过滤空，需要重新确认默认阶段策略：客户问题页应优先按里程碑可用项默认，不能直接套系统测试第一个父级阶段。
- 系统测试非法数据和议题查询需要统一系统测试记录页范围口径。
- 代码走查少 6000 条需要内网按字段核验后，再修正 `merge_request_fact` 字段映射或非法 SQL 谓词。
