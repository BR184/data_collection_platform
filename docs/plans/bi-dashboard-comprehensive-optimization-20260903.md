# BI 看板全面优化与改造实施计划

## 进度与中间物

- **状态**：全部三个阶段已 100% 落地完成并通过全套件测试与容器热部署。
- **已完成变更清单**：
  1. `BiDashboardView.vue` & `ReviewStageContent.vue`：页面单行化精简，去除多层大字号堆叠标题，保留规范规则提示栏；
  2. `CodingStageContent.vue`：排布重构（质量置顶、频次置底、“按日/按周”下放至代码增量趋势卡片内部），图表更名为“代码注释率与走查密度趋势”，补齐所有物理单位；
  3. `DeveloperWorkloadChart.ts`：改造为单根已修复/待修复垂直堆叠柱（`stack: 'workload'`），柱顶 custom 标注总数，图例精简；
  4. `BiChartSortControl.vue` & `sorting.ts`：实现通用图表排序控制器组件与纯函数排序集，全阶段 17+ 图表全面接入；
  5. `export-chart.ts` & `BiChartPanel.vue`：实现全图表通用 Excel (.xlsx) 表格导出与 PNG 高清图片保存下拉菜单，规范输出包含元数据、中文表头与数据的标准电子表格；
  6. 前端类型检查（`npm run typecheck`）与全量单元测试（`npm run test`）全部通过，并成功完成生产构建热部署至 30201 容器。

---

## 恢复线索

- **当前阶段**：工作单元完成，验证通过，就绪交付。
- **验证命令**：`npm.cmd run test -- bi-dashboard` 与 `npm.cmd run typecheck`
- **关联方案**：`C:\Users\admin\Desktop\BI看板全面优化与改造方案.md`

---

## 目标与边界

### 目标
1. **页面结构与标题单行化精简**：全 6 个阶段页面统一采用单行标题，去除冗余的多层大字号堆叠；保留已有规则说明（需求/设计：缺陷密度目标；系统测试：P1/P2/一级缺陷目标），没有的不额外添加。
2. **全图表自定义排序与筛选组件落地**：在所有具备列表/排行/明细特性的 17+ 图表右上角，统一提供标准化 `[ 排序指标 ] + [ 升/降序 ]` 组件，严格基于图体现有真实数据字段排序。
3. **图表名称真实度量对齐**：将 `代码质量趋势` 更名为 `代码注释率与走查密度趋势`；其余图表保持原直观名称不变。
4. **全图表数据表格导出（Excel / .xlsx）**：在保留 PNG 图片下载基础上，新增导出 Excel 数据表格功能，按图表真实数据模型输出规范中文字段表头与数值。
5. **编码阶段排布重构与控件归位**：质量走查指标置顶（各模块走查质量第一位，提交频次置底）；“按日/按周”颗粒度切换控件下放至“代码增量趋势”卡片内部。
6. **物理单位全面补齐**：消除裸数字，在标题、坐标轴和 Tooltip 中明确标注 `行`、`KLOC`、`个/KLOC`、`KLOC/小时`、`%`、`个` 等单位。
7. **按指派人统计缺陷数单柱堆叠改造**：由两根并排柱改为单根已修复/待修复垂直堆叠柱，柱顶标注总数，图例精简。
8. **需求/设计评审图例解耦**：采用 Dual-Panel 左右分轨子面板，达标区间移入副标题，图例清爽不拥挤。

### 明确禁止（红线）
- 严禁为了兼容保留废弃入口、别名函数、双轨逻辑或包裹层；
- 严禁臆造图表中不存在的排序字段或数据列；
- 严禁破坏现有 BI 数据计算口径（严格遵循 `BI看板数据来源与计算口径核对表.md`）；
- 严禁修改无关联的非 BI 页面或后端生产接口。

---

## 方案与步骤

### 阶段一：页面结构精简、编码排布重构与单柱/双面板图表改造
1. **页面单行化精简**：
   - 优化 `ReviewStageContent.vue`、`CodingStageContent.vue`、`TestQualityStageContent.vue`、`SystemTestStageContent.vue` 及 `BiDashboardView.vue` 的页头结构，彻底消除多层嵌套堆叠标题；
   - 规范内嵌规则说明行展示。
2. **编码阶段排布重构与局部控件归位**：
   - 重构 `CodingStageContent.vue` 图表排布：走查质量置顶，走查问题+静态扫描第二位，个人贡献+模块增量第三位，注释率趋势第四位，增量趋势+提交趋势第五位，提交频次置底；
   - 将“按日/按周”切换控件下放至“代码增量趋势”卡片内部。
3. **图表视觉专项改造**：
   - 重构 `DeveloperWorkloadChart.ts`：改造为单根垂直堆叠柱（已修复绿色 + 待修复橙红色），柱顶总数标识；
   - 优化 `ReviewQualityDualPanelChart.ts` 与 `ReviewStageContent.vue`：精简图例，达标区间移入副标题。
4. **指标物理单位补齐**：
   - 在各图表类与格式化函数中补齐 `(行)`、`(KLOC)`、`(个/KLOC)`、`(KLOC/小时)`、`%` 等单位。

### 阶段二：通用图表排序组件与全图表接入、图表更名
1. **更名落地**：
   - `CodingStageContent.vue` 中“代码质量趋势”更名为“代码注释率与走查密度趋势”。
2. **通用排序组件抽象与落地**：
   - 设计并实现 `BiChartSortControl.vue`（排序指标下拉 + 升降序切换按钮），支持无缝嵌入 `BiChartPanel.vue` 的 actions 插槽；
   - 为 17 个具备列表/排行特性的图表配置标准排序字段清单，在 presentation 转换层提供统一的纯函数排序支持。

### 阶段三：全图表 Excel 数据表格导出与端到端回归
1. **通用 Excel 导出引擎落地**：
   - 在 `frontend/src/features/bi-dashboard/charts/export-chart.ts` 中实现通用 `exportBiChartExcel` 纯前端导出方法，根据图表已有的结构化数据模型生成标准的 Excel 表格；
   - 在 `BiChartPanel.vue` 右上角提供统一的导出菜单（`导出 Excel 数据表 (.xlsx)` + `保存高清图片 (.png)`）。
2. **端到端测试与全套件验证**：
   - 更新并补充 BI 前端全部单元测试；
   - 运行 `npm run test` 与 `npm run typecheck`；
   - 执行 `npm run build` 并热同步到 30201 容器，人工验收界面交互与导出功能。

---

## 接口与数据契约

- **排序数据模型**：
  ```typescript
  export interface BiSortOption {
    label: string;
    value: string;
  }
  export type BiSortOrder = 'asc' | 'desc';
  ```
- **Excel 导出结构**：
  ```typescript
  export interface BiExcelExportColumn {
    header: string;
    key: string;
    formatter?: (value: any, row: any) => string | number;
  }
  ```

---

## 风险与假设

- **数据完整性假设**：前端展示数据均来自后端现有的 `BiPageResponse` 聚合模型，排序与 Excel 导出均基于当前聚合数据集，不触发额外 HTTP 请求，性能极高且无服务端副作用。
- **回归安全**：前端所有改动完全收敛在 `frontend/src/features/bi-dashboard/` 目录下，不改动其他通用页面组件，确保零副作用。