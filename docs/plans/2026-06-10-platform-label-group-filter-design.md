# 平台标签组筛选 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 建立一套符合数据采集平台业务口径的标签组筛选能力：把多个同维度标签值聚合为一个可复用筛选单元，用于条件筛选、报表分析和统一分析口径。

**Architecture:** 标签组只管理同一维度下的已归一化标签值，不创建新业务字段，不跨维度组合筛选条件。页面应用标签组时，将“字段 = 标签组”展开为“字段 IN 标签组成员”，并复用现有事实字段、老平台解析规则和页面查询能力。

**Tech Stack:** Spring Boot、PostgreSQL/Flyway、Vue 3、Element Plus、现有事实层查询与页面筛选组件。

---

## 1. 背景和结论

本方案取代 `docs/plans/2026-06-09-deprecated-tag-group-segmentation-record.md` 中记录的“对象分群 / 语义标签组 / 跨字段筛选方案”方向。已废弃方案只作为历史记录保留，不作为后续实现依据。

新的标签组能力只解决一个明确问题：当同一字段下可选标签很多时，用户可以把常用的一组标签保存成一个整体，在页面筛选和报表分析中直接复用。

一句话定义：

> 标签组是将多个同维度标签聚合为一个可复用筛选单元的能力，用于提升条件配置效率和统一分析口径。

平台内的“标签”不是让用户手动创造的新字段，而是事实层和规则层已经识别出来的可筛选值。它可能来自 GitLab 原生字段，也可能来自 GitLab 标签按老平台规则解析后的归一化字段。

### 1.1 与 GAP 文档的关系

本方案承接 `docs/plans/2026-06-09-user-feedback-gap-analysis.md` 中“标签组筛选”相关诉求，但不覆盖所有 GAP。

| GAP | 本方案关系 | 结论 |
|---|---|---|
| G3 业务事实历史快照 | 相关但不实现 | 第一版标签组按“当前成员”展开；历史快照另行设计，不在本方案落地 |
| G8 个人收藏 / 最近使用页面 | 不实现 | 个人收藏是用户偏好能力，不能复用标签组表 |
| G11 保存筛选方案 | 不实现 | 跨字段筛选方案是查询模板能力，不能混入同维度标签组 |
| G12 标签组平铺/下拉切换 | 部分承接 | 标签组成员选择和应用入口可按数量使用平铺或下拉，但不改变后端模型 |

如果后续要实现“个人收藏”“保存筛选方案”“业务场景模板”，必须独立建模，不能复用本方案的标签组表。

### 1.2 本周 MVP 范围

本周交付目标是让用户能在一个真实业务页面应用静态标签组，而不是完成所有长期能力。

Must：

1. 完成 Phase 0：维度矩阵和页面兼容矩阵。
2. 完成 Phase 1：后端只读维度和候选值接口。
3. 完成 Phase 2：静态标签组数据库和后端 CRUD。
4. 完成 Phase 3：系统设置中的静态标签组管理页。
5. 完成 Phase 4：评审数据管理页面试点应用标签组。

Stretch：

1. Phase 5 中再推广 1 到 2 个记录型页面。
2. 补充更多维度候选值的清洗测试。

Defer：

1. Phase 6 统计板高级筛选接入。
2. Phase 7 动态标签组规则模板与只读预览。
3. Phase 8 权限、审计、性能和全量回归中的非阻断增强项。

## 2. 核心概念

### 2.1 标签维度

标签维度是页面和事实层中可筛选的业务字段，例如：

- 模块
- 项目
- 评审负责人
- 评审专家
- 议题处理人
- 客户问题处理人
- 合并人
- 目标分支
- 里程碑
- 轮次
- 测试阶段
- 严重程度
- 紧急程度
- 缺陷原因
- 延期原因
- 客户问题闭环状态

标签维度必须来自已归一化事实字段或老平台规则明确识别的派生字段。不能从标题、页面名称、统计表头、英文冒号标签、导航名称或未收束文本中临时猜测。

人员类维度不能使用一个泛化的 `owner` 跨页面复用。评审负责人、评审专家、议题处理人、客户问题处理人必须拆成独立维度，各自绑定候选值来源和页面字段，避免同名人员在不同事实源之间静默错配。

### 2.2 标签值

标签值是某一个标签维度下的具体候选项，例如：

- 模块：草图、工程图、BOM
- 评审负责人：张三、李四
- 议题处理人：张三、李四
- 目标分支：dev、release/2026
- 严重程度：一级缺陷、二级缺陷、三级缺陷
- 紧急程度：P1、P2、P3

标签值只能来自系统已有数据和规则字典。创建或维护标签组时，用户通过下拉、搜索、穿梭框、多选列表等方式选择已有标签值，不能手动输入数据库里不存在、规则层不识别的值。

### 2.3 标签组

标签组是同一标签维度下多个标签值的集合。

正确示例：

```text
标签维度：模块
标签组：核心业务系统
成员：CRM、OA、ERP
```

使用时：

```text
模块 = 核心业务系统（标签组）
```

系统执行时展开为：

```text
模块 = CRM OR 模块 = OA OR 模块 = ERP
```

另一个正确示例：

```text
标签维度：评审负责人
标签组：领导
成员：张三、李四、王五
```

使用时：

```text
评审负责人 = 领导（标签组）
```

系统执行时展开为：

```text
评审负责人 = 张三 OR 评审负责人 = 李四 OR 评审负责人 = 王五
```

### 2.4 不支持的模型

标签组不做跨维度条件组合。

错误示例：

```text
标签组：张三负责的 CrownCAD 草图
成员：评审负责人=张三、项目=CrownCAD、模块=草图
```

这个需求应由页面筛选条件组合完成：

```text
评审负责人 = 领导（标签组）
AND 项目 = CrownCAD
AND 模块 = 草图
```

如果后续确实需要保存跨字段筛选条件，应作为“筛选方案”或“查询模板”另行设计，不能混入标签组模型。

## 3. 静态标签组

静态标签组由用户手动维护组内成员，适用于成员相对固定的场景。

示例：

- 模块标签组：核心业务系统 = CRM、OA、ERP
- 人员标签组：领导 = 张三、李四、王五
- 分支标签组：主干分支 = dev、master
- 严重程度标签组：高严重缺陷 = 一级缺陷、二级缺陷

维护规则：

1. 用户先选择标签维度，再选择该维度下的已有标签值。
2. 选中维度后，成员候选列表只能展示该维度的值。
3. 标签组保存后不能改变维度；如需改变，必须新建标签组。
4. 成员被基础数据淘汰或长期不存在时，系统可以提示“当前数据中暂无命中”，但不自动删除静态成员。
5. 静态成员不能手动输入，只能从系统候选值中选择。
6. 单个静态标签组成员数量上限为 200，超过时提示用户拆分为多个标签组，避免生成过长的 `IN` 查询。

## 4. 动态标签组

动态标签组基于规则自动维护组内成员，适用于成员会随基础数据或规则变化的场景。

动态标签组仍然只属于一个标签维度。规则只用于筛选该维度下的候选值，不能生成跨维度条件。

示例：

```text
标签维度：模块
标签组：当前版本有系统测试缺陷的模块
规则：模块在系统测试范围内有命中议题
```

```text
标签维度：客户问题处理人
标签组：当前仍有未闭环客户问题的客户问题处理人
规则：客户问题处理人名下存在未闭环客户问题
```

维护规则：

1. 动态标签组必须声明所属维度。
2. 动态规则的输出必须是该维度下的标签值列表。
3. 动态规则只能使用平台事实字段和规则层允许的条件。
4. 动态标签组需要展示最近一次计算时间、成员数量和计算状态。
5. 动态标签组第一阶段可以只做设计和接口预留，先落静态标签组。

## 5. 标签候选值来源

标签候选值必须来自平台已归一化的数据，遵守 `docs/platform-page-business-rules.md`。

多 GitLab 实例场景下，标签值候选默认按当前页面或当前数据范围的激活源实例聚合；跨源同名值的命中和去重沿用现有事实层去重规则。标签组本身第一版不绑定源实例，同名模块或同名枚举在筛选时按当前页面的数据范围展开。

候选值接口必须过滤页面筛选下拉已明确排除的占位值，包括空值、`未设定...`、`未标注...`、`GitLab接口报错` 等；包含 ` & ` 的组合值按现有规则拆分后去重。

### 5.1 GitLab 原生字段

可直接作为标签维度的 GitLab 原生字段包括但不限于：

- 项目
- 负责人
- 处理人
- 审查人
- 合并人
- 作者
- 目标分支
- 源分支
- 里程碑
- 状态

这些字段必须来自镜像层或事实层已有字段，不能让用户手动创建。

本节列出的是字段类型层面的可选范围，不代表第一版交付清单。具体维度是否启用、维度 key 如何命名、候选值来源和页面兼容关系，以 `docs/plans/2026-06-10-label-group-dimension-matrix.yml` 为准。人员类字段必须按页面和事实语义拆分为独立维度，不能用一个泛化 `owner` 维度跨页面复用。

### 5.2 GitLab 标签派生字段

GitLab 标签派生字段必须按老平台规则识别：

- 议题标签只按中文冒号 `：` 识别 `模块`、`工具箱`、`软件`、`项目`、`状态`、`测试阶段`、`严重程度`、`类别` 等前缀。
- `工具箱` 归并到模块。
- 议题裸标签只允许按老平台规则识别测试阶段关键字、`P1/P2/P3` 和延期原因枚举。
- 合并请求模块只识别 `模块：X`、`模块-X`、`工具箱：X`、`工具箱-X`。
- 英文冒号、裸标签、`XX模块` 后缀、导航名、页面名、统计模块名和未被老平台收束的文本都不得反推为模块、项目或其他业务维度。

### 5.3 不应成为标签值的内容

以下内容不应作为标签组成员候选：

- 指标字段：有效的独立评审问题数合计、独立评审工作量合计、缺陷密度、占比、平均周期等。
- 统计表头：质量看板模块、系统测试汇总模块、横向对比模块等页面结构名称。
- 页面描述：适用场景、规则说明、指标与例外条件等说明性文本。
- 用户手动输入的任意字符串。
- 老平台规则没有识别为业务维度的未归类标签。
- 空值、`未设定...`、`未标注...`、`GitLab接口报错` 等下拉占位值。

指标字段仍可在应用标签组后的结果集上继续排序、筛选、聚合或导出，但它们不是标签组成员。

### 5.4 枚举维度的等价值约束

枚举维度必须明确“保存值”和“匹配值”。保存值是标签组成员的 `member_value`，匹配值是查询展开时实际传给 SQL 或内存过滤的值。

第一版等价值规则：

| 维度 | 规范保存值 | 等价匹配值 | 规则落点 |
|---|---|---|---|
| closure_status | 需求如此 | 需求如此、设计如此 | 候选值服务合并展示，展开服务输出两个匹配值 |

`closure_status` 候选值接口只返回“需求如此”一个候选项，不返回“设计如此”作为独立候选。用户把“需求如此”加入标签组后，查询展开必须同时匹配“需求如此”和“设计如此”。等价值展开放在 `LabelGroupExpansionService`，页面查询服务只消费展开后的匹配值列表，避免每个页面重复实现。

## 6. 页面应用规则

### 6.1 兼容性

页面能否应用某个标签组，只取决于页面是否支持该标签组所属维度。

示例：

- 页面支持“模块”筛选，则可以应用“模块”维度标签组。
- 页面支持“评审负责人”筛选，则可以应用“评审负责人”维度标签组。
- 页面支持“议题处理人”筛选，则可以应用“议题处理人”维度标签组。
- 页面不包含“紧急程度”字段，则不能应用“紧急程度”维度标签组。

不需要用户手动选择“适用页面”或“字段范围”。系统根据页面筛选字段和标签组维度自动计算兼容性。

### 6.2 交互方式

业务页面只提供“应用已保存标签组”的入口，不提供创建和编辑入口。

建议交互：

1. 页面筛选区中，每个支持标签组的字段旁边提供“标签组”选择入口。
2. 用户选择字段后，只展示该字段维度下可用的标签组。
3. 选择标签组后，筛选条件展示为“字段 = 标签组名（标签组）”。
4. 用户可以展开查看组内成员，但默认不要求逐个确认。
5. 表格查询时自动展开为 `字段 IN 成员列表`。
6. 如果标签组成员为空，页面应提示“该标签组当前没有可用成员”，不发起无意义查询。

设置页负责标签组创建、编辑、删除和动态规则维护。

### 6.3 与现有筛选条件关系

标签组是现有筛选条件的快捷方式，不替代现有筛选能力。

示例：

```text
评审负责人 = 领导（标签组）
AND 项目 = CrownCAD
AND 模块 = 草图
```

含义是：

```text
(评审负责人 = 张三 OR 评审负责人 = 李四 OR 评审负责人 = 王五)
AND 项目 = CrownCAD
AND 模块 = 草图
```

同一个字段上，如果用户同时选择普通值和标签组，应统一折叠为一个 `IN` 条件。是否允许普通值和标签组混选由页面交互决定，但后端查询语义必须一致。

同字段折叠时必须先去重，最终 `IN` 列表上限仍为 200。超过上限时返回中文业务错误，例如“筛选条件展开后超过 200 个值，请减少普通筛选值或拆分标签组”，不能静默截断。

多值字段的 `IN` 语义是“字段值集合与标签组成员集合相交不为空”。例如评审记录的 `reviewExperts` 为 `[李四, 王五]`，标签组成员为 `[张三, 李四]` 时应命中；`reviewExperts` 为 `[王五]` 时不命中。多值字段不能按单个字符串精确等于实现。

### 6.4 历史口径

标签组成员变化会影响历史报表重新查询时的结果。平台存在三种可选口径：

1. 当前成员：任何时候应用标签组，都按标签组当前成员展开。
2. 应用时刻成员：用户执行查询时记录当时成员，后续重新打开按当时成员展开。
3. 快照成员：历史报表冻结到业务事实快照表，随 G3 业务事实历史快照方案统一处理。

第一版选择“当前成员”。原因是实现简单，符合“标签组是可复用筛选单元”的当前使用预期，也不提前绑定尚未落地的 G3 历史快照能力。页面和导出必须在规则说明中标注：历史数据重新查询时使用标签组当前成员；如果业务方要求复盘口径冻结，必须等 G3 快照方案落地后再扩展。

标签组成员变更全部进入操作审计。业务方需要回溯某天报表的当时成员时，第一版通过操作审计追溯，不依赖运行时标签组表保存历史版本。

导出文件必须同时写明标签组名和导出时展开的成员快照，例如“模块 = 核心业务系统（标签组：CRM、OA、ERP）”。这个成员快照只写入导出文件作为审计提示，不写入数据库，不改变第一版“当前成员”运行时口径。

## 7. 设置页管理规则

标签组管理入口放在系统设置中。

创建静态标签组的最小表单：

1. 标签组名称。
2. 标签维度。
3. 成员标签值。
4. 备注，可选。

不让用户填写：

- 适用页面。
- 字段范围。
- 适用对象。
- 维护人。
- Hash。
- 规则 DSL。
- 任意自定义字段名。

系统自动展示：

- 当前成员数量。
- 可应用主模块。
- 可应用页面明细。
- 不可应用页面原因。
- 最近使用时间。
- 创建人和更新时间。

创建动态标签组的表单在静态标签组稳定后再启用，必须先提供经过产品确认的规则模板，不开放自由 DSL。

标签组可见范围第一版为全局共享。个人收藏、最近使用、角色默认视图、跨字段筛选方案和业务场景模板均独立建模，不复用标签组表。

## 8. 命名边界

后续实现应采用“标签组 / 标签维度 / 标签值”这套业务语言。

建议数据库和接口命名：

- `label_groups`
- `label_group_members`
- `label_dimensions`
- `label_member_value`
- `label_display_name`

建议前端页面和组件命名：

- `LabelGroupSettingsView`
- `LabelGroupSelector`
- `LabelGroupMemberPicker`
- `LabelDimensionOption`

建议 Java 包路径：

- `com.data.collection.platform.controller.LabelGroupController`
- `com.data.collection.platform.entity.labelgroup.*`
- `com.data.collection.platform.service.labelgroup.*`

不得复用已经废弃的旧方向命名、接口、页面或数据库运行时模型。历史迁移中已存在的废弃名称仅作为迁移记录保留，不作为新实现依据。

实现前必须阅读 `backend/src/test/java/com/data/collection/platform/architecture/NoDeprecatedTagGroupOrSegmentationRuntimeTest.java`。运行时代码、前端代码和新 SQL 中严禁再次出现该守门测试禁止的标记，包括但不限于：

- `taggroup`
- `tagselection`
- `tagselections`
- `businesstaggroup`
- `semantictaggroup`
- `segmentfilterpreset`
- `segmentdefinition`
- `segmentcomputeservice`
- `segmentcostestimator`
- `segmentschemacompatibilitychecker`
- `segmentmanagementcontroller`
- `tag-groups`
- `business-tag-groups`
- `semantic-tag-groups`
- `tag_group`
- `tag_value`
- `business_tag_group`
- `semantic_tag_group`
- `semantic_tag_value`
- `segment_filter_preset`
- `segment_definition`
- `segment_compute_run`
- `segment_member`
- `segment_snapshot`

特别注意：不要写 `label_tag_value`、`tag_value_mapping`、`tag_group_filter` 等包含禁词的派生命名。使用 `label_member_value`、`label_display_name`、`label_group_filter` 这类不会触发禁词的名称。

## 9. 实施总原则

1. 第一版只实现静态标签组，动态标签组只做模型和接口边界预留，不开放页面配置。
2. 先实现“系统设置中维护标签组”，再接入业务页面“应用标签组”。业务页面不提供创建、编辑和删除入口。
3. 先接入记录型表格，再接入统计型表格。试点顺序建议为：评审数据管理、系统测试议题查询、客户问题列表、系统测试非法数据、统计板高级筛选。
4. 每一步都必须先补测试或契约，再做实现。标签组会影响筛选口径，不能靠手测确认。
5. 候选值、兼容页面和查询展开都由后端统一计算；前端只负责展示和提交用户选择。
6. 接口、数据库和前端类型统一使用第 8 节允许的命名。不要复用已废弃的旧命名，也不要使用守门测试禁止的 `tag_value` 等派生命名。
7. 所有显示文案必须是中文；英文标识只作为接口字段、数据库字段和代码枚举存在。
8. 接口错误统一返回 `{code: 英文常量, message: 中文说明}`，前端只展示 `message`。

## 10. 分阶段实施计划

### Phase 0: 维度矩阵和页面兼容矩阵

**目标：** 在写运行时代码前，先确认“哪些字段可以成为标签维度、每个页面支持哪些标签维度、每个维度的候选值从哪里来”。

**Files:**
- Create: `docs/plans/2026-06-10-label-group-dimension-matrix.md`
- Create: `docs/plans/2026-06-10-label-group-dimension-matrix.yml`
- Modify: `docs/platform-page-business-rules.md`

**Step 1: 新建维度矩阵文档**

先创建 `docs/plans/2026-06-10-label-group-dimension-matrix.yml`，作为机器可读的权威来源。Markdown 表格只给人阅读，必须从 YAML 手动同步，不作为守门脚本解析对象。

YAML 至少包含：

```yaml
dimensions:
  - key: module
    name: 模块
    valueKind: STRING_LITERAL
    source: 事实层归一化模块字段，遵守老平台模块识别规则
    mvpSupported: true
    notes: 工具箱归并到模块
  - key: review_owner
    name: 评审负责人
    valueKind: STRING_LITERAL
    source: 评审数据 reviewOwner 等现有文本字段
    mvpSupported: true
    notes: 第一版按文本保存，接受重名风险
  - key: review_expert
    name: 评审专家
    valueKind: STRING_LITERAL
    source: 评审数据 reviewExpert/reviewExpertsSummary 多值字段
    mvpSupported: true
    notes: 多值字段按成员集合相交匹配
  - key: issue_assignee
    name: 议题处理人
    valueKind: STRING_LITERAL
    source: 议题事实层 assigneeName 文本字段
    mvpSupported: true
    notes: 系统测试议题查询使用
  - key: customer_assignee
    name: 客户问题处理人
    valueKind: STRING_LITERAL
    source: 客户问题事实层 assigneeName 文本字段
    mvpSupported: true
    notes: 客户问题列表使用
pages:
  - pageKey: review-data-home
    name: 评审数据管理
    mvpEnabled: true
    dimensions:
      - module
      - project
      - review_owner
      - review_expert
```

再创建 `docs/plans/2026-06-10-label-group-dimension-matrix.md`。Markdown 只保留页面兼容矩阵、不允许字段表和说明文字，不再手写“标签维度清单”表格；标签维度清单以 YAML 为准，避免双源漂移。

```markdown
# 标签组维度与页面兼容矩阵

## 页面兼容矩阵

| 页面 | 页面 Key | 支持标签维度 | 第一版接入 | 备注 |
|---|---|---|---|---|
| 评审数据管理 | review-data-home | 项目、模块、评审负责人、评审专家 | 是 | 第一试点 |
| 系统测试议题查询 | question-metrics-issue-search | 项目、模块、测试阶段、严重程度、紧急程度、里程碑、议题处理人 | 是 | 第二试点 |
| 客户问题列表 | customer-issues-cc-product-issues | 模块、客户问题处理人、紧急程度、里程碑、闭环状态 | 是 | 第三试点 |
| 统计板高级筛选 | statistic-board-* | 取决于看板 definition.filters | 后续 | 需逐看板验证 |
```

```markdown
## 不允许成为标签维度的字段

| 字段 | 原因 |
|---|---|
| 有效的独立评审问题数合计 | 指标，不是标签值 |
| 独立评审工作量合计 | 指标，不是标签值 |
| 缺陷密度 | 计算结果，不是标签值 |
| 占比 | 计算结果，不是标签值 |
| 质量看板模块 | 页面结构名称，不是事实字段 |
```

**Step 2: 对照现有前端页面筛选字段**

检查这些文件中的筛选字段：

- `frontend/src/views/review-data-management.ts`
- `frontend/src/views/SystemTestIssueSearchView.vue`
- `frontend/src/views/CustomerIssueRecordsView.vue`
- `frontend/src/views/issue-illegal-records/IssueIllegalRecordsPage.vue`
- `frontend/src/components/statistic-board-filters.ts`
- `frontend/src/views/system-test/system-test-condition-fields.ts`
- `frontend/src/views/customer-issues/customer-issue-condition-fields.ts`

把每个页面现有筛选字段映射到维度矩阵。无法明确映射的字段先标记为“不支持标签组”，不要猜。

**Step 3: 对照后端事实字段和规则层**

检查这些后端文件：

- `backend/src/main/java/com/data/collection/platform/service/IssueFactNormalizationRules.java`
- `backend/src/main/java/com/data/collection/platform/service/IssueFactQueryService.java`
- `backend/src/main/java/com/data/collection/platform/service/ReviewDataFilterOptionService.java`
- `backend/src/main/java/com/data/collection/platform/service/ReviewDataRecordQueryService.java`
- `backend/src/main/java/com/data/collection/platform/service/SystemTestIssueSearchService.java`
- `backend/src/main/java/com/data/collection/platform/service/CustomerIssueRecordService.java`
- `backend/src/main/java/com/data/collection/platform/service/statistics/*BoardService.java`

确认候选值来源是事实层字段、规则枚举还是页面已有 filter options。未在后端事实层稳定存在的字段，第一版不进入标签组维度。

**Step 4: 确认架构守门命名**

读取 `backend/src/test/java/com/data/collection/platform/architecture/NoDeprecatedTagGroupOrSegmentationRuntimeTest.java`，把第 8 节禁词清单与测试保持一致。确认本方案后续运行时代码不会出现 `tag_value`、`tag_group`、`semantic_tag_group`、`segment_*` 等禁词。

**Step 5: 设计维度矩阵漂移守门**

新增脚本计划：`scripts/check_label_group_dimension_matrix.py`。

脚本职责：

1. 从 `docs/plans/2026-06-10-label-group-dimension-matrix.yml` 读取维度 key、`valueKind` 和页面兼容关系。
2. 从后端维度目录枚举或 `LabelDimensionCatalogService` 的注册表读取代码中的维度 key。
3. 比对 YAML 与代码，任何一侧缺失都失败。
4. 输出中文错误，提示先更新矩阵再改代码。

该脚本在 Phase 1 维度目录实现后落地，并纳入回归验证。

脚本必须配套测试 `scripts/check_label_group_dimension_matrix_test.py`，覆盖：

1. YAML 与代码一致时通过。
2. YAML 多一个维度时代码缺失，失败。
3. 代码多一个维度时 YAML 缺失，失败。
4. `valueKind` 不是 `STRING_LITERAL`、`GITLAB_USER_ID`、`ENUM_KEY`、`BRANCH_NAME` 之一时失败。

脚本和测试必须加入 `scripts/verify-local.ps1` 的常规校验序列，调用风格与现有脚本保持一致，使用 `python scripts/<name>.py`，不要引入 `python3` 或其他调用方式。

`_test.py` 必须能用 `python scripts/<name>_test.py` 直接运行，与 `scripts/check_flyway_destructive_migrations_test.py` 的现有约定保持一致；如果测试依赖框架，测试文件自身必须负责入口，不能要求 `verify-local.ps1` 临时换一种运行方式。

**Step 6: 更新业务规则总表**

在 `docs/platform-page-business-rules.md` 的“标签组筛选规则”后补一个链接，指向维度矩阵，并写明“实现前必须先更新矩阵”。

**Step 7: 验证**

Run:

```powershell
git diff --check
```

Expected: 无输出。

### Phase 1: 后端只读维度和候选值接口

**目标：** 先提供系统可识别的标签维度和候选值，不做标签组保存。这样可以先验证候选值是否干净、是否遵守老平台规则。

**Files:**
- Create: `backend/src/main/java/com/data/collection/platform/controller/LabelGroupController.java`
- Create: `backend/src/main/java/com/data/collection/platform/entity/labelgroup/LabelDimensionResponse.java`
- Create: `backend/src/main/java/com/data/collection/platform/entity/labelgroup/LabelValueResponse.java`
- Create: `backend/src/main/java/com/data/collection/platform/entity/labelgroup/LabelGroupCompatiblePageResponse.java`
- Create: `backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelDimensionCatalogService.java`
- Create: `backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelValueQueryService.java`
- Create: `backend/src/test/java/com/data/collection/platform/controller/LabelGroupControllerTest.java`
- Create: `backend/src/test/java/com/data/collection/platform/service/labelgroup/LabelDimensionCatalogServiceTest.java`
- Create: `backend/src/test/java/com/data/collection/platform/service/labelgroup/LabelValueQueryServiceTest.java`

**API Contract:**

先扫一遍现有 Controller 的路径风格，确认本项目 API 以 kebab-case 资源名为主后，再使用以下路径。若当时主流风格变化，以现有 Controller 主流风格为准，但不得使用已禁用的 `tag-groups`。

```text
GET /api/label-groups/dimensions
GET /api/label-groups/dimensions/{dimensionKey}/values?pageKey=&sourceInstanceId=&keyword=&page=&size=
GET /api/label-groups/dimensions/{dimensionKey}/compatible-pages
```

`GET /api/label-groups/dimensions` response:

```json
{
  "code": 0,
  "data": [
    {
      "key": "module",
      "name": "模块",
      "description": "按老平台规则识别并归一化后的模块",
      "valueKind": "STRING_LITERAL",
      "staticSupported": true,
      "dynamicSupported": false
    }
  ]
}
```

`GET /api/label-groups/dimensions/module/values` response:

```json
{
  "code": 0,
  "data": {
    "items": [
      {
        "value": "草图",
        "label": "草图",
        "valueKind": "STRING_LITERAL",
        "source": "FACT",
        "hitCount": 128
      }
    ],
    "total": 1
  }
}
```

**Step 1: 写维度目录服务测试**

`LabelDimensionCatalogServiceTest` 需要覆盖：

- 返回中文维度名。
- 维度 key 稳定，例如 `module`、`project`、`review_owner`、`review_expert`、`issue_assignee`、`customer_assignee`。
- 每个维度声明 `valueKind`，取值为 `STRING_LITERAL`、`GITLAB_USER_ID`、`ENUM_KEY`、`BRANCH_NAME` 之一。
- 不包含指标字段，例如“缺陷密度”“占比”“工作量合计”。
- 不包含已废弃概念。
- 不出现 `tag_value`、`tag_group` 等架构守门禁词。

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-local.ps1 -SkipDatabase
```

Expected: 新测试因服务不存在失败。

**Step 2: 实现最小维度目录**

`LabelDimensionCatalogService` 先使用代码内固定目录，但目录内容必须来自 Phase 0 矩阵。固定的是“维度定义”，不是“标签值”。标签值仍从数据库和规则层查询。

维度注册表必须包含 `valueKind`：

- `STRING_LITERAL`：模块、项目、里程碑等文本值。
- `GITLAB_USER_ID`：人员维度优先使用稳定 ID，显示名通过候选值服务回填。
- `ENUM_KEY`：严重程度、紧急程度、状态、延期原因等枚举。
- `BRANCH_NAME`：目标分支、源分支。

如果某个人员维度当前事实层只有中文姓名，没有稳定 ID，矩阵中必须标为 `STRING_LITERAL` 并写明重名风险，不能假装已经有 ID。

**Step 3: 写候选值查询测试**

`LabelValueQueryServiceTest` 用 mock `JdbcTemplate` 或测试数据覆盖：

- 不传 `pageKey` 时返回该维度的全量候选值聚合结果。
- 传 `pageKey=review-data-home` 时，只返回评审数据管理页面范围内该维度可用的候选值。
- 传 `sourceInstanceId` 时，只返回该源实例范围内的候选值；不传时按全部源聚合。
- 模块候选值来自归一化模块字段。
- `工具箱：草图` 已在事实层归并为“草图”后才出现。
- 空值、`未设定...`、`未标注...`、`GitLab接口报错` 不出现在候选列表。
- `工程图 & 平台` 这类组合值被拆分为两个候选值。
- 英文冒号标签、页面名、统计模块名不会进入候选值。

**Step 4: 实现候选值查询服务**

`LabelValueQueryService` 按维度分派查询：

- 第一版优先复用已有 filter options 服务，例如 `ReviewDataFilterOptionService`。
- 对 issue fact 维度，统一从事实层字段查询 distinct 值。
- 组合值拆分和占位值过滤要复用已有规则，不在前端重复处理。
- `pageKey` 是显式查询范围参数，不能从 session 隐式读取；无 `pageKey` 时按全部源和全部兼容页面聚合。
- `sourceInstanceId` 是可选源实例过滤参数；无 `sourceInstanceId` 时按全部源聚合。

**Step 5: 写 Controller 测试**

`LabelGroupControllerTest` 覆盖：

- `/dimensions` 返回中文维度名。
- `/dimensions/module/values` 支持 `pageKey`、`sourceInstanceId`、关键词搜索和分页。
- 不存在的维度返回业务错误。
- `/compatible-pages` 返回可用页面和不可用原因。

**Step 6: 验证**

Run:

```powershell
( $env:JAVA_HOME='D:\projects\data_collection_platform\tools\jdk\jdk-21.0.10+7'; $env:PATH="$env:JAVA_HOME\bin;D:\projects\data_collection_platform\tools\maven\apache-maven-3.9.9\bin;$env:PATH"; cd backend; mvn -q "-Dtest=LabelDimensionCatalogServiceTest,LabelValueQueryServiceTest,LabelGroupControllerTest" test )
python scripts/check_label_group_dimension_matrix.py
```

Expected: PASS。

### Phase 2: 静态标签组数据库和后端 CRUD

**目标：** 实现标签组保存、成员维护、同维度校验和查询展开基础能力。

**Files:**
- Create: `backend/src/main/resources/db/migration/V20260610_01__label_group_core_schema.sql`
- Create: `backend/src/main/java/com/data/collection/platform/entity/labelgroup/LabelGroupCreateRequest.java`
- Create: `backend/src/main/java/com/data/collection/platform/entity/labelgroup/LabelGroupUpdateRequest.java`
- Create: `backend/src/main/java/com/data/collection/platform/entity/labelgroup/LabelGroupResponse.java`
- Create: `backend/src/main/java/com/data/collection/platform/entity/labelgroup/LabelGroupMemberResponse.java`
- Create: `backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelGroupService.java`
- Create: `backend/src/main/java/com/data/collection/platform/service/labelgroup/LabelGroupExpansionService.java`
- Create: `backend/src/test/java/com/data/collection/platform/service/labelgroup/LabelGroupServiceTest.java`
- Create: `backend/src/test/java/com/data/collection/platform/service/labelgroup/LabelGroupExpansionServiceTest.java`
- Modify: `backend/src/main/java/com/data/collection/platform/controller/LabelGroupController.java`
- Modify: `backend/src/test/java/com/data/collection/platform/controller/LabelGroupControllerTest.java`

**Schema Draft:**

```sql
create table label_groups (
  id bigserial primary key,
  name varchar(100) not null,
  dimension_key varchar(64) not null,
  group_type varchar(16) not null default 'STATIC',
  description varchar(500),
  enabled boolean not null default true,
  created_by varchar(100),
  created_at timestamptz not null default now(),
  updated_by varchar(100),
  updated_at timestamptz not null default now(),
  constraint ck_label_groups_type check (group_type in ('STATIC', 'DYNAMIC')),
  constraint uk_label_groups_dimension_name unique (dimension_key, name)
);

create table label_group_members (
  id bigserial primary key,
  group_id bigint not null references label_groups(id) on delete cascade,
  dimension_key varchar(64) not null,
  member_value varchar(255) not null,
  display_name varchar(255) not null,
  sort_order int not null default 0,
  created_at timestamptz not null default now(),
  constraint uk_label_group_members_value unique (group_id, member_value)
);

create index idx_label_groups_dimension on label_groups(dimension_key);
create index idx_label_group_members_group on label_group_members(group_id);
create index idx_label_group_members_dimension_value on label_group_members(dimension_key, member_value);
```

说明：`label_group_members.dimension_key` 必须与 `label_groups.dimension_key` 一致。PostgreSQL 无法用简单 check 跨表验证，必须在 service 保存时校验，并在测试中覆盖。

`member_value` 是稳定匹配值，含义由维度注册表的 `valueKind` 决定。人员维度如果是 `GITLAB_USER_ID`，`member_value` 存用户 ID；如果当前事实层只有姓名，才允许按 `STRING_LITERAL` 存姓名。`display_name` 是冗余兜底显示名，列表查询时应优先通过维度候选值服务按 `member_value` 回填最新中文名，数据库字段只用于候选值暂时不可用时展示。`display_name` 仅在 `GITLAB_USER_ID`、`ENUM_KEY` 等需要 ID/枚举到中文名称映射的维度上有额外意义；`STRING_LITERAL` 维度可写入与 `member_value` 相同的值，校验时不要求二者不同。

**API Contract:**

```text
GET /api/label-groups?dimensionKey=&keyword=&enabled=
POST /api/label-groups
GET /api/label-groups/{groupId}
PUT /api/label-groups/{groupId}
DELETE /api/label-groups/{groupId}
POST /api/label-groups/{groupId}/expand?pageKey=&sourceInstanceId=
```

Create request:

```json
{
  "name": "核心业务系统",
  "dimensionKey": "module",
  "description": "常用核心业务系统模块",
  "members": [
    { "value": "CRM", "label": "CRM" },
    { "value": "OA", "label": "OA" },
    { "value": "ERP", "label": "ERP" }
  ]
}
```

**Step 1: 写 Flyway schema 检查**

如项目已有 Flyway drift 检查，新增迁移后必须跑：

```powershell
python scripts/check_schema_flyway_drift.py
python scripts/check_flyway_destructive_migrations.py
python scripts/check_flyway_migration_immutability.py
```

Expected: PASS。

**Step 2: 写 Service 测试**

`LabelGroupServiceTest` 覆盖：

- 创建模块标签组成功。
- 同一维度下标签组名称不能重复。
- 创建时维度不存在则失败。
- 成员为空则失败。
- 成员格式不符合维度 `valueKind` 则失败。
- `closure_status` 直接保存等价值“设计如此”时失败，并提示使用规范保存值“需求如此”。
- 成员不在当前候选集中时允许保存，但响应中标记“当前数据中暂无命中”。
- 更新时不能改变维度。
- 删除标签组会删除成员。
- 返回列表时展示成员数量和中文维度名。
- 单组成员超过 200 时失败并提示拆分。

**Step 3: 实现 CRUD 服务**

`LabelGroupService` 使用 `NamedParameterJdbcTemplate` 或项目现有数据访问方式。不要引入新的 ORM 风格。

写时校验只做：

1. 维度存在。
2. 成员数量在 1 到 200 之间。
3. 成员值格式符合 `valueKind`。
4. 成员维度与标签组维度一致。
5. 枚举维度 `ENUM_KEY` 的成员值必须是该维度的规范保存值之一；等价值不能作为成员保存。例如 `closure_status` 只能保存“需求如此”，直接保存“设计如此”应返回中文业务错误，提示用户使用“需求如此”。

不要在写时强制要求文本类成员一定存在于当前候选值接口。镜像刷新、事实层刷新或当前页面数据范围可能导致候选值临时缺失；这种情况在读时展示“当前数据中暂无命中”，不阻断保存。

**Step 4: 写展开服务测试**

`LabelGroupExpansionServiceTest` 覆盖：

- 展开静态组返回同维度成员列表。
- 禁用组不能展开。
- 空成员组不能展开为“匹配全部”。
- 请求字段维度与标签组维度不一致时失败。
- `pageKey` 和 `sourceInstanceId` 透传给展开服务后，能按页面和源实例应用等价值、字段路由和候选范围规则。
- `closure_status` 成员 `需求如此` 展开为 `需求如此`、`设计如此` 两个匹配值。

**Step 5: Controller 接口补齐**

`LabelGroupController` 接入 CRUD 和 expand。所有错误信息用中文，例如：

- `标签维度不存在`
- `标签组成员不能为空`
- `标签组只能应用到相同维度的字段`
- `标签组成员超过 200 个，请拆分后保存`
- `标签组成员格式不符合当前维度要求`

**Step 6: 验证**

Run:

```powershell
( $env:JAVA_HOME='D:\projects\data_collection_platform\tools\jdk\jdk-21.0.10+7'; $env:PATH="$env:JAVA_HOME\bin;D:\projects\data_collection_platform\tools\maven\apache-maven-3.9.9\bin;$env:PATH"; cd backend; mvn -q "-Dtest=LabelGroupServiceTest,LabelGroupExpansionServiceTest,LabelGroupControllerTest" test )
```

Expected: PASS。

### Phase 3: 前端 API 类型和系统设置管理页

**目标：** 用户能在系统设置里创建、编辑、删除静态标签组，并且只能从系统候选值中选择成员。

**Files:**
- Create: `frontend/src/types/api/label-groups.ts`
- Modify: `frontend/src/types/api/index.ts`
- Create: `frontend/src/api-client/label-groups-api.ts`
- Create: `frontend/src/api-client/label-groups-api.test.ts`
- Create: `frontend/src/views/LabelGroupSettingsView.vue`
- Create: `frontend/src/views/label-groups/label-group-settings.ts`
- Create: `frontend/src/views/label-groups/label-group-settings.test.ts`
- Create: `frontend/src/components/label-groups/LabelGroupMemberPicker.vue`
- Create: `frontend/src/components/label-groups/LabelGroupMemberPicker.test.ts`
- Modify: `frontend/src/router.ts`
- Modify: `frontend/src/feature-manifest/*`

**Step 1: 写 API Client 测试**

`label-groups-api.test.ts` 覆盖：

- 查询维度列表。
- 查询维度候选值。
- 创建标签组。
- 更新标签组。
- 删除标签组。
- API 错误消息能透出中文。

Run:

```powershell
( cd frontend; npm.cmd run test -- src/api-client/label-groups-api.test.ts )
```

Expected: 新测试先失败。

**Step 2: 实现 API Client 和类型**

类型命名示例：

```ts
export interface LabelDimension {
  key: string;
  name: string;
  description?: string;
  valueKind: 'STRING_LITERAL' | 'GITLAB_USER_ID' | 'ENUM_KEY' | 'BRANCH_NAME';
  staticSupported: boolean;
  dynamicSupported: boolean;
}

export interface LabelGroup {
  id: number;
  name: string;
  dimensionKey: string;
  dimensionName: string;
  description?: string;
  memberCount: number;
  members: LabelGroupMember[];
  enabled: boolean;
  updatedAt: string;
}
```

**Step 3: 写成员选择器测试**

`LabelGroupMemberPicker.test.ts` 覆盖：

- 未选择维度时不展示候选值。
- 选择“模块”后只加载模块候选值。
- 支持关键词搜索。
- 已选成员展示为标签。
- 不能手动输入任意字符串。
- 当前候选值缺失但已保存的成员要能展示“当前数据中暂无命中”，不能静默删除。
- 候选值为空时展示中文空状态。

**Step 4: 实现成员选择器**

使用 Element Plus 的多选、远程搜索或穿梭框。优先选择低学习成本交互：

- 左侧候选值搜索。
- 右侧已选成员。
- 成员数量实时显示。
- 不出现“适用对象”“字段范围”“Hash”“指标与例外条件”。

**Step 5: 写设置页状态测试**

`label-group-settings.test.ts` 覆盖：

- 页面加载维度和标签组列表。
- 新建弹窗按“标签组名称 -> 标签维度 -> 成员”顺序填写。
- 选择维度后显示可应用页面预览。
- 保存成功后刷新列表。
- 编辑时不能修改维度。
- 删除前二次确认。

**Step 6: 实现设置页**

页面布局建议：

- 顶部：维度筛选、关键词搜索、新建按钮。
- 表格：标签组名称、维度、成员数量、成员预览、可应用范围、更新时间、操作。
- 抽屉或弹窗：创建/编辑。
- 预览区：按主模块展示可用页面数量，展开后看具体页面。

**Step 7: 接入路由和菜单**

在系统设置模块下新增页面，例如“标签组管理”。如果系统设置菜单由 feature manifest 驱动，优先在 manifest 中声明，不在路由里硬写孤立入口。

**Step 8: 验证**

Run:

```powershell
( cd frontend; npm.cmd run test -- src/api-client/label-groups-api.test.ts src/components/label-groups/LabelGroupMemberPicker.test.ts src/views/label-groups/label-group-settings.test.ts )
( cd frontend; npm.cmd run typecheck )
```

Expected: PASS。

### Phase 4: 记录型表格试点接入

**目标：** 在记录型页面中应用标签组筛选，先选一个页面端到端打通，再复制到其他页面。

**试点页面：** 评审数据管理。

**Files:**
- Modify: `frontend/src/types/record-table.ts`
- Modify: `frontend/src/components/base/RecordTableFilterFields.vue`
- Modify: `frontend/src/components/base/RecordTableFilterFieldRenderer.vue`
- Create: `frontend/src/components/label-groups/LabelGroupSelector.vue`
- Create: `frontend/src/components/label-groups/LabelGroupSelector.test.ts`
- Modify: `frontend/src/views/review-data-management.ts`
- Modify: `frontend/src/api-client/review-data-api.ts`
- Modify: `frontend/src/types/api/review-data.ts`
- Modify: `backend/src/main/java/com/data/collection/platform/controller/ReviewDataRecordListRequest.java`
- Modify: `backend/src/main/java/com/data/collection/platform/controller/ReviewDataRequestAssembler.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/ReviewDataRecordQueryRequest.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/ReviewDataRecordQueryService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/ReviewDataExcelExportService.java`
- Modify: `backend/src/test/java/com/data/collection/platform/controller/ReviewDataRequestAssemblerTest.java`
- Modify: `backend/src/test/java/com/data/collection/platform/service/ReviewDataRecordQueryServiceTest.java`

**Step 1: 扩展前端筛选字段类型**

在动筛选组件前，先对照 `ReviewDataRecordQueryService.java`、`ReviewDataRecordQueryRequest.java` 和 `ReviewDataRecordListRequest.java` 写字段映射表，并补到 Phase 0 维度矩阵：

| 标签维度 | 页面字段 | 后端请求字段 | 查询字段 | value_kind | 第一版 |
|---|---|---|---|---|---|
| project | 项目 | `projectName` | `ReviewDataRecordQueryRequest.projectName` | STRING_LITERAL | 是 |
| review_owner | 评审负责人 | `reviewOwner` | `ReviewDataRecordQueryRequest.reviewOwner` | STRING_LITERAL | 是 |
| review_expert | 评审专家 | `reviewExpert` | `ReviewDataRecordQueryRequest.reviewExpert` | STRING_LITERAL | 是 |
| module | 模块 | `moduleName` | `ReviewDataRecordQueryRequest.moduleName` | STRING_LITERAL | 是 |

如果代码后续字段名变化，必须先更新这张映射表和 Phase 0 维度矩阵，再改页面。评审数据里的工作量、问题数、缺陷密度等指标字段不得进入映射表。

在 `RecordTableFilterField` 增加：

```ts
labelDimensionKey?: string;
labelGroupEnabled?: boolean;
```

字段示例：

```ts
{
  key: 'moduleName',
  label: '模块',
  type: 'select',
  labelDimensionKey: 'module',
  labelGroupEnabled: true,
}
```

**Step 2: 写标签组选择器测试**

`LabelGroupSelector.test.ts` 覆盖：

- 只加载当前维度的标签组。
- 选择后显示“标签组名（标签组）”。
- 可展开查看成员。
- 空成员标签组禁用或提示。
- 清空后移除标签组筛选。

**Step 3: 实现 `LabelGroupSelector`**

组件只负责选择已保存标签组，不创建、不编辑。创建入口只显示为“去系统设置维护”，不在业务页面弹出管理表单。

同时增加业务页面守门测试或静态检查，确保业务页面 Vue/TS 文件不引入这些符号：

- `LabelGroupSettingsView`
- `LabelGroupMemberPicker`
- `createLabelGroup`
- `updateLabelGroup`
- `deleteLabelGroup`
- `POST /api/label-groups`
- `PUT /api/label-groups`
- `DELETE /api/label-groups`

建议脚本名：`scripts/check_label_group_business_page_guard.py`。业务页面只能引入 `LabelGroupSelector` 和只读查询 API。

脚本必须配套测试 `scripts/check_label_group_business_page_guard_test.py`，覆盖：

1. 业务页面只使用 `LabelGroupSelector` 时通过。
2. 业务页面引入 `LabelGroupSettingsView` 时失败。
3. 业务页面调用 `createLabelGroup`、`updateLabelGroup`、`deleteLabelGroup` 时失败。
4. 设置页调用管理 API 时不失败。

**Step 4: 扩展记录表筛选渲染器**

`RecordTableFilterFieldRenderer.vue` 在支持标签组的 select 字段旁渲染标签组选择入口。避免占用过多筛选区空间，可以使用小按钮或下拉触发器，文案必须中文。

**Step 5: 后端请求增加标签组筛选参数**

统一使用 JSON 数组格式，不提供 `labelGroupFilter.moduleName=1` 这类字段散落格式。

```text
labelGroupFilters=[{"fieldKey":"moduleName","dimensionKey":"module","groupId":1}]
```

原因：

1. 前后端都能显式携带字段、维度和标签组 ID，便于提示和二次校验。
2. 通用 DTO 可直接复用于 Phase 5 记录型页面。
3. 与 Phase 6 统计板的 `inLabelGroup` 语义一致。
4. 后续如果允许同字段多组，数组格式能自然扩展。

如果 GET query 过长，先扫现有列表 Controller 是否已有 POST + body 的列表查询模式；若项目主流做法允许，新增 POST 查询接口承载同一 DTO。不能临时新增第二套标签组参数格式。无论前端如何传参，后端都必须校验字段维度与标签组维度一致。

**Step 6: 查询服务展开标签组**

在 `ReviewDataRecordQueryService` 中，应用普通筛选前先通过 `LabelGroupExpansionService` 展开：

```text
moduleName groupId=1 -> moduleName IN ('CRM', 'OA', 'ERP')
```

导出服务必须复用同一个 `ReviewDataRecordQueryRequest`，不能导出绕过标签组筛选。

**Step 7: 写后端测试**

覆盖：

- `moduleName` 应用模块标签组后只返回组内模块。
- `reviewOwner` 应用评审负责人标签组后只返回组内评审负责人。
- `reviewExpert` 应用评审专家标签组后按多值字段语义匹配：记录 `reviewExperts = [李四, 王五]`、标签组成员 `[张三, 李四]` 时命中；记录 `reviewExperts = [王五]`、标签组成员 `[张三, 李四]` 时不命中。
- 实现复用 `ReviewDataRecordFilterGroupSupport.splitMultiValue(row.reviewExpertsSummary())` 同款多值拆分逻辑，不另写第二套分隔符语义。
- 字段和维度不匹配返回中文业务错误。
- 导出使用同一查询条件。
- 标签组为空时返回空结果或业务提示，不能变成不筛选。
- 标签组 198 个成员再叠加 5 个普通值时，去重后超过 200 返回业务错误。

**Step 8: E2E 验收用例**

人工或 Playwright 验收路径：

1. 在系统设置创建“模块”标签组。
2. 打开评审数据管理，选择该标签组。
3. 表格刷新后只显示组内模块数据。
4. 翻页、排序、调整普通筛选条件。
5. 打开详情或下钻，确认明细仍按标签组筛选。
6. 导出 Excel，确认导出数据与页面结果一致。
7. 刷新浏览器或从历史记录重新打开，确认标签组筛选状态恢复。
8. 清空标签组筛选，确认页面恢复普通筛选结果。

**Step 9: 验证**

Run:

```powershell
( $env:JAVA_HOME='D:\projects\data_collection_platform\tools\jdk\jdk-21.0.10+7'; $env:PATH="$env:JAVA_HOME\bin;D:\projects\data_collection_platform\tools\maven\apache-maven-3.9.9\bin;$env:PATH"; cd backend; mvn -q "-Dtest=ReviewDataRequestAssemblerTest,ReviewDataRecordQueryServiceTest,LabelGroupExpansionServiceTest" test )
( cd frontend; npm.cmd run test -- src/components/label-groups/LabelGroupSelector.test.ts src/views/review-data-management.test.ts )
```

Expected: PASS。

### Phase 5: 记录型表格推广

**目标：** 将 Phase 4 的接入方式推广到系统测试议题查询、客户问题列表和非法数据页面。

**Files:**
- Modify: `frontend/src/views/SystemTestIssueSearchView.vue`
- Modify: `frontend/src/views/CustomerIssueRecordsView.vue`
- Modify: `frontend/src/views/issue-illegal-records/IssueIllegalRecordsPage.vue`
- Modify: `frontend/src/views/system-test/system-test-condition-fields.ts`
- Modify: `frontend/src/views/customer-issues/customer-issue-condition-fields.ts`
- Modify: `backend/src/main/java/com/data/collection/platform/controller/*Request*.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/SystemTestIssueSearchService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/CustomerIssueRecordService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/AbstractIssueFactRecordListService.java`
- Modify: corresponding tests under `backend/src/test/java/com/data/collection/platform/controller` and `backend/src/test/java/com/data/collection/platform/service`

**Step 1: 抽取记录表通用请求模型**

如果多个记录页都需要同一套 `labelGroupFilters`，优先抽取通用 DTO，例如：

```java
public record LabelGroupFilterRequest(String fieldKey, String dimensionKey, Long groupId) {}
```

不要在每个页面各自发明一套参数名。

**Step 2: 接入系统测试议题查询**

支持维度优先为：

- 模块
- 项目
- 测试阶段
- 严重程度
- 紧急程度
- 里程碑
- 议题处理人 `issue_assignee`，字段为 `assigneeName`

测试要覆盖严重程度和紧急程度不能混用。

**Step 3: 接入客户问题列表**

支持维度优先为：

- 模块
- 客户问题处理人 `customer_assignee`，字段为 `assigneeName`
- 紧急程度
- 里程碑
- 闭环状态

客户问题第一版不接入负责人。处理人维度使用 `customer_assignee`，对应事实字段 `assigneeName`。如果后续要支持负责人，必须作为独立维度接入，不能与处理人合并。

测试要覆盖：

- 客户问题处理人使用 `customer_assignee` 维度和 `assigneeName` 字段。
- 客户问题负责人不出现在第一版可用标签组维度中。
- 客户问题闭环状态保存值为 `需求如此`，展开匹配 `需求如此/设计如此`。

**Step 4: 接入非法数据页面**

非法数据页面只接入当前已有字段对应维度，不为了标签组新增页面不支持的筛选字段。

**Step 5: E2E 验收用例**

每接入一个记录型页面，都至少验收：

1. 选择标签组后列表结果变化正确。
2. 翻页、排序、普通筛选与标签组组合后结果一致。
3. 导出使用同一筛选条件。
4. 页面刷新或浏览历史重开后筛选状态恢复。
5. 当前页面不支持的维度标签组不会出现在选择器里。

**Step 6: 验证**

Run:

```powershell
( $env:JAVA_HOME='D:\projects\data_collection_platform\tools\jdk\jdk-21.0.10+7'; $env:PATH="$env:JAVA_HOME\bin;D:\projects\data_collection_platform\tools\maven\apache-maven-3.9.9\bin;$env:PATH"; cd backend; mvn -q "-Dtest=SystemTestIssueSearchServiceTest,SystemTestIssueSearchControllerTest,CustomerIssueRecordServiceTest,CustomerIssueControllerTest,IssueFactRecordListRequestAssemblerTest" test )
( cd frontend; npm.cmd run test -- src/views/SystemTestIssueSearchView.test.ts src/api-client/issue-records-api.test.ts src/api-client/customer-issues-api.test.ts )
```

Expected: PASS。

### Phase 6: 统计板高级筛选接入

**目标：** 让统计类页面在高级筛选中使用同维度标签组，同时保持统计范围、下钻和导出口径一致。

**Files:**
- Modify: `frontend/src/components/statistic-board-filters.ts`
- Modify: `frontend/src/components/StatisticFilterBuilder.vue`
- Modify: `frontend/src/components/StatisticBoardToolbar.vue`
- Modify: `frontend/src/components/statistic-board-route-query.ts`
- Modify: `frontend/src/api-client/statistic-boards-api.ts`
- Modify: `frontend/src/types/api/statistics.ts`
- Modify: `backend/src/main/java/com/data/collection/platform/entity/statistics/StatisticFilterCondition.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/statistics/StatisticFilterGroupSupport.java`
- Modify: `backend/src/main/java/com/data/collection/platform/service/IssueFactFilterGroupSqlSupport.java`
- Modify: `backend/src/test/java/com/data/collection/platform/service/statistics/StatisticFilterGroupSupportTest.java`
- Modify: `backend/src/test/java/com/data/collection/platform/service/IssueFactFilterGroupSqlSupportTest.java`

**Step 1: 扩展统计筛选字段定义**

给 `StatisticFilterField` 增加：

```ts
labelDimensionKey?: string;
labelGroupEnabled?: boolean;
```

后端 `StatisticFilterField` 也增加同等字段，统计板 definition 明确哪些字段支持标签组。

**Step 2: 扩展筛选条件模型**

新增操作符或条件类型，建议优先使用明确语义：

```json
{
  "fieldKey": "moduleName",
  "operator": "inLabelGroup",
  "value": "1"
}
```

不要把标签组伪装成普通字符串值，否则后端无法校验维度。

**Step 3: 前端高级筛选支持标签组操作符**

`StatisticFilterBuilder.vue` 中，当字段支持标签组时，操作符展示“属于标签组”。选择该操作符后，值控件切换为 `LabelGroupSelector`。

**Step 4: 后端统计筛选展开**

`StatisticFilterGroupSupport` 负责校验字段存在和操作符合法；`IssueFactFilterGroupSqlSupport` 负责把 `inLabelGroup` 展开为 SQL `IN` 或数组包含条件。

AND/OR 合并语义：

1. 同一 filter group 内、同一字段上的普通值和标签组值，可以折叠为一个 `IN` 条件，成员取并集。
2. 同一 filter group 内、不同字段的条件按该 group 的 `AND` / `OR` 逻辑组合。
3. 跨 group 不做合并，不把不同层级的同字段条件擅自折叠成一个 `IN`。
4. 标签组为空时不能变成“不过滤”；应返回空结果或业务错误，具体由页面交互决定。

**Step 5: 下钻和导出复用同一条件**

统计板的：

- `GET /api/statistic-boards/{boardKey}`
- `GET /api/statistic-boards/{boardKey}/details`
- `GET /api/statistic-boards/{boardKey}/export`
- `GET /api/statistic-boards/{boardKey}/rule-explanation`

都必须支持相同的标签组筛选条件。

**Step 6: E2E 验收用例**

统计板接入后至少验收：

1. 高级筛选选择“模块 属于标签组”。
2. 图表和表格按标签组刷新。
3. 下钻明细继承标签组筛选。
4. 导出 CSV 继承标签组筛选。
5. 打开规则说明时展示当前筛选口径。
6. 复制 URL 或浏览历史重开后筛选状态恢复。

**Step 7: 验证**

Run:

```powershell
( $env:JAVA_HOME='D:\projects\data_collection_platform\tools\jdk\jdk-21.0.10+7'; $env:PATH="$env:JAVA_HOME\bin;D:\projects\data_collection_platform\tools\maven\apache-maven-3.9.9\bin;$env:PATH"; cd backend; mvn -q "-Dtest=StatisticFilterGroupSupportTest,IssueFactFilterGroupSqlSupportTest,StatisticBoardControllerTest" test )
( cd frontend; npm.cmd run test -- src/components/StatisticFilterBuilder.test.ts src/components/statistic-board-route-query.test.ts src/api-client/statistic-boards-api.test.ts )
```

Expected: PASS。

### Phase 7: 动态标签组规则模板与只读预览（不参与页面筛选）

**目标：** 在静态标签组稳定后，再实现动态标签组的规则模板和只读预览。此阶段不参与页面筛选，不在业务页面标签组选择器中出现动态组。

**Files:**
- Create: `docs/plans/2026-06-10-dynamic-label-group-rule-templates.md`
- Modify: `backend/src/main/resources/db/migration/V20260610_02__label_group_dynamic_rule_preview.sql`
- Create: `backend/src/main/java/com/data/collection/platform/service/labelgroup/DynamicLabelGroupRuleService.java`
- Create: `backend/src/main/java/com/data/collection/platform/service/labelgroup/DynamicLabelGroupPreviewService.java`
- Create: `backend/src/test/java/com/data/collection/platform/service/labelgroup/DynamicLabelGroupRuleServiceTest.java`
- Create: `backend/src/test/java/com/data/collection/platform/service/labelgroup/DynamicLabelGroupPreviewServiceTest.java`
- Modify: `frontend/src/views/LabelGroupSettingsView.vue`

**Step 1: 收集动态规则模板**

只允许产品确认后的模板，例如：

- 模块：当前版本有系统测试缺陷的模块。
- 客户问题处理人：当前仍有未闭环客户问题的处理人。
- 分支：最近 30 天有合并请求的目标分支。

模板必须输出同一维度的标签值列表。

**Step 2: 设计规则存储**

动态规则独立建表，不在 `label_groups` 上新增大量可空字段，避免污染静态标签组行。

Schema 草案：

```sql
create table label_group_dynamic_rules (
  id bigserial primary key,
  group_id bigint not null references label_groups(id) on delete cascade,
  rule_template_key varchar(100) not null,
  rule_params_json text not null,
  last_preview_status varchar(32),
  last_preview_member_count int,
  last_preview_error varchar(500),
  last_preview_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint uk_label_group_dynamic_rules_group unique (group_id)
);
```

只存模板 key 和模板参数，不存用户手写 DSL。`label_groups.group_type = 'DYNAMIC'` 时才允许存在对应规则行。

**Step 3: 实现只读预览**

动态标签组先展示：

- 最近计算时间。
- 计算状态。
- 成员数量。
- 成员预览。
- 失败原因。

**Step 4: 暂不接入筛选**

只读预览通过产品确认后，再决定是否允许动态标签组参与页面筛选。

**Step 5: 验证**

Run:

```powershell
( $env:JAVA_HOME='D:\projects\data_collection_platform\tools\jdk\jdk-21.0.10+7'; $env:PATH="$env:JAVA_HOME\bin;D:\projects\data_collection_platform\tools\maven\apache-maven-3.9.9\bin;$env:PATH"; cd backend; mvn -q "-Dtest=DynamicLabelGroupRuleServiceTest,DynamicLabelGroupPreviewServiceTest" test )
```

Expected: PASS。

### Phase 8: 权限、审计、性能和回归

**目标：** 在功能可用后补齐生产可维护性，确保不会拖慢页面和系统设置。

**Files:**
- Modify: `backend/src/main/java/com/data/collection/platform/service/OperationAuditService.java`
- Modify: `backend/src/main/java/com/data/collection/platform/controller/LabelGroupController.java`
- Modify: `frontend/src/views/LabelGroupSettingsView.vue`
- Modify: `docs/platform-page-business-rules.md`

**Step 1: 权限**

先扫一遍 `backend/src/main/java/com/data/collection/platform/security/` 和现有 `*Controller.java` 的权限写法。当前项目使用自定义 `@RequireRole(AuthRole.ADMIN)`，不是 Spring `@PreAuthorize`。除非安全模块先做统一改造，否则标签组管理接口也使用 `@RequireRole(AuthRole.ADMIN)`。

建议第一版：

- 普通用户可查看和应用标签组。
- 管理员可创建、编辑、删除标签组。
- 删除标签组需要二次确认。

**Step 2: 审计**

创建、编辑、删除标签组写入操作审计，至少记录：

- 操作人。
- 标签组 ID。
- 标签组名称。
- 维度。
- 变更前后成员数量。

应用标签组筛选不写操作审计，避免高频查询拖慢业务页面；如后续需要分析使用频率，可单独做轻量埋点或聚合统计，不进入本周 MVP。

**Step 3: 性能**

候选值查询必须分页和关键词搜索。列表页不要一次加载所有维度所有值。

标签组展开结果可以在单次请求内缓存，避免一个页面多个查询重复查同一组成员。

单组成员上限 200 必须在后端强制校验，前端同步提示。统计板接入前必须压测典型 `IN` 条件，确认不会明显拖慢页面加载。

**Step 4: 回归验证**

Run:

```powershell
python scripts/check_schema_flyway_drift.py
python scripts/check_flyway_destructive_migrations.py
python scripts/check_flyway_migration_immutability.py
python scripts/check_label_group_dimension_matrix_test.py
python scripts/check_label_group_dimension_matrix.py
python scripts/check_label_group_business_page_guard_test.py
python scripts/check_label_group_business_page_guard.py
( $env:JAVA_HOME='D:\projects\data_collection_platform\tools\jdk\jdk-21.0.10+7'; $env:PATH="$env:JAVA_HOME\bin;D:\projects\data_collection_platform\tools\maven\apache-maven-3.9.9\bin;$env:PATH"; cd backend; mvn -q test )
( cd frontend; npm.cmd run lint )
( cd frontend; npm.cmd run typecheck )
( cd frontend; npm.cmd run test )
```

Expected: PASS。

## 11. 端到端验收路径

1. 用户能在设置页创建“模块”维度标签组，例如“核心业务系统 = CRM、OA、ERP”。
2. 用户能在支持模块筛选的页面选择“模块 = 核心业务系统（标签组）”。
3. 查询结果与手动选择 CRM、OA、ERP 三个模块的结果一致。
4. 用户能创建“评审负责人”维度标签组，例如“领导 = 张三、李四、王五”。
5. 支持评审负责人筛选的页面能应用该标签组，不支持该维度的页面不展示该标签组。
6. 创建标签组时不能手动输入不存在的标签值。
7. 指标字段、统计表头、页面说明字段不能出现在标签维度或标签值候选中。
8. 模块、项目、阶段、严重程度、紧急程度等候选值遵守老平台解析规则。
9. 应用标签组后，原页面排序、导出、下钻和二次筛选仍使用同一套查询口径。
10. 不出现已废弃方案中的旧页面、旧 API 或旧运行时命名。
11. 标签组成员变化后，历史数据重新查询使用标签组当前成员，并在规则说明中明确提示。
12. 业务页面只出现应用标签组入口，不出现创建、编辑、删除标签组入口。
