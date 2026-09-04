# 下拉框选项设置（首期：新增评审·项目名称）工作单元计划

## 进度与中间物

- 状态：已完成（2026-09-03 实现与全部验证收尾，见"进度与中间物"）。
- 本期范围：只做评审数据"新增/编辑评审"表单的项目名称字段，但数据模型与页面按全平台规模设计（配置/字段解耦，支持共用与拆分）。术语按用户要求称"回归测试"（代码/文档既有名词 GoldenBaseline 不在本期改名范围）。
- 变更清单（全部完成）：
  - 迁移：`V20260903_01__dropdown_option_configs.sql`（两表 + SYSTEM_DROPDOWN_OPTION_VIEW/MANAGE 权限种子）。
  - 后端：`entity/dropdown/`（9 个 DTO）、`service/dropdown/`（Registry/Repository/RuleSupport/FilterService/FieldService）、`DropdownOptionFieldController`；`PlatformPermissionCodes`、`ResultCode.CONFLICT`、`LabelGroupFilterOperatorSupport` 改 public。
  - 接入：`ReviewDataFilterOptionService` 项目名称候选改走 `DropdownOptionFilterService.resolveOptions`；`PageRecordSnapshotService.reviewDataSourceVersion` 并入下拉配置指纹（sum(version)+max(updated_at)+max(bound_at)+count(bindings)）。
  - 前端：`dropdown-options.ts` 类型、`dropdown-option-api.ts`、`request.ts` ApiBizError、页面登记链（PageKey/modules/router/route-contracts）、`DropdownOptionSettingsView.vue`。
  - 测试：后端 `DropdownOption*Test` 3 类 29 用例 + `ReviewDataFilterOptionServiceTest`/`PageRecordSnapshotServiceTest` 扩展；前端 `dropdown-option-api.test.ts` 6 用例。
  - 回归目录：`endpoint-catalog.yml` 登记 6 端点 ✓；更新模式快照已生成（2026-09-03 用户确认执行，BUILD SUCCESS 179 用例），8 个快照文件人工审阅通过（READ 数据非空来自夹具池、WRITE 响应+dropdown_option_configs/dropdown_option_field_bindings 表状态、binding-copy 负面用例 B0001 均符合契约）；比对模式复跑全绿（179 用例 0 失败，309s）✓。
- 文档：`docs/decisions.md` D-09、`docs/architecture.md` 契约条、`docs/platform-page-business-rules.md` 规则 11。
- 测试状态：后端编译 ✓；新增/受影响后端测试 38 用例全绿 ✓；全量默认套件 1227 用例除 1 个**预先存在**失败（`ReviewDataRecordReadSupportTest`——工作树中 2026-08-25 他人未提交代码的期望与实现不一致，与本功能无关）外全绿 ✓；前端 tsc/vite build/eslint ✓、全量 vitest 456 用例全绿 ✓（含 request.ts 新增 3 用例）。
- **UI 浏览器实测（2026-09-03 完成，18080 新代码 + 18181 vite dev + local admin）**：迁移/权限种子 ✓；字段列表与"未配置（直通现状）" ✓；共用清单、拆分三路径按钮、双套规则卡与判定文案 ✓；手动选项值池联想显示 25 个已获取值 ✓；添加手动项预览 25→26（手动项恒显示+并集语义） ✓；保存后列表实时变"已配置·位置路径" ✓；新增评审弹窗项目名称下拉 26 项含池外手动新值 ✓；列表筛选候选 projectNames 全程 14 项不变（隔离） ✓；乐观锁 A0409 ✓；测后已恢复空配置直通（25/14）。
- **过程中发现并修复平台级缺陷（非 dropdown 特例）**：登录成功轮换 CSRF token 后，"页面加载即自动 POST"的首个请求会 403（sessionStorage 中 token 已过期）。修复：`request.ts` 新增 `fetchWithCsrfReplay`——403 时用 403 响应携带的最新 token 换头重放一次（被拒请求未触达业务层，重放零副作用；safe method 不重放）；`request()`/`requestRaw()` 统一走该入口，新增 3 用例。
- 当前阻塞/进行点：无。实现、验证（含 UI 实测与回归测试快照闭环）全部完成；工作树未提交（含同事未提交内容，提交策略待用户决策）。
- **2026-09-04 试用反馈调查（结论已实锤，修复方案待用户批准）**：
  1. **containsAll 单值陷阱（黑名单失效直接根因）**：UI 暴露看板集合语义的 5 个标签组操作符，"包含全部"（containsAll）要求值集合包含组内全部成员，下拉判定是单值 vs 集合 → 恒不命中（notContainsAll 反向恒命中）。实测对照：containsAll 黑名单 25 值全保留；intersects 正确剔除。
  2. **AND 组合恒不命中案例**：黑名单一条规则两个条件（eq A + 属于组 B）在"满足全部"下为永假式，规则失效。用户心智是"满足任意"。
  3. **候选来源口径差异（2a）**：标签组"项目"维度候选=列表筛选口径（LabelValueQueryService→getFilterOptions().projectNames），下拉池=GitLab"项目："标签解析（loadLabelProjectNames）；用户期望镜像直取（labels 表解析、users 表人名），方向已认可，独立工作单元。
  4. **待批修复方案**：①StatisticFilterBuilder 加可选 props（labelGroupOperatorOptions/operatorLabels 覆盖/logicSelector auto 单条件隐藏），不传=现状，看板零影响；②下拉页传收缩后操作符+直白文案（属于/不属于该组）；③后端 DropdownOptionRuleSupport 保存期只接受 intersects/notIntersects；④黑名单规则默认"满足任意"、白名单默认"满足全部"（待用户确认）；⑤条件值接值池联想+按值勾选（问题 1）。判定语义与端点不变，快照无需重建。
  5. 复现教训：复现用户 UI 缺陷必须复刻确切 UI 操作与选项（API 等效构造漏掉了 containsAll 变量，被用户两次纠正）。

## 恢复线索

- 当前阶段：已完成。后续如需重新验证：先跑默认套件 DropdownOption*Test，再在用户确认后跑回归测试。
- 恢复后首条命令：`cd backend && ../tools/maven/apache-maven-3.9.9/bin/mvn.cmd test -Dtest=DropdownOption*Test`。
- 回归测试快照命令（需 Docker + 用户确认）：`../tools/maven/apache-maven-3.9.9/bin/mvn.cmd test -Pgolden-baseline -Dtest=GoldenBaselineChainTest -Dgolden.update=true`，生成后 git diff 人工审阅。
- 上一份计划：`docs/plans/golden-baseline-regression-20260901.md`（已完成，回归测试门禁）。

## 目标与边界

- 用户原始需求：新增系统设置页面"下拉框选项设置"，集中配置各下拉框显示内容。长期目标=全平台所有下拉字段接入；本期仅评审数据→新增/编辑评审→项目名称。
- **配置/字段解耦模型（V2 核心）**：
  - 配置是独立实体，每个下拉字段绑定一个配置；多个字段可绑定同一配置（改一处多字段生效），配置详情显示使用者清单。
  - 共用可拆分：为字段新建配置（复制当前配置或空白）并改绑即可分家，原配置继续服务其余字段。
  - 配置名不存库，由绑定关系实时推导：单绑定显示字段位置路径（如"评审数据-新增评审-项目名称"），多绑定显示"位置A、位置B 等 N 个字段共用"。
- **双套规则**：每个配置内自动获取值与手动添加值各一套独立有序黑白名单（判定语义相同、互不影响）：
  `最终显示 = 自动获取值经【自动值规则】保留 ∪ 手动添加值经【手动值规则】保留`。
- 判定语义（单套规则内，用户确认）：候选逐值自上而下取**第一条命中**规则——黑名单剔除、白名单保留；全部未命中时存在任一白名单则剔除（精确模式），只有黑名单则保留。规范算例：池 1-10，黑1/黑2,3,4/白5/白1 → 只显示 5；仅黑1 → 显示 2-10。
- **重叠值任一保留即显示**（用户确认推荐项）：值既被自动获取又被手动添加时，自动规则或手动规则任一保留它即显示。手动添加=持久保险（自动池规则收紧不影响已手动固定的值）；代价=想隐藏重叠值须两套都拉黑或从手动列表删除。
- 拆分初始内容：提供"从当前配置复制"（默认推荐）与"新建空白"两种。
- 规则条件 = 条件筛选（一条规则多条件，AND/OR），可引用已保存标签组（成员字符串集合语义）；规则可排序，最上方优先级最高。
- 成功标准：管理员配置后项目名称下拉即时生效（含快照缓存失效）；无配置 ≡ 现状直通；非管理员不可配置。
- 明确禁止：不动列表筛选区候选（projectNames）；不动 GitLab 标签获取链路；不做按值勾选快捷名单、不做下拉显示顺序手动编排（首期不做）；不为未注册字段预留半成品入口。

## 约束与背景

- 平台栈：Java 21 + Spring Boot（JdbcTemplate 风格服务），Vue 3 + TS + Element Plus；端口 18080/18181；本地库 127.0.0.1:15432/qaflex。
- 现有部件（复用，不重写）：
  - 条件引擎 `StatisticFilterEngine`（对 `List<String>` 求值的通用引擎，文本/集合操作符全平台唯一语义）+ `StatisticFieldDescriptor.multiValue`。
  - 前端 `StatisticFilterBuilder.vue`（单字段 fields 传入即可复用，含标签组操作符与选择 UI）；`statistic-board-filters.ts` 草稿类型。
  - 标签组 `LabelGroupExpansionService.expand(groupId, valueType, fieldKey, pageKey, sourceInstanceId)`（含 SAME_FIELD 适用域校验、200 成员上限）。
  - 系统设置接入：`feature-manifest/modules.ts` + `router.ts` + `route-contracts.ts`；权限注解 `@RequirePermission` + `PlatformAuthorizationInterceptor`；权限种子迁移模式参照 `V20260804_01__bi_dashboard_permissions.sql`。
  - 快照缓存：`/api/review-data/records/filter-options` 整响应经 `PageRecordSnapshotService.readOrRefresh`，sourceVersion=reviewDataSourceVersion()。
- 值池单源：`ReviewDataMirrorOptionRepository.loadLabelProjectNames()`（ods_gitlab_labels "项目："标签）。

## 证据与根因

- 候选链路（代码实证）：ReviewDataFilterOptionService.java:116 formProjectNames ← loadLabelProjectNames；表单消费 ReviewRecordFormDialog.vue:78/185（SmartSelect 无 allow-create）；列表筛选走 :89 filterProjectNames（历史+match，不含镜像标签）——两类候选独立，故只改表单侧。
- 缓存失效缺口（代码实证）：PageRecordSnapshotService.java:202-264 reviewDataSourceVersion 只含读模式/review_records/match 表/issue generation/label_groups——新增配置表必须并入该版本指纹，否则改配置后快照不失效、下拉不更新。
- 引擎可复用性（代码实证）：StatisticFilterEngine.compile 对任意 R 的 valuesAccessor 求值（:81-88），matchesSet :106 直接支持 values 列表比较；标签组条件由调用方先展开成 values（CustomerIssueRecordService.java:259-290 现行模式）。
- 权限：SUPER_ADMIN/ADMIN 拥有全部权限码（V20260720_01 cross-join 种子）；回归测试目录已覆盖 @RequirePermission 端点（permission-settings 条目为模板）。

## 方案与步骤（V2 数据模型）

### 数据模型（两张表）

```sql
create table dropdown_option_configs (
    id          bigserial primary key,
    rules_json  jsonb not null default '[]'::jsonb,  -- {"acquiredRules":[...], "manualRules":[...]}
    manual_options_json jsonb not null default '[]'::jsonb,
    version     bigint not null default 0,
    updated_by  varchar(64),
    created_at  timestamptz not null default current_timestamp,
    updated_at  timestamptz not null default current_timestamp
);
create table dropdown_option_field_bindings (
    field_key   varchar(128) primary key,  -- 注册字段键（代码注册表登记）
    config_id   bigint not null references dropdown_option_configs(id),
    bound_at    timestamptz not null default current_timestamp,
    bound_by    varchar(64)
);
```

- 配置实体与字段绑定分离：共用=多行指向同一 config_id；拆分=新建 config + 改绑；无绑定行（或绑定的配置全空）= 直通现状。
- rules_json 内部分两数组：`{"acquiredRules":[规则...], "manualRules":[规则...]}`；规则对象 = `{listType: BLACKLIST|WHITELIST, name?: 规则名, filterGroup: StatisticFilterGroup 线格式}`。
- rules_json、bindings 每次保存以整行替换（无规则子表）。
- 校验：listType 黑/白、条件字段仅 `optionValue`、操作符白名单（eq/ne/contains/notContains/isEmpty/isNotEmpty + 标签组集合操作符）、标签组引用经展开服务校验、空条件拒绝、每套规则数上限（20）、手动项数上限（200）。
- 权限：`system.dropdown_option.view` / `system.dropdown_option.manage`，种子仅授 SUPER_ADMIN/ADMIN（照 V20260804_01 模式，注意 15432 本地库走 Flyway 自动迁移）。

### 后端组件（`service/dropdown/` + `entity/dropdown/`）

- `DropdownOptionFieldRegistry`：字段注册表，每项 = fieldKey、显示名、值池 Supplier。首期仅 `review-data.form.project-name`（显示名"评审数据-新增评审-项目名称"）。后续挂新字段 = 注册一行 + 接入一行。
- `DropdownOptionConfigRepository`：JdbcTemplate 读写两张表；保存（乐观锁）；绑定读取与改绑；版本聚合计数（sourceVersion 用）。
- `DropdownOptionRuleSupport`：规则 JSON 解析/序列化/校验。
- `DropdownOptionFilterService`：判定管道——加载字段绑定配置 → 自动值过 acquiredRules、手动值过 manualRules → 并集（去重、获取值原顺序在前手动项在后）→ 返回最终列表。标签组条件先经 LabelGroupExpansionService 展开为 values 再交 StatisticFilterEngine（选项值包成 `multiValue(option -> List.of(option))` 单值描述符，fieldKey=optionValue）。

### 接入与缓存失效（关键正确性点）

- ReviewDataFilterOptionService 组装 formProjectNames 处过判定管道；列表筛选的 filterProjectNames 不动。
- PageRecordSnapshotService.reviewDataSourceVersion() 追加 `dropdown:<sum(config.version)>:<max(config.updated_at)>:<sum(binding count)>` 段——配置内容或绑定关系变化都会推进版本指纹使快照失效重建。
- 前端表单零改动（ReviewRecordFormDialog 吃 formProjectNames）。

### API（/api/dropdown-option-fields，均 ApiResponse 包装）

- `GET /`（view）：注册字段列表，每项 `{fieldKey, displayName, configured, configId, configLabel}`；configLabel=推导名（单绑定=位置路径，多绑定=共用提示）。
- `GET /{fieldKey}`（view）：完整配置 `{fieldKey, displayName, configId, configLabel, acquiredRules, manualRules, manualOptions, version, consumerFields: [使用该配置的字段位置名...]}`。
- `GET /{fieldKey}/acquired-options?keyword=&limit=`（view）：自动获取值池联想（contains 大小写不敏感，默认 limit 50）。
- `POST /{fieldKey}/preview`（view）body `{acquiredRules, manualRules, manualOptions}` → `{finalOptions}`（草稿预览，不经保存）。
- `PUT /{fieldKey}`（manage）body `{acquiredRules, manualRules, manualOptions, version}`：整体保存当前绑定配置；版本冲突 409（"配置已被他人修改"）。
- `PUT /{fieldKey}/binding`（manage）body `{target: "NEW"|"COPY"|"CONFIG", configId?}`：拆分/改绑——NEW=新建空配置并绑定，COPY=复制当前配置为新配置并绑定（默认推荐），CONFIG=绑定到既有 configId（共用入口）。原配置不删除，继续服务其余字段。

### 前端（系统设置 → "下拉框选项设置"）

- 接入：modules.ts（key=dropdown-option-settings，permission=system.dropdown_option.view）+ router.ts + route-contracts.ts。
- `DropdownOptionSettingsView.vue` + `api-client/dropdown-option-api.ts`：
  - 左侧字段列表（显示名 + 已配置徽标）。
  - 右侧配置面板：
    - 头部：配置名（推导）、使用者清单（consumerFields，多字段共用时显著提示）、拆分/改绑操作（下拉选既有配置共用 / 复制新建 / 空白新建）。
    - **自动获取值规则**：规则卡列表（黑/白切换 + 可选规则名 + StatisticFilterBuilder 单字段"选项值" + 上移/下移/删除）。
    - **手动添加值规则**：同上独立一组。
    - **手动添加选项编辑**：远程联想 acquired-options + allow-create（LabelGroupMemberPicker 模式）。
    - **预览**：实时调 preview，展示最终显示列表（保存前可见效果）。

### 测试

- 后端单测：判定语义全部算例（两个规范算例、双套独立性、重叠值任一保留即显示）、空配置直通、共用/拆分/改绑、乐观锁、sourceVersion 变化、权限拦截、非法配置拒绝。
- 前端 Vitest：api client 与视图草稿逻辑。
- 默认快速套件全绿。

### 回归测试纪律

- endpoint-catalog.yml 登记新端点（GET/preview=READ，PUT=WRITE + affected-tables: dropdown_option_configs、dropdown_option_field_bindings）。
- 夹具库无下拉配置与绑定 → 既有评审端点响应零变化（formProjectNames 直通）；权限种子若造成权限表快照差异，属有意变更，走"展示 diff → 用户确认 → update 重建 → 审阅"。
- 快照生成需跑回归测试更新模式（约 7 分钟 + Docker），执行前先征得用户同意。

### 文档收尾

decisions.md 新决策（判定语义+双套规则+共用拆分模型+重叠值并集语义）、platform-page-business-rules.md（新增评审项目名称候选可配置）、architecture.md（注册表与 sourceVersion 接入）、progress.md、本计划收尾。

## 决策记录

- 已选（V2，用户批准）：配置/字段解耦两张表；配置名推导不存库；自动/手动两套独立规则；重叠值任一保留即显示（手动=持久保险，代价=隐藏重叠值须双拉黑或删手动项）；拆分提供复制与空白两条路径；排序用上移/下移按钮（代码库无拖拽先例，不引新依赖）；联想走独立 acquired-options 端点。
- 已选（V1 沿用）：单表 JSONB 整行替换；无配置行 ≡ 直通，不做 enable 开关与删除配置接口（配置无引用后自然闲置，不物理删）；条件复用 StatisticFilterEngine 不另造引擎；前端复用 StatisticFilterBuilder。
- 已否决：手动项豁免黑白名单（用户纠正：手动项只是补池，统一判定）；任一拉黑即隐藏（重叠值语义，用户采纳"任一保留即显示"推荐）；配置名人工填写（用户采纳位置路径自动推导）；规则子表关系建模（低价值高复杂度）。
- 待定事项：无。

## 接口契约

- 表结构：见"方案与步骤（V2 数据模型）"。
- 规则 JSON 线格式（存储与 API 同构）：
  ```json
  {"acquiredRules":[{"listType":"BLACKLIST|WHITELIST","name":"可选规则名","filterGroup":{"logic":"AND|OR","conditions":[{"fieldKey":"optionValue","operator":"eq|ne|contains|notContains|isEmpty|isNotEmpty|intersects|notIntersects|containsAll|notContainsAll|partialContainsAny","value":"...","secondaryValue":null,"valueType":"LITERAL|LABEL_GROUP","labelGroupId":1,"labelGroupName":"...","values":[]}]}}],
   "manualRules":[同构],
   "manualOptions":["李四","刘五"]}
  ```
- API 响应结构：见"方案与步骤（V2 API）"一节；判定语义基准见"目标与边界"。
- 权限：GET/preview=`system.dropdown_option.view`；PUT 两个写端点=`system.dropdown_option.manage`（仅 SUPER_ADMIN/ADMIN）。
- 伪字段：`optionValue`（label"选项值"），文本操作符 + 标签组集合操作符。

## 风险与假设

- 快照缓存失效是关键正确性点：忘记并入 reviewDataSourceVersion 会导致"改配置不生效"——已列为强制测试项。
- 配置共用场景下的改绑并发：两管理员同时拆分/改绑同一字段——绑定表以 field_key 为 PK，后写覆盖先写，乐观锁只保护配置内容；改绑操作幂等且低频，接受最后写入生效（页面保存时提示共用状态）。
- 回归测试快照：夹具库无配置数据 → 既有端点零变化；权限种子差异属有意变更须走确认流程。
- 假设：标签组 STRING 组成员可作为项目名称判定条件（值即字符串，语义成立）；标签组为空成员时集合操作符恒 false（引擎现状），规则仍可保存。
- 易错点：LabelGroupExpansionService 的 SAME_FIELD 适用域会拒绝绑定源字段的组（期望行为）；规则 JSON 由前端草稿序列化，id/源标记等草稿字段不进存储；多字段共用配置时，任一字段配置页显示的都是同一份内容（使用者清单可见）。

