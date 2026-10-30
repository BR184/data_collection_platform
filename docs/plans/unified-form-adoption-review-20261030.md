# 表单真实启用与既有消费者接入评审

## 进度与中间物

- 阶段：2026-10-30代码调查及生产接入范围补充。用户已确认旧非法页整页暂用老平台、新MR/Issue走新表单、历史资源/评论保留旧流程；接受新MR在旧非法页可能仍报无走查的过渡局限，尽快切换外部数据源。
- 已完成：老平台筛选/解析/外部输入链、新平台兼容/正式读源、main的Issue事实/SLA/延期标签写回链、现有方案与N4计划差距盘点；不改业务代码、UI、数据库或服务。
- 验证：源码调查及`CodeReviewIllegalRuleRegistryTest`、`IssueResponseTemplateParserTest`、`CustomerIssueDelayLabelWritebackPlannerTest`共12/0/0/0、BUILD SUCCESS；仅证明基础规则/解析/规划，不冒称FORM生产接入已通过。日志`.tmp/form-adoption-review-20261030/rules-directed.log`；不运行全量、黄金、浏览器或真实写回。
- 下一步：用户确认边界已归product/architecture/D-32，N4准入与主方案生产启用验收已补充；员工在安全交接点吸收并回填其N4计划，不并发修改其活动计划或实现。
- 维护期限：本计划用于本轮接入调查与交接，员工吸收并将入口/证据纳入N4计划后收口删除；长期规则归product/architecture/D-32及总执行单，过程由Git保留。
- 资源：审核树`C:/Users/admin/.codex/worktrees/form-admin-ui/data_collection_platform`，分支`表单管理界面开发-20261027`，基线`5cece6ad`；员工`D:/projects/dcp-form-w4-20261026`同功能主分支；稳定主线只读对照`D:/projects/dcp-delay-main-20261028`的`4084cd33`。老源码`D:/projects/spidergitdata-dev`无Git元数据，只能按文件SHA留证，不冒称已核实生产版本。
- 首条恢复命令：`git status --short --branch`、`git worktree list`；前序[N4准入](unified-form-system-implementation-plan-20261021.md#n4-admission)，员工N4活动计划在其独立树，不覆盖其未提交内容。
- 未验证：生产实际部署SHA/读模式、目标库数据与标签、CI_ROBOT源码/配置和真实投放、未来外部平台接口及其就绪时间；没有外网/内网数据库连接或外部写入。

## 原始目标与成功标准

三类结构化表单要实际替代新资源上的文字模板输入，连接事实、页面/下钻、统计/导出、周期SLA和已上线延期标签写回；创建/编辑/作废都能传播。历史评论不能丢失，原解析代码短期保留。非法页作为用户明确例外，整页继续使用老平台，不能把FORM字段覆盖进该页、缩小其范围或自动给GitLab补旧评论。

## 范围判定与运行链

以本仓库Spring Boot应用为主分析对象，老平台Spring Boot/Java8代码提供历史业务口径；当前main提供已上线延期功能的源码基线，表单分支提供尚未发布的N1—N3/N4状态。两套入口分别是`com.huayun.Main`和`DataCollectionPlatformApplication`。

| 代码边界 | 已读位置与真实行为 | 接入含义 |
| --- | --- | --- |
| 老非法页 | `StaticDataController.getIllegalData`→`SpiderCrowncadQueryBuilder` | 查询老平台加工表，按异常结果值/条件筛选 |
| 老走查输入 | `MergeRequestDetailServiceImpl.parseComments/parseCommentStr` | 从GitLab评论读取模板，解析人员、分钟和五类问题数；格式/数值失败输出异常文本 |
| 老外部结果 | `MergeRequestDAOImpl.parseCodeScanResult/注释率判定`、`CodeWalkThroughService.checkClangTidyCodeLineValue` | 扫描机器人评论、注释率工具及Git变更行数共同提供结果，表单不能替代这些输入 |
| 新兼容页 | `CodeReviewMatchModeSyncService.selectSql`→`code_review_match_mode_records`→`CodeReviewIllegalRecordService.activeLoader`→ResponseMapper/RuleRegistry | 同步SQL没有只取非法行的WHERE；本地仍做条件筛选和非法类型判断。不能说只复制老平台非法列表，也不能说新平台完全没规则 |
| 新正式MR | `GitlabFactSourceSqlProvider.form_records`与事实构建 | 仍有旧Demo表`collect_form_records`及评论兜底；只改新表CRUD不会自动接入事实 |
| Issue首次事实 | main的`IssueFactSourceRowMapper`、`IssueResponseTemplateParser`、`IssueFactNormalizationRules` | 计划、原因、首次调研、回复/响应存在、非法和延期等仍依赖聚合评论 |
| 周期SLA | main的`FactBuildService.refreshCustomerIssueDelayFactsForConfig` | 用`fact.raw_payload`再次解析回复/响应，重算延期三列；只改首次mapper会被周期链覆盖 |
| 标签写回 | main的ClosureRound→SourceReadiness→Queue→Planner→Worker | Planner读`IssueFact`延期布尔，worker发送前复核授权轮次/事实版本并回读候选；FORM必须进入该链，不能另建旁路worker |
| 质量/分析读模式 | `QualityBoardCodeReviewReadSupport`、`CodeReviewMultiBoardAnalyticsQueryService` | 同样存在兼容读源选择；非法页例外不能靠整体关闭兼容开关解决，其他表单消费须逐出口核对 |

## 老平台非法规则核对

以下是源码口径，不代表本次已核实生产运行配置或数据库真实标签。旧页继续原实现，不在本轮修旧规则。

| 非法项 | 老代码实际依据 | 依赖来源 |
| --- | --- | --- |
| 未标注项目/模块 | `project_name/module_name`的未标注结果文本；部分项目受缺陷排查等级控制 | GitLab标签/老平台映射 |
| 无代码走查 | `assignee`为没有合法评论、时间/缺陷数异常、标题异常或记录行数异常 | 人工走查评论解析 |
| 未代码扫描 | `sonar_qube_result`为未进行代码扫描 | 扫描机器人评论/状态描述 |
| 静态扫描问题未关闭 | `bug_count_result`未通过，机器人问题数非0 | 扫描外部结果 |
| 静态扫描失败 | `bug_count_result`扫描失败 | 扫描采集/解析结果 |
| 代码注释量未达标 | CC注释率工具数据、15%阈值及放行；DGM机器人结果、20%阈值及放行 | 独立注释率工具/机器人，不是走查问题数量 |
| Clang分析错误 | DGM注释率未达标时比较Clang分析新增行与Git新增行，失配置错误 | Clang与Git变更数据 |
| GitLab接口报错 | 走查人/扫描状态/目标分支中的接口错误结果 | 采集失败信息 |

默认非法查询约束MERGED，排除模块“无需标注”；CC/DGM常用dev范围及2024-04-01之后合并数据还受调用参数/查询分支控制。还有“无需走查扫描”“扫描问题已说明”、特殊注释放行等源码分支。`spiderCrowncadDataFilter`的注释说提交人与走查人相同，实际比较author与mergedUserName；部分总筛选分支把注释率失败值用于bug_count_result。两项属于实现差异，不擅自升格为新系统规范。

## 新旧资源与实际替换清单

新旧按已批准切换清单里的来源/项目/资源创建时间和模板持久判定；不能按“今天有没有新填一条表单”改变旧资源权威。旧资源继续COMMENT，切换后的新资源使用FORM；新资源没填写是缺记录，不从旧评论补值。普通讨论、系统备注、标签/完成事件和扫描机器人输入继续保留各自用途。

历史原值保留不变；已批准的Wiki原因唯一性等合规口径仍按N0/D-30执行，保留旧输入解析不等于恢复已确认的原因计数bug。代码走查非法页整页旧结果是单独明确的过渡例外。

| 消费功能 | 新资源必须使用的新输入 | 历史/过渡保留 |
| --- | --- | --- |
| MR走查填写、记录和正式统计 | 表单全版权威记录及获批聚合，包含业务人员、时间、分钟、五类问题与说明 | 旧评论/旧表按原权威；非法页独立保留老平台 |
| Issue响应 | 首次响应、是否响应、最新整份计划/分支、D-21相关输入 | 老资源保留旧响应解析 |
| Issue回复 | 原因/合规、修复人、影响类型/详情、回复存在等结构化字段 | 老资源保留旧回复解析；多选采集与Wiki唯一性分层 |
| 统计、记录页、下钻与Excel | 同一已发布事实与原版字段投影，不额外解析FORM资源评论 | 非法页查询/自身导出仍走老页读源；旧资源可追溯 |
| 周期SLA与延期事实 | 与首次构建相同的结构化业务输入和权威/版本证据 | COMMENT资源继续原算法；不把FORM重新序列化成Markdown骗旧解析器 |
| 延期标签写回 | 表单最新变更已完成事实消费后，经既有授权轮次/队列/发送前复核 | 保留现有来源开关、停止控制、租约/围栏、真实标签所有权；不新增并行写回器 |
| CI_ROBOT入口 | 新MR/Issue投放稳定表单链接；响应/回复入口都可达 | 旧资源文字模板流程保留；本轮不授权机器人代填或回写旧格式副本 |

## 生产接入与验收顺序

1. 核对实际部署源码及已上线延期修复依赖；表单树尚缺main的`9a8dee32`、`9daf6c77`等写回修复，先吸收已验证主线及必要依赖，检查Flyway冲突与升级，不在过时写回链上接入。
2. N4改所有FORM消费者，包括周期SLA、关闭/完成重算和写回资格；保持旧COMMENT解析，仅用于历史批次。非法页整页保持老平台，独立读身份与快照，不因其他页面启用而整体关闭旧数据源。
3. 在专属环境走一次新Issue：机器人链接→响应/回复保存→事实/页面→周期SLA→写回规划；再改计划/作废回复，核对最新输入重算且旧任务不能发送过期标签。写回只用替身或获明确授权的测试实例，本轮不调用真实GitLab。
4. 新MR表单→正式消费/统计/导出与历史MR评论样本同时核对；非法页旧范围/结果保持，明确其暂不反映新表单。重复多模板页面矩阵不是验收目标。
5. E1机器人、E2真实非空范围、E6旧写回所有者和部署/切换清单补证。实际启用须冻结切换时点/批次、账号可达、权限、前置事实追平、来源开关、旧新写回唯一所有者及恢复方案；N4单元通过不等于已启用。
6. 外部平台就绪后另行批准非法页整体验收切换；旧COMMENT解析的退出则取决于历史数据另行获批归档/转换且核对完成，两者不是同一个删除时点。

## 取舍、合同与风险

- 已选：非法页全旧；新资源FORM/旧资源COMMENT；保留短期历史解析。混合覆盖非法项、缩旧页范围、同步旧评论均不实施。用户已接受暂时的不一致，未来外部接入列优先依赖。
- 无新增API/DDL或业务实现；沿既有`FORM/COMMENT`、`authority_revision`、资源head、事实版本及延期轮次授权补充接入设计，具体协议由N4员工提出并审核。
- 现有N4计划把MR输入描述为只影响三个字段，并建议沿Demo的max，不能代替已批共同字段统计；完整映射与MR公式影响仍须按真实业务报审。
- 没有CI_ROBOT真实材料不能宣称自动投放已接通；没有部署证据不能把本地主线等同于运行中的生产包。新表单保存成功也不能宣称旧非法页已同步。
