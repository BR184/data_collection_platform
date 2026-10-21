# 完整读版本升级与验收

本文件维护读发布模型的安装、恢复与目标负载验收步骤。技术不变量见 `docs/decisions.md` D-26，当前完成状态见 `docs/progress.md`；不得据本文件推断已经迁移生产或通过容量门禁。

## 安装与恢复

1. 明确获准的目标实例、发布包和数据库备份，在同版本 PostgreSQL 的隔离副本执行完整 Flyway 链；校验 `scripts/flyway-migration-checksums.json`，既有迁移不得修改。此工作单元不自动连接内网或 20001。
2. 成熟实例升级前完成一次合格的同步及事实发布，再停止镜像/事实写入。同步成功日志不是单独的接管凭据：安装同时要求完整发布控制面、全部依赖、无待发布 heads/构建任务，以及未被候选占有的成熟事实与父关系。
3. 升级先运行一次性 `PLATFORM_RUN_MODE=migrate`，退出成功后核验 Flyway、业务行数守恒及 PG 身份，再启动 `service` 模式。仅迁移进程无 Web/业务组件；不以 backend health 代替迁移退出状态。正常服务由专用后台调度每轮接管一个事实域/CAT 快照，镜像和本地数据集仍须真实完整证据；平台后台总开关关闭时不接管，自动镜像开关不影响已完成镜像的认证。遇到在途写入或不合格数据按域记错并至少一分钟后重试，不造空版本；修复证据缺口后观察重试。HTTP 健康只表示服务健康，不能证明安装已完成。
4. 只读核对所有应有域均指向 PUBLISHED、实际记录/候选/下钻/下载可读取，随后恢复正常同步。查询示例：

```sql
select d.producer_kind, d.source_instance, d.fact_type, d.table_key,
       p.state, p.sequence_no, p.data_as_of, d.last_successful_verification_at,
       d.producer_retry_after, d.producer_last_error
from read_publication_domains d
left join read_publications p on p.id = d.current_publication_id
order by 1, 2, 3, 4;
```

修订唯一键已替代业务自然键，旧程序的写入冲突目标无法直接使用新结构。回退应恢复经过验证的数据库备份与配套旧发布包，不能只换旧二进制。已公开历史不原地修补；失败候选由现行中止/巡检和无引用历史回收入口清理。

## 手工事实重建提交失败

若 `POST /api/facts/rebuild` 在入队前报 `latest_mirror_run_id` 非空错误（SQLState 23502），`20261015T100047Z-c691de911db4` 的已验证缺陷在已有来源状态时也会发生；不要据“同步已成功”反复点击，也不要删状态或填假运行号。使用包含 `V20261015_01` 修复的新包，通过标准 Flyway 流程迁移后再提交；规则边界见 D-26“手工重建意图与镜像证据”。

验收先确认提交返回 FACT_REFRESH 运行编号，再跟踪该运行与必需投影完整发布；如后续报告 ODS 依赖未齐、来源变化或投影失败，按其具体失败处理，不能将本次入队修复当作全链路验收。修复不重拉 GitLab、不清库或自动重建已有事实。新包仍须通过发布门禁与目标实例升级演练，本地回归不代替现场验收。

## 事实候选中止与清理超时

`090845Z-f767474ac463` 与 `100047Z-c691de911db4` 的后端 JAR 相同，不能通过换这两个包解决候选清理问题。三类“全量候选事实构建完成”后仍需投影及原子发布；父运行 FAILED、候选 PROJECTING 不表示后台会继续发布该候选，须核对候选与当前域指针。

使用包含 `V20261015_02` 的修复包后，事实候选先逻辑中止为 ABORTED，未公开父子修订由既有维护调度独立分批清理（默认每次最多 500 个父修订）。清理失败不会撤销已提交的 ABORTED 或改写原始发布错误；重启后继续剩余批次。临时存在不可见修订属于待清理状态，不需要人工 DELETE；来源未结算意图/heads 仍由正常刷新路径处理，旧完整点继续服务。

```sql
select p.id, d.source_instance, d.fact_type, p.producer_run_id,
       p.state, p.abort_reason, p.abort_cleanup_pending,
       d.current_publication_id, p.updated_at
from read_publications p
join read_publication_domains d on d.id = p.domain_id
where p.abort_cleanup_pending
order by p.updated_at, p.id;
```

内网验收检查：发布失败日志保留最初异常与 publicationId；ABORTED 不再占活动名额；后台调度正常时待清理标识最终清空；新候选完整发布后再以当前指针确认刷新成功。若仍失败，按首个构建/投影/发布异常处理，不将最后一条 DELETE 或 autovacuum 活动单独当成根因。本地小规模回归只证明一致性和恢复边界，不证明现场清理耗时、吞吐或磁盘瓶颈。

## 用户验收矩阵

使用已有完整点的同一账号、固定来源/项目/里程碑或产品版本；同时保留主表、已开明细、规则和一个未完成下载。

| 场景 | 应验证的行为 |
|---|---|
| 每 10–15 分钟增量及事实发布 | 主表、记录、候选、BI、兼容与镜像浏览一直使用完整点；下钻可用，半成品不进入画面 |
| 凌晨全量补偿对账 | 旧代际继续可查，完整候选成功后整批换版，不按 chunk 显示半表 |
| 同步/构建/投影失败或手工中止 | 指针不移动，原列表/图表/已开明细保留，管理员可见原因，普通用户只见中性状态 |
| 刷新最新数据及加载超时 | 按钮前台等待窗口及整批准备各有五秒边界；后台继续，准备失败保留旧画面，不假报最新数据已可用 |
| 跨页下钻与父页面离开 | 目标页独立核验当前权限及来源闭包；子租约保护原完整点，不能任意转用其它页身份 |
| 页面换版期间 PNG/Excel 返回 | 丢弃旧画面的迟到文件，不把旧查询与新标题混合下载 |
| 权限撤销、退出、切账号 | 立即清除原账号私人画面，不保留旧租约授权或复用原查询 |
| 定义变化但无事实变化 | 登记完整重算/数据集发布请求，旧点继续使用旧定义；失败重试保持全量意图 |
| 同一根在新点被撤回 | 旧点下钻仍存在；整批切到新点后显示真实撤回或空集合，不显示“等待聚合” |
| 陈旧超过 24 小时 | 仅升级管理员告警，读取不关闭 |

管理表编辑、连接设置和管理员源库预览是当前控制面；业务数据浏览、正式/兼容记录及已登记镜像使用完整版本。从未有过合格完整点仍需明确提示恢复步骤，不能以零值冒充产出。

## 固定目标负载

工具 `backend/src/test/benchmarks/benchmark_published_reads.py` 只创建/释放读取租约与读取端点，不触发同步。使用隔离验收实例与真实目标规模副本，记录 CPU/内存、PostgreSQL 版本、事实/关系/镜像行数、并发用户和后台写负载。Cookie 及 XSRF token 分别由环境变量 `READ_BENCH_COOKIE`、`READ_BENCH_CSRF` 提供，不进入报告。

同一页面与来源范围，分别执行 healthy、incremental、full、failed、cancelled 场景；后台运行由验收者在隔离实例中触发。每次保留原始报告，不用重跑挑选更快结果。示例在仓库根执行，URL 必须换为获准的隔离目标：

```powershell
python -B backend/src/test/benchmarks/benchmark_published_reads.py --base-url http://127.0.0.1:18080 --page customer-issues-cc-product-issues --scope '{"topic":"cc-product","projectId":"325"}' --operation main=/api/customer-issues/records --samples 50 --warmup 5 --scenario incremental --output .tmp-logs/read-publication-incremental.json
```

输出是顺序负载的成功样本 min/median/p95/p99/max、完整来源版本、业务日及逐次失败状态；混版、HTTP/业务失败不计成快速成功，并以非零退出。租约准备和业务读取分别报告。此工具的固定样本数不代表并发容量结论；生产并发压力需使用获准的实际负载工具单独测量。

同时记录运行/任务完成耗时、保留空间、WAL 与回收影响，不能只验证“能存副本”：

```sql
select run_type, status, started_at, finished_at,
       extract(epoch from finished_at - started_at) as elapsed_seconds
from sync_runs where started_at is not null order by id desc limit 50;

select relname, pg_total_relation_size(relid) as total_bytes,
       n_live_tup, n_dead_tup
from pg_stat_user_tables
where relname in ('issue_fact','issue_fact_customer_members','merge_request_fact',
 'merge_request_commit_fact','integration_test_fact','read_dataset_rows',
 'read_publications','read_projection_artifacts') order by relname;
```

本地确定性回归和受控浏览器只证明一致性、失败保留及交互路径；没有目标负载实测不得承诺容量、发布耗时或每次刷新在五秒内完成。

## 发布门禁

先完成默认测试、前端 typecheck/lint/build、仓库门禁与差异审查。黄金仅在发布/部署验收前或用户明确要求时按 AGENTS.md 执行；运行前后处置开发服务并检查健康。此次新增详情/候选端点和 readView 协议属于实际接口变化，发布前需审核具体差异、生成新增端点用例并审核快照；不能调整掩码或手工改快照让差异消失。现有夹具、快照与掩码保持原样，未生成代表黄金门禁尚未完成。
