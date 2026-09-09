# 18181→20001 评审数据增量迁移（2026-09-09）

## 进度与中间物

- 状态：**两个脚本已完成并通过本地全链路演练，待用户在 115 服务器执行**。演练栈 = `qaflex-upgrade-sim-20001`（20260803 基线真实形态），演练覆盖：export 全流程 → dry-run 五分支决策（INSERT/REPLACE[k1 链接匹配]/KEEP/SKIP_DELETED/SKIP_LEGACY 逐项命中预期）→ `--apply --include-legacy-managed` 单事务合并 → 合并后逐项核对（整树替换删旧/插入新 id 分配/authority 重写 PLATFORM_OWNED/链接按 legacy_id 重解析/staging 清理）全部符合 → 演练数据已清理还原。
- 演练发现并已修复（脚本缺陷，真机同样会踩）：①`psql_exec` 的 `sh -c` 未透传 `-At` 参数（补 `"$@"` 透传）；②空表（0 行）导出为空 CSV 属合法状态，不再误判失败，行数以 manifest 为准；③Git Bash 环境的 MSYS 路径转换（`MSYS_NO_PATHCONV=1` 豁免，Linux 无影响）与 `flock` 缺失（降级跳过）；④--apply 的备份/校验先于一切写入，演练中两次失败均零残留、直接重跑成功，验证了失败安全语义。
- 恢复线索：脚本 `deploy/migrate-review-data-export.sh`（18181 侧）与 `deploy/migrate-review-data-merge.sh`（20001 侧）；执行顺序与指令见「执行 runbook」。
- 调查已完成（代码与迁移史实证，见「证据与根因」）；零应用代码改动，纯数据迁移 runbook。

## 执行 runbook（115 服务器，两实例同机，导出目录无需预建）

1. 导出（18181 只读）：`bash migrate-review-data-export.sh <18181部署目录> <导出目录>`。
2. 预演（默认 dry-run，不落库）：`bash migrate-review-data-merge.sh <20001部署目录> <导出目录>`——输出逐条决策报告，存档 `dry-run-report-<时间戳>.txt`。
3. 人工复核：逐条看 INSERT/REPLACE/SKIP_CONFLICT 与链接重解析 `resolved id`。
4. 执行：`bash migrate-review-data-merge.sh <20001部署目录> <导出目录> --apply`（如需一并迁移 LEGACY_MANAGED 记录追加 `--include-legacy-managed`）——自动先全库 pg_dump 备份到导出目录并校验，再单事务合并；失败整体回滚，处置后直接重跑。
5. apply 后抽查 20001 页面（列表/详情/搜索）；报告、日志、pre-merge 备份均留在导出目录。
- 注意：传错部署目录 = 操作错实例；脚本日志打印实际操作的容器 ID 与两侧 Flyway 版本（18181≈20260724.x、20001=20260803.01），核对后再继续。

## 目标与边界

- 用户需求：有人误在 18181 实例新建评审数据，需增量迁移到 20001 生产实例——20001 没有的直接迁入；两边都有的按更新时间新者为准。
- 成功标准：合并前有 dry-run 逐条报告供人工复核；合并单事务可整体回滚；合并前行数守恒校验与全库 pg_dump 备份；20001 既有较新数据零覆盖损失。
- 禁止：不迁移兼容模式快照表（`review_data_match_mode_reports/descriptions/problem_details/contents` 等——两实例从同一老平台同步，20001 自有同源数据）；不动 18181 数据库（只读导出）；不碰 PostgreSQL 容器/volume；不推送远端。

## 约束与背景

- 18181 = 20260724 链末端实例（Flyway ≈20260724.x）；20001 = 20260803 全新包（Flyway 20260803.01），正式生产（DNS）。
- 两实例同 LDAP、同一老平台数据源；`created_by` 为 varchar(128) 用户名字符串（非外键），可直接复制，无用户 id 映射问题。
- 20001 未升级 20260908 更新包（现场因容器名冲突失败待回退/重建包），迁移与其无关、先行执行。

## 证据与根因（迁移史逐文件实证）

- 评审数据持久化全集 = 8 张表：主表 `review_records`；四张子表 `review_record_experts`（唯一键 record+expert_name）、`review_problem_items`、`review_record_descriptions`、`review_record_contents`（均 `on delete cascade` 指向主表）；两张正式↔老平台链接表 `review_data_match_mode_edit_links`、`review_data_match_mode_problem_edit_links`（无外键、有唯一约束）。
- 版本差异：20260724→20260803 区间唯一触及评审表的迁移 V20260730_07 不改数据表列 → **六张数据表（主表+四子表+problem_edit_links）列集两版本完全一致**；唯一结构差异在 edit_links：18181 多 `last_handover_at` 列且 authority 允许 LEGACY_MANAGED，20001 已删列并只允许 PLATFORM_OWNED（演练以 information_schema 实证）。
- 链接表关键事实（V20260702_13 实证）：`match_mode_report_legacy_id`/`match_mode_problem_legacy_id` 均为 varchar(128) **NOT NULL** → 按 legacy_id 重解析恒可行；compat 表 `review_data_match_mode_reports`/`review_data_match_mode_problem_details` 均有 `unique(legacy_id)` → 重解析确定性（至多一行）；edit_links 有 `unique(review_record_id)`（替换前必须先删目标旧链接），problem_edit_links 对 review_record_id 仅普通索引（一条记录可挂多条问题链接）。
- 平台自身"同一条记录"判定（Excel 导入 SKIP 重复键，`ReviewDataRecordReadRepository.existsDuplicateRecord`）：`deleted=false AND project_name/title/review_type/review_date/review_version` 精确相等——迁移复用为业务匹配键。
- 平台替换子表用硬删除（`ReviewDataExpertRepository.replaceExperts` = `delete from review_record_experts where review_record_id = ?`）→ 迁移的整树替换复制相同语义：删除目标全部子行、仅复制源 `deleted=false` 子行。
- 搜索影子列（search_text 等 8 列）计算类 `ReviewDataSearchSupport`/`ReviewDataSearchIndexSupport` 在 2026-07-01→09-01 零提交 → 两版本算法一致，**直接复制原值，无需回填**。
- `review_records.updated_at` 不随子行编辑变化 → 时间比较必须用 effective_updated_at = greatest(主行, 四子表各自 max(updated_at))。
- `page_record_snapshots` 为查询缓存（jsonb response_payload），迁移后旧行集可能短时命中 → 合并完成后清理该表（纯缓存，自动重建）。

## 方案与步骤

1. **匹配键（判定"同一条"，优先级递降）**：① edit-link 老平台 legacy_id 相等（两边各自正式化同一老平台报告）；② gitlab 上下文三元组（gitlab_project_id+resource_type+resource_iid 均非空且相等，目标 deleted=false——两列上为非唯一部分索引，可能多命中）；③ 业务五元组（project_name+title+review_type+review_date+review_version 逐列 `is not distinct from` 相等，双方 deleted=false）。全部不中 → 18181 独有 → 插入。
2. **决策规则**：18181 侧 deleted=true → SKIP_DELETED（报告列出）；18181 侧链接 authority=LEGACY_MANAGED → 默认 SKIP_LEGACY（20001 模型已废弃该形态、兼容快照已提供等价内容；可 `--include-legacy-managed` 强制，链接重写为 PLATFORM_OWNED）；匹配成功且 18181 effective_updated_at **严格大于** 20001 → REPLACE 整树替换（保留 20001 主行 id，全部业务列/时间戳/影子列/created_by 覆盖；四子表删旧插新重指父；两类 edit_links 旧行按 record 删除并按 18181 侧重建重解析）；其余（20001 更新或相等）→ KEEP 保留 20001；**多条 18181 记录争抢同一 20001 目标 → 仅保留 effective 最新者，其余 SKIP_CONFLICT 需人工复核**。插入/替换时 20001 侧的实时 updated_at 参与比较（非导出时刻快照），天然规避导出→合并窗口内 20001 用户新编辑被旧数据覆盖。
3. **执行流程**（runbook 指令见「执行 runbook」段）：
   - `migrate-review-data-export.sh <18181部署目录> <导出目录>`：定位 18181 PG 容器 → 7 表列集合与预期精确比对（漂移即中止）+ Flyway 版本留痕 → `\copy to stdout csv`（显式列清单，NULL/空串往返保真；空表 0 行合法）导出 7 CSV + manifest + SHA256。18181 全程只读。
   - `migrate-review-data-merge.sh <20001部署目录> <导出目录> [--apply] [--include-legacy-managed]`：导出物 SHA256/manifest 校验 → 20001 侧 7 表列集合校验 → staging schema `mig_18181`（LIKE 目标表建草稿 + edit_links 补源侧 last_handover_at 列）载入 7 CSV 并与 manifest 逐表比对行数 → **dry-run（默认）**：只读计算全部决策输出逐条报告（概要/明细/链接重解析/子表规模），不写正式数据 → 用户复核 → `--apply`：**先全库 pg_dump -Fc 备份到导出目录（pg_restore --list 校验通过才继续；备份先于一切写入）**，再单事务执行：temp 表冻结决策 → id 映射（插入用 nextval 序列、替换保留 20001 id）→ 删目标旧链接/旧子树 → 插入/整树替换 → 两类链接按 legacy_id 在 20001 兼容表重解析（解析不到丢弃并在报告标注）→ 守恒校验（决策覆盖全部源记录/每迁移记录子树行数=源非删数/链接数=可解析源链接数，任一不符 raise 整体回滚）→ 清 `page_record_snapshots` → drop staging → COMMIT。
   - 链接迁移时 `match_mode_report_id`/`match_mode_problem_id` 按 legacy_id 在 20001 兼容表重解析；解析不到则丢弃该链接并在报告标注（正式记录照常迁移，20001 无对应快照故无双显示风险）。
4. **验证**：dry-run 报告人工复核每一条 INSERT/REPLACE/SKIP_CONFLICT；apply 后守恒校验自动全对；20001 页面抽查迁移记录的列表可见性、详情（专家/问题项/描述/分工）、搜索命中；18181 侧无任何改动。**本地演练已完成**（20260909，演练栈 qaflex-upgrade-sim-20001）：五决策分支逐项命中、apply 提交后逐项核对通过、两次模拟失败（备份校验阶段）零残留可重跑。

## 决策记录

- 已定：匹配键三层优先级（legacy_id > gitlab 三元组 > 平台重复五元组）；effective_updated_at 含子表；相等时间保留 20001（生产优先保守方向）；跳过软删与 LEGACY_MANAGED（后者可开关）；不迁兼容快照表。
- 待用户确认默认值（偏保守，均可调）：软删记录跳过、LEGACY_MANAGED 跳过、相等保留 20001。
- 否决（2026-09-09，用户询问后代码实证）：经平台"旧平台 Excel 导入"模块迁移——`confirm` 只 `createRecord` 无更新语义、`duplicateStrategy` 仅 SKIP/插新行（无法表达"比较更新时间择新"）、Excel 管道含类别猜测/工作量均摊/大量回退改写（非原样迁移）、`ReviewDataRecordSaveRequest` 无 gitlab 三元组且 descriptions/contents 恒空、页面导出 Excel 与导入模板格式不同还需转换器。
- 否决：直接整库 dump/restore（会覆盖 20001 自有生产数据）；按主表 updated_at 比较（会漏子行编辑导致误判新旧）；迁移兼容快照表（与 20001 自有同步冲突）。

## 接口契约

- 无应用代码/API 变更；产物 = 两个 bash 脚本 + 本文档。脚本契约见上（参数、`--apply`、`--include-legacy-managed`）。

## 风险与假设

- 假设：18181 Flyway ≤20260724.x、20001 =20260803.01（脚本运行时以 information_schema 列集合精确校验兜底，漂移即中止而非带病迁移）。
- 假设：误录数据不含 30001 侧（用户明示仅 18181）。
- 风险：业务五元组可能误并/漏并（同名同日同版本的不同评审）——dry-run 逐条报告人工复核是硬闸门；匹配命中多行时取 updated_at 最新并在报告标注 multiple。
- 风险：导出→合并窗口内 18181 再有新编辑不在本次迁移内（runbook 提示导出后勿再在 18181 录入，或重跑导出）。
- 未验证：真实内网执行（SQL 逻辑与 bash 编排已在本地演练栈全链路实测通过；dry-run 报告在真实 18181 数据上首次生成时仍须人工复核，该闸门不因演练而豁免）。
