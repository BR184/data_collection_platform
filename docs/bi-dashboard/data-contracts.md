# BI 看板数据与接口契约

## 计算权威与责任

- `BI看板数据来源与计算口径核对表.md` 是所有 BI 业务字段语义、公式、筛选、去重、聚合、精度、质量目标和不可计算语义的唯一权威。实现、测试和页面 DTO 必须引用对应编号；表中的早期英文候选名不自动构成物理字段契约。
- BI 不直接复用平台现有页面 Service 或页面 DTO。平台已发布事实和稳定基础查询可以作为输入；BI 在自身应用服务中按核对表实现页面计算并建立独立测试，不能复制粘贴平台页面代码。
- 上游已经提供且与核对表一致的聚合值可以直接消费；上游只提供基础数据时由 BI 服务端计算。浏览器只做格式化、图例显隐和已加载有限数据的展示派生，不承担全量聚合。
- 物理字段必须从来源表结构、实体和实际查询核实并以 `table.column` 写回核对表。名称不同但业务语义相同的字段标记为“等价映射”后直接使用；只有业务语义、公式或来源范围冲突时才停止该指标接入并报告。不得因候选字段名不一致拒绝已有数据，也不得增加字段别名、回退、拼接旧结果或用 `0` 补齐。
- 计算结果必须能追溯来源平台、来源字段中英文语义、核对表编号、公式版本和来源版本。分母不足、基础字段缺失或范围不完整时返回不可计算状态。
- 具有相同稳定业务 ID 且业务字段完全一致的重复事实可以确定性去重；同一稳定业务 ID 的核心字段发生冲突时，依赖该事实族的指标必须标记为 `INCOMPLETE`，不得任选首条、拼接字段或让冲突记录进入聚合。与该事实族无依赖且能独立证明完整的分区可以保持自身状态。
- 缺失值、负数等非法度量和缺失业务日期必须保留其不可计算语义；不得通过 `coalesce`、`Math.max(0, ...)`、替代日期或其它回退伪造合法输入。每个汇总、趋势和散点只接收完成稳定键、范围、单位和非负性校验的输入集合。

## 稳定业务 ID 与来源快照维度

- 稳定业务 ID 用于事实唯一性、跨请求追溯或跨系统映射，例如评审记录 ID、合并请求 ID、缺陷 ID、产品版本目录 ID 和 CAT 模块/功能 ID。ID 合法范围必须遵循来源契约：`review_visible_records.id` 以正数表示正式记录、负数表示统一视图隔离后的兼容记录，两者都是稳定身份，只有零值无效；相同 ID 的冲突规则仍按上节执行。
- 编码合并请求身份必须保留读源物理边界：兼容态为 `source_instance + merge_request_iid`，正式态为 `project_id + merge_request_id`。领域层分别使用 `CompatibilityMergeRequestIdentity` 和 `FormalMergeRequestIdentity`，不得退化为裸数值 ID、仓库显示名或项目显示名。
- 编码事实按依赖字段分别去重和判冲突：新增行或合并时间冲突不影响仅依赖稳定身份的合并请求计数；作者冲突不清空代码规模和模块增量；模块冲突不清空人员贡献；走查质量、问题类别和注释率也只受各自事实族完整性约束。
- `author_name`、`module_name`、`module_names` 和 `assignee_name` 在当前页面只承担一个冻结平台来源快照内的分组语义，不用于跨平台关联员工或模块实体，因此不要求上游额外提供当前不存在的全局 ID。
- BI 使用 `BiSourceDimension(sourceValue, displayName, identified)` 承载该语义。已识别成员的 `sourceValue` 是规范化后的真实来源值；缺失值的 `sourceValue=null`，以“未标注作者”“未标注模块”“未指派”或“未归类”等显式成员展示，不能与同名真实来源值碰撞。
- 不允许对名称做哈希、拼接产品版本或复制显示文本来伪造稳定 ID。后续若出现跨平台人员/模块关联需求，必须新增并冻结真正的业务映射契约，不能改变现有快照维度的含义。

## 平台已核实字段映射

详细公式和逐字段实现状态只维护在核对表。当前权威查询边界如下：

| 页面 | 来源对象 | 已核实的关键物理字段 | 当前限制 |
|---|---|---|---|
| 需求、设计 | 由平台兼容模式上下文决定的评审可见来源；当前统一查询对象为 `review_visible_records`、`review_visible_problem_items` | `id`、`project_name`、`module_name`、`review_date`、`review_scale_pages`、`workload_hours`、`review_category`、`problem_status`、`problem_category` | 模块名称仅作快照内分组；页数或独立评审工时缺失时对应区块不可计算 |
| 编码-代码规模与维度 | 兼容态 `code_review_match_mode_records`；正式态 `code_review_formal_records` | 来源感知的 MR 稳定键、`author_name`、`module_name`、`merged_at_source`、`added_lines` | 仍无仓库稳定 ID，仓库 ID 筛选保持不完整 |
| 编码-GitLab 提交 | `merge_request_commit_fact` | `source_instance`、`project_id`、`commit_sha`、`committed_at_source`、`project_name` | 推荐/全部同步默认启用；自定义白名单未同时选择两张提交血缘表时该事实族为 `INCOMPLETE`，不读取残留事实；当前范围存在 MR 但没有任何已发布提交事实时同样为 `INCOMPLETE`，不得把 MR 时间桶补成全零提交趋势；内网需确认真实覆盖率 |
| 编码-人工走查 | 兼容态 `code_review_match_mode_records`；正式态 `code_review_formal_records` | 走查记录 ID、MR 身份、`module_name`、`code_walkthrough_date`、走查行数/工时/问题计数、扫描和注释率字段 | 兼容态真实数据来自平台兼容表，需在兼容模式常开环境验收覆盖率 |
| 系统测试 | `issue_fact`、`issue_scope_groups`、`issue_scope_members` | `issue_id`、`testing_phase`、`severity_level`、`priority_level`、`is_fixed`、`module_names`、`assignee_name`、`fix_user`、`reason_category`、`label_names`、`delay_issue`、`delay_cause`、`delay_reason` | 本地外网镜像的评论覆盖不足；修复人与原因完整性需在内网完成最终验证 |

- 系统测试查询统一排除已删除、已排除和建议类事实，再按 `issue_id` 去重；模块多值按逗号拆分，同一缺陷在同一模块只计一次。
- 原因大类/子类由 `BiSystemTestCauseClassifier` 的 `bi-system-test-cause-v1` 词典从 `reason_category + label_names` 计算；同一缺陷在同一大类或大类/子类组合最多计一次，未命中项保留为“未归类”。
- 延期图只统计 `delay_issue=true` 的有效缺陷。`BiSystemTestDelayCauseClassifier` 按 `bi-system-test-delay-cause-v1` 从 `delay_cause + delay_reason + label_names` 提取七类原因，未命中项保留为“未归类延期原因”，再按“延期原因 × 严重级别”聚合；不存在也不再要求 `delayApplicationStatus`。
- 轮次提交总数不是独立上游字段，而是当前轮次有效缺陷 ID 的去重数量。每个轮次必须满足 `提交总数 = 一级 + 二级 + 三级 = 已关闭 + 未关闭`；任一等式因缺失严重级别、修复状态或重复冲突而不成立时，该轮次区块标记为 `INCOMPLETE`。
- 完整轮次目录用于事实归属校验和顺序管理；页面响应只输出提交总数大于零的目录轮次。目录中没有有效缺陷事实的轮次不是零值业务记录，不进入响应，也不由前端二次过滤。

## 系统测试人员语义

- `issue_fact.assignee_name` 是 GitLab 当前指派责任人；`issue_fact.fix_user` 是平台从合法 `### 1、修复状态` 评论提取的评论作者，表示实际修复归属。两者不是同义字段，不能互相回退。
- 当前 ODS 只提供一份规范指派身份，因此 `handler_name` 与 `assignee_name` 可以相同，但仍是独立事实字段。老平台的当前剩余缺陷统计明确使用指派人和未关闭状态，历史修复人统计明确使用合法修复评论作者。
- BI 需要在同一人员下常驻展示“缺陷总数”和“当前待修复数”，因此人员负荷图按 `assignee_name` 聚合，并使用“按指派人统计缺陷数”语义；已修复数按同一指派人范围由 `总数 - 待修复数` 得出。`fix_user` 只适用于实际修复历史归属，不参与当前待修复负荷，不得把缺失修复评论的未关闭议题聚合成“未识别修复人”。

## 平台读源模式

- BI 不拥有独立兼容开关。一次请求只读取一次平台现有兼容模式上下文，并按与对应平台页面相同的规则选择评审或代码走查来源。
- 兼容态和正式态是同一页面契约的不同物理模式，不是两套业务公式。编码兼容态内部按事实族读取 `code_review_match_mode_records`、`merge_request_commit_fact`，人工走查复用 `code_review_match_mode_records`，这不是兼容/正式双读；禁止把三类事实互相替代、跨模式拼接、空结果回退和 BI 专属模式参数。
- 内网真实环境兼容模式常开，内网发布验收必须覆盖该模式；正式态保留确定性自动测试，不作为常规生产读源假设。
- 兼容表 `code_review_match_mode_records` 的 `merge_request_state` 承载合并状态，不能作为已完成走查标志。兼容态合法走查按 `reviewer_names` 非空且不属于平台既有非法/无需走查占位值判定；正式态继续使用 `review_status=COMPLETED`。该差异只存在于基础设施映射边界，计算器不感知兼容模式。
- `comment_rate_source` 是注释率的可选来源说明，仅正式态 `code_review_formal_records` 携带；兼容态复用 `code_review_match_mode_records.comment_rate`，无来源说明。若某条来源说明为空但 `comment_rate` 合法，仍可展示数值，不能因缺少说明清空 CD-38。

## 页面与来源边界

| 页面 | 唯一业务数据来源 | 来源范围 |
|---|---|---|
| 需求 | 数据采集平台 | 已发布评审事实与 BI 独立计算 |
| 设计 | 数据采集平台 | 已发布评审事实与 BI 独立计算 |
| 编码 | 数据采集平台 | 已发布合并请求、代码走查等事实与 BI 独立计算 |
| 单元测试 | CAT | CAT 提供的整体、模块、功能数据 |
| 集成测试 | CAT | CAT 提供的整体、模块、功能数据 |
| 系统测试 | 数据采集平台 | 已发布 `issue_fact`、阶段目录与 BI 独立计算 |

- 单个页面不得同时从数据采集平台和 CAT 拼装业务数据，因此两方不需要共同批次标志。
- 顶部产品版本用于保持六页业务范围一致，不表示六页采集时间或来源版本相同；页面必须展示或保留自己的来源身份。
- 系统测试按平台“议题测试阶段定义”展开产品版本对应的测试轮次，不创建 BI 专属解析器。
- 平台历史项目名称中的组合版本按年份分段展开；省略年份的 `R<n>` 只能继承当前分段最近一次显式年份，遇到新的显式年份即切换作用域，禁止把后续分段的轮次归到前一年份。

## CAT 接口契约

### 权威来源与覆盖范围

- `集成测试平台接口使用手册.md` 是 CAT 开发方提供的当前接口事实，已说明项目目录、项目版本/测试阶段树、模块统计和模块下功能统计四个查询接口。
- 当前实现按用户确认让单元测试和集成测试分别使用同一套 CAT 传输协议；两页通过配置中的独立 `unitTestingPhaseId`、`integrationTestingPhaseId` 选择阶段。手册没有单元测试专用路径，因此不按页面名称猜测或复用另一页的阶段 ID。
- 手册说明传输字段和 CAT 内部统计行为，不改变 `BI看板数据来源与计算口径核对表.md` 的公式权威。只有 CAT 聚合语义与对应 IT 编号一致时，BI 才能直接消费。
- 手册未提供服务基地址、认证方式、HTTP 状态码语义、机器可判定错误码、接口版本或发布兼容策略；这些仍是上线前置条件，不能因请求头章节只列 `Content-Type` 就推断接口无需认证。

### 已说明的调用链

所有接口均为只读查询语义但使用 `POST`。下表路径按手册“接口地址”记录；手册请求示例省略了部分路径前缀，完整部署路径必须在联调时确认。

| 顺序 | CAT 接口 | 请求 | 已说明响应 | BI 用途 |
|---|---|---|---|---|
| 1 | `/integrationSearch/getAllProject` | 无参数 | `data[].id/name/defaultProject/createTime` | 发现 CAT 项目稳定 ID；BI 正式请求不使用默认项目兜底 |
| 2 | `/testingPhase/getAllByProjectId` | Query `projectId`，手册允许省略 | 版本顶级节点 `id/name`，测试阶段子节点 `id/name/projectId/versionId` | 校验显式产品版本映射并取得 `testingPhaseId` |
| 3 | `/integrationSearch/getStatisticsInfoByTPId` | Query `testingPhaseId`，必填 | `data.passRate` 与 `data.result[]` 模块统计 | 读取整体通过率、模块 ID/名称、模块通过率和功能通过/未通过数量 |
| 4 | `/getFeatureInfoByModuleId` | JSON Body `moduleId/testingPhaseId/page/pageSize/sortField/sortOrder` | `statisticsInfoList[]` 与分页元数据 | 模块下功能统计；页码从 1 开始，省略分页参数时返回全量 |

- BI 后端必须显式传入已映射的 `projectId`，不能依赖 CAT 默认项目，否则默认项目变化会让同一 BI 产品版本静默切换数据范围。
- 浏览器只调用同源 `/api/bi/**`；CAT 四个接口仅由 BI 后端出站适配器调用，CAT 地址、参数细节和凭据不进入前端。
- `sortField` 的允许成员未在手册中列出。BI 只能使用经过联调确认的固定字段，不能把浏览器任意输入透传给 CAT。

### 字段映射与语义边界

| CAT 字段 | BI 核对表编号 | 当前结论 |
|---|---|---|
| 项目 `data[].id/name` | G-00、G-00A | 稳定项目 ID 和显示名已说明 |
| 版本顶级节点 `id/name`、阶段子节点 `versionId` | G-01、G-02 | CAT 版本 ID/名称已说明；与 BI 产品版本的映射尚未确认 |
| 阶段子节点 `id/name` | G-03、G-04 | 集成测试 `testingPhaseId` 和名称已说明 |
| 模块 `result[].id/moduleName/name` | IT-02、IT-02A | `id` 作为模块 ID；`moduleName` 为显示字段，冗余 `name` 只做一致性校验，二者冲突时为 `INCOMPLETE` |
| 整体 `data.passRate` | IT-07 | 百分比数值已说明；精度、零分母和准确聚合公式未说明，当前只能视为部分契约 |
| 模块 `result[].testPassRate` | IT-13 | 百分比数值已说明；准确分子分母未说明，当前只能视为部分契约 |
| `passFeatureCount/notPassFeatureCount` | 无用例数映射 | 表示达到 95% 阈值的功能数量，不是通过/未通过用例数，禁止映射为 IT-10、IT-11 或 IT-12 |
| 功能 `featureUniqueId/name/featureLabel` | IT-03、IT-03A | 字段已说明；一个展示节点可能聚合多个功能，稳定功能 ID 与展示节点的基数关系仍需真实样例确认 |
| 功能 `testPassRate` | IT-18 | 百分比数值已说明；准确分子分母未说明，当前只能视为部分契约 |
| `page/pageSize/totalCount/sumPageCount` | 页面下钻契约 | 1 基页码已说明；稳定次排序和跨页一致性未说明 |

- 当前四个接口均未返回执行用例数、通过用例数或可推导二者的基础记录，因此 IT-04、IT-05、IT-10、IT-11、IT-15、IT-16 尚未满足。不能用功能数量或通过率反推用例数量。
- CAT 以 95% 作为功能通过/未通过分类阈值，与 BI 目标值一致；但 `passRate`、模块 `testPassRate`、功能 `testPassRate` 的精确分子、分母、去重、舍入和零分母语义仍需 CAT 确认后才能标记 `READY`。
- 功能接口中的 `id` 在展示节点聚合多个功能时可能为空，不能作为 `featureUniqueId` 的回退；若一个展示节点确实对应多个功能，需先确认 BI 功能层按展示节点还是实际功能建模。

### 产品版本、阶段和来源一致性

- CAT 运行时只使用用户确认并保存的 `catProjectId`、`catVersionId`、`unitTestingPhaseId` 和 `integrationTestingPhaseId`。首次目录发布后，系统可基于唯一 `defaultProject`、唯一版本语义匹配、平台默认版本对应的唯一 `curVersion`、以及唯一 UT/IT 阶段语义预填可编辑建议；建议不是运行时映射，歧义字段必须保持空白并由用户选择。
- 同一 CAT 版本下存在多个测试阶段时，BI 只读取映射指定的阶段；未配置阶段 ID 或阶段树核验不一致时返回 `INCOMPLETE`，不能自行合并多个 `testingPhaseId`。
- 四个接口没有 `sourceVersion`、`snapshotId`、ETag、更新时间水位或等价来源身份。模块统计与多个功能分页调用之间无法证明属于同一次发布，当前不满足 `BiCatTestSource` 的原子三层来源契约。
- 不得用调用时间、响应内容哈希或 BI 生成时间冒充 CAT 来源版本。CAT 需补充稳定来源身份，或提供一次原子返回页面所需数据的接口，才能让完整集成测试页面进入 `READY`。
- 当前 CAT 真实数据在上述来源身份补充前仍可用于查看，页面和“测试质量达成”区块保持 `INCOMPLETE`，`sourceVersion` 为空，PNG 下载按钮保持禁用；这不是用零值补齐，也不代表数据采集平台或 mock 已参与计算。
- 功能全量数据若通过分页读取，必须在来源身份固定后使用固定排序和稳定次排序遍历；当前手册未说明这两项，不能据此实现一致的完整 PNG 数据集。

### 空数据、错误与客户端边界

- 客户端成功条件必须同时满足 HTTP 请求成功和响应体 `code == 200`；任何非 200 业务码、反序列化失败、超时或字段类型错误均映射为 `ERROR`，不得返回旧数据或零值。
- 阶段树的 `code=200, data=null` 可能表示默认项目不存在或没有阶段，手册不能区分；BI 使用显式项目映射后仍应先视为 `INCOMPLETE`，待 CAT 补充可判定空数据语义。
- 统计接口把“测试阶段不存在”和服务器异常都示例为 `code=500`，当前无法区分业务不存在与服务故障，统一映射为 `ERROR`。
- CAT 客户端使用强类型 DTO 校验 `code/message/data` 和各层必填字段；不使用无类型 `Map` 穿透到应用层，不维护字段别名回退。路径、连接/读取超时、响应大小和快照保留数均由后端部署配置控制，系统设置页不向业务管理员暴露这些保护参数；客户端不自动重试。
- 并发上限、限流、迟到数据和凭据轮换策略仍待联调。当前请求按模块顺序读取，避免在没有来源身份时扩大不一致窗口。

### 尚需 CAT 补充

- 服务基地址、完整路径前缀、认证方式、网络边界和证书要求（当前客户端按手册请求头执行无认证 POST，若联调发现认证要求必须补充正式契约）。
- CAT 单元测试是否最终独立出专用接口仍待 CAT 另行说明；当前页面通过统一统计协议和显式阶段 ID 运行。
- 集成测试及单元测试各层执行/通过用例数，或足以按 UT/IT 编号计算的基础记录；当前手册没有这些字段，不能补零或反推。
- 三种通过率的精确公式、去重、百分比单位、精度、舍入和零分母语义。
- 实际目录无法形成唯一建议时的 BI 产品版本到 CAT 项目、版本、测试阶段映射及多阶段选择规则。
- 原子快照/来源版本、稳定分页次序、错误分类、超时、响应大小、限流和重试约束。

### CAT 配置边界

系统设置页允许管理员维护 CAT 地址、启停和全量同步时间策略，并确认目录建议生成的稳定映射；页面不展示连接/读取超时、响应上限、快照保留数、路径或认证。后者属于数据采集平台后端部署配置，不进入浏览器、Git 或页面响应。每个 BI 产品版本保存时必须完整填写两套阶段 ID；以下示例中的 ID 仅表示结构：

```yaml
platform:
  bi:
    cat:
      enabled: true
      base-url: https://cat.intranet.example
      scopes:
        - product-version-key: CC2026R4
          cat-project-id: project-id-from-cat
          cat-version-id: cat-version-id
          unit-testing-phase-id: unit-testing-phase-id
          integration-testing-phase-id: integration-testing-phase-id
```

路径、连接/读取超时、响应大小上限和快照保留数可在同一配置块覆盖。运行时先通过阶段树核验项目/版本/阶段 ID，再调用模块统计和功能下钻；缺少映射、阶段核验不一致或 CAT 响应违反契约时，不读取平台数据补齐。

## 页面 API

六页使用六个独立入口，不建立全阶段大接口：

| 页面 | 目标入口 |
|---|---|
| 需求 | `/api/bi/requirements` |
| 设计 | `/api/bi/design` |
| 编码 | `/api/bi/coding` |
| 单元测试 | `/api/bi/unit-test` |
| 集成测试 | `/api/bi/integration-test` |
| 系统测试 | `/api/bi/system-test` |

- 同一请求先冻结产品版本、页面筛选和来源版本，再生成该页全部指标、图表和表格 DTO；不能逐图读取不同发布版本。
- 页面 API 只返回页面需要的聚合结果、有限明细和下钻标识，不默认导出上游一条一条的完整原始数据。
- 页面 DTO 是 BI 内部契约，不是跨系统公共 API；事实关联和跨系统映射使用稳定业务 ID，单一冻结来源快照内的图表分组使用 `BiSourceDimension`，两者不得混用。
- 时间桶趋势（编码页代码注释率、代码走查缺陷密度）由 BI 后端按请求的 `granularity` 聚合后返回周期值，页面 DTO 只承载“周期 → 值”；前端只做周期轴并集与查找，禁止在页面层对明细做二次聚合，也禁止按重复周期键取最后一条。
- 记录级覆盖率（`totalObservations`/`validObservations`）统计的是走查记录数，不能解释为趋势周期数量；没有合法记录的时间桶不返回点位，由前端对齐为 `null`，不得补零、前向填充或连接断点。
- `/api/external/v1/datasets` 不属于 BI 页面链路。

## 状态契约

| 状态 | 含义 | 页面行为 |
|---|---|---|
| `READY` | 当前范围、公式和来源版本完整 | 正常展示 |
| `EMPTY` | 来源完整但当前范围真实无数据 | 展示空状态 |
| `NOT_APPLICABLE` | 指标不适用于该页面或范围 | 展示 `/` 或不适用说明 |
| `INCOMPLETE` | 基础字段、层级或同页来源身份不完整 | 不计算、不补零，指出缺口 |
| `ERROR` | 来源或 BI 处理失败 | 保留页面壳并显示失败状态 |

- 页面响应中的 `sections` 是固定结构契约，不是只返回当前有数据的区块。每个页面在 `READY`、`EMPTY`、`INCOMPLETE`、来源冲突和页面级 `ERROR` 下都必须包含下表全部区块键，前端不得通过“缺少区块键”推测数据状态。

| 页面 | 必须返回的区块键 |
|---|---|
| 需求、设计 | `source-consistency`、`overview`、`problem-categories`、`module-quality`、`review-scatter` |
| 编码 | `source-consistency`、`code-trend`、`submission-trend`、`contributors`、`module-increments`、`review-quality`、`review-categories`、`module-review-quality`、`review-scatter`、`static-scan`、`comment-rate`、`quality-trend` |
| 单元、集成 | `test-quality` |
| 系统测试 | `source-consistency`、`quality-targets`、`round-quality`、`severity-distribution`、`module-quality`、`module-repair-targets`、`cause-distribution`、`delay-analysis`、`developer-workload` |

- 页面级 `ERROR` 只展示一次真实失败原因和重试入口，不继续渲染业务图表；但响应仍返回完整区块键集合，使状态契约可验证且不退化为“页面响应缺少该区块状态契约”。
- 已打开页面持有加载时的 `sourceVersion`。平台或 CAT 出现同产品版本的新来源时只提示可刷新；刷新前不得把新旧版本数据混在同一页。
- 刷新是整页重新读取，不是逐图更新。刷新失败时继续保留原页面或明确进入错误状态，不能把部分新数据覆盖到旧页面。

## 下载契约

- 每张图表的下载入口调用 BI 专属 PNG 导出逻辑，输出内容、布局和视觉与已确认静态页面一致。
- PNG 必须对应当前产品版本、页面筛选和来源版本，并包含图表标题、图例、轴、标签及完整数据；高基数图下载时使用完整数据布局，不受当前 10/20 项窗口或 dataZoom 截断。
- 文件名至少能辨识产品版本、页面/图表和生成时间；没有下载权限时前端不展示入口，后端或受保护资源仍必须拒绝访问。
- 每张图表支持导出 `.xlsx` 数据表：前端按图表语义提取表格（表头 + 数据行），后端 `BiExcelExportService` 用 Apache POI 生成标准 OOXML 工作簿（`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`），不再产出扩展名与 MIME 不符的 XML Spreadsheet 2003。
- Excel 数据表必须忠实呈现图表所展示的数值口径：比率类字段（修复率、通过率、关闭率、注释率、占比等）在页面数据中已是百分数（0-100），原样输出、不再二次乘 100；表头单位取自图表自身配置（如评审质量图的 `个/KLOC`、`行/小时`）。
- Excel 端点 `POST /api/bi/download/excel` 内部复用与 PNG 完全相同的下载授权门（校验查看 + 下载权限、页面/模板合法、来源版本为当前发布版）；附件文件名由服务端消毒标题与时间戳生成。
- Excel 数据表在元信息行下携带一行“口径说明：…”（取自该图表卡片的问号词条，请求字段 `explanation`，可空）：与看板呈现的达标门槛与计算口径保持单一事实源；空白说明不写入，因此表头/数据行索引与冻结行数随是否携带说明动态推导（无说明时冻结 4 行、有说明时 5 行）。
- 首期仍不输出 CSV、原始明细包或整页长截图。BI Excel 数据表与平台其它 Excel 导出只共享无状态样式工具（`ExcelExportStyles`），数据提取与授权等业务实现各自独立，不互相复用。
