## 进度与中间物

- 当前阶段（2026-10-27）：输入4cd8588d的[N0五审通过](v6-n0-technical-admission-20261027.md#n0-fifth-review)，IV-01/IV-02关闭；**准入N1目录/原位登记/读源基础实施**，尚未开始实现。N2后续逐阶段验收，W5/main/真实切换/发布未准入。
- 输入与恢复：N0原稿SHA-256 8fb94e3a…dff212，Node58694efe…0defda；审核树`C:/Users/admin/.codex/worktrees/form-admin-ui/data_collection_platform` / `codex/form-admin-ui-20261027`已快进吸收4cd8588d；员工树`D:/projects/dcp-form-w4-20261026`为干净4cd8588d且双远端一致。先读AGENTS/进度/本单/N0，核对status/worktree/HEAD，再吸收最终回执SHA。
- 验证：独立Node134项及四项真实实现突变通过；独立重建原类型反例正确返回明确无效合同。21份SQL哈希未变，沿用四审115/6/33证据，本轮未重跑PG或业务套件。审核证据本树`.tmp/n0-review-r5/verification.json`。
- 下一步：员工先建立N1活动计划，再按[当前指令](#ai员工下一步执行指令2026-10-27)实施必要基础迁移、元数据/目录、受控原位登记、统一内部读源及正式回归。无须重复申请N0；跨范围变化另报审。
- 边界：当前不改frontend，批准94749f3a保持；导出真实名单、E2、MR级未批公式及未确认产品模型维持原门禁。当前审核仅文档/Node临时文件，无DB/容器/服务资源。

# 统一表单系统实施与审核清单

> 当前已吸收、待N0准入设计为v6.0，正式工作单元见[交接清单](#v60待下发工作单元与交接清单)。下方W0—W4交付与复审记录按各自v5.1输入保留；复制、迁移后当前表和单一存储版本证据的旧要求不再约束新版，只用于解释冻结修复及审查历史。
> 用户指定等待员工完成正在执行的轮次后，再一次交接新反馈与新方案。员工已在287147ff吸收文档，4423920e回执已核实；文档吸收不代表新版已实现或准入W5。

## 恢复线索

恢复先运行`git status --short --branch`，读取[AGENTS.md](../../AGENTS.md)、[项目进度](../progress.md)、[主方案v6.0](unified-form-system-plan-20261009.md)和本清单。当前审核文档使用`C:/Users/admin/.codex/worktrees/form-admin-ui/data_collection_platform`；员工目录为`D:/projects/dcp-form-w4-20261026`，当前仅整改N0合同，R05及文档吸收已完成。下表为v5.1审批轨迹，新版实施以v6准入补审为前置，不依据旧阶段通过直接跳到W5。

| 阶段 | 状态 | 交付版本/差异 | 验证证据 | 审核结论 |
| --- | --- | --- | --- | --- |
| W0 实施准入 | 通过，2026-10-22 | [W0准入材料](unified-form-system-w0-admission-20261021.md)1426行批准输入（SHA-256 `0c20354a46f6e1761c29bdf10f0e6f038c035aa4ee339191fad2d63585b29f1a`）；批准范围§8.14 | 158行/8个hunk完整重建一致；设计与静态核对，未运行新功能 | R01至R09全部关闭，批准W1 |
| W1 存储与迁移基础 | 通过，2026-10-22 | 批准输入指纹`e15d96d2…b3e18`，见[W1第四次审核与W2准入记录](#w1第四次审核与w2准入记录2026-10-22) | 独立定向34/0/0/0；真实双连接run()及清理通过，两类漂移拒绝；员工默认日志1969/0/0/1 | R01至R04全部关闭，批准W2 |
| W2 三模板记录服务 | 通过，2026-10-23 | 批准输入清单SHA-256 `f8164b79…1d645`，见[W2复验与W3准入记录](#w2复验与w3准入记录2026-10-23) | 独立定向34/0/0/0；独立PG三项修复及锁等待交错通过；员工默认日志1980/0/0/1 | R01至R03全部关闭，批准W3 |
| W3 填写页与草稿 | 通过，2026-10-23 | 58项批准输入SHA-256 `c5fc84e4…e4880`，见[W3通过与W4准入记录](#w3通过与w4准入记录2026-10-23) | 独立正式102项、补充11项通过；员工全量873项、浏览器41项及51项证据核对 | R01至R04全部关闭，批准W4 |
| W4 字段管理与版本发布 | 通过（冻结v5.1），2026-10-27 | `4423920e`，142项清单SHA-256 `d15c6260…fe80`，见[本轮复审](#r05通过与n0首次审批2026-10-27) | 独立正式26项及补充3项通过；代码/证据一致 | R01—R07全部关闭；N0另行退回，N1/W5未准入 |
| W5 消费适配与来源归属 | 待W4 | 待提交 | 待提交 | 未审核 |
| W6 集成验收 | 待W5 | 待提交 | 待提交 | 未审核 |
| W7 发布准备与黄金门禁 | 待W6及发布条件 | 待提交 | 待提交 | 未审核 |
| W8 现场切换 | 待W7及部署授权 | 待提交 | 待提交 | 未审核 |

员工在每阶段更新状态、实际改动文件、测试通过/失败/跳过、阻塞和下一步。审核结论绑定具体版本；修复后重新提交受影响差异与验证。完成整个实施工作单元后，将有效事实归入对应权威文档，再删除本执行单。

### W0 交付记录（2026-10-21）

- **阶段与版本**：W0 实施准入；仓库 `D:\projects\data_collection_platform`，分支 `main`，HEAD `84e7c023449de190e722d79d027accb062358172`；工作树混合其它任务改动，本单元不暂存、不提交、不推送。
- **实际改动**：新增 [W0 准入材料](unified-form-system-w0-admission-20261021.md)；更新本文件 W0 状态行与本节；更新 [项目进度](../progress.md) 表单条目。未改业务代码、迁移、权限、数据库、黄金快照或运行服务。
- **交付内容**：开发基线与允许范围；范围写入栅栏、管理员草稿/预览、持久发布任务/租约/状态迁移、head/权威/切换清单正式契约；`issue_response`/`issue_reply` 完整字段规格、条件 DSL、词典版本与真实列；MR/Issue 消费、权限、端点、数据库查看、工作区、统计/导出与写回影响面；W1—W7 测试入口；E1—E6 现状与 FR-E1 准入结论。
- **验证**：`JAVA_HOME=tools/jdk/jdk-21.0.10+7 tools/maven/apache-maven-3.9.9/bin/mvn.cmd test -Dtest=CollectFormServiceTest,CollectFormControllerTest`（backend 目录）→ 11 项通过/0 失败/0 跳过，exit 0；仅证明既有 Maven/JDK 与表单入口可用。未运行默认全套、黄金、迁移、事实链或浏览器。
- **提交时报告的阻塞**：FR-E1依据缺失、E1至E6未取得及原§8.2四项待确认；当前技术整改与正确阻塞范围以W0第8.4至8.6节审核结论为准。

### W0首次审核记录（2026-10-21）

- **审核输入**：`main@84e7c023449de190e722d79d027accb062358172`工作树中的W0提案，原稿SHA-256及定位见[审核结论](unified-form-system-w0-admission-20261021.md#84-首次技术审核结论2026-10-21)。
- **结论**：修改后重审，W1未准入。整改发布失败恢复、租约副作用、发布与head原子性、首版标签、初装LEGACY迁移、Issue历史连续性、范围绑定、COMMENT合规与完整消费、可执行测试入口九项。
- **验证范围**：已核对主方案、产品/架构/D-30、W0全文及两份Surefire报告，既有测试合计11项且失败/错误/跳过为0；本次未运行新DDL、迁移、业务测试、服务或黄金，交错问题属于契约推演。
- **记录检查**：24项审核记录/状态检查、16个本地链接及三项仓库护栏通过，已暂存与未暂存diff检查通过；主方案和W0原12个代码块保持。原稿及核对材料位于`.tmp/form-w0-review-20261021/`。
- **下一步**：员工修订W0并提交逐项答复、完整差异和新SHA-256。MR汇总公式在W5前补充消费影响后确认，其余原“业务确认”按已有规则、工程设计和外部证据分别处理。审核方不代写实现。

### W0整改交付（2026-10-21，重审）

- **阶段与版本**：W0 首次审核后整改；`main@84e7c023449de190e722d79d027accb062358172` 工作树；未改业务代码、迁移、权限、数据库、黄金或运行服务。
- **修订稿**：[W0 准入材料](unified-form-system-w0-admission-20261021.md)，共 1096 行；SHA-256 `21e6cbf29f4d83bc3c01fce86fda171bc129c271f610ba6850c31ae947f91628`。审查输入 SHA 见该文件 §8.4；文件未纳入 Git，无法提供字节级 `git diff`，按 §8.7 的 R01—R09 章节/条款对照审查。
- **整改内容**：R01 失败重做重新停写/捕获/复制；R02 统一锁顺序、栅栏归属 token、全部副作用 fencing 与 head 结算租约；R03 切换事务登记受影响 head 并失效旧候选；R04 拒绝空标签/无证据发布；R05 空平台、旧表 S、既有 RECORDS 三初装路径与 LEGACY/日志顺序；R06 跨版本迁移历史缺值与 `NOT VALID` 约束；R07 范围绑定锁定与历史覆盖反例；R08 COMMENT/FORM 统一原因合规计数、完整消费投影不缩减；R09 PowerShell 7 可执行命令与 15446/form_test 隔离。
- **验证**：沿用既有表单定向测试 11/0/0（仅入口证据）；本轮为 W0 文档整改，未新增可执行实现、未运行黄金/迁移/事实链/浏览器；三项仓库护栏与本地链接检查见 §8.7 和本次交付说明。
- **待批/阻塞**：R01—R09 重审结论；§2.9 Flyway 初装职责差异需审核方明确批准或退回；MR 级汇总公式在 W5 前补充影响说明后确认；E1—E6 及真实发布标签证据按阶段补证。
- **下一步**：审核方重审修订稿并给出“通过/修改后重审/外部条件未满足”；通过后才进入 W1。

### W0第二次审核记录（2026-10-21）

- **输入**：1096 行修订稿，SHA-256 `21e6cbf29f4d83bc3c01fce86fda171bc129c271f610ba6850c31ae947f91628`；副本与核对材料位于 `.tmp/form-w0-rereview-20261021/`。追加审核记录后的文件不再使用该哈希标识当前字节。
- **结论**：修改后重审，W1 未准入。R01/R04/R05/R07 单项通过；剩余 R02 租约实际时刻校验、R03 版本复核与公开提交原子性、R06 历史缺值下作废/重复制、R08 具体原因 ID 计数、R09 测试连接密码与失败处理，详见 W0 §8.8。
- **单项批准**：Flyway 安装基础能力，绑定后的旧 S 初始搬迁走受控 `INITIAL_MIGRATION`；保留停旧写与守恒要求，不代表发布协议整体通过。完整 W0 获准后同步主方案对应职责表述。
- **验证范围**：文档及入口静态核对，PostgreSQL 官方约束/时间语义核对；未执行新 DDL、迁移、业务测试、事实链、黄金或浏览器，历史 11 项测试不作为新设计验收。
- **记录检查**：输入哈希与1096行一致，21个提案代码块和主方案保持；进度文档表单段外内容及其它任务文件保持；14项记录/保留性检查、两个当前复审链接锚点、三项仓库护栏与diff检查通过，暂存区仍为空。核对结果见`.tmp/form-w0-rereview-20261021/verification.json`。
- **下一步**：员工只修订五项剩余问题，提交相对本次输入的完整差异和新 SHA-256；审核方复核后决定完整准入。MR 汇总仍在 W5 前确认，外部补证不扩大为 W1 全局阻塞。

### W0第三次整改交付（2026-10-21，重审）

- **阶段与版本**：W0 第二次审核后整改；`main@84e7c023449de190e722d79d027accb062358172` 工作树；未改业务代码、迁移、权限、数据库、黄金或运行服务。
- **修订稿**：[W0 准入材料](unified-form-system-w0-admission-20261021.md)，共 1204 行；SHA-256 `3d4eef7b2cb44d5763d9d1ec5f4e4bc36d126035e44c6766c6b5bbf24334fb22`。相对审查输入（1096 行，SHA-256 `21e6cbf2…`）的完整文本差异保存在 `.tmp/form-w0-remediation3-20261021/w0-vs-rereview-input.diff`（393 行 diff，含审核方追加的§8.8记录）。
- **整改内容**：R02 租约在取得锁后按 `clock_timestamp()` 复核并续租，定义诊断提交、过期回收与回滚边界；R03 结果公开、版本复核与 head 结算同事务并在 head 行锁内串行化；R06 以 `legacy_incomplete/legacy_missing_fields` 替代 `NOT VALID`，允许历史缺值直接作废与重试重复制；R08 改为按稳定具体原因 ID 去重计数，大类仅分类统计；R09 补齐测试库用户名/密码、就绪与退出码检查、`finally` 清理与环境恢复。
- **验证**：沿用既有表单定向测试 11/0/0（仅入口证据）；本轮为 W0 文档整改，未新增可执行实现、未运行新 DDL/迁移/事实链/浏览器；三项仓库护栏、`git diff --check` 与本文档链接检查见本次交付说明。
- **待批/阻塞**：R02、R03、R06、R08、R09 复核结论与完整 W0 准入；MR 级汇总公式在 W5 前补充影响说明后确认；E1—E6 及真实发布标签证据按阶段补证。
- **下一步**：审核方复核 §8.9 后给出“通过/修改后重审/外部条件未满足”；通过后才进入 W1。

### W0第三次审核记录（2026-10-21）

- **输入**：1204 行、SHA-256 `3d4eef7b2cb44d5763d9d1ec5f4e4bc36d126035e44c6766c6b5bbf24334fb22`；393 行差异的 17 个 hunk 可从1096行输入完整重建新稿，核对材料位于 `.tmp/form-w0-review3-20261021/`。
- **结论**：修改后重审，W1 未准入。R02/R06/R08 本轮在设计层关闭；R03 的 head 更新集合漏掉未填写且无实例的 FORM 资源，R09 新增按固定名称无条件删除容器，详见 W0 §8.10。
- **执行解释**：R02 按实际时刻复核、最终发布/结算与持锁协议通过；`statement_timeout` 仅单语句计时，需纠正累计事务超时的文字并补相应验收；维护回收仍遵循 scope→任务锁顺序。
- **边界**：未改主方案、业务代码、数据库、服务或黄金；未运行新功能测试，历史11项测试不作新设计验收。已批准条目保持，MR汇总与E项仍按原阶段。
- **记录检查**：13项记录/保留性检查、两个当前复审链接锚点、三项仓库护栏与diff检查通过；22个提案代码块及主方案保持，进度表单段外和其它任务文件保持，暂存区为空，核对结果见`.tmp/form-w0-review3-20261021/verification.json`。
- **下一步**：员工修订两项阻塞并同步超时说明，提交相对本次1204行输入的完整差异及新哈希后重审。

### W0第四次整改交付（2026-10-21，重审）

- **阶段与版本**：W0 第三次审核后整改；`main@84e7c023449de190e722d79d027accb062358172` 工作树；未改业务代码、迁移、权限、数据库、黄金或运行服务。
- **修订稿**：[W0 准入材料](unified-form-system-w0-admission-20261021.md)，共 1377 行；SHA-256 `812ae85edfe9fee34bc7f0d7ffbdf9b8fed9ba7dd4f63c44323490b59de22d41`。相对第三次审查输入（1204 行，SHA-256 `3d4eef7b…`）的完整文本差异保存在 `.tmp/form-w0-remediation4-20261021/w0-vs-review3-input.diff`（429 行，含审核方追加的§8.10记录）。
- **整改内容**：R03 受影响 head 集合改为 FORM 权威 ∪ 目标实例，FORM 权威登记初始化存储证据并建立 I-R03 不变量，切换补建缺失 head、按 id 升序锁定、全部写目标版本并在提交前做三项覆盖核验，消费者按权威类型复核且 FORM 证据必须非空；R09 删除固定名称预删除，改为本轮唯一名称并保存容器 ID，就绪/清理只按 ID，覆盖同名冲突、端口占用、创建/测试/清理失败；R02 纠正 `statement_timeout` 为单条语句计时，累计超预算由提交前实际时刻校验拦截并补相应用例。
- **验证**：沿用既有表单定向测试 11/0/0（仅入口证据）；本轮为 W0 文档整改，未新增可执行实现、未运行新 DDL/迁移/事实链/浏览器；三项仓库护栏、`git diff --check` 与链接检查见本次交付说明。
- **待批/阻塞**：R03、R09 复核结论与完整 W0 准入；MR 级汇总公式在 W5 前补充影响说明后确认；E1—E6 及真实发布标签证据按阶段补证。
- **下一步**：审核方复核 §8.11 后给出“通过/修改后重审/外部条件未满足”；通过后才进入 W1。

### W0第四次审核记录（2026-10-21）

- **输入**：1377行、SHA-256 `812ae85edfe9fee34bc7f0d7ffbdf9b8fed9ba7dd4f63c44323490b59de22d41`；429行差异的16个hunk可从1204行输入完整重建相同新稿。核对材料位于`.tmp/form-w0-review4-20261021/`。
- **结论**：修改后重审，W1未准入。R09单项通过，R02文字纠正通过，R03的A∪B覆盖与I-R03设计通过；其实际锁定SQL未锁CTE内的head基表行，FORM最终分支还需共同核验权威修订，详见W0 §8.12。
- **范围**：员工只修正R03两处执行契约，容器生命周期与集合覆盖无需重做；已有批准、MR汇总及E项阶段保持。本轮不改主方案、业务代码、数据库、服务、黄金或远端，不运行新业务验收。
- **记录检查**：13项记录/保留性检查、两个当前审核链接锚点、三项仓库护栏及已暂存/未暂存diff检查通过；23个提案代码块与主方案保持，进度表单段外和其它任务文件保持，暂存区为空。结果见`.tmp/form-w0-review4-20261021/verification.json`。
- **下一步**：提交相对本次1377行输入的完整差异和新哈希后重审，完整获准后再归并主方案及进入W1。

### W0第五次整改交付（2026-10-21，重审）

- **阶段与版本**：W0第四次审核后整改；`main@84e7c023449de190e722d79d027accb062358172` 工作树；未改业务代码、迁移、权限、数据库、黄金或运行服务。
- **修订稿**：[W0 准入材料](unified-form-system-w0-admission-20261021.md)，共 1426 行；SHA-256 `0c20354a46f6e1761c29bdf10f0e6f038c035aa4ee339191fad2d63585b29f1a`。相对第四次审查输入（1377 行，SHA-256 `812ae85e…`）的完整文本差异保存在 `.tmp/form-w0-remediation5-20261021/w0-vs-review4-input.diff`（158 行，含审核方追加的§8.12记录）。
- **整改内容**：R03-a 删除对 WITH 结果加锁的写法，A∪B 只求 `affectedIds`，最终查询直接锁定 `form_change_heads` 基表行并按 id 升序，锁保持到更新、核验和提交，行数不足即回滚；R03-b 捕获权威类型与修订，结果提交对 FORM/COMMENT 都做 NULL 安全修订比较，再分别校验 FORM 非空存储版本、COMMENT 空存储版本，覆盖存储版本不变而修订变化及 FORM→COMMENT→FORM 交错。
- **验证**：沿用既有表单定向测试 11/0/0（仅入口证据）；本轮为 W0 文档整改，未新增可执行实现、未运行新 DDL/迁移/事实链/浏览器；三项仓库护栏、`git diff --check` 与链接检查见本次交付说明。
- **待批/阻塞**：R03-a/R03-b 复核结论与完整 W0 准入；MR 级汇总公式在 W5 前补充影响说明后确认；E1—E6 及真实发布标签证据按阶段补证。
- **下一步**：审核方复核 §8.13 后给出“通过/修改后重审/外部条件未满足”；通过后才进入 W1。

### W0第五次审核与W1准入记录（2026-10-22）

- **批准输入**：1426行、SHA-256 `0c20354a46f6e1761c29bdf10f0e6f038c035aa4ee339191fad2d63585b29f1a`；158行差异的8个hunk从1377行输入完整重建一致，副本与核对材料位于`.tmp/form-w0-review5-20261022/`。
- **裁定**：R03-a基表行锁与R03-b两种权威共用类型/修订复核关闭，之前各项批准保持。W0技术通过，批准W1；不代表新功能或发布验收通过，详见W0 §8.14。
- **下一步**：员工先归并主方案第三章及相关状态，替换Flyway初装等已废弃表述，然后按W1实施存储/迁移基础并提交真实隔离PG与守恒证据；审核方不代写功能。W2至W8继续逐阶段审核。
- **验证边界**：本轮仅文档和静态核对；未运行新DDL、迁移、并发、事实链、黄金、浏览器或历史11项业务测试。MR汇总在W5前确认，E项按原阶段补证，快照/部署/转权授权保持。
- **记录检查**：13项记录/保留性检查、两个当前批准链接锚点、三项仓库护栏与已暂存/未暂存diff检查通过；23个提案代码块、主方案及其它任务文件保持，进度表单段外保持，暂存区为空。结果见`.tmp/form-w0-review5-20261022/verification.json`。

### W1 交付记录（2026-10-22）

- **阶段与版本**：W1存储与迁移基础；开发基线`main@84e7c023449de190e722d79d027accb062358172`工作树（混合其它任务改动），本单元未暂存、未提交、未推送。未改业务库、运行服务、黄金快照或内网；未修改任何既有Flyway迁移。
- **实际改动**：
  - 迁移：[V20261022_01__unified_form_storage_foundation.sql](../../backend/src/main/resources/db/migration/V20261022_01__unified_form_storage_foundation.sql)（314行，13张基础表与共享序列`form_record_id_seq`）、[V20261022_02__form_permissions.sql](../../backend/src/main/resources/db/migration/V20261022_02__form_permissions.sql)（55行，目录、当前角色授予与默认快照）；[checksum清单](../../scripts/flyway-migration-checksums.json)仅新增两条，既有条目零改动，共158条。
  - 模板规格：`backend/src/main/resources/form-templates/`下`code_review`、`issue_response`、`issue_reply`三份完整规格，Issue回复词典规范哈希`efbc6c2e…ce6c`。
  - 代码：`backend/src/main/java/com/data/collection/platform/domain/form/`11个文件（规格模型、哈希、来源URL规范化、规格目录）；`backend/src/main/java/com/data/collection/platform/service/form/`5个文件（DDL生成、初始S迁移、启动/漂移校验）；`DatabaseBrowserFormTableDefinitions`及目录登记；`DatabaseBrowserTableCatalog`接入。
  - 测试：`FormTemplateSpecCatalogTest`、`FormSchemaMigrationTest`、`FormStorageFoundationUpgradeTest`、`DatabaseBrowserFormTableDefinitionsTest`；更新`MigrationOnlyIntegrationTest`的最新迁移版本断言。
  - 文档：主方案第三章获准契约保持不变，本文件、产品、架构与项目进度同步W1状态。
- **交付内容**：
  - 元数据、范围栅栏/绑定、草稿、预览证据、审计、迁移日志、发布/attempt、切换清单、权威、head基础表；三模板物理列与约束由规格生成，含`legacy_incomplete/legacy_missing_fields`历史缺值豁免、条件必填/清空和词典校验。
  - 初始S受控迁移：按来源配置与项目选集读取全部旧`code_review`行，保留ID/时间/作废，源侧校验和复核后建表写入；实例/记录/权威/head/清单/日志/指针在同一事务内切换，任一守恒核验失败整体回滚且旧表保持可写。
  - 启动校验：共享序列、PUBLISHED版本的表名/列集合/类型/可空性/检查约束数量、ACTIVE范围指针；空平台无范围直接通过。
  - 权限三表：新增`form.record.create`、`form.record.edit_own`、`form.record.delete_own`、`form.template.manage`；当前授予由旧`code_review.form.create/edit`继承，默认快照按五角色/两管理员登记。
  - 数据库查看：13张表单基础表只读登记与中文列标签。
- **验证**：
  - W1定向测试（含隔离PostgreSQL）：`FormTemplateSpecCatalogTest,FormSchemaMigrationTest,FormStorageFoundationUpgradeTest,DatabaseBrowserFormTableDefinitionsTest` → 12项通过/0失败/0错误/0跳过（6+4+1+1）；覆盖空平台启动、代表性非空升级、S数量/字段/唯一归属守恒、漂移拒绝、head归属冲突时回滚不切指针。
  - 既有表单入口：`CollectFormServiceTest`5、`CollectFormControllerTest`6、`DatabaseBrowserPublicColumnsTest`7，均0失败（含于全量套件）。
  - 后端默认全量套件：`mvn test` → 1947项通过/0失败/0错误/1跳过，`BUILD SUCCESS`。首轮唯一失败为`MigrationOnlyIntegrationTest`固定断言最新迁移版本`20261015.03`，新增迁移后应为`20261022.02`；更新后单类复跑1/0/0，再全量复跑全绿。
  - 仓库门禁：`check_flyway_migration_immutability.py`（158条锁定）、`check_worktree_artifacts.py`、`check_runtime_artifact_locations.py`、`check_text_whitespace.py`、`git diff --check`全部通过。
- **实现期修正**：`form_template_publications.publication_kind`由`varchar(16)`改为`varchar(24)`——`INITIAL_MIGRATION`长17字符；主方案§3.1已同步。此为W1实施中发现的真实缺陷，随交付报告。
- **证据边界**：数据库验证使用Testcontainers `postgres:16-alpine` 与动态隔离schema；非空升级从`20261015.03`夹具迁移到最新。未运行黄金基线、浏览器、内网现场或真实业务库；生产存量S核对留E2，不以本地空集代替。
- **待批/阻塞**：W1阶段验收；W2三模板记录服务未开始。无新增业务裁定。
- **下一步**：审核方验收W1并给出通过/修改后重审；通过后才进入W2。

### W1首次审核记录（2026-10-22）

**结论：修改后重审，W2未准入。** W0设计批准保持，本次退回的是W1实现缺陷。审核输入为`main@84e7c023449de190e722d79d027accb062358172`混合工作树，39份输入文件的指纹与副本见`.tmp/form-w1-review-20261022/input-manifest.json`；其中files对象的规范化SHA-256为`773fd2f471d44171e8ea398ee1cd7e125dede16658f622d7d6efb127496921ab`。

已核对两个新增迁移、生成器、三模板规格、初始迁移及启动校验、数据库查看登记、权限迁移与对应测试。`publication_kind`扩大为`varchar(24)`可容纳17字符的`INITIAL_MIGRATION`，接受该工程更正；不要求重开业务决策。

| 编号 | 级别及位置 | 已复现问题 | 修订与验收条件 |
| --- | --- | --- | --- |
| W1-R01 | P1，[FormInitialMigrationService](../../backend/src/main/java/com/data/collection/platform/service/form/FormInitialMigrationService.java)第1039至1047行 | `advanceRecordSequence`只取旧表与本次目标表最大ID，忽略其他已发布版本及当前共享序列。已有Issue记录ID为102、共享序列已推进到5000，迁移旧走查ID101后，下一次nextval重新返回102，造成跨模板记录身份重复 | 共享ID分配只能向前推进，考虑所有已分配ID及当前序列水位，并明确迁移与并发分配的互斥边界。真实PG回归覆盖已有其他模板记录、序列已超前、多个范围先后迁移、失败后的分配；不能只断言大于本次S最大ID |
| W1-R02 | P1，[FormSchemaStartupValidator](../../backend/src/main/java/com/data/collection/platform/service/form/FormSchemaStartupValidator.java)第237行及初始迁移请求校验 | 绑定项目为`[9]`、发布项目为999仍迁移成ACTIVE并通过启动；把来源配置Web地址改成另一个地址后启动校验也通过。当前只查ACTIVE指针，没有实现主方案§3.1的范围验证器 | 启用/迁移与启动共用实际范围校验：项目正整数/去重、版本项目属于集合、ACTIVE范围不重叠、来源存在且实例/规范URL一致、指针及本范围归属有效。对非法输入应在写入前拒绝或整体回滚，不产生可用ACTIVE范围；覆盖改址/删源、越界版本项目、重叠及合法空平台 |
| W1-R03 | P1，[FormSchemaStartupValidator](../../backend/src/main/java/com/data/collection/platform/service/form/FormSchemaStartupValidator.java)第196行 | 只比较CHECK数量，没有验证实际约束/索引契约。实际删除记录表`request_key`唯一约束后，validate仍正常返回；后续幂等并发依赖的数据库唯一保护已消失 | 以当前版本冻结规格及DDL契约核验真实约束、唯一键、外键、共享序列默认值和必要索引，不以数量相同代替定义一致。覆盖删除request_key唯一约束、删除实例完整键唯一约束/FK、把数值CHECK替换为同数量的CHECK(true)、修改ID默认序列；均应报告定位明确的漂移。合法发布字段变更仍按冻结版本核验，不硬比种子规格 |
| W1-R04 | P1，[FormInitialMigrationService](../../backend/src/main/java/com/data/collection/platform/service/form/FormInitialMigrationService.java)第287至345行 | `loadSelection`把所有不属于本次绑定的旧走查行都当未覆盖，没有检查它们是否已属于其他登记范围。同一来源项目9、172分属两个范围，172已登记时迁移9仍因记录202被判未覆盖而失败；两个范围无法逐一搬迁 | 区分全局覆盖预检与本范围S。按已登记范围集合验证全部历史的唯一归属，再按目标范围迁移；属于其他合法范围的行不进入本次复制，也不误报未覆盖。覆盖同一来源两个项目范围、真正未覆盖/重复归属拒绝、逐范围守恒、集合外模板不混入；不通过强行合并不同版本体系的项目规避 |

**独立验证**：

- 复跑`FormTemplateSpecCatalogTest,FormSchemaMigrationTest,FormStorageFoundationUpgradeTest,DatabaseBrowserFormTableDefinitionsTest`，12项通过、失败/错误/跳过均为0；日志`directed-tests.log`与结果`directed-results.json`位于上述证据目录。
- 使用当前编译类、原始Flyway迁移和`TransactionTemplate`调用真实迁移服务，独立创建Testcontainers `postgres:16-alpine`，动态端口62877；逐项执行上述反例，`probe.log`、`probe-results.json`及`W1ReviewProbe.java`保留复现方法。探针exit 0表示反例复现完成，不表示产品验收通过。容器ID已记录，try-with-resources退出后只删除本轮容器，并核实该ID已不存在；未访问开发库或共享服务。
- 首轮探针夹具连续插入两条默认来源配置，触发既有`source_instance`唯一键；已修正为同一来源下两个项目范围后完整执行，原日志`probe-fixture-conflict.log`保留。这是探针夹具错误，不计为产品缺陷。
- 员工默认套件报告为tests=1947、failures=0、errors=0、skipped=1，本轮不重跑全量。跳过项`GitlabWhitelistServiceIntegrationTest`是`local.gitlab.it=true`才启用的真实GitLab探测，不是本次W1必测项，不据此单独退回。Surefire目录还含历史204项黄金链报告，不把目录简单求和当本轮默认套件证据。

**整改交接**：员工仅继续W1修复并把四个反例转为正式集成测试，提交新差异、版本指纹、定向及默认套件结果和确切日志路径后重审；未通过前不进入W2。共享序列修复需验证身份唯一，范围/漂移修复需验证拒绝后无部分激活，跨范围迁移需验证完整归属和字段守恒。W1关闭后清理或归并临时探针，避免长期保留第二套测试。真实内网S、W4/W5的发布/消费并发、浏览器与黄金仍按原阶段，不扩为本次额外前置条件。审核方不代写生产修复；本轮仅修改本执行单和进度，保留所有生产代码、迁移与其它任务改动，不提交推送。

**记录检查**：39份输入中37份非审核文件保持原字节，包括全部生产代码、迁移、规格、正式测试、主方案及其它任务文档；进度表单段外保持。六项审核记录/保留性检查、两个新链接锚点、158条Flyway不可变检查、三项其它仓库护栏及已暂存/未暂存diff检查通过，AGENTS为29567字节，暂存区为空。结果见证据目录`verification.json`。

### W1整改交付（2026-10-22，首次审核后）

- **阶段与版本**：W1首次审核退回后整改；开发基线`main@84e7c023449de190e722d79d027accb062358172`混合工作树，本单元未暂存、未提交、未推送。未改既有Flyway迁移（158条锁不变）、业务库、运行服务、黄金快照或内网。
- **整改内容**：
  - **R01 共享序列**：[FormInitialMigrationService](../../backend/src/main/java/com/data/collection/platform/service/form/FormInitialMigrationService.java)在迁移开始及复制完成后，均在`form_record_id_seq`事务级咨询锁内计算高水位：旧表全部模板/范围行、全部已登记记录表最大ID、当前序列`last_value/is_called`。仅当下一值不大于高水位时`setval(..., true)`，禁止回退；失败回滚可留空洞。主方案§3.4补充“后续记录创建必须取得同一咨询锁”的互斥契约。
  - **R02 范围校验**：新增[FormScopeValidationService](../../backend/src/main/java/com/data/collection/platform/service/form/FormScopeValidationService.java)，首次迁移写入前与启动校验共用：项目正整数/去重、版本项目属于集合、来源配置存在且实例/规范URL一致、current指针属于本范围PUBLISHED RECORDS、binding_hash可由持久字段重算、ACTIVE范围按`(template_key, source_config_id, resource_type, project_id)`不重叠；迁移前取得范围绑定咨询锁，激活后再复核，任一失败整体回滚。启动校验改为委托该服务。
  - **R03 结构漂移**：[FormVersionSchemaService](../../backend/src/main/java/com/data/collection/platform/service/form/FormVersionSchemaService.java)为PK/唯一键/FK/CHECK/实例索引生成确定性名称及结构化契约；启动校验逐项核对约束名称集合、类型、列、外键引用、CHECK定义归一化、记录表id共享序列默认值和实例索引，不再以数量代替。规格仍来自版本冻结`field_spec`，不硬比种子模板。
  - **R04 跨范围迁移**：[FormInitialMigrationService](../../backend/src/main/java/com/data/collection/platform/service/form/FormInitialMigrationService.java)先按全部ACTIVE范围对旧走查行做唯一归属预检：每行必须且只能命中一个范围；属于其他范围的行不进入本范围S，未命中或重复归属停止。迁移后仍逐范围做数量/字段/实例守恒。
- **改动文件**：生产代码新增`FormScopeBinding`、`FormVersionConstraint`、`FormVersionIndex`、`FormScopeValidationService`，修改`FormInitialMigrationService`、`FormSchemaStartupValidator`、`FormVersionSchemaService`、`FormVersionSchema`；测试修改`FormSchemaMigrationTest`（新增R01—R04反例17项）、`FormTemplateSpecCatalogTest`、`FormStorageFoundationUpgradeTest`；文档同步主方案§3.4、本文件及项目进度。完整文件指纹见`.tmp/form-w1-remediation-20261022/input-manifest.json`，规范化SHA-256 `5ab7730b1c7c4378c9dd9526355fe53b7dd3b3d080a9ee0576804bc3bd97254b`。
- **验证**：
  - W1定向测试（隔离PostgreSQL）：`FormTemplateSpecCatalogTest,FormSchemaMigrationTest,FormStorageFoundationUpgradeTest,DatabaseBrowserFormTableDefinitionsTest` → **29项通过/0失败/0错误/0跳过**（6+21+1+1）。R01覆盖其他模板记录、序列超前、两范围顺序迁移、失败后分配；R02覆盖越界版本项目、改址、删源、重叠与空平台；R03覆盖删除request_key唯一键、实例唯一键、FK、同数量CHECK(true)、改ID默认序列、删索引；R04覆盖两范围顺序搬迁、未覆盖、重复归属及集合外模板不混入。日志`.tmp-logs/w1-remediation-directed-5.log`。
  - 默认全量套件：在一次性`postgres:16-alpine`（端口15499，独立库）上 `mvn test` → **1964项通过/0失败/0错误/1跳过，BUILD SUCCESS**；日志`.tmp-logs/w1-remediation-final-suite.log`。该库已删除。
  - 共享15433默认`qaflex_test`/`public`存在遗留旧表（Flyway历史停在20260626.01，缺`read_publications`/`gitlab_sync_configs`），首轮全量在该库出现1项备份并发抖动和13项旧schema缺表错误；相关类在一次性库复跑全部通过（`IssueFactSourceInstancePipelineTest`6/0、`ReviewDataSearchIndexIntegrationTest`4/0；`IntegrationTestFactPipelineTest`3/0、`ProcessBackupProcessRunnerTest`6/0），不归因W1。未手工修改共享库对象；默认测试配置会对`qaflex_test`执行Flyway clean/migrate，该schema因`public`遗留旧表停留在20260626.01，未用于本轮验收。
  - 仓库门禁：158条Flyway不可变检查、worktree artifacts、runtime artifact locations、text whitespace、`git diff --check`全部通过。
- **残余边界**：记录创建（W2）必须使用与迁移相同的`form_record_id_seq`咨询锁，已写入主方案；共享15433测试库的遗留`public`旧表需环境维护，不影响隔离库验收。未运行黄金、浏览器、内网、真实业务库或事实链。
- **待批/阻塞**：W1复验；W2仍未准入。无新增业务裁定。
- **下一步**：审核方按R01—R04验收条件复验并给出通过/修改后重审；通过后才进入W2。

### W1第二次审核记录（2026-10-22）

**结论：修改后重审，W2未准入。** W1-R01共享序列、W1-R02范围校验关闭；W1-R03结构漂移和W1-R04跨范围迁移仍未满足验收。W0技术批准及既有阶段边界保持。

**输入核对**：员工清单16份文件与本地字节全部一致，files对象规范化SHA-256为`5ab7730b1c7c4378c9dd9526355fe53b7dd3b3d080a9ee0576804bc3bd97254b`。审核开始另保存45份工作树输入及HEAD，见`.tmp/form-w1-rereview-20261022/input-manifest.json`，该files对象规范化SHA-256为`d90dd378dd9dac314c6a3d87411f7f9a2588ef2fe3c98218b15c09f42c1ce3a5`。

| 编号 | 本轮结论与证据 | 剩余修订及验收 |
| --- | --- | --- |
| W1-R01 | 关闭。事务级咨询锁覆盖迁移两次高水位计算，取旧表全量、所有已登记记录表及序列已分配状态，不再回退。其他模板ID、序列超前及失败后分配回归通过 | W2记录创建必须遵守主方案同一咨询锁契约；实际创建/迁移交错按后续阶段验证，不作为本轮已验证行为 |
| W1-R02 | 关闭。候选、激活后与启动共用范围校验，越界版本项目、改址、删源、ACTIVE重叠等正式回归通过；空平台保持可启动 | 不新增业务裁定 |
| W1-R03 | **P1，未关闭。** [启动校验](../../backend/src/main/java/com/data/collection/platform/service/form/FormSchemaStartupValidator.java)第231、245、345行：CHECK归一化删除全部括号，将`A OR (B AND C AND D)`与`(A OR B) AND C AND D`视为相同；真实PG更换`ck_fr_*_2`后启动通过，同一历史空归属输入原表达式为true、新表达式为false。FK只比列和引用，改成同名同列的`ON DELETE CASCADE`后启动仍通过；删除实例使其下记录由1变0 | 保留表达式的逻辑分组和字面量语义，不用删除全部括号/统一小写等有损方式证明定义相同；将FK删除/更新动作及相关执行属性纳入冻结契约核对。正式回归覆盖逻辑分组变化、同名FK级联动作变化，均须在启动时报出具体漂移；合法生成DDL继续通过 |
| W1-R04 | **P1，未关闭。** [初始迁移](../../backend/src/main/java/com/data/collection/platform/service/form/FormInitialMigrationService.java)第263至274行只查询ACTIVE。两条旧记录201/202在首次迁移前已分别存在于项目9/172，172有来源、项目、版本项目及正确绑定哈希的DRAFT范围，迁移9仍报202未覆盖，ACTIVE数量保持0。现有`shouldMigrateTwoRegisteredScopesSequentiallyWithoutReusingRecordIds`在第一次迁移后才插入另一个范围的旧行，未覆盖该初装顺序 | 全局覆盖预检使用已核验的迁移范围清单或完整已绑定DRAFT范围，区分待迁移归属与已激活写入资格；空白DRAFT不能算覆盖，同一目标不得被候选和登记行重复计数。两边历史先全部存在、两范围尚未激活时，须能逐范围完成搬迁，各自S/字段/ID守恒，真正未覆盖或重复归属仍拒绝。主方案§3.4本轮新增的“全部ACTIVE范围”同步纠正；不得靠预先伪激活、移走旧行或合并版本体系规避 |

**独立验证**：

- 四类W1定向测试复跑29项，失败/错误/跳过均为0（6+21+1+1），结果见`directed-tests.log`及`directed-results.json`。均使用各自Testcontainers隔离库。
- 临时审核探针使用当前编译类、原158条Flyway迁移及事务模板调用真实服务；新建独占PG16容器，动态端口62639，复现上表三个反例。源码`W1RereviewProbe.java`、日志`probe.log`、结构化结果`probe-results.json`均在上述审核目录。exit 0表示反例执行完成，不能作为产品验收通过。
- 探针容器`f6f64217687ac71ea371a0dd441cbeee1cc7028182ed91b76e0eb5f79f02c009`随退出删除，按该ID回读确认不存在，见`cleanup.json`。未操作共享15433、业务库、服务、黄金、内网或远端。
- 已核对员工`.tmp-logs/w1-remediation-final-suite.log`末尾1964/0/0/1及BUILD SUCCESS；本轮不重跑默认全量，不以其通过替代上述反例。共享测试库环境维护另行处理，不扩大为W1阻塞。

**交接**：员工仅继续R03/R04修复，把三个反例转为正式测试并提交差异、指纹与验证。R01/R02批准保持；W2待W1完整通过后准入。审核方只更新本执行单与进度，未代写生产实现。记录与保留性检查结果见审核目录`verification.json`。

**记录检查**：158条迁移锁、worktree/runtime/whitespace护栏及已暂存/未暂存diff检查通过，AGENTS为29567字节。生产代码、迁移、正式测试和主方案保持审核输入；进度表单段外及既有计划记录保持。另一个延期写回计划在审核期间收到并行更新，本轮未操作该文件，差异另存证据目录；暂存区为空。

### W1第二次整改交付（2026-10-22，第二次审核后）

- **阶段与版本**：W1第二次审核退回后整改；开发基线`main@84e7c023449de190e722d79d027accb062358172`混合工作树，本单元未暂存、未提交、未推送。未改既有Flyway迁移（158条锁不变）、业务库、运行服务、黄金快照或内网。
- **整改内容**：
  - **R03 结构漂移**：[FormSchemaStartupValidator](../../backend/src/main/java/com/data/collection/platform/service/form/FormSchemaStartupValidator.java)不再做有损CHECK归一化，改为把冻结表达式放入与目标表同列的会话临时表，由PostgreSQL解析并用`pg_get_expr`反解析后与真实约束逐字比较，保留逻辑分组和字面量语义；[FormVersionConstraint](../../backend/src/main/java/com/data/collection/platform/domain/form/FormVersionConstraint.java)新增外键删除/更新动作、匹配模式、可延迟、初始延迟和已验证状态，启动逐项核对，合法生成DDL继续通过。
  - **R04 首次多范围搬迁**：[FormScopeValidationService](../../backend/src/main/java/com/data/collection/platform/service/form/FormScopeValidationService.java)新增覆盖范围清单：ACTIVE范围与完整绑定DRAFT范围（来源配置/实例、规范URL、项目集合、版本项目、`binding_hash`可由持久字段重算）均表示待迁移归属，空白或绑定不完整DRAFT不计覆盖；候选与同绑定DRAFT按绑定哈希去重。[FormInitialMigrationService](../../backend/src/main/java/com/data/collection/platform/service/form/FormInitialMigrationService.java)复用匹配DRAFT范围，以递增草稿修订号记录后仍走完整发布事务激活，不伪激活、不移走旧行、不合并版本体系。主方案§3.4同步纠正。
- **改动文件**：生产代码修改`FormVersionConstraint`、`FormVersionSchemaService`、`FormSchemaStartupValidator`、`FormScopeValidationService`、`FormInitialMigrationService`；测试修改`FormSchemaMigrationTest`（新增4项正式回归：同名CHECK逻辑分组变化、同名FK改为`ON DELETE CASCADE`、两范围历史先全部存在且项目172为完整绑定DRAFT时先迁移项目9、空白DRAFT不算覆盖）；文档同步主方案§3.4、本文件及项目进度。完整文件指纹见`.tmp/form-w1-rereview-remediation-20261022/input-manifest.json`，规范化SHA-256 `704d308c3b524e3adc85532b8bdd2f026beb5a3f90336638600473a95a1e8b00`。
- **验证**：
  - W1定向测试（隔离PostgreSQL）：`FormTemplateSpecCatalogTest,FormSchemaMigrationTest,FormStorageFoundationUpgradeTest,DatabaseBrowserFormTableDefinitionsTest` → **33项通过/0失败/0错误/0跳过**（6+25+1+1），日志`.tmp-logs/w1-rereview-directed-final.log`。
  - 默认全量套件：一次性`postgres:16-alpine`（15499，独立库）上 `mvn test` → **第二轮1968项通过/0失败/0错误/1跳过，`BUILD SUCCESS`**（`.tmp-logs/w1-rereview-full-suite-2.log`）。首轮全量出现2项Testcontainers容器连接中断（`LegacyPlatformFormalImportRevisionIntegrationTest`、`FactTargetPublicationServiceIntegrationTest`），两类隔离复跑24/0/0/0（`.tmp-logs/w1-rereview-failed-classes.log`），第二轮全量同两类均通过；连接拒绝与宿主GitLab容器约6.5GiB/163% CPU的资源争用一致，不归因W1。测试库已删除。
  - 突变自证：分别移除CHECK表达式比对、FK删除/更新动作比对、DRAFT覆盖范围后，对应三项正式回归分别失败（`.tmp-logs/w1-mutation-r03.log`、`w1-mutation-r03-fk.log`、`w1-mutation-r04.log`）；源码按备份SHA-256还原后定向套件复跑33/0/0/0。
  - 仓库门禁：158条Flyway不可变检查、worktree artifacts、runtime artifact locations、text whitespace、`git diff --check`通过。
- **残余边界**：W2记录创建必须使用与迁移相同的`form_record_id_seq`咨询锁；未运行黄金、浏览器、内网、真实业务库或事实链。
- **待批/阻塞**：W1-R03/R04复核；W2未准入。无新增业务裁定。
- **下一步**：审核方按第二次审核验收条件复验R03/R04；通过后才进入W2。

### W1第三次审核记录（2026-10-22）

**结论：修改后重审，W2未准入。** R04关闭；R03的CHECK语义比较、FK执行属性修复通过，但临时表校验新增跨连接缺陷，R03尚未完整关闭。R01/R02及W0技术批准保持。

**输入**：员工清单16份文件与本地字节全部一致，files对象规范化SHA-256为`704d308c3b524e3adc85532b8bdd2f026beb5a3f90336638600473a95a1e8b00`。审核开始保存45份输入及HEAD，见`.tmp/form-w1-review3-20261022/input-manifest.json`，该files对象规范化SHA-256为`5fc0c025853bec7058c9e491c528e77467f9df98ce6512cf382da638a08699ef`。

| 编号 | 本轮结论 | 验收及交接 |
| --- | --- | --- |
| W1-R04 | 关闭。全部旧行先存在，项目172完整绑定DRAFT，先迁移9成功且172仍为DRAFT；再迁移172复用原scope，各复制1行，最终2个ACTIVE。正式回归另锁定字段/ID守恒、空白DRAFT不算覆盖；主方案§3.4已纠正 | 不重开范围设计 |
| W1-R03，原两个反例 | 修复通过。固定同一会话后合法结构通过，CHECK逻辑分组变更和同名FK改CASCADE分别报具体漂移；不再删除括号或忽略FK动作。正式回归及员工突变日志与之相符 | 语义比较修复保持，只继续修正以下连接边界 |
| W1-R03，会话边界 | **P1，未关闭。** [FormSchemaStartupValidator](../../backend/src/main/java/com/data/collection/platform/service/form/FormSchemaStartupValidator.java)第228、285至312行：临时表创建、ALTER、pg_get_expr及DROP通过多个独立JdbcTemplate调用执行；`run()`/`validate()`没有固定连接。真实Hikari池中在创建后借走原连接，会话62创建临时表，下一语句由会话63执行，合法结构校验报SQLSTATE 42P01“relation form_ck_probe_* does not exist” | 校验入口自身保证临时表完整生命周期使用同一物理连接或实际生效的事务，不依赖连接池恰好返还同一连接。`run()`直接调用本类`validate()`，仅给后者加事务注解不能证明启动入口经过代理。正式回归须受控借走空闲连接，验证合法结构经实际入口通过、两种漂移仍准确拒绝、临时资源正确清理；不通过缩池为1或调用方临时包事务掩盖 |

**验证**：

- 独立复跑四类定向测试33/0/0/0（6+25+1+1），使用各自Testcontainers隔离库，结果见`directed-tests.log`、`directed-results.json`。
- 审核探针使用原158条迁移、当前编译类、独占PG16（动态端口61440）及真实Hikari双连接池。原三个反例按上述结论完成；语义比较对照由探针外包事务固定会话，仅用于定位，不代表生产入口已修。连接交错直接调用未外包事务的生产`validate()`，创建后保留会话62的连接，迫使下一语句使用会话63，确定性复现42P01。源码、日志及结构化结果为`W1Review3Probe.java`、`probe.log`、`probe-results.json`；exit 0表示审核探针完成，含预期复现的缺陷。
- 探针容器`3620cdc8b81501f5fe76b84d1cc068433bb7652d7a81ffed8aec2beb4c3e22af`随退出删除，按该ID确认不存在，见`cleanup.json`。未访问共享15433、业务库、服务、黄金、内网或远端。
- 员工默认全量第二轮1968/0/0/1、BUILD SUCCESS及两类隔离复跑24/0/0/0已核对原日志；三项突变日志已检查，本地源码指纹与交付一致。本轮未重跑默认全量，历史容器中断原因不作额外推断。

**下一步**：员工仅修复R03连接生命周期并提交正式回归、新差异及指纹；R04及其他批准保持，W1完整通过后才准入W2。审核方仅更新本执行单与进度，不代写生产修复。记录检查及文件保留证据见审核目录`verification.json`。

### W1第三次整改交付（2026-10-22，第三次审核后）

- **阶段与版本**：W1第三次审核退回后整改；开发基线`main@84e7c023449de190e722d79d027accb062358172`混合工作树，本单元未暂存、未提交、未推送。未改既有Flyway迁移（158条锁不变）、业务库、运行服务、黄金快照或内网。
- **整改内容**：[FormSchemaStartupValidator](../../backend/src/main/java/com/data/collection/platform/service/form/FormSchemaStartupValidator.java)把CHECK反解析临时表的创建、ALTER、`pg_get_expr`读取与DROP整体放入单一`JdbcTemplate` `ConnectionCallback`，由校验入口自身固定同一物理连接，不依赖连接池在多次调用间返还同一会话；启动`run()`直接调用`validate()`时同样生效。未给自调用方法加事务注解，也未缩池或要求调用方包事务。
- **改动文件**：生产代码修改`FormSchemaStartupValidator`；测试修改`FormSchemaMigrationTest`：新增合法结构经真实多连接池实际入口通过并在全部会话清零`form_ck_probe_*`的回归，CHECK分组与FK级联两条漂移改用同一固定连接入口断言；文档同步主方案§3.4、本文件及项目进度。完整文件指纹见`.tmp/form-w1-review3-remediation-20261022/input-manifest.json`，规范化SHA-256 `e15d96d2df53db3637a5acd1f21e5bd9821101d726dbbf577a63abe1229b3e18`。
- **验证**：
  - W1定向测试（隔离PostgreSQL）：`FormTemplateSpecCatalogTest,FormSchemaMigrationTest,FormStorageFoundationUpgradeTest,DatabaseBrowserFormTableDefinitionsTest` → **34项通过/0失败/0错误/0跳过**（6+26+1+1），日志`.tmp-logs/w1-review3-directed.log`。
  - 多连接池回归：`PinnedDataSource` 使用真实Hikari（最大20连接），只固定实际执行`create temp table`的物理连接，其余连接正常归还；经`FormSchemaStartupValidator.run()`执行后合法结构通过、CHECK分组与FK级联分别报出具体漂移、全部探测会话临时对象计数为0（`.tmp-logs/w1-r03-pinned-tests-v2.log`、`w1-review3-migration-test-v2.log`）。
  - 跨连接突变自证：仅把临时表创建改回独立`JdbcTemplate`调用，合法结构用例即失败并在会话中遗留1个`form_ck_probe_*`（`.tmp-logs/w1-mutation-r03-connection-v2.log`）；源码按SHA-256还原后定向复跑34/0/0/0。
  - 默认全量套件：一次性`postgres:16-alpine`（15499，独立库）上 `mvn test` → **1969项通过/0失败/0错误/1跳过，`BUILD SUCCESS`**，日志`.tmp-logs/w1-review3-full-suite-final.log`；该库已删除。
  - 仓库门禁：158条Flyway不可变检查、worktree artifacts、runtime artifact locations、text whitespace、`git diff --check`通过。
- **残余边界**：W2记录创建必须使用与迁移相同的`form_record_id_seq`咨询锁；未运行黄金、浏览器、内网、真实业务库或事实链。
- **待批/阻塞**：W1-R03连接边界复核；R01/R02/R04批准保持，W2未准入。无新增业务裁定。
- **下一步**：审核方按第三次审核条件复核R03连接生命周期；通过后才进入W2。

### W1第四次审核与W2准入记录（2026-10-22）

**裁定：W1阶段验收通过，批准W2。** R03临时表连接边界关闭，R01/R02/R04及W0批准保持。此次批准覆盖存储与迁移基础，三模板记录接口、填写页面及实际消费仍按W2至W8实施和验收。

**批准输入**：状态回填前员工清单16份文件与本地字节一致，files对象规范化SHA-256为`e15d96d2df53db3637a5acd1f21e5bd9821101d726dbbf577a63abe1229b3e18`。审核保存46份工作树输入及HEAD，见`.tmp/form-w1-review4-20261022/input-manifest.json`，其files对象规范化SHA-256为`84e1bf99b5c02821ba465efe29b139338ef7b07180b6ffc151b3e2f4aca0d681`。回填文档后的当前字节不再用批准输入哈希标识。

**R03关闭依据**：CHECK临时表的创建、ALTER、`pg_get_expr`读取和DROP完整位于同一`ConnectionCallback`，无跨调用借连接或自调用事务代理依赖。沿用上轮真实Hikari双连接交错，创建连接所在会话61被继续占用、后续独立查询落在会话62时，实际`run()`仍完成合法结构校验；CHECK分组变更和FK改CASCADE分别准确拒绝；各路径临时对象均为0，恢复合法约束后再次通过，原业务记录仍为1条。探针未在调用方包事务。

**验证证据**：

- 独立复跑四类W1定向34/0/0/0（6+26+1+1），exit 0，使用各自Testcontainers；见`directed-tests.log`和`directed-results.json`。
- 独立探针使用当前编译类、原158条迁移、独占PG16（动态端口56535）；源码`W1Review4Probe.java`、日志`probe.log`及`probe-results.json`保留上述受控交错。探针容器`2bebc7c1e7204dccb1f990e7240c11abb11906102a4f920b3014088102ddffec`退出后按ID确认不存在，见`cleanup.json`。
- 员工`.tmp-logs/w1-review3-full-suite-final.log`的1969/0/0/1及BUILD SUCCESS已核对；跨连接突变日志确认正式合法用例在故障实现下因遗留1个临时对象而失败，交付源码指纹一致。审核方未重跑默认全量，未运行黄金、浏览器、事实链、内网或共享业务库。
- 原反例及连接生命周期均已纳入`FormSchemaMigrationTest`维护；`.tmp/`中的审核探针只保留复核证据，不作为第二套正式测试入口。

**W2准入边界**：按本单W2已有工作及验收实施MR走查、Issue响应、Issue回复的detail、新建/编辑/作废、人员候选、上下文、权限与端点登记；记录写入须取得与迁移相同的共享序列咨询锁，完成六步幂等、本人归属、记录/结构版本、审计及head原子提交。提交真实PG并行新增、旧页后保存、重试、同记录冲突及失败回滚证据后单独验收。W3至W8未预先通过；E1至E6按原阶段，MR聚合公式仍在W5前确认，生产存量S核对留E2。

**状态归并**：同步主方案、产品、架构和进度中的W1状态；审核方未改生产代码、迁移、正式测试或其它任务改动，未暂存、提交、推送。记录与保留性检查见审核目录`verification.json`。

**记录检查**：158条迁移锁、worktree/runtime/whitespace护栏及已暂存/未暂存diff检查通过，AGENTS为29567字节。主方案、产品、架构仅回填阶段状态；进度表单段外、此前审核记录及W2至W8执行契约保持。延期写回计划在审核期间发生并行更新，本轮未操作；差异另存证据目录。

### W2 交付记录（2026-10-23）

- **阶段与版本**：W2三模板记录服务；开发基线`main@84e7c023449de190e722d79d027accb062358172`工作树（保留其它任务改动），本单元未暂存、未提交、未推送。未修改任何既有Flyway迁移；未访问共享业务库、运行服务、内网或黄金快照。
- **实际改动**：
  - 迁移：新增[V20261022_03__retire_legacy_form_permissions.sql](../../backend/src/main/resources/db/migration/V20261022_03__retire_legacy_form_permissions.sql)，删除旧`code_review.form.create/edit/delete`的角色授予、默认快照与目录记录；[checksum清单](../../scripts/flyway-migration-checksums.json)仅追加一条，共159条，既有条目零改动。
  - 服务与API：[CollectFormController](../../backend/src/main/java/com/data/collection/platform/controller/CollectFormController.java)改为`GET /detail`、`POST /records`、`PUT /records/{id}`、`POST /records/{id}/void`、`GET /reviewer-candidates`、`GET /notification-payload`；新增`FormRecordContextService`、`FormRecordWriteService`、`FormRecordService`、`FormFieldValueService`、`FormReviewerDirectoryService`、`FormRecordStore`、`FormRecordRow`、`FormRecordViewFactory`、`ValidatedFormValues`、`FormVersionContext`及对应实体/响应对象；删除旧`/save`、`/delete`、`/update-record`及旧Service/Mapper/实体。
  - 事务与校验：创建在事务级`form_record_id_seq`咨询锁内完成范围共享锁、范围当前指针复核、资源镜像校验、实例原子归并、同走查人确认、唯一插入、审计与head提交；编辑/作废按`created_by + deleted=false + record_version`执行CAS，审计/head任一失败整体回滚；字段按冻结`field_spec`校验必填/条件/互斥/枚举/词典/日期年份/计划分支/人员权威姓名并生成规范指纹。
  - 复用与登记：`GitlabResourceRootResolver`统一MR/Issue根ID解析，`FactBuildService`改为复用；`IssueResponsePlanFieldRules`开放计划分支规范化与校验；`FormFieldSpec`/`FormTemplateSpecCatalog`补齐日期年份、格式与选项来源校验；`DatabaseBrowserTableCatalog`登记13张表单基础表；`PlatformPermissionCodes`新增四项权限码。
  - 调用调整：[CollectFormView.vue](../../frontend/src/views/CollectFormView.vue)接入记录级API、稳定`requestKey`、远程走查人、同走查人二次确认与作废；[DatabaseBrowserView.vue](../../frontend/src/components/DatabaseBrowserView.vue)删除旧内联编辑器；同步`collect-form.ts`、`collect-forms-api.ts`及相关回归测试。
  - 测试：新增[FormRecordServiceIntegrationTest](../../backend/src/test/java/com/data/collection/platform/service/form/FormRecordServiceIntegrationTest.java)17项，使用Testcontainers `postgres:16-alpine`；更新`EndpointAuthorizationContractTest`、`DatabaseBrowserPublicColumnsTest`、`MigrationOnlyIntegrationTest`；黄金端点目录登记6个新端点并删除旧快照，新快照留W7。
- **验收覆盖**：空页面并行新增得到独立记录；同key并发/顺序重试仅一条记录、一次CREATE（含UUID大小写规范化）；旧页面迟到保存按记录版本拒绝且不覆盖其它记录；同记录并发编辑仅一胜；同走查人未确认不写入；审计或head失败全部回滚；他人及无归属历史拒绝编辑/作废；退役范围只读；未镜像资源、超1MB、未知字段、条件必填、词典顺序、日期年份、计划分支等拒绝；notification-payload保留外部四字段。
- **验证**：
  - `FormRecordServiceIntegrationTest` → 17项通过/0失败/0错误/0跳过，`BUILD SUCCESS`（`backend/.tmp-logs/w2-record-test-2.log`）。
  - W1/W2/护栏定向首轮62项中唯一失败为`MigrationOnlyIntegrationTest`最新迁移版本断言仍为`20261022.02`；更新为`20261022.03`后单类复跑1/0/0/0（`backend/.tmp-logs/w2-migration-only.log`），其余61项首轮通过。
  - 默认全量套件（隔离`dcp-suite-pg-15445`）→ **1975项通过/0失败/0错误/1跳过，`BUILD SUCCESS`**（`backend/.tmp-logs/w2-full-suite.log`，总耗时22:22）；唯一跳过为既有`GitlabWhitelistServiceIntegrationTest`真实GitLab白名单元数据条件测试。相对W1的1969项，退役旧表单测试11项、新增W2集成测试17项。
  - 前端：`npm run typecheck`、`npm run lint`、`npm test` → 166文件/805项通过。
  - 仓库门禁：`check_flyway_migration_immutability.py`（159条锁定）、`check_worktree_artifacts.py`、`check_runtime_artifact_locations.py`、`check_text_whitespace.py`、`git diff --check`全部通过。
- **有意产出变化**：权限目录移除旧三项并新增四项，`permission-settings`相关黄金快照及`database-browser`表清单快照预计变化；`collect-forms`端点目录与旧快照已在本单元同步，新端点快照尚未生成。本轮未运行黄金、未改快照；W7统一展示差异并经用户确认后用更新模式重建。
- **残余边界**：W3填写页草稿、多标签页、未提交离开保护未实施；W5消费与MR汇总未实施；W6集成验收、W7黄金门禁、W8现场切换未开始。真实PG验证仅限隔离容器，未验证浏览器、内网容量或真实业务库；notification-payload外部机器人调用（E1）未接通。未提交、未推送混合工作树。
- **待批/阻塞**：W2阶段验收；无新增业务裁定。
- **下一步**：审核方按W2验收条件复验并给出通过/修改后重审；通过后进入W3。

### W2首次审核记录（2026-10-23）

**结论：修改后重审，W3未准入。** W0/W1批准保持；本轮不改业务规则。记录级写入、CAS、本人归属、审计/head同事务及旧入口退役已进入正式验证，但以下三项未满足获准契约。

**审核输入**：员工`.tmp/form-w2-delivery-20261023/input-manifest.json`的51份文件哈希及16个删除项与本地一致，整个清单规范化SHA-256为`19dbcfce5b8719cf0232de102b22d6bea7b7f434345b8f6c6ccf8c9ba2d83b5f`（包含base/deleted/files）。审核开始保存87份输入及16个删除项，见`.tmp/form-w2-review-20261023/input-manifest.json`；该审核清单整体规范化SHA-256为`980baadcd5d9763e91588dbe8e6e527d806bb953b0342c107fc84f7172472224`。

| 编号 | 级别、定位及已复现问题 | 修订与验收条件 |
| --- | --- | --- |
| W2-R01 | **P1**，[FormRecordContextService](../../backend/src/main/java/com/data/collection/platform/service/form/FormRecordContextService.java)第177至196行。`lockWritableScope`持有共享锁，但只查status/current，未读取`write_fenced`。真实PG把ACTIVE范围置为停写后，新建返回CREATED、编辑升至版本2、作废成功，新增3条审计，head升至4。发布捕获S后仍可修改旧版本，破坏停写复制的前提 | 按已批准W0 §2.3，在写事务取得范围锁后核对ACTIVE、`write_fenced=false`及预期当前版本；栅栏开启时三个入口统一409/A0409，实例/记录/审计/head均不得变化，读取仍能取得完整当前版本。真实PG覆盖三入口及“等待范围锁期间设置栅栏，取得锁后拒绝”的交错；不能仅在事务外预检或推迟到W4补调用端保护 |
| W2-R02 | **P2**，[FormRecordService](../../backend/src/main/java/com/data/collection/platform/service/form/FormRecordService.java)第136至140行、[FormFieldValueService](../../backend/src/main/java/com/data/collection/platform/service/form/FormFieldValueService.java)第306至324行。已有key查找前完整validate调用`requireRealName`。第一次成功创建、同key重放成功；随后把同userId当前姓名置NULL，再用原账号/C/key/载荷重试，抛“走查人不存在或姓名为空”，已有记录/CREATE审计各1条。主方案§3.3明确重放沿用已存姓名，首次新增才取权威人员值 | 将用于首次指纹的规范化与新增时人员取值分开；已有key在账号、完整C及首次指纹匹配后返回当前记录和已存姓名，不因当前目录姓名为空/人员消失拒绝。新key仍必须核验权威人员值；跨账号/实例、异指纹、已作废重放继续拒绝。正式回归验证重放不新增记录、CREATE审计或head变更 |
| W2-R03 | **P2**，[FormRecordContextService](../../backend/src/main/java/com/data/collection/platform/service/form/FormRecordContextService.java)第371至375行。webUrl只拼基址、资源类型和IID，遗漏namespace/project路径；详情页实际将其作为超链接。镜像夹具资源地址为`http://review-w2.local/team/example/-/merge_requests/12`，detail却返回`http://review-w2.local/-/merge_requests/12` | 从已镜像项目/资源元数据及现有链接解析能力取得真实项目路径，按来源形成MR/Issue链接；缺少路径时不伪造可点击地址。正式回归覆盖MR、Issue及不同项目相同IID，避免项目上下文丢失 |

**独立验证**：

- `FormRecordServiceIntegrationTest`17、`EndpointAuthorizationContractTest`1、`FormTemplateSpecCatalogTest`6，合计24/0/0/0、exit 0；数据库测试使用类自身Testcontainers，结果见审核目录`directed-tests.log`和`directed-results.json`。
- 独立探针用当前生产类、真实Spring事务代理及`DataSourceTransactionManager`、原159条Flyway迁移，在独占PG16动态端口59849调用实际service。源码`W2ReviewProbe.java`、日志`probe.log`及`probe-results.json`保留上述反例。未用模拟写入替代生产SQL；exit 0表示反例复现完成，不是阶段验收通过。
- 探针容器`0271488b61a38bc858473de92ed9558627d5c5bbea190c397662d6fd01de0113`退出后按ID核实不存在，见`cleanup.json`。未操作共享业务库、开发服务、内网或远端。
- 员工`backend/.tmp-logs/w2-full-suite.log`的1975/0/0/1、BUILD SUCCESS及`.tmp-logs/w2-frontend-tests.log`的166文件805项通过已核对；typecheck/lint日志未见错误。本轮不重跑默认全量/前端，不把已有绿灯代替三项反例。

**阶段及黄金边界**：W3草稿、多标签页、迟到响应/离开保护及浏览器验收仍按W3实施，本轮未预先通过。黄金目录已调整、8份旧表单快照已删除，故原交付中的“未改快照”不能理解为快照目录零差异；新快照尚未生成，其余快照保持。本轮不运行黄金、不增加W2黄金前置门禁，W7仍须展示完整有意差异、按既有授权规则确认并生成/严格比对，不能带未解释差异发布。

**交接**：员工仅继续W2修复，把三个反例转为正式回归，提交相对此输入的差异、指纹、定向及默认套件结果后重审。W0/W1、E项和MR汇总确认阶段保持。审核方只更新本执行单与进度，未改生产实现、迁移、正式测试、前端或快照，未暂存、提交、推送；记录及保留性检查见审核目录`verification.json`。

### W2首次整改交付记录（2026-10-23）

- **阶段与版本**：仅整改首次审核的W2-R01至R03；基线仍为`main@84e7c023449de190e722d79d027accb062358172`混合工作树，未改迁移、黄金快照或共享业务库，未暂存、未提交、未推送。
- **W2-R01 停写栅栏**：[FormRecordContextService](../../backend/src/main/java/com/data/collection/platform/service/form/FormRecordContextService.java)的`lockWritableScope`在同一写事务取得范围行`for share`后读取`status/write_fenced/current_version_id`，仅ACTIVE、未停写且版本相符才继续；新建/编辑/作废三入口统一409/A0409，栅栏期间实例、记录、审计、head均不变，detail仍返回完整当前版本。真实PG新增三入口拒绝、栅栏期间可读及“发布事务持范围行锁→写线程等待→设置栅栏并提交→写线程取得共享锁后拒绝”的交错回归；等待以`pg_blocking_pids`确认，不依赖固定延时。
- **W2-R02 重放不依赖当前人员目录**：[FormFieldValueService](../../backend/src/main/java/com/data/collection/platform/service/form/FormFieldValueService.java)拆分新记录`validate`（取权威姓名/姓名快照）与`validateForReplay`（仅规范化和首次指纹，不查人员目录）；[FormRecordService](../../backend/src/main/java/com/data/collection/platform/service/form/FormRecordService.java)先按重放模式校验、计算指纹并在已有key时直接返回当前记录及已存姓名，再仅对新key取权威人员值。真实PG新增姓名置NULL、人员删除两种情况下同key重放均REPLAYED且记录/审计/head不变；新key仍因无权威姓名拒绝，跨账号/异指纹/已作废重放既有回归保持。
- **W2-R03 资源链接项目路径**：[FormRecordContextService](../../backend/src/main/java/com/data/collection/platform/service/form/FormRecordContextService.java)复用[GitlabResourceLinkService](../../backend/src/main/java/com/data/collection/platform/service/GitlabResourceLinkService.java)，按范围来源基址和已镜像`ods_gitlab_projects`/`ods_gitlab_namespaces`项目路径生成MR/Issue链接；路径缺失返回空，不再拼造可点击地址。真实PG新增MR、Issue及同IID不同项目的链接回归，并验证删除项目元数据后`webUrl`为null。
- **验证**：
  - W2真实PG集成：[FormRecordServiceIntegrationTest](../../backend/src/test/java/com/data/collection/platform/service/form/FormRecordServiceIntegrationTest.java)22项通过/0失败/0错误/0跳过（`backend/.tmp-logs/w2-remediation-record-test.log`）。
  - W1/链接/护栏定向：`GitlabResourceLinkServiceTest`5、`FormTemplateSpecCatalogTest`6、`FormSchemaMigrationTest`26、`FormStorageFoundationUpgradeTest`1、`DatabaseBrowserFormTableDefinitionsTest`1、`DatabaseBrowserPublicColumnsTest`7、`GoldenBaselineCoverageGuardTest`2、`EndpointAuthorizationContractTest`1、`MigrationOnlyIntegrationTest`1，合计50项通过/0失败/0错误/0跳过（`backend/.tmp-logs/w2-remediation-directed.log`）。
  - 默认全量套件（隔离`dcp-suite-pg-15445`）→ **1980项通过/0失败/0错误/1跳过，`BUILD SUCCESS`**（`backend/.tmp-logs/w2-remediation-full-suite.log`，总耗时19:54）；唯一跳过仍为既有真实GitLab白名单元数据条件测试。
  - 前端：`npm run typecheck`、`npm run lint`通过；`npm test` 166文件/805项通过（`.tmp-logs/w2-remediation-frontend-typecheck.log`、`.tmp-logs/w2-remediation-frontend-lint.log`、`.tmp-logs/w2-remediation-frontend-tests.log`）。
- **差异与证据**：相对首次审核输入新增/修改R01至R03实现、`GitlabResourceLinkService`、W2集成测试及前端可空链接类型；完整文件哈希与16个删除项见`.tmp/form-w2-remediation-20261023/input-manifest.json`。159条迁移锁、worktree/runtime/whitespace护栏及`git diff --check`全部通过。
- **残余边界**：W3仍按原阶段实施草稿/多标签页/迟到响应/离开保护及浏览器验收，W3未准入；未运行黄金、浏览器、内网或真实业务库。黄金目录已调整、旧表单快照已删除但新快照未生成，W7仍须展示完整有意差异并按授权重建/严格比对。
- **待批/阻塞**：W2-R01至R03复验；无新增业务裁定。
- **下一步**：审核方按[W2首次审核记录](#w2首次审核记录2026-10-23)的R01至R03条件复验；通过后进入W3。

### W2复验与W3准入记录（2026-10-23）

**裁定：W2阶段验收通过，批准W3。** W2-R01至R03全部关闭，W0/W1批准保持；W3填写页、草稿与交互及后续消费、发布仍按原阶段实施验收。

**批准输入**：状态回填前员工清单52份文件哈希及16个删除项与本地一致，`.tmp/form-w2-remediation-20261023/input-manifest.json`整体规范化SHA-256为`f8164b793dc1fe705f0aad4a95625ba600ec50526c4992af87f1616c7bd1d645`。审核保存88份输入、HEAD及16个删除项，见`.tmp/form-w2-rereview-20261023/input-manifest.json`，整体规范化SHA-256为`e77d5fc05e7e43a846c965ccd50308f38b7ebd9e292bcf372dc6fe4d82f87a18`。批准输入哈希不用于标识本次状态回填后的文档字节。

| 编号 | 关闭依据 |
| --- | --- |
| W2-R01 | 取得范围共享锁后同时复核status、write_fenced和current；新建、编辑、作废被停写拒绝，实例/记录/审计/head保持原值，detail仍完整可读。独立PG另以发布事务持范围行锁、写线程等待、设置栅栏后提交，验证写线程获锁后拒绝，零副作用 |
| W2-R02 | 首次指纹规范化不查询当前人员姓名，只有新key解析权威姓名。独立PG将姓名置NULL、再删除人员，同key均REPLAYED且保留Original Name快照，记录/审计/head不变；新key仍因人员无效拒绝。账号/C/异指纹/作废等原保护回归保持 |
| W2-R03 | 复用已有链接服务，使用范围来源基址及镜像项目/命名空间路径。独立PG核对MR、Issue和项目10相同IID分别指向正确地址，项目路径缺失时为null，不生成伪链接；原链接服务及来源基址调用由定向回归覆盖 |

**独立验证与证据**：

- `FormRecordServiceIntegrationTest`22、`GitlabResourceLinkServiceTest`5、`EndpointAuthorizationContractTest`1、`FormTemplateSpecCatalogTest`6，合计34/0/0/0、exit 0；使用类自身Testcontainers及原有单元测试入口，见`directed-tests.log`、`directed-results.json`。
- 独立探针使用当前生产类、真实Spring事务代理和原159条迁移，独占PG16动态端口63717；`W2RereviewProbe.java`、`probe.log`及`probe-results.json`记录上表三项修复和锁等待交错。实例/记录/CREATE审计均保持1，head版本保持1。
- 首轮探针观察连接误复用发布事务，未正确观察新会话的锁等待，未完成最后一项交错；改为独立观察数据源后完整通过。原日志`probe-observer-fixture-failure.log`和初始探针副本保留，该问题属于审核探针，不计为产品缺陷。
- 两轮探针容器`c733cda5b406…`、`d7640605f02e…`均随退出删除，并按各自完整ID核对不存在，见`cleanup.json`。未访问共享业务库、开发服务、内网或远端。
- 已核对员工默认日志`backend/.tmp-logs/w2-remediation-full-suite.log`的1980/0/0/1及BUILD SUCCESS、前端166文件805项及typecheck/lint日志。本轮未重跑默认全量/前端，未运行黄金、浏览器或事实链。临时探针覆盖已归入正式W2集成测试，`.tmp/`仅作审核证据。

**W3准入边界**：按本单W3交付MR、Issue响应、Issue回复页面及稳定入口，草稿按账号/C/编辑会话/记录块隔离，按需加载不打断输入，保留首次requestKey与载荷，处理迟到响应、401/换账号和多标签页。危险操作确认、未提交离开保护及草稿保存状态按既定需求完成。提交前端状态回归和真实浏览器双用户并行新建/保存、多标签恢复、焦点/下拉保护等证据后单独验收；不得以受控接口替身代表真实服务接通。W4至W8未预先通过，MR汇总仍在W5前确认，E项按原阶段补证；W7黄金差异及发布授权规则保持。

**状态与保护**：回填主方案、产品、架构及进度的W2通过/W3准入状态，未改设计规则或生产实现、迁移、正式测试、前端及黄金文件，未暂存、提交、推送。检查及保留性结果见审核目录`verification.json`。

### W3交付记录（2026-10-23）

**阶段与版本**：W3填写页与草稿实施完成，提交阶段验收，等待审核方复验。仓库 `D:\projects\data_collection_platform`，分支 `main`，HEAD 保持 `84e7c023`；工作树仍混合其它任务改动，本单元未暂存、提交、推送，未改其它模块的实现或运行服务。

**实际改动**：

| 文件 | 作用 |
| --- | --- |
| `frontend/src/features/collect-forms/field-logic.ts` | 按`field_spec`生成默认值、条件必填/清空、互斥、类型与长度/日期/版本分支校验及展示格式化 |
| `frontend/src/features/collect-forms/draft-store.ts` | 本机草稿按账号/C/编辑会话/draftId存取，保留draftId、requestKey、首次待提交快照与输入/持久化修订；跨标签页恢复副本、退出清理及存储信号 |
| `frontend/src/features/collect-forms/request-fence.ts` | 请求作用域令牌，迟到响应不得改写当前页面 |
| `frontend/src/features/collect-forms/form-filling-session.ts` | 已保存列表与新增/编辑块状态机；防抖草稿、提交冻结、CONFIRM_REQUIRED重放、结果未知核验、编辑冲突、作废、条件清空、退出停止及离开保护查询 |
| `frontend/src/components/collect-forms/FormFieldControl.vue` | 由已发布规格渲染人员、文本、数字、日期、单选、复选、多选、版本分支等控件 |
| `frontend/src/components/collect-forms/FormRecordCard.vue` | 已保存记录只读展示、编辑/作废入口及填写人/模板版本/时间 |
| `frontend/src/views/CollectFormView.vue` | 三模板单列页面、MR稳定外链与Issue模板入口、按需刷新、草稿状态、危险操作确认、焦点/下拉/滚动保护、beforeunload及站内离开确认 |
| `frontend/src/router.ts`、`frontend/src/feature-manifest/{types,route-contracts}.ts` | 新增`/external/forms`及参数白名单，保留`/external/code-review-form` |
| `frontend/src/composables/auth-state.ts`、`frontend/src/App.vue` | 暴露身份代际；退出前提示并清除本账号全部表单草稿 |
| `frontend/src/types/api/collect-form.ts`、`frontend/src/api-client/collect-forms-api.ts`、`frontend/src/components.d.ts` | 发布规格/详情类型与既有W2记录端点调用 |
| `frontend/src/styles.css`、`frontend/src/views/ux-interaction-regressions.test.ts`、`frontend/src/router.test.ts` | 单列填写样式、交互回归与入口白名单 |
| `frontend/src/features/collect-forms/*.test.ts`、`frontend/src/views/CollectFormView.test.ts` | 字段逻辑、草稿隔离、请求围栏、会话状态机、页面渲染与入口回归 |

**交付内容**：

- 三模板共用同一填写体验：MR代码走查保留原链接并转换上下文；Issue统一入口在省略`templateKey`时列出已登记的响应/回复，未登记模板与资源类型明确提示。
- 页面按已发布`field_spec`单列渲染；条件必填/清空、互斥、词典、数字/日期/版本分支约束在输入与提交时统一校验。
- 草稿按账号/C/编辑会话/记录块隔离；每个新增块在首次生成稳定`requestKey`，恢复时保留`draftId`、首次载荷与提交快照。已保存列表只在进入/切换资源、主动刷新或本页保存成功后加载；刷新保留所有草稿、焦点、光标、下拉展开与选择。
- 提交仅冻结当前块；CONFIRM_REQUIRED使用原key与原载荷并仅改变确认标记；5xx/超时/断网进入“提交结果待确认”并保留快照，重新获取detail或同key重放核验；编辑冲突保留用户内容并提供权威记录比较；保存成功与列表刷新失败分开表达。
- 危险操作（作废、丢弃/覆盖草稿、条件清空、退出）先说明目标与后果；作废需二次确认并输入“作废”；普通纠错不弹确认。站内跳转使用平台确认框，刷新/关闭标签页挂载beforeunload；退出清除本账号在平台各标签页的草稿并广播。

**验证证据**：

- 前端定向7个文件74/0/0/0（`.tmp/w3-acceptance/focused-tests.log`），全量171文件845/0/0/0（`frontend/.tmp-logs/w3-frontend-tests-rerun.log`）；`npm run typecheck`、`npm run lint`、`npm run build`均通过。首轮全量运行时机器满载使无关用例`customer-issue-records.mount.test.ts`超时1项，该文件单独复跑10/10、全量复跑845/845，不涉及W3文件。
- 真实浏览器验收使用独立PG16容器`qaflex-w3-acceptance-pg-20261023`（`127.0.0.1:15601`）、后端`18090`、Vite `18183`（`/api`代理到18090）及两个独立Chrome配置；夹具来源`http://gitlab.w3.local`、项目7、MR IID 3、Issue IID 31，Issue严重级别“二级缺陷”，三模板范围均已发布。验收脚本与夹具见`.tmp/w3-acceptance/`，临时Java夹具已移出产品测试目录。
- 浏览器41项全部通过，覆盖：双用户并行新建/保存及各自填写人；同走查人需显式确认；另一用户保存后旧标签页不自动刷新、主动刷新可见；新标签页按需加载全部记录；同账号双标签页草稿独立、重载恢复且互不覆盖；刷新保留焦点/光标/输入与已打开下拉节点；无未提交内容不触发beforeunload、有草稿时触发；401保留本账号草稿、换账号不可见、同账号重登恢复；退出确认并清除本账号全部草稿；Issue双模板入口；条件切换清空需确认，取消保留选项与明细、确认后清空；Issue回复与响应保存，响应在非一级缺陷下禁用负责人确认。新增三项：迟到列表响应即使在更新的保存重载结果之后到达也不倒退；普通输入纠错不弹确认且草稿保存修订等于当前输入；保存成功而列表刷新失败时保留已保存记录并单独提示，主动重试后恢复列表。
- 数据库对账：`form_s1_v1_records`5条（admin、approval、approval、admin、approval，含迟到响应期间保存与刷新失败保存），`form_s3_v3_records`1条（RESOLVED、原因`design_forget`、`affected_kinds={NONE}`、已知明细已清空），`form_s2_v2_records`1条（DEFECT、计划2026-12-01、`CC2026R1&CC2026R2`、负责人确认不适用）；7条CREATE审计的模板/范围/存储版本与操作者一致；MR变更head的`latest_change_version`为5，两个Issue为1，pending均指向对应记录。记录ID来自共享序列。
- 证据目录`.tmp/w3-acceptance/evidence/`：22张截图、`acceptance.log`（SHA-256 `6870615b…68f59`）、`acceptance-summary.json`（SHA-256 `ae35eb46…55fd`）；逐文件哈希见`evidence-sha256.txt`。运行日志`run.log`、`fixture.log`、`backend.log`、`frontend.log`保留同一目录。
- 代码与证据输入清单（不含`docs/`状态文档，59个文件）SHA-256 `06afd49bdb56b27d68f9ce7d465a507ef15c9143dfdd56ae66a5a91f97a66bda`；含状态文档的全量清单及逐文件哈希见`.tmp/w3-acceptance/input-manifest.json`。状态文档不计入该哈希，避免回填文字改变代码与证据标识。

**验收中发现并修复**：

- 会话块的界面键原与`draftId`各自随机生成，整页重载恢复草稿后键变化，影响恢复块的定位与后续交互证据。按“草稿使用稳定draftId”统一为同一标识，并在恢复与编辑测试中增加`block.key === block.draftId`断言；不改接口、存储字段或已保存记录。
- 其余失败均属验收脚本自身：同走查人确认框未点击、后台标签页不推进下拉展开过渡、列表刷新加载态未结束时点击被吞、重载时beforeunload对话框未处理、退出按钮未等待身份恢复、并行保存后两页列表完成顺序造成的计数竞态、确认框整页截图偶发超时（已加短等待与视口截图回退）。已分别修正脚本并保留断言（含“有草稿时触发beforeunload”“迟到响应不倒退”“保存成功与刷新失败分离”），不计为产品缺陷。

**残余边界**：未运行黄金基线、内网部署、真实GitLab/机器人/AI调用或共享业务库；浏览器验收为隔离夹具与本地服务，不代表内网容量、真实登录入口或外部自动化已验收（E1/E4/E5仍按原阶段）。W4字段管理与发布、W5消费与来源归属、W6集成、W7黄金及W8现场切换未开始；MR汇总公式仍在W5前确认。W3通过前W4不得准入。

**状态与保护**：回填本执行单、产品、架构及进度的W3实施/待验收状态；未改设计规则、迁移、权限、黄金文件或其它模块实现，未暂存、提交、推送。验收后停止本单元启动的后端、Vite与PG容器，不保留监听端口；其它AI任务的改动与服务未触碰。

### W3首次审核记录（2026-10-23）

**结论：修改后重审，W4未准入。** W0/W1/W2批准保持。三模板入口、记录块、动态字段与本机草稿主流程已有交付；以下四项不满足主方案§3.6已确认的输入保护和恢复契约。

**审核输入**：`.tmp/w3-acceptance/code-evidence-manifest.json`的59项代码与证据逐项匹配，文件SHA-256为`06afd49bdb56b27d68f9ce7d465a507ef15c9143dfdd56ae66a5a91f97a66bda`；含五份状态文档的64项`input-manifest.json`也全部匹配，清单文件SHA-256为`2094ec0700ab14cce6a8c1a7bf31de8bae184536a55a16cb412d50c52c0b0e0e`。审核前123项工作树状态及AGENTS指纹、五份文档副本保存在`.tmp/w3-review-20261023/`；未以HEAD差异冒充本次W3独立改动。

| 编号 | 级别、定位及已复现问题 | 整改与验收条件 |
| --- | --- | --- |
| W3-R01 | **P1**，[FormFillingSession](../../frontend/src/features/collect-forms/form-filling-session.ts)第605至616、1004至1012行及[draft-store](../../frontend/src/features/collect-forms/draft-store.ts)第284行。甲有新增草稿，乙恢复同draftId后继续修改并自动保存；甲保存原内容时，`deleteFormDraftCopies`删除乙的持久副本，乙收到`draft-cleared`后无条件移除内存块。独立探针得到乙的内存块0、本机副本0、离开保护false、丢弃确认0次，乙独立输入直接丢失 | 成功通知及副本清理须识别是否仍为该次提交对应的未分叉内容；不得仅凭draftId删除全部副本。保留已分叉输入、提交快照及离开保护，并说明原操作已保存，供用户明确选择后续处理；不能静默生成新key绕过同次新增幂等。正式测试及双标签页页面验收覆盖甲先保存/乙先保存、已落盘和防抖待落盘的分叉输入、未变化副本以及另一页提交在途，确认只清理安全副本且无重复创建 |
| W3-R02 | **P1**，[FormFillingSession](../../frontend/src/features/collect-forms/form-filling-session.ts)第651至662行，[CollectFormView](../../frontend/src/views/CollectFormView.vue)第1252行。`discardBlock`只阻止pending，unknown仍可确认丢弃。模拟504后块已unknown/frozen，点击丢弃返回true，原key和快照的内存/本机记录均为0，再新增立即得到新key；期间未核验原结果。服务端若已提交，会失去同次操作核验依据并可能重填重复记录 | 结果未知时保留原key、载荷和状态，核验后才允许丢弃或替换该次提交内容；页面按钮与会话方法都应遵守，普通丢弃确认不能代替结果核验。仍可保留草稿离开。正式测试覆盖新增和编辑在“服务端成功但响应丢失”“确定未写入”“核验再次失败”下的丢弃/重新填写路径；页面验证未知结果不能被普通丢弃绕过，成功后转已保存记录或显式编辑 |
| W3-R03 | **P2**，[FormFillingSession](../../frontend/src/features/collect-forms/form-filling-session.ts)第348至370行。结构失配只在`blockFromDraft`恢复时计算，`reconcileBlocks`不比较活动块的scope/storageVersion。页面22版草稿填写后，主动刷新得到23版及新field_spec，块仍为22版、`structureStale=false/readOnly=false/canSubmit=true`；页面已用新规格渲染，提交仍带旧版本，无法通过W2版本校验 | 每次权威detail变化都复核活动草稿的范围及结构版本；失配保留原输入并按既定规则停止原块提交、提示核对/复制，不把新规格直接套到旧草稿继续填写。pending/unknown保留原提交快照和核验语义。正式测试覆盖新增与编辑块、scope变化、storageVersion变化、结构不变普通刷新、只读退役及恢复；W3用状态/页面测试验证客户端，W4/W6再验证真实发布交错，不以W4未实施为由省略W3保护 |
| W3-R04 | **P2**，[CollectFormView](../../frontend/src/views/CollectFormView.vue)第648至650、831行。离开保护只挂`onBeforeRouteLeave`，Issue“返回模板入口”只变同一路由query，随后watch直接dispose当前会话。挂载真实组件与Vue Router，输入未提交文字后点击该按钮，即使确认替身设为取消，确认调用仍为0，页面已回入口，输入区消失；本机副本仍在，故此项是缺少离开确认，不声称数据已丢失 | 同路由资源/模板/query切换与不同路由离开共用既定离开检查。取消须保持原URL、模板、输入、焦点和提交归属；确认才补存草稿并切换，且一次导航不重复确认。正式路由测试和真实页面覆盖Issue返回入口、切换模板/资源、浏览器后退、空块/改回原值免确认、草稿写入失败提示及结果未知保留核验 |

**独立验证及证据边界**：

- `npx vitest run`定向运行字段逻辑、草稿存储、请求围栏、填写会话、填写页、路由及交互护栏七个正式文件，74/0/0/0，日志`.tmp/w3-review-20261023/focused-tests.log`。
- 审核探针直接导入当前生产状态机/草稿模块；R01至R03的三个既定契约断言全部失败，日志`review-invariants.log`。R04挂载真实`CollectFormView.vue`与Vue Router，契约断言失败，日志`review-route.log`。接口为受控替身，仅证明客户端缺陷，不作为后端或真实服务验收。探针和配置仅在审核目录；页面探针首次有依赖与导入路径配置错误，修正后实际执行到契约断言，未计为产品缺陷。
- 员工全量171文件845项通过的日志已核对；浏览器41项记录、22张截图及逐文件哈希全部匹配，抽看MR填写、Issue回复页面。认可已有真实服务主流程证据，但这41项没有覆盖恢复副本分叉后保存、unknown丢弃、活动结构更新或Issue同路由离开，不据此关闭四项反例。本轮未重新启动浏览器服务或访问数据库，未把组件探针写成真实浏览器复验。
- 未运行黄金、默认后端套件、内网、真实GitLab/机器人/AI或共享业务库；E1/E4/E5、MR汇总与W4至W8的原阶段保持。临时探针应在整改时转为正式行为测试，审核目录只保留本次输入与失败证据。

**交接与保护**：员工仅整改W3-R01至R04，提交相对本次输入的完整差异、新清单指纹、正式回归与受影响真实页面证据后重审。审核方只更新阶段状态和本审核记录，不代写实现；未改生产代码、正式测试、迁移、快照、其它任务文件或服务，未暂存、提交、推送。记录与工作树保留性检查见审核目录`verification.json`。

### W3整改交付记录（2026-10-23）

- **阶段与版本**：W3四项整改交付；基线`main@84e7c023`混合工作树，本单元未暂存、未提交、未推送；未改后端、迁移、权限、黄金文件、共享业务库或其它模块实现。
- **R01跨标签页**：保存结果由“按draftId清除全部副本”改为广播保存结果。[form-filling-session](../../frontend/src/features/collect-forms/form-filling-session.ts)的`applyRemoteSaveOutcome`（:657）仅对字段与本次提交逐字相同（`hasSameFields`，:1284）、`submitState==='none'`且无`savedElsewhere`的副本执行安全清理；分叉副本经`markSavedElsewhere`（:706）转为只读并保留原`requestKey`，由用户显式复制为新块；`resolveCreateDraftCopies`（:1134）在新增成功时统一枚举同账号同上下文副本。[draft-store](../../frontend/src/features/collect-forms/draft-store.ts)新增持久字段`savedElsewhere`（:57）与`announceFormDraftSaveOutcome`（:341），删除按draftId清除副本的旧API，无兼容别名。
- **R02未知结果**：`canDiscardBlock`（:751）与`discardBlock`（:755）拒绝`pending`/`unknown`，页面丢弃按钮同源禁用并给出核验提示；未知态保留原key、载荷快照与核验语义，普通丢弃确认不能替代核验。
- **R03结构版本**：`reconcileBlocks`（:366）在每次权威详情落地后调用`applyStructureGuard`（:414）复核`scopeId`/`storageVersionId`；失配块保留输入、转只读、停止原块提交并提供显式复制，pending/unknown保留原提交快照，旧草稿不套用新`field_spec`。
- **R04统一离开检查**：[CollectFormView](../../frontend/src/views/CollectFormView.vue)的`confirmFormLeave`（:866）由`onBeforeRouteLeave`（:914）与新增`onBeforeRouteUpdate`（:919）共用，覆盖同路由资源/模板/query切换；取消经`stayOnForm`（:905）以replace恢复原地址并恢复焦点/选区，模式未变化时直接放行，一次导航只确认一次。
- **后退取消根因**：Element Plus `MessageBox`默认`closeOnHashChange: true`，hash路由下浏览器后退先改写地址并触发`hashchange`，会关闭刚出现的离开确认框致Promise不settle；仅在离开确认框传`closeOnHashChange: false`（:894），不推广到其它对话框。
- **正式测试**：`draft-store.test.ts`10项、`form-filling-session.test.ts`29项、`CollectFormView.test.ts`12项，覆盖双标签页保存顺序与分叉副本、未知态禁止丢弃与核验、结构版本失配、Issue返回取消/确认、模板与资源切换取消、浏览器后退取消、空块免确认、草稿写入失败提示、刷新版本只读；表单定向5文件60项全绿。
- **全量验证**：前端全量171文件866项中865项通过；唯一失败为无关的`customer-issue-records.mount.test.ts`单用例5秒超时，单独复跑该文件10/10通过（员工推测机器负载影响，根因未确认；该测试文件不在本单元改动范围）。`typecheck`、`lint`、`build`通过。
- **突变自证**：把离开守卫取消分支改为直接放行后，`CollectFormView.test.ts` 5项转红（含浏览器后退取消）；把失败态固定为正常后，草稿写入失败提示用例转红。两次均原样复原，`CollectFormView.vue` SHA-256 恢复为`dfdda2100b0a571e821729dba9cd99f33a11033f68c25e4b810336c0ac747c7c`，复原后定向60项全绿。
- **真实页面**：既有验收41/41全绿（`evidence/acceptance.log`、`acceptance-summary.json`及22张截图）；整改场景45/45全绿（`evidence/remediation.log`、`remediation-summary.json`及R01至R04截图），覆盖服务端成功但响应丢失、核验重放同key恰好1条、双标签页分叉副本保留与显式复制、版本1→4刷新只读并可复制、Issue返回取消保持URL/输入/焦点、模板与资源切换取消、浏览器后退取消、确认离开补存草稿、空块免确认。独立后退探针输出`evidence/r04-browser-back.log`。
- **数据库对账**：`form_s1_v1_records`恰有3条预期记录（响应丢失待核验、甲标签页原内容、乙标签页独立修改各1条），3条CREATE审计；`code_review`范围版本1→4（W3-2026.11），Issue两范围保持2/3。
- **脚本加固声明**：整改脚本仅做两处测试工具等待加固（入口按钮就绪后再点击、空块离开改用DOM点击并等待渲染），产品空块离开行为另以独立探针确认无确认框且URL回到入口，不计产品改动。
- **交付指纹**：`.tmp/w3-remediation-20261023/code-evidence-manifest.json`覆盖54项代码与证据（不含`docs/`），文件SHA-256 `3bb61e0cd05a3891206ecd46bb73606d36cba7e0e3a95b18cd549e5c478646e2`；含状态文档的59项全量清单及逐文件哈希见同目录`input-manifest.json`，证据逐文件哈希见`evidence/evidence-sha256.txt`。
- **边界**：仅使用隔离PG16容器`127.0.0.1:15601`、后端18090与Vite 18183；未触碰18080/15432/18181/18182、共享业务库、内网、真实GitLab/机器人/AI；未运行黄金门禁。

### W3整改复审记录（2026-10-23）

**结论：修改后重审，W4未准入。** W3-R01、R03、R04单项通过，R02的普通丢弃入口已修复，但未知结果仍可经复制或版本冲突绕过保护。W0/W1/W2及本次通过的三项批准保持；不新增业务规则。

**审核输入**：`.tmp/w3-remediation-20261023/code-evidence-manifest.json`的54项逐项匹配，SHA-256 `3bb61e0cd05a3891206ecd46bb73606d36cba7e0e3a95b18cd549e5c478646e2`；含状态文档的59项清单逐项匹配，文件SHA-256 `a37057a6e891d6714ec88034e3eed57815600f9ed3785820d06d9f0e267bc8d6`。首次清单中未纳入本轮的53个文件全部存在且保持原哈希，缺失0；本轮六个生产/正式测试文件均有变化。审核前工作树124项状态、AGENTS指纹和五份文档副本见`.tmp/w3-rereview-20261023/`。

| 编号 | 裁定与依据 |
| --- | --- |
| W3-R01 | **通过**。已删除按draftId清全部副本和`draft-cleared`旧路径。新保存结果带提交字段及记录身份；未分叉安全副本可清理，分叉输入保留原key、只读标记和显式复制入口，在途副本由原请求结算。正式测试覆盖持久/防抖分叉及在途副本，独立反例确认另一页输入、key、本机副本和离开保护保留；员工真实页面证据有分叉保留及显式复制后仅一条新记录 |
| W3-R03 | **通过**。每次detail更新先复核scope/storageVersion，再处理记录状态；活动与恢复的旧版本草稿保留输入并停止原块提交，普通同版本刷新仍可编辑。正式测试及独立反例通过，员工真实版本1→4与只读提示证据一致。与unknown组合时仍须受R02约束；当前`REPLAYED`替身测试不证明跨版本真实重放成功 |
| W3-R04 | **通过**。同路由更新与跨路由离开共用检查；取消按原地址replace并恢复交互，离开框关闭`closeOnHashChange`默认行为。正式组件/路由测试覆盖返回入口、模板切换、浏览器后退、确认补存和本地写入失败；突变能使取消地址及失败提示用例失败。浏览器日志与截图核对了后退取消后的URL、输入和焦点 |
| W3-R02 | **部分修复，继续整改**。页面和会话已禁止unknown普通丢弃，新增/编辑原key与快照能保留。但下述两个同属“核验前不得替换或丢弃”的入口仍未关闭 |

**R02剩余问题与验收条件**：

| 子项 | P1问题与复现 | 整改及验收 |
| --- | --- | --- |
| R02-a 未知结果可复制新操作 | [FormFillingSession](../../frontend/src/features/collect-forms/form-filling-session.ts)第775至787行，及[CollectFormView](../../frontend/src/views/CollectFormView.vue)第1305至1312行。新增响应丢失进入unknown，刷新取得新结构后原块readOnly，页面显示可用的“复制内容到新填写块”。独立探针调用该入口，原key `id-2`仍unknown，却产生可提交的`id-4`、使用新版本23；未核验原结果即能另建记录 | 页面和会话层都禁止pending/unknown绕过核验复制成新提交；保留只读查看、原key/快照和保留草稿离开。正式测试与页面验收覆盖unknown+结构变化、scope变化、恢复旧草稿，以及尚在途时触发复制；核验后才按权威结果允许显式编辑或新建。已确认原操作成功的分叉副本仍可显式复制，不误禁R01已通过路径 |
| R02-b 版本冲突被误作核验完成 | [FormFillingSession](../../frontend/src/features/collect-forms/form-filling-session.ts)第1049至1057、1029至1042行。unknown新增核验复用`performSubmit`，旧版本重放收到409后变conflict、解除冻结，`canDiscardBlock=true`；独立探针确认丢弃后内存/本机草稿均为0。现有[FormRecordService](../../backend/src/main/java/com/data/collection/platform/service/form/FormRecordService.java)第132行先`requireExpected`，第142行才查`replayIfPresent`；[范围校验](../../backend/src/main/java/com/data/collection/platform/service/form/FormRecordContextService.java)第216至217行对版本变化返回409，该拒绝不说明原请求未提交 | 区分首次写入失败与未知结果核验失败；版本/范围/权限/字段校验拒绝、网络失败或未执行到原key查询，均不能当作原写结果已确认。保留原key/快照与未决状态，继续阻止丢弃/替换，并说明核验阻塞原因。正式回归覆盖原请求已提交/未提交但核验均被旧版本409拒绝的两条路径，以及再次核验失败；不得只给新版本场景stub一个REPLAYED。真实发布与跨版本核验闭环仍归W4/W6集成，但W3必须先保证核验受阻时不丢依据、不重复创建 |

**验证与边界**：

- 独立正式定向七文件95/0/0/0：字段7、草稿10、围栏2、会话29、填写页12、路由26、交互9，日志`directed-tests.log`。独立生产模块探针5项中3通过/2失败，日志`review-invariants.log`明确记录R02-a的新key可提交、R02-b从unknown转conflict并删除原依据；探针和配置只在审核目录，接口为受控替身，不冒充本轮真实服务验收。
- 新清单覆盖的员工41项浏览器验收、45项整改记录及33项截图/日志逐项哈希一致，抽看分叉草稿和离开确认截图，核对`r04-browser-back.log`及数据库3条记录/3条CREATE/版本1→4日志。本轮未重启浏览器服务、PG或后端；上述浏览器记录未覆盖两个R02组合反例。
- 员工全量171文件结果为865通过/1失败，失败为`customer-issue-records.mount.test.ts`的5000ms超时；单类10/10只证明该次单类执行通过，不能改记全量通过，也不能仅凭重跑断言根因是机器负载。该文件不在本轮六项改动内，本轮不代改其它模块；应另行定位并如实提交当前全量状态，不以重试、加延时或放宽超时掩盖。
- 员工突变日志及复原后的正式测试已核对；未运行黄金、默认后端全量、内网、真实GitLab/机器人/AI或共享业务库。E项、MR汇总及后续阶段不变，不把本轮客户端缺陷扩大为已批准W2的全部重审。

**交接与保护**：员工只继续R02两处修复及相称测试，提交完整差异、新指纹和受影响真实页面证据后重审，R01/R03/R04不重开。审核方只更新本记录与五份文档阶段状态，未改实现、正式测试、迁移、黄金、其它任务文件或服务，未暂存/提交/推送；保留性与护栏检查见审核目录`verification.json`。

### W3-R02二次整改交付记录（2026-10-23）

- **阶段与版本**：R02-a/R02-b两项绕过路径整改；基线`main@84e7c023`混合工作树，本单元未暂存、未提交、未推送；仅改前端会话/页面与相应测试，R01/R03/R04不重开。
- **R02-a复制防线**：[form-filling-session](../../frontend/src/features/collect-forms/form-filling-session.ts)新增`canCopyBlock`（:776），`copyBlock`（:780）在`pending`/`unknown`时拒绝并提示；[CollectFormView](../../frontend/src/views/CollectFormView.vue)复制按钮同源`:disabled`并显示原因（:1310-1313）。已确认原操作成功的`savedElsewhere`分叉副本与仅结构过期的可编辑副本仍可显式复制，不误伤R01/R03。
- **R02-b核验不降级**：`performSubmit`增加`mode: 'submit' | 'verify'`（:960）；`verify`复用提交时，版本/范围/权限/字段拒绝、网络失败及未到达原key查询的任何失败（含409）均保持`unknown`、保留原key与提交快照、继续禁止丢弃/复制，并提示“核验未完成”；同走查人确认被取消时同样保持未知。只读原因与核验阻塞原因改为同时展示（`v-else-if`改为独立`v-if`，:1345-1353）。
- **正式测试**：会话测试+5（共34项）、页面测试+2（共14项），定向5文件67项全绿；覆盖unknown/pending复制拒绝、恢复未知草稿、scope变化、原请求已提交与未提交两条409核验路径、核验阻塞原因与丢弃/复制持续禁用。
- **突变自证**：`canCopyBlock`恒真 → 5项转红（含页面禁用复制）；移除核验模式保护 → 两条409用例转红；两次均原样复原，`form-filling-session.ts` SHA-256恢复为`a48368b5…1c490`。随后为让核验阻塞原因在只读块上可见，将只读/提示告警改为可同时展示，`CollectFormView.vue`最终SHA-256为`29252932…a85e`；改动后定向67项全绿。
- **全量验证**：最终代码`npm run test`为171文件873项全部通过；此前单项5秒超时未再现，未通过延时、放宽超时或单类复跑替代全量。`typecheck`、`lint`、`build`通过。
- **真实页面**：干净夹具上既有验收41/41与整改场景51/51全绿。新增检查：unknown+结构版本2→5后复制按钮禁用且点击不新增块（`r02-copy-blocked-unknown.png`）；刷新恢复未知草稿仍禁止复制；核验因旧版本409被拒后丢弃仍禁用且本机草稿保持`unknown`（`r02-verification-blocked.png`）。数据库侧该次原创建已落库：`form_s2_v2_records` 1条“R02结构变化待核验”、CREATE审计共4条，覆盖“原请求已提交但核验被拒”路径；Issue响应范围版本2→5（W3-2026.12），`code_review`4、`issue_reply`3，code_review三条预期记录各1条。
- **交付指纹**：`.tmp/w3-remediation-20261023/code-evidence-manifest.json`覆盖58项代码与证据（不含`docs/`），SHA-256 `c5fc84e43dc67af60b736ebdfe04780f3430fe07799325c855c21e89c17e4880`；含状态文档的全量清单及逐文件哈希见同目录`input-manifest.json`；相对上轮交付的差异见`manifest-diff-vs-w3-r01.txt`，证据哈希见`evidence/evidence-sha256.txt`。
- **边界**：仅使用隔离PG16容器`127.0.0.1:15601`、后端18090与Vite 18183；未触碰18080/15432/18181/18182、共享业务库、内网或真实GitLab/机器人/AI；未运行黄金门禁。

### W3通过与W4准入记录（2026-10-23）

**结论：W3阶段验收通过，批准W4实施。** R02-a、R02-b关闭，R01/R03/R04及W0/W1/W2批准保持。本次批准填写页与客户端保护，不代表字段发布、跨版本核验集成、消费、黄金或上线已验收。

**批准输入**：`.tmp/w3-remediation-20261023/code-evidence-manifest.json`58项逐项匹配，文件SHA-256 `c5fc84e43dc67af60b736ebdfe04780f3430fe07799325c855c21e89c17e4880`；含五份状态文档的63项完整清单也匹配，文件SHA-256 `7bb31355c8a26406d2788b1e3cc04dd1acbd16870b6f5e575f07030879876a31`。相对上轮生产及正式测试仅四文件变化：会话模块/测试、填写页/测试；草稿存储及其它输入按清单保持。审核前工作树124项状态及五份文档副本见`.tmp/w3-r02-review-20261023/`，本次回填后文档字节不再由送审哈希标识。

| 项目 | 关闭依据 |
| --- | --- |
| R02-a 未知/在途禁止复制 | [会话模块](../../frontend/src/features/collect-forms/form-filling-session.ts)的`canCopyBlock`与`copyBlock`统一拒绝pending/unknown，[填写页](../../frontend/src/views/CollectFormView.vue)同源禁用复制按钮并提示原因。独立原反例现在不生成新块；已确认成功的分叉副本与单纯结构过期草稿仍可显式复制，R01/R03未受影响 |
| R02-b 核验失败保持未决 | `performSubmit`区分submit/verify；核验中的版本、权限、字段或网络拒绝及异常响应保持unknown/frozen、原key和快照，不能降为可丢弃冲突；核验同走查人确认取消也保留未决。独立原409反例现在不可丢弃，内存及本机依据各保留1份；结构只读原因与核验阻塞原因可同时显示 |

**验证**：

- 独立正式定向七文件102/0/0/0，日志`directed-tests.log`；包含表单67项、路由26项和交互护栏9项。补充生产模块探针11/0/0/0，日志`review-invariants.log`；上一轮两个失败反例转绿，并覆盖400字段拒绝、403权限拒绝、409版本拒绝、网络异常、缺失记录的异常响应与核验确认取消。接口替身只证明客户端行为，不记为本轮真实服务复验。
- 员工最终全量171文件873/0/0/0日志`run-full-vitest-r02-final2.log`、构建日志及两份突变日志已核对；复制突变5项失败、核验突变2项失败，复原后的正式测试通过。此前超时本版未再现，未修改超时或增加延时；不据此宣告历史不稳定用例的根因已定位或修复，仍按仓库测试稳定性规则处理。
- 员工真实页面41项与整改51项记录、截图及哈希均核对，抽看`r02-copy-blocked-unknown.png`及`r02-verification-blocked.png`：结构2→5后及整页恢复均不能复制，旧版本409后仍禁丢弃且保留unknown；夹具侧原创建确已写入的证据与4条CREATE审计、范围4/5/3一致。本轮未重启服务、浏览器或数据库，不将证据审阅写成独立浏览器复跑。
- 未运行黄金、默认后端全量、内网、真实GitLab/机器人/AI或共享业务库。58项输入保持；仓库护栏、159条迁移锁及保留性检查见审核目录`verification.json`。

**W4准入边界**：按本单W4及已批准W0发布契约交付完整管理员字段草稿、预览、范围绑定、首次启用、字段增删改/迁移、发布和退役；普通用户不可见且直接请求403，验证停写栅栏、CAS、租约、失败/重启、守恒和旧链接连续性。用真实PG及页面完成至少一次字段变更与迁移，不以夹具改指针代表发布功能验收。沿主方案§3.3、§3.6及W3既有交接，在W4/W6补齐“原创建已成功但响应丢失→发布迁移→旧key核验”的真实闭环；本次通过的是核验受阻时不丢依据、不重复创建的保护，永久unknown不能代替最终结果收敛。采用既定记录身份和幂等契约，不以静默换key或丢弃旧快照解决。

**后续与保护**：W4交付后单独审核，不预先批准W5至W8；MR汇总公式在W5前确认，E1/E4/E5等仍按既定阶段补证，W7黄金差异与发布授权保持原门禁。本轮只更新五份文档的状态及本审核记录，未改生产实现、正式测试、迁移、快照、其它任务文件或服务，未暂存、提交、推送。

### W4交付记录（2026-10-23，待审）

**状态：W4实现与本地验收完成，单独送审；未批准W5，未运行黄金、内网或生产切换。**

**交付范围**：

- 管理接口（`form.template.manage`）：模板/范围列表与详情、新建DRAFT、草稿修订CAS（409/A0409）、绑定保存与首次发布后冻结、预览证据（目标版本预留、差异/DDL/影响/阻断）、发布创建/查询/恢复、退役、仅未发布空范围删除、来源候选与ODS里程碑。
- 受控发布执行器：INITIAL_EMPTY与MIGRATION，PREPARED→COPYING→VERIFIED→PUBLISHED/FAILED，持久任务/attempt、范围停写栅栏（publication+token）、租约与fencing（`clock_timestamp()`）、失败显式恢复重捕获S/truncate目标表/新attempt、过期租约回收、复制事务与切换事务分离；INITIAL_MIGRATION按[D-32](../decisions.md#d-32-统一表单字段发布通用执行器跨版本重放与历史缺值标记)委托既有一次性实现。
- 迁移与守恒：按稳定字段名映射，改名保值、新增列NULL、删除列不复制、可核验无损/扩展类型转换；新增/收紧必填导致历史缺值时写`legacy_incomplete/legacy_missing_fields`且可直接作废；复制与核验共用同一源投影逐行比较，失败回滚。
- 切换与head：目标head只直锁基表按id序加锁，提交前三类计数不变量（FORM权威、目标实例、COMMENT证据为零）。
- 跨版本旧key：先按requestKey查当前权威表，用请求携带旧版本冻结规格校验并重算指纹，命中返回REPLAYED且不新建；新key仍要求当前版本。
- 前端：`系统设置 → 表单管理`页面（字段表格增删改排序、高级JSON、绑定/里程碑、预览、发布轮询、恢复/退役/删除、CAS冲突提示），路由/权限/菜单登记，普通用户不可见。
- 端点目录新增12条collect-forms管理用例并通过黄金覆盖护栏；未重建任何黄金快照。

**验证证据**：

- `FormTemplateAdminIntegrationTest` 9/9、`FormRecordServiceIntegrationTest` 23/23（隔离真实PostgreSQL、Flyway与真实事务）；覆盖空平台首次启用、CAS/绑定冻结、改名+新增+删除迁移、缺少新必填字段的legacy标记与直接作废、旧key重放收敛、失败恢复重捕获、复制中停写与租约回收、退役历史及里程碑证据。日志`.tmp/w4-admin-test.log`、`.tmp/w4-record-test.log`。
- 前端173文件883项通过，`typecheck`与`lint`通过。
- 后端全量（独立容器15602，未污染默认15433）触发1972项：Failures 0、Errors 3、Skipped 1。3项错误均为容器池初始化连接失败（宿主机约3GB空闲、同时驻留38个长期容器），受影响3类立即隔离复跑29/29通过（日志`.tmp/w4-failed-rerun.log`）；未修改或重试掩盖测试。默认15433的public schema残留缺少`mirror_task_id`的旧ODS表会使Flyway在`V20260629_01`建索引处失败，本轮未改动该库或迁移。
- 真实浏览器（隔离PG 15601、后端18090、Vite 18183）18/18：页面创建范围/绑定/首次发布、字段JSON改名+新增+删除、迁移发布、版本历史、过期指针发布409、旧key重放同ID、旧链接读取迁移后记录、普通用户403。证据`.tmp/w4-acceptance/evidence/`含截图、`acceptance.log`、`acceptance-summary.json`、`db-evidence.txt`（v2/v4各1条记录与1个实例、attempt计数0→1、源/目标SHA-256一致、迁移日志`migrated_rows=1`、旧值`RESOLVED/具体原因`保留、新增字段为空、`legacy_missing_fields={}`、当前版本head=1且FORM权威=1、范围栅栏已清空）。
- 仓库检查：`check_worktree_artifacts.py`、`check_runtime_artifact_locations.py`、`check_text_whitespace.py`、`check_flyway_migration_immutability.py`（159条）通过。

**输入指纹**：code-evidence清单文件SHA-256 `98b1b11673b23b84cd2d98d34fcd9e9658ad3fae78eb67c543367721a4251391`，内容摘要`93a16a13110befc6838014866d868ba8347f9ffc3b19d94001fa56e29c9f9d24`（`.tmp/w4-acceptance/code-evidence-manifest.json`，60项；内容摘要对文件路径与SHA-256排序后计算，重生成仅`generatedAt`变化）；含文档的完整清单见`.tmp/w4-acceptance/input-manifest.json`（文档回填后其哈希不再作为送审标识）。

**边界与残留**：写回消费、历史评论切换、W6集成、W7黄金与内网部署、W8切换均未执行；本次未提交、未推送、未修改黄金快照或既有迁移。

### W4首次审核记录（2026-10-26）

**结论：修改后重审，W5未准入。** W0至W3批准保持。管理员主流程、RECORDS迁移、权限与跨版本记录key重放已有实现，但以下六项尚未满足已批准的发布、恢复及输入保护契约。

**审核输入**：`.tmp/w4-acceptance/code-evidence-manifest.json`的60项代码与证据全部匹配，文件SHA-256 `98b1b11673b23b84cd2d98d34fcd9e9658ad3fae78eb67c543367721a4251391`，重算内容摘要`93a16a13110befc6838014866d868ba8347f9ffc3b19d94001fa56e29c9f9d24`一致。含文档的67项清单有三份文档因回填而失配（W4计划、执行单、进度），符合员工已声明的状态文档边界，未将其当成精确送审标识。审核前147项工作树状态及七份文档副本保存在`.tmp/w4-review-20261026/`。

| 编号 | 级别、定位与复验结果 | 整改及验收条件 |
| --- | --- | --- |
| W4-R01 | **P1，切换与创建锁顺序相反。** [FormPublicationTxService](../../backend/src/main/java/com/data/collection/platform/service/form/FormPublicationTxService.java)第365至378行先锁范围/任务，再于`advanceRecordSequence`取得共享序列咨询锁；[记录创建](../../backend/src/main/java/com/data/collection/platform/service/form/FormRecordWriteService.java)第81至84行先取序列锁，再取范围共享锁。真实PG两会话按这两条实际路径交错，`pg_blocking_pids`确认等待后触发SQLSTATE `40P01`，普通创建与发布互相死锁 | 按W0统一锁序调整所有涉及共享序列、范围、任务和head的路径，不能靠捕获死锁后重试或改超时解决。正式PG回归覆盖同范围创建与切换、不同范围并行创建/切换及初始迁移；使用数据库锁等待证据控制交错，拒绝/提交后核对指针、记录、审计、head及栅栏状态 |
| W4-R02 | **P1，旧表首次迁移绕过冻结草稿。** [管理发布入口](../../backend/src/main/java/com/data/collection/platform/service/form/FormTemplateAdminService.java)第383至409行只向一次性迁移传绑定与标签；[初始迁移](../../backend/src/main/java/com/data/collection/platform/service/form/FormInitialMigrationService.java)第106至107行改取种子规格并重写草稿/预览。真实PG在管理员草稿把首字段改为`REVIEW_CONFIRMED_LABEL`并完成预览后发布，返回PUBLISHED，实际字段仍为种子“走查人”，预览目标1也被替换为实际目标3。另外`legacySelectionExists`第977至991行按全库模板计数，未按本范围绑定选择S | 可以复用既有旧表取数/映射，但必须执行管理员已确认的冻结规格、预览及迁移证据，不得静默重建为种子规格；INITIAL_MIGRATION委托不是绕过W0审批契约的例外。路径判定须按当前来源/项目范围的完整S，其他范围有旧行、本范围S为空时应走INITIAL_EMPTY。正式测试覆盖旧表首迁前改名/新增/删除、自定义字段守恒与预览一致、多来源/项目空S分流；阶段协议与失败恢复不能因委托路径缩减 |
| W4-R03 | **P1，恢复未检查完整结构漂移。** [恢复校验](../../backend/src/main/java/com/data/collection/platform/service/form/FormPublicationTxService.java)第847至866行仅比较列名，[复制入口](../../backend/src/main/java/com/data/collection/platform/service/form/FormPublicationMigrator.java)第383至434行只比较列名/类型。真实PG已复制后记录失败，删除目标记录表CHECK `ck_fr_1_2_1`，恢复未拒绝，重新执行返回true且PUBLISHED；缺少约束的表成为当前版本 | 复用W1已批准的完整结构契约检查，恢复和复制使用同一权威校验：列类型/空值/默认值、共享序列、PK/唯一键/FK及动作、CHECK分组/字面量、索引等均需一致；发现漂移不得truncate、切指针或宣告恢复。正式反例覆盖删除/改变CHECK、FK级联、索引/唯一键/默认值漂移及部分目标结构，验证失败后指针与目标数据保持原状，不以删除约束或重建为通过手段 |
| W4-R04 | **P2，发布成功后的同key重放失效。** [创建任务](../../backend/src/main/java/com/data/collection/platform/service/form/FormPublicationTxService.java)第80至110行先检查当前草稿/预览/指针，再查原key。真实PG首次任务PUBLISHED后用原请求原key重放，抛“范围当前版本已变化”，没有返回原任务。W0规定同key同首次载荷返回原任务 | 幂等重放按首次冻结请求核验，不能依赖当前指针/草稿仍未变化；同key异载荷继续409。页面同一次发布重试复用请求key与载荷，不能每次点击都换key。正式回归覆盖PREPARED/COPYING/PUBLISHED/FAILED后重放、草稿或预览变化、响应丢失后的页面核验，确认不生成新任务或版本 |
| W4-R05 | **P1，管理员CAS冲突会直接覆盖本地编辑。** [管理页](../../frontend/src/views/FormTemplateAdminView.vue)第222至226行收到A0409即`loadTemplates`，继而`selectScope`替换所有字段与绑定输入。真实组件/路由探针修改字段标签后触发CAS拒绝，`localInputRetained=false`、覆盖确认0次；现有正式测试反而断言自动刷新。字段行删除、切换范围、主动刷新和离开也需按既定危险操作规则核对 | 冲突保留本地字段、绑定、标签及修订基准，展示权威稿供比较，用户明确确认后才覆盖；取消保留输入。修订保护错误行为的测试。对范围切换/刷新/离开、删除有内容字段或选项等丢弃操作执行既定确认；发布须明确基于已保存且核验的稿，不能用本地未保存标签确认另一个服务器稿。正式页面测试覆盖CAS取消/接受、未保存切换及发布，不能以Toast代替确认 |
| W4-R06 | **P2，过期持有者仍能写失败阶段并撤栅栏。** [fail](../../backend/src/main/java/com/data/collection/platform/service/form/FormPublicationTxService.java)第492至520行仅核对owner/fencing，未核对租约，attempt写入也未绑定有效任务更新结果。真实PG领取后把租约置为已过期，再调用原claim的fail，任务从COPYING变FAILED、范围栅栏变false；W0 §2.3规定过期任务只能由独立维护回收 | 有效持有者诊断与过期租约回收分开，取得锁后按`clock_timestamp()`复核有效租约、owner/fencing及attempt；过期/已接管者不得改阶段、attempt或栅栏。维护回收再按当前归属和token清理。正式PG交错覆盖锁等待期间到期、旧持有者迟到fail、维护回收和新attempt接管，证明旧请求零副作用，并核对W0规定的续租和提交前检查完整性 |

**独立验证及证据边界**：

- 正式后端`FormTemplateAdminIntegrationTest`9项、`FormRecordServiceIntegrationTest`23项合计32/0/0/0，BUILD SUCCESS，日志`directed-backend.log`；使用测试自身Testcontainers隔离库，没有使用15433或共享业务库。正式前端管理页、字段模型、路由及交互护栏四文件45项通过，日志`directed-frontend.log`。
- 五项真实PG契约探针均失败：旧表草稿、恢复约束、终态key重放、两会话锁顺序见`probe-results.json`/`probe-run.log`；过期fail见`lease-probe-results.json`/`lease-probe-run.log`。探针直接调用当前Spring事务代理和生产类，创建/写入另一会话的锁前缀按当前生产顺序执行，死锁由PostgreSQL检测，未以休眠猜测交错。管理员CAS的真实组件契约断言失败，见`review-ui.log`；API为受控替身，仅证明客户端输入丢失。
- 首轮事务探针在隔离夹具清理时遗漏旧审计外键CASCADE，四项未进入业务验证；修正临时夹具后才执行到上述四项真实反例。原失败日志/JSON保留为`probe-fixture-failure.*`，不计产品缺陷。全部探针、编译产物与配置只在审核目录，未改生产或正式测试；整改时应把反例转为正式行为测试。
- 员工浏览器18项及9张截图、数据库守恒摘要和60项指纹已核对，抽看迁移预览和普通用户截图；认可已记录的主流程与403、跨版本记录key收敛证据，但它们不覆盖上述六项。未独立重启浏览器或业务服务。
- 员工全量日志确为1972项、Failures 0/Errors 3/Skipped 1，三处包含数据库连接/初始化错误；29项隔离复跑日志为通过。资源不足是员工给出的解释，本轮未定位该根因，也未用复跑替代完整全量通过。整改后如实交付同版本全量结果；不得清理其它AI容器、修改共享库、放宽超时或忽略失败求绿。
- 未运行黄金、内网、真实外部调用、事实消费或共享业务库；W5至W8、MR汇总和E项保持原阶段。审核探针自建三个容器（含首轮夹具失败）均按ID核对已删除，见`cleanup.json`，其它服务未触碰。

**交接与保护**：员工仅继续W4-R01至R06整改，提交相对本次输入的完整差异、新指纹、正式回归及受影响真实页面/事务证据后重审。W0至W3不重开；W5未准入。审核方只更新审核记录和七份文档的阶段状态，未改实现、正式测试、迁移、黄金、其它任务文件或服务，未暂存、提交、推送。保留性及护栏检查见审核目录`verification.json`。

### W4整改交付记录（2026-10-26，待复验）

**结论：R01至R06及R07整改已实现并完成员工侧验证，等待审核方复验；W5未准入，W0至W3批准保持。** 证据包`.tmp/w4-remediation-20261026/`，代码/证据变更摘要见`evidence/change-summary.json`。

| 项 | 整改落点 | 验证 |
| --- | --- | --- |
| R01 | 创建与切换统一“记录序列咨询锁（如涉及）→范围行→发布任务行→head行（按id）”，移除`advanceRecordSequence`旁路 | `FormRecordServiceIntegrationTest`同范围创建/切换、不同范围并行、首迁交错，`pg_blocking_pids`等待与指针/记录/审计/head/栅栏核对，26/26 |
| R02 | 删除`FormInitialMigrationService`及请求/结果模型；INITIAL_MIGRATION进入通用阶段机，`FormLegacyRecordMigrator`按冻结field_spec复制，按绑定S判定空/非空，写LEGACY版本、切换清单与迁移日志 | `FormInitialMigrationAdminTest`冻结改名/新增/删除、空S分流、切换锁序，4/4；`FormSchemaMigrationTest`26/26 |
| R03 | `FormVersionStructureVerifier`统一类型/空值/默认值、PK/唯一/FK动作、CHECK表达式与字面量、索引、共享序列；启动、复制前与恢复共用 | 管理集成测试覆盖CHECK/索引/FK级联/default漂移、半表、复制前拒绝truncate，14/14 |
| R04 | `createPublication`先按`(scope, requestKey)`查首任务并核验冻结载荷，同载荷返回原任务（含PUBLISHED），异载荷409；页面复用key与预览 | 管理集成测试发布后同key重放与异载荷409；前端请求key复用组件测试 |
| R05 | CAS冲突识别真实HTTP 409（`HttpRequestError`或`ApiBizError`），保留本地字段/绑定/标签/基准并展示服务器稿，显式选择后才覆盖；切换/刷新/离开/删除字段与选项确认；未保存不得发布 | 真实页面22/22含冲突保留、显式覆盖、刷新取消保留、未保存禁发布；组件9项；突变自证还原后测试失败 |
| R06 | `fail`锁内以`clock_timestamp()`复核stage/owner/fencing/attempt与有效租约，失效持有者零副作用，回收独立结算 | 管理集成测试过期租约失败零副作用与回收后新attempt |
| R07 | 管理页仅编辑显示名称、填写方式、必填、选项与顺序；内部键/列/JSON/SQL由系统维护；预览输出中文业务变化与历史影响，DDL折叠；改名保留身份与历史值 | 真实页面覆盖直接编辑、新增/删除、中文预览、历史影响与DDL折叠；后端迁移测试覆盖改名保值、新增为空、删除不复制 |

**正式验证**：

- 后端全量（独立库15602、Testcontainers PG16）：2002项，Failures 0、Errors 0、Skipped 1（既有跳过），日志`.tmp-logs/w4r-backend-full.log`；表单定向70项全绿。
- 前端：`typecheck`、`lint`、全量`npm test`173文件/889项、生产构建通过；表单定向16项。
- 真实页面（独立PG15601、后端18090、Vite18183、真实Chrome）：22/22，8张截图、`acceptance.log`、`acceptance-summary.json`、`db-evidence.txt`；数据库显示范围ACTIVE、版本PUBLISHED、任务INITIAL_EMPTY/PUBLISHED/attempt 1、栅栏已清、草稿保留原字段身份与新字段稳定键。
- 真实页面发现并修复：后端409以`HttpRequestError`到达，页面原只识别`ApiBizError`，导致R05冲突被当普通错误；已修复并补真实传输层组件测试与突变自证。
- 指纹：`code-evidence-manifest.json`、`input-manifest.json`与`evidence/change-summary.json`；相对上轮交付的新增16项、修改11项、删除3个旧一次性迁移源文件。最终文件SHA-256在复验包生成后由交付报告给出（写入本记录前的清单）。
- 环境边界：仅使用独立PG15601/15602、后端18090、Vite18183与独立Chrome配置；未触碰18080/15432/18181/18182、共享业务库、内网、真实GitLab/机器人/AI；未运行黄金；未提交、未推送。Docker Linux VM失联由另一AI重启恢复，本轮未清理任何其它AI容器。

### W4整改复验记录（2026-10-26）

**结论：修改后重审，W5未准入。** 本轮关闭R02（冻结草稿首迁与空S分流）、R03（完整结构漂移检查）、R04（终态发布key重放）、R06（过期持有者失败零副作用）；R01、R05、R07仍有下列可复现问题。W0至W3批准保持，关闭项绑定本次输入，后续变更仍复验受影响范围。

**输入**：`.tmp/w4-remediation-20261026/code-evidence-manifest.json`共78项、3个删除源文件，文件SHA-256 `0e486d8cf19786758b03f8903551ac2dfdfc0301d4f40198b1e2d876e164ab94`，复算内容摘要`7fd185eb5903e4a6e6d3ab7e0659f42e97cc073516f0fca33063d045c86cd7ad`；完整84项清单文件SHA-256 `8d6f8cda164a136361bf340b9d2c722a87d4718cec4f14ed71f7e07e35121a4d`，内容摘要`881d66f728a9e864a6b909b2ac64c36837ca0f6125b0c227d6a122791ba01bbf`。逐文件与删除项均匹配，复算见`.tmp/w4-rereview-20261026/input-verification.json`。变更摘要中的`currentDigest`仍为另一口径`5330b3d2…`，下一次注明口径或去除对包含自身文件的清单摘要的循环引用；本次以已复算清单为准。

| 编号 | 复验发现及影响 | 修改后复验条件 |
| --- | --- | --- |
| W4-R01 | **P1，复制仍存在反向锁序。** [copy](../../backend/src/main/java/com/data/collection/platform/service/form/FormPublicationTxService.java)第305至307行未先锁范围，续租先取得任务行锁；[copyInstances](../../backend/src/main/java/com/data/collection/platform/service/form/FormPublicationMigrator.java)第133行随后插入实例，其范围外键取得范围行KEY SHARE。另一工作者延迟领取按范围→任务锁定时形成环。真实PG用生产copy与领取入口相同的范围→任务锁前缀交错，`pg_blocking_pids`确认等待后报`40P01`，定位到实例范围外键。创建/切换测试通过不证明完整发布协议无死锁 | 将复制的显式锁和外键隐式锁纳入统一协议，覆盖INITIAL_MIGRATION与MIGRATION、延迟重复领取及回收/恢复竞争。真实PG受控交错证明无死锁、无错误发布、失败时守恒且可恢复；不得以忽略或重试死锁代替根因处理 |
| W4-R05 | **P1，保存等待期间的新输入被无确认覆盖。** [saveDraft](../../frontend/src/views/FormTemplateAdminView.vue)第379至385行成功后重建字段行并加载服务器稿，输入控件仍可编辑。真实Vue/路由组件探针：发送“已发送名称”→等待期间改为“等待期间继续输入”→原保存成功，页面回到“已发送名称”，确认0次，后续输入丢失。CAS冲突保留已修复，但保存成功路径仍破坏输入保护 | 保存时捕获输入修订/范围身份，迟到响应不得覆盖更晚输入或其它范围；可保留后续输入与未保存状态，或在适当提交阶段冻结编辑并明确状态。覆盖保存成功、发布完成刷新、切换范围及迟到响应；确认取消仍保留。正式测试使用真实传输异常与可控异步响应 |
| W4-R07-a | **P1，删除后新增复用身份，污染历史值。** [createFieldRow](../../frontend/src/views/form-template-admin/form-template-admin.ts)第177行只查当前行并按长度分配`new_field_N`，[addOption](../../frontend/src/views/form-template-admin/form-template-admin.ts)第228行同样复用`OPTION_N`。生产函数探针删除第二个已存字段/选项后新增，均复用原键。真实PG再以该函数产生的复用身份发布，预览把删除/新增误判为LABEL，新增字段历史值实际为“应删除的历史值”，未留空；选项复用也会将旧记录关联到新选项含义 | 新增字段和选项分配不可复用的稳定身份，删除不释放已持久化身份，显示名称修改继续保持原身份。覆盖同次编辑删除后新增、保存/刷新后新增、跨版本与管理员冲突；真实迁移验证新增历史NULL、旧值不串入新字段，删除选项后的旧值不解释成新选项 |
| W4-R07-b | **P2，业务界面要求尚未完整落地。** [条件区](../../frontend/src/views/FormTemplateAdminView.vue)第849至853行只有“系统维护”列表，无已约定的受支持条件编辑；[conditionSummaries](../../frontend/src/views/form-template-admin/form-template-admin.ts)第328行误解真实条件DSL。读取实际种子规格的探针得到`resourceSeverityLevel`以及“field为affected_kinds、contains为KNOWN”等技术文字。版本历史第1025至1026行仍直列物理表名，折叠区第976至982行仍提供列/类型/DDL，与用户明确的业务字段管理边界不符 | 三模板在业务名称和选项下编辑已有受支持条件，不把已承诺功能收窄为系统只读；按真实DSL翻译条件、互斥及布尔值。工程元数据移出日常管理流程，后台/工程审核证据仍保留。验收覆盖Issue响应/回复实际条件、版本历史及展开后的完整页面，不以只翻译预览或默认折叠作为通过 |

**独立验证及边界**：

- 正式后端四类70/0/0/0、BUILD SUCCESS，使用各类自身Testcontainers数据库；正式前端管理页与字段模型两文件16项通过。日志`directed-backend.log`、`directed-frontend.log`。首个Maven命令JAVA_HOME误指JDK父目录，未启动测试；修正到已安装JDK后才运行，原输出保留`backend-launch-env-error.log`。
- 真实PG两项反例：`probe-run.log`/`probe-results.json`验证复制锁序，`migration-probe-run.log`/`migration-probe-results.json`验证复用身份的真实迁移后果。调用当前Spring事务代理与生产类，仅夹具和反例位于审核目录；锁交错以数据库等待关系控制。两个自建容器已按ID确认删除，未触碰员工保留的验收卷或其它容器。
- 前端六项反例为字段/选项身份、两个实际Issue条件显示、保存期间继续输入及版本历史元数据，见`ui-contract-probes.log`。保存探针首次刷新替身返回旧修订，随后改为返回实际已保存修订，仅复验该场景仍失败（`late-edit-confirmation.log`，确认0次）；不依赖旧夹具证明丢失。
- 员工全量2002/0/0/1、前端889项和页面22项日志/清单已核对，抽看业务预览与CAS截图。认可所覆盖路径，未把全绿推及上述反例；本轮未独立重启浏览器或业务服务。既有条件跳过不影响本次70项定向，W5至W8与E项保持原阶段。
- 员工披露早前误触默认15433并停止，称只读核查未新增迁移历史；本轮未访问该库，不能把“无新迁移”扩大为已证明其它表零变化。隔离交接后必须显式核对测试目标，防止再次误用共享库。未运行黄金、内网、真实外部调用或共享业务库。

**立即交接**：当前阶段已封包，用户确认的安全交接点已到。员工先执行下文隔离交接，核实活动会话、改动归属和基线，保存可恢复副本，在专属任务worktree继续R01/R05/R07；当前主树不得追加业务修复。已完整验证的独立工作单元可按AGENTS阶段提交并双远端推送，失败/未完成的W4保留可恢复差异，不得整树提交、合入main、出正式包或进入W5。审核方只维护审核记录和阶段状态，不代做业务实现或提交。

### W4界面要求补充（2026-10-26，用户确认）

**W4-R07，业务字段管理界面待整改。** 用户针对迁移预览截图中的`fix_status / LABEL`、`review_comment / ADDED`等提出明确要求：管理员管理表单中看得见的业务字段，底层定义由系统维护。该要求补充首次技术审核，R01至R06结论不变；产品要求归属[产品文档表单采集](../product.md#表单采集目标定位2026-10-20-用户明确范围)，设计及边界见[主方案§3.4](unified-form-system-plan-20261009.md#34-管理员字段管理与受控版本发布机制)。

- **整改范围**：三模板字段编辑、条件/选项配置、变更预览、删除与发布确认、校验和结果提示，均围绕业务名称；新增字段自动生成稳定标识及受控映射，改名保持身份。内部键、列名和存储类型不作为编辑项，移除高级JSON编辑入口，SQL/DDL留作后台执行及工程审核证据。
- **复验条件**：正式组件测试和连续真实页面证据覆盖新增、改名、条件/选项调整、排序、删除、预览和发布；管理员全程无需理解或填写技术字段，变化使用中文及修改前后业务内容，历史影响和错误可按业务字段定位。同时核对新增身份独立、改名身份与可映射历史值保持；仅替换截图中的显示文字不算关闭。
- **本次边界**：审核方只同步产品要求、主方案、W4工作单元及当前状态，未修改业务代码或测试，未运行服务、业务测试或黄金；由员工实现后提交复审。W4仍未通过，W5未准入。

### 阶段完成后的隔离交接（2026-10-26，用户要求）

**状态与触发**：W4整改已封包并于2026-10-26复验退回，隔离交接条件已触发，员工下一步先隔离、再修复；审核方负责下达要求和复核，员工负责隔离与验证。先核对活动会话并到安全点，不移动在写文件、不在共享目录切换分支，不提前判定W4通过或准入W5。仓库级规则以[AGENTS的Git规则](../../AGENTS.md#git-规则)及[并行任务与发布隔离](../../AGENTS.md#并行任务与发布隔离)为准，不另建规则副本。

1. 核对任务基线、全部已跟踪/未跟踪改动及活动会话，按文件和必要的差异块确认归属、共享修复与真实依赖；先保存可恢复副本和清单。已存在的隔离候选先核对并复用，不以当前本地main自动代表稳定基线。
2. 在相关写入停止的安全点，从核实的稳定提交建立或复用任务分支和独立worktree，登记目录、分支、基线及责任会话。仅迁移归属明确的任务差异；共享文件按差异块及依赖处理，归属不明时保留原状并报告。不得整树提交、整体覆盖、回退或清理他人文件。
3. 按真实依赖组织职责单一、完整且可回退的阶段提交；每批在隔离后的准确版本验证，代码、迁移、测试与文档自洽，符合当前黄金快照分阶段约束。历史阶段批准不自动证明拆分后的版本可运行。任务分支提交/双远端推送用于保存已验证进展；稳定主线、正式包及上线仍须独立满足发布条件。
4. 按AGENTS隔离数据库、端口、Compose与运行资源，核对迁移版本/校验和/升级路径；共享修复单独验证后由所需任务吸收。已完成的其它任务仅携带目标功能和必要依赖独立验收，不混入尚未完成的表单或同步改造。
5. 交付任务归属及依赖清单、可恢复副本位置、目录/分支/基线/提交映射、迁移冲突检查、逐提交测试证据、双远端核对结果及未完成门禁；同时证明其它任务改动和运行环境被保留。审核绑定隔离后的版本，新增差异复验受影响范围。

**恢复入口与边界**：先读当前AGENTS、项目进度及本执行单，运行`git status --short`和`git worktree list --porcelain`，确认当前阶段及活动会话后再操作。旧计划中的“不提交/不推送”继续约束当前在写的混合目录；阶段交付后按本节及更新后的AGENTS处理任务分支提交。此项不授权绕过W4审核、黄金确认、生产部署或转权门禁，也不要求现在运行黄金。

### W4二次整改交付记录（2026-10-26，待复审）

**状态：待复验，W5未准入。** 员工按上一节隔离交接在专属worktree完成R01/R05/R07二次整改与验证；R02/R03/R04/R06不重开，W0至W3批准保持。本记录只陈述交付与证据，关闭结论由审核方复验决定。

**隔离与基线**：目录`D:/projects/dcp-form-w4-20261026`，分支`codex/unified-form-w4-isolation-20261026`，基线`84e7c023`（main）；主树可恢复副本见`.tmp/w4-isolation-20261026/`（patch、untracked tgz、stash`c9c4f9b…`）。数据库/端口：PG`15601`、后端`18090`、Vite`18183`、测试库`15602`；未使用`18080/15432/18181/18182`、共享业务库、内网或真实GitLab。完整步骤见`.tmp/w4-r2-20261026/environment.md`。

**逐项处理**

- **R01**：[copy](../../backend/src/main/java/com/data/collection/platform/service/form/FormPublicationTxService.java)在任何续租/任务行锁定前先锁范围行，覆盖复制插入实例时外键对范围行的隐式KEY SHARE；统一锁序为记录序列咨询锁（如涉及）→范围行→发布任务行→head。新增INITIAL_MIGRATION与MIGRATION两条真实PG受控交错回归（另一事务按“范围→任务”前缀延迟领取并锁发布行），保留为正式测试；移除范围锁的突变在真实PG复现`40P01`，恢复后通过。
- **R05**：保存启动时捕获范围身份与输入快照；响应返回后若范围已切换，只刷新列表，不写当前表单；若本地快照已变化，保留新输入并提示“等待期间的新输入尚未保存”，不重建字段行；发布轮询完成时若仍有未保存修改，只刷新范围元数据并提示，不覆盖输入。组件测试与真实页面（CDP请求级暂停）覆盖保存成功、切范围迟到响应、发布完成刷新，确认取消路径保持。
- **R07-a**：新增字段/选项使用不可复用的随机稳定身份（`f_<12hex>`、`OPT_<12HEX>`），删除不释放身份，重命名沿用原身份；覆盖同次删除后新增、保存/刷新后、跨版本与管理员冲突。后端冻结规格迁移回归验证新增历史NULL、旧值不串入；真实页面与数据库草稿证据见下。
- **R07-b**：三模板既有受支持条件以业务名称和选项编辑（条件必填、包含/不包含/等于、布尔值、互斥、否则清空），保存写回真实根级DSL并收敛字段级重复；Issue回复4条条件与Issue响应严重级别条件均已验证。日常界面移除折叠DDL、物理表/列与原始字段键，预览与版本历史只展示业务变化、版本标签、状态和发布时间；`snapshotColumn`等工程信息仅保留在后台与预览证据。展开后的完整页面、预览和版本历史扫描无工程元数据。
- **验收发现的保存阻断**：词典型`enum-array`（Issue回复“缺陷原因”）被前端“至少需要一个选项”校验拒绝保存；HTTP显式`null`发布依据被后端当作非对象拒绝。已分别按词典来源跳过内联选项校验并保留词典（不写空options）、把显式null归一为缺省，并补正式测试。

**验证**

- 后端全量（独立库15602、真实PG16）：2005项，Failures 0、Errors 0、Skipped 1，`BUILD SUCCESS`；表单定向73项（记录27、管理15、首迁5、迁移26）。
- 前端：`typecheck`、`lint`、全量172文件/894项、生产构建通过。
- 真实页面：41/41通过（Issue回复/响应两范围，PG15601/18090/18183），证据`.tmp/w4-r2-20261026/evidence/`（11张截图、日志、41项清单、数据库草稿证据）。
- 突变自证4项（R05迟到响应保护、字段身份、选项身份、字段级条件收敛）均使正式测试转红，恢复后原样通过；备份在`.tmp/w4-r2-20261026/mutation/`。
- 仓库护栏：worktree/runtime artifact、文本空白、Flyway 159条校验和与`git diff --check`通过。

**指纹**：code-evidence清单139文件（新增92、修改30、删除17），文件SHA-256与内容摘要见交付报告；清单、输入与变更摘要位于`.tmp/w4-r2-20261026/evidence/`。

**未执行与边界**：未合入main、未出正式包、未进入W5、未运行黄金基线，未访问内网、真实GitLab、共享业务库或机器人；未删除其它任务容器或改动其它AI服务。W4最终准入以审核复验为准。

### W4二次整改复审记录（2026-10-26）

**结论：修改后重审，W5未准入。** 本轮关闭R01（复制范围锁与外键隐式锁顺序）及R07-a（字段/选项新增身份）；R02/R03/R04/R06保持批准。R05的已覆盖迟到保存成功路径已修复，但刷新与冲突路径仍未守住上下文；R07-b已补业务条件编辑并移除物理表/DDL，实际条件执行及部分控件显示仍未满足契约。W0至W3批准保持，不以新增失败反例重开已关闭项。

**审核对象与隔离**：专属目录`D:/projects/dcp-form-w4-20261026`，分支`codex/unified-form-w4-isolation-20261026`；基线`84e7c023449de190e722d79d027accb062358172`；依次提交`729c235a`后端、`f661cbc9`前端、`727ed1e718a9212ea52511bce11da97bbae74d06`文档，双远端`gitlab25`/`origin`独立回读均等于该HEAD。清单139项（含8项文档）与基线至HEAD的全部差异路径、文件及删除项一致，摘要复算一致；主树HEAD未变、原有混合改动保留。`c9c4f9b70ec4251a158b6ebf51f8a74edec86ad3`恢复对象存在，patch及未跟踪归档保留于主树`.tmp/w4-isolation-20261026/`。认可已建立的隔离与阶段提交，不要求重新拆分或改写历史。

清单文件SHA-256 `e1357a9c7a0204a6309b946e3e2f78ab342a5aebdca05eb37bb5df38f5e2d373`，内容摘要`8ffdfac0e3ae717bcc7a140e5916c4b3ded59840ef69d766e01b67b188984e96`；输入清单SHA-256 `f757868d00726f09f94950e521f62b8b7b5881d7438790ed25aff24f114317ac`，变更摘要文件SHA-256 `1fe274cf090a09abb3108681aa0871936d02034ccb7c8266f117d39bc49f9ad2`。核验记录位于主树`.tmp/w4-r2-review-20261026/`。

| 编号 | 已复现问题及影响 | 修改后复验条件 |
| --- | --- | --- |
| W4-R05 | **P1，异步保护仍有缺口。** [pollPublication](../../frontend/src/views/FormTemplateAdminView.vue)第703至713行只在开始刷新前检查dirty；[selectScope](../../frontend/src/views/FormTemplateAdminView.vue)第214至222行收到详情后无条件hydrate/markSaved。发布已完成、最终刷新在途期间输入，响应覆盖新输入。另[saveDraft catch](../../frontend/src/views/FormTemplateAdminView.vue)第550至554行按当前scope查询冲突稿，未绑定原请求：A保存在途→切B→A冲突后开始取B稿→切回A→B稿迟到，点击“载入服务器版本”后，A范围下显示B字段/修订。两项真实Vue/路由组件探针均失败 | 将范围身份、编辑会话/加载代次、输入修订与请求快照统一应用到成功、失败、冲突二次查询、发布轮询及最终刷新；回写前复核上下文，不能仅在发起请求前检查dirty或仅比较scopeId。迟到结果不得覆盖新输入、回退修订或把其它范围草稿装入当前范围。正式受控异步测试及真实页面覆盖上述两个完整交错、A→B→A与取消确认 |
| W4-R07-b（执行契约） | **P1，页面配置与实际约束不一致。** [DDL生成](../../backend/src/main/java/com/data/collection/platform/service/form/FormVersionSchemaService.java)第414至425行忽略`withAnyOf`，以“存在触发项且总数>1”替代选定互斥组合。新增自定义多选甲/乙/丙，仅配置甲与乙互斥；冻结发布成功、服务字段校验接受甲+丙，实际写入被CHECK拒绝，PG `23514`。原Wiki字段/规则未改。另一真实PG反例：编辑器可选数字字段作条件必填目标，预览无阻断，发布因第459行对integer生成`btrim`而FAILED。[条件校验](../../backend/src/main/java/com/data/collection/platform/domain/form/FormSpecValidator.java)仅检查引用，不校验类型/操作数支持组合 | 编辑器、规格校验、DDL、填写校验及迁移使用相同的有类型条件契约。合法互斥集合必须按冻结`withAnyOf`执行；不支持的字段/目标/操作数组合在草稿或预览阶段用业务说明阻断，不得清洁预览后发布失败或保存时才由数据库拒绝。覆盖自定义甲/乙/丙所有组合、数字/布尔/人员/文本目标支持边界、条件修改后的历史迁移；不借此改变既有Wiki业务规则 |
| W4-R07-b（业务显示） | **P2，真实Issue填写方式仍显示内部控件值。** `CONTROL_OPTIONS`与真实种子的`multi-select`、boolean+`radio`、`checkbox`组合不匹配；[controlOptionsFor](../../frontend/src/views/FormTemplateAdminView.vue)按type筛选后未包含当前值，选择器回显技术字符串。员工截图`04-condition-edited.png`中可直接看到这些值，管理组件夹具只含少量字段未覆盖完整种子 | 按三模板真实已发布字段规格和字段类型显示中文填写方式，编辑选择与保存后的回显一致；不能用原控件值作为业务标签兜底，也不为改显示而丢失冻结字段身份/原值。正式页面与真实完整种子覆盖词典多选、布尔单选、复选及新增字段 |

**交接与证据补齐**：

- 该worktree的AGENTS仍为旧版29,567字节（SHA-256 `08b8a26d…df595b`），主树用户确认版本为26,361字节（`dbac2c25…79f061`），隔离条款没有随新树补齐。员工在同一任务分支先同步最新已确认规则，核对恢复线索与环境归属，再继续修复；不回主树编码、不覆盖其它任务。
- 31项输入证据中只有`.tmp/w4-r2-20261026/backend.log`哈希不符；运行日志没有冻结到清单所指的内容，追加原因未独立判定。本次代码139项均匹配，不据此推断代码变化；下一版在停止写入后冻结日志并生成清单。清单中的捕获HEAD为提交前基线，须另给最终HEAD与清单的对应关系。
- 员工报告前端172文件/894项；可见`.tmp/w4-r2-20261026/logs/.tmp-fe-tests.log`为172文件/893项且不在31项证据清单内。本次独立25项通过；下一版提供最终准确版本的完整日志与指纹，不把缺失证据表述为已独立核实894项。

**验证与保护边界**：独立正式后端四类73/0/0/0、BUILD SUCCESS，前端两文件25项通过；未再跑默认全量。员工后端全量2005/0/0/1日志已核对，既有条件跳过单列；页面41项与11张截图清单已核对并抽看条件/版本历史。本轮补充两项异步组件反例与两类真实PG条件反例失败，证据`async-probes.log`、`probe-results.json`及`custom-probe-results.json`。互斥反例先沿既有字段验证，再改为独立新增字段，最终仍失败，不将前一夹具当作修改Wiki的依据。探针只在审核临时目录，调用当前生产类和Spring事务代理；两个自建PG容器按ID确认删除，正式测试使用自身Testcontainers。未改业务实现、正式测试、迁移、快照或服务，未运行黄金、内网、外部调用或共享业务库，未提交/推送审核改动。

**下一步**：员工复用当前隔离目录和分支，补齐AGENTS及证据封包后整改R05/R07-b，以新增提交和新指纹复审，不改写已推送的三个提交。只有复验通过才裁定W4与W5准入；阶段提交、双远端同步及本轮部分关闭不构成main合入或正式发布批准。

### v5.1员工第三次送审材料（历史输入，复审以随后记录为准）

### W4三次整改交付记录（2026-10-26，待复审）

**范围**：按[二次整改复审记录](#w4二次整改复审记录2026-10-26)条件在现有隔离目录与分支上以新增提交修复R05/R07-b，并补齐AGENTS同步、冻结日志与准确定量证据。未改既有Wiki业务规则，未改写已推送提交（`729c235a`/`f661cbc9`/`727ed1e7`）。

**R05（异步上下文统一防护）**：
- 管理页引入范围加载代次（`scopeLoadEpoch`）、输入快照与范围身份三重校验，统一应用到保存成功、保存失败、冲突二次查询、发布轮询与手动/最终刷新：回写前复核代次与范围，不再仅在发起请求前检查dirty。
- 手动刷新与发布完成刷新共用受保护刷新：刷新在途时继续输入，迟到详情只更新范围元数据并提示“新输入已保留”，不覆盖输入、不回退修订；保存冲突二次查询绑定原请求的范围与代次，迟到响应丢弃，不再按当前范围取稿。
- 真实页面（CDP请求级暂停）覆盖：保存迟到响应、保存在途切换范围、发布轮询完成保留输入、**最终刷新在途输入保留**（发布完成后点击“刷新状态”，在最终刷新GET暂停期间输入并放行，断言输入保留且未保存提示存在）、**冲突对话框正常路径**（外部修订后保存出冲突框，保留本地并再次保存成功）、**A→B→A迟到冲突**（冲突二次查询暂停期间切B再切回A后放行，断言不弹冲突框、A范围字段未被其它会话草稿覆盖、B范围草稿未被污染）。

**R07-b（条件执行契约与业务显示）**：
- 编辑器、规格校验、受控DDL、填写校验与历史迁移共用同一有类型条件契约：互斥按冻结`withAnyOf`生成显式冲突值CHECK（不再以“总数>1”替代）；必填/清空目标按字段类型生成表达式（数组用cardinality、数值/布尔/日期用`is not null`、文本/枚举/人员用`btrim`）；规格校验拒绝不支持的字段/操作数组合并给出业务文案，运行时提示使用字段中文名。
- 条件收紧后的历史迁移：新条件违反的旧行按`legacy_incomplete=true`+`legacy_missing_fields`复制，值保留，不再因CHECK拒绝导致发布FAILED。
- 真实页面：新增自定义多选字段（甲/乙/丙）配置“甲仅与乙互斥”、新增数字字段配置布尔条件必填，发布成功（此前数字目标必发FAILED）；填写页选择乙时甲被冻结规则自动清除（即时纠正）；甲+丙组合与数字0提交成功。数据库证据：`ck_fr_1_1_19`为`NOT((甲=ANY(col)) AND (乙=ANY(col)))`（仅乙，withAnyOf冻结）、`ck_fr_1_1_20`为数值目标`IS NOT NULL`；反向UPDATE甲+乙被CHECK拒绝（23514类）且原值不变，见`evidence/db-r3-evidence.json`。
- 真实Issue回复/响应页面填写方式列全部显示中文业务名称（多选/单选/复选/数字/多行文本/日期/版本成员等），页面全文无`multi-select`/`radio`/`checkbox`/`version-members`/`person-select`/`textarea`技术控件值；新增字段编辑选择与保存后回显一致，冻结身份不丢失。

**验证**：
- 后端全量（独立库15602、真实PG16）：`Tests run: <后端全量数>, Failures: 0, Errors: 0, Skipped: 1`，`BUILD SUCCESS`（冻结日志`logs/backend-full.log`）。既有`FormTemplateSpecCatalogTest`断言的旧互斥DDL语义（“总数>1”）随本次修复同步更新为冻结`withAnyOf`语义，未弱化断言。
- 前端：`typecheck`、`lint`、全量172文件/899项、生产构建全部通过（冻结日志`logs/frontend-full.log`）。
- 真实页面：66/66通过（隔离PG15601/后端18090/Vite18183，17张截图、`acceptance-summary.json`、`acceptance.log`、`db-r3-evidence.json`，证据目录`.tmp/w4-r3-20261026/evidence/`）；其中包含二次复审要求的两个完整交错与A→B→A场景。
- 突变与边界：本轮未运行黄金基线、内网、真实GitLab、机器人/AI、共享业务库或生产切换；未操作其它任务容器与服务。

**交接与证据**：AGENTS已同步主树用户确认版（26,361字节，提交`972bc502`）；日志在服务停止写入后冻结并计入输入清单；代码清单按基线`84e7c023`至最终HEAD生成并附HEAD对应关系（见交付报告）。

### W4三次整改复审与v6交接（2026-10-27）

**结论：修改后重审，W5仍未准入。** 按本轮冻结的v5.1输入核验，不以待交接v6方向反向改变验收。R07-b执行契约与中文填写方式关闭；R01/R02/R03/R04/R06/R07-a保持关闭；R05的保存冲突及最终刷新缺陷已修，但预览和发布任务的跨范围异步归属仍未闭合。

**输入与证据**：员工干净分支`codex/unified-form-w4-isolation-20261026`，HEAD `979502227d4ea1950c02c05a0c9fa341e0942df5`，origin/gitlab25独立回读一致；141项完整提交差异（93新增/31修改/17删除）与29项输入逐文件核对零漂移。代码清单SHA-256 `24a75a8fa1a893c1aea45552d5fa3a598c7c8cf208c5f8a01d67beb39a4eace2`，内容摘要`c934d33eeea1491a4d4e881fe54d3e8a493c29914d2f19c2856a8568066c21c0`；输入清单`129825d8fbe7b1c616a102bc4dcd1fbe540047a980c044258782de56d4de878b`，变更摘要`a7d179450e2588f0cca664c169ca605131f3b8c98c6577d4fb84d1bf2ff2255b`。本轮日志已冻结，2009/0/0/1、172文件899项及typecheck/lint/build均与员工报告相符；页面总结66项及17张编号截图，另有历史failure.png不充当最终通过证据。AGENTS与当前主树确认规则一致，26,361字节。

| 项目 | 复验结论与证据 | 后续要求 |
| --- | --- | --- |
| R05已修部分 | 正式异步回归通过；独立补充的最终刷新在途输入仍保留。保存失败/冲突二次读取绑定原范围与加载代次，A→B→A旧冲突不再污染当前草稿 | 保留回归，不因v6调整丢失保护 |
| R05剩余P1：预览归属 | `FormTemplateAdminView.vue`的previewScope第686—690行在await后直接写preview，没有原scope/会话/草稿修订复核。A发起预览→切B→A响应迟到，B标题下显示A专属阻断。真实Vue/路由组件受控Promise探针失败 | 预览使用原范围、加载代次、草稿修订及输入快照；回写前和后续发布使用前均复核。不把A的结果当作B的可用预览 |
| R05剩余P1：任务归属 | pollPublication第770—815行将迟到任务直接写publication，终态又读取当前scope。A任务轮询在途→切B→A返回FAILED，B下出现A的任务#5及恢复按钮；独立探针点击后实际调用`recoverFormPublication(5)`。当前后端权限仍校验管理员，不据此宣称已越权修改B数据库 | 发布创建、轮询、恢复、终态刷新及确认等待统一绑定原范围/会话/任务；校验publication.scopeId与请求归属，离开/切换后旧结果不能装入新上下文，也不能替新范围清pending key或提供旧任务动作。显式返回原范围后才操作其任务 |
| R07-b P1关闭 | 实读DDL按冻结withAnyOf；正式真实PG覆盖甲+丙、乙+丙成功，甲+乙业务拒绝，数字0/布尔false有效且缺值拒绝，条件收紧保值。原“总数>1”错误测试同步替换合理。员工DB证据含反向UPDATE被23514类CHECK拒绝且原值未变 | v6保留类型和互斥语义；复制/legacy运行协议按新方向替换，不再为旧方案新增建设 |
| R07-b P2关闭 | 正式控件测试和真实Issue编号截图显示中文单选/多选/复选等；已抽看完整回复及最终刷新截图，内部控件字符串不再作为填写方式回显 | 新旧版本规格均用业务标签显示，稳定身份不变 |

**独立验证**：后端六类83项，0失败/0错误/0跳过，BUILD SUCCESS（含真实PG集成）；前端两文件30项通过。补充异步组件探针3项：最终刷新保护1项通过，上表两项归属反例失败；强化任务探针确认恢复请求参数为5，不仅检查视觉文本。首次探针因Windows路径转义未收集用例，已修正测试工具后执行，未计为产品缺陷。证据位于主树`.tmp/w4-r3-review-20261027/`：`input-verification.json`、`directed-backend.log`、`directed-frontend.log`、`async-context-final.log`及探针源码。未重跑员工全量或浏览器，不声称独立66/66；未运行黄金、内网、真实外部调用、业务服务或共享库。测试仅用自身Testcontainers，无手动清理其它容器；产品源码、正式测试、迁移及员工送审HEAD均未改。

**与新方案一起交接**：用户指定的等待点已满足，当前封包已收到并完成复审。本次反馈同时提供已确认v6.0方案（初始文档提交`5f34b095`）及下方N0清单；员工尚未合并文档/回执，不能记录为已送达或已实现。下一单先收尾可复用的R05异步归属并准备N0技术补充包；允许先做N0文档调查，不启动N1业务代码。不要继续为历史复制扩充实现或验收。N1需R05关闭且N0获批，W5、main、黄金更新和正式发布均无自动授权。

**员工复验条件**：正式组件用例与真实页面请求暂停分别覆盖A→B、A→B→A、预览/创建/轮询/恢复的成功与失败、确认等待及最终刷新；结果只更新所属任务，当前输入/草稿/请求key不被别的会话改变。发布确认必须对应实际原范围及冻结预览，恢复等有副作用操作沿危险操作确认规则；确认等待期间范围变了应停止该次动作。更新测试与准确指纹，以新增提交送审。

### W4三次复审后R05收尾、v6文档吸收与N0提交（2026-10-27，待复审）

**结论范围**：按上一节复验条件关闭 R05 的预览与发布任务跨范围归属；吸收审核分支文档补丁（v6.0 方案、本次复审、N0 清单），保留既有交付与复审记录；按 N0 清单提交[v6 技术准入补充包](v6-n0-technical-admission-20261027.md)。未启动 N1 业务实现，未扩建历史复制流程。

**R05 修复**：
- 预览、发布创建、轮询、恢复与终态刷新统一绑定“原范围 + 加载代次 + 草稿修订/输入快照”；回写前复核，迟到结果只丢弃不装载，且不替新范围清 pending key 或提供旧任务动作。
- 预览缓存附加所属范围；A→B→A 首次访问的迟到预览不得复用。恢复任务新增危险操作确认，确认等待期间切换范围则停止该次动作；`publication.scopeId` 与当前范围不一致时拒绝恢复。
- 正式组件测试新增 9 项（A→B 迟到预览、A→B→A 预览、确认等待切范围停止发布、迟到创建响应、迟到任务与恢复动作、迟到 PUBLISHED 刷新、A→B→A 迟到轮询、确认等待恢复、跨范围任务恢复拒绝），预览与轮询两处守卫均经突变自证转红；审核方原 3 项异步探针（此前 2 项失败）在修复后全部通过。
- 真实页面新增 8 项（含 A 预览迟到不显示于 B、B 不显示 A 任务卡片/最近发布/恢复动作、取消恢复不执行、返回 A 后重新预览成功），本轮页面合计 74/74。

**文档吸收**：审核补丁`v6-and-review-vs-97950222.patch`在干净工作树`git apply --check`通过后整体吸收，未覆盖员工既有交付/复审记录；主方案升为 v6.0，本执行单新增本次复审与 N0 清单。**回执：吸收提交`287147ff`**（基于`6a02107a`），补丁 7 个文件全部命中、无冲突标记；审核分支代码基线未合并，审核提交`f5682304`仍在独立分支。

**N0 提交**：新增[技术准入补充包](v6-n0-technical-admission-20261027.md)，按八类差异给出可执行 DDL、约束/索引、事务伪码与正式类型：定位目录与跨版 requestKey、可读版本集合/目录事件/高水位、既有数据登记（原行不变、无实例/无账号只读）、多规格 detail 与按所属版编辑、空结构发布与旧任务收口、多版权威读取与 head 锁序、共同字段与九项选择器、按版本 Excel 与权限、迁移删除清单与替代验收；另列 9 项待审默认建议。N0 未批准前不建表、不改业务代码。

**验证**：前端`typecheck`/`lint`/172文件908项/构建通过（`npm test`全程无失败，隔离重跑日志`.tmp/w4-r4-20261027/logs/frontend-full.log`；首轮在并发负载下三个无关挂载用例曾超时，单独复跑均通过，未改测试或重试掩盖）；后端代码本轮未改，沿用上一轮2009/0/0/1冻结证据；真实页面74/74（20张截图与日志，证据目录`.tmp/w4-r4-20261027/`）。

**边界**：未合 main、未出正式包、未运行黄金、未触碰内网/共享库/真实 GitLab/机器人；未改写已推送提交；隔离资源收尾停止。

### R05通过与N0首次审批（2026-10-27）

**结论**：R05关闭，W4冻结v5.1目标通过；N0修改后重审，N1/W5未准入。已关闭项不再要求重复修复；新合同按[八项整改及Q项裁定](v6-n0-technical-admission-20261027.md#n0首次审核2026-10-27)修订后重审。

**输入与吸收回执**：员工HEAD `4423920e5d4bf916388156dc380192d9b0070eb0`，现有隔离分支干净，origin/gitlab25独立回读一致；6a02107a为R05修复，287147ff吸收审核七份文档及新增N0，4423920e回填回执。142项完整差异（A94/M31/D17）与27项证据逐项复算零漂移；内容摘要`a393295d785c47f60f49eb0c884243e58a193dcb9a2cb16e29105249f01c7b6f`，代码清单SHA-256 `d15c62600c6b4cc8d9adf967ce497194c7320b2608afff45c816983370e9fe80`，输入清单`3d9ac7287f88adbd364631831649e06eb5f3c75e2ab907e810b6e782bafc8ec1`，变更摘要`f77404e32dfd9624de888c2226d29b598cbc2668dfa1579334d90eecd29b10ae`。原N0 SHA-256 `1e24d65ea633ade176ffbe5484442e8a50115de41b5b53b7717a7b64d434f15e`，后续审核批注不改写该原输入身份。

**R05**：预览绑定原scope/epoch/草稿修订/输入快照；发布创建、逐轮轮询、终态刷新和恢复绑定原范围/代次并校验返回scopeId，恢复加入危险确认及确认前后守卫。独立正式26项、原审核三项全部通过，前轮A迟到预览和B下恢复A任务两项反例均转绿；员工74项/20张编号截图及908项全量、typecheck/lint/build冻结日志已核对，抽看B无A任务及恢复场景。未独立重跑浏览器或前端全量。后端git树与97950222一致，不重跑后端，以旧冻结证据单列。

**N0**：八类材料已提交，但新阶段/协议列和活跃任务约束、登记恢复、跨版幂等/序列、消费完整提交、既定Issue选择器、真实草稿格式、共同字段语义及影响/授权/验证入口尚不完整。真实隔离PG仅验证本模块基础结构+提案SQL：SQL块解析通过，但protocol列42703、三个新阶段23514、空实例版本23502；不等同执行或验收N1实现。详细整改及Q1—Q9裁定由N0维护，主方案暂不写入未批准DDL。

**验证与保护**：证据主树`.tmp/w4-r4-review-20261027/`，`input-verification.json`、`directed-frontend.log`、`async-context.log`、`n0-ddl-probe.json`及脚本。PG初轮重复种子为探针夹具错误，已修正；两个本轮唯一容器均确认按ID删除。未改员工实现、正式测试、迁移、HEAD、共享主树或快照，未启动业务服务，未运行黄金、共享库、内网及真实外部调用。审核工作树仅维护方案及状态。

**下一步**：员工继续同专属任务分支，按N0-R01—R08修改合同并补逐项差异/指纹；真实E2门禁在现场登记前，MR公式在消费相关阶段前补批。没有N1/W5/main/正式包或黄金更新授权。N0获批后再统一回写主方案技术合同并准入N1。

## AI员工下一步执行指令（2026-10-27）

### 五审通过后N1实施指令

**授权**：N0通过，允许在原专属任务分支/worktree开始N1；先建活动计划，再自主实施下列范围，无需重复申请N0。不启动N2—N5或W5，不改变批准UI。

1. **开工与计划**：检查原员工目录的status/worktree/HEAD及会话归属，核对最新AGENTS；本轮审核核对点为干净4cd8588d。回读双远端审核分支`codex/form-admin-ui-20261027`，以最终回执SHA安全快进吸收；有新进展则保留并增量吸收，不reset。创建`docs/plans/unified-form-v6-n1-implementation-20261027.md`，按AGENTS八类恢复信息列基线、允许/禁止范围、精确DDL/调用顺序、验收、隔离资源及提交边界。
2. **允许实现**：追加N1目录/版本源集合及必要元数据DDL；实现源角色/实例模式、全局记录定位与跨版key唯一、origin/实际storage/完整C/首次指纹证据、稳定语义登记与实际类型校验、共享序列高水位、真实物理结构漂移校验。按[N0已批合同](v6-n0-technical-admission-20261027.md#n0-fifth-review)实现旧Demo/v5唯一承载的受控原位登记和统一内部reader。物理列与列名以实际迁移/代码核实；新增Flyway前复核最新编号和依赖，159条既有迁移及锁条目不改。
3. **数据与接入边界**：登记事务前确认旧写停止/选集稳定，仅操作本任务隔离夹具。原业务行、原field_spec、origin/首次hash/创建归属/时间不改写；重复、未覆盖、冲突、漂移整体拒绝，提交后纠正只追加证据。稳定ID/语义声明的一次性登记位置与原规格绑定在计划定型，不借元数据升级改旧规格。N1计划写清reader接入与现有v5写入/复制/消费路径的阶段依赖，禁止自动在启动时登记真实旧库、启用旧复制器改已登记源，或暴露不完整读写组合。
4. **隔离与验证**：数据库用显式专属库/账号/密码或已核实Testcontainers，禁止默认15432/15433及共享服务；不预建无用前端/业务服务。正式PG测试覆盖空平台、代表性非空Demo/v5、唯一/重复/缺失归属、原行/规格/hash/目录守恒、首次hash与origin/storage不同、NULL实例/未知账号、重入/回滚/单调补偿、序列is_called全部边界及跨范围竞争、合法与漂移结构。内部reader覆盖全部权威版本、排除TARGET/快照、完整C隔离与缺失/退役范围。N1把元数据冻结类型/声明冲突、稳定身份/冻结域校验转为正式测试；134项中的共同字段选择/聚合在N4实施时转为正式测试，不要求N1提前实现统计，也不能以临时Node代替正式业务验收。
5. **实施清单与门禁**：同步启动/漂移校验、只读数据库查看及实际受影响调用者；新增端点若确属N1必要，先列其已批合同、权限与黄金目录登记，不把N2/3/4接口提前扩入。完成定向真实PG及一次最小充分默认后端套件；明确测试库、失败/跳过及根因，不用重试或增加延时掩盖失败。不主动运行黄金、不更新快照；保留既有发布准备门禁。
6. **提交与送审**：按已完整验证、可独立回退的阶段提交，代码/迁移/正式测试/文档自洽后同步origin/gitlab25并回读。验收包给完整差异、冻结commit/清单hash、迁移新增与旧锁零差异、精确日志/结果、数据守恒和资源收口证明；证明frontend相对94749f3a零差异。N1通过只表示任务分支基础阶段完成，不代替v6整体验收或可部署声明；未获N1验收不得进入N2。

**保持未授权**：frontend和UI功能变更、N3发布执行器、N4事实公开/统计/Excel及正式权限授予、外部机器人/AI/GitLab写回、共享/真实业务库、main、内网和正式包。真实E2未取得不阻断隔离N1开发，但阻断真实登记/切换；导出角色和MR级未批公式按其实施前门禁补齐。必要基础依赖若不能按本范围独立验证，先报告具体依赖，不以隐藏页面或关闭开关冒充可发布成果。


### N1基础实施交付（员工，2026-10-27，待验收）

**范围**：吸收审核回执`13985b7a`后按“五审通过后N1实施指令”实施；frontend零改动，批准UI`94749f3a`保持；不启动N2—N5/W5。

**实现**：前向迁移`V20261027_01__form_v6_directory.sql`（N0合同§1.2—§1.4，仅依赖`V20261022_01`；实际基线`instance_table`先drop not null；校验和159→160仅新增）；`FormV6RegistrationService`路径A（旧Demo`collect_form_records`原位LEGACY/NONE，不伪造实例/账号/修订）与路径B（v5唯一承载原位登记，承载版本AUTHORITATIVE、其余LEGACY_SNAPSHOT、原版版本行与field_spec保留供旧key重放），precheck→register同实现强制一致、源变化/重复归属/证据缺失整体拒绝、幂等重入不增修订；`FormRecordDirectoryService`目录写入接入六步创建事务（序列锁→范围FOR SHARE→目录判重→实例/记录/目录/审计/head），跨版重放按目录`origin_version_id`+v5指纹复算，PAYLOAD_MISMATCH/异账号/完整C/作废/原版证据缺失均409；`FormRecordDirectoryReader`统一内部读源（AUTHORITATIVE且PUBLISHED/RETIRED、排除TARGET/LEGACY_SNAPSHOT、`idx_frl_resource`完整C单索引、captureSourceSet）；`FormComparableRegistrationService`稳定ID/语义声明一次性登记（独立表，不改写field_spec），声明类型≠冻结`field.type`即`TYPE_DECLARATION_CONFLICT`。

**验证**：定向真实PG测试12项全绿（`FormV6DirectoryMigrationTest`/`FormV6RegistrationIntegrationTest`/`FormComparableRegistrationIntegrationTest`，Testcontainers随机端口）；默认后端套件一次（结果见验收包）；159条旧迁移零改动、frontend相对`94749f3a`零差异、仓库护栏通过。事实入口（`GitlabFactSourceSqlProvider`等）未接，发布协议/消费捕获迁移留待N2；未自动登记真实旧库。

**边界与下一步**：只改本任务范围文件；未改业务无关代码、权限、黄金；未合main、未出包。送N1验收，通过前不进入N2。

### 四审指令（历史输入，IV-01/IV-02已关闭）

### 四审当时指令

1. 核对原专属目录/分支/HEAD和在途文件，读最新AGENTS；本轮核对点为干净`119b95a1`。员工原提交已由审核方同步双远端，直接从审核分支`codex/form-admin-ui-20261027`核对回执SHA并安全快进吸收。已有新工作时保留并增量吸收，不reset，不在聊天索取PAT。
2. 按[N0四审IV-01/02](v6-n0-technical-admission-20261027.md#n0-fourth-review)只修订真实类型→可比类型的生成/校验及Node证据。可比合同绑定真实冻结类型，不兼容合法合同不共同；实际类型和声明冲突在预览/发布及读取边界拒绝。不能用种子静态metadata继续覆盖新版本类型，也不向管理员增加技术输入；现有管理页的同类型控件限制保持，不开放跨类型编辑。
3. 补数字→文本及其它不兼容类型/单位、声明冲突和合法同类型/显示改名的固定样例。真实突变只破坏实现，保留用例/断言；删除无条件check(false)，记录每项转红断言和复原hash。原输入反例flag若保留必须如实命名。
4. 本轮不改frontend或业务代码，III-01/02/03、S1及此前已关闭项不重做。SQL无变化时只跑Node正常/真实突变与仓库护栏，引用本轮PG批准输入指纹；不重复启动数据库、业务服务或业务全套。
5. 更新N0、执行单、progress的真实状态及完整差异/hash/证据；执行159条迁移锁、frontend相对94749f3a零差异及仓库护栏后阶段提交，按现有GCM可见认证同步双远端并回读。N0获批前不进入N1/W5，不改正式迁移/授权/黄金，不合main、不出包。


### 四审整改交付（员工，2026-10-27，待五审）

**范围**：吸收审核回执`096b3724c0065f518e235636b7618c45165c948d`（快进前核对`119b95a1`为祖先、双远端回读一致）后，仅修订[N0四审IV-01/02](v6-n0-technical-admission-20261027.md#n0-fourth-review)对应的§7.1/§7.1.1合同、§9.2 P13行与探针两文件；III-01/02/03、S1及此前已关闭项不重做，frontend零改动，批准UI`94749f3a`保持，未开放字段编辑能力。

**整改**：IV-01可比类型绑定该版真实冻结`field.type`，§7.1默认表降为首版语义登记且不覆盖后来冻结声明；登记与冻结冲突→显式`TYPE_DECLARATION_CONFLICT`（发布阻断/读取明确无效），各自合法但不兼容类型或单位变化→`incompatible_value_type`不聚合，不自动转数值；`selectCommonFields`新增`invalidContracts`，`aggregateMetric`携带`invalidReason`并对非integer返回`UNSUPPORTED_AGGREGATION`；`enum-set`统一为`enum-array`。IV-02删除样例突变与无条件`check(false)`，`--break=`只覆盖实现决策点（identity/domain/type/no-common），固定用例与断言不变，四模式全部由正常断言转红非零退出；源文件运行前后hash一致即复原证明。

**验证**：探针Node正常检查134项全PASS；四突变退出码1且转红断言逐项记录（[回执](v6-n0-technical-admission-20261027.md#n0-fourth-review-remediation)）。SQL及其输入hash未变，未重复public/P2/并发数据库套件，未启动PG、业务服务或浏览器；PG侧引用`119b95a1`批准指纹及四审独立复跑（115/6/33、旧请求3.4秒409、消费等待2.4/3.3秒无40P01）。仓库护栏通过：159条迁移锁零改动、frontend相对`94749f3a`零差异、工件/空白检查及`git diff --check`干净。

**边界与下一步**：只改N0合同、progress、本执行单及`.tmp`探针；未改业务代码、正式迁移、权限、前端或黄金，未访问共享库/内网/真实外部，未合main、未出包。送五审，N0获批前不进入N1/W5。

### 三审后指令（历史输入，已收敛为上方四审范围）

### 三审当时指令

本轮生效结论为[N0三审](v6-n0-technical-admission-20261027.md#n0-third-review)。以下按顺序执行，后段二审指令保留为历史输入：

1. 在原员工专属目录检查工作树、分支、HEAD及会话归属，核对最新AGENTS；本轮只读核对点为干净`f7fa9384`。从审核分支`codex/form-admin-ui-20261027`吸收最终回执SHA，确认双远端一致且员工HEAD为祖先后快进；已有新提交或在途文件时保留并安全增量吸收，不reset、不覆盖整份文件。
2. 只续改III-01（真实旧指纹）、III-02（发布锁序/切换与首次启用）、III-03（消费锁序与完整pending核验）、III-04（共同字段实际选择/聚合）及S1候选执行细节。II-01/06/07不重做；具体问题、样例和关闭条件归N0，不在本单复制技术合同。
3. 真实非空旧样例必须保留旧算法首次hash，不用新算法预先改旧证据。发布/消费探针使用与正文相同的guard与锁序；证明两处40P01消失，旧版在途新增按原key/版本409而不换版提交。共同字段测试必须调用实际选择/聚合算法，并让两种错误突变转红；不能继续只加测试计数。
4. N0正文清理候选分页与SmartSelect拼音/首字母跨页搜索、来源/完整项目路径和版本所属项目确定规则；补无回复D-21样例。当前只设计，不改frontend。后续功能变更事前报审，布局、卡片、摘要、抽屉与视觉保持批准基线。
5. 更新逐项整改表、输入/输出指纹、完整差异、固定命令/预期/实际与资源收口；同步本单和progress。用最小充分隔离探针验证本轮改动，执行仓库护栏、159条旧迁移锁、frontend零差异证明；按AGENTS阶段提交并推送双远端回读。
6. N0再次明确批准前不进入N1/W5，不改业务Java/TS、正式迁移、权限、黄金或共享数据，不合main、不出包。目标库导出正式名单、E2和MR级未批公式保持原门禁。

### 三审整改交付（员工，2026-10-27，待四审）

**范围**：吸收审核回执`565332e8`后，只修订[N0合同](v6-n0-technical-admission-20261027.md#n0-third-review)与隔离探针；II-01/06/07不重做，UI基线`94749f3a`保持，frontend零差异。

**整改**：III-01以v5真实JSON顺序和origin版本重建指纹，旧格式哈希独立生成，旧key重放同一ID且零新增/审计/head，承载版本/当前编辑值/错误C/作废拒绝；III-02所有发布、接管、DDL、首次启用脚本统一序列→范围→任务，真实双会话覆盖enter/apply窗口、旧版在途409、新版成功、崩溃回滚、过期接管及DRAFT→ACTIVE首启闭环；III-03 public与p3db共用同一capture/settle函数并按范围→head执行，pending集合/权威/记录身份/最新版本逐项失配零事实零结算，失败/重试受owner/fence/租约guard，消费与发布/接管交错无40P01；III-04实现实际共同字段交集选择与聚合，新身份/异义/异域/无交集/缺字段/NULL/0/作废覆盖，三个突变非零退出；S1补齐跨页拼音/首字母搜索、完整路径、来源隔离、ID边界、版本项目显式选择及D-21三样例。

**验证**：隔离PG16容器`b3f38037…fa81a`（15609）public 115、非空升级6、真实双会话33、共同字段93全部PASS；旧版在途请求等待约3.2秒后409，接管/发布与消费交错等待约2.3/3.2秒并按新集合结算，日志无40P01；三个共同字段突变均非零退出。容器按ID删除并确认不存在；证据`.tmp/w4-r5-n0-20261027-r2/logs/verification-*.json`、逐项日志与`cleanup.txt`。

**边界与下一步**：只改N0合同、progress、本执行单及`.tmp`探针；未改业务代码、正式迁移、权限、前端或黄金，未访问共享库/内网/真实外部，未合main、未出包。送四审，获批前不进入N1/W5。

### 二审后指令（历史输入，已由上方三审收敛）

> 本轮生效：员工`3c8d35b9`经N0二审仍需修改后重审。按[N0-II-01—07及S1](v6-n0-technical-admission-20261027.md#n0-second-review)整改，原首次审核已完成的部分不重做。以下交接方式继续有效；原4423920e/c9a7aa44均为历史输入，不要求回退。

1. 在员工专属目录核对工作树、分支、HEAD和活动任务。只读核对点为干净`3c8d35b9`；审核交接提交包含该提交祖先及本轮反馈，可在双远端回读一致后快进吸收。实际已有新提交/在途修改时先核对差异，保留成果并安全增量吸收，不reset、不整份覆盖。
2. 固定UI基线`94749f3a`，不改frontend。吸收交接中的SmartSelect、名称候选和简化流程要求，只补N0候选接口、命名和操作合同；此前未确认模型仍列待确认。
3. 只修订N0生效正文与`.tmp`设计探针，关闭序列is_called边界、origin/storage跨版核验、发布过期接管/短栅栏、捕获/结算归属、真实可比语义、导出授权及可执行隔离入口。两项方向已批准，不能把它们重复提交为未裁定选择。
4. 用固定样例复现并验证对应反例；给出非空v5承载副本与旧key样例、真实PG并发/权限验证、实际双版语义统计。入口本身从干净隔离环境运行，保留创建失败不误删占用者的证据。无需重复与本次无关的全量业务测试。
5. 提交逐项修订表、完整差异、输入/输出SHA-256、命令/预期/实际/清理和剩余外部条件；更新本单及progress，执行仓库护栏、159条旧迁移锁和frontend零差异证明，提交并推送双远端回读。N0未批准前不进入N1/W5，不改正式迁移/权限/业务代码，不运行黄金、不合main、不出包。

以下首次交接条款按本轮结论续用，其历史状态以本节顶部为准。

### 生效范围与恢复入口

本单只准许吸收已批准成果、修改N0设计合同及运行隔离设计探针。R05关闭、W4冻结v5.1阶段通过；N0修改后重审，N1/W5仍未准入。首次交接时的原员工目录为`D:/projects/dcp-form-w4-20261026`，分支`codex/unified-form-w4-isolation-20261026`，已核实本地及双远端均为`4423920e`且工作树干净。本次交接分支为`codex/form-admin-ui-20261027`，以审核方交接回执的完整提交SHA为冻结输入。

先读最新AGENTS、progress、本执行单、主方案§3.4及N0首次审核。首条命令为`git -C D:/projects/dcp-form-w4-20261026 status --short --branch`，再查worktree、HEAD和会话归属。若实际HEAD或未提交状态已变化，先报告差异，不reset、不覆盖文件、不改写已推送历史。

### 一、吸收交接并保留UI

在员工专属目录fetch交接分支，核对origin/gitlab25目标分支均等于审核方回执SHA，确认原员工HEAD为其祖先后，以`git merge --ff-only <交接SHA>`吸收。该步骤携带已批准前端实现和评审文档，不是再次设计页面。若不能快进，保留当前状态，提交冲突/依赖说明，不强推或手工覆盖整份文件。审核分支`b8979af7`的结论已整理进本次交接，不需要合入该分支的代码基线。

当前[UI基线及变更审批规则](../product.md#form-admin-approved-ui)已由用户确认。本次N0不修改frontend；吸收后及提交前均运行`git diff --exit-code 94749f3a -- frontend`证明无新增前端差异。后续v6接入确需修改前端功能时，先列功能差异、调用/状态/草稿影响和验收方法，获审核方批准再执行；不得借功能适配改变布局、样式、字段卡片、常驻摘要、抽屉或主次操作。已废弃的恢复确认不恢复，保留当前直接重试及原范围归属检查。

N0恢复合同须与此交互一致：原冻结输入仍有效才直接重试；输入已失效时阻断并保留修改，说明如何通过现有发布确认重新冻结，不新增恢复确认弹窗，也不静默替换原发布载荷。此处只要求补设计，前端功能接入仍须事前获批。

### 二、只整改N0技术合同

补充用户2026-10-27的选择器与命名要求：保留当前页面布局，为适用项目设计按真实GitLab名称/路径搜索的多选，必要的代码平台为单选；系统可推导的来源不再要求重复填写。有权威目录的其它字段逐项列来源、业务数量关系及控件选择，不统一改成多选。清理“版本成员”等意义不明的可见名称；里程碑、代码分支、表单结构版本不混同。新增独立表单、产品版本解耦及停用恢复的上一轮具体建议仍待用户确认，不能借本补充自行批准整个模型变更。

选择器的明确参照为评审数据管理页现有下拉框，实施时复用同一SmartSelect组件及对应单/多选交互，不另造样式；权威候选不开放随意新建值。N0同时给出常用任务的最短操作流程及自动带出项，去掉重复输入、跨页操作和非必要确认，不以新增术语或长说明增加学习成本。后续获批实现的验收需对照该页实际搜索、选择、回显、清空行为，并检查原管理页布局和必要确认保持。

如员工已开始此前N0整改，在核对当前HEAD和在途文件后于安全点增量吸收本补充，保留已有修订；4423920e仅是初次交接输入，不要求回退。不得整份覆盖正在修改的N0或通过重做UI吸收文档。

N0补齐项目候选接口的精确查询来源/物理列、权限、ID与名称/路径响应、搜索/分页，以及加载、未同步、删除和失败处理；候选覆盖已同步合法项目，不局限于已有统计记录的项目，也不复用不相干看板接口。通过后端提供候选，不让浏览器直连GitLab库；版本分支目录未核实前不编造来源。送审时附可见字段名清单及单/多选理由。此阶段只补合同，不改frontend；后续获批实施时允许对应选择器、数据接入和必要文案修正，UI冻结不阻止已批准功能变更。

按[N0-R01—R08及Q1—Q9裁定](v6-n0-technical-admission-20261027.md#n0首次审核2026-10-27)逐项修改正文。每项交付一个确定方案及对应DDL、事务或精确数据契约，不保留“新增列或另建表”“回退修订或补偿”等未裁定选项。保留原审核结论、原输入指纹及必要历史效力说明，旧正文由Git追溯；不能把被否决建议继续当作实现规则。

- R01—R03：补完整前向DDL/阶段与索引/旧任务收口；登记全值守恒与单调恢复；跨版旧key先核验、原账号/首次规格与全局序列同锁序。
- R04—R06：补消费捕获到公开/结算的正式合同与持久事件恢复；对齐既定九项Issue选择器；按真实v1草稿和十进制字符串ID给精确DTO及升级映射。
- R07—R08：三模板默认共同字段必须可用，补可比语义及各指标分母；列全消费影响、精确导出授权、固定输入/资源边界及安全隔离验证入口。

本阶段不修改业务Java/TypeScript、正式Flyway、权限授予、正式数据或黄金快照，不扩建旧复制协议。允许在本任务`.tmp/`准备设计探针与代表性夹具，数据库SQL仍是待审合同。MR级未确认公式只提交影响与待裁定项，不自行补公式；真实E2仍是现场登记/切换门禁，不以其未知替代当前八项技术缺项。

### 三、设计验证与送审

提供PowerShell 7可执行入口，显式隔离库/账号/密码或已核实Testcontainers；容器名称唯一、记录本轮容器ID，finally只清理本轮资源并恢复环境。不使用默认15433/15432、共享服务或其它AI的容器；文档整改不启动业务页面或外部写回。

至少覆盖空平台和代表性非空旧结构升级：协议列/新阶段/活跃任务约束/无实例LEGACY元数据合法，非法组合仍拒绝；旧159条迁移不改；登记重入、业务值变化、跨版key、账号已知/未知、副本、并发及失败恢复均有固定输入与预期。原42703、23514和23502反例需由修订合同解释并转为正确结果，不只证明SQL可解析。设计探针不冒充N1业务验收，不用旧全量结果代替新协议证明。

送审包包含N0修订稿、R01—R08对照、Q项落实位置、相对本交接SHA的完整差异、原始与修订SHA-256、探针命令/结果/清理证明、仍待外部补证的阶段清单。同步执行单与progress；只提交本单文档及允许入库的合同资料，临时证据留`.tmp/`，按AGENTS推送双远端并回读。纯文档改动不重复运行前后端全套，不运行或更新黄金、不合main、不出包；获N0明确批准后才申请N1。

### N0修订稿与设计探针交付（2026-10-27，第一轮送审历史）

> 本节为`3c8d35b9`的第一轮送审记录，二审结论与整改见下节；保留作历史输入，不再作为当前执行依据。

**范围**：按[N0首次审核](v6-n0-technical-admission-20261027.md#n0首次审核2026-10-27)的R01—R08与Q1—Q9重写准入合同；前端保持`94749f3a`，未实施N1、未改正式迁移/业务代码/黄金。

**逐项整改**：
- R01：protocol列/历史默认、v2阶段（VALIDATING/BUILDING/SWITCHING，初始VALIDATING防旧执行器误领）、活跃唯一/领取索引、attempt协议、空实例LEGACY元数据、旧任务锁/租约/fencing受控收口、恢复重冻结。
- R02：Demo与v5两条登记路径；原行全值哈希、承载副本报告、单事务内覆盖/幂等核验；提交后只追加补偿事件、不回退修订/不删证据。
- R03：单一六步事务（序列锁→范围锁→跨版key→新建版本→目录/审计/head）；首次冻结规格/账号/C/指纹；高水位只进不退。
- R04：`form_head_captures`定型；提交事务持范围FOR SHARE并以revision/hash/latest/fencing/authority CAS；漂移零公开零结算；事件领取/确认/游标/重启；全量→增量。
- R05：九项选择器逐项对齐主方案§3.5，删除“最新非空回退”和新增分支门槛；同走查人沿reviewerUserId+C。
- R06：bigint字符串、recordId/storageVersionId/originVersionId/recordVersion区分；按真实`draft-store.ts` v1字段映射到v2，保留分叉/未知/401证据。
- R07：三模板全部既有字段默认元数据、完整语义哈希、逐指标分母、2+3=5与新身份/异义枚举反例。
- R08：MR/Issue实际消费入口与读源替换、导出端点/角色/范围/列/审计、50k/64MB待验证边界、pwsh隔离入口。

**验证**：独立`postgres:16-alpine`容器执行本稿DDL：public 12项、非空升级1项、共同字段95项全部通过；先`V20261022_01`后本稿DDL，旧159条未改。容器ID`99ac1b2068ec1176af77a8c8a1a343075956476ed409646e07d9bfbc51e9d47d`已按ID删除、15607无残留；未启动业务服务、未连共享库/内网。证据`.tmp/w4-r5-n0-20261027/`。

**差异与边界**：相对`c9a7aa44`只改N0文档、progress与本执行单；`git diff --exit-code 94749f3a -- frontend`无差异。未合main、未出包、未运行黄金、未访问真实外部；完整差异与SHA-256见交付报告。

### N0二审整改交付（2026-10-27，待三审）

**范围**：吸收审核交接`d338f843`，按[N0二审](v6-n0-technical-admission-20261027.md#n0-second-review)关闭II-01—07并补S1；只改N0合同、progress、本执行单与`.tmp`探针。UI保持`94749f3a`，frontend零差异；未实施N1、未改正式迁移/权限/业务代码/黄金。

**逐项关闭**：
- II-01：序列按`last_value + is_called`推导下一可分配值，相等未调用时`setval(high,true)`；真实PG覆盖相等未调用/已调用、更高/更低、跨范围、登记后立即创建。
- II-02：目录新增`origin_version_id/origin_spec_hash/create_payload_hash`与完整资源C；旧key按原版证据重放、编辑按`storageVersionId`与CAS；非空v5承载样例验证同一ID、零新增/审计/head、原版行不变及异账号/C/载荷拒绝。
- II-03：BUILDING不停写；SWITCHING仅enter/apply两个短事务持栅栏；每次DDL/阶段更新在副作用前后复核owner/fence/租约；真实双会话覆盖构建期写入、切换期阻塞后写新版本、旧owner迟到DDL/结算、崩溃事务全回滚、SWITCHING过期接管。
- II-04：捕获/结算绑定captureToken/owner/fence/`clock_timestamp()`租约、集合/head/pending/authority；旧worker覆盖与过期结算均为0；补资源C目录索引与事件/head单调推进。
- II-05：语义哈希绑定冻结选项稳定ID或词典版本+`canonicalSha256`+成员；显示改名不变、真实选项/词典变化必变；真实双版本2+3=5、零共同字段显式报告。
- II-06：导出只复制既有导出权（当前授予与默认快照各自复制），禁止view推导；补版本/项目/资源C谓词；仅查看、双查看、撤销、默认恢复、部分项目、越权/跨范围/TARGET/空版本均有反例。
- II-07：单入口`run-probes.ps1`按基础DDL→修订DDL→探针执行；`docker create`捕获ID后`start`；只清理所持ID；正常、DDL失败、就绪超时、同名冲突、端口占用五模式均无残留，占用者存活。

**验证**：`postgres:16-alpine`隔离容器`92e305a331d4f9f1b3f9476ef5ce997ccb852fd4da637fbbfcefed696253a7a0`（15609）执行public 82、非空升级6、真实双会话16、共同字段81项全部PASS；切换期写等待约3秒后在新当前版本提交。容器按ID删除、无残留端口；未启动业务服务、未连共享库/内网/真实外部。证据`.tmp/w4-r5-n0-20261027-r2/`（入口、SQL、日志、`verification.json`、`cleanup.txt`）。

**差异与边界**：相对`d338f843`只改文档与本轮`.tmp`探针；`git diff --exit-code 94749f3a -- frontend`无差异，旧159条迁移未改。未合main、未出包、未运行黄金。

**交付回执**：整改提交`059dc08d`及回执/澄清提交已推送；最终commit与双远端tip以`.tmp/w4-r5-n0-20261027-r2/verification.json`的`commit/remoteTips`为准（回读一致）。完整差异同目录`diff.patch`，验证清单含normal与四个负向模式、清理记录及输入SHA-256。

## v6.0待下发工作单元与交接清单

### 当前阶段、恢复与时点

当前业务方案已吸收，N0首次审批退回，尚不实施v6业务代码。审核文档工作区`D:/projects/dcp-form-version-design-20261026`、分支`codex/form-version-design-20261026`；已合并员工提交回执，不改其实现目录。仅本轮使用隔离PG做设计DDL验证，已按ID清理；业务服务、共享数据库、Compose和外部写入不使用。

恢复先读AGENTS、进度及主方案v6.0，运行`git status --short --branch`核对分支。文档输入快照、差异与哈希在主树`.tmp/form-version-design-20261026/`；不得从该快照整体覆盖员工完成后的最新文档。代码/测试/迁移不从在写工作树复制。

2026-10-27已核实员工287147ff吸收v6设计、4423920e回填回执；R05已关闭，N0退回修改。员工吸收本次审查时仍核对最新提交与文档增量，不拿早先快照覆盖后续进展。

### 目标与边界

用户2026-10-26确认：记录留原版、新建用当前版、旧记录按原版维护、相同业务字段跨版总体统计、按版本Excel导出。主方案§2.2、§3.1—§3.8为当前目标；原Wiki、本人权限、C、字段/选项稳定身份、审计、幂等、危险操作和草稿体验继续有效。

只删除被替换的历史复制/转换运行路径，不撤销真实已发生迁移、改旧Flyway、删除存量记录或恢复废弃HTTP/旧Service双轨。不把历史副本当新增记录，不把缺字段补0，不把相同中文名当语义身份，不将导出权限扩大为跨项目读取。

### 证据、根因与替换范围

v5.1通过复制使全部历史落在当前表，因此记录定位、原key重放、历史缺值、发布恢复及消费head都依赖单一存储版本。用户取消搬运后，仅删copy会导致旧记录失联、旧key重复创建、统计漏旧或重复、旧编辑被当前版本错误拒绝；必须统一改路由与读源。

| v5.1契约 | v6目标 | 主要影响 |
| --- | --- | --- |
| 当前表承载全部历史 | 每记录一个原位权威位置 | 定位目录、detail、原版CRUD、权限及数据库查看 |
| 结构发布复制/转换/核验S | 建空结构、核验、登记版本/切新建指针 | 管理预览、发布阶段/恢复、fencing、测试 |
| 历史升级后用新规则补齐 | 原版结构/填写校验保留 | 字段渲染、编辑草稿、条件与历史数据 |
| 每版请求key物理唯一 | scope内跨版本唯一目录 | 创建事务、重放、未知结果与同人提示 |
| 单一pending_storage_version_id | 完整可读版本集合证据 | 权威、head、固定输入、结果公开及目录变化 |
| 读当前表聚合 | 统一多版读取及既定业务选择器 | MR/Issue事实、全量/定向/周期、统计/导出/写回资格 |
| 新表单导出后续再做 | 按版本Excel纳入本期 | 端点、权限、类型/筛选、目录及黄金差异 |

### N0：v6技术准入补充包（五审已通过）

以下八类差异已完成[N0五审](v6-n0-technical-admission-20261027.md#n0-fifth-review)，正式合同归N0。旧W0通过仍只证明对应v5.1输入；当前按上方N1指令开始实施，不能把设计批准当作实现验收：

1. **数据与目录**：正式`form_record_locations`列、唯一键、FK/索引、跨版本requestKey作用域、全局序列高水位及与记录/审计/head的事务边界；版本源角色、集合修订/哈希、已发布历史版与退役范围区分。
2. **既有数据登记**：代表性旧Demo与已有v5复制副本的唯一承载清单、原位登记脚本/事务与恢复、原物理行不变和目录覆盖核验；未知创建人只读。真实E2未取得如实标注，不在开发期写共享库。
3. **接口/本地格式**：新版detail多规格响应、记录所属版参数与CAS、跨版旧key核验、同C跨版同人提示、历史记录权限；草稿结构/缓存升级策略保留真实输入及未知提交依据，不用清缓存替代正确性。
4. **普通发布**：冻结草稿/预览/绑定/目录修订，空结构生成与完整校验，短切换/目录事件，任务阶段/租约/恢复；旧未完成复制任务的收口与历史日志处理。不能让旧工作者领取新协议任务。
5. **消费协议**：多版权威读取器、记录去副本、头版本与目录/规格/权威捕获、统一范围→head锁序、结果公开/结算同事务；无实例FORM资源、全量到自动增量交接及失败恢复。
6. **共同字段与选择器**：稳定字段身份和可比属性的来源；同名不同义/不同单位不自动匹配，NULL/0/缺字段及分母说明；跨版本Issue最新/首次/合法计划/原因等九项选择器，保留MR公式待批项。
7. **Excel与权限**：正式API、DTO/文件列顺序、版本/项目/C范围、form.record.export及现行角色/defaults核验、资源/流式预算；端点、页面view/export、工作区及事实依赖登记。
8. **迁移/测试/交付**：只追加前向Flyway并登记校验和，说明与其它分支迁移的冲突；列出删除的旧运行路径/调用者/错误测试和替代验收、新黄金有意差异清单、最小充分测试命令、专属运行资源和恢复方式。

准入材料给出可执行DDL/约束/索引、事务伪码和正式类型，审核后回写主方案的技术合同；不得由执行员工将未审技术细节或未确认业务公式写成既定事实。

### N1—N5：获准后的实施顺序

| 单元 | 交付范围 | 必须证据 |
| --- | --- | --- |
| N1 多版本目录与读源 | 全局定位、版本源集合、原位登记、旧副本排除、真实列/结构校验 | 真实PG覆盖完整与重复归属、旧物理行不变、目录回滚、高水位只进、未配置与漂移拒绝 |
| N2 原版记录服务与页面 | 当前版新建、所属版编辑/作废、全版key/同人提示、detail多规格、草稿及异步回写保护 | 两版真实CRUD/CAS/未知重放，V1/V2不同字段展示，双用户/多标签及迟到响应浏览器 |
| N3 空结构发布 | 原执行器直接改目标协议、阶段/恢复、目录CAS、租约及短切换 | V1原表不变/V2空启、新建竞争/旧版编辑、漂移/非空目标/过期持有者零副作用 |
| N4 多版本消费/统计/Excel | MR/Issue统一读源、共同字段、九项选择器、版本导出与权限/端点登记 | 非空真实全量/定向/周期及写回资格、目录/权威漂移零公开、统计样本/导出全字段与权限 |
| N5 集成与发布准备 | 默认套件、构建/lint、真实PG/页面、所有影响面及资料 | 主方案§4矩阵逐项完成；只有满足发布条件后按W7规则做黄金，W8仍需现场授权 |

每单元使用同任务专属worktree/分支及隔离库/端口；原生v6记录数据不在单元间搬表。前一单元通过后才启动依赖项；如更小单元不能独立运行/升级则按真实依赖一起验收，不为沿用旧W编号制造虚假完成。v5当前轮次通过不直接准入N1或W5。

### 跨版本最小验收样本

- V1创建A并保存“规范=2、逻辑=1”；发布V2新增“设计”、删除“逻辑”，V1 A及旧值不变，V2空。V2创建B“规范=3、设计=1”，列表两条、共同规范合计5，不把逻辑/设计拼成同项，按版本导出保留各版特有列。
- A在V2发布后按V1编辑与作废，仍在V1且审计/head正确；本人CAS、缺创建人只读、范围退役及已作废排除保持。
- A创建响应丢失后发布V2，原key返回同一A且不向V2写重复；真正未落库的旧版新增请求拒绝并保留草稿，未知结果先核验，不静默新key。
- 字段仅改显示名仍共同；删除后同名新增身份独立，不继承原值或强并统计。实际三模板条件与DDL类型/互斥语义一致。
- 已有v5同recordId多张副本只登记唯一真实承载位置；重复/未覆盖/身份冲突不得公开部分成功结果。
- 目录发布、V1旧记录编辑和消费者构建/提交交错，版本集合或head/权威证据变化时旧候选零公开/零结算；重启、取消、接管及全量到自动增量交接正确。
- 两版Issue A早建晚改、B后建：最新仍选B，首次仍选A；作废B后跨版重选，最新整份缺字段不从A拼补。

### 下发、审批与文档收口

| 事项 | 当前状态/责任 |
| --- | --- |
| v6业务方向 | 用户已确认；本次文档修订落实 |
| 独立文档提交 | 文档静态校验通过，保存于本分支；不合入员工在写目录，不修改其封包 |
| 与当前反馈统一交接 | 员工已逐轮吸收，当前送审4cd8588d；N0五审通过，按本次回执准入N1 |
| 技术准入N0 | 五审通过；批准输入4cd8588d，IV项关闭，准入N1 |
| N1—N5 / W5后续 | N1已准入、尚未实施；N2—N5及W5仍逐阶段待审，不沿用旧测试作新版验收 |
| 黄金、内网、正式包、main | 本次不执行；保持原确认与发布门禁 |

完成文档修订后，长期业务/技术事实分别归product、architecture和D-32；本执行单只维护该工作单元及交接状态，不复制权威全文。最终实施结束再按文档生命周期清理已完成活动计划，过程由Git历史保留。
