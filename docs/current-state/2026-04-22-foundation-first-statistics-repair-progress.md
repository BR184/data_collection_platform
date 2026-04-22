# 统计链路地基修复进展

更新时间：2026-04-22  
适用项目：`D:\projects\data_collection_platform`

## 1. 本轮已完成

本轮继续按“先修地基，再建楼”的顺序推进，已经落下来的基础能力有：

- 新增 issue 域 `scope/profile` 抽象：
  - `SystemTestScopeProfile`
  - `CustomerIssueScopeProfile`
  - `IssueScopeContext`
  - `IssueScopeProfile`
- 新增 issue 模板解析基础组件：
  - `IssueTemplateParsingSupport`
  - `IssueTemplateSnapshot`
- `FactBuildService` 已开始正式回填客户问题所需的关键事实字段：
  - 优先从 `ods_gitlab_milestones` 回填 `issue_fact.milestone_title`
  - 如果本地镜像库缺少 milestone 相关表结构，则自动回退到兼容查询，避免整条重建链路失败
- 已落地的客户问题统计板开始改为消费统一 `CustomerIssueScopeProfile`，不再把“客户问题 = 非系统测试剩余集”直接写死在页面服务里：
  - `CustomerIssueDefectSummaryBoardService`
  - `CustomerIssueDefectCauseBoardService`
- 新增事实层验收诊断能力：
  - `GET /api/facts/issue-diagnostics`
  - 输出 `issue_fact` 的 overall / system-test / customer-issue 三个 scope 汇总
  - 输出原因归类分布、客户问题项目分布
  - 可直接用于判断 `reason_category`、`milestone_title`、模板回复、SLA 标记等覆盖情况
- 新增 ODS 源数据就绪度诊断能力：
  - `GET /api/facts/issue-source-readiness`
  - 直接检查 `ods_gitlab_projects / ods_gitlab_issues / ods_gitlab_milestones`
  - 区分“事实层没沉淀出来”和“源数据本身就没有客户问题数据”
- 客户问题两张已落地统计板的 scope 入参已补完整：
  - `CustomerIssueDefectSummaryBoardService`
  - `CustomerIssueDefectCauseBoardService`
  - scope 判断不再丢失 `projectId / milestoneTitle / createdAt`

## 2. 这轮修复的意义

这一轮不是继续铺页面，而是把之前散落在多个 service 里的业务口径和验收判断，开始往事实层和底层 profile 收。

修复后的变化是：

- 客户问题口径开始从“页面里临时判断”转向“统一 scope/profile”
- 模板回复、原因归类、SLA 规则开始共享同一套模板解析支撑
- `issue_fact.milestone_title` 开始真正进入构建链路，而不是只有表字段、没有写入
- 事实层现在有了一个可以直接观察的数据验收入口，不必每次都靠页面空表来反推问题
- 现在还能直接判断“空表是 facts 没建出来，还是 ODS 根本没有这类数据”

## 3. 当前还没补完的地基

虽然基础已经往前走了一步，但还没有彻底收口，当前仍待继续完成的内容有：

- 把更多 issue 域服务切换到统一 `scope/profile`
- 继续补强 `CustomerIssueScopeProfile` 的真实业务口径：
  - `CC_Product`
  - 创建时间下界
  - milestone 维度
- 继续把模板解析结果沉淀到更多事实字段
- 为 `merge_request_fact` 补对应的验收和观测能力
- 在事实层稳定后，再继续实现：
  - `客户问题 -> 缺陷非法数据`
  - `系统测试 -> 非法数据`

## 4. 当前本地数据现状

通过直接检查本地 PostgreSQL 容器中的数据，当前已经确认：

- `issue_fact` 当前共 `391` 条，全部来自 `project_id = 1 / Rocksdb`
- `issue_fact` 当前没有任何 `CC_Product` 项目数据
- `issue_fact.milestone_title` 当前全部为空
- `ods_gitlab_projects` 当前只有一个项目：`Rocksdb`
- `ods_gitlab_milestones` 当前没有数据
- 当前 ODS 中能识别到的系统测试标签只有：
  - `CC2026R1第一轮系统测试`
  - `CC2026R1回归测试`

这意味着：

- 客户问题相关页面当前为空，核心原因不是前端或统计板链路，而是本地源数据本身没有客户问题域输入
- 后续继续补客户问题模块前，至少要先补进 `CC_Product` 相关 issue 源数据，或提供对应 demo 数据

## 5. 本轮验证

本轮已完成如下验证：

- 后端单测：
  - `IssueFactDiagnosticsServiceTest`
  - `IssueSourceReadinessServiceTest`
  - `FactBuildControllerTest`
  - `IssueScopeProfileTest`
  - `IssueFactNormalizationRulesTest`
- 后端编译：
  - `mvn -q -DskipTests compile`

说明：

- 本地测试日志里仍能看到既有的 `ods_gitlab_label_links` 缺表告警，这属于当前本地环境已有问题，不是本轮新引入的问题
- 上述测试和编译已经成功通过，说明事实层诊断接口这条链路是可用的

## 6. 下一步建议

下一步继续按当前主方案推进，不建议中途切回“先做页面”的节奏。

推荐顺序：

1. 先补充 `CC_Product` 相关 ODS 源数据或最小可用 demo 数据，否则客户问题链路只能继续返回空结果
2. 用 `/api/facts/issue-diagnostics` 和 `/api/facts/issue-source-readiness` 作为固定验收入口
3. 在确认客户问题源数据到位后，再继续实现 `客户问题 -> 缺陷非法数据`
