# GitLab 小样本只读导出与空测试 GitLab 导入操作手册

> 适用入口：`scripts/gitlab_bounded_sql_export.py`（导出）、`scripts/gitlab_bounded_sql_import.py`（导入）。
> 包契约与脱敏规则：`scripts/gitlab_sample_contract.py`；连接与结构检查：`scripts/gitlab_sample_db.py`。
> 本手册只覆盖已验证的 GitLab 16.11.10 结构和本地已验证流程；未覆盖版本不得按本手册直接用于生产。

## 1. 已验证范围

| 项目 | 已验证值 |
| --- | --- |
| GitLab | CE 16.11.10（`schema_migrations` 最大值 `20240702181131`） |
| 目标 GitLab PostgreSQL | 14.11（Omnibus 内置） |
| 导出源 PostgreSQL | 16.13（同 GitLab 16.11.10 schema 的本地只读副本） |
| 选样 | 单项目 325；默认 60 天窗口；148 议题、387 评论、759 label_links |
| 显式选样 | 单议题 ID / iid；时间窗上限与空选样失败路径 |
| 导入 | 空 GitLab 数据库、标记令牌、单事务、外键/多态校验、重复与替换导入 |

未验证：其他 GitLab 版本或 schema 指纹、GitLab.com/SaaS、多项目同时导出、超大议题量真实容量、实例级邮件/SMTP 配置的实际关闭、平台前端页面交互截图。上述场景必须先取证结构并补充验证，不能因为数据库名或显示版本相似而放行。

## 2. 前置条件

### 2.1 源环境

- 一个只读 PostgreSQL 账号，至少对包契约中的 24 张表有 `SELECT` 权限；不需要 GitLab 服务器登录、Rails 控制台或管理员令牌。
- 推荐使用 `PGPASSFILE` 或受控密码文件提供密码；不要把密码写入命令行、脚本、日志、报告或 Git。
- 源库必须是本次要取证的真实来源；导出前确认 `current_database()`、服务器地址/端口和角色，必要时由数据库管理员书面确认。

### 2.2 目标环境

- 独立、可丢弃、结构匹配的 GitLab CE 16.11.10 测试实例；数据库为空，仅允许实例初始化产生的管理员/根命名空间行。
- 目标数据库账号需能建表、写入、删除、查询；导入器会在 `public` 创建 `dcp_sample_import_target` 与 `dcp_sample_import_journal` 两张控制表。
- 目标实例必须与生产、内网验收、其他任务实例隔离；不得复用正在被平台同步或其他验收使用的数据库。
- 目标实例的邮件、SMTP、Webhook、外部集成、CI Runner 必须关闭或为空；导入器会关闭项目/命名空间邮件、共享 Runner、 builds、注册，并拒绝非空敏感表，但不能替代实例级 `gitlab.rb` 配置检查。

### 2.3 运行环境

- Python 3.11+；依赖仅标准库与 `psycopg2`（建议 `psycopg2-binary==2.9.12`）。
- 工具本身不依赖 Docker；只要能通过 libpq 连接 PostgreSQL 即可。
- 离线安装：在联网机器执行 `python -m pip download psycopg2-binary==2.9.12 -d <wheel-dir>`，把 wheel 复制到目标机后执行 `python -m pip install --no-index --find-links <wheel-dir> psycopg2-binary==2.9.12`。

## 3. 数据包契约

```
<package>/
  manifest.json
  CHECKSUMS.sha256
  data/<table>.jsonl
```

- `manifest.json` 包含：`format=dcp-gitlab-sample/v1`、工具版本、创建时刻、源数据库/服务器/角色指纹、GitLab 版本与 schema 指纹、固定起止与时区、选样模式、项目/议题 ID 与 iid、每表行数与文件 SHA-256、完整性检查、`status=COMPLETE`、`package_id` 和结构化脱敏清单。
- `CHECKSUMS.sha256` 覆盖 `manifest.json` 与全部 `data/*.jsonl`。
- 导入器只读取清单登记的列，自行构造参数化 `INSERT`；**不会执行包内任何 SQL**。
- `package_id` 由选样信息与各数据文件哈希重算；即使同时改写清单和 `CHECKSUMS.sha256`，身份不一致仍会被拒绝。
- 包内保留议题标题、描述、评论正文等业务文本，不做内容级脱敏；传输前必须人工确认样本内容允许离开源环境。

## 4. 选样与导出

### 4.1 选样规则

- 默认项目 325；默认窗口为运行时刻向前 60 天，时区 `Asia/Shanghai`，起止固定到秒并写入清单。
- 时间窗只筛选根议题（`issues` 表行）；选中议题的全部评论、评论元数据、标签事件、里程碑事件、指派、指标和成员关系按闭包导出，**不按评论时间或条数截断**。
- 显式选样：`--issue-ids <id,...>`（全局议题 ID）或 `--issue-iids <iid,...>`（要求恰好一个项目）；显式选样绕过时间窗。
- `--max-issues` 默认 200，硬上限 500；候选数超过上限直接失败，不静默截断。
- 多项目：`--project-ids 1,2`；闭包会按项目/命名空间/用户/标签/里程碑合并。多项目真实链路尚未验证，首次使用先用小窗口和 `--max-issues` 限制。

### 4.2 设置连接

```bash
export PGHOST='<source-db-host>'
export PGPORT='<source-db-port>'
export PGDATABASE='<source-db-name>'
export PGUSER='<readonly-role>'
export PGPASSFILE='<path-to-pgpass>'
# 或使用受控环境变量：export PGPASSWORD="$(cat <password-file>)"
```

不要把密码作为命令行参数；导出/导入脚本没有密码命令行选项。

### 4.3 执行导出

```bash
python scripts/gitlab_bounded_sql_export.py \
  --project-ids 325 \
  --since '2026-08-27T00:00:00+08:00' \
  --until '2026-10-26T23:59:59+08:00' \
  --timezone Asia/Shanghai \
  --max-issues 200 \
  --batch-size 200 \
  --batch-sleep-ms 100 \
  --statement-timeout-ms 30000 \
  --lock-timeout-ms 5000 \
  --max-runtime-seconds 300 \
  --source-instance '<source-label>' \
  --output '<package-dir>'
```

显式议题示例：

```bash
python scripts/gitlab_bounded_sql_export.py \
  --project-ids 325 \
  --issue-ids 41803 \
  --max-issues 10 \
  --output '<package-dir>'
```

- 单连接、单 worker、`REPEATABLE READ READ ONLY` 事务；选样与分页读取在同一事务内完成，形成一致的跨表视图。
- 批间 sleep 默认 100ms；总时限是协作式预算，临近上限会收紧单条查询的 `statement_timeout`，超时或断开即失败，不续跑拼接。
- 输出目录必须不存在；工具先在临时目录写完全部文件，再原子改名为最终目录。失败不会留下半成品包。
- 成功后记录 `package_id`、议题数、评论数和耗时。

## 5. 传输与校验

1. 传输整个目录（`manifest.json`、`CHECKSUMS.sha256`、`data/`），不要只复制部分文件。
2. 在包目录执行 `sha256sum -c CHECKSUMS.sha256`（Windows 可使用 Git Bash）。
3. 传输后确认包目录未被解压工具改写换行或编码；`.jsonl` 必须保持 UTF-8、LF。
4. 不要把包提交到 Git、对象存储公共桶或工单附件；按样本敏感级别使用受控介质并在验收后删除。

## 6. 目标初始化

目标必须是全新的 GitLab 16.11.10 实例。先确认目标连接身份，再写入归属标记：

```bash
export PGHOST='<target-db-host>'
export PGPORT='<target-db-port>'
export PGDATABASE='<target-db-name>'
export PGUSER='<target-db-owner>'
export PGPASSFILE='<path-to-target-pgpass>'
export DCP_TARGET_TOKEN='<random-32+-char-token>'

python scripts/gitlab_bounded_sql_import.py \
  --init-target \
  --target-label '<operator-target-label>' \
  --report '<target-init-report.json>'
```

- `--init-target` 只在目标没有标记时成功；已有标记会拒绝覆盖。
- `DCP_TARGET_TOKEN` 至少 16 字符；标记表只保存 SHA-256，不保存明文。令牌不要写入报告或 Git。
- `--target-label` 是操作者给目标起的唯一名称，导入时必须完全一致。

## 7. 导入

```bash
python scripts/gitlab_bounded_sql_import.py \
  --package '<package-dir>' \
  --target-label '<operator-target-label>' \
  --report '<import-report.json>'
```

导入器会依次完成：

1. 读取并校验包完成状态、清单、`package_id`、逐文件 SHA-256、表顺序、列白名单和行数。
2. 校验源/目标不是同一数据库（服务器地址 + 端口 + 数据库指纹）；校验目标标记标签与令牌。
3. 校验目标 GitLab schema 版本与 schema 指纹等于包记录；拒绝其他版本/结构。
4. 检查目标为空（仅允许初始化管理员/根命名空间/根路由）；检查敏感/外部表为空；非空目标默认拒绝。
5. 在单事务内按依赖顺序参数化插入；对 `organizations` 按主键、`work_item_types`/`member_roles` 按业务键复用目标种子行，必要时分配新 ID 并改写议题引用；bootstrap 用户/命名空间/路由只允许使用目标初始化行。
6. 补齐 GitLab 直接导入不会自动创建的关联行（用户详情、偏好、命名空间详情/设置、项目设置、项目 CI 设置）。
7. 校正序列（尊重 `pg_sequence.seqmin`），关闭共享 Runner/builds/项目与命名空间邮件/注册，检查全部外键与多态关联、敏感表为空。
8. 任一失败整体回滚；成功才提交并写报告。

成功后检查报告：`status=IMPORTED`、`foreign_key_checks` 全部 `orphans=0`、`polymorphic_checks` 全部 `violations=0`、`external_table_counts` 全部为 0、`reference_maps` 符合预期。**未通过时不得交给平台验收。**

### 7.1 导入后的测试账号与令牌

导入器要求 `personal_access_tokens`、`sessions`、`web_hooks` 等敏感表为空。因此：

- 需要平台 API 读取时，在导入完成后再创建测试 PAT（GitLab 页面或 Rails 控制台），不要提前创建。
- 已创建 PAT/登录会话的目标不能再直接重复导入；标准做法是重建目标数据库或使用 `--replace-existing`（仅当目标仍只含本工具上次导入且无这些操作者行时）。
- 测试用户密码、PAT、Webhook 等由目标实例重新生成；包内不含任何可登录凭据。

## 8. 空 GitLab 验收

用目标 GitLab 页面或 API 确认：

1. 项目 ID、议题 ID、iid 与包清单一致；目标项目/议题可打开。
2. 选中议题的全部评论可见，包含窗口前的最早评论；报告中的评论数与页面/API 一致。
3. 标签、里程碑、作者（脱敏占位）、成员/项目授权关系存在；不得出现真实姓名/邮箱。
4. 能新建测试议题、添加评论、添加/摘除测试标签；操作只影响目标测试实例。
5. Webhook、集成、CI、个人令牌等敏感表仍为空（创建平台 PAT 后仅允许该测试 PAT 行存在）。

验收证据保存到任务证据目录，不提交 Git。

## 9. 平台验收（可选，需独立资源）

使用冻结的延期候选镜像时，必须新建独立平台数据库、后端/前端容器、网络和端口，不得共用既有验收库，不得修改候选分支。

1. 平台配置：`sourceMode=DIRECT`；`dbHost`/`dbPort`/`dbName`/`dbUsername`/`dbPassword` 指向目标 GitLab 数据库；`webBaseUrl` 必须是**平台后端容器可访问**的目标 GitLab 地址（不能填平台容器自身的 `127.0.0.1`）；`apiToken` 使用导入后创建的测试 PAT。
2. 白名单至少包含 `issues`、`projects`、`namespaces`、`users`、`milestones`、`labels`、`label_links`、`notes`、`issue_assignees`、`resource_label_events`、`issue_metrics`，以及平台读取的合并请求/评审表。
3. 先保持 `delayLabelWritebackEnabled=false`，执行首次全量；等待运行 `SUCCESS`、事实发布状态 `READY`、无待发布根。
4. 开启自动增量；新增/修改源数据，确认自动增量把变化带入 ODS 与事实。
5. 开启延期标签写回；选择事实延期状态与当前标签不一致的样本，验证真实加/摘标签、保护标签不变、作业全部 `SUCCEEDED`。
6. 等待自动增量把写回结果带回 ODS，再观察一个完整周期：不得出现新增或变更的写回作业，标签保持不变，即第二轮收敛。
7. 任何作业进入 `RETRY_WAIT`/失败、标签与事实不一致、或第二轮出现新作业时，停止平台验收，按失败处理表排查，不得手工改库掩盖。

## 10. 失败处理

| 现象 | 含义 | 处理 |
| --- | --- | --- |
| `Package is not COMPLETE` / `Checksum mismatch` / `package_id` | 包未完成、被改动或传输损坏 | 重新传输并校验；不要手工改清单或跳过校验 |
| `Unsupported GitLab schema` | 源/目标版本或 schema 指纹不在已验证范围 | 停止；先取证结构与迁移版本，不能强行导入 |
| `Current role lacks access to required GitLab tables` | 源账号缺少 SELECT 或表不可见 | 由数据库管理员授予最小 SELECT；不要用更高权限绕过 |
| `canceling statement due to statement timeout` / lock timeout / `runtime limit exceeded` | 查询、锁等待或总预算超限 | 缩小窗口/项目/议题数，或显式调整对应上限后重跑；检查源库负载 |
| `No root issues` | 窗口内无根议题 | 调整 `--since/--until` 或项目；确认时区 |
| `above --max-issues` | 候选议题超过上限 | 缩小窗口或显式提高 `--max-issues`；不得接受截断结果 |
| `Target is not an empty test GitLab` | 目标已有数据 | 使用全新目标；仅在上次导入且无操作者行时使用 `--replace-existing` |
| `Target marker ...` / token/label 不匹配 | 目标归属不明或令牌错误 | 停止；核对目标实例、标记标签和令牌环境变量 |
| `Source and target are the same database` | 源目标连接指向同一库 | 停止；修正环境变量，禁止继续 |
| `Foreign key ... orphan` / `Polymorphic ...` | 包关联不完整或导入破坏完整性 | 不得交给平台；回滚目标，检查导出闭包和包报告 |
| 写回作业 `RETRY_WAIT` / 标签未知 | 平台无法访问目标 GitLab API 或发送结果未知 | 检查 `webBaseUrl` 是否从平台后端可达、PAT 权限/有效期、目标 GitLab 状态；不要把失败当成功 |
| 重复导入被拒 | 目标标记记录上次导入 | 需要替换时显式 `--replace-existing`；否则重建目标 |

## 11. 重复导入、替换与目标重建

- **默认重复导入**：拒绝；不会静默跳过或覆盖。
- **`--replace-existing`**：读取上次包 ID 的导入日志，按逆依赖顺序只删除上次包登记的行（引用种子行保留），重新导入新包；必须重新通过全部校验。上一包与当前包不同也可以替换，前提是目标仍只含本工具管理的数据。
- **目标重建**：停止目标 GitLab，删除/重建目标数据库（或删除并重建测试实例），重新执行第 6 节初始化与第 7 节导入。不要手工删除标记表或导入日志来“绕过”重复检查。
- 重建后必须重新创建测试 PAT，并重新执行空 GitLab 验收和平台验收。

## 12. 停止条件

出现以下任一情况立即停止，不继续导出/导入/平台验收：

- 连接目标实际是生产库、共享库或归属不明；无法用服务器地址/端口/数据库/标记确认目标身份。
- 源账号需要写入权限才能完成操作，或导出过程出现任何写操作。
- 包校验失败、版本/结构不匹配、目标非空且不能安全重建。
- 为通过检查需要删除敏感表、关闭外键触发器、放宽校验、截断评论或使用 `ON CONFLICT DO NOTHING`。
- 目标 GitLab 仍启用邮件、Webhook、外部集成、CI Runner 或生产外发配置。
- 平台写回作业失败、标签与事实不一致，或第二轮出现新差异。
- 共享 Docker/其他任务资源需要重启或清理才能继续。

## 13. 清理与证据

- 验收结束后停止本任务新增的容器/服务，保留证据卷供复核；不得停止或清理其他任务/共享资源。
- 样本包、证据中的凭据文件和平台 PAT 不进入 Git；按敏感数据要求删除或加密归档。
- 交付证据至少包含：包清单、校验和、导出耗时/数量、导入报告、GitLab 页面/API 结果、平台同步/写回/第二轮结果、测试报告。
