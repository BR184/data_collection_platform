## 进度与中间物

- 用户决定交 AI 员工执行、完成后由经理审批。执行范围/阶段/证据与审批清单统一转入[容量治理执行指令](read-publication-capacity-remediation-20261015.md)；本计划继续保留工件调查与已证机制，不作为第二轮完成证明。
- 用户要求将大数据量导致的备份/事实刷新耗时翻倍和频繁失败一并纳入处理。本轮完成整体容量方案补充，尚未实施第二轮存储/回收改造；第一轮基本修复完成不能等同容量问题解决。下一步按下文统一方案收敛变更协议、清理及验收，不继续按孤立超时补丁推进。
- 用户最新明确确认所有流程耗时、文件大小至少翻倍，本轮接受为现场事实，不再要求重复证明或以计时范围不同解释。调查转向导出内容/存储放大归因：同备份脚本下硬件读慢不能单独解释产物变大；需区分新增业务数据、审计日志、事实历史及数据集副本，压缩产物大小也不直接等于行数。核查只读，不修改业务代码/现有库。
- 备份脚本/日志归档比较完成，存储增长归因审查继续：最初截图为 141807Z 包升级前备份，中间约 476 MB、COPY sync_run_fact_targets、DataFileRead；用户后续已确认耗时与文件增长至少翻倍，不能继续写成“增长未经确认”。0930/141807 两包 backup.sh 仅部署身份不同，导出范围/格式相同；两包日志归档类逐字节一致。工件凭证 `.tmp/backup-growth-audit-20261015/`。未操作内网、服务、备份进程或业务代码。
- 本地修复及基本定向验证完成，待内网验收；计划保留为目标容量采证/恢复入口。内网存储构成和性能归因仍待采证。测试包为 `110609Z-f2dd70d0dfb6`，对照生产包 `112440Z-cccce87b68b5`。不操作内网、共享 target/服务、既有栈或黄金快照；不打包。
- 已改：独立 MigrationApplication/主入口、后台安装 Scheduler（删除安装 Runner）、逐域 ReadProducerRetryService/V20261015_03、镜像/本地数据集 worker、稳定键捕获请求与全部调用、独立串行读调度器及既有维护任务归属；升级生成器/测试和 D-26/架构/部署标准/runbook 同步。新迁移只追加一项校验和，原 155 项不变。
- 验证结果：旧实现 4 个后端反例均失败，打包器反例失败；第一轮实施回归暴露初始登记抢占正式态、测试夹具模式不一致及测试基础设施缺项，均已修正。最终隔离副本 123/0/0/0，954 个生产/370 个测试源码编译通过；10 类直接相关回归含迁移上下文、逐域恢复、事实/CAT、启用来源及真实调度隔离。日志 `.tmp/read-publication-runtime-fix-20261015/targeted-final.log`。打包器 62 项全绿；迁移失败 shell 执行验证自有容器清理/异属容器不删除；生成 backup/upgrade/rollback 均 bash -n 通过。
- 仓库检查：156 条迁移锁、破坏性迁移审查、Flyway smoke 登记、生成物/运行物位置、文本空白与 git diff --check 通过；AGENTS.md 27110 B，无需压缩。17 个受影响源码/测试/迁移文件与受测副本核对，4 个文件测试后仅补公共 Javadoc，其余逐字节一致，明细 `.tmp/read-publication-runtime-fix-20261015/source-verification.json`。未提交推送：共享树混合他人改动、远端未指定。
- 实际镜像/JAR 摘要一致：归档 `1e726a5d3f349ce0362c6ba3e9d30861e50cd33406cf355289795cde7db6ffe4`，实际 JAR `7e27fa9a0d7cc3b3d7f5466b0ba251e9c78125795aa7c495559e8b84591a79a3`。镜像逐层提取 JAR，三个数据集写入核心类与 090845Z 原包逐字节一致；6 类 javap 及校验结果在 `.tmp/read-dataset-storage-audit-20261015/`。
- 已证四类成本：全宽行 JSONB 捕获/全源判等；新代际全量复制；发布时批量区间 UPDATE；全平台共享小批次历史回收及可重复空扫描。现场 69 GB 不能直接按“有效数据+历史版本”分摊，尚无统计/采样。
- 双包实际 JAR 逐条比较：1913 个文件条目内容/名称全部一致（含 1657 个 class），无增删改；归档字节摘要不同不代表业务内容不同。生产实际 JAR `9c2b67ff34d171492af1098811113ea0dbad6e32f7dd5419af0037b7c2121a89`，归档摘要 `61c429176731c0a3a3c2515b8dc568034254fb76babc483de2c0fa383d678477`；校验 `.tmp/read-dataset-storage-audit-20261015/test-production-comparison.json`。安装 Runner 和调度配置的原包字节码已补核。

### 恢复线索

当前阶段：内网交接验收；恢复先看 `.tmp/read-publication-runtime-fix-20261015/targeted-final.log` 终态及 `source-verification.json`，不无端重跑。隔离 Maven 副本在同目录 backend。正式出包仍须按发布流程执行黄金及目标实例升级演练，本单元没有出包。前序故障边界见 [事实候选清理计划](fact-refresh-cleanup-timeout-investigation-20261015.md)。下列内网采证 SQL 尚未执行，禁止默认扫描全表或执行 EXPLAIN ANALYZE。

启动/升级现场仍需看 `Application run failed`/安装 SQL 日志与容器 RestartCount，区分尚未完成安装、候选发布重试和已退出重启；不能仅凭 HTTP health 或运行号推断。用户已授权本地修复，未授权更改生产 `.env` 或自行运行升级。

### 目标与边界

回答是否一次保存整个版本导致、哪些场景整表复制、69 GB 能否据截图归因及同步受影响链路，并落实已证的迁移/启动/失败恢复/稳定键写放大修复。成功标准为基本真实 PG 和脚本路径通过、旧完整视图稳定、其它事实触发链路回归通过。无内网写入；不关闭 vacuum、不清表、不用小库外推容量。

已核查两个包的后端/脚本、安装 Runner、调度/健康/静默迁移边界及超时范围。仅修代码中已证问题，不部署生产，不调大默认 30 秒掩盖大表扫描成本。

### 约束与背景

继续兼容模式，依赖老平台数据；D-26 的完整版本/历史读保护不能因性能问题静默取消。包名已由用户明确，不据截图迁移号推断。内网原始数据库和日志未连接；新增确定性行为回归，不做小规模容量模拟或运行黄金。

### 证据与根因

#### 升级前备份耗时与体积（新增调查）

1. 截图还在 backup.sh 的整库导出，未进入本次升级迁移。该脚本导出现有现场库，不能把这一步已有的 26 GB 归因于本次尚未执行的新迁移；此前运行/试升级产生的现有数据仍须纳入调查。完整 dump 后还有关键表 dump、内容目录和校验；最初截图只显示中间约 476 MB，当时不足以证明最终大小，后续用户已明确确认所有流程耗时和文件至少翻倍，现以此为现场事实。
2. 直接比较发布目录内 0930 与 141807 的 backup.sh：仅 EXPECTED_BACKEND/EXPECTED_FRONTEND/PACKAGE_NAME 三行不同，其他逐行一致。两者都是先 `pg_dump -Fc` 整库，再 `pg_dump -Fc --table ...` 关键表，关键表列表相同；没有新增排除项或新增第二次整库导出，sync_run_fact_targets 不在第二份关键表列表。Fc 默认压缩、此命令无并行导出，顺序扫描大表/压缩/写盘均计入耗时。1010 full 包明确用于首次空库部署、不带 backup.sh，首次空库安装与长期生产库升级不可直接比较耗时。
3. sync_run_fact_targets 是 20260731_01 引入的按轮事实目标登记表，不是 read_dataset_rows。主键包含 mirror_run_id，所以同一根跨运行可持续保留多条；D-24 已记录此前达到 26 GB，不能称此次读发布模型新引入。真实变化按稳定根登记，不宣称每次同步无变化也全表新增。
4. 清理条件是 updated_at 超过保留窗口且 head.published_version >= target.change_version；默认 30 天、每页 2000、每次最多 20 页，启动 5 分钟后首次、随后完成后延迟 30 分钟。不是同步成功立即删除，也不保证每次实际删满 4 万行；待发布/缺失 head 的行不会被该 SQL 选中。归档只 DELETE，不迁移到另一归档表。尚无现场归档成功/超时日志，不能写成“归档没运行”。
5. 从 0930 Docker 镜像层提取实际 JAR，镜像 SHA-256 `eeee94d5892231c38ef771dcccfc2f80c52d270e593b31679f18dd1cbd47d99d`、JAR `64e281b78ee5465be168a70c8540ff1f1318af69e46bdf28c4b2347284757611` 均与清单相符。0930 已含 FactTargetJournalArchiveService/Scheduler；二者 class 与 141807 清单匹配的实际构建 JAR 逐字节相同（后者 JAR `2b7ec0ff0339de587f427579591015df8ea553e2b8326b058968e0d6b1193519`）。0930 application.yml 也已声明上述 30/2000/20 默认值。不能仅按提交时间断言旧包没有归档；旧包来源为 dirty 工作树。
6. DataFileRead 表示采样时等待读关系文件，不是锁等待；不能单凭它证明持续前进、IO 健康或预计十分钟完成。pg_stat_progress_copy 的 bytes_processed/tuples_processed 两次增长才是当前 COPY 推进证据，仍不预测后续表时长。
7. 物理表占用、实际有效行和压缩备份大小必须区分。活跃审计日志/尚未应用删除的历史修订进入逻辑备份；索引页、死元组和空闲页不会按原物理文件复制。膨胀的 heap 仍可增加导出扫描成本，但不能据物理 26 GB 预测成品 26 GB；索引占用不等于 COPY 会读取全部索引。普通 VACUUM 不保证文件回缩。备份不停平台不等于资源零影响，磁盘 IO/压缩 CPU 仍共享。

原始机制：[pg_dump](https://www.postgresql.org/docs/16/app-pgdump.html)、[IO 等待](https://www.postgresql.org/docs/16/monitoring-stats.html)、[COPY 进度](https://www.postgresql.org/docs/16/progress-reporting.html)、[VACUUM](https://www.postgresql.org/docs/16/routine-vacuuming.html)。现场只读采证先执行下列目录/统计查询，不做全表 COUNT、清理或 VACUUM FULL：

```sql
begin read only;
set local statement_timeout = '5s';
select pid, relid::regclass as relation, command, type,
       bytes_processed, tuples_processed
from pg_stat_progress_copy where datname = current_database();

select relname, pg_size_pretty(pg_relation_size(relid)) as heap_size,
       pg_size_pretty(pg_indexes_size(relid)) as index_size,
       pg_size_pretty(pg_total_relation_size(relid)) as total_size,
       n_live_tup, n_dead_tup, last_autovacuum, last_autoanalyze
from pg_stat_user_tables
where relname in ('sync_run_fact_targets', 'read_dataset_rows')
order by relname;
commit;
```

进度查询另开事务间隔采样；统计行数是估计不是精确值。随后核对该实例归档日志、配置是否覆盖默认保留期、最终 dump 大小/总耗时和同实例旧备份，才选择日志保留/膨胀治理。不得跳过完整灾备或据截图主动删审计/历史数据。

补充截图判读：内网 AI 转述磁盘 28–54 MB/s、util 73–99%、heap 11 GB、buff/cache 58 GB、kswapd 活跃和 iowait 22%。这些是 IO 受限线索，不足以确证物理带宽上限或严重内存抖动；11 GB heap 与前次 26 GB 若为 total，则只是口径不同，未见原 SQL 不认定表已缩小。需修正下列推论：

- 若“20 秒十万块”来自 PG 的 blks_read/heap_blks_read，默认 8 KiB/block 对应约 39 MiB/s（40.96 MB/s），不是 5 MB/s；应先确认计数来源和 SHOW block_size。PG 读块统计还可能命中 OS 缓存，不能等同物理设备字节或单一备份进程读量。
- buff/cache 高本身不证明可用内存不足；看 MemAvailable、连续 si/so 及回收指标。仅 swap 已用 117 MB/kswapd 活跃不足以证明抖动。
- util 接近 100% 对串行设备是饱和线索，对 SSD/RAID 等并行设备不直接代表性能极限；还需 await/aqu-sz、设备/虚拟化限额及基准。截图命令 iostat -x 1 3 的首屏是开机以来平均，不能混入当前区间比较；可用 iostat -y -x 1 10 只采短时区间，不做磁盘压测。
- “备份后所有数据都在 shared_buffers、升级一定更顺”没有依据。OS 文件缓存与 PG shared_buffers 分开，大表扫描的批量读缓存策略复用有限缓冲；被备份过不代表全部数据/索引常驻。备份、迁移、运行接管访问对象也不同。

来源：[块与 OS 缓存统计](https://www.postgresql.org/docs/16/monitoring-stats.html)、[块大小](https://www.postgresql.org/docs/16/storage-page-layout.html)、[Linux 内存字段](https://docs.kernel.org/filesystems/proc.html)、[iostat 手册](https://man7.org/linux/man-pages/man1/iostat.1.html)、[PG16 缓冲策略](https://github.com/postgres/postgres/blob/REL_16_STABLE/src/backend/storage/buffer/freelist.c)。只采进度/IO 区间和真实配置，不据截图干预进程、修改内核/数据库参数或承诺剩余分钟数。

用户已明确旧版 10–12 分钟包含备份和升级全过程，且所有流程耗时、文件大小至少翻倍；计时范围与增长事实均已确认，不再要求重复证明，也不以缺少历史文件为由停止分析。此为现场历史对照，非新增容量 SLA，不能用内网 AI 所述上一轮 16 分钟替换。每次全库备份解释不了新增耗时；当前 COPY 发生在本次迁移之前，须查现场已有的数据。历史已见 26 GB 不能证明单一表最近增长，但总产物增长已成立；压缩逻辑产物增大不是单纯 IO 慢可造成。后续按新增副本、事实历史及日志/缓存归属收敛，补表级尺寸/版本分布仅用于量化各自贡献，不再要求用户证明是否翻倍。

#### 文件增长确认后的代码归因边界

- 对照清单匹配的 0930/141807 JAR 迁移：旧包没有 read_* 模型，后者新增 17 张 read_* 表（脚本正则清单仅用于识别新增模型，非现场已落地行数证明）；记录 `.tmp/backup-growth-audit-20261015/read-storage-migration-comparison.json`。
- `MirrorDatasetPublicationWorker` 取 select *，`ReadDatasetRepository.captureRelation` 转完整行 JSONB 写入 read_dataset_rows；原 ODS/业务编辑表保留。即使历史完全回收，仍有当前完整数据集这份额外存储；不宣称每轮都复制所有行或所有数据必然翻倍。前轮修复消除了有键 FULL_RECONCILE 强制新 epoch，没有取消首次/无键复制或当前副本。
- V20261012_02 将事实自然键唯一改为按创建发布的修订唯一；全量事实候选新 epoch，旧公开修订与新候选并存，发布后旧历史依引用/宽限回收。小批量全平台 GC 不保证赶上高变化/反复全量的产生速率。三类事实表及其历史都是 pg_dump 可见行，而非会被逻辑导出自动过滤的死元组。
- 关键表列表核对：critical-tables.dump 排除 read_dataset_rows 和 sync_run_fact_targets，但包含三类事实表、ODS 议题/MR、统计/页面快照。按所有备份文件均增大的现场事实，不能只把增长归给前两张大表；优先审查两份备份交集中的事实版本、ODS 与缓存内容。记录 `backup-content-boundary.json`，此为可确定的排查收敛，不等于已量出事实历史贡献。
- 结论：模型确有额外副本、历史修订和保留/回收成本，不能称方案在容量上已无问题；同时不能将全部现场翻倍直接归于某一路径。后续方案须保持已确认的完整读/历史保护，在生产量级确认存储增量与回收收敛后再改，不用单纯加硬件或直接删历史替代根因治理。本轮没有新增实现或运行容量测试。

截图称 150 万行为 69 GB（含索引/TOAST），临时关闭 read_dataset_rows 和 issue_fact 的 autovacuum 后构建提速；这是两表同时干预的现场转述，不能单独确认 read_dataset_rows 的贡献，也不能区分有效数据、历史、死元组和可复用空闲页。

#### 原包与源码逐项结论

1. **职责**：`V20261013_02` 创建本表，早于 `V20261013_12`，不能把后者当成首次引入版本。保存 MIRROR_TABLE 及兼容/原生数据集的行修订；三张事实主表另有自身修订，`IssueFactRecordRepository` 等直接查询对应事实，不是“所有看板和事实都从本表读取”。`DatabaseBrowserService:176–195` 对注册镜像表使用完整数据集身份，镜像工作器的取数没有按当前正在看的页面筛表。
2. **复制范围**：`MirrorDatasetPublicationWorker:46,72–75` 将 FULL_RECONCILE 映射 full_epoch，请求 `select * from ODS表`，忽略列清单为空；完整行包含源内容及镜像元数据，不会裁掉原表中的大文本/JSON/差异载荷。`DatasetProducerCatalog` 另捕获兼容走查、评审及原生关系，与事实修订表分开。
3. **不是每轮必复制**：`DatasetPublicationService:51–60` 先判模式/定义和全源业务多重集合一致，完全相同可复用旧版本，即使 full 请求也不强行复制；同代际有稳定键只插新增/变化行并登记删除。首次/模式变化/无稳定键须新 epoch；FULL_RECONCILE 有变化且需产出新版本时也强制新 epoch，哪怕只改一行，`ReadDatasetRepository:53–123` 的单条 INSERT SELECT 就插整个来源关系。逻辑“全量对账输入完整”与物理“必须重建全部行版本”被绑定，存在明确写放大路径。
4. **零/少变化仍有全源成本**：`matchesCompleteSource:208–231` 两侧完整 JSONB 分组并 FULL JOIN；捕获再做全源 JSONB 转换/NOT EXISTS 比较、源行 count 和完整闭包核验。它不是以 ODS 变更键/版本为起点的捕获。失败时 worker 无按表退避/失败游标，下轮可重选同一最早表并重做。不能把扫描全源等同每次写入全源，但两者都是独立成本。
5. **发布也有大批量更新**：`publishRevisions:155–168` 新 epoch 先关闭整批旧行，再打开整批新行；同 epoch 关闭变化对应旧行再打开新行。语义原子，但“大表发布仍是短事务”的耗时预期尚无目标容量证明。原注释实际是“最终发布另用短事务，不在发布事务复制全表”，不是“仅切指针”；前一次口头概括已撤回。可见区间列参与索引，更新会造成堆/索引维护；行触发器每次 INSERT/UPDATE 均执行校验。不能因此声称每次区间 UPDATE 再完整复制一份 JSONB/TOAST：PostgreSQL 对未变化的外置大值通常复用原 TOAST，具体触发器比较/解压成本仍需实测。
6. **历史回收吞吐预算**：默认宽限 15 分钟；当前指针、活跃视图、候选基线/跨域依赖和读取锁仍保护旧版本。维护每 5 秒延迟调用，每轮至多一个域、最多共 500 行修订，并非每表每轮 500；SQL 和其它维护用时还会增加间隔。若全局仅有 150 万行已经满足删除条件且独占预算，纯调度预算约 4 小时 10 分钟（150万÷500×5秒），这只是理论最乐观量级，不是内网性能预测，不能拿总表 150 万行直接作为可删行数。
7. **重复空回收风险**：旧创建者版本可能因当前版本复用其未变行而长期保留 RETIRING；`countCreatedRows>0` 不删元数据，`collectOneDomain` 又按历史状态选择该域；`deleteUnreferenced` 的区间 NOT EXISTS 检查可持续返回 0。机制上可重复检查整片 epoch，不能据此断言内网实际顺序扫描，须 EXPLAIN 和调用/删除计数。不是简单提高 gcBatchSize 就能证明消除。
8. **VACUUM 边界**：应用按版本保留规则执行 DELETE，VACUUM 才处理数据库死元组；它不认识应用的 visible_to，也不主动删仍合法存活的历史修订。普通 VACUUM 通常把空间留给本表重用，不保证总文件回缩。截图缺 heap/主索引/TOAST/TOAST索引分解，69 GB 还可能含回滚/更新/已删行留下的空间，不能只用可见行数与平均 JSONB 相乘解释。
9. **与事实/同步的耦合**：事实构建主要读 ODS 并写事实主表，镜像数据集独立生产，但共用 PostgreSQL 的磁盘、缓存、WAL、连接及调度资源。故本表维护/发布能拖慢事实构建，不代表事实必须等本表所有版本生成。两表 autovacuum 同时被停且缺等待采样，现有截图只能作为资源竞争线索；未见 524 SUCCESS，AI 的 5–8 分钟预计不是终态证据。

PostgreSQL 原始事实：[VACUUM](https://www.postgresql.org/docs/16/sql-vacuum.html)、[TOAST](https://www.postgresql.org/docs/16/storage-toast.html)。这是外部机制说明，不是目标实例已经测出的成因。

#### 新截图：同步、启动与升级边界复核

1. **双包业务内容相同**：两个包均从镜像归档提取 JAR，并校验归档、镜像及实际 JAR 摘要；1913 条文件内容逐条一致（含 1657 类和全部迁移）。不能只据共同 commit/dirty 工作树作推断，本次已有工件证据。upgrade.sh 的执行逻辑相同，差异不止目标镜像标签，还包括包名、各自允许的直接基线镜像和归档文件名；compose 的固定容器名与 project 作用域命名也不同。不能据“业务相同”互换部署身份。
2. **启动主路径做大表生产（高优先级）**：原包 `ReadModelInstallationRunner.run:37–50` 是无异步隔离的 ApplicationRunner，依次执行事实接管、本地数据集发布、全部完整镜像表安装，再处理 CAT。`BackgroundSchedulingConfiguration` 仅条件化 EnableScheduling；Runner、Worker Bean 本身未被该开关禁用，直接调用不受 @Scheduled 注册与否影响。故 `PLATFORM_BACKGROUND_JOBS_ENABLED=false` 仍可执行数据集/事实安装 DML。现有离线标准要求静默迁移，这属于实现与验收契约不一致，不能通过弱化标准或只比行数掩盖。
3. **迁移阶段会重复触发上述工作**：upgrade.sh:347–361 先关闭调度重建一个后端，健康/行数检查后再重建正常调度后端，两次启动都会进入 Runner。首次接管写事实修订列等操作未必改变事实行数，而 read_dataset_rows 不在守恒表清单；counts.diff=0 不能证明无业务生产。已有当前事实域可跳过其接管，但镜像表安装方法仍会每次调用，不能称全部数据集“一次安装之后就不再扫描”。
4. **恢复候选失败可升级为启动失败（高优先级）**：`MirrorDatasetPublicationWorker:40–42` 的 prepared 候选恢复发布在 try/catch 之外；后半段新候选 prepare/publish 才被 catch。若恢复发布抛 QueryTimeout 等异常，经 installCompleteTables→Runner 直接逃逸，Spring Boot 启动失败；backend 配置 restart:unless-stopped 可重启并再次遇到同一 prepared 候选。此路径已由原包字节码确认，现场是否触发要查 Application run failed 与 RestartCount，不能写成已观察事实。
5. **重试需要分路径描述**：安装 for 循环次数是 sync_run_table_states 的数量，有上限；但失败的最早表没有前进游标/逐表退避，多次循环可重复处理同一表。定时镜像发布会继续重新挑选。不能统称“主线程无限循环”；本地 DatasetRefreshWorker 则已有一分钟 retry_after，不可把镜像工作器的问题推广到所有发布 worker。其 catch 内清理若再失败仍可逃逸，且数据集中止不在 V20261015_02 的事实清理范围。
6. **健康与完成证据不闭合**：脚本等待 Docker health（普通 /actuator/health），没有单独核验 Runner 或各域完整点的安装终态；代码也没有把所有安装结果绑定到该 health。安装可 WARN 后继续返回，所以即使 Runner 完成/应用 ready 也不自动证明每个数据集都完整。Spring Boot 生命周期区分上下文启动和 runner 完成后的 readiness，但不能用它替代业务完整点证据。反向也不能断言 Runner 一直执行就必然使普通 health 为 DOWN，需现场实际 health 响应和状态时间线。
7. **900 秒不是 guaranteed grace**：升级 wait_healthy:331–340 一见 unhealthy/exited/dead 立即 fail，900 只是仍处于可等待状态的外层上限。Compose 后端 healthcheck interval20s/timeout5s/retries12/start_period60s 是另一套判据，可能先进入 unhealthy。升级脚本自身没有失败自动数据库回滚逻辑，截图所谓测试“已回滚”须依具体外部/人工处置证明，不能据函数名补推论。
8. **30→600 只能是有条件缓解**：该变量对应 spring.jdbc.template.query-timeout，作用于默认 JdbcTemplate，不等同所有 MyBatis/外部数据源 SQL。放宽可让部分超过30秒的SQL跑完，也会允许安装主路径、默认前台查询/恢复SQL占用更久；既不改变全源成本、失败选择策略、开关边界，也不改 Docker unhealthy 立即失败。因此不能直接批准为生产修复方案；若临时试验，需已识别 SQL 的真实耗时和单独运行策略，不能把 600 写成已验证安全值。
9. **截图两条错误归因**：V20261015_02 仅增加发布元数据列、CHECK 和部分索引，没有大表 DELETE/重建；正常事实完整发布更新区间并移动指针，物理历史回收独立有界，事实失败候选按新标识后台分批清理。根清单/输入/缓存仍有自身元数据释放操作，不能把这些等同整批 issue_fact DELETE。旧问题未完全消除，但“现在 all 收尾仍必须整批删除事实表”不符合该包。

初步修复顺序（仅建议，未实施）：明确仅迁移模式并禁止业务安装/生产副作用；正常启动仅登记可恢复、有进度的持久生产意图；修复 prepared 恢复异常边界/逐表让位与退避；分别定义进程健康、迁移完成和域完整点状态，保持旧完整点可读；再按前文治理写入与回收预算，最后按职责设置查询超时。验收须增加“关闭调度/迁移模式零业务副作用、恢复超时不拖垮应用、重启不热循环、数据未就绪状态准确”确定性用例，再做目标负载验证。

此前本单元 143 项及员工黄金 204 项分别锁定一致性/冻结产出，未覆盖上述安装开关契约与目标大表容量，不能据全绿直接为这些边界放行。生产33GB本身也不能替代最大表宽度、首次安装新增空间、变化率和磁盘延迟证据。Spring Boot 机制说明：[Application Availability](https://docs.spring.io/spring-boot/reference/features/spring-application.html#features.spring-application.application-availability)。

#### 最小内网取证（先目录，后采样；均未在内网执行）

目录阶段不读取宽 JSONB，不对大表 count/group by：

```sql
begin read only;
set local statement_timeout = '10s';

-- pg_table_size 含 TOAST/FSM/VM，主堆仅用 pg_relation_size 表示主 fork；各列不可相加重复计数。
select c.relname, pg_size_pretty(pg_relation_size(c.oid)) as heap_main,
       pg_size_pretty(pg_indexes_size(c.oid)) as main_indexes,
       pg_size_pretty(pg_table_size(c.oid)) as table_including_toast,
       pg_size_pretty(pg_total_relation_size(c.oid)) as total,
       pg_size_pretty(pg_total_relation_size(c.reltoastrelid)) as toast_including_indexes,
       c.reloptions, s.n_live_tup, s.n_dead_tup, s.n_tup_ins, s.n_tup_upd, s.n_tup_del,
       s.last_autovacuum, s.autovacuum_count
from pg_class c left join pg_stat_all_tables s on s.relid = c.oid
where c.oid = 'public.read_dataset_rows'::regclass;

select c.oid::regclass as toast_relation, c.reloptions,
       s.n_live_tup, s.n_dead_tup, s.last_autovacuum, s.autovacuum_count
from pg_class main join pg_class c on c.oid = main.reltoastrelid
left join pg_stat_all_tables s on s.relid = c.oid
where main.oid = 'public.read_dataset_rows'::regclass;

-- 捕获的逻辑完整行数可能复用旧修订，不能累加为实际物理行数。
select d.producer_kind, d.source_instance, d.table_key, p.state, p.published_at,
       t.captured_row_count, p.epoch_id
from read_publication_domains d
join read_publications p on p.id = d.current_publication_id
join read_dataset_tables t on t.publication_id = p.id
order by t.captured_row_count desc limit 30;

select d.table_key, p.state, count(*) as publication_count,
       min(p.created_at) as oldest, max(p.created_at) as newest
from read_publications p join read_publication_domains d on d.id = p.domain_id
where d.fact_type = 'DATASET' group by d.table_key, p.state order by 1, 2;

select pid, backend_type, wait_event_type, wait_event,
       pg_blocking_pids(pid) as blockers, query_start, left(query, 300) as query
from pg_stat_activity
where datname = current_database() and pid <> pg_backend_pid()
  and (query ilike '%read_dataset_rows%' or backend_type = 'autovacuum worker');
commit;
```

目录阶段确认 TOAST/索引大小和最大来源后，再在允许窗口做低比例块采样（统计近似，不扫描 JSON 内容），区分当前/历史/未发布行及来源宽度；需要精确数再定受控查询：

```sql
begin read only;
set local statement_timeout = '10s';
select r.table_key, case when r.visible_from is null then 'unpublished'
         when current_point.epoch_id = r.epoch_id and r.visible_from <= current_point.sequence_no
          and (r.visible_to is null or current_point.sequence_no < r.visible_to) then 'current'
         else 'history' end as row_class,
       count(*) as sampled_rows, avg(pg_column_size(r.row_payload)) as sampled_payload_bytes
from public.read_dataset_rows as r tablesample system(0.05) repeatable(20261015)
join read_publication_domains d on d.id = r.domain_id
left join read_publications current_point on current_point.id = d.current_publication_id
group by 1, 2 order by 1, 2;
commit;
```

采样仅用于方向；`pg_column_size` 不是整表占用，也不能从活元组样本量算死元组/空闲页。同步阶段另保留运行号、表级任务策略、失败/重试的 SQL 和实际时长、回收每轮删除量、磁盘吞吐/延迟；EXPLAIN 先不加 ANALYZE，避免再触发大表扫描。

### 方案与步骤

#### 下一轮容量治理方案（本轮提出，尚未实施）

目标：在兼容模式及完整读/在用历史保护不变的前提下，将同步→事实→投影→历史回收→完整备份作为一条生命周期治理；用户确认的备份/事实刷新耗时至少翻倍且经常失败是待解决问题，旧版含备份全流程 10–12 分钟是历史对照，不擅自写成硬性 SLA。不能仅以查询能返回、基础测试通过或延长超时宣布完成。

1. **减少产生量与全源工作**：有稳定键的数据集由真实变更证据驱动捕获/无变化认证，减少每轮完整 JSONB 分组判等与重复全源扫描；首次、模式/键变化和无键输入单独设计有界捕获及失败续跑。跨批必须保持冻结输入证明，不能用分页混合来源版本。事实重建需区分真实业务/计算定义/计算时点变化与无效重复触发；更改“全量新 epoch”以复用事实属于 D-26 模型调整，先设计完整证明和迁移，不直接拼补增量到全量。缩减镜像历史字段/范围、改旧视图保留策略涉及产品取舍，另行确认，不能偷删字段。
2. **让回收具备收敛能力**：先将只有存在性需求的 countCreatedRows/countRevisionsCreatedBy 完整计数替换为有索引支撑的存在性判定；核查当前版本复用旧创建者修订导致的重复零删除扫描，设计按引用/版本变化推进的游标或重查边界。按真实积压及单批行数/字节/时间预算规划回收，保留现有域→版本锁序、当前/活跃视图/候选基线保护和精确父子关系删除。当前默认每周期一个域、500 行以及读生产/维护共用专用串行调度并不构成吞吐保证；需证明长捕获不会让回收持续得不到执行，不能仅增大线程或批次参数。
3. **分开治理现有存量与后续增长**：先止不必要新增，再用正式、可恢复的清理流程收敛已无引用的历史/失败候选；对仍被合法引用的数据保留。审计日志按独立保留与发布条件处理。删除应用历史、数据库死元组回收、物理文件回缩是不同步骤；若需物理整理，另定窗口/空间预算，不主动 TRUNCATE、删当前版本、关闭 vacuum 或运行 VACUUM FULL。上轮代码不会自动让已有 69 GB 物理文件缩回原大小。
4. **备份与恢复纳入同一验收**：保留全库/关键表备份及原有校验契约，不跳过大表来缩短时间。先比较两份备份的内容交集：关键表 dump 不含前两张大表，必须同时覆盖事实修订、ODS、统计/页面快照的增长。记录完整备份字节、分阶段耗时及实际恢复可用性，再评估备份流程的重复工作或格式选择；改变备份格式/恢复流程须单独审查，不能只比较生成速度。
5. **相称验证与目标验收**：本地只跑直接相关正确性/失败恢复/历史保护回归；目标容量留给内网真实库或同规模隔离副本，不做小数据容量外推。场景至少包含普通增量、多轮全量重建、零变化重跑、失败/重试、并行页面读取、历史清理、完整备份和恢复。分别记录各表当前/历史/候选的行数与字节、增量产生/回收速率、积压年龄、阶段耗时和首个失败 SQL；稳定业务集连续运行后可回收积压应收敛，备份/刷新耗时对照旧版基线解释差异。活跃读保护、发布原子性和备份恢复结果同时成立才可验收，不能用“库大正常慢”代替。

当前确定的具体切入点：ReadDatasetRepository.matchesCompleteSource 全源 JSONB 分组；captureRelation 的完整来源捕获；ReadPublicationService 发布可见区间批量更新；维护中的全量 COUNT 存在性判断、重复零删除域扫描及全局共享删除预算。方案尚未将它们各自的现场耗时占比量化，不伪造比例或承诺特定分钟数。需改变数据可见性/历史策略/全量协议时先提交可审阅的具体设计；纯 SQL 存在性优化和无意全源工作可以在原契约内独立实施。

#### 已授权实施方案与验收

1. 增加独立 `MigrationApplication`（不扫描业务组件、无 Web），正常主入口依据 `PLATFORM_RUN_MODE=service|migrate` 选择；迁移完成后关闭上下文并以进程结果交付。升级脚本改 `compose run --rm --no-deps -T` 单次迁移，不把服务 health 当迁移成功证据，不使用守护容器重启策略。核验 Flyway/守恒/PG 身份后才启动服务；普通服务健康失败打印诊断，仍保留明确退出/超时边界。
2. 删除同步执行大表工作的 ReadModelInstallationRunner，替换为后台安装调度；每轮有界选择尚未接管的事实/CAT 域，失败持久记录退避，不占启动主线程。DatasetRefreshWorker 去掉 ReadyEvent 写入，在正常调度内幂等登记当前启用来源。读生产/维护共用具名单线程 readPublicationScheduler；显式保留默认 taskScheduler，避免 Spring 唯一调度器回退造成同步派发被占用。固定数量的 fixedDelay 周期无累计工作提交。迁移入口完全不装配业务类，后台关闭也不触发安装。
3. 新增只涉及域控制元数据的迁移：按域持久 producer_retry_after/last_error。镜像 prepared 与新捕获路径统一捕获异常并按域退避；后续表可继续，进程重启不丢退避。原有本地请求租约/一分钟退避沿用，清理二次失败不丢首错或绕过请求失败记账。
4. 解除完整对账与物理新 epoch 的绑定：删除内部 DatasetPublicationRequest.fullEpoch；仅首次、真实模式/键变化或无稳定键开启新代际。稳定键完整数据集仍生成增删改证明、复用未变行，旧视图保持一致。不静默忽略字段，不改业务显示口径。
5. 先用旧实现反例确认启动/恢复异常/全量复制问题；在隔离副本完成迁移上下文零业务 Bean/零业务写入、后台安装失败恢复、镜像逐域退避、旧视图与稳定键复用、打包脚本执行契约及相关既有回归。只跑必要定向，不跑完整/黄金；内网大表容量与首次全源比较/捕获成本继续按证据验收，不宣称由本轮全部消除。

接口：新增环境变量 PLATFORM_RUN_MODE（service 默认；migrate 为单次仅迁移）；内部请求移除 fullEpoch 并同步全部调用/测试。迁移元数据追加校验和，旧迁移零改动。

1. 定位镜像及兼容/原生数据集生产者，核查全源字段、增量复用及触发条件。
2. 核查发布的可见区间 UPDATE、负载不可变触发器和 SQL/事务规模。
3. 核查历史回收预算、保留引用、VACUUM 与实际文件大小的边界。
4. 制品类摘要核对；补内网分阶段只读采证 SQL，避免先扫描 69 GB 表。
5. 调查后按已授权方案实施并验证；尚待容量证据的进一步优化不纳入已完成声明。

### 决策记录

不把版本化本身等同于每轮全量复制；不把 vacuum 当作历史数据回收器；先测存储构成和生产/回收速率，再选择缩减数据集、增量捕获、索引或分区。用户已授权本轮基本修复，不扩展到未经测量的目标容量治理。

第一轮已完成完整对账与强制物理新 epoch 解耦、镜像持久退避及启动/迁移边界修复；未完成的容量治理统一按上节方案推进，不重复列为未来工作。缩减镜像浏览历史范围、拆宽载荷或分区须结合业务闭包和目标采证，不能直接删表或撤销历史读保证。纯镜像元数据应与业务语义区分，但 mirror_deleted 的删除含义不能一并忽略；源 UPSERT 已有业务列 DISTINCT guard，不宣称每轮不变行都仅因同步时间戳复制。

### 接口契约

无新增业务 API。新增环境变量 PLATFORM_RUN_MODE（service 默认/migrate）；内部 DatasetPublicationRequest 删除 fullEpoch；V20261015_03 给 read_publication_domains 增加 producer_retry_after/producer_last_error。保持完整发布和在用历史读保护，旧迁移不变。

### 风险与假设

宽 JSONB、同字段不同镜像元数据、全量对账新 epoch、发布 UPDATE 的物理放大及回收吞吐均待定点核对；不能先把这些可能性写成现场全部 69 GB 的确定成因。源写入已有业务列 DISTINCT guard，不能预设仅同步一次就让所有不变行的镜像时间戳变化。
