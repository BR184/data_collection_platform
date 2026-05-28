# 集成测试老平台 Excel 导出对齐方案

## 背景

老平台的集成测试页面提供两类 Excel 导出：

1. 单阶段集成测试数据导出：按功能聚合，并在右侧展示模块聚合结果。
2. 两阶段横向对比导出：按模块和功能对齐，比较两个集成测试阶段的关键统计字段。

新平台当前已经有 `integration_test_fact` 事实表、模块汇总、模块明细和 CSV 明细导出。用户确认集成测试页面不需要暴露“重建事实”功能，本次只对齐老平台导出能力。

## 实现范围

- 移除集成测试分析页用户态“重建事实”按钮。
- 新增“导出集成测试数据”按钮，导出当前项目与测试阶段的 Excel。
- 新增“导出横向对比”按钮，弹窗选择两个不同测试阶段后导出 Excel。
- 后端新增两个 Excel 导出接口，数据仍来自 `integration_test_fact`。
- 保留现有模块明细和 CSV 明细导出，不改变事实构建链路。

## 数据口径

### 单阶段导出

功能级聚合键：

- `module_name`
- `function_name`

模块级聚合键：

- `module_name`

统计字段：

- `execute_case`：求和
- `pass_case`：求和
- `not_pass_case`：求和
- `not_pass_case_now`：求和
- `problem_case`：求和
- `exception_count`：求和

通过率：

- `pass_case / execute_case * 100`
- 当 `execute_case = 0` 时按 `0.00%`

功能标签：

- 使用 `function_labels`
- 同一功能聚合多条记录时去重后拼接

### 横向对比导出

对比键：

- `module_name`
- `function_name`

差值：

- 后阶段 - 前阶段

如果某个功能只存在于后阶段：

- 前阶段字段留空
- 差值留空
- 后阶段字段正常展示

如果某个功能只存在于前阶段：

- 本版暂不展示，保持老平台以目标阶段功能为主的对比习惯。

## 接口设计

### 单阶段导出

`GET /api/integration-tests/module-function/export`

参数：

- `projectId`
- `testingPhase`
- `sourceInstance`，可选

返回：

- `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- 文件名：`${testingPhase}集成测试数据.xlsx`

### 横向对比导出

`GET /api/integration-tests/comparison/export`

参数：

- `projectId`
- `basePhase`
- `targetPhase`
- `sourceInstance`，可选

返回：

- `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
- 文件名：`${basePhase}-${targetPhase}集成测试横向对比.xlsx`

## 前端交互

- 页面工具区保留刷新按钮。
- 移除“重建事实”按钮。
- 新增“导出集成测试数据”按钮。
- 新增“导出横向对比”按钮。
- 横向对比弹窗：
  - 前阶段选择框
  - 后阶段选择框
  - 两个阶段不能为空且不能相同

## 任务拆分

1. 后端增加聚合 DTO 和查询方法。
2. 后端增加 Excel 生成服务。
3. 后端增加两个导出接口和控制器测试。
4. 前端移除“重建事实”入口。
5. 前端增加导出按钮、横向对比弹窗和 API client 方法。
6. 补充定向测试并运行后端控制器测试、前端类型检查和相关前端测试。

## 验收标准

- 单阶段导出字段与老平台截图保持一致。
- 横向对比导出字段与老平台截图保持一致。
- 横向对比差值按“后阶段 - 前阶段”计算。
- 集成测试分析页不再出现“重建事实”按钮。
- 现有模块汇总、模块明细和明细导出不回退。
