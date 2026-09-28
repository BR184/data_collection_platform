<!-- DOC_STATUS_START -->
> 文档状态：常驻 Runbook
> 说明：数据库备份的恢复操作规程，随备份管理页功能交付并经本地真实恢复演练验证。
<!-- DOC_STATUS_END -->

# 数据库备份恢复 Runbook

## 适用范围

平台数据库（`qaflex`，PostgreSQL 16）从备份管理页产出的 `.dump` 产物执行整库恢复。
产物由 `pg_dump --format=custom` 生成，经 `pg_restore --list` 可恢复性校验后落位。

- 本地（LOCAL）模式产物：部署服务器宿主机目录（默认 `/opt/qaflex-backups/<实例标识>/`，经 bind mount 映射为后端容器内 `/var/lib/qaflex/backups/<实例标识>/`）。
- 远程（REMOTE）模式产物：备份服务器 SFTP 目录；恢复前先由操作员把目标 `.dump` 下载到宿主机备份目录，再走同一路径。

页面上的备份历史（`/system-settings/backup`）提供每次运行的时间、大小与 SHA-256，用于人工核对产物完整性（下载或拷贝后可自行 `sha256sum` 比对）。

新备份文件名在时间戳后带运行 ID 与执行 token，避免同秒运行覆盖。自动轮转仅处理当前目录中与 `backup_runs` 成功记录完全匹配的产物；未登记文件和恢复中断留下的独立暂存文件不会被当成有效成功副本，也不会自动删除。旧版成功历史仍可按页面记录识别与恢复。恢复时以页面成功记录中的文件名、大小和 SHA-256 为准；不要把 `.staging/tmp-<运行 ID>-<token>.dump` 当作可恢复产物。

租约过期后的恢复巡检会先撤销执行 token，再确认 worker、子进程及远程会话已停止，随后才清理该次暂存文件并释放运行权。若停止或清理无法确认，运行权和暂存文件会保留供下一次巡检继续处理；操作员不要手动删除或复用这类文件。

失败运行可能保留候选文件名供定位，但 `FAILED` 状态、候选路径或磁盘上存在文件都不代表备份成功。恢复只接受页面 `SUCCESS` 记录中的文件名、大小和 SHA-256。

## 失败候选与孤立产物处置

- 先在备份历史中确认运行状态。只有 `SUCCESS` 且有文件大小、SHA-256 的记录可用于恢复；`FAILED` 行显示的文件名仅标记本次候选位置。
- `FAILED` 候选或无历史记录的文件不参与自动轮转，需人工核对精确路径。删除前确认没有 `SUCCESS` 记录指向该路径，并确认同一存储位置至少还有一份经校验的 `SUCCESS` 备份；否则保留文件并升级处理。
- `.staging/tmp-<运行 ID>-<token>.dump` 只由恢复巡检在确认对应执行停止后清理。若运行仍处于恢复待处理状态，不手动删除、覆盖或复用 staging 文件。
- 迁移前遗留的 `legacy-<运行 ID>` 执行身份若没有持久化 PID/进程启动时间，系统不能证明旧进程已停止，会保留运行权和暂存文件。先核实宿主机相关进程已结束，再按受控运维流程完成清理；不得仅因租约过期而释放运行权。
- 应用重启后若没有当前执行上下文和持久化 PID，系统无法证明后台 worker、远程会话或子进程均已停止，也会保留运行权与 staging；按受控运维流程确认全部外部执行已结束后再处置，不可将“PID 缺失”当作停止证明。

## 醒目警示

**恢复 = 全库覆盖回备份时刻点，备份点之后写入的全部数据永久丢失。**
执行前必须与业务方确认时间点可接受；如需保留备份点之后的少量数据，先另做一次当前库的 `pg_dump` 留存。

**PG 容器红线：全程只停 backend 容器，严禁 `docker rm` 或重建 postgres 容器**
（PG 数据卷由容器名/挂载关系锚定，重建即丢库，见打包标准与升级流程约束）。

**恢复操作必须在低峰期执行**；升级迁移（DDL）与恢复不要并发，runbook 错峰。

**客户端版本匹配**：custom 格式不向后兼容——执行恢复的 `pg_restore` 主版本必须不低于产出该 `.dump` 的 `pg_dump` 主版本（本地演练实证：PG 18 客户端产物无法被 PG 16 的 pg_restore 读取，报 `unsupported version (1.16) in file header`）。生产路径天然一致：后端镜像钉死 `postgresql-client-16` 且产物只落生产（打包期有 `pg_dump --version` 16.x 断言）。仅当把外部产物手工带入现场时，先用 `pg_restore --version` 核对版本。

## 恢复步骤（LOCAL 模式，后端容器自带客户端，零拷贝）

以下 `<...>` 均为现场占位符，按实例实际值替换；进入后端容器执行，客户端已在镜像内。

1. **定位目标备份**：页面 `/system-settings/backup` → 备份历史 → 记下目标文件名、大小、SHA-256。
2. **停 backend 容器**（释放数据库连接；PG 容器保留）：

   ```bash
   sudo docker stop <backend容器名>
   ```

3. **重建数据库**（强制断开残余连接后 drop/create；在 backend 容器内连 `postgres` 服务名执行）：

   ```bash
   sudo docker run --rm --network <compose网络名> -e PGPASSWORD='<POSTGRES_PASSWORD>' postgres:16-alpine \
     psql -h <postgres服务名默认postgres> -U qaflex -d postgres \
     -c "select pg_terminate_backend(pid) from pg_stat_activity where datname='qaflex' and pid <> pg_backend_pid();" \
     -c "drop database qaflex;" \
     -c "create database qaflex owner qaflex;"
   ```

4. **恢复 dump**（backend 容器内挂载路径直读，无需拷贝）：

   ```bash
   sudo docker run --rm --network <compose网络名> \
     -v <宿主机备份目录>:/restore:ro -e PGPASSWORD='<POSTGRES_PASSWORD>' postgres:16-alpine \
     pg_restore -h <postgres服务名默认postgres> -U qaflex -d qaflex --exit-on-error \
     /restore/<实例标识>/<目标文件名>.dump
   ```

   也可用 `sudo docker start <backend容器名>` 后再 `docker exec <backend容器> pg_restore ...`（此时后端已启动，恢复完成后必须重启 backend 以清空连接池缓存）。
5. **启动 backend 并验证健康**：

   ```bash
   sudo docker start <backend容器名>
   sudo docker logs --tail 50 <backend容器名>
   curl -fsS http://127.0.0.1:<backend端口>/actuator/health
   ```

6. **页面验收要点**：
   - 登录正常（用户/角色/权限来自恢复点）；
   - 备份管理页显示恢复点之后的历史记录（`backup_runs` 表随库恢复）；
   - 抽查 2-3 个核心业务页面数据与预期时间点一致。

## 恢复步骤（REMOTE 模式）

1. 从备份服务器把目标 `.dump` 下载到宿主机备份目录（`scp <备份服务器>:<远程目录>/<文件名> <宿主机备份目录>/<实例标识>/`）。
2. `sha256sum` 比对页面记录的 SHA-256。
3. 之后与 LOCAL 模式第 2 步起完全相同。

## 失败处置

- **pg_restore 报错中断**：`--exit-on-error` 模式下库处于部分恢复状态——不要尝试"续传"；重新 drop/create 后换产物重试，或回退到上一份备份。
- **恢复后后端起不来**：查 `docker logs`；常见为 Flyway 校验失败（备份点版本低于当前镜像预期）——说明该产物早于某次升级，须与升级基线对齐后再恢复对应时代的镜像版本。
- **产物校验不符**（大小/SHA-256 与页面记录不一致）：禁止使用该产物；换用历史列表中的其他成功备份。

## 相关文档

- 备份主密钥 `PLATFORM_BACKUP_SECRET_KEY` 的生成/放置/生效/轮换（REMOTE 远程备份前置）：`deploy/runbooks/database-backup-secret-key.md`
- 离线包备份 env/compose 接线与密钥自动生成（权威）：`deploy/intranet-offline-packaging-standard.md`
