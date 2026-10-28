# Git分支中文名称对照

2026-10-28按用户要求完成改名：功能名称用中文直接说明，去掉codex、feature等前缀，保留已有日期时间；main保持。长期命名规则由AGENTS.md维护，此表用于查询历史指令、清单中的旧名称。

| 旧名称 | 当前名称 |
| --- | --- |
| `backup/before-sync-fix-20260702-184402` | `同步修复前备份-20260702-184402` |
| `codex/delay-intranet-test-package-20261026` | `延期标签写回测试交付审批-20261026` |
| `codex/delay-kit-e2e-local-20261027` | `延期标签写回完整流程验收-20261027` |
| `codex/delay-legacy-compare-20261028` | `新旧平台延期写回对比与旧平台停写-20261028` |
| `codex/delay-writeback-b1-b2-20261023` | `延期标签模板解析与在途停写修复-20261023` |
| `codex/delay-writeback-d52-test-20261026` | `延期标签写回稳定基线测试-20261026` |
| `codex/delay-writeback-takeover-20261022` | `延期标签写回接替修复-20261022` |
| `codex/fix-label-links-reconcile-20001` | `生产20001标签关联与删除对账修复` |
| `codex/form-admin-ui-20261027` | `表单管理界面开发-20261027` |
| `codex/form-version-design-20261026` | `表单版本方案设计-20261026` |
| `codex/gitlab-sample-transfer-20261026` | `GitLab样本导出导入工具-20261026` |
| `codex/repackage-20001-latest` | `生产20001更新包重制` |
| `codex/sync-bootstrap-cancellation-repair-20261019` | `同步启动与取消恢复修复-20261019` |
| `codex/unified-form-w4-isolation-20261026` | `表单多版本与填写功能开发-20261026` |
| `complete-2026-05-18-sync-module-plan` | `同步模块方案完成-2026-05-18` |
| `feature/filter-tag-groups` | `筛选标签分组` |
| `fix/gitlab-sync-runtime-gap` | `GitLab同步运行缺口修复` |
| `qaflex-update-20261021T011723Z-d52affe66b4f` | `增量发布包留档-20261021T011723Z-d52affe66b4f` |
| `release/20261019-90bbb` | `同步机制恢复与发布验证-20261019-90bbb` |
| `rewritten-blueprint-local` | `平台重构回归审查留档` |
| `sync-orchestrator-hard-cutover` | `同步调度整体切换留档` |
| `task9-release-verification-scripts` | `发布验收脚本` |

## 改名范围与验证

- 本地22个分支改名；origin原有11个非main分支、gitlab25原有10个非main分支同步改名；仅本地分支保持本地，不将未交付成果推到远端。
- 改名前后每个远端各自分支提交一致，本地改名前后各分支提交一致；工作树目录及文件内容不因改名变更。命名规则与此对照表的提交在经理分支正常追加。
- “同步机制恢复与发布验证-20261019-90bbb”原先本地d5383a17、双远端d3aa90f0，既有差异保持，本次不合并或改写历史。
- 发布包文件及包清单不改写；历史包/指令中的旧分支名称按本表追溯，留档分支保留原发布日期时间和版本标识。改名不校正既有包分支与包清单的提交对应关系。
- 操作快照与核验记录在经理任务树.tmp/branch-rename-20261028/，完成时本地23分支（含main）、origin12分支、gitlab25 11分支。主树已有混合改动保留，仅定点更新AGENTS命名规则，未代其提交。
