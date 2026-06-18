# 全平台页面二次对齐台账

> 范围：新平台所有可见页面。  
> 目标：逐页复核老平台已有功能、入口、字段、字段值、统计口径、导出和下钻是否在新平台等价存在。  
> 标准：同一批数据、同一筛选条件下，新老平台展示的数据集合、总数、字段含义、字段值和导出结果应一致。

## 核验规则

1. 老平台已有的按钮、筛选、级联、导出、下钻、排序、刷新状态和规则说明，必须在新平台等价可达。
2. 页面字段不仅要字段名一致，字段值展示也要一致；内部枚举值不得直接暴露给用户。
3. 新平台增强能力不能替代老平台入口；增强能力需要单独记录保留依据。
4. 接口返回 200 不等于用户侧可用，必须确认用户能完成完整操作窗口。
5. 每个页面完成后需要更新本台账和对应页面 gap audit；复查发现的漏项同步登记到 `docs/audits/missed-alignment-registry.md`。

## 页面清单

| 模块 | 页面 | 新平台页面键 | 老平台/规则依据 | 当前状态 | 下一步 |
|---|---|---|---|---|---|
| 质量看板 | 研发质量看板 | `quality-board-rd-quality-board` | `docs/audits/quality-board-gap-audit.md` | 已标记差异，需求未明确暂跳过 | 看板模块未拿到新需求，本轮不实现 |
| 质量看板 | 其他看板 | `quality-board-other-board` | `docs/audits/quality-board-gap-audit.md` | 已标记差异，需求未明确暂跳过 | 看板模块未拿到新需求，本轮不实现 |
| 评审数据 | 评审数据管理 | `review-data-home` | `docs/audits/review-data-management-gap-audit.md` | 已按老平台完成差异对齐，待验证 | 跑冒烟和真实链路，回填验证结果 |
| 代码走查 | 代码走查非法数据 | `code-review-illegal-records` | `docs/audits/code-review-illegal-records-gap-audit.md` | 已有审计，待抽样复查 | 核验字段值和导出是否仍一致 |
| 代码走查 | 代码走查多元看板 | `code-review-multi-board` | 待确认老平台是否存在等价入口 | 待二次复核 | 若为增强，记录保留依据 |
| 系统测试 | 系统测试缺陷汇总 | `question-metrics-home` | 规则总表 §4.3；`ModuleTable.vue` | 已补齐代码，后端编译复验已通过 | 继续核验数据一致性、下钻、规则说明和导出 |
| 系统测试 | 议题多元看板 | `question-metrics-multi-board` | 待补老平台证据 | 待二次复核 | 核对多元看板是否为老平台能力 |
| 系统测试 | 申请延期缺陷分析 | `question-metrics-delay-analysis` | 规则总表 §4.4；页面 audit | 待二次复核 | 复核默认阶段、导出和规则说明 |
| 系统测试 | 系统测试非法数据 | `question-metrics-illegal-records` | 规则总表 §4.5；页面 audit | 已有审计，待抽样复查 | 核验非法原因和字段值展示 |
| 系统测试 | 缺陷原因分析 | `question-metrics-defect-cause` | 规则总表 §4.6；页面 audit | 已有审计，待抽样复查 | 核验阶段筛选、原因列和导出 |
| 系统测试 | 议题阶段统计 | `question-metrics-phase-statistics` | 规则总表 §4.7；页面 audit | 已有审计，待抽样复查 | 核验轮次默认和下钻 |
| 系统测试 | 议题查询 | `question-metrics-issue-search` | 旧平台 `IssueSearch.vue`；页面 audit | 已有审计，待抽样复查 | 核验高级筛选复用和导出缺口 |
| 客户问题 | 缺陷汇总 | `customer-issues-home` | 规则总表 §5.2；页面 audit | 已有审计，待抽样复查 | 核验里程碑切换和字段值 |
| 客户问题 | 缺陷非法数据 | `customer-issues-illegal-records` | 规则总表 §5.4；页面 audit | 待二次复核 | 复核规则说明和事实层非法判定 |
| 客户问题 | 缺陷原因分析 | `customer-issues-defect-cause` | 页面 audit | 已有审计，待抽样复查 | 核验里程碑默认和原因列 |
| 客户问题 | CC_PRODUCT议题 | `customer-issues-cc-product-issues` | 页面 audit | 已有审计，待抽样复查 | 核验字段、筛选、导出 |
| 客户问题 | 延期问题 | `customer-issues-delay-issues` | 规则总表 §5.3；页面 audit | 已有审计，待抽样复查 | 核验延期状态和排序 |
| 客户问题 | 缺陷响应效率 | `customer-issues-response-efficiency` | 规则总表 §5.6；页面 audit | 待二次复核 | 核验默认里程碑、总计行和字段集合 |
| 客户问题 | 按功能展示缺陷数量 | `customer-issues-issue-by-function` | 规则总表 §5.5；页面 audit | 待二次复核 | 核验是否恢复老平台模块-功能透视布局 |
| 系统设置 | 标签组管理 | `label-group-settings` | 标签组设计文档 | 新平台能力 | 按设计文档验收，不按老平台业务页对齐 |
| 系统设置 | 数据镜像设置 | `mirror-settings` | 新平台同步治理能力 | 新平台能力 | 单独验收同步和状态可用性 |
| 系统设置 | 数据库查看 | `database-browser` | 新平台排查工具 | 新平台能力 | 单独验收查询权限和只读边界 |

## 本轮处理记录

### 2026-06-18

- 启动全页面二次对齐台账。
- 优先处理已确认漏项：系统测试 / 系统测试缺陷汇总缺少老平台项目/测试阶段级联切换入口。
- 已实现：统计板页级数据范围接入、系统测试缺陷汇总项目/测试阶段级联入口、阶段选择合并进同一套 `filterGroup`，覆盖主表、下钻、规则说明和导出请求。
- 验证：前端类型检查通过；后端编译首次失败于新增方法名与父类权限冲突，已修正，按仓库测试规则待下一轮单独复验。
- 质量看板模块已完成二轮差异标记，见 `docs/audits/quality-board-gap-audit.md`。因当前未拿到新的看板模块需求，质量看板本轮跳过实现，不作为后续模块推进阻塞项。
- 评审数据 / 评审数据管理完成二轮差异实现，见 `docs/audits/review-data-management-gap-audit.md`。已按老平台收紧直接筛选语义、收敛默认列、对齐导出列，并恢复新增后继续录入评审问题的操作闭环。
