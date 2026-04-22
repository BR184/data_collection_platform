# 统计链路地基修复进展

更新时间：2026-04-22  
适用项目：`D:\projects\data_collection_platform`

## 1. 本轮完成内容

本轮开始按“先修地基，再建楼”的顺序推进统计链路修复，当前已经完成第一批基础能力落地：

- 新增 issue 域 scope/profile 抽象：
  - `SystemTestScopeProfile`
  - `CustomerIssueScopeProfile`
  - `IssueScopeContext`
  - `IssueScopeProfile`
- 新增 issue 模板解析基础组件：
  - `IssueTemplateParsingSupport`
  - `IssueTemplateSnapshot`
- `FactBuildService` 已开始补齐客户问题所需的事实字段写入：
  - 优先尝试从 `ods_gitlab_milestones` 回填 `issue_fact.milestone_title`
  - 若本地镜像库缺少 milestone 相关结构，则自动回退到旧查询，避免重建链路直接报错
- 客户问题已落地的两张统计板已不再使用“页面内部写死范围判断”的方式：
  - `CustomerIssueDefectSummaryBoardService`
  - `CustomerIssueDefectCauseBoardService`
  - 两者已改为消费 `CustomerIssueScopeProfile`
- 新增基础单测：
  - `IssueScopeProfileTest`

## 2. 当前修复意义

这轮不是新增页面，而是把原先散落在多个 service 中的业务域判断开始往底层收。

修复后的变化是：

- `客户问题 = 非系统测试剩余集` 这条临时逻辑，已经不再直接写在客户问题统计服务里
- 模板回复和解决时限解析不再完全散在多个规则类里，开始收敛到统一模板解析支撑层
- `issue_fact` 的 `milestone_title` 开始真正进入构建链路，而不是只有表字段和 mapper、没有事实写入

## 3. 当前仍未完成的部分

虽然地基已经起步，但还没有完成全部基础治理。

当前仍待继续完成的关键内容：

- 将更多 issue 域服务切换到统一 scope/profile
- 继续补强 `CustomerIssueScopeProfile` 的真实口径识别
  - `CC_Product`
  - 创建时间下界
  - milestone 维度
- 将模板解析结果进一步沉入更多事实字段
- 为事实层增加可验收的检查能力
- 在事实层稳定后再继续落：
  - `客户问题 -> 缺陷非法数据`
  - `系统测试 -> 非法数据`

## 4. 已验证情况

本轮已完成如下验证：

- 后端单测：
  - `IssueFactNormalizationRulesTest`
  - `IssueScopeProfileTest`
  - `StatisticBoardControllerTest`
- 后端编译：
  - `mvn -q -DskipTests compile`

说明：

- 本地测试过程中仍能看到既有的 `ods_gitlab_label_links` 缺表告警，这是当前本地环境已有问题，不是本轮新引入的问题
- 本轮命令已成功退出，说明新增的地基修复代码能够正常通过编译与目标测试

## 5. 下一步建议

下一步应继续沿当前主方案推进，不建议中途切回“先做页面”路线。

推荐继续顺序：

1. 扩大 scope/profile 在 issue 域服务中的覆盖面
2. 继续完善 `FactBuildService` 与模板解析结果沉淀
3. 为事实层补验收与观测能力
4. 再开始实现 `客户问题 -> 缺陷非法数据`
