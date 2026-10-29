## 进度与中间物

- 阶段：原包核验完成，现交由 AI 员工整合已验收修复并重出替代包；本会话仅交付指令，未合并修复、构建、运行测试、起服或打包。
- 用户确认：`qaflex-update-20261029T025459Z-b4d40e323a09.tar.gz` 尚未部署到 20001。新包是它的替代包，直接升级基线仍为 d52，不是从 025459 包继续增量升级。
- 原包实测：206,186,794 字节，SHA-256 `bc9f75f50adda099f3a9f904dcdce39cc81a5f2c7023357e2219c9992fb5ea49`，与包外校验文件一致；包内 `source.commit=90d6cf93c284728afb6d7e29101909340b9ee6aa`，`workspaceState=clean`。
- 原包实际镜像中的 `app/app.jar` SHA-256 为 `02ab910f97b7574ae4cd2bf78a0a507a58ccbb169ec96b549728aa6eae2125f1`，与清单相同；对该 JAR 中真实 class 执行 javap，`matchesMetric` 没有 `module_total` 分支，确认未包含修复。只读检查证据：当前文档树 `.tmp/package-drilldown-check-20261029/matchesMetric-bytecode.txt`。

## 目标与通过标准

保留上述包的全部生产功能与发布口径，仅加入已审批的系统测试模块总数下钻过滤修复，生成可从 d52 保数据升级的新归档，交回本会话审批。

通过标准：新镜像确实包含 `module_total -> IssueSource::isRegularMetricIssue`；建议类独立下钻保留；旧包全部业务内容保留；最终源码、清单、镜像/JAR、归档身份一致；发布黄金严格 compare 及既有打包验收通过；本地升级、回滚、再次升级通过。不把已出包或本地通过表述为已部署生产。

## 恢复线索与环境

- 首条命令：`git status --short --branch`、`git worktree list`、`git rev-parse HEAD`；读取所用任务树最新 `AGENTS.md`、`docs/progress.md`、本计划、`deploy/intranet-offline-packaging-standard.md` 和黄金规则。
- 本指令维护目录：`D:/projects/dcp-delay-main-20261028`，维护前 main 为 `a445681e8ef08982290945c7fa3cbc371e8f29ac`，树 clean；本轮只新增指令与进度入口。员工实施从重新核实的最新稳定 main 建立中文任务分支及独立 worktree，或复用明确归属的同任务树，按 AGENTS 留下基线和环境记录。不要在表单混合树取代码或构建。
- 原包目录：`D:/projects/data_collection_platform_deploy/qaflex-update-20261029T025459Z-b4d40e323a09`；原归档及校验文件位于同级目录，保持原字节不变。
- 直接基线目录：`D:/projects/data_collection_platform_deploy/qaflex-update-20261021T011723Z-d52affe66b4f`。使用前核对其清单及真实镜像身份；不得把原包清单的 baseline 当作已验证的现场运行事实，现场部署前仍须核对 20001 实际 d52 身份。
- 已验收修复任务树：`D:/projects/dcp-system-test-drilldown-20261029`，分支 `系统测试模块总数下钻修复-20261029`；实现提交 `725616a51d858e06b47e3b276ad666db302cf56f`，验收收口提交 `0efa68f3a1640162eaa7a608b86c12b18d7ff0d7`。先确认原任务树未有其他会话在写，再读取证据；不在那里混入打包任务。
- 生产库、115/20001、GitLab 写回：本轮不使用、不部署。构建输出、隔离 Compose project/端口/卷、测试库、备份目录及外部访问先登记归属；现有模拟栈只有确认可由本任务接续时才能复用，不重置其他会话资源。资源不足时仅按实际入口停启已核实的本任务服务，记录并验证恢复状态。

## 执行步骤

### 1. 整合修复并固定范围

1. 从最新稳定 main 吸收 `725616a5` 的一行服务修复和配套服务测试。可以 cherry-pick 该提交，但其中旧活动计划已经收口，不能把旧计划或过时进度重新作为当前状态；按当前任务文档处理。不要整体合入旧表单/读发布历史、下钻分支上的整份进度文件或其他未验收功能。
2. 对照原包构建提交 `90d6cf93` 审查最终差异。当前已知 main 相对它只有发布文档与打包器测试夹具修正 `c66dd44a`，没有新增生产实现；允许保留这些已验证修正。生产源码的预期增量只有 `SystemTestDefectSummaryBoardService.matchesMetric` 的目标分支。如最新 main 出现别的业务增量，先查明是否属于本包必要依赖，不自动扩大发布范围。
3. 保持主表 `SummaryCounts`、建议类共享基础集合、修复率与未复现口径、`RULE_VERSION`、前端、迁移及 Flyway 校验和不变。无需重新设计过滤引擎或增加配置。

### 2. 完成最终代码验证与发布门禁

1. 原修复已有服务回归及规则测试 10/10、隔离默认套件 1818 项 0 失败/0 错误/1 项既有跳过、最小页面验收；同一 backend 生产/测试/资源内容且测试配置不变时，可以核对 Git 内容身份后复用对应记录，注明来源提交及日志，不仅凭提交标题宣称等价。出现相关内容差异才补相称回归，不重复添加防御性矩阵或修复前失败测试。
2. 本次确实要打正式包，必须在最终候选业务代码完成黄金严格 compare，不能沿用原延期包“业务未变”的豁免；在隔离 backend 目录使用仓库 Maven 入口运行：

   ```text
   mvn.cmd test -Pgolden-baseline -Dtest=GoldenBaselineChainTest
   ```

   具体绝对 Maven/JDK 路径按任务树配置；运行前后记录服务入口与恢复结果。不得加 `-Dgolden.update=true`、放宽掩码或修改快照使失败转绿；若出现真实有意差异，先把差异交审核方裁定，再遵循 AGENTS 更新规则。普通快照测试不替代此门禁。
3. 完成必要代码与文档提交，经相称验证后将任务单元整合到稳定 main 并快进同步 origin/gitlab25、回读确认；本次请求授权为重出含已审批修复的正式替代包进行必要主线整合，不授权其他功能或生产部署。若存在未解决门禁/业务差异则不合入 main、不出包。
4. 从双远端一致的干净 main 固定构建提交；可以在该提交的干净专属构建 worktree 出包。最终代码若在验证后再有业务变更，补验受影响范围，不使用浮动 HEAD 代替冻结身份。

### 3. 按原直接基线重新生成正式包

使用仓库打包器完整构建新 release，不手工替换旧归档中的 JAR、不复用旧镜像 tag、不覆盖原 tar.gz 或清单。

```text
python scripts/package_intranet_offline.py --mode incremental-update --baseline-dir D:/projects/data_collection_platform_deploy/qaflex-update-20261021T011723Z-d52affe66b4f --require-fact-rebuild --fact-rebuild-scope issue --plan-only
```

核对计划的包类型、目标目录、d52 基线前后端镜像及逐服务容器命名模型，再用完全相同参数去掉 `--plan-only` 正式构建；输出在独立部署产物目录。遵循发布标准，不使用任何 skip-build、skip-docker-build、skip-archive 或 skip-frontend-release-tests 参数。

- 预期目标 Flyway 仍为 `20261022.03`、140 份迁移；原 d52 的139份及延期迁移均保留，无新增迁移。
- 清单继续为 `facts.rebuildRequired=true / scope=issue / odsCorrectionRequired=false`。原因是该替代包仍携带 d52 之后原延期包的事实变更，不能因新增修复仅影响实时下钻就删掉原有重建要求；下钻修复本身不新增事实构建要求。
- README 应保留“基于现有 ODS 重建 issue 事实”，不要求 GitLab 全量同步、历史 ODS 全量补偿或 MR/all 重建。不要把尚未部署的 025459 包作为 `--baseline-dir`。

### 4. 审计新包并完成隔离演练

按 `deploy/intranet-offline-packaging-standard.md` 的既有工作流执行，不另造测试框架：

1. 跑打包器现有测试；Git Bash 可用时应覆盖现有回滚执行用例，不以环境造成的全部回滚跳过代替验证。检查包内/包外 SHA-256、归档结构、Compose 解析、源码与 JAR/前端镜像摘要、清单与 README。实际镜像中的 `matchesMetric` 必须包含目标分支，不能只看到 Git 已含提交就认定旧镜像已更新。
2. 从真实 d52 镜像建立或恢复任务专用隔离基线，执行包内 backup→upgrade→rollback→backup→upgrade。核对目标/回滚镜像与健康、Flyway、备份可读、空 counts.diff、受保护数据及 PostgreSQL 容器 ID 全程不变；不通过恢复数据库来替代应用回滚。
3. 下钻的一行修复已有服务/页面验收，不重开整轮同步、延期写回或容量专项。包验收聚焦本次新镜像实际身份和既有升级链；不触碰真实 GitLab、生产平台或表单任务库。
4. 新包验证通过后再以旁注标记旧 025459 包被替代，保留其归档及 SHA-256；不篡改旧包追认修复。以“中文发布内容-新release-id”建立留档分支，准确指向清单 `source.commit` 并同步双远端，不指向出包后新写的文档 HEAD。

## 交付与审批

交回本会话：任务目录/基线/最终构建提交与差异；必要测试复用的身份依据、黄金 compare 和打包测试日志；新包目录、tar.gz、包外 sha256、包内 README 的绝对路径；归档大小与摘要、清单核心字段、镜像内目标分支核验；d52 隔离升级/回滚/再次升级证据及环境恢复状态；双远端与发布留档分支回读。

结果压缩维护到进度文档，完成后按生命周期收口本计划。用户仅要求在原包基础上增加已验收修复，遇到其他缺陷先报告证据及其是否阻断本包，不自行扩展整治。生产数据版本与五条截图议题仍未核验，交付需继续明确“未部署生产”。

## 约束、风险与关键身份

- 无新增 API、表结构、协议或关键常量；目标是既有 `module_total` 下钻过滤分支。核心风险是混入其他开发内容、错选直接基线、遗漏原延期包重建要求或把旧镜像当新包。
- 实包确认依据为归档摘要、清单、真实 JAR 摘要及 class 字节码；release-id 尾部 `b4d40e323a09` 不是源码 commit，不能将其当 Git revision。
- 文档指令不包含凭据、业务数据或生产写入授权；日志、截图及临时审计文件按 AGENTS 放任务树 `.tmp/` 等允许目录。后续环境与未决项以员工开工核对为准，不假定当前共享状态可写。
