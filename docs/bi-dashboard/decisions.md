# BI 看板决策记录（Decisions）

<!-- DOC_STATUS_START -->
> 文档状态：BI 架构决策的单一持续维护记录
> 说明：记录 BI 看板"为什么这么设计"的架构决策。BI 数据字段与公式以 `BI看板数据来源与计算口径核对表.md` 为权威、接口契约以 `data-contracts.md` 为权威，本文件只记录决策与理由。禁止按决策新开文件；平台级决策在 `docs/decisions.md`。
<!-- DOC_STATUS_END -->

## 使用规则

- 新增 BI 架构决策：在本文件末尾追加条目（编号 `D-NN`），说明背景（一句话）、选择、否决选项（如有）、当前状态、关联文档。
- 决策被替代或废弃：更新对应条目状态并压缩失效内容，不新建文件、不保留双轨结论。

---

## D-01 BI 内嵌模块与强包边界

- **状态**：已接受 / 已实施
- **决策**：
  1. 首期不拆分 Maven Reactor：后端统一位于 `com.data.collection.platform.bi`，前端位于独立 BI 功能目录，随现有 Maven 工程、Spring Boot JAR、前端发布物、Docker 和离线包一起构建部署。
  2. 保留进程内 BFF，负责六页 API、来源版本、聚合计算和页面状态，不创建第二个网络服务。
  3. Spring 启动期只创建薄网关和 Runtime Manager；BI 内部服务使用普通对象并在首次已授权请求时显式、延迟装配。
  4. BI 直接复用平台成熟基础设施和稳定事实/基础查询端口；页面计算、页面状态、CAT 适配和图表体系在 BI 域独立实现。
- **理由**：以最低改动把 BI 接入平台单应用，避免 Reactor 拆分带来的构建/发布复杂度；强包边界保持 BI 内部自治与可测试性。
- **关联**：`docs/bi-dashboard/architecture.md`

## D-02 CAT 数据采用 BI 专属全量镜像快照

- **状态**：已接受 / 已实施
- **决策**：
  1. CAT 数据写入 PostgreSQL 中 BI 专属的配置、同步运行、快照、模块、功能、原始响应和当前发布指针表（`bi_cat_*`）。
  2. CAT 镜像不进入 GitLab 镜像表注册、兼容模式、GitLab 同步运行或现有表级增量引擎；任一 CAT 同步失败不得改变其它镜像任务和平台页面。
  3. 页面只读取已发布快照，不在请求链路直接依赖 CAT 网络状态或半成品响应。
- **理由**：CAT 是外部测试平台，接口契约不稳定；镜像快照把外部依赖隔离在 BI 域内，保证页面请求只读稳定快照。
- **关联**：`docs/bi-dashboard/architecture.md`、`docs/bi-dashboard/data-contracts.md`

## D-03 代码注释率趋势按请求粒度在后端做非加权算术平均

- **状态**：已接受 / 已实施（规则版本 `bi-coding-v5`）
- **背景**：编码页「代码注释率与走查密度趋势」中，密度轨已按请求粒度在后端聚合，注释率轨却由后端逐条返回 `comment_rate`、前端以走查日期为键构造 `Map` 折叠。JavaScript `Map` 对重复键保留最后一次写入，同一周期的展示值因此取决于来源行顺序（`merged_at_source, id`），既无业务含义也无法复现；两条轨道输出粒度不一致。
- **决策**：
  1. 注释率趋势由 BI 后端按请求的 `granularity` 分桶（日=自然日，周=周一所在日期，与 CD-39 的 `bucket` 一致），对桶内合法 `comment_rate` 做非加权算术平均，忽略 `NULL`，先累加原始值、最后一次性保留 2 位小数（`HALF_UP`）。
  2. 记录粒度为 CD-16 的稳定走查记录 ID：完全相同的重复事实只计一次；同一 ID 核心字段冲突时整组不进入聚合，`comment-rate` 与 `quality-trend` 区块标记 `INCOMPLETE`，其它无关事实族不被清空。
  3. 页面 DTO 直接替换为 `commentRateTrend(period, averageCommentRate)`，删除 `commentRatePoints`/`CommentRatePoint`/`observedOn`/`commentRate`/`commentRateSource` 页面输出字段，不保留兼容别名或双轨路径。
  4. 没有合法记录的时间桶不生成点位，前端与密度轨对齐后显示 `null` 并保持 `connectNulls=false`。
- **理由**：核对表「编码与人工代码走查」前言已确立记录级比率的聚合模式——先按每条记录计算、再对记录值取平均；上游只提供比值，没有注释行数与代码行数，加权与总量比不可计算。聚合放后端使两条轨道同粒度，并让页面 DTO 承载口径，避免前端二次加工造成页面与 Excel 口径分叉。
- **否决方案**：
  1. 取当日最后一条（原实现）：取决于来源顺序，无业务依据，无法复现解释。
  2. 按 `added_lines`／被走查行数加权或总量比：来源没有注释行数/代码行数分子分母，属臆造口径。
  3. 按合并请求去重：那是 CD-39 密度轨的专用规则（同一 MR 被走查行数只计一次），不适用于注释率。
  4. 前端聚合：绕过页面数据契约，页面与 Excel 口径可能分叉。
  5. 补零／前向填充／插值／连接断点：把「无观测」画成「有观测」，与 CD-38「只展示合法注释率、不臆造」冲突。
- **关联**：`docs/bi-dashboard/BI看板数据来源与计算口径核对表.md`（CD-38A、CD-39）、`docs/bi-dashboard/data-contracts.md`（页面 API）、`docs/plans/bi-coding-comment-rate-period-aggregation-20260916.md`

## D-04 BI 权限按页面拆分：每页独立 view/download，删除模块级码

- **状态**：已接受 / 已实施（迁移 `V20260928_01__bi_dashboard_page_permissions.sql` + 前向修正 `V20260929_01__bi_dashboard_default_permissions.sql`）
- **背景**：BI 原为「一个模块一个查看权限」（`bi.dashboard.view` / `bi.dashboard.download` 挂在控制器类级注解上），无法按页面独立授予或下线；统计板与分析看板早已是页面级 view/export 成对权限。新增的客户问题页需要与统计板客户问题统计页一致地按页面收敛。
- **决策**：
  1. 单层权限，不做模块准入码：删除 `bi.dashboard.view` / `bi.dashboard.download`，新增 7 页 × (view + download) 共 14 个码（`bi.dashboard.<页面>.view` / `.download`），命名与下划线分词对齐统计板范式。
  2. 页面 key 与权限码的唯一映射收敛在后端 `BiPagePermissionResolver`（前端对应 `features/bi-dashboard/data/page-permissions.ts`，由导航清单与 BI 单测锁定一致）；查看端点各自标注本页查看码，产品版本目录用「任一页面查看码」，下载端点用「任一页面下载码」粗粒度门禁 + 控制器按请求体 `pageKey` 精确校验该页查看与下载码（页面身份在请求体，拦截器拿不到；不改下载端点路径与请求体）。
  3. 角色授权（`platform_role_permissions`）按迁移前旧码持有角色**逐角色复制**：升级前后逐角色可见性一致，内网既有定制（含上线期临时隐藏）原样保留。默认授权（`platform_default_role_permissions`）**不随当前持有漂移**，固定为平台托管五角色全员可见，使「恢复默认权限」回到既定默认口径（全员可见），而不是把临时隐藏固化成默认。`V20260929_01` 即为归位该口径的前向修正。
  4. 不保留别名、转发或双码并存；前端下载按钮按本页下载码显示，但按钮隐藏不代替后端授权。
- **理由**：与统计板/分析看板范式一致，撤销某页权限即菜单不可见且后端与下载通道同步拒绝；单层结构避免「有模块码无页面码」的空菜单中间态。
- **否决方案**：
  1. 保留模块级码作为 BI 模块准入（两层）：会出现拿不到任何页面权限却能看到空菜单的中间态。
  2. 只拆查看、下载保留单一模块码：下载通道会绕过页面权限。
  3. 把下载路径改成 `/api/bi/{pageKey}/download/*` 以便拦截器解析：破坏对外契约并让 pageKey 出现两个来源。
  4. 在迁移里把新页面设为管理员专属：默认口径是全员可见，「除管理员外不可见」只是上线后为避免领导使用未完成版本的临时手段（在权限设置页撤销，点「恢复默认权限」即回到全员可见）。
- **关联**：`docs/bi-dashboard/architecture.md`（请求与安全）、`docs/bi-dashboard/product.md`、`docs/decisions.md` D-22（程序化鉴权 403 与权限默认授权登记口径）、`docs/plans/bi-dashboard-permission-split-20260928.md`
