# GitLab 小样本只读导出与空 GitLab 导入工具

## 进度与中间物

- 阶段：经理复审 R1—R4 返修与复测完成，重新提交审批。独立工作树 `D:/projects/dcp-gitlab-transfer-20261026`、分支 `codex/gitlab-sample-transfer-20261026`，基线 `bdb9326726cc508e1ad595843603ef62a7b362c5`。
- 已完成：开工门禁；契约/导出/导入重构；首轮正式测试、148 议题全量包导出与空 GitLab 16.11.10 导入、页面/API 验证、冻结延期候选平台全量/自动增量/延期判定/真实写回/第二轮收敛；经理复审后按 R1—R4 补红测并修复：初始化前置校验与目标身份绑定、关联闭包全分页与总行/字节预算、写包/末页/事务释放时限覆盖、事务安全的 `ALTER SEQUENCE ... RESTART`、`package_id` 绑定来源与 schema 元数据；最终 148 包重新导出并在空 GitLab schema 库完成正常导入、同包替换和提交失败恢复。
- 进行点：返修代码与测试已完成，待提交并推送双远端；交付说明、手册和证据需同步最终 `package_id` 与测试计数。
- 测试状态：契约/导出/导入单元测试 41 项通过；真实 PostgreSQL 集成测试 14 项通过；最终 148 议题包 `status=COMPLETE`、`package_id=5ee2895f01b863ca660d3dfbb37b41bce18dd8cfc7daac1325e28e13e948c614`、12,150 行/2,635,096 字节，数据文件与首轮 148 包逐字节一致；空 GitLab schema 库导入 148 议题/387 评论/759 label_links，外键孤儿 0、多态违规 0、敏感表为空；平台全量事实 150 条（导入 148 + 新建 2），响应延期 114、解决延期 103；写回作业 3/3 SUCCEEDED，第二轮无新增/变更作业（首轮证据继续有效）。
- 阻塞：无技术阻塞；未经经理审批与发布授权，不连接生产、不取生产样本、不合入 main、不制造正式发布包。

## 恢复线索

- 首条恢复命令：`cd /d/projects/dcp-gitlab-transfer-20261026 && git status --short --branch && git log --oneline -3`
- 前序基线：`bdb93267`（稳定更新包源码）；不承接主树未提交表单改动。
- 环境归属：本任务独立 PG/GitLab/平台容器、网络、卷、端口；证据在 `.tmp-logs/gitlab-sample-transfer-20261026/`（不进入 Git），交付说明在 `.tmp/gitlab-sample-transfer-20261026/DELIVERY.md`（不进入 Git）。
- 关联候选：延期写回被测平台固定使用 `codex/delay-writeback-d52-test-20261026` 的 `d3d86b96` 镜像与源码，只读复用，未改分支、未共用其验收库。

## 目标与边界

### 用户目标

1. 在只有 PostgreSQL 读取权限、无 GitLab 服务器登录权限时，从 GitLab 导出少量可测试延期标签的数据。
2. 将数据包导入独立、可丢弃、结构匹配的空测试 GitLab，保留项目、议题、全部评论、标签、里程碑、作者与权限关联。
3. 完成正式自动测试、真实源副本导出、空 GitLab 导入、页面/API 验证，并用冻结延期候选平台完成同步、判定、真实写回和第二轮收敛。
4. 交付源码、测试、离线运行说明、操作手册、证据、未验证项，交经理审批。

### 成功标准

- 指定议题 ID、项目、固定时间窗口、议题数量上限均生效；窗口只筛根议题，选中议题的全部评论（含窗口前）完整保留。
- 导出只读、单连接、单 worker、参数化 SQL、有界分页、批间间隔、查询/锁等待/总时限；权限不足、关联缺失、超时、超预算时明确失败，不截断后报成功。
- 数据包无凭据、令牌、会话、Webhook、外部集成、CI 凭据；含版本、范围、身份、表行数、校验和、完整性检查与完成状态。
- 导入拒绝源目标同库、版本/结构/校验和不匹配、未完成包和非空目标；ID 冲突显式处理；失败整体回滚；重复导入有明确行为。
- 导入后可新增议题、评论、加摘测试标签；平台可同步并完成延期判定、写回、第二轮收敛。
- 导出前后源业务数据不变，写操作在只读边界被拒绝。

### 禁止行为

- 不连接生产库、不取生产样本、不部署内网、不操作 20001、不合入 main、不制造正式发布包。
- 不重启共享 Docker Desktop、不停止或清理其他任务容器/卷/网络。
- 不修改延期候选分支或其验收库；被测平台使用全新独立资源。
- 不新增 `_v2`/第二套入口；直接在原脚本上重构，保留必要调用方兼容或同步修改调用方。
- 不把敏感样本、令牌、密码、真实数据库地址写入 Git 或交付说明。
- 不以 `ON CONFLICT DO NOTHING`、关闭外键触发器或截断数据换取“成功”。

## 约束与背景

- 本机已验证 GitLab CE 版本为 `16.11.10-ce.0`；真实生产 GitLab 版本未核实，因此首版只支持经本地验证的 16.11 schema 指纹，其他版本明确拒绝并在文档列出扩展条件。
- 本地源副本：`D:/projects/data_collection_platform/.tmp/delay-writeback-real-gitlab-20261022/source.dump`（75 MB，PostgreSQL custom dump），用于构造独立源 PG。
- Python 3.14.2，psycopg2 2.9.12。运行依赖仅标准库与 psycopg2。
- 现有 `scripts/gitlab_bounded_sql_export.py` 与 `scripts/gitlab_bounded_sql_import.py` 为重构起点；现有问题包括硬编码地址与密码、每议题仅 4 条评论、原始 SQL 导入、`ON CONFLICT DO NOTHING`、无包校验、自动提交读取、无查询/总时限。
- `scripts/extract_golden_baseline_fixtures.py` 依赖旧导出函数的 `adapt_row`、`quote_ident` 与 SQL.gz 参数；重构时同步修改调用方，保持黄金夹具抽取能力，不新增双轨。
- 时间窗口默认项目 325、Asia/Shanghai、运行时刻向前两个月，起止固定到秒并写入清单；允许显式覆盖。

## 证据与根因

- 旧导出直接连接 `172.22.10.233` 与明文密码；默认 `notes_per_parent=4` 截断评论；以 `autocommit=True` 读取，跨表无一致边界；无查询/锁/总时限。
- 旧导入把包内任意 SQL 逐条执行，关闭外键触发器，冲突行静默跳过；无版本、身份、校验和与完整性验证。
- 现有脚本没有正式测试；黄金抽取器仅是调用方，不构成行为测试。

## 方案与步骤

### 数据包契约 v1

目录包（不新增第二入口）：

```
<package>/
  manifest.json
  CHECKSUMS.sha256
  data/<table>.jsonl
```

- `manifest.json`：`format=dcp-gitlab-sample/v1`、工具版本、创建时刻、源版本与 schema 指纹、源数据库标识（脱敏）、固定时间窗口与时区、选样模式、项目/议题 ID 与 iid、表行数、每文件 SHA-256、完整性检查结果、`status=COMPLETE`、脱敏清单。
- `data/*.jsonl`：每行一个 JSON 对象，键为真实列名，值为类型化编码；导入器只接受清单登记的列，自己构造参数化 INSERT，不执行包内 SQL。
- `CHECKSUMS.sha256`：覆盖 `manifest.json` 与全部 data 文件；导入时逐文件校验。
- `package_id` 由选样信息、数据文件 SHA-256、格式/工具版本、来源身份指纹与 schema 指纹共同重算；它用于一致性和篡改校验，不是身份认证或数字签名。导入器拒绝来源身份、schema 指纹、表校验和或选样任一不一致的包，即使清单文件校验和已重算。
- 写入临时目录，全部完成并校验后改名为最终目录；缺少 `status=COMPLETE` 或校验失败即拒绝；任一时限/预算失败都删除半成品。

### 选样

- 默认 `--project-ids 325`，`--since/--until` 默认运行时刻向前两个月，时区 `Asia/Shanghai`。
- 显式 `--issue-ids`（全局 ID）或 `--issue-iids`（配合单一项目）。
- `--max-issues` 默认小样本上限；超限直接失败并提示缩小范围，不静默截断。
- 时间窗口只筛根议题；选中议题的全部 `notes`、元数据与资源事件按议题闭包导出，不按评论时间或条数截断。
- 关联闭包：项目、命名空间（含父级）、用户（作者/指派/成员，脱敏）、成员与项目授权、标签与 label_links、里程碑、issue_assignees、issue_metrics、resource_*_events、routes。排除令牌、会话、Webhook、集成、CI 与外部配置。

### 导出

- 单连接、单 worker；会话只读；`statement_timeout`、`lock_timeout`、总运行时限；选样、关联闭包和数据读取全部 keyset 分页；批间 sleep。
- 总行数 `--max-total-rows`（默认 200,000）与总字节 `--max-total-bytes`（默认 64 MiB）预算覆盖选样、闭包与数据读取；每次来源查询和闭包扩展前复核剩余行/字节/时限，超限整批失败，不截断评论或关联行。
- 快照边界：在单个只读事务内读取选样根议题与计数；分页读取同一事务，保证包内跨表一致视图。读取结束后立即回滚只读快照，再进行清单序列化、写入、校验和改名；连接在失败或成功路径都回滚并关闭。
- 写包、清单/校验和写入、改名前后均复核总时限；超时删除半成品且不得产出 `COMPLETE` 包。
- 凭据只从环境变量或交互式密码文件读取；命令行、日志、清单均不出现密码。
- 写包前检查必需关联完整性；缺失即失败并列出表与原因。

### 导入

- 目标必须为空 GitLab（仅允许初始化管理员/根命名空间）；先校验目标数据库非源库，且目标实例归属为显式测试标识（数据库 + 服务器地址 + 端口 + 实例标记），不以库名含 test 判断。
- `--init-target` 在写入任何控制表或标记前，先校验目标具有所需 GitLab 结构与迁移版本、空库资格和实际连接身份；非 GitLab、非空目标、错误连接均拒绝且不新增持久对象。
- 持久标记同时绑定服务器指纹、数据库身份指纹、schema 指纹和迁移版本；导入时必须同时匹配标签、令牌和这些身份字段，不能仅凭可任意填写的标签/令牌放行错误目标。
- 校验包完成状态、`package_id`、来源身份、schema 指纹、文件校验和、表清单与行数。
- bootstrap 冲突：对源包中 id=1 的用户/命名空间，若目标仅存在初始化管理员/根命名空间，则显式改用目标行并记录；其他任何冲突立即失败，禁止静默跳过。
- 在单事务内按依赖顺序插入；任何失败整体回滚。序列使用 `ALTER SEQUENCE ... RESTART WITH` 校正（可随事务回滚），不使用不随事务回滚的 `setval`；导入后显式检查外键与多态关联，重新开启外键触发器不作为验证替代。序列调整后、后置检查及提交任一步失败，都必须恢复旧行、标记、导入日志和序列可用性。
- 重复导入：默认拒绝；仅在目标仍为本工具上次导入且 `--replace-existing` 时，先完整删除上次导入数据再导入（仍需通过校验）。
- 导入后重建测试账号/令牌，关闭邮件、Webhook、CI 与生产外发配置；输出导入报告。

### 阶段与提交边界

1. 方案与契约：计划、CLI/清单契约、单元测试骨架；提交后推送双远端。
2. 导出实现：选样、脱敏、只读边界、包校验；单元 + PG 集成测试；真实源副本导出；提交推送。
3. 导入实现：预检、冲突、事务回滚、序列/关联验证、重复导入；单元 + PG 集成测试；空 GitLab 导入；提交推送。
4. 验收与交付：页面/API、平台同步与写回、操作手册、交付说明；提交推送并回读。

## 接口契约

- 入口保持 `scripts/gitlab_bounded_sql_export.py` 与 `scripts/gitlab_bounded_sql_import.py`；包格式、脱敏规则和 schema 指纹共用 `scripts/gitlab_sample_contract.py`，连接与结构检查共用 `scripts/gitlab_sample_db.py`。
- 导出关键参数：`PG*`/libpq 环境变量（不接受命令行密码）、`--project-ids`、`--since`、`--until`、`--timezone`、`--issue-ids`、`--issue-iids`、`--max-issues`、`--max-total-rows`、`--max-total-bytes`、`--output`、`--batch-size`、`--batch-sleep-ms`、`--statement-timeout-ms`、`--lock-timeout-ms`、`--max-runtime-seconds`、`--connect-timeout`、`--source-instance`。
- 导入关键参数：`PG*`/libpq 环境变量、`--package`、`--target-label`、`--target-token-env`（默认 `DCP_TARGET_TOKEN`）、`--init-target`、`--replace-existing`、`--report`、`--batch-size`、`--statement-timeout-ms`、`--lock-timeout-ms`、`--max-runtime-seconds`、`--connect-timeout`。
- 清单字段与表清单在阶段 1 固化；新增表必须同时更新完整性检查、导入顺序与测试。`package_id` 覆盖选样、数据文件哈希、格式/工具版本、来源身份与 schema 元数据；重算清单文件校验和不能绕过，但它不是身份认证或签名。
- 黄金基线夹具专用导出器为 `scripts/golden_baseline_slice_export.py`，只服务于 `scripts/extract_golden_baseline_fixtures.py`，不是小样本传输入口，也不改变小样本包契约。

## 风险与假设

- 空 GitLab 初始化行、加密字段与权限表结构需以真实 16.11.10 实例核实；未核实前不实现猜测字段。
- 直接 DB 导入后 GitLab 缓存/序列/权限可能要求重启或 Rails 侧修正；以页面/API 实测为准。
- 生产 GitLab 版本与 schema 未知；首版只声明 16.11.10 验证范围，扩展前需重新取证。
- 用户名/邮箱脱敏后可能影响 GitLab 登录与通知；测试账号导入后重建。
- 共享 Docker 资源有限；只新建本任务命名资源，结束停止但保留卷供复核。

## 验收证据与未验证项

- 真实 GitLab 运行时（首轮 148 包，数据文件与最终包逐字节一致）：本地空 GitLab CE 16.11.10（schema_migrations 最大值 `20240702181131`，PostgreSQL 14.11）导入 148 议题/387 评论/759 label_links；议题 2251 页面与 API 均返回全部 7 条评论、标签、里程碑和脱敏作者；新建议题、评论、加摘标签成功。
- 返修后最终包：`package_id=5ee2895f01b863ca660d3dfbb37b41bce18dd8cfc7daac1325e28e13e948c614`，12,150 行、2,635,096 字节；24 张表行数与 SHA-256 与首轮包完全一致。在真实 PostgreSQL 16.13 的空 GitLab 16.11 schema 库（`gl_sample_it_target`，bootstrap 仅保留管理员/根命名空间）完成：`--init-target` 成功；全量导入 `status=IMPORTED`，148 议题/387 评论/759 label_links，`reference_maps` 组织 1→1、work_item_type 2→1；外键孤儿 0、多态违规 0；个人令牌、Webhook、集成、密钥、会话、CI 凭据均为 0；序列 `nextval > max(id)`；同包 `--replace-existing` 再次导入后行数与标记一致；在 `notes` 上挂延迟约束触发器使替换导入在提交时失败，事务回滚后 148 议题/387 评论/759 label_links、标记、导入日志和序列均恢复，目标仍可继续新增。
- 平台链路（首轮证据，继续有效）：使用冻结延期候选镜像新建独立平台库/后端/前端与网络端口，首次全量 SUCCESS，事实 150 条；自动增量 SUCCESS；3 个样本议题分别验证“事实延期但标签缺失→补标签”“事实不延期但标签存在→摘标签”“同一议题双向调整”，3 个作业均 SUCCEEDED，保护标签未受影响；第二轮无新增/变更作业。
- 正式测试：`python -m unittest` 契约/导出/导入 41 项通过；真实 PostgreSQL 集成 14 项通过，覆盖首轮全部场景并新增：初始化拒绝非 GitLab/非空/错误目标且不新增持久对象、正确空目标初始化、大关联集合预算耗尽不截断、末页读取后超时、写包期间超时、超时后连接/事务释放、序列调整事务回滚、提交失败恢复行/标记/日志/序列、来源与 schema 元数据篡改拒绝。
- 未验证或仅有限验证：除 16.11.10 外的 GitLab 版本；生产 GitLab 实际版本与结构；多项目、多实例、超大议题量真实容量；GitLab 邮件/外部集成在实例级 `gitlab.rb` 的实际禁用在本次仅由导入报告要求操作者确认，工具本身不修改实例配置文件；返修后的运行态页面/API 未重新执行，沿用首轮证据（最终包数据文件与首轮逐字节一致，导入映射未改变；新增序列调整已在真实 PG 验证）。
- 交付材料：工具源码与 4 个正式测试文件；操作手册 `deploy/runbooks/gitlab-sample-transfer-runbook.md`；本计划；证据目录 `.tmp-logs/gitlab-sample-transfer-20261026/`；交付说明 `.tmp/gitlab-sample-transfer-20261026/DELIVERY.md`。
