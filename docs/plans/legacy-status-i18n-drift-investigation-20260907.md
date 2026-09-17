# 老平台数据枚举值中文化影响面调查（2026-09-07）

## 进度与中间物

- 状态：调查完成，修复方向待用户拍板（方案 A/B/C 见"决策记录"）。未做任何代码修改。
- 产出：本报告 = 完整影响面清单 + 根因链 + 修复方向建议。

## 恢复线索

- 当前阶段：调查完毕，等用户选择修复方案后开实施工作单元。
- 恢复后首条命令：内网 MySQL 验证 `select status, count(*), count(merged_time) from spider_crowncad_data group by status`（确认中文行 merged_time 空置规模）。
- 关联：老平台源码 `D:/projects/spidergitdata-dev`（只读参考）；平台兼容模式链路见 `docs/architecture.md` 兼容模式章节。

## 目标与边界

- 用户原始需求：老平台库 status 从英文 merged 变为中文"已合并"（爬虫用前开发人员 GitLab 账号，界面切中文），代码走查非法数据页 CC 源无数据；排查是否还有同类问题。
- 边界：只调查不修改。

## 证据与根因

根因链（全链闭合）：

1. 老平台爬虫（Java/Jsoup）解析 MR 列表页 `class=issuable-status` 元素可见文本**原样入库**，无归一化（`spidergitdata-dev/src/main/java/com/huayun/service/impl/MergeRequestDAOImpl.java:1613-1620`）→ 界面中文后 status：MERGED→已合并、OPEN→已打开、CLOSED→已关闭；元素缺失兜底 "OPEN"。
2. 新平台兼容模式同步 `CodeReviewMatchModeSyncService.mapLegacyRow:428` 原样搬运 `status` → `merge_request_state`（同一值 :444 还写入 `review_status`）。
3. 转正式导入 `LegacyPlatformFormalImportService:272` 原样搬运进 `merge_request_fact`；`code_review_formal_records` 视图（V20260721_03）CC/DGM 优先取老平台交接数据。
4. 读路径 8 处 `upper(coalesce(merge_request_state,'')) = 'MERGED'` 全部失配 → CC 源零数据。

老平台侧次生损坏（爬虫代码缺陷，状态映射救不回）：

- **merged_time/merged_user_name 不再填充**：老平台爬虫仅当 `status.equals("MERGED")` 才解析合并人/时间（MergeRequestDAOImpl.java:999-1006、1361-1367）→ 中文行这两个字段为空。后果：即使平台做状态映射，这些行 `merged_at_source` 为 null，过不了 `merged_at > 2024-04-01` 过滤，仍不出现。
- **assigneed（被指派人）全变 `--`**：解析依赖英文 title 前缀 "Assignee: " 的 `substring(11)` 固定截取（MergeRequestDAOImpl.java:1525-1532），中文前缀长度不同 → 异常兜底 `--`。平台搬入 `assignee_names`（mapLegacyRow:434）。不触发非法误判（`--` 不在异常值清单），但列表展示退化。

## 平台侧受影响清单（6 个功能面）

| 功能 | 匹配点 | 影响 |
|---|---|---|
| 代码走查非法数据页（列表/筛选选项/导出/规则预览共用 BASE_WHERE） | `CodeReviewIllegalRecordSqlQueryBuilder.java:13` | CC 源无数据（用户已发现） |
| 兼容模式数据浏览/预览 | `CodeReviewMatchModeRecordLoader.java:21`（贯穿全部查询与导出） | 同样零数据 |
| 质量看板-代码走查 | `QualityBoardCodeReviewReadSupport.java:79、553` | 老平台来源统计归零 |
| 多看板总览与分析 | `CodeReviewMultiBoardService.java:157`、`CodeReviewMultiBoardAnalyticsQueryService.java:164` | 同上 |
| 系统测试横向对比导出 | `statistics/SystemTestHorizontalComparisonExportService.java:486` | CC/DGM 代码走查对比数据缺失 |
| BI 看板编码页（兼容模式） | `bi/infrastructure/BiPlatformCodingSourceAdapter.java:89` | 兼容读源编码统计缺失 |

## 排查过、确认不受影响

- 客户议题/系统测试非法数据、质量看板议题统计：`issue_state` 由平台从 GitLab API `state_id`/`closed_at` 数字字段自算英文值（`IssueFactSourceRowMapper:79`、`IntegrationTestFactBuildService:251`）；议题侧老平台走 API state 映射平台常量（IssueServiceImpl.java:520-525）。
- 评审数据 Mongo 兼容链路（review_data_match_mode_*）：老平台业务字段，固定中文值域。
- scan_status / annotation_rate_result / bug_count_result：机器人评论 + 老平台固定中文常量（IllegalTypeList.java），与界面语言无关。
- reviewer_names（走查人）、author、target_branch、module/project_name：评论/JSON API 字段/labels，不依赖界面文本。
- 前端：无按 merged 状态值的匹配（MirrorSyncLogTable 的 MERGED 是平台同步日志状态）。
- 多看板/BI 的 `review_status='COMPLETED'` 匹配：老平台行该字段搬运的也是 status（mapLegacyRow:444），中文化前后都不等于 COMPLETED，行为不变（既有语义，非本次破坏）。

## 决策记录（修复方向，待拍板）

- 方案 A（倾向）：写入侧归一化——`mapLegacyRow` 入口单点映射 已合并→MERGED、已打开→OPEN、已关闭→CLOSED；读路径 8 处零改动；存量靠重新触发兼容模式同步全量覆盖修复。局限：修不了爬虫次生损坏（merged_time 空、assigneed=--）。
- 方案 B（否决倾向）：读侧兼容 8 处 SQL 改 `in ('MERGED','已合并')`——改动分散易漏，中文值域再变还得追着改。
- 方案 C：老平台爬虫源头改 API state——治本但跨遗留项目。
- 无论 A/C：中文行若 merged_time 空置则救不回，需先跑内网验证 SQL 量化规模。
- 方案 A 触及产出行为，实施时须过黄金基线门禁（快照含老平台数据时按流程重建审阅）。

## 风险与假设

- 假设：内网老平台库确实已出现中文 status 行（用户观察"上个月开始"）；规模待验证 SQL 确认。
- 敏感只读数据：spidergitdata-dev 为只读参考，不得进入本仓库构建链路。
