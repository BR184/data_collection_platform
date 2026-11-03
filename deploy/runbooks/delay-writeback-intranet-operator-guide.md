# 延期标签写回内网测试操作报告

适用日期：2026-10-27。用于在空闲端口新建一套平台和 GitLab，导入少量生产来源样本后验收延期标签写回。现有生产实例继续运行。

## 0. 本轮审批与实际流程

### 2026-10-28 旧平台停写包审批

旧原版与新平台分别连接相同样本的独立GitLab完成对比，并验证旧停写仍采集、新平台自动写回的接替形态；V1/V2/V3及三项返修经经理独立复核通过，批准用户自行部署旧停写包。使用 `crowncad-spider-stopwrite-20261028.tar.gz`，归档SHA-256 `acddae535dbc5607bf99073e411ea4bcd10c9032af7af9fb0a6705638428677a`；内JAR为 `crowncad-spider-0.0.1-SNAPSHOT.jar`，SHA-256 `95b544f178daa4805dd0988fd2ecc35429c9d894dca9c0f428e26617164dbc0b`。与原版相比仅中央停写实现类不同，其余类、配置、145份静态资源相同。

部署顺序：停止 `crowncad-spider` 服务并确认进程退出 → 备份现场JAR/XML → 同名替换及校验 → 启动检查采集 → 确认旧延期写回停用后开启新写回。回退恢复现场备份JAR，期间新写回始终关闭；再次切回新写回前必须先停用旧写回。保留对比中单列的多评论计划取值、数字天数、状态匹配及关闭手工清标差异，本轮不修改规则。

三项返修已收口：测试兼容连接覆盖到本地并复跑收敛；首次全量后无时间水位导致前置刷新拒绝、随后定时增量自动恢复的首错已记录；包内回退说明已消除双写歧义。历史测试曾尝试生产Mongo地址并超时，现有证据未显示成功连接或生产写入。经理证据在 `.tmp/legacy-delay-review-20261028/APPROVAL.md`；员工交付位于 `D:/projects/dcp-delay-legacy-compare-20261028/.tmp/legacy-delay-compare-20261028/delivery/`。

### 2026-10-27 空环境测试套件审批

用户确认本轮只验收这条使用链路：空GitLab → 近期小样本只读导出 → 导入测试GitLab → 空平台连接测试GitLab → 验证延期标签写回。该本地链路经员工执行、经理独立复核通过，可以准备内网测试；不等于批准生产平台接替写回。

本次使用 `dcp-delay-acceptance-20261027-7e7582af.tar.gz`，1,577,271,993字节，SHA-256为 `2df687f1406e990e03395cb01425868b29471cd0fd0e7d480d01593350a9d07e`。原327c8b20套件有首次启动认证等问题，现场改用此修正版。经理回读16个归档文件、46个证据文件哈希一致；复跑工具与套件辅助测试52项通过，本地源库导出前后业务内容、序列、定义摘要一致。首次全量30/30、事实6/6成功；61个正常写回作业成功，1个故意使用read_api令牌的负例DEAD保留，关闭/重开统计排除及第二轮收敛有本轮证据。

清单已知勘误：`packagingBranch` 应为 `codex/delay-kit-e2e-local-20261027`，制造提交为 `7e7582afcfb97467912b912ca7d2ab8c936dd95e`，双远端回读一致。接受该分支文字误写，以归档SHA、实际提交及本说明核对身份；不修改已测归档，也不为此重复业务测试。包内未包含后续4个说明提交，令牌权限、关闭议题及旧平台停写说明以本报告和经理随包补充说明为准。

本轮在Docker Desktop使用凭据卷适配入口，不声称原生Ubuntu完整流程已验证；现场先完成空环境启动和数据库连通检查，再运行生产源只读取样。E10缺少重启前PENDING/WAIT证据，S11截止时刻和其它未提供证据的机制用例不标为本轮通过，不扩大本次使用链路验收。未修改应用或同步业务代码，不要求重复默认套件和黄金。

内网按以下顺序执行即可，细节见后续对应章节：

1. 内网AI或运维校验包、选择空闲端口，部署新平台和空GitLab；不操作生产平台。
2. 你在内网本地提供源GitLab PG连接信息及现有只读账号密码，先做元数据预检。版本/结构不支持即停止。
3. 选择近一至两个月的一小部分议题，固定ID或时间窗及上限，执行 `transfer.sh source`。源端只查，不建账号、表、索引、触发器或修改配置；不需要生产GitLab PAT或服务器SSH权限。
4. `transfer.sh target` 只对这套空测试GitLab初始化和导入。它会写测试库，不能指向生产；首次流程不使用replace-existing。
5. 导入后创建测试GitLab的api权限PAT。平台的数据库和API均指向新GitLab，先全量/自动增量，再开测试写回，人工核对加标、摘标、无关标签及第二轮收敛。
6. 保存结果，关闭测试同步/写回并停止这套测试容器，保留卷和镜像。

源导出器使用单连接、REPEATABLE READ READ ONLY，并校验只读状态；源端没有INSERT/UPDATE/DELETE、DDL或序列操作，结束和异常均回滚关闭。导入器单独承担测试库的创建标记、插入、配置更新和序列调整。只读仍有查询/I/O/快照负载，不能承诺生产零性能影响；不为测试改变生产库，也不在生产复现本地全库dump对比。

## 1. 先回答执行与账号问题

**脚本需要在内网服务器执行，但不要求你本人逐条操作。** 你可以交给有该服务器 Docker 权限的内网 AI 或运维人员执行，由你确认源库身份、取样范围和验收结果。外网开发端不能替你连接内网生产库。

**生产数据库密码不需要发给外网 AI，也不进入交付包。** 在内网服务器本地填写只读账号的连接信息和受限密码文件即可。部署空环境不需要生产凭据；只有源库核查和经批准的取样会使用它。

| 所需信息 | 谁提供 | 在哪里使用 | 是否需要生产凭据 |
| --- | --- | --- | --- |
| 服务器地址、Docker 权限、五个空闲端口、磁盘/内存余量 | 你或内网运维 | 部署新实例 | 否 |
| 新平台管理员密码、平台库密码 | 初始化脚本随机生成 | 新平台登录、平台自己的 PostgreSQL | 否 |
| 新 GitLab root 密码、目标库密码、归属令牌 | 初始化脚本随机生成 | 新 GitLab 管理、目标初始化和导入 | 否 |
| 源 GitLab PostgreSQL 主机、端口、库名、只读账号、密码及 SSL 要求 | 你或数据库管理员，仅本地填写 | 元数据核查、批准后的限量导出 | 是，只读 |
| GitLab 生产服务器 SSH、生产 GitLab PAT | 本流程无需提供 | 不使用 | 否 |
| 测试 GitLab PAT，需 api 权限 | 导入后在新 GitLab 创建 | 新平台向测试 GitLab 加摘标签 | 否，必须为新测试令牌 |

能连接生产 PG 不代表可以写入或可以无限读取。只读事务仍会产生查询、I/O 和快照保留负载，取样参数须先审阅。

## 2. 交付版本与边界

套件清单 `KIT-MANIFEST.json` 是本次实际镜像、源码提交和文件摘要的索引。套件名为 `dcp-delay-acceptance-*`，属于独立验收辅助套件，不能用作20001/20101的更新包。

- 平台基线：`qaflex-update-20261021T011723Z-d52affe66b4f`，包内源码 `bdb93267`。
- 延期候选：`d3d86b96`，实际业务代码止于 `9daf6c77`。前端直接使用 d52 稳定镜像，未加入主树未完成的表单系统。
- 样本工具：`2e393fca94621d2d8912d2ce6868b1e57ae843d7`，业务修复为 `e5fa543e`。四份脚本逐字节固定，不使用主树同名旧脚本。
- 目标 GitLab：CE `16.11.10-ce.0`。源库必须通过工具的迁移版本、表结构和权限检查，导入还会严格比对源目标 schema 指纹。其他版本先停下，不改清单绕过。
- 此环境只验客户问题的 GitLab 同步与延期写回。旧平台 MySQL/Mongo 兼容页面和生产容量不在本次空环境验收范围内。
- 测试启动前仍须把兼容模式MySQL/Mongo/旧API及其它已配置数据源覆盖为本地测试地址或封闭出站；仅更改GitLab同步目标不会覆盖兼容后台任务的独立连接配置。
- 包内没有生产数据库、样本正文、生产配置或生产密码。平台定时备份不能直接还原成原生 GitLab；可以用其独立恢复副本选择议题 ID，正文和关联仍由专用导出器从 GitLab PG 读取。

已知风险：候选曾有一次黄金引导 `FULL_SYNC PARTIAL_SUCCESS`，后一次严格 compare 203项通过；首轮原因尚未确定，不得记成“环境抖动已解决”。现场若重现，先保存运行和表任务证据，不靠重复触发宣称通过。

## 3. 现场分工与放行点

| 阶段 | 执行者 | 成功条件 | 失败时停在哪里 |
| --- | --- | --- | --- |
| 校验、部署空平台和空 GitLab | 内网 AI/运维 | 身份独立、健康可达、无生产连接 | 不继续启动同步 |
| 源库元数据核查 | 获授权的内网执行者 | 版本/schema、SELECT 权限、索引资料齐全 | 不导出业务数据 |
| 取样范围与预算审阅 | 你和审核方 | 固定项目/ID或时间窗，查询计划和读取预算可接受 | 保持等待，不自动取样 |
| 限量导出、目标导入 | 内网 AI/运维 | 完整包、导入完整性检查全通过 | 不开启平台写回 |
| 测试 PAT、平台配置、全量到自动增量 | 内网 AI/运维 | 源端明确为新 GitLab，事实 READY | 保持写回关闭 |
| 业务、故障和第二轮收敛验收 | 你人工抽验，AI记录证据 | 对照用例全部满足预期 | 关闭测试写回并保留现场 |
| 交回审核 | 内网 AI整理，你确认 | 提交脱敏报告、运行号与证据 | 本次测试通过不自动等于生产接替批准 |

## 4. 校验与空环境部署

以下命令在 Ubuntu 平台部署服务器执行。已有 Docker/Compose，无需安装或重启共享 Docker。先检查 `docker ps -a`、`ss -lnt`、`df -h`、`free -h`。GitLab 镜像较大，应核对加载后的镜像、持久卷及备份余量；不能用“端口空闲”代替资源核查。

```bash
sha256sum -c dcp-delay-acceptance-<实际标识>.tar.gz.sha256
tar -xzf dcp-delay-acceptance-<实际标识>.tar.gz
cd dcp-delay-acceptance-<实际标识>
sha256sum -c SHA256SUMS.txt
docker load -i docker-images/images.tar
python3 init-env.py --server '<本服务器内网IP或DNS名>'
```

脚本只生成当前目录的 `.env`、`.secrets/`、`work/`、`reports/`，遇到已有 `.env` 或 `.secrets` 会拒绝覆盖，不连接数据库。Linux 下文件受 `umask 077` 保护。

在服务器本地编辑 `.env`：核对 `COMPOSE_PROJECT_NAME` 和五个端口。默认20201前端、20200后端、25433平台PG、20280 GitLab Web、25434 GitLab PG；这些是建议值，尚未核实现场占用。

`WEB_BIND` 默认127.0.0.1。需要工作站访问时，设为该服务器的内网接口IP并配置仅验收人员可访问的防火墙规则。`GITLAB_PUBLIC_URL` 必须与 GitLab 实际外部访问地址、端口一致。改变GitLab PG端口时，同时修改 `.secrets/target.pgpass`，后续设置 `TARGET_PGPORT`；改变GitLab Web端口时同步修改URL。

保持 `BACKGROUND_JOBS_ENABLED=false`、`DELETE_RECONCILIATION_ENABLED=false`。不要复制生产 `.env`。

修正版init-env已生成 `GITLAB_DB_PASSWORD_MD5=md5(GITLAB_DB_PASSWORD + 'gitlab')`，只供Omnibus数据库校验使用。平台连接测试GitLab PG时仍填写明文 `GITLAB_DB_PASSWORD`；如手工改密码必须同步生成摘要。

```bash
docker compose config --quiet
docker compose up -d postgres gitlab backend frontend
docker compose ps
curl --fail http://127.0.0.1:20200/actuator/health
curl --fail -o /dev/null http://127.0.0.1:20201/
docker compose exec -T gitlab curl --fail -o /dev/null http://127.0.0.1/-/readiness
curl --fail -o /dev/null http://127.0.0.1:20280/users/sign_in
```

改过端口则替换上述端口。GitLab 首次初始化需要等待；用 `docker compose logs --tail 100 gitlab` 检查，不重复创建实例。平台后端健康须为UP，Flyway应为140份成功迁移，含 `20261022.03`，无新表单迁移。

Compose使用project独立卷和桥接网络，资源身份与生产分开。桥接网络本身不禁止出站访问：现场网络管理员须限制本测试网络访问生产数据库/API，保留经批准取样工具的宿主机通路；未完成这项限制时只做空环境健康检查，暂停样本导入和业务测试。不要复制生产数据源配置。浏览器访问新GitLab用 `GITLAB_PUBLIC_URL`；平台后端访问同一实例用内部 `http://gitlab`。

此时不要登录新GitLab、创建项目或PAT，导入器要求目标保持初始化空库。可以登录新平台，但暂不保存数据源或触发同步。

## 5. 源库核查，只读元数据

在本机安全编辑 `.secrets/source.pgpass`，格式为 `主机:端口:库名:只读用户名:密码`，权限600。若密码含反斜杠或冒号，按pgpass规则转义。不要把密码放入命令历史、聊天或截图。

```bash
chmod 600 .secrets/source.pgpass
export PGHOST='<源GitLab数据库地址>'
export PGPORT='<源PG端口>'
export PGDATABASE='<源GitLab库名>'
export PGUSER='<获准的只读账号>'
# 根据现场已有要求设置PGSSLMODE；不要为了连通降低SSL要求。
bash transfer.sh preflight --report /reports/source-preflight.json
```

该入口仅查数据库身份、迁移元数据、表/列结构、SELECT权限和索引定义，不读取议题/评论正文，不生成样本。报告 `status=METADATA_ONLY`、`business_rows_read=0`、`sampling_approved=false`。它不代替取样查询的执行计划核查，也不证明完整导出不会超时。

将脱敏报告交审核方：确认是否是获准来源、版本与结构是否匹配、必需权限是否齐全、选样和关联查询是否有合适索引，再确定议题数量、总读取行数/字节、单条语句及整轮时限。必要时仅做无ANALYZE的EXPLAIN，不在此阶段读取业务正文。

**此处是取数放行点。** 版本不支持、结构不一致、权限不足或计划不可接受时停下；不要改支持版本、移除检查或改用高权限账号绕过。

## 6. 批准后的少量导出

优先从已有采集平台页面或其备份恢复副本挑选项目325、近一至两个月的少量议题，固定全局issue_id。显式ID选样绕过时间窗，执行者须确认这些ID属于已审批范围。以下20议题、20万累计读取行、64MiB、300秒仅沿用工具限制作为填写示例，实际以批准预算为准。

```bash
export APPROVED_ISSUE_IDS='<审批通过的全局issue_id，逗号分隔>'
bash transfer.sh source \
  --project-ids 325 --issue-ids "$APPROVED_ISSUE_IDS" --max-issues 20 \
  --batch-size 100 --batch-sleep-ms 200 \
  --statement-timeout-ms 30000 --lock-timeout-ms 2000 \
  --max-runtime-seconds 300 --max-total-rows 200000 --max-total-bytes 67108864 \
  --source-instance cc --output /work/approved-sample
```

如采用时间窗，去掉 `--issue-ids`，显式填 `--since '<固定ISO时间，含时区>' --until '<固定结束时间，含时区>'`。窗口内议题超过上限会失败，不会自动取前20条。不要反复扩大范围。

窗口只筛根议题，选中议题全部评论及关联闭包完整导出，含窗口前评论。超时、超限或缺关联直接失败；没有完整包不得导入。读取结束会释放只读快照，写包不继续占用源事务。监控出现可感知业务影响时停止取样。

样本保留正文，可能含客户信息，只保存在内网受限目录，不随报告发出。报告只给package_id、分表行数、哈希、时窗和结果。清单 `checks.rows` 是累计读取预算计数，不能写成导出的物理行数。

导出完成后删除当前shell的源连接变量；封存或删除源密码文件。后续目标导入、平台配置和写回均不再需要源生产密码。

## 7. 目标初始化与导入

先确认当前目录和Compose project，停止本测试环境的backend以及GitLab应用写入进程；保持测试PG运行。以下 `gitlab-ctl` 只在本Compose的gitlab服务中执行。

```bash
unset PGHOST PGPORT PGDATABASE PGUSER PGSSLMODE
docker compose stop backend
docker compose exec -T gitlab gitlab-ctl stop puma
docker compose exec -T gitlab gitlab-ctl stop sidekiq
bash transfer.sh target --init-target \
  --target-label delay-acceptance --report /reports/target-init.json
bash transfer.sh target --package /work/approved-sample \
  --target-label delay-acceptance --report /reports/target-import.json
```

目标端口改过时先 `export TARGET_PGPORT='<实际端口>'`。初始化/导入必须报告正确服务器和目标身份，`status=IMPORTED`、所有外键孤儿为0、多态违规为0、敏感表为空。导入器需要目标库写权限，脚本固定目标为本机测试端口，禁止改成生产源地址。

目标非空时停止。首次流程不使用 `--replace-existing`、不手工删除敏感表或标记以通过检查。重复测试如需重建，只重建这套可丢弃目标，并重新生成PAT；细则见随包样本工具手册。

```bash
docker compose exec -T gitlab gitlab-ctl start puma
docker compose exec -T gitlab gitlab-ctl start sidekiq
docker compose up -d backend
```

此后才可用本机 `.env` 中的新GitLab root密码登录。检查项目325、抽样议题、全部历史评论、标签、里程碑均可读；新建议题/评论、加摘临时标签均成功，序列无冲突。邮件/SMTP禁用、无生产Webhook和CI；不能只凭导入成功认定Web/API可用。

## 8. 创建测试PAT与配置平台

在新GitLab创建短期测试PAT，授予api权限，保存于本地受限位置。该PAT只属于测试GitLab，不能复制生产令牌。用API核对用户身份，完成后将PAT填入新平台配置，日志中只记录已配置与有效期。

填写API认证时只需PAT字符串，不额外填写GitLab用户名/登录密码；PAT绑定其GitLab账号，账号须具有目标项目议题标签修改权限，仅能登录或读取项目不够，read_api/read_repository也不能代替api写权限。不要求root；原本地root链路不能代替非root权限验证，整体验收应补专用非root账号正向与权限不足负例。套件自动生成的.secrets/target-token是导入器目标归属校验令牌，不能用作GitLab PAT；PG账号密码则只用于数据库连接。

在20201的新平台，创建DIRECT GitLab数据源：

| 字段 | 本套件中的值 |
| --- | --- |
| 数据库地址/端口 | `gitlab:5432`，不是宿主机映射端口 |
| 数据库名/账号 | `gitlabhq_production` / `gitlab` |
| 数据库密码 | 本测试目录 `.env` 的 `GITLAB_DB_PASSWORD` |
| Web/API地址 | `http://gitlab`，后端可达；人工浏览器使用外部URL |
| API令牌 | 上一步新建的测试PAT |
| 延期写回 | 先关闭 |
| 自动同步 | 先关闭，首次全量成功后再开启 |

项目325必须保留，不能新建同名项目替代。表白名单及数据源字段细节见样本工具手册第9节；特别包括issues、notes、labels、label_links及事件、用户、项目等依赖表。

本地编辑 `.env` 将 `BACKGROUND_JOBS_ENABLED=true`，仅重建本环境backend：

```bash
docker compose up -d --no-deps --force-recreate backend
```

通过页面测试连接、启用来源并执行首次全量。等待运行SUCCESS、所需表SUCCESS、事实READY且没有待发布根，再开自动增量。先验证新建议题和评论经自动链路进入ODS和事实，再开启延期标签写回。

若页面链接使用了内部域名 `gitlab`，人工核对时改用清单中的外部GitLab地址打开同一项目/iid；不能把数据源API改为生产GitLab来修链接。

## 9. 人工验收与预期

完整用例、模板和记录表见同包《延期标签写回本地GitLab测试与人工验收手册》。其中历史本地容器名、端口和标签ID仅是历史证据，执行环境以本套件清单及现场查询为准。

先只读核实测试库中的真实标签值与关联，保存议题完整来源身份和当前全部标签。缺失的边界只在测试GitLab补造。所有加摘操作必须由平台真实worker完成，不能人工修改目标延期标签后记作平台成功。

| 场景 | 预期 |
| --- | --- |
| 超过原响应时限，无有效模板 | 加“响应已延期” |
| 响应模板的计划解决时间缺失、空白、待定或非法日期 | 超过原响应时限时仍加“响应已延期” |
| 响应模板有符合当前严格格式的合法计划日期，包括已过去的合法日期 | 不加或摘“响应已延期”；解决延期另算 |
| 回复模板符合原识别规则，即使另有空计划响应模板 | 不加或摘“响应已延期” |
| 历史曾有合法响应模板，最新模板计划为空 | 按现有历史回查规则仍不响应延期 |
| 编辑/删除唯一有效模板 | 变化同步后重新计算，不沿用已删除评论 |
| 初始标签与事实不同，分别测补标、摘标、同议题同时加摘 | 两类标签分别收敛，无关标签不变 |
| 关闭写回、来源未就绪或事实尚在收敛 | 不发送无资格写回；重开/就绪后按新授权处理 |
| 重启时存在等待进度或PENDING作业 | 恢复后继续，不丢请求，不由过期领取者结算 |
| 自动回流和再观察一个完整周期 | ODS标签与GitLab一致，无新增差异作业；不能只看队列总行数 |
| 关闭议题、范围外议题和保护标签 | 按手册规则保持，不越范围改标 |

物理删除评论需要删除感知入口。此套件默认删除对账关闭；在独立小样本环境验证删除时，可按手册显式启用测试删除对账并记录生效值，测试后恢复。普通updated_at增量不能证明感知物理删除，也不能据小样本结果开启生产全量删除反熵。

精确时限/租约并发边界由已交付自动测试证据补证，不修改服务器时钟。D8“15个工作日与18天”差异按原实现保留，本轮不新增业务裁定。

## 10. 回交材料与停止办法

每例记录：用例ID、项目/issue_id/iid、输入评论、写前标签、预期、实际、同步运行号、事实版本、进度修订、作业状态、最终GitLab标签及第二轮结果。正文与密码不发出；可用脱敏截图和哈希证明对应关系。

汇总交回：套件SHA-256、清单、源元数据报告、批准的取样参数、样本package_id及分表计数、导入完整性报告、平台全量到自动增量结果、用例记录和失败层日志。任何失败保留首次证据，不通过修改租约/状态/版本强行放行。

停止测试时先在新平台关闭延期写回与自动同步，再停止本目录服务：`docker compose stop`。不执行共享Docker重启、全局prune或未核对project的卷删除。凭据和样本留在内网受限目录，完成后由你决定保留或销毁。

本次内网验收通过后另行审批生产接替：确认老平台仅停延期写回入口、唯一写入者和新平台开启顺序。不能让本测试环境指向生产GitLab来完成“最后一轮验证”。

## 11. 原327c8b20套件制造阶段的历史验证

以下为2026-10-26制造阶段记录；2026-10-27修正版的本地链路审批和当前范围见第0节。

本轮独立核对候选后端JAR与已测镜像完全一致，内嵌140份迁移与源码一致；稳定前端142个发货文件哈希全部一致。前端dist摘要覆盖141个文件，镜像多出的nginx默认50x.html不属于dist；按原算法可复现清单摘要ac22c038…，此前聚合值疑问已解除。

新增辅助入口10项测试通过；冻结工具41项单测在离线Linux工具镜像通过。独立空平台启动后后端UP、前端200、Flyway140份/20261022.03；新元数据预检在已有本地GitLab测试副本成功，不读取业务行。整合时另修复黄金夹具抽取入口对已移除adapt_row的引用，先有ImportError红测，修正到专用切片模块后转绿；未抽取或修改冻结夹具。

未在本轮重新启动一套完整GitLab或重跑全套写回业务链路，沿用d3d86b96候选和2e393fca工具的既有真实链路证据；也未声称在内网或目标容量下验证完成。套件后端沿用已测候选运行镜像，生产备份/升级脚本不在此验收套件中，不能据此替代正式生产包的制造与发布门禁。
