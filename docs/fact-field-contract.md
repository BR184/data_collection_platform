# 事实字段契约

本文档记录 Java 事实构建、SQL 查询字段和前端筛选字段之间的边界。新增事实字段时，先更新本文档，再修改代码和迁移。

## 维护规则

1. Java 负责生成归一化字段、拼音字段、首字母字段和业务分类字段。
2. SQL 负责基于持久化字段过滤、排序、聚合和分页，不临时重算复杂文本索引。
3. Flyway 负责创建字段和索引，`schema.sql` 作为静态比对基准同步维护。
4. 前端筛选字段必须能追溯到后端请求对象和事实字段。
5. 修改搜索字段生成规则时，需要评估是否触发历史数据重建。

## 搜索影子字段

| 事实表 | 字段族 | Java 责任 | SQL 责任 | 前端用途 | 重建要求 |
| --- | --- | --- | --- | --- | --- |
| `review_records` | `search_*` | 生成评审记录全文搜索归一化、紧凑、拼音、首字母字段 | 使用 `GIN/trgm` 索引支持模糊匹配 | 评审数据列表关键词搜索 | 规则变化后需要重建 |
| `review_records` | `title_search_*` | 生成标题搜索影子字段 | 使用 `GIN/trgm` 索引支持标题搜索 | 评审标题关键词搜索 | 规则变化后需要重建 |
| `issue_fact` | `search_*` | 生成议题全文搜索影子字段 | 使用 `GIN/trgm` 索引支持模糊匹配 | 客户问题、系统测试记录搜索 | 规则变化后需要重建 |
| `issue_fact` | `title_search_*` | 生成议题标题搜索影子字段 | 使用 `GIN/trgm` 索引支持标题搜索 | 记录页标题搜索 | 规则变化后需要重建 |
| `issue_fact` | `module_search_*` | 生成模块搜索影子字段 | 使用 `GIN/trgm` 索引支持模块搜索 | 模块条件筛选 | 模块归一化规则变化后需要重建 |
| `issue_fact` | `milestone_search_*` | 生成版本搜索影子字段 | 使用 `GIN/trgm` 索引支持版本搜索 | 版本条件筛选 | 版本归一化规则变化后需要重建 |
| `issue_fact` | `author_search_*` / `assignee_search_*` | 生成人员搜索影子字段 | 使用 `GIN/trgm` 索引支持人员搜索 | 作者、负责人筛选 | 人员归一化规则变化后需要重建 |
| `issue_fact` | `phase_search_*` | 生成阶段搜索影子字段 | 使用 `GIN/trgm` 索引支持阶段搜索 | 测试阶段条件筛选 | 阶段归一化规则变化后需要重建 |
| `merge_request_fact` | `search_*` | 生成合并请求全文搜索影子字段 | 使用 `GIN/trgm` 索引支持模糊匹配 | 代码走查记录搜索 | 规则变化后需要重建 |
| `merge_request_fact` | `owner_search_*` | 生成责任人搜索影子字段 | 使用 `GIN/trgm` 索引支持责任人搜索 | 代码走查责任人筛选 | 人员归一化规则变化后需要重建 |

## 业务分类字段

| 事实表 | 字段 | Java 责任 | SQL 责任 | 前端用途 | 备注 |
| --- | --- | --- | --- | --- | --- |
| `issue_fact` | `category` | 根据标签、标题、备注和范围规则生成缺陷分类 | 按分类过滤和聚合 | 客户问题、系统测试分类展示 | 规则变化需要补充 rule 层测试 |
| `issue_fact` | `reason_category` | 根据标签、标题、备注和原因规则生成缺陷原因分类 | 按原因过滤和聚合 | 缺陷原因分析、记录页原因筛选 | 规则变化需要补充原因映射样例 |
| `issue_fact` | `is_illegal` / `illegal_reason` | 根据字段完整性和业务规则判断非法数据 | 按非法状态过滤、排序和统计 | 非法数据页、待确认提示 | 规则变化需要补充非法原因样例 |
| `issue_fact` | `is_legacy` | 根据时间、状态和标签判断历史遗留 | 按历史遗留过滤和统计 | 记录页、分析页口径说明 | 避免在 SQL 中重复实现 |
| `issue_fact` | `resolve_sla_days` / `resolve_deadline_at` | 根据计划解决时间和响应数据计算 SLA 口径 | 过滤、排序和统计 | 响应效率类页面 | 修改规则前先确认产品口径 |
| `integration_test_fact` | `execute_case` / `pass_case` / `not_pass_case` / `not_pass_case_now` / `problem_case` / `exception_count` / `pass_rate` | 从备注解析结果生成执行、通过、未通过等计数 | 汇总、明细查询和导出 | 集成测试分析页 | 解析规则变化需要补充端到端链路测试 |
| `integration_test_fact` | `parse_status` / `validation_reason` / `legal` | 标记备注解析完整性、校验原因和合法状态 | 明细过滤和待确认提示 | 集成测试分析页 | 校验规则变化需要补充 parser 与 pipeline 测试 |

## 自动守卫

`scripts/check_fact_field_contract.py` 会检查 `schema.sql`、Flyway 最终字段集合和本文档中的关键事实字段是否一致。本脚本已接入 `scripts/verify-local.ps1` 和 CI 的 `static-guards`，新增事实字段时需要同步更新脚本中的必备字段清单。

## 新增字段检查清单

新增或修改事实字段时逐项确认：

1. 是否需要 Flyway 新增列或索引。
2. 是否需要同步 `schema.sql`。
3. 是否需要补充 Java 生成规则测试。
4. 是否需要补充 SQL 查询或 service 测试。
5. 是否需要更新前端筛选字段、导出字段或明细展示。
6. 是否需要历史数据重建入口。
7. 是否需要更新本文档。
