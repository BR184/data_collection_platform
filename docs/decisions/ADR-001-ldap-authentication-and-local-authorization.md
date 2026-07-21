<!-- DOC_STATUS_START -->
> 文档状态：已接受架构决策 / 已实施（持续验证中）
> 说明：定义数据采集平台接入统一 LDAP 平台后的认证、身份同步、Session、本地 RBAC 和评审数据归属边界。
<!-- DOC_STATUS_END -->

# ADR-001：LDAP 认证与数据采集平台本地授权

## 状态

已接受，已实施；后续仅保留联调和环境验收项。

## 日期

2026-07-20

## 背景

数据采集平台与 LDAP 平台是两个独立项目。LDAP 平台采用 RBAC 架构，但其权限仅用于控制 LDAP 平台自身功能。数据采集平台需要复用 LDAP 中的账号、用户状态和角色基础信息，同时独立维护适合本平台业务操作颗粒度的授权规则。

数据采集平台保留自己的 HTTP Session，并通过 LDAP Provider 调用 LDAP 接口完成认证。本决策取代早期“直接 LDAP Bind、LDAP group 映射到固定平台角色”的预留方向，作为实现和验收依据；当前认证和授权边界以本文及 `docs/architecture.md` 为准。

## 决策

### 1. 登录与 Session

1. 保留数据采集平台自己的登录页面，不跳转到 LDAP 平台页面。
2. 浏览器只向数据采集平台提交用户名和密码。数据采集平台后端调用 LDAP 平台暴露的登录接口进行认证。
3. LDAP 认证成功后，数据采集平台后端读取用户基础信息、账号状态、全部角色编码和角色名称，并创建数据采集平台自己的 HTTP Session。
4. 浏览器后续只携带数据采集平台的 Session Cookie，不使用 LDAP 平台 JWT 访问数据采集平台接口。
5. LDAP 平台返回的访问令牌如需用于调用用户详情接口，只能保留在本次服务端登录编排中，不下发给数据采集平台前端，不作为数据采集平台业务接口凭据。

### 2. 用户与角色同步

1. 首次接入时，从 LDAP 平台全量拉取数据采集平台需要的用户基础信息、账号状态、角色编码和角色名称，写入本地身份快照表。
2. 首次全量完成后不执行定时全量或增量同步。
3. 用户每次登录时，以 LDAP 平台当次返回结果更新该用户的本地基础信息、状态和角色关系。
4. LDAP 角色是只读主数据。数据采集平台不允许创建、删除或重命名 LDAP 角色。
5. 角色关联必须使用稳定角色编码；角色名称只用于展示，不能作为权限关联键。
6. LDAP 中存在但尚未配置本地权限的角色默认不获得额外权限。
7. 首次全量同步形成独立的 LDAP 用户身份镜像表，并在“系统设置 / 数据库查看”中提供查看入口；后续登录刷新同一用户的镜像信息。
8. LDAP 用户基础信息、账号状态、角色编码和角色名称属于外部主数据，在数据采集平台中只读。平台本地权限不得直接写入或覆盖这些 LDAP 镜像字段。

### 3. LDAP 故障和状态变更边界

1. LDAP 平台不可用或登录调用超时时，已有数据采集平台 Session 和已加载的本地权限继续有效。
2. LDAP 不可用时，新登录失败。数据采集平台不得缓存、复制或离线校验 LDAP 密码。
3. 用户登录后，如果 LDAP 中的账号被禁用、删除或角色发生变化，当前 Session 不主动轮询 LDAP，也不立即失效。
4. 账号状态和角色变化在用户下次登录时生效；现有 Session 持续到用户退出或 Session 自然过期。
5. 当前阶段不增加 LDAP 状态轮询、令牌 introspection、主动会话踢出或定时用户同步机制。
6. 当前产品启动时默认选择 LDAP Provider；本地 Provider 仅用于明确的本地认证测试或离线开发，并且必须显式配置，禁止因环境变量缺失而静默回退。
7. 后端进程重启会清除当前内存 Session；这与空闲超时无关。需要跨重启保持会话时应独立引入持久化 Spring Session，不能通过放宽认证规则实现。

### 4. 数据采集平台本地 RBAC

1. LDAP 只回答“用户是谁、账号是否可用、用户有哪些角色”；数据采集平台决定“这些角色在本平台可以做什么”。
2. 数据采集平台本地维护权限目录及 LDAP 角色到本地权限的多对多关系，不把本平台权限写回 LDAP。
3. 一个用户可以拥有多个 LDAP 角色。用户的最终权限为其全部角色对应本地权限的并集。
4. 权限检查以后端为最终裁决者；前端页面、导航和按钮只根据同一权限结果控制可见性，不能替代后端鉴权。
5. 权限采用稳定编码并细化到用户可执行的动作，例如：
   - 查看具体模块或页面；
   - 导出具体业务数据；
   - 新增、编辑、删除评审记录；
   - 新增、编辑、删除评审问题项；
   - 管理标签组、测试阶段或其他设置；
   - 管理数据同步能力。
6. 强关联的数据同步、同步诊断、同步运行和事实重建能力可以归并为一个本地权限域，不要求按每个按钮拆分。
7. 首次初始化时，为五个 LDAP 角色写入以下默认权限模板：

| LDAP 角色 | 初始化默认权限 |
| --- | --- |
| `SUPER_ADMIN`、`ADMIN` | 默认拥有全部页面和操作权限。 |
| `DIRECT_MANAGER`、`TREE_MANAGER` | 默认不拥有“数据镜像设置”“数据库兼容模式临时设置”“数据库查看”权限，其他页面和操作权限默认开放。 |
| `NORMAL_USER` | 默认不拥有任何“系统设置”页面权限，其他业务页面和操作权限默认开放；评审删除默认仅开放本人数据。 |

8. “一级、二级、三级”仅是需求讨论中描述上述三组初始化模板的简称，不是系统概念。数据库、后端、前端和 API 中均不得引入权限等级字段、等级枚举、等级比较或按等级分支；运行时授权只根据五个 LDAP 角色对应的可编辑权限编码集合计算并集。
9. 在“系统设置”中新增独立的“权限设置”页面，交互和页面结构参考“议题测试阶段定义”，不在“数据库查看”页面中混合配置权限。
10. “权限设置”页面以 `SUPER_ADMIN`、`ADMIN`、`DIRECT_MANAGER`、`TREE_MANAGER`、`NORMAL_USER` 五个 LDAP 角色为授权对象，维护“LDAP 角色到数据采集平台本地权限”的映射。
11. 权限编辑只允许修改本地权限目录和角色权限映射，不允许修改 LDAP 同步来的用户名、用户状态、角色编码、角色名称或用户角色关系；这些身份主数据只能由 LDAP 登录同步更新。
12. “数据库查看”只负责查看 LDAP 用户身份镜像和其他允许浏览的平台表，不承担权限配置，也不得通过通用数据库行编辑绕过权限设置页面。
13. 其他业务模块按本地角色权限配置向已登录用户开放，不把 LDAP 平台自己的菜单或权限当作数据采集平台权限。
14. “权限设置”的查看和修改分别由 `system.permission.view`、`system.permission.manage` 控制，初始化时授予 `SUPER_ADMIN` 和 `ADMIN`，之后也通过同一角色权限映射进行编辑；每次修改记录修改人、修改时间、目标角色、变更前权限和变更后权限。
15. 系统必须保证始终至少存在一个可登录用户能够管理权限；不得通过一次权限修改移除最后一个权限管理员。
16. 权限设置页面的角色排列使用本地 `display_order` 展示字段，从高到低展示；该字段只服务于页面排序，不代表权限等级，不参与认证、授权或权限并集计算。LDAP 同步更新角色名称和状态时不得覆盖该本地展示字段。
17. 权限设置提供“恢复默认设置”操作。默认权限保存在独立的本地快照表中，恢复时一次性覆盖五个受管理 LDAP 角色，并写入权限审计日志；该操作不改变 LDAP 用户、角色或当前认证会话。

#### 4.1 第一版权限明细（待确认）

权限编码使用稳定英文编码，页面文案调整不得改变权限编码。表中的“默认角色”只表示首次初始化值，不是不可修改的等级规则；拥有 `system.permission.manage` 的用户可以在“权限设置”页面调整五个角色的本地权限映射。

##### 页面查看权限

| 权限编码 | 页面 | 默认角色 |
| --- | --- | --- |
| `quality.rd.view` | 质量看板 / 研发质量看板 | 全部五个角色 |
| `quality.other.view` | 质量看板 / 其他看板 | 全部五个角色 |
| `review.data.view` | 评审数据 / 评审数据管理 | 全部五个角色 |
| `code_review.illegal.view` | 代码走查 / 代码走查非法数据 | 全部五个角色 |
| `code_review.board.view` | 代码走查 / 代码走查多元看板 | 全部五个角色 |
| `system_test.summary.view` | 系统测试 / 系统测试缺陷汇总 | 全部五个角色 |
| `system_test.board.view` | 系统测试 / 议题多元看板 | 全部五个角色 |
| `system_test.delay.view` | 系统测试 / 申请延期缺陷分析 | 全部五个角色 |
| `system_test.illegal.view` | 系统测试 / 系统测试非法数据 | 全部五个角色 |
| `system_test.cause.view` | 系统测试 / 缺陷原因分析 | 全部五个角色 |
| `system_test.phase.view` | 系统测试 / 议题阶段统计 | 全部五个角色 |
| `system_test.issue.view` | 系统测试 / 议题查询 | 全部五个角色 |
| `customer_issue.summary.view` | 客户问题 / 缺陷汇总 | 全部五个角色 |
| `customer_issue.illegal.view` | 客户问题 / 缺陷非法数据 | 全部五个角色 |
| `customer_issue.cause.view` | 客户问题 / 缺陷原因分析 | 全部五个角色 |
| `customer_issue.record.view` | 客户问题 / CC_PRODUCT 议题 | 全部五个角色 |
| `customer_issue.delay.view` | 客户问题 / 延期问题 | 全部五个角色 |
| `customer_issue.efficiency.view` | 客户问题 / 缺陷响应效率 | 全部五个角色 |
| `customer_issue.function.view` | 客户问题 / 按功能展示缺陷数量 | 全部五个角色 |
| `system.label_group.view` | 系统设置 / 标签组管理 | `SUPER_ADMIN`、`ADMIN`、`DIRECT_MANAGER`、`TREE_MANAGER` |
| `system.testing_phase.view` | 系统设置 / 议题测试阶段定义 | `SUPER_ADMIN`、`ADMIN`、`DIRECT_MANAGER`、`TREE_MANAGER` |
| `system.permission.view` | 系统设置 / 权限设置 | `SUPER_ADMIN`、`ADMIN` |
| `system.mirror.view` | 系统设置 / 数据镜像设置 | `SUPER_ADMIN`、`ADMIN` |
| `system.match_mode.view` | 系统设置 / 数据库兼容模式临时设置 | `SUPER_ADMIN`、`ADMIN` |
| `system.database.view` | 系统设置 / 数据库查看 | `SUPER_ADMIN`、`ADMIN` |

看板下钻详情、规则说明、筛选候选和列表详情继承所属页面的查看权限，不再重复生成一套详情查看权限。系统设置模块是否出现在导航中，由当前用户是否拥有至少一个系统设置页面查看权限决定。

##### 导出和下载权限

| 权限编码 | 作用范围 | 默认角色 |
| --- | --- | --- |
| `quality.rd.export` | 研发质量看板全部导出 | 全部五个角色 |
| `quality.other.export` | 其他看板全部导出 | 全部五个角色 |
| `review.record.export` | 导出评审列表 | 全部五个角色 |
| `review.problem.export` | 导出评审问题列表及单条问题详情 | 全部五个角色 |
| `review.template.download` | 下载评审导入模板 | 全部五个角色 |
| `code_review.illegal.export` | 导出代码走查非法数据 | 全部五个角色 |
| `code_review.board.export` | 导出代码走查多元看板数据 | 全部五个角色 |
| `system_test.summary.export` | 系统测试缺陷汇总、议题数据和横向对比导出 | 全部五个角色 |
| `system_test.board.export` | 系统测试议题多元看板图表导出 | 全部五个角色 |
| `system_test.delay.export` | 申请延期缺陷分析导出 | 全部五个角色 |
| `system_test.illegal.export` | 系统测试非法数据导出 | 全部五个角色 |
| `system_test.cause.export` | 系统测试缺陷原因分析导出 | 全部五个角色 |
| `system_test.issue.export` | 系统测试议题查询导出 | 全部五个角色 |
| `customer_issue.summary.export` | 客户问题缺陷汇总及议题数据导出 | 全部五个角色 |
| `customer_issue.illegal.export` | 客户问题非法数据导出 | 全部五个角色 |
| `customer_issue.cause.export` | 客户问题缺陷原因分析导出 | 全部五个角色 |
| `customer_issue.record.export` | CC_PRODUCT 议题导出 | 全部五个角色 |
| `customer_issue.delay.export` | 客户问题延期问题导出 | 全部五个角色 |
| `customer_issue.efficiency.export` | 客户问题缺陷响应效率导出 | 全部五个角色 |
| `customer_issue.function.export` | 客户问题按功能统计导出 | 全部五个角色 |

系统测试“议题阶段统计”当前没有导出功能，因此不定义虚假的导出权限。导出权限必须同时要求对应页面查看权限，不能只持有导出权限后绕过页面数据范围。

##### 业务写操作与数据维护权限

| 权限编码 | 操作 | 默认角色 |
| --- | --- | --- |
| `business_data.refresh` | 业务页面刷新最新数据，包括统计、记录和非法数据刷新 | 全部五个角色 |
| `review.record.create` | 新增评审记录 | 全部五个角色 |
| `review.record.edit` | 编辑评审记录 | 全部五个角色 |
| `review.record.delete_any` | 删除任意评审记录 | `SUPER_ADMIN`、`ADMIN`、`DIRECT_MANAGER`、`TREE_MANAGER` |
| `review.record.delete_own` | 删除本人创建的评审记录 | 全部五个角色 |
| `review.problem.create` | 新增评审问题项 | 全部五个角色 |
| `review.problem.edit` | 编辑评审问题项 | 全部五个角色 |
| `review.problem.delete_any` | 删除任意评审问题项 | `SUPER_ADMIN`、`ADMIN`、`DIRECT_MANAGER`、`TREE_MANAGER` |
| `review.problem.delete_own` | 删除本人创建的评审问题项 | 全部五个角色 |
| `review.legacy_import` | 预览并确认导入老平台评审 Excel | 全部五个角色 |
| `code_review.form.create` | 通过代码走查表单新增记录 | 全部五个角色 |
| `code_review.form.edit` | 编辑代码走查表单记录 | 全部五个角色 |
| `code_review.form.delete` | 删除代码走查表单记录 | `SUPER_ADMIN`、`ADMIN`、`DIRECT_MANAGER`、`TREE_MANAGER` |
| `system.label_group.manage` | 新增、修改、删除和预览标签组 | `SUPER_ADMIN`、`ADMIN`、`DIRECT_MANAGER`、`TREE_MANAGER` |
| `system.testing_phase.manage` | 新增、修改、启停和删除测试阶段及分组 | `SUPER_ADMIN`、`ADMIN`、`DIRECT_MANAGER`、`TREE_MANAGER` |
| `system.permission.manage` | 修改五个 LDAP 角色的本地权限映射 | `SUPER_ADMIN`、`ADMIN` |
| `system.mirror.config` | 修改数据镜像连接、白名单和 System Hook 配置 | `SUPER_ADMIN`、`ADMIN` |
| `system.mirror.sync` | 测试连接、执行同步、补偿、重试、注册 Hook 和中止任务 | `SUPER_ADMIN`、`ADMIN` |
| `system.mirror.purge` | 删除本地镜像数据 | `SUPER_ADMIN`、`ADMIN` |
| `system.fact.rebuild` | 重建事实层及查看事实诊断 | `SUPER_ADMIN`、`ADMIN` |
| `system.match_mode.config` | 修改兼容模式数据库及 DGM 项目源配置 | `SUPER_ADMIN`、`ADMIN` |
| `system.match_mode.sync` | 执行兼容数据同步和集合/表选项刷新 | `SUPER_ADMIN`、`ADMIN` |
| `system.match_mode.formal_import` | 将兼容数据转入正式数据源 | `SUPER_ADMIN`、`ADMIN` |
| `system.database.refresh` | 在数据库查看中刷新允许浏览的表 | `SUPER_ADMIN`、`ADMIN` |

兼容模式和非兼容模式不得产生两套权限编码。评审删除统一使用 `delete_any` 和 `delete_own`，具体读源差异由数据访问层处理；权限服务只根据当前用户权限、当前记录 `created_by` 和稳定记录标识裁决。

### 5. 取消游客业务访问

1. 后续不再保留游客业务角色。所有数据表格页面、看板和业务 API 都要求用户先登录。
2. 登录接口、静态资源和健康检查继续作为明确白名单。
3. GitLab System Hook 等机器调用接口不属于游客业务访问，应继续使用其专用 secret 或机器身份认证，不能误改为用户 Session 登录。
4. 后端安全策略应采用默认拒绝；未显式列入技术白名单的业务接口必须要求有效 Session，并继续执行细粒度权限检查。

### 6. 评审数据边界

1. 创建人归属只引入到“评审数据管理”页面管理的评审记录和评审问题项，不扩展到其他统计表、看板或业务模块。
2. 评审记录和评审问题项增加可为空的 `created_by` 字段。新增数据时写入当前 LDAP 用户的稳定标识，不使用显示名或角色名作为归属键。
3. 老平台及现有历史评审数据没有可靠创建账号，迁移后 `created_by` 保持空值；不根据操作审计日志、请求 IP、评审负责人、作者、评审专家或责任人反推或补造创建人。
4. 拥有 `review.record.delete_any` 或 `review.problem.delete_any` 的用户，可以删除对应类型的任意评审数据；默认将这两个权限授予 `SUPER_ADMIN`、`ADMIN`、`DIRECT_MANAGER`、`TREE_MANAGER`。
5. 拥有对应 `delete_own` 权限但没有 `delete_any` 权限的用户，只能删除 `created_by` 等于当前登录用户稳定标识的数据；`created_by` 为空的历史数据不能按“本人创建”删除。
6. 上述删除规则在数据库兼容模式开启和关闭时完全一致。兼容模式只能改变评审数据读取或持久化来源，不能改变当前用户已配置的 `delete_any` / `delete_own` 权限结果。
7. 删除接口必须以后端鉴权结果为准；前端按钮可见性只用于交互提示，不能替代后端对权限编码和 `created_by` 的校验。
8. 兼容模式和非兼容模式必须使用同一删除授权服务或同一权限策略，不得分别复制一套角色判断规则。

### 7. LDAP 接口契约要求

LDAP 平台需要向数据采集平台后端提供受保护且稳定的接口，至少支持：

1. 使用用户名和密码执行登录认证；
2. 返回或查询用户稳定标识、用户名、显示名、账号状态及其他需要的基础信息；
3. 返回用户拥有的全部角色编码和角色名称；
4. 首次接入时分页获取全量用户及其角色基础信息；
5. 明确认证失败、账号禁用、账号不存在、请求超时和服务不可用的错误语义。

当前 LDAP 登录响应提供角色编码；数据采集平台后端在登录编排中调用角色接口补充中文角色名称，并将身份主数据镜像到本地。若首次全量接口权限暂不可用，不能影响已通过 LDAP 密码认证的当前用户登录；管理员完成首次同步后，后续登录只刷新当前用户身份。

## 未采用方案

### 跳转到 LDAP 页面再回跳

不采用。数据采集平台保留自己的登录体验，不实施当前版本的浏览器重定向式 SSO。

### 浏览器直接使用 LDAP JWT

不采用。否则数据采集平台需要成为 LDAP JWT 资源服务，并承担令牌签名校验、吊销、续期和前端令牌存储等额外契约；这也会破坏现有 Session 和 CSRF 模型。

### 将数据采集平台权限放入 LDAP

不采用。两套平台独立演进，LDAP 角色只作为身份元数据，本平台权限在本地配置。

### 定时同步 LDAP 用户和角色

不采用。首次全量建立本地基线，之后通过登录时刷新单个用户，降低同步任务和故障耦合。

### 缓存 LDAP 密码以支持离线登录

不采用。LDAP 不可用时只维持已有 Session，新登录明确失败。

### 直接编辑 LDAP 身份镜像表来配置权限

不采用。LDAP 身份和角色是外部只读主数据；本地授权必须写入独立的权限目录及角色权限映射表，并统一在独立的“权限设置”页面维护，不能把 LDAP 镜像表或“数据库查看”变成权限配置入口。

## 结果与约束

1. LDAP 角色或状态变化不会影响已经建立的 Session，直到重新登录、主动退出或 Session 过期。
2. 数据采集平台必须持久化本地身份快照、LDAP 角色镜像、本地权限目录和角色权限映射，但不能成为 LDAP 角色的创建端。
3. 权限模型从当前单一 `AuthRole` 扩展为多角色权限并集；实现时不得继续用单个枚举角色承载全部页面和操作授权。
4. 所有业务页面改为登录后访问时，必须同步收紧后端 GET 和写接口，不能只隐藏前端导航。
5. 操作审计继续记录真实登录用户和权限变更；评审数据的创建归属只以新增时写入的 `created_by` 为准，审计日志不作为归属回填来源。
6. 评审数据删除必须跨兼容模式复用同一授权策略：`delete_any` 允许删除任意对应记录，`delete_own` 只允许删除本人创建且 `created_by` 非空的对应记录；不得读取或比较任何权限等级。
7. 本 ADR 同时作为当前实现的边界说明；新增权限或 LDAP 字段必须遵循本文件约束，并补充对应契约测试和迁移说明。
