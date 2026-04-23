# 议题测试阶段定义落地进展

## 背景

本轮按“先打事实层地基、看板后置”的整体计划推进。

当前明确边界：

- 暂不实现任何看板模块。
- `系统测试非法数据` 已按计划废弃，当前仅保留为测试对象，不再作为正式功能继续铺页面。
- 优先补齐会影响事实层归档和统计范围的基础配置能力。

## 本轮落地

已将 `系统设置 -> 议题测试阶段定义` 从占位页改为真实页面。

后端新增：

- `GET /api/testing-phases`
- `GET /api/testing-phases/project-options`
- `POST /api/testing-phases`
- `PUT /api/testing-phases/{id}`
- `PATCH /api/testing-phases/{id}/enabled`
- `DELETE /api/testing-phases/{id}`

前端新增：

- 阶段定义列表
- 项目、关键字、启用状态筛选
- 新增、编辑、启停、删除
- 关联议题数量展示

## 数据链路

当前继续复用已有底层表：

- `testing_phase_calendar`

没有新增统计结果表，也没有新增看板专用表。

事实层 `FactBuildService` 已经读取 `testing_phase_calendar` 来判定系统测试阶段，因此该页面维护的数据后续会自然进入真实链路。

## 验证

已通过：

- 后端 `mvn -q -DskipTests compile`
- 前端 `npm run build`

前端构建仍有既有大 chunk 体积提示，本轮未处理。

## 下一步建议

继续按基础能力优先推进：

1. 复核系统测试、客户问题统计页对 `testing_phase_calendar` 的项目/阶段来源是否全部统一。
2. 补齐模块管理等系统设置基础页，减少业务页面硬编码。
3. 再进入客户问题和系统测试剩余非看板页面的回归修复。
4. 所有看板类页面最后统一设计和实现。
