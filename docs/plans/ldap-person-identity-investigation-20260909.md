# LDAP 人员选择治理与跨模块姓名统一调查报告

## 进度与中间物

- 状态：2026-09-18 只读调查完成；推荐方向待用户确认，尚未进入实现。
- 本次交付：更新本报告，替代 2026-09-09 版本中的过强结论；未修改业务代码、数据库、LDAP 配置、权限或黄金快照。
- 证据基线：采集平台本地 `main@7565afd5` 及调查时工作树；LDAP 服务端本地 `D:/projects/ldap`、`HEAD=d0dc914` 及调查时工作树。两个仓库均有既有未提交改动，本次未改动它们。
- 验证范围：读取用户截图、平台文档、前后端代码、迁移、测试代码及 LDAP 服务端实现；未连接数据库或内网接口，未运行应用、自动化测试、同步或事实重建。静态结论不等于内网部署版本、人员数量和映射覆盖率的实测结果。
- 恢复线索：下一步先确认本报告第 7 节的产品边界和上游契约，再制定实施计划；不要把旧报告中的姓名映射清单直接当作可执行迁移。

## 1. 结论摘要

**用 LDAP 权威姓名治理人工选人是可行且推荐的；但不能仅把 `users.name` 查询替换成 LDAP 姓名查询，也不能把“LDAP 用户 ID”直接理解为永久人员身份。**

1. **新建/编辑评审可以独立治理，不需要先统一所有 GitLab 用户。** 人工选择应采用身份键作为值、LDAP 权威姓名作为标签，保存、专家匹配、导入和旧记录编辑一并适配。
2. **当前脏候选有两个入口：GitLab 姓名与历史姓名补充。** 只换 GitLab 来源，历史拼音和错名仍会回流；只保留中文也不能证明姓名正确、在职或人员唯一。
3. **平台已有 LDAP 用户镜像，但不是持续维护的完整在职名录。** 首次登录尝试拉目录，以后主要刷新登录者；完整性、删除、离职、新入职和改名传播都存在缺口。
4. **本地 LDAP 上游允许工号修改、账号删除后复用。** `userId` 只在普通编辑时不可改；数据库 `id` 是账号记录身份，不是跨删除重建的永久员工身份。必须先明确生命周期契约。
5. **设置中的“人员标签组”不是简单选人表单。** 它目前是跨页面复用的字符串筛选集合。若直接存 LDAP ID/中文名，而系统测试、客户问题仍按 GitLab 姓名筛选，会出现“选了正确的人却查不到记录”。
6. **系统测试、客户问题、代码走查保持原样是安全且符合本次需求的边界。** 将它们全面统一为 LDAP 姓名可作为后续工作，但需要来源身份映射；只改字符串会影响筛选、人员统计、榜单和导出，不属于可以顺手完成的视觉修整。

## 2. 当前链路：问题不只在 GitLab 用户表

### 2.1 候选来源与人员匹配

当前链路为：

```text
GitLab users.name + 正式评审历史姓名 + 兼容快照姓名
    → value=姓名、label=姓名
    → 前端提交姓名
    → 后端保存姓名，并用姓名关联专家与问题项
```

- `ReviewDataMirrorOptionRepository.loadUserNames()` 只读取 `name`，排除镜像删除和空值；没有 GitLab 账号状态、在职、中文姓名或真人账号限制。[P1]
- `ReviewDataFilterOptionService` 将镜像、历史负责人、历史专家/作者以及兼容姓名合并，最终只做空白清理和字符串去重，返回 `value=label`。负责人池与专家/作者池范围不同，但都含历史补充。[P2]
- 因此，同一个人的中文名、拼音名、旧名可能并列；两个不同人的完全同名又可能被合成一个选项。截图显示的混杂与此链路一致，但不能仅凭截图判断每个账号属于谁。
- 正式评审的 `review_owner`、`author_name`、专家 `expert_name`、问题 `reviewer_name/owner_name` 都是姓名字符串；描述作者、内容评审人也需要纳入身份改造范围。[P3]
- 专家占位项的补建、清理、首次填写和删除最后一个问题后的专家移除，都按归一化姓名匹配。换成新中文名而不迁移关联，会把同人视作新人；同名不同人仍不能区分。现有实现已保护含真实内容的问题，不应误报为仍会任意覆盖同名问题。[P4]

### 2.2 范围矩阵

| 场景 | 当前事实 | 建议处理 |
|---|---|---|
| 新建/编辑评审负责人、专家、作者 | 提交并保存姓名，候选混入历史值 | 改成目录身份选择，服务端校验有效身份 |
| 新建/编辑问题专家、责任人 | 复用候选池，专家还混入当前记录姓名 | 与主记录同时改，保留旧值回显与明确的待关联状态 |
| Excel 导入默认人员 | 默认负责人、专家允许自由创建；默认作者为文本；文件内姓名还能覆盖默认值 | 默认人员改目录选择；历史文件保留原文并做受控关联，不能按姓名自动认领 |
| 评审历史筛选 | 当前比较的是历史姓名字符串 | 必须继续能查旧名、离职者及未关联记录，不能直接复用“新评审可选人员”名单 |
| 设置 → 标签组 → 人员 | 全局 `person` 候选取镜像姓名；组展开使用字符串成员值 | 与人工分配人员分开设计，详见第 4 节 |
| 设置 → 下拉框选项设置 | 当前仅注册评审项目名称，没有人员字段 | 不存在现成的人员黑白名单可直接切源；若扩展，应另行明确身份规则 |
| 系统测试、客户问题 | 事实构建关联 GitLab 用户；筛选取业务范围 | 本轮保留原始来源和语义 |
| 代码走查 | GitLab 关联、评论文本、老平台数据等混合来源 | 本轮保持原样，不能假设全部来自 `users.name` |
| 质量达人榜、BI 按人分组 | 存在按姓名关联/聚合的逻辑 | 不随评审候选治理改动 |

导入入口已明确存在，旧报告对此的疑问已解除；仅改前端下拉不能阻止 Excel/API 再写入任意姓名。[P5]

## 3. LDAP 可复用什么，又缺少什么

### 3.1 已有能力

平台不是直接查询 LDAP 协议目录，而是调用身份平台 HTTP API：登录、当前用户、角色、用户列表。`LdapUserData` 已接收 `id/userId/realName/employeeNo/employmentStatus` 等字段；本地 `platform_ldap_users` 保存它们，`user_id` 为主键，`ldap_id`、工号均不是本地唯一键。[P6]

已有 `displayNameForUser(userId, fallback)` 可按账号查询姓名，但它会在无姓名时返回 fallback；不能原样用它保证“新选人只显示经过确认的中文姓名”。普通业务候选还需要最小字段接口，不应借用能暴露手机号、邮箱等信息的数据库查看入口。[P7]

### 3.2 同步完整性是实际缺口

现有认证流程在成功登录后尝试一次目录同步；`initial_full_sync_completed` 为真后不再全量拉取，仅继续刷新登录者。目录逐人 upsert，没有消失用户对账，也没有周期性完整刷新。目录抓取失败不阻止该用户登录。[P8]

更重要的是，本地 LDAP 上游 `/api/v1/users` 按调用者权限过滤：`USER_READ` 可以查看全部候选，仅有 `USER_READ_SELF_AND_SUBORDINATE_TREE` 则只有本人及下属。接口返回成功并不等于目录完整，而采集平台当前遍历成功即标记首次全量完成，空数组也会完成。[L1][P8]

因此，后续目录维护需要：

- 使用明确拥有完整目录读取能力、经授权的接入方式；不能依赖随机登录者的可见范围，也不能凭空假设上游已有机器令牌接口。
- 周期刷新与可控手工刷新，记录最后成功时间和失败状态；只在确认完整抓取后发布新目录。
- 对上游消失者停止新选择，但保留历史身份引用；失败、权限缩小或不完整响应不能被当作全员离职。
- 将目录变更纳入相关候选/名称投影的缓存版本。现有评审快照指纹没有 LDAP 目录版本，仅改库中姓名并不能保证候选立即刷新。[P9]

这些是业务名录维护要求，不表示本轮要改变既有 Session、RBAC 或“下次登录更新账号状态”的安全契约。

### 3.3 “稳定 ID”的准确含义

| 字段 | 本地 LDAP 上游实际保证 | 适用判断 |
|---|---|---|
| `userId` | 普通更新禁止修改；删除会回收旧字符串，之后可重新创建同 ID 账号 | 账号标识，不能裸值作为永久人员身份 |
| `employeeNo` | 当前唯一约束；普通更新允许修改；删除会清空并释放工号 | HR 交叉核验属性，不宜直接充当不可变主键 |
| `id` → 平台 `ldap_id` | 本库自增账号记录主键；普通改名不变，新建账号通常获得新 ID | 同一身份服务数据库生命周期内，比可复用账号名更合适的外部记录键；不是跨实例、删建或库重置的永久人员键 |
| `realName` | 可修改，没有姓名唯一约束，也不保证纯汉字 | 展示属性，不是身份键 |

证据：普通更新直接写姓名和工号，同时校验 `userId` 不变；删除把 `userId` 改成回收值并清空工号，创建重新检查当前冲突后插入新记录。[L2][L3]

历史迁移 V24/V25 也曾整体重设账号键。因此旧报告所谓“两个永久稳定 ID”不能成立。

**建议的身份原则**：限定身份服务实例，以不可混淆的账号实体身份建立业务引用；检测同 `userId` 对应 `ldap_id` 改变的情况，禁止自动继承旧人员关联。若要保证同一个自然人在多账号、返聘、删建之后仍为一人，需要上游永久人员标识或人工确认的身份关联，不能靠姓名/工号相等猜测。具体数据模型待批准后设计，不在本报告中定稿。

### 3.4 正确姓名与可选状态还需要业务口径

- LDAP 是推荐的权威姓名来源，但不能由“改用 LDAP”推出“所有姓名一定纯中文且全局唯一”。权威姓名可能有数字后缀；不能自行去数字、同音纠错或拼音反推汉字。
- “一人只有一个选项”应按身份去重；若确有同名不同人，应展示姓名加部门/工号作辅助区分，不能吞掉其中一人。若领导要求不显示任何后缀，应先在权威名录确认真实姓名字段。
- 离职、账号禁用、系统账号不是同一概念。在职但无平台登录权限的人是否能担任专家，需业务确认，不能直接把“能登录”等同“能被选择”。
- 当前本地上游返回 `accessAllowed`，没有旧式 `status` 字段；采集平台 DTO 接收 `status` 却未接收 `accessAllowed`，未知字段会忽略。若按本地当前接口接入，不能依赖这个 `status` 判断可选状态。内网是否部署同版本仍需核对。[L4][P6]

## 4. 设置页：标签组是必须单独处理的耦合点

`LabelValueQueryService` 的 `person` 在页面分支之前统一读取 GitLab 姓名；现有 `pageKey` 参数不会自动把它切成评审 LDAP 名单。`LabelGroupService` 展开组时使用 `member_value`，不是 `display_name`。[P10]

以虚构例说明：某人的 LDAP 姓名是“张三”，GitLab 历史显示名是 `zhangsan`。

- 把组成员值改为“张三”：仍保存 `zhangsan` 的客户问题不会因此命中。
- 只把组成员标签显示为“张三”，保留值 `zhangsan`：可保留原匹配，但若此人有多个旧名，就不能自动成为“一人一个成员”；仅凭旧名还可能与其他人重名。
- 直接把组成员值改成 LDAP ID：所有继续比较姓名字符串的调用方都需要适配。

**结论：只换候选或只按页面分源，都不足以解决全局标签组的跨页面身份语义。** 当前产品明确定义标签组是按类型组织的原始值集合，不是员工组织模型，不能静默把全部字符串组改成人员组。

推荐区分两个需求：

1. 给评审分配真实人员：用目录身份选择，不依赖通用字符串标签组。
2. 设置“一个人员集合”并跨模块筛选：需要人员身份到各来源用户的明确关联，以及调用方按当前字段解释该关联的契约。已有通用标签组的非人员成员、动态规则、旧筛选值必须保持正确；没有可靠映射的历史文本不能承诺精确归人。

如果“设置里选人也必须全部正确且一人一项”包含全局人员标签组，那么身份关联与标签组调用方改造必须纳入同一验收范围，不能以“评审下拉已干净”宣称整个需求完成。

## 5. 其他模块能否一起统一

### 5.1 现有来源并不一致

- Issue 作者来自 `issues.author_id → users.name`；指派人由用户关系聚合为姓名；`handler_name` 当前取同一指派集合，但作为独立字段输出。修复人取符合修复状态模板的评论作者，不等同当前指派人。[P11]
- MR 作者/审核人等部分字段有用户关系，但走查人还可来自评论“走查人”文本、人工表单和占位状态。部分字段在聚合成姓名字符串后已不携带全部人员 ID。[P12]
- 老平台兼容走查保存 `author/assignee/merged_user_name` 等文本，转正式不会凭空补出 LDAP 身份。现有 MR 提交事实也不能提供完整 Git commit 作者身份。[P12][P13]
- GitLab `users.id` 必须限定真实 GitLab 实例。当前通用镜像使用 `default` 来源和 `ods_gitlab_*` 表；不能把兼容业务标签 `cc/dgm` 不经核实就视作完整账号命名空间。[P14]
- 当前代码没有 GitLab 用户到 LDAP 用户的关联链路。用户整行镜像可能包含 username/email，但其相等不证明同人；常规来源目录未登记 `identities` 作为现成身份桥。若使用 `provider/extern_uid`，应先核实真实源表、目录提供者和键含义。[P15]

### 5.2 风险不止“名字变了”

| 做法 | 后果 |
|---|---|
| 直接修改 GitLab/ODS `users.name` | 破坏原始证据边界；ODS 手改会被同步覆盖，还不能保证走过完整事实失效链 |
| 在事实构建中统一替换姓名 | 影响历史事实、搜索、筛选、人员聚合和快照，可能改变按人统计归属 |
| 把质量达人名单换中文，业务事实不改 | 名单精确匹配失效，代码量等分母可能取零，并非仅显示不同 |
| 仅网页换名、Excel 不改 | 页面、导出和筛选条件不一致 |
| 按当前 LDAP 同名自动回填旧记录 | 可能把已离职同名者、复用账号的历史贡献归给当前人员 |

质量达人榜实际以 `member.name()` 查作者代码量并关联缺陷；BI 人员维度目前也按来源中的名称值分组。合并旧名为一个人员，是统计分组语义改变，必须单独审批和验证。[P16][P17]

### 5.3 可行的后续方向

若后续确需统一，先建立经过验证的 `(实际来源实例, 源用户ID) → 目录身份` 关联，允许同一个人关联多个来源账号。仅保留姓名的旧记录需独立核验；邮箱、账号、中文名相等只能作为候选证据，不作为自动确认依据。

可先对可靠映射的记录提供统一显示名，同时保留原始来源名、角色和追溯信息；不要同时自动重算“按人”聚合。未映射/歧义值保持原文并报告覆盖不足。要做到跨模块一人一行统计，则还需迁移人员分组键、标签组、筛选、导出与缓存，不能只增加显示函数。

**本轮建议不执行该扩展。** 不统一 GitLab 姓名并不妨碍新评审从 LDAP 选择正确人员。

## 6. 推荐范围与验收标准（待批准）

### 6.1 推荐顺序

1. **先明确上游契约**：姓名权威字段、账号实体键、重建/复用规则、在职与可选条件、完整目录读取方式及内网实际版本。
2. **建立可持续的人员目录读取能力**：完整性判定、目录刷新、历史引用保留、最小字段业务接口、候选缓存更新；不复用用户密码缓存，不扩大普通用户目录权限。
3. **完整改造评审人工输入链**：创建、编辑、问题、专家同步、描述/内容作者及导入默认人员一起改。新身份与原始姓名不是两套竞争真相：身份用于关联，原始文本仅用于历史证据或待关联记录。
4. **明确历史迁移策略**：新记录必须有有效身份；历史记录允许未关联，正常回显且可筛选；人工确认关联后整棵评审一致迁移，不按中文同名批量认领，不改变原创建人归属。
5. **设置人员集合单独验收**：若包含通用标签组跨模块选人，先解决来源身份匹配及已有组迁移，再宣称这部分完成；不能用 LDAP 候选替换掩盖筛选失效。
6. **GitLab 自动采集人员暂不变**：源表、ODS、事实计算、系统测试/客户问题/代码走查展示及其原有筛选均保持。

`created_by` 是权限归属，不是负责人或评审专家。当前平台按登录 `userId` 保存并检查本人权限；人员治理不得顺手改它，也不得以新负责人回填历史空创建人。账号复用对旧归属的潜在影响需单独做安全评估。[P18]

### 6.2 必须验证的场景

- 同一身份多个旧名只在新选人目录中出现一项；真实同名不同身份均保留且可辨别。
- 改名不改变专家身份；增删专家不误删真实问题、不制造重复占位项；删除最后一个问题正确同步对应专家。
- 新入职、离职、禁用、特殊账号、目录不可达及无权查看全目录均有明确行为；部分目录不能覆盖完整目录。
- 同 `userId` 删除重建且 `ldap_id` 改变时，不继承旧业务身份；工号修改不丢历史关联。
- 历史英文/拼音/未关联人员可回显和筛选；改动无关字段不强制把整条历史记录认领给当前人员。
- Excel 默认人员、文件人员及直接 API 写入均不能绕过新记录身份校验；历史导入的未关联状态不得静默伪装为目录人员。
- 标签组选择、保存、跨页面展开、导出条件与实际筛选结果一致；不能只验下拉中文标签。
- LDAP 姓名/状态变化使相关候选或显示快照更新；所有未纳入范围的 GitLab 展示和统计保持不变。
- 上线前按仓库门禁审阅有意变化、更新受影响黄金快照并回归；调查阶段不运行或改写黄金基线。

## 7. 实施前仍需确认的事项

1. “设置选择用户名字段”是否包含全局人员标签组跨系统测试/客户问题筛选；若包含，必须接受其大于“换下拉源”的改造范围。
2. 可选对象是全部在职人员，还是还要排除禁用/无准入/系统账号；外部专家及历史离职人员如何处理。
3. LDAP `realName` 是否已由业务确认；数字后缀是否就是权威显示名；同名时是否允许显示部门/工号辅助区分。
4. 对历史评审只要求保留并可人工补关联，还是要审核后批量归一；是否要求历史导出始终反映最新姓名。
5. 内网 LDAP 实际版本、完整目录接入权限、账号重建/复用政策及多账号归人依据。当前代码可说明机制，但无法代替这些现场契约。

## 8. 对旧调查的更正

旧报告的本地查询记录只能代表 2026-09-09 的一次快照，本轮未重跑，不能继续表述为当前内网实测。

- “user_id/employee_no 都稳定、都能直接作永久人员主键”：更正为第 3.3 节的有限保证。
- “LDAP 是完整、干净的在职目录，离职者已经全部移除”：不能作为机制保证；目录可保留离职者，API 有权限过滤，本地镜像也不对账删除。
- “当前姓名无重名，因此旧名可自动映射当前人员”：推论不成立；当前唯一无法证明历史身份唯一，也无法识别账号复用。
- “LDAP 所有名字一定是正确纯中文名”：代码不提供此保证；不能擅自删除权威姓名后缀。
- 原文出现的 264/266 在职统计口径不一致，本轮不沿用这些数量，也不沿用未经历史身份核验的 17 组自动迁移建议。
- “可能没有 Excel 导入写入口”：已确认存在，纳入改造边界。
- “只按页换 person 来源即可解决”：只能改变候选来源，不能解决全局字符串标签组的跨字段身份匹配。

## 9. 主要代码证据索引

平台路径均相对 `D:/projects/data_collection_platform/`；LDAP 路径相对 `D:/projects/ldap/`。行号对应本次读取状态。

| 编号 | 文件与行号 | 证实内容 |
|---|---|---|
| P1 | `backend/src/main/java/com/data/collection/platform/service/ReviewDataMirrorOptionRepository.java:31` | GitLab 姓名候选 SQL |
| P2 | `backend/src/main/java/com/data/collection/platform/service/ReviewDataFilterOptionService.java:67`、`:144` | 历史池合并、姓名即选项值 |
| P3 | `backend/src/main/java/com/data/collection/platform/service/ReviewDataRecordWriteRepository.java:83`；`ReviewDataExpertRepository.java:28`（同目录） | 主记录/专家按姓名保存 |
| P4 | `backend/src/main/java/com/data/collection/platform/service/ReviewDataRecordCommandService.java:216`、`:287`、`:383` | 专家与问题按姓名匹配；真实问题保护 |
| P5 | `frontend/src/views/review-data/ReviewDataLegacyExcelImportDialog.vue:158`；`backend/src/main/java/com/data/collection/platform/service/ReviewDataLegacyExcelImportService.java:64` | 导入默认人员与真实写入口 |
| P6 | `backend/src/main/java/com/data/collection/platform/service/LdapPlatformClient.java:71`、`:112`；`backend/src/main/resources/db/migration/V20260720_01__ldap_identity_and_local_permissions.sql:3` | 用户 API、DTO、本地身份键 |
| P7 | `backend/src/main/java/com/data/collection/platform/service/PlatformIdentityService.java:37`、`:105` | 按账号 upsert、姓名解析 |
| P8 | `backend/src/main/java/com/data/collection/platform/security/LdapPlatformAuthenticationProvider.java:48`、`:81` | 登录刷新、一次目录同步和完成标记 |
| P9 | `backend/src/main/java/com/data/collection/platform/service/PageRecordSnapshotService.java:340` | 现有评审来源指纹未纳入 LDAP |
| P10 | `backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelValueQueryService.java:48`、`:84`；`LabelGroupService.java:192`（同目录）；`backend/src/main/java/com/data/collection/platform/service/dropdown/DropdownOptionFieldRegistry.java:35` | person 全局姓名池、按原始值展开、只注册项目下拉 |
| P11 | `backend/src/main/java/com/data/collection/platform/service/GitlabFactSourceSqlProvider.java:58`、`:68`、`:100` | Issue 人员字段血缘 |
| P12 | `backend/src/main/java/com/data/collection/platform/service/GitlabFactSourceSqlProvider.java:217`、`:382` | MR 多种人员来源与提交事实字段 |
| P13 | `backend/src/main/java/com/data/collection/platform/service/CodeReviewMatchModeSyncService.java:358`；`LegacyPlatformFormalImportService.java:252`（同目录） | 兼容与交接保留来源人员文本 |
| P14 | `backend/src/main/java/com/data/collection/platform/service/GitlabSourceInstanceSupport.java:12`、`:43` | 当前通用来源及镜像命名 |
| P15 | `backend/src/main/java/com/data/collection/platform/service/GitlabSourceScanSqlBuilder.java:31`；`backend/src/main/java/com/data/collection/platform/service/sync/GitlabSourceLineageCatalog.java:21` | 动态源列与已登记同步来源 |
| P16 | `backend/src/main/java/com/data/collection/platform/service/analytics/QualityBoardOtherQueryService.java:141`、`:380` | 姓名参与榜单匹配和数值计算 |
| P17 | `docs/bi-dashboard/BI看板数据来源与计算口径核对表.md:121`、`:270` | BI 来源姓名维度，非全局员工身份 |
| P18 | `backend/src/main/java/com/data/collection/platform/service/ReviewDataAuthorizationService.java:40`；`docs/architecture.md:115`；`docs/decisions.md:16` | 创建归属、历史空值和认证授权边界 |
| L1 | `src/main/java/com/company/idm/interfaces/user/UserController.java:62`；`src/main/java/com/company/idm/application/user/UserReadScopeService.java:41`、`:77` | 用户列表受权限范围约束 |
| L2 | `src/main/java/com/company/idm/application/user/UserApplicationService.java:141`、`:200`、`:700`、`:732` | 创建、可改工号/姓名、不可普通改账号、删除回收 |
| L3 | `src/main/java/com/company/idm/infrastructure/persistence/repository/MybatisUserRepository.java:249`、`:300` | 新账号插入，删除释放工号及账号 |
| L4 | `src/main/java/com/company/idm/interfaces/user/UserResponse.java:9`；`UserResponseAssembler.java:34`（同目录） | 当前上游身份与准入字段，不含旧 status |

最终建议：先完成“可靠目录 + 评审人工选人身份化”，明确设置人员标签组的独立验收边界；GitLab 自动采集模块保留原样。全面中文姓名统一不是第一步的前置条件，也不应在未经身份核验时顺带实施。
