# 当前状态与待办

## 进度与中间物

- 阶段：真实链路验收完成，等待经理复核；无业务代码改动，未提交修复。
- 任务目录 `D:/projects/dcp-legacy-mr-parser-20261104`，分支 `codex/legacy-mr-parser-20261104`，基线 `b57dea4a`；JAR 摘要实测等于 `c1a96ae9…fd9c5`。
- 环境：任务自有 Compose 项目 `dcp-legacy-mr-acceptance-20261104`（GitLab 16.11.10、MySQL 8、Mongo 4.4；端口 18990/13316/13317），已 `docker compose stop` 释放，卷保留可复核；qaflex 库仅 SELECT；未启停他人容器。
- 样例：qaflex MR 47882(!30039)/47884(!30040)/43696(!28411)，在测试项目 `root/legacy-mr-acceptance`（项目ID 9）重建为 !1/!2/!3。
- 结果：解析/写入/去重均 3 行；A/B/C 全部字段（项目名称、状态、合并时间、合并人、模块、走查人、走查值）与 API 预期及独立 SQL 回读一致；`comparison.json` 三项 passed。
- 证据目录：`.tmp/legacy-mr-local-qaflex-20261104/`（`report.md`、`comparison.md`、`parsed.json`、`after_write.json`、`after_dedup.json`、`sql_readback.tsv`、`run.log`、`gitlab-workhorse.log`、`kit/expected.json`）。
- 恢复命令：`git -C D:/projects/dcp-legacy-mr-parser-20261104 status --short --branch`；容器恢复：在 `.tmp/legacy-mr-local-qaflex-20261104/kit` 执行 `docker compose start`。

| 目标 | 完成标准 | 状态与负责人 |
| --- | --- | --- |
| 用本地qaflex样例验证当前爬虫 | 3条MR的真实API/页面→当前JAR解析→老平台MySQL写入及去重→独立SQL回读一致 | 用户已授权员工本地测试；员工执行，审核方复核 |

原分支codex/legacy-mr-parser-20261104、目录D:/projects/dcp-legacy-mr-parser-20261104继续使用；已完成修复及构建合同见[交付runbook](../../deploy/runbooks/legacy-mr-api-parser.md)。本轮只补真实链路验证，不重跑已有11项离线回归或新平台全量/黄金，不更换生产JAR。

## 输入与环境

- 测试实际交付的crowncad-spider-mr-api-fix-20261104包，JAR SHA-256 `c1a96ae9929c513ee96ae715f6701604be6b33ab64349cc1ad8165f6321fd9c5`，代码输入9debf2d7。不得改用昨天的仅换会话包。
- qaflex为样例来源。先核实本地实例、GitLab真实版本、MR可用性及归属；已知本机存在16.11.10测试镜像和若干停止的验收容器，不据此认定其中已有可用MR，不擅自启停他人容器。
- qaflex平台库只读取样。优先读取可用的本地测试GitLab；已有实例缺MR时，在任务专属测试项目用qaflex业务标签/评论重建3条MR即可，不重导全库、不改既有样例。需要新GitLab时用已有16.11.10镜像和任务自有卷。
- 老平台写入目标为任务专属MySQL 8，不能用qaflex PostgreSQL代替；端口与卷先核对空闲。测试进程以外置参数将全部URL、session/token、数据源指向本地测试对象，JAR本体与内网凭据保持。最小入口关闭定时及后台任务，不启动其它业务链；如现有bean确实依赖Mongo，再准备任务自有本地Mongo。

## 三条样例

样例使用真实读取到的项目标签，不凭空推断标签。MR24509在本地存在则优先采用，否则记录本地完整项目身份+iid，预期值取当前API独立读取结果。

| 样例 | 数据条件 | 预期 |
| --- | --- | --- |
| A | 已合并，有“项目：”标签、非空merged_at及合并人、一个合法走查评论 | 项目名=标签内容；状态MERGED；合并时间=API时刻转本地秒级格式；合并人=API显示名；走查值与评论一致 |
| B | 开放，有项目标签和走查评论 | 项目名正确；状态OPEN；合并时间/合并人为空；保留已有走查解析 |
| C | 已合并，含“无需走查扫描”且原配置允许免标注 | 状态MERGED、合并证据正确；项目/模块为无需标注，沿用原豁免规则 |

## 执行步骤

1. 核对JAR摘要；选好样例后先保存每条当前API的state、labels、merged_at、merged_by和对应页面行，作为独立预期。使用本地测试凭据，不输出JAR内凭据。
2. 对当前JAR使用最小测试启动入口装配真实GitLab客户端、解析服务、转换与MyBatis mapper。调用`CodeWalkThroughService.updateWalkThrough(GitProject, year, month, day)`，传入只含这3条MR的任务专属项目，再调用`StaticDataDAOImpl.cleanDumPlicateData()`。不调用硬编码项目9的生产全量手动入口。若只读取既有项目，则用定向读取入口限制为选中MR后接同一原转换/写入链，不扫描整个项目。
3. API、列表/详情/评论读取和MySQL写入均使用真实本地服务；不得沿用`LegacyMrApiTest.StubDao`覆盖详情解析，也不得手填修复后的结果再INSERT。MySQL使用原实体对应表结构及原mapper写入，不另写一套入库逻辑。
4. 解析后、写入后、去重后三个位置按同一组字段核对：`project_name/status/merged_time/merged_local_date_time/merged_user_name/module_name/走查人和走查值`。使用独立SQL回读；按项目+MR+走查人对账，不能把多走查记录误当重复MR。无需为验证去重再触发一轮全量采集。
5. 员工报告逐样例给出“API预期→解析结果→SQL回读”、行数及结论。任一字段错误或样例没有被实际处理即不通过，先定位当前路径，不以已有离线回归替代。若确需业务修复，沿原分支最小修改并复验受影响样例，重新封包单独送审。

## 交付与结束

证据放本任务`.tmp/legacy-mr-local-qaflex-20261104/`，保留简短结果表、SQL回读、启动参数键名和脱敏日志；正常释放自有进程/容器，不操作他人资源。交付写清实际JAR摘要、GitLab版本和样例身份。通过即可结束本轮基础验收，不加页面矩阵、性能测试或其他功能测试。员工只送结果，经理复核后再决定内网重新采集。
