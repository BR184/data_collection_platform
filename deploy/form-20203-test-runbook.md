# 20203 表单系统内网测试操作手册

本交付为功能分支测试候选，先验收再合 main。应用更新包保留原平台数据库、配置和卷；样本工具负责生产只读取样与专属测试 GitLab 导入。测试评论和标签只写测试 GitLab。所有口令在内网本地填写，包中不含生产凭据或生产数据。

## 1. 填写现场变量并核对身份

在 Ubuntu 24.04 的 Bash 中执行，替换两处绝对目录：

```bash
export DELIVERY='/绝对路径/本交付目录'
export APP_DIR='/绝对路径/现有20203部署目录'
export UPDATE_DIR="$(find "$DELIVERY" -maxdepth 1 -type d -name 'qaflex-update-*' -print -quit)"
export SERVER_IP='172.22.10.115'
test -f "$APP_DIR/.env" && test -f "$UPDATE_DIR/RELEASE-MANIFEST.json"
cd "$DELIVERY"
sha256sum -c SHA256SUMS.txt
docker compose --project-directory "$APP_DIR" --env-file "$APP_DIR/.env" -f "$APP_DIR/docker-compose.yml" ps
```

应确认前端 20203、后端 20200，原镜像版本为 `20261102T065925Z-bb9d51d048a5`，Compose project 为现场已有值。若实际基线不同，先停止升级并核对。记录平台和 GitLab 容器、卷名称；不要使用 `down -v`。

## 2. 备份和保数据升级

先在20203页面关闭自动同步、延期标签写回、评论投放及其他外部作业，等待活动运行结束；保留原开关值。

```bash
bash "$UPDATE_DIR/backup.sh" "$APP_DIR"
export BACKUP_DIR="$(cat "$APP_DIR/upgrade-backups/latest-predeploy-backup.txt")"
test -d "$BACKUP_DIR"
bash "$UPDATE_DIR/upgrade.sh" "$APP_DIR" "$BACKUP_DIR"
curl --fail http://127.0.0.1:20200/actuator/health
curl --fail -o /dev/null "http://$SERVER_IP:20203/"
```

预期后端 UP、前端可登录，既有数据保留。升级包含一次性补执行缺失的低版本迁移，正式服务恢复默认校验；具体校验、全事实重建与失败处理见更新包 `README-INCREMENTAL-DEPLOY.md`。异常时保持作业关闭；迁移后的回退按该文档恢复数据库备份和配套旧镜像，不能只换旧 JAR。

## 3. 准备空的专属测试 GitLab

旧测试 GitLab 保留。本工具要求新目标为空；已有人工评论、PAT或议题的旧目标不进行覆盖导入。随包 `sample-tools` 复用冻结导入导出工具，Compose仅保留GitLab及其三个卷，离线镜像仅包含GitLab与工具运行时。

```bash
cd "$DELIVERY/sample-tools"
sha256sum -c SHA256SUMS.txt
docker load -i docker-images/images.tar
python3 init-env.py --server "$SERVER_IP"
```

使用本地编辑器修改刚生成的 `.env`：`COMPOSE_PROJECT_NAME=dcp-form-sample-20203`，`WEB_BIND` 为服务器内网接口地址，`GITLAB_WEB_PORT=20283`、`GITLAB_DB_PORT=25483`、`GITLAB_PUBLIC_URL=http://172.22.10.115:20283`。同步把 `.secrets/target.pgpass` 中端口改成25483，其余随机密码保持。先确认20283/25483未占用；占用时另选并同步所有引用。

```bash
ss -lnt | grep -E ':(20283|25483)\b' || true
docker compose up -d gitlab
docker compose exec -T gitlab curl --fail http://127.0.0.1/-/readiness
curl --fail -o /dev/null "http://$SERVER_IP:20283/users/sign_in"
```

首次初始化需要数分钟。就绪前等待，不反复创建；导入前保持 GitLab 初始化状态，不登录、不创建 PAT/项目。

## 4. 从233只读导出325样本

以下来源必须是233上的 GitLab PostgreSQL，不是采集平台数据库。现场提供真实端口、库名和只读用户。先在 `.secrets/source.pgpass` 写入 `主机:端口:库名:用户:密码`，权限600。

```bash
cd "$DELIVERY/sample-tools"
chmod 600 .secrets/source.pgpass
export PGHOST='172.22.10.233'
export PGPORT='<现场GitLab数据库端口>'
export PGDATABASE='<现场GitLab数据库名>'
export PGUSER='<只读账号>'
bash transfer.sh preflight --report /reports/source-preflight.json
```

预检必须确认来源版本/结构受工具支持、SELECT权限齐全。推荐选择20条325议题，含已延期、正常计划、合法新计划覆盖旧计划、已关闭及有响应/回复的样本；填写 GitLab **全局 issue_id**，不是页面 `#编号`。选中议题的全部历史评论和标签、人员、项目、里程碑关联由工具完整导出。

```bash
export APPROVED_ISSUE_IDS='<全局ID1,全局ID2,...>'
bash transfer.sh source \
  --project-ids 325 --issue-ids "$APPROVED_ISSUE_IDS" --max-issues 20 \
  --batch-size 100 --batch-sleep-ms 200 \
  --statement-timeout-ms 30000 --lock-timeout-ms 2000 \
  --max-runtime-seconds 300 --max-total-rows 200000 --max-total-bytes 67108864 \
  --source-instance cc --output /work/form-20203-sample
unset PGHOST PGPORT PGDATABASE PGUSER PGSSLMODE
```

超时、超量或关联缺失即停止，使用完整导出包才进入下一步。样本正文留在内网受限目录；源码库与外发报告只记录计数和结果。

## 5. 导入测试 GitLab并核对

```bash
cd "$DELIVERY/sample-tools"
export TARGET_PGPORT=25483
docker compose exec -T gitlab gitlab-ctl stop puma
docker compose exec -T gitlab gitlab-ctl stop sidekiq
bash transfer.sh target --init-target --target-label form-20203 --report /reports/target-init.json
bash transfer.sh target --package /work/form-20203-sample --target-label form-20203 --report /reports/target-import.json
docker compose exec -T gitlab gitlab-ctl start puma
docker compose exec -T gitlab gitlab-ctl start sidekiq
```

预期导入报告 `IMPORTED`，外键孤儿/多态违规为0；项目325的议题、评论、标签和里程碑与导出清单一致。目标非空则停止，保留现场，不用删除表或覆盖标记绕过检查。然后登录测试 GitLab，使用本地新生成的管理员密码；创建或确认测试 `ci_robot` 账号，由它创建仅用于该测试实例的 `api` PAT。

## 6. 让20203采集测试来源

先把两个容器连接到同一Docker网络：

```bash
GL_ID="$(cd "$DELIVERY/sample-tools" && docker compose ps -q gitlab)"
BACKEND_ID="$(docker compose --project-directory "$APP_DIR" --env-file "$APP_DIR/.env" -f "$APP_DIR/docker-compose.yml" ps -q backend)"
test -n "$GL_ID" && test -n "$BACKEND_ID"
docker inspect "$BACKEND_ID" --format '{{json .NetworkSettings.Networks}}'
# 从上行选择20203本实例的网络名，不能选择生产网络：
export APP_NETWORK='<20203网络名>'
docker network connect --alias form-test-gitlab "$APP_NETWORK" "$GL_ID"
```

当前平台每类来源使用一份配置，因此在20203管理页面编辑已有CC连接，DGM保持关闭。先记录原连接与开关，并在“数据库查看”核对该测试库的`form_resource_authorities`和`form_record_locations`均为空；若已有表单权威或记录，保留现场并停止来源替换，先确定旧数据处置。

确认原同步和写回均已停止后，填写数据库 `form-test-gitlab:5432/gitlabhq_production`、用户gitlab、密码取sample-tools的本地.env；Web/API地址改为测试GitLab对外地址，PAT使用测试ci_robot的PAT。保存后先测试连接，再进行完整同步。

先关闭写回/投放，只启用本测试来源，执行首次全量同步。等同步成功、事实和页面完整发布，再按导入清单核对20条历史样本；若出现清单外的旧测试议题混入，停止开启写回并核查本次完整同步结果。历史数据按COMMENT处理，原平台库完整备份用于恢复原测试环境。

## 7. 准备表单并开启新议题联验

管理页分别发布CCProduct项目325的响应和回复范围。打开任一范围，找到“CCProduct · 议题表单”：开启“新议题使用表单”，选择“从现在开始”或指定生效时间T；开启“自动发送填写链接”，核对平台访问地址为 `http://172.22.10.115:20203`，点击“保存设置”。响应与回复显示同一份配置，保存后立即生效，无需修改环境变量或重启。

T应晚于导入历史样本的创建时间。先保存设置，再在测试GitLab创建新议题；通过管理页面开启测试来源自动同步（10分钟）和既有延期写回授权流程，使用测试ci_robot的API凭据。确认新议题响应/回复均按表单接入，历史议题继续评论；从回复范围再次打开设置，值应与响应范围一致。

关闭“新议题使用表单”会同时关闭链接投放，既有表单仍可维护。配置关闭期间首次处理的议题保持评论来源，重新开启不翻转已有登记。

## 8. 按表验收并记录结果

建立 `验收记录.md`，记录包ID、测试来源、项目、议题URL、每步实际结果和截图；失败项保留原始错误并停止后续写回。

| 操作 | 预期结果 |
| --- | --- |
| 查看导入历史样本 | 评论、计划、原因等与源样本一致；按COMMENT参与列表/统计/Excel |
| T之后在测试GitLab新建325议题，等待自动同步 | 新议题可查；ci_robot发布一条评论，含响应和回复两个入口 |
| 普通账号打开两个入口并保存 | 本人记录保存成功；刷新保留；对应页面能查到表单计划/原因 |
| 响应先填写已过期计划，再改为未来合法日期 | 延期页筛选、计数和Excel随之更新；解决已延期标签按规则添加/移除；合法日期不再被18天上限截断 |
| 回复选择申请延期，原因技术卡点 | 四个受管标签为状态：申请延期、技术卡点 |
| 原样保存，再改为资源卡点、再改数据异常 | 重复保存不重复加标；每次仅保留所选一个原因，替换旧原因 |
| 取消申请延期 | 四个受管标签全部移除，其余标签保持原业务规则 |
| 作废测试表单 | 页面和统计移除该记录的贡献，不回退旧评论模板 |
| 保持草稿时发布新版本 | 未保存输入保留并提示版本变化；旧记录仍按原版维护，新建进入新版独立表 |
| 复查同批历史COMMENT议题 | 原计划和来源不被新表单操作改写 |

新建测试议题应覆盖一个完整采集周期；每次记录实际耗时。统计页必须同时核对列表筛选数量、明细字段和导出，不以标签变化代替统计验收。代码走查生产启用和非法页旧来源保持既定边界。

## 9. 结束或失败后的处置

关闭测试来源的自动投放和标签写回，记录待处理任务；保留平台与专属GitLab的卷及验收材料。应用升级失败按照更新包恢复指令处理；样本导入失败只处理专属新GitLab，不改233或旧测试GitLab。验收通过后再提交main合入及正式发布结论。
