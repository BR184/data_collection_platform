# 进度与中间物

- 状态：已完成配置切换、全量镜像、事实刷新和页面验收；未修改仓库生产代码。
- 已确认：平台配置连接 `gitlabhq_production`，GitLab Rails 配置实际使用 `gitlabhq_full_import_test`。
- 已确认：`gitlabhq_production` 只有 5389 个 Issue、3392 条 `label_links`，且缺少项目 9/325；`gitlabhq_full_import_test` 有 8005 个 Issue、66165 条 `label_links`，包含项目 9/325 和系统测试关系。

## 恢复线索

- 当前阶段：运行配置修复。
- 恢复后首条命令：核对 `gitlab_sync_configs.db_name` 与 `gitlabhq_full_import_test` 的源表计数。
- 关联工作树：`c58ff5ac` 及其未提交解耦改动；不得回滚或覆盖。

## 目标与边界

- 目标：让 `18181` 使用与本地 GitLab Rails 相同的业务数据库，恢复系统测试数据链路。
- 成功标准：全量同步成功；事实刷新成功；项目 9 的 `issue_fact.testing_phase` 非空且系统测试接口返回记录。
- 禁止：修改解耦代码、直接补写 `issue_fact`、恢复已删除源数据、重置工作树或重启另一 AI 正在使用的服务。

## 约束与背景

- 当前平台源模式为 DOCKER，容器为 `gitlab-data-web-1`。
- GitLab Rails 配置文件 `D:/gitlab-data/config/gitlab.rb` 显式设置 `gitlab_rails['db_database'] = "gitlabhq_full_import_test"`。
- 本轮只调整本地平台数据库中的已持久化数据源配置。

## 证据与根因

- `gitlabhq_production` 查询结果：项目 9/325 不存在，项目 9 仍有 3608 个孤立 Issue，系统测试标签关联为 0；平台在 2026-08-24 全量运行中按该源集合将旧镜像关系标记删除。
- `gitlabhq_full_import_test` 查询结果：260 个项目、8005 个 Issue、66165 条标签关系，项目 9/325 存在，系统测试/回归测试关联 5452 条。
- 解耦前后 DOCKER 查询 SQL 与执行路径语义一致，未发现代码导致源数据缩水的证据。
- 修复后运行 `2269`：`FULL_SYNC/SUCCESS`，`665/665` 个表任务完成，扫描 `700527` 行，应用 `359543` 行。
- 修复后运行 `2271`：`FACT_REFRESH/SUCCESS`，`6/6` 个事实任务完成，应用 `18005` 行；项目 9 的 `issue_fact` 测试阶段从 `0/3608` 恢复为 `4983/5774`。
- 页面验收：系统测试非法数据切换到 `CC2026R3` 后返回 `1816` 条；`CC2026R4` 当前无数据是本地测试库没有 R4 阶段样本的真实数据状态。

## 方案与步骤

1. [完成] 更新 `gitlab_sync_configs.id=1` 的 `db_name` 为 `gitlabhq_full_import_test`。
2. [完成] 不启动或停止现有 18080/18181 进程，通过平台已有入口提交全量同步。
3. [完成] 等待事实刷新完成，核对 ODS、`issue_fact`、系统测试接口和页面数据。
4. [完成] 未对事实表做手工回填；保留了错误数据库导致的历史运行证据。

## 决策记录

- 选择切换平台连接库，因为 GitLab Rails 已将 `gitlabhq_full_import_test` 作为实际业务库；平台应连接该库而不是不完整的旧库。
- 否决页面层兜底、事实表补阶段和恢复 tombstone：这些方案会掩盖来源配置错误或制造双重数据事实。

## 接口契约

- 无新增代码/API/表结构；仅修改本地 `gitlab_sync_configs.db_name` 运行配置。

## 风险与假设

- 假设 `gitlabhq_full_import_test` 是当前本地 GitLab Rails 运行库，且其 schema 满足平台源表目录；切换前后通过全量 schema 检查和事实构建验证。
- 自动同步可能在手工全量期间排队；以数据库运行记录为准，不重复并发提交同源 writer。
