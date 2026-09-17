<!-- DOC_STATUS_START -->
> 文档状态：常驻 Runbook
> 说明：评审数据 18181→20001 增量迁移脚本的操作规程与权威执行手册。脚本逻辑已在本地演练栈全链路实测通过；真机首次执行仍须逐条人工复核 dry-run 报告（该闸门不因演练而豁免）。
> 关联：调查证据与决策记录见 `docs/plans/review-data-18181-to-20001-migration-20260909.md`；本文件是执行步骤的权威来源。
<!-- DOC_STATUS_END -->

# 评审数据 18181→20001 增量迁移 · 使用手册

## 1. 这套脚本做什么

把误录入 **18181 实例**的评审数据，增量合并进 **20001 生产实例**：

- 20001 没有的记录 → 直接迁入（INSERT）；
- 两边都有的记录 → 按"更新时间新者为准"（18181 严格更新才 REPLACE 整树替换，否则 KEEP 保留 20001）；
- 20001 独有、或 20001 更新/相等的记录 → 一律不动。

迁移域 = 评审数据持久化全集 **7 张表**：主表 `review_records`；四张子表 `review_record_experts`、`review_problem_items`、`review_record_descriptions`、`review_record_contents`；两张正式↔老平台链接表 `review_data_match_mode_edit_links`、`review_data_match_mode_problem_edit_links`。

**不迁移**：兼容模式快照表（`review_data_match_mode_reports/descriptions/problem_details/contents` 等）——两实例从同一老平台同步，20001 自有同源数据；也不动 18181 数据库（全程只读导出）、不碰任何 PostgreSQL 容器/数据卷。

两个脚本：

| 脚本 | 运行侧 | 作用 |
| --- | --- | --- |
| `deploy/migrate-review-data-export.sh` | 18181（源） | 只读导出 7 表为 CSV + manifest + SHA256 |
| `deploy/migrate-review-data-merge.sh` | 20001（目标） | 校验导出物 → dry-run 决策报告 / `--apply` 单事务合并 |

> 本手册只覆盖**评审数据合并**这一段；它在内网的完整执行位置（验包 → 备份 → 升级 20001 → 导出 → 合并 → 验收）见 §5「总顺序」。升级链路的权威定义在 `deploy/intranet-offline-packaging-standard.md`。

## 2. 环境要求

- **操作系统**：内网 Ubuntu（现场 115 服务器），两实例**同机**部署。
- **必备命令**（宿主机）：`bash`、`docker` + **Docker Compose v2**（`docker compose` 子命令）、`sha256sum`、`mktemp`、`awk`。
- **可选**：`flock`（util-linux）——存在则用于防 dry-run/apply 重叠运行；缺失时脚本降级跳过（单人顺序执行无影响）。
- **不需要**在宿主机安装 PostgreSQL 客户端：`psql`/`pg_dump`/`pg_restore` 全部通过 `docker exec` 在**各自的 postgres 容器内**执行。
- **权限**：能执行 `docker`（或 `sudo docker`，脚本会自动探测）；对导出目录有读写权限。
- **导出目录**：无需预建，脚本会 `mkdir -p`；两实例同机时导出目录可被 merge 直接复用。

## 3. 是否需要拉起 18181 之前运行的平台？

**不需要拉起 18181 的前端/后端，只需要临时起它的 PostgreSQL。**

- 导出脚本只读数据库，不经过 18181 的应用层。18181 当前是 `docker compose down` 状态（容器已删、**数据卷保留**），数据完好。
- **严禁整套 `docker compose up -d`**：18181 的 compose 声明了固定容器名 `qaflex-{frontend,backend,postgres}`，同机其他实例可能已占用这些名字，整套起会撞名失败，且完全没必要。
- 只起 postgres：`docker compose up -d postgres`。若 `qaflex-postgres` 名已被同机其他实例占用，见 §5 步骤 0 的 override 处置。
- 导出完成后 `docker compose down`（**不带 `-v`**）恢复 down 状态、数据卷保留。

20001 侧：merge 脚本只需要 **20001 的 postgres 在运行**，其前端/后端容器状态与脚本无关（无需停、也无需起）。

### 3.1 18181 实例身份基线（防止拉起错误平台的数据库）

115 服务器同机部署多个平台实例，且 18181 的 compose 使用**固定容器名**。因此「起了一个 postgres」不等于「起了 18181 的 postgres」——起错容器就是把别的实例当成迁移源。进入内网执行步骤 0 前，必须用下表核对身份：

| 身份项 | 18181 实例确认值（2026-09-09 现场取证） |
| --- | --- |
| **镜像 tag（前后端共用同一发布 ID）** | `20260729T093338Z-72635b164fee` |
| 前端镜像 | `qa-flex-platform-frontend:20260729T093338Z-72635b164fee`（主机端口 18181） |
| 后端镜像 | `qa-flex-platform-backend:20260729T093338Z-72635b164fee`（主机端口 18080） |
| 容器名 | `qaflex-frontend` / `qaflex-backend` / `qaflex-postgres`（固定名，非 project 前缀） |
| 部署目录 | `/home/huayun/data_collection_platform/official_version_18181` |
| PostgreSQL 主机端口 | 15432 |
| **预期 Flyway 版本** | `20260729.03`（该构建镜像内最新迁移为 `V20260729_03__migrate_sync_cursors_to_json.sql`） |

该 tag 是**正向指纹**：只有 compose 中出现 `20260729T093338Z-72635b164fee` 的部署目录才是本次迁移的源。同机其他实例（20001 为 20260803 全新包，另有曾手动升级的更新实例）tag 与之不同即判定为「不是 18181」，不得在其目录内执行步骤 0，也不得以「端口看起来一样」「目录名带 18181」作为判据。

**起库前的身份核对（不依赖手工输入目录名）**：

```bash
ls -d /home/huayun/data_collection_platform/*/        # 用实测目录名，勿凭记忆手打
cd <上一步实测出的 18181 目录>
grep -nE 'image:|container_name:' docker-compose.yml   # 必须出现 20260729T093338Z-72635b164fee
```

`image:` 行不含该 tag → 目录选错，**立即停止**，不要继续 `docker compose up -d postgres`。

**起库后的身份复核**：`migrate-review-data-export.sh` 会打印 `source flyway version:`，必须等于 `20260729.03`；若打印出 `20260803.01` 或其他值，说明连到的是别的实例数据库，立即 `docker compose down`（不带 `-v`）并回到上面的目录核对。

> 两处取证冲突，现场以实测为准：① 目录名另有一份记录写作 `officical_verston_18181`（拼写不同），故必须用 `ls` 结果 + 上表 tag 双重确认，不得手打路径；② 取证时曾观测到三容器 healthy（前端自 8/8 连续运行），而 09-09 记录为已 `down`（容器删、卷留）——实际状态以 `docker ps -a` 为准，两种状态在步骤 0 均已覆盖。

## 4. 决策规则（dry-run 报告里每条记录的判定）

对每条 18181 评审记录：

| 条件 | 决策 | 含义 |
| --- | --- | --- |
| `deleted = true` | `SKIP_DELETED` | 源侧已软删，跳过（报告列出） |
| 链接 `authority = LEGACY_MANAGED` 且未加开关 | `SKIP_LEGACY` | 20001 已废弃该形态，默认跳过 |
| 三层匹配键均未命中 | `INSERT` | 18181 独有，插入为新记录（新 id 取自 20001 序列） |
| 命中且 18181 更新时间**严格大于** 20001 | `REPLACE` | 整树替换（保留 20001 记录 id，业务列/影子列/时间戳/created_by 全覆盖，子表删旧插新） |
| 命中且 20001 更新或时间相等 | `KEEP` | 保留 20001 现状（生产优先，保守方向） |
| 多条 18181 记录争抢同一 20001 目标 | 最新者照常，其余 `SKIP_CONFLICT` | 需人工复核处理 |

- **匹配键优先级**：① 兼容快照 `legacy_id`（edit_links 表）② GitLab 上下文三元组（`gitlab_project_id` + `gitlab_resource_type` + `gitlab_resource_iid` 均非空且相等，目标 `deleted=false`）③ 业务五元组（`project_name` + `title` + `review_type` + `review_date` + `review_version` 逐列 `is not distinct from` 相等，双方 `deleted=false`）。
- **更新时间** = `effective_updated_at` = 主行 `updated_at` 与四张子表各自 `max(updated_at)` 的最大值（因为主行 `updated_at` 不随子行编辑变化）。比较用的是 20001 侧**实时** `updated_at`（非导出时刻快照），天然规避"导出→合并窗口内 20001 用户新编辑被旧数据覆盖"。
- `--include-legacy-managed`：把 `LEGACY_MANAGED` 记录也迁移（链接重写为 `PLATFORM_OWNED`）。默认不加即跳过。

## 5. 操作步骤

> 以下 `<...>` 为现场占位符。所有命令在 **115 服务器**上执行。脚本路径以实际存放位置为准（下文记作 `<脚本目录>`）。

### 总顺序 · 20001 部署最新版 + 18181 只起 pg

实例落点（2026-09-10 用户确认）：**最新版只部署到 20001**；**18181 不部署、不升级，只临时拉起 postgres 供只读导出**。

```bash
# 阶段一 · 20001 升级到最新版（升级链路以发布标准为准）
cd <更新包目录> && sha256sum -c SHA256SUMS.txt
cd <20001部署目录>
docker compose --env-file .env config --images        # 必须等于发布清单 baseline
bash <更新包目录>/backup.sh "$PWD"                     # ← 备份点①（强制，必须在部署前）
bash <更新包目录>/upgrade.sh "$PWD" "$(cat upgrade-backups/latest-predeploy-backup.txt)"
#   Flyway 前向迁移在 upgrade.sh 内部完成后端启动时自动执行，无需手工跑 SQL

# 阶段二 · 从 18181 只读导出（先过 §3.1 身份闸门）
cd <18181部署目录>
grep -nE 'image:' docker-compose.yml                  # 必须出现 20260729T093338Z-72635b164fee
docker compose --env-file .env up -d postgres         # 只起 pg，严禁整套 up
bash <脚本目录>/migrate-review-data-export.sh "$PWD" <导出目录>
#   日志 source flyway version: 必须是 20260729.03
docker compose down                                   # 不带 -v，恢复 down、数据卷保留

# 阶段三 · 合并进 20001（三次独立调用，不可合并）
bash <脚本目录>/migrate-review-data-merge.sh <20001部署目录> <导出目录>          # dry-run
#   → 逐条人工复核 dry-run-report-*.txt（硬闸门）
bash <脚本目录>/migrate-review-data-merge.sh <20001部署目录> <导出目录> --apply  # ← 备份点②自动在写入前

# 阶段四 · 验收：20001 页面抽查列表/详情/搜索；不重启任何容器
#   可选备份点③（留档）：系统设置 → 数据库备份 → 立即备份
```

三个备份点的定位：**① 是 upgrade.sh 的准入闸门**（无它则脚本直接拒执行，且部署后现场镜像已不等于基线镜像，L1183 检查必失败）；**② 是合并的真正恢复点**（`--apply` 写入前自动全库 `pg_dump -Fc` + `pg_restore --list` 校验）；**③ 只是验收通过后的留档基线**，不是安全机制——在迁移完成后再补备份不能提供任何回退能力，因为错误状态已被它固定一份。

硬约束：

- **不得把备份改到部署之后**。顺序只能是 `backup.sh` → `upgrade.sh`；也不能把本迁移插在两者之间（`upgrade.sh` 会比对静默迁移窗口前后的受保护表行数，中途合并会让 `counts.diff` 非空而失败，那是护栏，不要放宽）。
- **18181 全程不得部署新镜像或跑 `upgrade.sh`**。它必须停在 `20260729.03`；一旦升级，`review_data_match_mode_edit_links.last_handover_at` 被删，`export.sh` 列集校验失配且源语义不可复原。
- **不得用 `fresh-empty` 包对着 20001 或 18181 的现有目录/卷部署**（发布标准禁止以全新空包覆盖现有实例）。
- **合并完成后不需要重启/重新拉起平台**：`merge.sh` 只通过 `docker exec` 连目标 postgres 容器，不经过应用层；查询缓存 `page_record_snapshots` 已在合并同一事务内 `truncate`，下次读取自动重建。需要收尾的只有 18181 的 postgres（起完必须 `down`）。

### 步骤 0 · 临时起 18181 的 PostgreSQL（仅导出前）

```bash
cd <18181部署目录>                       # 含 .env 与 docker-compose.yml；目录名先按 §3.1 用 ls + tag 双重确认
grep -nE 'image:' docker-compose.yml     # §3.1 身份闸门：必须出现 20260729T093338Z-72635b164fee
docker volume ls | grep 18181            # 确认数据卷仍在（外部卷，卷名以现场 .env 的 POSTGRES_VOLUME_NAME 为准）
docker ps -a --format '{{.Names}}\t{{.Status}}' | grep qaflex   # qaflex-postgres 名占用预检
docker compose --env-file .env up -d postgres                   # 只起 postgres，勿整套 up
docker ps | grep postgres                                         # 等 healthy（WAL 回放约 10-60 秒）
```

- 若 `qaflex-postgres` 名**未被占用** → 上面直接起即可。
- 若**已被同机其他实例占用**（如其曾手动升级）→ 在 18181 部署目录写一个临时 `docker-compose.override.yml`，把 postgres 的 `container_name` 改名（如 `qaflex-postgres-18181-export`）再 `up -d postgres`。导出脚本按"部署目录的 compose 项目 + 服务名"定位容器，**不依赖容器名**，改名不影响导出。用完记得删除该 override 文件。

### 步骤 1 · 从 18181 只读导出

```bash
bash <脚本目录>/migrate-review-data-export.sh <18181部署目录> <导出目录>
```

脚本会：定位 18181 的 postgres 容器 → 对 7 表逐表做 `information_schema` 列集合精确比对（**任何列漂移立即中止**）→ 记录源 Flyway 版本 → `\copy ... to stdout with (format csv)` 导出 7 个 CSV（显式列清单，NULL/空串往返保真，空表 0 行合法）→ 写 `manifest.env`（逐表行数 + 源身份 + Flyway 版本）→ 生成 `SHA256SUMS.txt`。**18181 全程只读。**

导出成功后日志形如 `export complete: <导出目录>`。

### 步骤 2 · 恢复 18181 为 down 状态

```bash
cd <18181部署目录>
docker compose down          # 不带 -v：容器删除、数据卷保留
# 若步骤 0 用过 override：rm -f docker-compose.override.yml
```

> 导出后**请勿再在 18181 录入/修改评审数据**，否则这批新改动不在本次迁移内（需要时重跑步骤 0-1 重新导出）。

### 步骤 3 · 在 20001 侧预演（默认 dry-run，不落库）

```bash
bash <脚本目录>/migrate-review-data-merge.sh <20001部署目录> <导出目录>
```

脚本会：`sha256sum -c` 校验导出物 → 20001 侧 7 表列集合校验（漂移即中止）→ 建草稿 schema `mig_18181`（`like public.x`）载入 7 CSV 并**逐表行数与 manifest 核对** → 只读计算全部决策，输出 6 段报告（决策概要 / 逐条明细 / 两类链接重解析 / 子表规模），存档 `<导出目录>/dry-run-report-<时间戳>.txt`。**dry-run 对正式表零写入。**

### 步骤 4 · 人工复核（硬闸门，不可跳过）

打开 `dry-run-report-<时间戳>.txt`，**逐条**核对：

- 每一条 `INSERT` / `REPLACE` / `SKIP_CONFLICT` 是否符合预期；
- `REPLACE` 是否确实应当用 18181 覆盖 20001（看 `src_updated` vs `dst_updated`）；
- 链接重解析的 `resolved id`（`DROP(20001无该快照)` 表示该链接会被丢弃，正式记录照常迁移）；
- `multi` 列 > 1 的行（匹配命中多个目标，重点看）；
- 决策概要的各类计数是否与预期规模一致。

**特别复核业务五元组匹配（key=3）**：同名/同日/同版本但实为不同评审，理论上可能被误判为"同一条"而 REPLACE。这是本迁移唯一的模糊匹配风险，人工复核是主要防线。

### 步骤 5 · 执行合并（`--apply`）

```bash
bash <脚本目录>/migrate-review-data-merge.sh <20001部署目录> <导出目录> --apply
# 如需一并迁移 LEGACY_MANAGED 记录：追加 --include-legacy-managed
```

`--apply` 的安全序：

1. **先全库 `pg_dump -Fc` 备份**到 `<导出目录>/pre-merge-backup-<时间戳>.dump` → 非空检查 → 拷进容器 `pg_restore --list` **校验通过才继续**（备份先于一切写入）；
2. **单事务**执行：冻结决策 → id 映射（INSERT 用 20001 序列 `nextval`、REPLACE 保留 20001 id）→ 先删目标旧链接/旧子树 → 插入/整树替换 → 两类链接按 `legacy_id` 在 20001 兼容表重解析（解析不到丢弃并进报告）→ **四重守恒校验**（决策守恒 / 子树守恒 / edit-link 守恒 / problem-edit-link 守恒，任一不符 `raise exception` 整体回滚）→ 同事务清空查询缓存 `page_record_snapshots` → `drop schema mig_18181 cascade` → `commit`；
3. 输出 `apply-log-<时间戳>.txt`（含已执行决策概要与两类链接丢弃清单）。

任何一步失败 → 整个事务回滚，20001 回到执行前状态，草稿 schema 保留供排查；处置后**直接重跑**（staging 每次重建，幂等）。

### 步骤 6 · 合并后验收

- 抽查 20001 页面（`/review-data/home` 等）：迁移记录的**列表可见性、详情（专家/问题项/描述/分工）、搜索命中**是否正常；
- **不需要为了“刷新指标”而手工编辑保存任何记录**：正式态页面的缺陷密度、是否达标与加权缺陷密度全部在**读取时实时计算**（`ReviewDataRecordRowMapper` 走 `ReviewDataReachStandardRule.reached(reviewType, problemDensity)`，`ReviewDataRecordQueryBuilder` 用 `ReviewDataMetricSqlExpressions` 表达式并以其别名参与排序），不消费 `review_records.weighted_defect_density` 存量列；`not_reach_standard_reason` 是用户填写的自由文本，原样搬运即正确。查询缓存 `page_record_snapshots` 已在合并事务内清空，下次读取自动按新口径重建。手工编辑保存只会刷新 `updated_at`（作用是将来再跑迁移时该记录会被判 `KEEP`，从而保护手工编辑）；
- 例外（不在本迁移域，仅防混淆）：兼容模式表 `review_data_match_mode_reports.weighted_defect_density` **是**真读取的存量列（值来自老平台 Mongo 同步）。它不随本次合并变化，因为兼容快照表不迁、两实例同源各自同步；
- 核对 `apply-log-<时间戳>.txt` 里守恒校验已通过、丢弃清单符合预期；
- 报告、日志、`pre-merge-backup-*.dump` 均留在导出目录备查。

## 6. 参数与产物速查

**export.sh**：`migrate-review-data-export.sh <18181部署目录> <导出目录>`

**merge.sh**：`migrate-review-data-merge.sh <20001部署目录> <导出目录> [--apply] [--include-legacy-managed]`

| 参数 | 含义 |
| --- | --- |
| （无 `--apply`） | dry-run，只出报告不落库（默认） |
| `--apply` | 真正执行合并（先全库备份+校验，再单事务） |
| `--include-legacy-managed` | 一并迁移 `authority=LEGACY_MANAGED` 记录（链接重写为 `PLATFORM_OWNED`） |
| `--help` / `-h` | 打印用法 |

> 帮助与头部说明同源：`merge.sh --help` 由 `usage()` 用 awk 自动打印文件头注释块（第 4 行起到首个非注释行），改头部即改帮助；`export.sh` 无 `--help`，参数缺失时直接报 usage。两个脚本的头部已同步收录§5「总顺序」与 18181 身份闸门，现场只拿到脚本时也能看到同一套约束；修改头部注释时必须两边同步（本节与脚本头部不双轨维护事实，只允许脚本头部引用本手册）。

**导出目录产物**：`<表名>.csv` ×7、`manifest.env`、`SHA256SUMS.txt`；merge 侧追加 `dry-run-report-<时间戳>.txt`、`apply-log-<时间戳>.txt`、`pre-merge-backup-<时间戳>.dump`、`.merge.lock`。

## 7. 为什么不会损坏/丢失数据（安全保证）

- **源侧只读**：export 只 `select`/`\copy to`，绝不写 18181。
- **写入前四道硬闸门**：SHA256 校验导出物、目标列集合校验、载入草稿 schema 后行数与 manifest 核对、flock 防重叠——任何"导出/导入不完整"都在**写库前**干净中止，不留半成品。
- **默认 dry-run**：不人工加 `--apply` 绝不写正式表。
- **单事务 + 守恒断言**：合并全有或全无；四重守恒任一不符即 `raise exception` 整体回滚。
- **apply 前全库备份并校验**：最坏情况可用 `pre-merge-backup-*.dump` 完整还原（还原步骤见 `deploy/runbooks/database-backup-restore.md`）。
- **列清单完整显式**：主表 29 列、各子表全列均在 INSERT/UPDATE 中逐列列出，不存在"漏拷某列导致静默丢字段"。
- **id 与外键重映射正确**：INSERT 用 20001 自身序列取新 id；问题项先建 `src_id→新id` 映射，问题链接据此重指；链接的 report/problem id 按 `legacy_id` 在 20001 兼容表重解析，解析不到自然丢弃（不插坏引用）。

## 8. 失败处置与重跑

| 现象 | 处置 |
| --- | --- |
| export 报"column set mismatch" | 18181 版本与预期列集不符——**不要强改脚本**，先核对 18181 实际构建/Flyway 版本与迁移史，确认列集后再定 |
| export 报"postgres service is not running" | 回步骤 0 只起 postgres 并等 healthy 后重跑 |
| merge 报"SHA256 verification failed" | 导出物在传输/复用中损坏——重新导出，勿手工编辑 CSV |
| merge 报"row count mismatch after load" | CSV 截断/漏载——重新导出并核对 manifest |
| merge 报"column set mismatch on target" | 20001 版本与预期不符——核对 20001 Flyway 版本（应为 20260803.01），确认后再定 |
| `--apply` 中途失败 | 事务已整体回滚、20001 未变；看 `apply-log`/控制台定位根因，处置后直接重跑 `--apply`（会重新做全库备份） |
| 守恒校验 `raise exception` | 属预期的安全中止（已回滚）；说明决策/子树/链接数不自洽，回步骤 3-4 重新 dry-run 复核 |

## 9. 常见坑

- **传错部署目录 = 操作错实例 = 迁移错数据库**：判据是 §3.1 的镜像 tag `20260729T093338Z-72635b164fee`，不是目录名或端口印象。脚本日志会打印实际操作的容器 ID 与两侧 Flyway 版本（**18181 = `20260729.03`**、20001 = `20260803.01`），**核对后再继续**；任一不符即停止，不要靠「列集合校验会通过」侥幸放行——两实例评审表列集高度相似，只有 `last_handover_at` 等少数差异能拦住。
- **Git Bash（Windows 演练）**：脚本已用 `MSYS_NO_PATHCONV=1` 豁免路径转换、`flock` 缺失降级；Linux 现场无影响。
- **dry-run 后草稿 schema 残留**：dry-run 分支不删 `mig_18181`（apply 分支才在事务内删）。它是独立草稿 schema、不影响 `public` 表与应用，下次运行开头会 `drop ... cascade` 重建，无害。
- **导出→合并窗口**：期间 18181 的新编辑不在本次迁移内；需要时重跑导出。
