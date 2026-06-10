<!-- DOC_STATUS_START -->
> 文档状态：常驻规则
> 说明：本机工具链与跨平台 shell 命令规范，所有 agent 必须先读这一份再执行命令。
<!-- DOC_STATUS_END -->

# Agent Toolchain & Shell Rules

## 0. 身份前提与工作节奏

当前 agent 是项目中的 AI 员工，听命于项目经理。每次完成项目经理分配的任务后，必须停下来等待项目经理确认；只有在收到项目经理明确确认或新的指令后，才能继续执行下一步工作。

> 给在本仓工作的 agent 看的硬性事实。先读这页，再发命令。
>
> 目标：避免每次会话都要重新探测 `mvn` / `java` / `node` 在哪、PowerShell 和 bash 的命令为什么写一份就跑不了一份。

## 0.1 常驻业务规则入口

涉及任何页面设计、页面文案、统计口径、筛选条件、导出、下钻、规则说明、非法数据判定、事实字段生成或页面刷新状态时，必须先阅读：

- `docs/platform-page-business-rules.md`

这是数据采集平台所有页面必须遵守的业务规则总表。若它与旧文档、页面现状或代码实现冲突，默认以该文件为准；如果业务方确认口径变化，先更新该文件，再改代码和测试。

涉及对象分群、语义标签组、动态/静态分群、静态快照、规则 DSL、语义口径或 `semantic_tag_*` / `segment_*` 命名时，还必须先阅读：

- `docs/plans/2026-06-09-deprecated-tag-group-segmentation-record.md`

该文件现在是废弃记录，不再是实施方案。本仓已删除旧 `TagGroup`、`TagSelection`、`tagSelections`、`tag-groups` 运行时代码，也已删除本次误实现的 `business-tag-groups`、`semantic-tag-groups`、`semantic_tag_*`、`segment_*` 运行时代码和页面入口。后续没有项目经理新的明确需求前，不得重新引入这些命名、API、页面或数据库运行时模型。

## 0.2 UTF-8 读写硬规则

本仓中文文档和源码均按 UTF-8 处理。读取、生成或修改中文文件时，必须显式固定 UTF-8，避免把控制台乱码误当业务事实。

PowerShell 读中文文件前先执行：

```powershell
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Get-Content -LiteralPath 'path\to\file.md' -Encoding UTF8
```

PowerShell 写中文文件时必须使用 UTF-8 无 BOM，优先用项目脚本或 `apply_patch`；如确需 PowerShell 写入，使用：

```powershell
[System.IO.File]::WriteAllText($path, $content, [System.Text.UTF8Encoding]::new($false))
```

不要用未指定编码的 `Get-Content`、`Set-Content`、`Out-File` 处理中文业务文档；不要在看到乱码时继续基于乱码内容做业务判断。

## 1. 真实运行环境（已实测）

| 项 | 事实 |
|---|---|
| Shell | **MSYS bash** (`/usr/bin/bash.exe`，MINGW64)。`uname -a` 返回 `MINGW64_NT-10.0-19045`。**不是** PowerShell，**不是** cmd。 |
| OS | Windows 10 Pro 19045 |
| PowerShell | **5.1.19041**（系统自带；不是 7.x）。脚本走 `powershell` 命令，**不是** `pwsh`。 |
| Java | **Temurin 21.0.10+7**，路径 `D:\projects\data_collection_platform\tools\jdk\jdk-21.0.10+7`（MSYS 形式 `/d/projects/data_collection_platform/tools/jdk/jdk-21.0.10+7`）。`where java` 在干净 PATH 中返回不到，**必须显式指定**。 |
| Maven | **Apache Maven 3.9.9**，路径 `D:\projects\data_collection_platform\tools\maven\apache-maven-3.9.9`。`where mvn` 同样找不到。 |
| Node | **v24.14.0**，`C:\Program Files\nodejs\node.exe`。bash 里 `node -v` 直接可用。 |
| npm | **v11.9.0**，bash 里**必须**用 `npm.cmd`（直接 `npm` 在 MSYS 下不是可执行 PE）。 |
| Python | **3.14.2**，`C:\Users\admin\AppData\Local\Microsoft\WindowsApps\python.exe`，`python` / `python3` 都可用。 |
| Postgres CLI | `D:\projects\data_collection_platform\tools\postgresql-17.9\pgsql\bin`（`psql.exe` 等）。 |
| 本地 DB | `jdbc:postgresql://localhost:15432/qaflex`，需要环境变量 `DATASOURCE_PASSWORD`。 |
| 后端端口 | `18080` |
| 前端端口 | `18181`（vite proxy → `http://localhost:18080`） |
| 行尾 | LF，强制（见 `.gitattributes` 和 `.editorconfig`）。**不要**写 CRLF。 |

`tools/` 在 `.gitignore` 中，是本机解压目录，不入仓。换机器时需要重新放入相同结构。

## 2. 默认 PATH 的坑

干净 bash 启动后：

- `where java` → 找不到
- `where mvn` → 找不到
- `where node` → `C:\Program Files\nodejs\node.exe`（OK）
- `where psql` → 找不到

且 `C:\Users\admin\my-nocobase-app\tools\node20\node-v20.18.3-win-x64` 这个旧 node 路径**可能**残留在用户 PATH 中，导致 node 版本不一致。`scripts/dev-env.ps1` 会显式把它从 PATH 中剔除——bash 里没人帮你做这件事，自己注意。

**结论**：每次执行 Java/Maven 相关命令前，都要先准备 PATH。直接运行 `mvn ...` 99% 会 `command not found`。

## 3. MSYS bash 中正确启用工具链

### 3.1 单次命令（一次性 export，推荐）

```bash
export JAVA_HOME=/d/projects/data_collection_platform/tools/jdk/jdk-21.0.10+7
export MAVEN_HOME=/d/projects/data_collection_platform/tools/maven/apache-maven-3.9.9
export POSTGRES_HOME=/d/projects/data_collection_platform/tools/postgresql-17.9/pgsql
export PATH="$JAVA_HOME/bin:$MAVEN_HOME/bin:$POSTGRES_HOME/bin:$PATH"
java -version
mvn -v
```

**关键**：MSYS bash 中 `PATH` 段必须用 `/d/...` 这种 Unix 风格，或者纯正斜杠的 `D:/...`。**不要**写 `D:\\projects\\...`：反斜杠会被 bash 当转义，导致 `command not found`。我已实测，用 `/d/...` 通过，用 `D:\...` 拼接 `:` 失败。

### 3.2 在子 shell 中跑一条 Maven 命令（不污染当前环境）

```bash
( export JAVA_HOME=/d/projects/data_collection_platform/tools/jdk/jdk-21.0.10+7 \
    && export PATH="$JAVA_HOME/bin:/d/projects/data_collection_platform/tools/maven/apache-maven-3.9.9/bin:$PATH" \
    && cd backend && mvn -q -DskipTests compile )
```

### 3.3 通过项目自带 `dev-env.ps1` 拉起（推荐用于 .ps1 任务）

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-local.ps1
```

`dev-env.ps1` 会把 JAVA_HOME / MAVEN_HOME / POSTGRES_HOME 等都设上、清掉旧 node、统一 UTF-8 编码。**任何 .ps1 脚本都必须通过 `powershell -NoProfile -ExecutionPolicy Bypass -File`** 来跑，不要直接 `./xxx.ps1`（MSYS 不会用 PowerShell 解释 .ps1）。

## 4. PowerShell 与 bash 的命令差异速查

复制命令前先确认目标 shell。混用是事故主因。

| 场景 | bash (MSYS) | PowerShell 5.1 |
|---|---|---|
| 路径分隔 | `/d/projects/...` 或 `D:/projects/...` | `D:\projects\...` 或 `D:/projects/...` 都行 |
| PATH 分隔 | `:` | `;` |
| 变量赋值 | `export FOO=bar` | `$env:FOO = "bar"` |
| 单行多变量 | `FOO=1 BAR=2 cmd` | `$env:FOO=1; $env:BAR=2; cmd` |
| 命令链 (上一条成功才下一条) | `a && b` | `a; if ($?) { b }`（PS 5.1 不支持 `&&`，PS 7+ 才支持） |
| 命令链 (无论成败) | `a; b` | `a; b` |
| 重定向 stdout 到文件 | `cmd > out.log` | `cmd > out.log` |
| 重定向 stderr 到文件 | `cmd 2> err.log` | `cmd 2> err.log` |
| 合并 stderr 到 stdout | `cmd 2>&1` | `cmd 2>&1` |
| 丢弃输出 | `cmd > /dev/null` | `cmd > $null`（**不要**写 `> NUL`，PS 中是普通文件名） |
| 丢弃输出 (cmd.exe) | n/a | `cmd > NUL`（在 cmd.exe 才有效，PS 中无效） |
| 行内注释 | `#` | `#` |
| 反引号转义 | `\"` `\\` | 反引号 `` ` ``（PS 转义符）；引号 `\"`（双引号串中） |
| 当前目录 | `pwd` / `$PWD` | `pwd` / `$PWD` / `Get-Location` |
| 列出环境变量 | `printenv FOO` 或 `echo $FOO` | `$env:FOO` |
| 多行字符串 | heredoc `<<EOF ... EOF` | here-string `@" ... "@` 或 `@' ... '@` |
| 删除文件 | `rm path` | `Remove-Item path` 或 `del path`（cmd 别名） |
| 强制删除目录 | `rm -rf dir` | `Remove-Item -Recurse -Force dir` |

**写命令的硬规则**：

1. 当前 shell 是 bash → 直接写 bash 语法。
2. 要跑 `.ps1` 脚本 → `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/xxx.ps1 [args]`，参数照原样传入；脚本内部是 PowerShell 语法。
3. **不要**在 bash 里写 `$env:FOO = "x"`——这是 PowerShell 写法，bash 会按字面解释失败。
4. **不要**用 `NUL` 当 `/dev/null`——`NUL` 仅在 cmd.exe 是设备名，bash 和 PowerShell 都把它当普通文件，会真的写一个名叫 `NUL` 的文件。MSYS bash 用 `/dev/null`，PowerShell 用 `$null`。
5. 路径里有空格（如 `C:\Program Files\nodejs`）必须用引号或 `\ ` 转义；MSYS 中 `"C:/Program Files/nodejs/node.exe"` 最稳。
6. **不要**在 bash 里写 `&&` 串 PowerShell 命令——上一条 `powershell -File xxx.ps1` 失败时 PowerShell 自身可能只 set 了 `$LASTEXITCODE`，bash 用 `&&` 是按 PowerShell 退出码判定的，可以；但反过来 PowerShell 5.1 里写 `a && b` 是语法错误。
7. **PowerShell 里 Maven/Java 的 `-Dkey=A,B` 这类含逗号参数必须整体加引号**，例如 `mvn -q "-Dtest=FooTest,BarTest" test`。未加引号时 PowerShell 会把逗号当数组/参数分隔，报 `Missing argument in parameter list`。bash 中不需要这层引号，但加上也安全。

## 5. 常用任务的精确命令

> 假设当前目录在仓库根 `D:\projects\data_collection_platform`，shell 是 MSYS bash。

### 5.1 后端编译 / 单测 / 全套验证

```bash
# 单次 PATH，跑 compile（最快）
( export JAVA_HOME=/d/projects/data_collection_platform/tools/jdk/jdk-21.0.10+7 \
    && export PATH="$JAVA_HOME/bin:/d/projects/data_collection_platform/tools/maven/apache-maven-3.9.9/bin:$PATH" \
    && cd backend && mvn -q -DskipTests compile )

# 跑指定测试
( export JAVA_HOME=/d/projects/data_collection_platform/tools/jdk/jdk-21.0.10+7 \
    && export PATH="$JAVA_HOME/bin:/d/projects/data_collection_platform/tools/maven/apache-maven-3.9.9/bin:$PATH" \
    && cd backend && mvn -q -Dtest=FactBuildServiceTest test )

# 一键全套本地校验（含 checkstyle、spotbugs、契约脚本、前端 lint+typecheck）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-local.ps1
# 跳过需要数据库的 Flyway smoke
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-local.ps1 -SkipDatabase
```

`mvn -q` 是 quiet 模式，CI 友好；调试时去掉 `-q` 看完整 reactor 输出。

### 5.2 前端

```bash
# 类型检查
( cd frontend && npm.cmd run typecheck )

# Lint
( cd frontend && npm.cmd run lint )

# 单测（vitest run，单跑一遍）
( cd frontend && npm.cmd run test )

# 单跑某个文件
( cd frontend && npm.cmd run test -- src/views/SystemTestIssueSearchView.test.ts )

# 启动 dev server（占用 18181）
( cd frontend && npm.cmd run dev )
```

注意 `npm.cmd run test -- <args>` 那个 `--` 是把后面的参数透传给 vitest，必须保留。

### 5.3 启动后端 / 前端开发服务

后端：

```bash
export DATASOURCE_PASSWORD='your-local-password'
export GITLAB_WEB_BASE_URL='http://your-gitlab-host'
powershell -NoProfile -ExecutionPolicy Bypass -File backend/run-backend.ps1
```

前端：

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File frontend/run-frontend.ps1
```

或在 bash 中直接：

```bash
( cd frontend && npm.cmd run dev -- --host 0.0.0.0 --port 18181 )
```

### 5.4 Python 校验脚本（Flyway / 契约 / 工件位置）

```bash
python scripts/check_schema_flyway_drift.py
python scripts/check_flyway_destructive_migrations.py
python scripts/check_api_contract_drift.py
# 等等，全部脚本见 scripts/*.py
```

`python` 在默认 PATH 里就能用，不需要 export。

### 5.5 Postgres CLI

```bash
export PATH="/d/projects/data_collection_platform/tools/postgresql-17.9/pgsql/bin:$PATH"
psql -h localhost -p 15432 -U postgres -d qaflex
```

或不污染 PATH：

```bash
'/d/projects/data_collection_platform/tools/postgresql-17.9/pgsql/bin/psql.exe' \
  -h localhost -p 15432 -U postgres -d qaflex
```

## 6. 常见失败模式与对策

| 现象 | 根因 | 对策 |
|---|---|---|
| `mvn: command not found` | 默认 PATH 没有 Maven | 按 §3.1 export |
| `java: command not found` | 默认 PATH 没有 Java | 按 §3.1 export |
| `npm: command not found` 或 `npm: No such file or directory` | bash 中应使用 `npm.cmd` | 全部改 `npm.cmd` |
| node 版本和预期不一致 | 旧 `my-nocobase-app/tools/node20` 在 PATH 前面 | bash 里手工把 `/c/Program Files/nodejs` 前置：`export PATH="/c/Program Files/nodejs:$PATH"`；或走 `dev-env.ps1` |
| `./scripts/foo.ps1: cannot execute binary file` | bash 不会解释 .ps1 | 改用 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/foo.ps1` |
| `command not found` 或 PATH 看起来很乱（含空格分段） | export 用了 `D:\\...` 反斜杠 | 改 `/d/...` 或 `D:/...` |
| 中文输出乱码 | Console 编码非 UTF-8，或读文件未指定 `-Encoding UTF8` | 先按 §0.2 固定 UTF-8；PowerShell 读取中文文件必须使用 `Get-Content -Encoding UTF8` |
| 文件多了一个名叫 `NUL` 的空文件 | bash/PS 把 `NUL` 当普通文件名 | bash 用 `> /dev/null`，PS 用 `> $null` |
| Flyway 测试失败提示 `connection refused` | 本地 Postgres 没起，或端口不是 15432 | 启动本机 Postgres 17（`tools/postgresql-17.9/pgsql/bin`），库名 `qaflex` |
| 后端启动报 `DATASOURCE_PASSWORD must not be null` | 没设密码环境变量 | bash: `export DATASOURCE_PASSWORD='...'`；PS: `$env:DATASOURCE_PASSWORD='...'` |
| `git diff --check` 报警 / CRLF 警告 | 编辑器写了 CRLF | 强制 LF；遵守 `.gitattributes` |
| stderr 看不到，只看到 stdout | 没合并流 | 命令尾加 `2>&1`，bash 和 PS 都支持 |

## 7. 写命令的最低标准（agent 自检清单）

下命令前问自己：

1. 这条命令是给 **bash** 还是 **PowerShell**？语法不能混。
2. 涉及 Java/Maven/psql？**有没有先 export PATH** 或 走 `dev-env.ps1`？
3. 路径里有空格？**加了引号没？**
4. PATH 中的 Windows 路径**用了正斜杠或 `/d/`**？没有用 `D:\\`？
5. 重定向到"无"用了 **`/dev/null`** (bash) / **`$null`** (PS)，不是 `NUL`？
6. 跑 .ps1 用了 **`powershell -NoProfile -ExecutionPolicy Bypass -File`**？
7. 用 `npm` 的地方写成了 **`npm.cmd`**？
8. PowerShell 里是否把含逗号的 `-D...=A,B` 参数整体加引号了？
9. 长命令是否塞进 `( ... )` 子 shell，避免污染外层 PATH？
10. 涉及页面或业务规则？**有没有先读 `docs/platform-page-business-rules.md`**？
11. 涉及分群、语义标签组或规则 DSL？**有没有先读 `docs/plans/2026-06-09-deprecated-tag-group-segmentation-record.md` 的废弃记录，并确认没有重新引入已删除的标签组/分群运行时模型**？
12. 涉及中文文件？**有没有显式使用 UTF-8 读取和写入**？

`scripts/verify-local.ps1` 是黄金路径——任何怀疑环境出问题时，先跑它一次，能过就说明本机工具链 OK。
