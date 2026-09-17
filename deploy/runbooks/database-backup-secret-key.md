<!-- DOC_STATUS_START -->
> 文档状态：常驻 Runbook
> 说明：数据库备份主密钥 `PLATFORM_BACKUP_SECRET_KEY` 的部署配置操作规程——如何生成、放在哪、如何生效与验证、缺失与非法的行为差异、轮换影响。
> 关联：离线包"每包自动生成密钥"的事实以 `deploy/intranet-offline-packaging-standard.md` 为权威；备份产物的恢复步骤见 `deploy/runbooks/database-backup-restore.md`。本文件是"在既有部署上手工配置/核对主密钥"的操作权威。
<!-- DOC_STATUS_END -->

# 数据库备份主密钥（PLATFORM_BACKUP_SECRET_KEY）配置说明

## 1. 这是什么，什么时候才需要

`PLATFORM_BACKUP_SECRET_KEY` 是**远程备份密码的加密主密钥**（AES-256-GCM，base64 编码的 32 字节）。备份管理页里填写的"远程备份服务器密码"用它加密后才落库；页面永不回显明文，编辑时留空 = 不修改。

**是否必须，取决于你用哪种备份模式：**

| 备份模式 | 是否需要主密钥 |
| --- | --- |
| **LOCAL（本机部署服务器）** | **不需要**。本地备份不加密任何凭据，密钥缺失也能正常备份 |
| **REMOTE（远程备份服务器）** | **需要**。保存/使用远程密码前必须配好密钥，否则被明确拒绝 |

> 若你**新部署用的是内网离线包**：`.env` 里已经**自动生成**了这行密钥（每包一个），无需手工添加——见 `deploy/intranet-offline-packaging-standard.md`。
> 若你是**把既有部署升级到含备份功能的版本**、且**要用 REMOTE 远程备份**：旧 `.env` 里没有这行，需要按本手册手工补一行。

## 2. 缺失 vs 非法：行为差异（重要警示）

后端启动时会解析该密钥（`BackupCryptoSupport` 构造期解码），两种"没配好"的后果**完全不同**：

- **缺失 / 留空**（未写这行，或 `PLATFORM_BACKUP_SECRET_KEY=`）：
  - 后端**正常启动**；LOCAL 本地备份**完全可用**；
  - 仅"保存远程配置 / 执行远程备份"被拒绝，页面报错：`服务端未配置备份主密钥 PLATFORM_BACKUP_SECRET_KEY（32 字节 base64），无法保存或使用远程密码`。
- **格式非法**（不是合法 base64，或解码后不是**恰好 32 字节**）：
  - 密钥解码在后端 Bean 构造期抛异常 → **Spring 上下文启动失败 → 后端容器起不来**。
  - 这比"缺失"严重得多。**填之前务必用 §3 的命令生成，不要手敲、不要截断、不要多加空格/换行。**

## 3. 如何生成

在部署服务器（或任意有 openssl 的机器）执行，生成 base64 的 32 字节随机密钥：

```bash
openssl rand -base64 32
```

输出是一行 44 个字符、以 `=` 结尾的 base64 串，例如（**仅示例，切勿使用**）：

```
Xk3vJ9mQ2pL7nR4sT8uV1wY6zA0bC5dE9fG2hI3jK4l=
```

无 openssl 时的等价方式：

```bash
python3 -c "import secrets, base64; print(base64.b64encode(secrets.token_bytes(32)).decode())"
```

**要求**：必须是 **32 字节**（base64 后 44 字符、结尾 `=`）。生成后：

- **只生成一次**，在该部署的整个生命周期内保持稳定（轮换代价见 §6）；
- 视同机密**离线留存一份**（如密码管理器）：万一 `.env` 丢失，已保存的远程密码将无法解密，需重新录入（LOCAL 备份与已产出的 `.dump` 文件不受影响）。

## 4. 放在哪里

放进**部署目录的 `.env` 文件**（与 `docker-compose.yml` 同目录、`docker compose --env-file .env` 读取的那个），新增一行：

```dotenv
PLATFORM_BACKUP_SECRET_KEY=<第 3 步生成的 base64 串>
```

- compose 的 backend 服务已声明 `PLATFORM_BACKUP_SECRET_KEY: ${PLATFORM_BACKUP_SECRET_KEY:-}`，会自动把 `.env` 里的值注入后端容器；无需改 `docker-compose.yml`。
- **不要**提交进版本库、不要写进前端或日志；`.env` 属现场机密文件。
- 与备份相关的其他 `.env` 行（离线包已带，手工部署时按需核对）：
  - `PLATFORM_BACKUP_HOST_DIR=/opt/qaflex-backups`：宿主机备份根目录，bind mount 进后端容器 `/var/lib/qaflex/backups`。刻意与部署目录分开，`docker compose down` 删栈也不会动到备份。
  - 实例标识：compose 部署下 `PLATFORM_INSTANCE_ID=${COMPOSE_PROJECT_NAME}`，它决定备份子目录名与文件名 `qaflex_<实例标识>_<时间>.dump`。因此**本地备份的宿主机实际落点 = `/opt/qaflex-backups/<COMPOSE_PROJECT_NAME>/`**。

## 5. 让配置生效并验证

`.env` 的环境变量在**容器创建时**注入，仅 `docker restart` 不会重新读取 `.env`——必须**重建 backend 容器**：

```bash
cd <部署目录>
docker compose --env-file .env up -d --force-recreate backend
```

验证：

```bash
# 1) 后端健康（非法密钥会导致起不来，这一步先兜底）
curl -fsS http://127.0.0.1:<backend端口>/actuator/health      # 期望 {"status":"UP"}

# 2) 确认密钥已注入（注意：这是机密，勿把输出粘进工单/聊天/日志）
docker exec <backend容器名> sh -c 'test -n "$PLATFORM_BACKUP_SECRET_KEY" && echo SET || echo EMPTY'
```

功能验证（页面 `/system-settings/backup`）：

- 只用 **LOCAL**：不填密钥也应能"立即备份"成功、历史列表出现新记录；
- 要用 **REMOTE**：填好远程主机/端口/用户/密码/目录 → "测试连接"（纯只读三查）通过 → 保存配置不再报"未配置主密钥"，即表示密钥生效。首次连接按提示"采纳实际指纹"后再保存。

## 6. 轮换（更换密钥）的影响

更换 `PLATFORM_BACKUP_SECRET_KEY` 后：

- **所有已保存的远程密码立即失效**（它们是用旧密钥加密的），执行远程备份会报 `远程密码解密失败：主密钥不匹配或密文已损坏，请重新录入远程密码` → 需在备份页**重新录入并保存远程密码**（用新密钥重新加密）。
- **LOCAL 本地备份、以及历史已产出的 `.dump` 文件不受影响**（它们不依赖该密钥）。
- 轮换步骤：改 `.env` 里的值 → `docker compose --env-file .env up -d --force-recreate backend` → 页面重新录入远程密码 → "测试连接"通过 → 保存。

## 7. 相关文档

- 离线包如何自动生成密钥与备份 env/compose 接线（权威）：`deploy/intranet-offline-packaging-standard.md`
- 备份产物（`.dump`）的整库恢复规程：`deploy/runbooks/database-backup-restore.md`
- 备份机制的架构决策（执行域自治、恢复仅走 runbook、凭据加密、版本一致性边界）：`docs/decisions.md`（D-11）
