# 调查报告：以 LDAP 稳定人员ID 为权威源治理评审数据重名/脏数据的可行性

> 性质：**调查报告（findings/evidence only）**。按用户要求，本文不含实现方案与代码改动；实现设计（数据模型、注入点、迁移、设置页等）待用户批准方向后另行产出。
> 关联：本报告为工作单元「评审数据人名治理」的证据基线，取代既有计划 `docs/plans/review-person-name-alias-mapping-20260909.md` 中"以 GitLab 镜像 + 花名册 XLSX 为权威源"的假设；该计划待本报告确认后修订。
> 数据快照：本地库 `127.0.0.1:15432/qaflex`，采集时刻 2026-09-09。生产 LDAP 结构一致、具体人员以生产目录为准。

## 0. 术语澄清（消除歧义）

- 用户所说"**飞书ID**"，经确认**指的是 LDAP 中的稳定人员ID**，而非飞书开放平台的 `open_id`。
- 本报告据此把"稳定人员ID"落到实证对象：LDAP 目录的 `user_id`（LDAP uid，如 `xuh`/`wx`/`lizhi2`）与 `employee_no`（工号）。二者均唯一、稳定、已同步进采集平台。

## 1. 结论摘要（可行性判定）

**可行，且比原"纯别名映射"方案更根治。** 依据：

1. LDAP 目录（`platform_ldap_users`，已同步进采集平台）是一份**干净的"当前在职权威名录"**：264 人 `ACTIVE`、`real_name` **全局唯一（0 重名）**、离职者已移除。质量远优于 GitLab 镜像（111 个离职账号仍在、缺在职者、显示名与 HR 不符）与花名册 XLSX（静态快照）。
2. 每人具备**两个稳定ID**（`user_id`、`employee_no`）+ 权威 `real_name` + `employment_status`；平台**已有 `displayNameForUser(userId)→real_name` 的按ID解析能力**。
3. LDAP `real_name` **全量验证并纠正**了此前基于 GitLab/花名册推导的种子清单（详见 §5），证明它是比人工交叉核对更可靠正名权威。

**不可回避的硬约束**：评审数据（正式表 + 老平台 Mongo 快照）以**姓名字符串**存储，非ID。正确名/拼音名可自动匹配 LDAP，但真脏名（错字、同音、离职后缀）匹配不上，**仍需别名映射**——LDAP 不替代别名表，而是给别名表提供权威、稳定、可校验的目标，并可把下拉候选源直接换成干净目录。

## 2. 调查方法与证据来源

- **LDAP 服务端源码**：`D:/projects/ldap`（Spring Boot + Vue 的身份管理服务，含飞书同步能力）。读取其 `User` 实体、`UserResponse`、`UserResponseAssembler`、`FeishuUserRemoteService`、迁移 `V1/V23/V24/V25` 等。
- **采集平台 LDAP 集成代码**：`LdapPlatformClient`、`LdapPlatformAuthenticationProvider`、`PlatformIdentityService`、`PlatformPermissionService`、迁移 `V20260720_01/02`。
- **本地库实测**：对 `platform_ldap_users`（270 行）执行只读查询（user_id 格式统计、real_name 重名检测、在职状态分布、歧义名核对、种子全量校验）。查询脚本见 `.tmp/query-ldap-*.sql`，结果见 `.tmp/query-ldap-*.txt`。

## 3. 发现一：采集平台 LDAP 集成现状

- 通过 `LdapPlatformClient` 以 **HTTP API**（`PLATFORM_LDAP_BASE_URL=http://127.0.0.1:28081`）访问 LDAP：
  - `POST /api/v1/auth/login` → 登录（返回 accessToken）
  - `GET /api/v1/auth/me` → 当前用户
  - `GET /api/v1/roles` → 角色
  - `GET /api/v1/users` → **全量用户目录**
- 用户数据落本地镜像表 `platform_ldap_users`，字段含：`user_id`(PK)、`ldap_id`、`real_name`、`email`、`intranet_email`、`mobile`、`employee_no`、`dept_name`、`dept_code`、`job_title`、`direct_leader_raw`、`leader_ref`、`account_status`、`status`、`employment_status`、`ldap_dn`、`last_login_at`、`source_synced_at`。
- `LdapUserData` DTO 标注 `@JsonIgnoreProperties(ignoreUnknown=true)`：LDAP 若返回未映射字段会被静默丢弃。**当前 DTO 无 feishu/open_id 字段。**
- 平台已有 `PlatformIdentityService.displayNameForUser(userId, fallback)` → 查 `platform_ldap_users.real_name`，即**已具备"按稳定ID解析权威显示名"的现成能力**。

## 4. 发现二：LDAP 服务端身份模型（为何 user_id 是 uid 而非 open_id）

- LDAP 服务 `sys_user` 身份列经历重键：`V24__user_identifier_rekey` + `V25__rename_user_identity_column` 把 `username` 置为 `external_id`（存在时）并 `CHANGE COLUMN username user_id`，随后 **`DROP COLUMN external_id`**。
- `FeishuUserRemoteService.fetchUsers()` 调飞书 `contact/v3/users`（`user_id_type=open_id`），取 `open_id` 作为该来源用户的标识 → 即**只有"飞书来源(sourceType=FEISHU_*)"用户，其 `user_id` 才是飞书 open_id**。
- `User` 实体与 `UserResponse` 字段：`id,userId,realName,email,intranetEmail,mobile,employeeNo,deptName,deptCode,departmentPath,jobTitle,directLeaderRaw,leaderRef,accountStatus,partTime*,permissionLevel,accessAllowed,employmentStatus,sourceType,ldapDn,roleCodes`。**无任何独立 feishu/open_id/union_id 字段**；所有迁移中也未新增此类列。
- **实测印证**：采集平台 `platform_ldap_users` 270 人的 `user_id` **全部为 LDAP 拼音 uid**（`ou_`/`on_` 开头 = 0），说明当前目录是 **LDAP 来源**、非飞书来源 → 现网拿不到 open_id，但拿得到 LDAP uid + 工号这两个稳定ID（正是用户所指"稳定人员ID"）。

> 补充：若未来确需飞书 `open_id`，需在 LDAP 服务端改动（改走飞书同步使 `user_id=open_id`，或新增 `feishu_id` 字段并纳入 `/api/v1/users` 响应）。对本治理目标而言无额外收益，非必需。

## 5. 发现三：LDAP 目录实测（决定性数据）

### 5.1 目录整体质量
- 总人数 **270**；`employment_status`：**ACTIVE 264**、空值 6（含 `admin/超级管理员`、`chenkui/陈魁` 等系统或特殊账号）。
- **`real_name` 重名检测：0 行**（同 `real_name` 多 `user_id` 不存在）→ **目录内姓名唯一，name→人 在目录内天然无歧义**。
- 离职者不在目录中（对比 GitLab 镜像 111 个离职账号仍进候选）。
- **两个稳定ID均唯一且全覆盖（实测）**：`user_id`（LDAP 登录账号/拼音 uid，如 `dll`/`xuh`/`lizhi2`）与 `employee_no`（**工号**，如 `0163`/`2481`）**是两个不同字段**；各 270/270 distinct、0 空值、工号 0 重复 → 二者都可作稳定人员主键。工号是**含前导零的字符串**（`0102`/`0163`/`0902`；`admin` 特殊为 `E0001`），**不可当整数处理**。平台现有约定以 `user_id` 作人员键（`platform_ldap_users` 主键、`displayNameForUser(userId)`、`review_records.created_by` 均存 `user_id`）；工号更贴近 HR，可作交叉校验/HR 关联。

### 5.2 歧义/后缀名在 LDAP 的真实形态
| LDAP uid (`user_id`) | `real_name`（权威正名） | 工号 | 状态 |
|---|---|---|---|
| liujiaqi | 刘佳祺 | 1760 | ACTIVE |
| liumin | **刘敏2** | 1108 | ACTIVE |
| mengxiuping | 孟秀平 | 2602 | ACTIVE |
| xuh | **徐昊2** | 2481 | ACTIVE |
| lizhi2 | **李志** | 2796 | ACTIVE |
| dll | 狄林林 | 0163 | ACTIVE |
| wx | **王旭3** | 2632 | ACTIVE |
| baixinhui | 白欣慧 | 1778 | ACTIVE |
| xsn | 邢胜南 | 0164 | ACTIVE |
| chenjiale | 陈嘉乐 | 3011 | ACTIVE |
| chenyihui | 陈艺珲 | 2609 | ACTIVE |
| gaoyang | 高扬 | 2679 | ACTIVE |
| gaoguanmeng | 高贯孟 | 2156 | ACTIVE |

关键观察：
- 目录里**每个歧义姓名只保留一个在职者**（徐昊只有"徐昊2"、王旭只有"王旭3"、李志只有一个"李志"），离职的同名者已移除 → 从源头消除"选错人"。
- **后缀并非统一规则**：`xuh`/`wx`/`liumin` 的正名**带后缀**（徐昊2/王旭3/刘敏2），而 `lizhi2` 的正名**不带后缀**（李志）。→ 说明**必须以 LDAP `real_name` 为准逐个核定，不能靠"后缀最大=正名"这类启发式**。

## 6. 发现四：种子清单全量校验与两处纠正

以 LDAP 目录全量校验此前拟议的 17 组映射（查询同时列入拟议 canonical 与拟议 alias）：

- **17 个拟议 canonical 全部命中 LDAP 且 ACTIVE**：刘佳祺·杨晓雨·白欣慧·邢胜南·陈艺珲·陈嘉乐·孟秀平·张梓瑜·张佳旺·燕欣睿·高扬·狄林林·高贯孟·徐昊2·王旭3·李志·刘敏2。
- **所有拟议 alias（脏名）在 LDAP 全部查无**：刘佳琪·杨晓宇·白新慧·形胜南·陈艺辉·陈家乐·孟秀萍·张子瑜·张家旺·燕新睿·高杨·狄琳琳·李志2·刘敏·徐昊·王旭 → 确认它们不是目录内真名。

### 两处纠正（相对既有计划）
1. **删除 `李志 → 李志2`**：LDAP 正名是 **"李志"**（uid `lizhi2`, ACTIVE）；"李志2"只是 GitLab 显示名、在 LDAP 查无。评审数据里的"李志"**已等于权威正名，无需映射**。（此前基于 GitLab + 时间线得出的 `李志→李志2` 被 LDAP 权威数据推翻。）
2. **新增 `刘敏 → 刘敏2`**：LDAP 正名是 **"刘敏2"**（uid `liumin`, ACTIVE）；评审数据里的"刘敏"（本地遗留 15 条）需映射到"刘敏2"才对齐权威正名。（此前"刘敏与刘敏2同账号故不映射"的结论，在"以 LDAP `real_name` 为 canonical"的标准下应改为映射。）

### LDAP 校验后的种子清单（17 组，全部 canonical 命中在职目录）
| # | alias（脏名，LDAP 查无） | canonical（LDAP `real_name`, ACTIVE） | LDAP uid | 工号 |
|---|---|---|---|---|
| 1 | 刘佳琪 | 刘佳祺 | liujiaqi | 1760 |
| 2 | 杨晓宇 | 杨晓雨 | yangxiaoyu | 1779 |
| 3 | 白新慧 | 白欣慧 | baixinhui | 1778 |
| 4 | 形胜南 | 邢胜南 | xsn | 0164 |
| 5 | 陈艺辉 | 陈艺珲 | chenyihui | 2609 |
| 6 | 陈家乐 | 陈嘉乐 | chenjiale | 3011 |
| 7 | 孟秀萍 | 孟秀平 | mengxiuping | 2602 |
| 8 | 张子瑜 | 张梓瑜 | zhangziyu | 2556 |
| 9 | 张家旺 | 张佳旺 | zhangjiawang | 2833 |
| 10 | 燕新睿 | 燕欣睿 | yanxinrui | 1862 |
| 11 | 高杨 | 高扬 | gaoyang | 2679 |
| 12 | 狄琳琳 | 狄林林 | dll | 0163 |
| 13 | dilin | 狄林林 | dll | 0163 |
| 14 | gaoguanmeng | 高贯孟 | gaoguanmeng | 2156 |
| 15 | 徐昊 | 徐昊2 | xuh | 2481 |
| 16 | 王旭 | 王旭3 | wx | 2632 |
| 17 | 刘敏 | 刘敏2 | liumin | 1108 |

> 注：本清单为**本地目录实测**结果；上线前应以生产 LDAP 目录复核（正名是否仍在职、有无新增脏名）。

## 7. 发现五：拼音脏名与 LDAP uid 的关系（解析红利与边界）

- 部分拼音脏名**恰好等于 LDAP uid**：`gaoguanmeng`（=uid `gaoguanmeng`→高贯孟）。→ 解析时"先按 `real_name` 精确、再按 `user_id`(uid) 精确"可**自动归一这类拼音名**，无需别名。
- 但**并非全部**：`dilin` ≠ 狄林林的 uid `dll`。→ 这类仍需别名表。
- 结论：uid 匹配是"锦上添花"的自动解析层，不能取代别名表。

## 8. 关键约束与边界（硬事实，非方案）

1. **存储是姓名、不是ID**：评审正式表（`review_records.review_owner/author_name`、`review_record_experts.expert_name`、`review_problem_items.reviewer_name/owner_name` 等）与兼容快照（`review_data_match_mode_reports.review_charger/review_experts` 及 contents/problem_details/descriptions 人名列）均以姓名字符串存储。引入 LDAP ID 不改变"历史数据是姓名"的事实。
2. **脏名解析仍需别名**：错字/同音/离职后缀名无法自动匹配 LDAP，必须人工/种子维护 alias→（LDAP 正名 或 稳定ID）。
3. **目录新鲜度缺口**：`platform_ldap_users` 目前**仅首次登录触发一次全量同步**（`synchronizeDirectoryIfNeeded`，受 `initial_full_sync_completed` 标志保护）+ 各用户登录时 upsert 自己；**无定期全量重同步**（现有 `@Scheduled` 均为 gitlab-mirror/match-mode/BI/fact，无 LDAP 目录）。若把 LDAP 当"活"的权威下拉源，需补一个定期/手动"刷新目录"机制（用具备目录读权限的服务账号执行 `client.users()`）。
4. **同步依赖权限**：首次全量同步为尽力而为——登录账号无目录读权限时跳过并保留待同步状态（本地已有 270 行，说明曾以有权账号登录）。
5. **特殊账号**：6 个 `employment_status` 为空者（admin/超级管理员、chenkui 等）需在"作为下拉源"时明确纳入或排除口径（留待方案）。

## 9. 人员数据来源全景与标签组 person 耦合（实测）

### 9.1 各模块人员字段来源
| 模块 | 人员字段 | 来源 | 与评审数据耦合 |
|---|---|---|---|
| 评审数据 | 评审专家/负责人(reviewOwner)/作者 | 下拉候选=`loadUserNames()`（GitLab 镜像 users）+ 历史/快照名 | — |
| 标签组 person 维度 | 候选值=`loadUserNames()`（GitLab，全平台共用一份） | 绑定：评审→reviewOwner、系统测试议题→assigneeName、客户问题→assigneeName | **是：与评审下拉同源，且绑定 reviewOwner** |
| 系统测试议题 | assigneeName(处理人) | issue_fact（GitLab 事实） | 否（数据）；仅经标签组 person 间接 |
| 代码走查 | reviewer/assignee/author/fix_user | GitLab 事实（`GitlabFactSourceSqlProvider` join `ods_gitlab_users`） | 否 |
| 客户问题 | authorName/assigneeName | issue_fact（GitLab 事实） | 否（数据）；仅经标签组 person 间接 |
| BI 看板 | 评审页**无人名输出**；编码/系统测试页用 GitLab 事实 | `review_visible_*` 视图（仅度量）/GitLab 事实 | 否 |
| 质量看板 | fix_user/assignee/代码走查 reviewer | issue_fact + 代码走查事实（GitLab） | 否 |

- `loadUserNames()` 全平台**仅 2 个生产调用方**：`ReviewDataFilterOptionService`（评审下拉）与 `LabelValueQueryService`（标签组 person）。其 SQL 只过滤 `mirror_deleted`、不过滤 `state`。
- 其余模块人员均为"事实构建期 join `ods_gitlab_users` 取名"或"数据驱动的实际参与者"，**不是从 users 表拉全量下拉**。

### 9.2 标签组耦合的实测证据（决定性）
- LDAP 在职 266 人中，**33 人（约 12%）的 `real_name` 在 GitLab 镜像 users 表缺失**（如 **王旭3(wx)**、**刘敏2(liumin)**、李伟6、侯文涛、冉爱华…）。
- 本次种子正名中，**王旭3、刘敏2 在 GitLab 镜像查无**（刘佳祺/徐昊2/李志/狄林林/白欣慧/高贯孟 在）。
- 含义：若评审 `reviewOwner` 归一为 LDAP 正名（如"王旭3""刘敏2"），而标签组 person 候选仍取 GitLab（`loadUserNames`），则**这 33 人无法作为标签组 person 值被选中来筛评审数据** → 标签组按人筛评审会漏。

### 9.3 判定
- **评审改 LDAP → 标签组 person 维度存在真实耦合，必须在方案中处理**（不是"可改可不改"）：至少要让 person 候选能覆盖 LDAP 正名。
- **但标签组 person 是"一份候选跨三页"**：系统测试/客户问题的 `assigneeName` 仍是 GitLab 事实来源；若把 person 候选整体换 LDAP，会与这两页的 GitLab 名产生新的不一致（如 LDAP"李志" vs GitLab 事实里的"李志2"）。
- **系统测试/代码走查/客户问题/BI 的人员数据本身：与评审解耦，保持原样即可**（改评审不影响它们的人员数据与展示）。唯一间接接触点是"标签组 person 维度"这一个共享候选。

## 10. 待决 / 留给方案阶段的事项（本报告不设计）

- 稳定ID 的落地形态：仅用 LDAP `real_name` 作 canonical，还是在评审人名旁**另存 `user_id`/工号**（后者是跨快照/视图/BI/搜索/导出的大改，需评估）。
- 下拉候选源切换到 LDAP 目录的具体口径（在职过滤、特殊账号、与既有候选框架 `DropdownOptionFieldRegistry`/`getFilterOptions` 的关系）。
- 目录刷新机制（定期 vs 手动、服务账号来源）。
- 别名表 canonical 与 LDAP 的校验绑定、解析优先级（real_name→uid→alias→原样）的具体实现。
- 黄金基线影响面（下拉候选、评审产出、权限快照）与门禁流程。
- **复核评审 Excel 导入写入路径**：权限表存在 `review.legacy_import`（导入老平台评审 Excel）、`review.template.download`（下载导入模板），但前次 grep 未定位到导入写入口（仅见导出），结论存疑；实施前必须确认是否存在该写入路径并纳入规范化覆盖，否则会成为绕过持久化门面的死角。
- **标签组 person 候选源口径（方案必决项）**：评审页（→LDAP）与系统测试/客户问题页（→GitLab 事实）共用一份 person 候选（`loadUserNames`）。需决定：按页分源（评审页用 LDAP、议题/客户页用 GitLab 事实），还是统一到 LDAP 并要求议题/客户人员也规范化（后者范围更大）。实测 33 名在职者 GitLab 缺失，维持现状会导致标签组按人筛评审漏这 33 人。

## 11. 附：可复现证据

- 查询脚本（只读）：`.tmp/query-ldap-users.sql`、`.tmp/query-ldap-names.sql`、`.tmp/query-ldap-seedcheck.sql`、`.tmp/query-ldap-idcheck.sql`、`.tmp/query-ldap-vs-gitlab.sql`
- 查询结果：对应 `.tmp/query-ldap-*.txt`
- 运行方式：`psql "<本地连接串>" -f <脚本> -o <结果文件>`（UTF-8）
- 诊断脚本（评审侧人名全集，已存在）：`scripts/audit-review-person-names.sql`
