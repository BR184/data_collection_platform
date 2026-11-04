# CCProduct表单就绪后投放执行方案

## 进度与中间物

| 阶段 | 状态 | 恢复入口 |
| --- | --- | --- |
| L1 就绪后登记 | 已实施并验证 | `FormIssueLinkService.registerPublishedIssues` + `V20261104_01` |
| L2 发一次双入口评论 | 已实施并验证 | `FormIssueLinkService.sendOne` + `GitlabCompensationScheduler` 每轮补发 |
| L3 本地联验 | 待外部前置 | 需 ci_robot 授权、平台对外地址、专属 GitLab 与 325 样本 |

- 工作目录 `D:/projects/dcp-form-w4-20261026`，分支 `表单多版本与填写功能开发-20261026`，基线提交 `b8ba9b92`（T2 首次严格 compare）。
- 原需求与红线见下文“最小方案与取舍”“就绪判定”“红线与前置”；本单元不生成/更新黄金快照、不合 main、不出正式包、不升级现场、不生产启用。
- 业务约束：FORM/COMMENT 持久权威不翻转；FORM 沿既有事实链；本人归属/幂等/CAS/审计/head 同事务；生产 MR 继续评论；非法页保持旧源；投放默认关闭。
- 已验证证据：
  - `FormIssueAdoptionFactPipelineIntegrationTest` 32/32（原 26 项 + 新增 6 项，真实 PostgreSQL/Testcontainers）；新增覆盖发布回滚 0 行、成功登记 1 行且不新增任务行、重复登记 0 行、单条评论含两入口、并发仅 1 条评论、HTTP 500 后编号为空且下一轮成功、COMMENT/非 325 为 0。
  - `GitlabCompensationSchedulerTest` 14/14、`DatabaseBrowserFormTableDefinitionsTest` 1/1、`FactPublicationFailurePreservationTest` 5/5、`FactTargetPublicationServiceIntegrationTest` 19/19、`LegacyPlatformFormalImportRevisionIntegrationTest` 5/5。
  - 新增迁移校验和已登记，Flyway 迁移锁校验通过。
- 关键契约：`form_resource_authorities.entry_registered_at`（已登记）、`entry_comment_id`（评论已发）；`platform.form-issue-link.enabled`（默认 false）、`platform.form-issue-link.external-base-url`、`batch-size`、`request-timeout`。
- 风险与假设：GitLab 成功、编号提交前进程退出会多一条评论（已按低风险接受）；评论 HTTP 在持有响应权威行锁的事务内执行，超时由 `request-timeout` 约束；L3 前不得改写来源配置 URL/令牌以外的投放配置。

## 当前状态与待办

L1/L2已交付`39eaae48`，保留原实现；登记/发送已补齐双模板有效性检查，下一步进入L3。新增表、独立worker及状态机保持0，新增核心类1个。L3 待用户取得 ci_robot 的 `api` 作用域 PAT 安全注入、平台对外地址与专属 GitLab/325 样本后执行。

| 编号 | 目标 | 完成标准 |
| --- | --- | --- |
| L1 | 就绪后登记 | 发布回滚时登记0行；同一Issue成功登记1行，新增任务行0 |
| L2 | 发一次双入口评论 | 正常重复/并发触发后登记1行、GitLab本功能评论1条，含响应/回复两个入口 |
| L3 | 完成本地联验 | 有效记录1条；改期后解决延期true→false，延期标签摘除 |

## 最小方案与取舍

在已有`form_resource_authorities`的响应模板行加`entry_registered_at`、`entry_comment_id`两列，沿用资源唯一键；只作投放记账，权威修订保持原规则。

`FactTargetPublicationService`在ISSUE成功发布事务内条件登记，提交后经已有`platformAsyncExecutor`触发发送。`GitlabCompensationScheduler`每轮处理325已登记且评论ID为空的记录。

新增`FormIssueLinkService`：检查就绪及评论ID；独立事务锁住响应权威行、复查ID、带超时发送、保存ID。两个入口共用一条评论。

| 机制 | 具体时序与裁定 |
| --- | --- |
| 独立任务表 | 删除。资源唯一键与两列已能表达待发/成功，复用响应权威行 |
| 租约/令牌CAS | 删除。A/B同时看到空ID，行锁使B在A提交后读到ID而跳过；进程退出由数据库释放锁 |
| 评论标记核对 | 删除。GitLab成功后、ID提交前进程退出，重发可能多一条评论；按低风险接受此窗口 |
| 退避/暂停状态机 | 删除。明确失败后ID仍空，下一轮现有调度重试并记录错误 |
| 补扫worker | 删除。提交后未执行发送即退出，由已有调度处理空ID记录 |
| 详情就绪DTO | 删除必做项。投放直接查资源证据，详情状态不参与发送 |

## 就绪判定

仅当完整来源下325的Issue初始事实已公开、响应/回复均为持久FORM、绑定和范围/版本有效时允许投放；写入口继续拒绝未就绪资源。

当前小修（已实施）：`FormIssueLinkService`对响应、回复分别应用相同的范围/当前版本校验，覆盖登记和发送两处；保留只锁响应权威行的事务边界。真实PG用例覆盖回复范围退役或版本无效时登记0、HTTP0，恢复有效后登记1并发1条；原正常路径同批复验。

可选增强：详情可写提示另审。本次保留现状：手拼未采集链接可能显示可写，保存仍被拒绝。T2项目样本单独纠正。

## 采集间隔

估算假设：无积压，采集到发送共≤1分钟，含每分钟调度检查；联验记录实际耗时校准。

| 选择 | 同来源其他项目影响 | 典型/正常最慢等待 |
| --- | --- | --- |
| CC来源10分钟同步 | 调度次数较15分钟约增50% | 约6至7/12分钟 |
| 保留15分钟，只每分钟查325就绪记录 | 采集频率不变，增加325轻量查询 | 约9/17分钟 |

选10分钟：要缩短从创建到链接的等待，仅查就绪不能提前发现尚未采集的Issue。只调整目标来源现有间隔配置；两种选择都复用已有调度。

## 验证与联验

| 风险 | 最低验证 |
| --- | --- |
| ①事务/并发 | 首个测试：真实PG中发布回滚后登记0行；再验证并发重复触发登记1行、HTTP成功评论1条 |
| ②业务结果 | ready的325 Issue收到两个入口；非325或COMMENT收到0条。HTTP明确失败用替身验证ID仍空、下次调度成功写ID |
| ③展示 | 本次无页面变更；可选增强获批时核对对应状态 |

一条主线：新建325 Issue→自动同步→机器人评论→普通账号开链接保存→延期页面→改期一次，结果按既定规则变化。

作废后按剩余有效输入重算；COMMENT对照保持原评论口径。10分钟到期用可控时钟断言。

## 红线与前置

沿用原功能分支与专属本地环境。FORM/COMMENT权威不翻转，FORM沿既有事实链，本人归属/幂等/CAS/审计/head同事务；生产MR继续评论，非法页旧源。投放默认关闭，生产配置/凭据及其他工具评论保持；黄金、main、正式包及生产启用各自审批。本轮验证限上述入口，容器回读、突变、全量重跑和黄金目录同步不列本轮要求。

| 缺什么 | 谁提供 | 卡住哪一步 |
| --- | --- | --- |
| ci_robot普通PAT含`api`，目标项目可评论 | GitLab管理员安全注入 | 本地L3 |
| 平台对外地址 | 本地环境负责人 | 链接访问 |
| 专属GitLab及325样本 | 员工复用现有初始化 | 联验 |

源码参考仅核对`Code_Value_V2/api_requests/gitlab_request.py`的Notes API调用行为。普通PAT的`read_api`/`write_repository`不足以发评论；注入后只读核验实际账号为ci_robot。当前实现使用来源`api_token`，只在专属本地来源配置中准备；本地实测评论作者必须为ci_robot。
