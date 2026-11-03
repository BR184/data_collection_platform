# 表单迁移一次性乱序升级入口

本 runbook 只用于已批准升级窗口：把目标旧库升级到表单 v6 发布目标迁移集（174 条，水位 `20261102.08`）。乱序只允许通过本入口的单次运行开启，常驻服务必须保持默认 `false`。

## 一、适用范围与停止条件

适用：

- 目标库为表单 v6 发布候选对应的旧库，已取得可恢复备份，升级窗口已批准。
- 允许更换后端制品；数据库升级与事实/投影重建在同一窗口完成。

任何一步出现以下情况立即停止，不执行下一步，不执行 `repair`，不删除或改写 Flyway 历史：

- 数据库身份、版本集合、校验和与窗口清单不一致。
- 备份缺失、状态非 `SUCCESS`、大小或 SHA-256 不符。
- 升级前仍有运行中的同步、延期写回、延期闭包、系统钩子队列或其他外部写入。
- 单次迁移进程非零退出，或迁移模式下启动 Web。
- 迁移后版本数不是 174、最大版本不是 `20261102.08`、存在 `success=false`。
- 新表/视图/函数/权限缺失，或旧 `code_review.form.*` 仍存在。
- 冷启动校验 `/actuator/health` 非 `UP` 或 Flyway 校验失败。
- 重建期间出现外部采集或标签写回。
- 必需读发布域（GitLab 事实、镜像表）仍有空指针；CAT 未配置不算停止条件。

## 二、前置核验

### 2.1 制品与备份身份

在构建/发布记录中固定并留存：

- 后端 jar 文件名与 SHA-256。
- 前端制品文件名与 SHA-256（如本次包含前端）。
- `scripts/flyway-migration-checksums.json` 的 SHA-256。
- 数据库备份文件名、大小、SHA-256 与备份页 `SUCCESS` 记录。

备份与恢复操作按 `deploy/runbooks/database-backup-restore.md` 执行；本文不重复 `pg_dump/pg_restore` 细节。

### 2.2 数据库身份与迁移前缀

在目标库执行并留存输出：

```sql
select current_database(), current_schema();

select count(*) filter (where version is not null and success) as applied,
       count(*) filter (where version is not null) as total,
       max(version) as max_version
  from flyway_schema_history;

select installed_rank, version, description, checksum, success
  from flyway_schema_history
 where version is not null
 order by installed_rank;
```

- 数据库身份与升级窗口清单一致，且已取得可恢复备份。
- 按实际版本集合判定旧库前缀，不能只按数量或“是否装过读发布脚本”推断：

| 旧库前缀 | 版本数 | 待执行 | 说明 |
| --- | --- | --- | --- |
| 仅 main 集 | 140 | 34（18 读取 + 16 表单） | 首次升级到读发布模型 |
| 共同祖先读发布集（`84e7c023`） | 156 | 18（`20261019_01`、`20261022_03` + 16 表单） | 曾部署读发布包但未含 main 后续两条 |
| 读发布集 + main 两条 | 158 | 16（表单 v6/S1/集成） | 已接续 main 后续迁移 |
| 其它数量/未知版本/校验和不符 | — | 停止 | 先核对身份与历史，不 repair、不删历史、不猜前缀 |

- 已执行读发布脚本的数据库保留真实历史，不要求删除或“清理”这些迁移；最终以版本集合与目标 174 条比对为准。
- 逐条把已应用版本的 `version + checksum` 与构建树 `scripts/flyway-migration-checksums.json` 比对；任一既有版本 checksum 不同即停止。
- 目标迁移集为 174 条，既有迁移内容与 checksum 未改动。

### 2.3 记录开关原值

把以下查询输出保存到窗口记录文件（不打印令牌、密码等敏感值）：

```sql
select id, enabled, source_enabled, auto_sync_enabled, delay_label_writeback_enabled,
       system_hook_enabled, system_hook_project_id, web_base_url
  from gitlab_sync_configs
 order by id;

select * from backup_settings where id = 1;

select id, enabled, sync_enabled, review_data_read_mode, code_review_read_mode
  from code_review_match_mode_db_settings
 where id = 1;

select id, enabled, sync_interval_minutes, last_sync_status
  from code_review_dgm_gitlab_project_source_settings
 where id = 1;

select id, enabled, auto_sync_enabled, sync_interval_minutes
  from bi_cat_mirror_configs
 where id = 1;
```

`backup_settings` 无行时视为默认值（未启用、03:00、保留 14 份），不要为此插入新行。

### 2.4 停止外部写入

1. 停止旧后端服务/旧 Compose，确认服务端口不再监听。
2. 在库内确认没有在途外部写入：

```sql
select count(*) from sync_runs
 where status in ('QUEUED', 'RUNNING');

select count(*) from customer_issue_delay_label_writeback_jobs
 where status in ('PENDING', 'RUNNING')
    or lease_until > current_timestamp;

select count(*) from customer_issue_delay_closure_requests
 where stage <> 'IDLE';
```

三项必须为 0；任一非 0 时等待收敛或由归属方处置，不得直接升级。

3. 升级窗口内把外部写入开关按原值记录后临时关闭：

```sql
update gitlab_sync_configs
   set auto_sync_enabled = false,
       delay_label_writeback_enabled = false,
       system_hook_enabled = false;
```

- `web_base_url`、`api_token`、来源连接不动。
- 记录更新前的每一行原值，恢复阶段逐项还原；不是长期停用。

## 三、执行步骤表

| 步骤 | 操作 | 前置状态 | 完成后状态 | 停止条件 |
| --- | --- | --- | --- | --- |
| P0 | 完成第二节全部核验 | 旧库、旧制品、外部写入已停 | 身份/备份/开关原值/在途队列均有记录 | 任一核验不符 |
| P1 | 单次乱序迁移（3.1） | 旧服务已停，无外部写入 | 进程退出码 0，174 条，水位 `20261102.08` | 非零退出、Web 启动、版本/校验和不符 |
| P2 | 冷启动校验（3.2） | 迁移完成 | 全部后台关闭时健康 `UP`，新旧对象核对通过 | 健康失败、对象缺失、旧权限残留 |
| P3 | 本机接管与事实/投影重建（3.3） | 冷启动校验通过 | 必需读发布域指针完整，重建完成 | 外部采集/写回开启、重建失败、必需域为空 |
| P4 | 读发布指针验收（3.4） | 重建完成 | GitLab/镜像域无空指针；CAT 未配置可保持空 | 必需域空、发布身份不一致 |
| P5 | 逐项恢复后台任务（3.5） | 指针验收通过 | 同步、延期、匹配模式、CAT、DGM、备份按原值恢复并各验证一次 | 任一项异常或外部写入失控 |
| P6 | 最终恢复与回执（3.6） | 全部恢复 | 正常 Compose 启动、健康 `UP`、页面可读 | 回退条件触发 |

### 3.1 单次乱序迁移

使用既有 `MigrationApplication` 的无 Web 单次迁移入口（与隔离测试同一入口），显式开启 out-of-order。
`PLATFORM_RUN_MODE=migrate` 是进入该入口的开关；缺少时进程按默认 `service` 常驻启动，不能用于一次性升级。

```bash
PLATFORM_RUN_MODE=migrate java -jar <backend-jar> \
  --spring.datasource.url=jdbc:postgresql://<host>:<port>/<db> \
  --spring.datasource.username=<user> \
  --spring.datasource.password=<password> \
  --spring.flyway.enabled=true \
  --spring.flyway.out-of-order=true \
  --spring.sql.init.mode=never \
  --spring.main.banner-mode=off \
  --platform.background-jobs.enabled=false
```

- 成功：进程退出码 `0`，日志无迁移失败，`flyway_schema_history` 增至 174 条，水位 `20261102.08`，全部 `success=true`。
- 失败：进程非零退出；停止，不执行 `repair`、不删除或改写历史、不放宽校验；保留日志并恢复备份或提交修订方案。
- 迁移进程不得启动 Web；若日志出现 Web 端口监听，停止并修正启动方式。

迁移完成后立即核对新增对象（与 3.2 的同一组查询）：

```sql
select count(*) as form_tables
  from information_schema.tables
 where table_schema = current_schema()
   and table_name in (
     'form_template_definitions','form_template_scopes','form_template_versions',
     'form_template_scope_drafts','form_template_preview_evidence','form_record_audit_logs',
     'form_template_migration_logs','form_template_publications',
     'form_template_publication_attempts','form_authority_cutover_manifests',
     'form_authority_cutover_items','form_resource_authorities','form_change_heads',
     'form_field_comparable_registrations','form_record_locations','form_directory_events',
     'form_directory_event_cursors','form_record_id_watermarks','form_head_captures',
     'form_publication_captures','form_review_fact','form_record_export_logs',
     'merge_request_fact_authority','form_project_candidates','form_project_candidate_states'
   );
-- 期望：25

select table_name
  from information_schema.views
 where table_schema = current_schema()
   and table_name in ('form_scope_readable_versions','code_review_formal_records');
-- 期望：2 行

select proname
  from pg_proc
 where pronamespace = current_schema()::regnamespace
   and proname in ('form_spec_hash','form_source_set_hash',
                   'form_create_fingerprint','form_advance_record_sequence');
-- 期望：4 行

select permission_code
  from platform_permissions
 where permission_code like 'form.%'
 order by permission_code;
-- 期望：create/edit_own/delete_own/template.manage/export 共 5 个

select count(*) from platform_permissions
 where permission_code like 'code_review.form.%';
-- 期望：0
```

### 3.2 冷启动校验（全部后台关闭）

去掉 `--spring.flyway.out-of-order=true`，并显式关闭全部后台任务：

```bash
PLATFORM_RUN_MODE=service java -jar <backend-jar> \
  --spring.datasource.url=jdbc:postgresql://<host>:<port>/<db> \
  --spring.datasource.username=<user> \
  --spring.datasource.password=<password> \
  --platform.background-jobs.enabled=false
```

- `/actuator/health` 为 `UP`；Flyway `validate()` 通过。
- 3.1 的 25 表、2 视图、4 函数、5 权限查询再次通过；旧 `code_review.form.*` 为 0。
- 旧非空数据行数/业务值守恒（升级前后各取一次关键表行数并比对）。
- 此阶段**不**查询读模型、不触发安装、不启动任何周期任务；确认后停止该进程。

### 3.3 本机接管与事实/投影重建

先关闭窗口内不应运行的外部采集/写回开关（记录原值后执行）：

```sql
update code_review_match_mode_db_settings
   set sync_enabled = false
 where id = 1;

update code_review_dgm_gitlab_project_source_settings
   set enabled = false
 where id = 1;

update bi_cat_mirror_configs
   set enabled = false
 where id = 1;

update backup_settings
   set enabled = false
 where id = 1 and enabled = true;
```

启动服务：全局调度开启（接管与发布生产者需要），镜像补偿/延期闭包、延期预同步、延期写回、代码走查指标增强关闭：

```bash
PLATFORM_RUN_MODE=service java -jar <backend-jar> \
  --spring.datasource.url=jdbc:postgresql://<host>:<port>/<db> \
  --spring.datasource.username=<user> \
  --spring.datasource.password=<password> \
  --platform.gitlab-mirror.scheduler-enabled=false \
  --platform.gitlab-mirror.customer-issue-delay-pre-writeback-sync-enabled=false \
  --platform.gitlab-mirror.customer-issue-delay-writeback-worker-enabled=false \
  --platform.gitlab-mirror.code-review-metric-enrichment-enabled=false
```

- `ReadModelInstallationScheduler` 会在后台按完整来源证据安装初始读版本；等待 `read_publication_domains` 的 `GITLAB_FACT` 域出现 `current_publication_id`。
- 通过管理页触发一次事实重建（等价 `POST /api/facts/rebuild?configId=<configId>`，需 `system.fact.rebuild` 权限与 CSRF/会话），记录 `runId`；等待构建任务与发布任务完成。
- 重建期间禁止：启动 CAT/DGM 外部同步、打开延期写回、注册/恢复 GitLab 系统钩子、启动第二次同步运行。

### 3.4 读发布指针验收

```sql
select producer_kind, source_instance, fact_type, table_key,
       current_publication_id, producer_retry_after
  from read_publication_domains
 order by producer_kind, source_instance, fact_type, table_key;

select producer_kind,
       count(*) filter (where current_publication_id is null) as missing
  from read_publication_domains
 group by producer_kind
 order by producer_kind;
```

- `GITLAB_FACT`（`ISSUE`、`MERGE_REQUEST`、`INTEGRATION_TEST`）与 `MIRROR_TABLE` 必需域 `missing=0`。
- `CAT_MIRROR` 未配置时可以为空；按读契约页面显示 `INCOMPLETE/READ_VIEW_UNAVAILABLE`，不作为升级失败，也不得用假数据补齐。
- 抽查一个页面读接口，确认 `readView.sourceState` 不是 `ATTENTION`；若为 `ATTENTION`，先查对应投影发布错误，不继续恢复后台任务。

### 3.5 逐项恢复后台任务

按以下顺序恢复；每一步完成并验证后再进入下一步。

1. **镜像补偿/延期闭包**：以 `--platform.gitlab-mirror.scheduler-enabled=true` 重启（其余覆盖保持）。等待一个补偿周期，确认 `sync_runs` 新运行完成、`/api/gitlab-sync/source-health` 正常。
2. **延期预同步与写回 worker**：以 `--platform.gitlab-mirror.customer-issue-delay-pre-writeback-sync-enabled=true --platform.gitlab-mirror.customer-issue-delay-writeback-worker-enabled=true` 重启。按 2.3 记录的原值恢复每个来源的 `delay_label_writeback_enabled`；若原值为 `false`，保持关闭。验证：
   ```sql
   select status, count(*) from customer_issue_delay_label_writeback_jobs group by status;
   select stage from customer_issue_delay_closure_requests;
   ```
   不能出现 `RUNNING` 长期不收敛或新建外部写回。
3. **代码走查指标增强**：恢复 `--platform.gitlab-mirror.code-review-metric-enrichment-enabled=true`；等待一个周期，确认没有持续失败重试。
4. **匹配模式**：恢复 `code_review_match_mode_db_settings.sync_enabled` 原值；等待一次匹配模式同步，确认 `last_sync_status` 正常且 `review_data_read_mode/code_review_read_mode` 未被改动。
5. **CAT**：恢复 `bi_cat_mirror_configs.enabled` 原值；原值为 `false` 时不触发同步并确认页面仍为契约内 `INCOMPLETE`；原值为 `true` 时等待一次 CAT 同步完成。
6. **DGM 候选**：恢复 `code_review_dgm_gitlab_project_source_settings.enabled` 原值；原值为 `true` 时等待一次候选同步。
7. **系统钩子与自动同步**：按 2.3 原值恢复 `gitlab_sync_configs.system_hook_enabled/auto_sync_enabled`；确认 GitLab 侧钩子地址仍指向本实例，且没有重复注册。
8. **备份**：恢复 `backup_settings.enabled` 原值（无行则保持无行）。确认 `backup_state` 无活动运行；若恢复时已到计划时间，允许其按计划执行一次并核对成功记录。

### 3.6 最终恢复与回执

- 以正常 Compose/正式制品启动，不再附加任何升级覆盖参数。
- `/actuator/health` 为 `UP`；前端可访问；管理员登录后可打开表单填写页与表单管理页。
- 留存：
  - 升级前后 Flyway 历史、25 表/2 视图/4 函数/5 权限查询输出。
  - 备份文件名/大小/SHA-256。
  - 事实重建 runId 与读发布域验收查询。
  - 每一步开关原值/恢复值、执行时间、验证结果。
  - 迁移进程退出码与日志片段。

## 四、回滚

### 4.1 回滚条件

任一停止条件触发，或升级后出现无法在窗口内收敛的读发布/表单写入故障。

### 4.2 回滚步骤

1. 停止新后端服务，确认端口关闭、无同步/写回/钩子写入。
2. 按 `deploy/runbooks/database-backup-restore.md` 使用升级前 `SUCCESS` 备份整库恢复；恢复前确认备份文件名、大小、SHA-256。
3. 恢复与备份同期的旧后端制品/Compose 配置；`web_base_url`、API 令牌、系统钩子按 2.3 原值核对。
4. 启动旧制品，验证 `/actuator/health`、登录和一个既有页面。
5. 记录回滚原因、恢复的备份、旧制品版本和最终健康状态。

**关键事实：**

- Flyway 不向后回退。已执行的新迁移不会因换回旧 jar 而消失；回滚必须使用“数据库备份 + 配套旧包”，不能只换 jar。
- 不执行 `flyway repair`、不删除 `flyway_schema_history`、不手工删除新表来伪造回滚。
- 恢复备份会丢失备份点之后的全部写入；升级窗口已停止外部写入，仍需与业务方确认时间点。

## 五、边界

- 该参数只用于本次已批准窗口；不得写入常驻配置、启动脚本默认值或 CI。
- 不修改既有迁移、不 `repair`、不改写历史、不预装新表单脚本。
- 目标库真实身份、备份和退出码记录随升级回执归档。
- 本 runbook 不授权生产启用、真实权限授予或外部数据源开通；这些仍需单独批准。