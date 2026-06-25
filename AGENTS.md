# Agent Toolchain & Shell Rules

> 给在本仓工作的 agent 看的硬性事实。先读这页，再发命令。
>
> 目标：避免每次会话都要重新探测 `mvn` / `java` / `node` 在哪、PowerShell 和 bash 的命令为什么写一份就跑不了一份。

## 0. 常驻业务规则入口

涉及任何页面设计、页面文案、统计口径、筛选条件、导出、下钻、规则说明、非法数据判定、事实字段生成或页面刷新状态时，必须先阅读：

- `docs/platform-page-business-rules.md`

这是数据采集平台所有页面必须遵守的业务规则总表。若它与旧文档、页面现状或代码实现冲突，默认以该文件为准；如果业务方确认口径变化，先更新该文件，再改代码和测试。

### 0.0.1 Markdown 文档维护与归档规则

当前仍在维护或与近期改动直接相关的 Markdown 必须保留在原业务路径，便于页面、规则和修复任务直接引用。当前主要入口包括：

- `docs/platform-page-business-rules.md`：页面业务规则总入口。
- `docs/current-state/`：当前对齐方案、近期修复决策和仍会用于后续验收的状态文档。
- `docs/audits/`：仍在跟踪或近期修复相关的差异审计记录。
- `docs/plans/`：仍有执行价值、待确认项或设计入口的计划文档。
- `docs/markdown-status-index.md`：Markdown 状态索引，移动、归档、新增重要文档后必须同步更新。

已完成、已废弃、仅作历史追溯的阶段报告、旧调查、旧对比记录和非当前维护范围审计记录，统一放入 `docs/archive/`。归档文档不作为当前实现依据；如果归档文档中的结论重新成为当前任务依据，必须先移回合适目录，或在 `docs/current-state/` / `docs/platform-page-business-rules.md` 中重新确认后再使用。

后续移动 Markdown 时必须同步修正文档内引用路径，避免留下指向旧路径的死链接。不要因为 IDE 仍打开了旧路径文档，就把已归档文档当作当前规则入口。

### 0.0.2 内网打包与增量更新规则入口

涉及内网离线打包、部署更新、Docker 容器、数据卷、全量同步、同步状态、用户持久化视图或页面设置保留时，必须先阅读：

- `docs/intranet-offline-packaging-standard.md`

当前内网已部署基线包为 `D:\projects\data_collection_platform_deploy\qa-flex-platform-intranet-20260618-runnable-empty-working-ubuntu2404-offline.tar.gz`。后续普通 bugfix 和代码更新默认基于该基线包已运行的内网实例做同容器代码增量更新，不重新制作全新的空平台全量包，不重建平台 PostgreSQL 数据卷，不清空同步状态和用户数据。只有业务方明确批准重建环境、清空环境或灾难恢复时，才允许重新走全量空平台部署流程。

内网服务器按无公网 Ubuntu 24.04 部署处理，普通增量更新不能要求目标服务器现场 `docker build`。即使 20260618 全量包里已有业务镜像，`docker build` 仍可能解析 `FROM eclipse-temurin:21-jre` / `FROM nginx:1.27-alpine` 并访问 Docker Hub，导致内网失败。面向内网交付的增量更新包必须带已经构建好的后端/前端业务镜像 tar，部署时只 `docker load` 这两个业务镜像，然后 `docker compose --env-file .env up -d --no-deps --force-recreate backend frontend`；不要重新加载 postgres，不要重建或删除 volume，不要执行 `docker compose down -v`。

### 0.0 老平台重构口径红线

本项目不是重新实现一个新业务平台，而是对老平台 `D:\projects\spidergitdata-dev` 的架构重构和体验升级。新平台可以改进半实时刷新、查询性能、筛选体验、权限、可维护性和用户效率，但凡涉及数据底层、事实字段、统计数量、列表字段、字段值、非法判定、导出、下钻、默认筛选范围和页面展示口径，必须优先遵从老平台代码中已经写死的规则以及 `docs/platform-page-business-rules.md`。

后续做功能对齐时，默认假设老平台规则是正确业务规则；不能因为新平台架构更现代、实现更方便，擅自设计一套“更合理”的数据口径。同一数据源、同一筛选条件下，新平台展示的数据集合、总数、字段含义、字段值和导出结果应尽量与老平台一致。允许做提升用户效率和体验的小改进，但这些改进不得改变数据本身、统计口径或用户看到的业务结果。当前阶段目标是先把老平台 1:1 重构到新平台，再讨论进一步产品化优化。

接口返回 200 只代表服务端处理成功，不自动等于用户侧可用。涉及弹窗、提交、导出、批量操作、刷新、下载、跳转、复制或任何受时间/空间限制的交互时，必须确认用户实际能完成完整操作窗口，不能只看接口状态码就判定功能可用。

后续页面联调、冒烟测试或真实链路测试中，如果操作路径经过任何下拉选择框，必须顺手确认平台级下拉样式是否仍然生效：弹层宽度跟选择框对齐，候选项按自适应标签平铺并自动换行，单选和多选都不能回退为固定阵列/网格，已选值直接在选择框内完整展示且不折叠为 `+N`，占位符在未选中和聚焦状态下都不能偏移。发现异常时优先修复平台级选择器样式或通用组件，不做页面局部补丁。

涉及标签组、对象分群、语义标签组、动态/静态分群、静态快照、规则 DSL、语义口径或 `semantic_tag_*` / `segment_*` 命名时，还必须先阅读：

- `docs/plans/2026-06-10-label-group-value-set-design.md`

该文件是标签组值集合能力的唯一设计入口，并记录已废弃方向边界。本仓已删除旧 `TagGroup`、`TagSelection`、`tagSelections`、`tag-groups` 运行时代码，也已删除本次误实现的 `business-tag-groups`、`semantic-tag-groups`、`semantic_tag_*`、`segment_*` 运行时代码和页面入口。后续没有新的明确需求前，不得重新引入这些命名、API、页面或数据库运行时模型。

## 0.1 测试策略（硬性约束）

**核心理念：只跑必要的测试，一次通过即止，严禁重复验证和多轮回归。**

### 后端测试规则

1. **编译优于测试**：任何代码改动后，优先只做编译验证（`mvn -DskipTests compile`），编译通过即视为基本正确。
2. **按需跑单测**：仅当改动涉及已有测试覆盖的代码路径，且明确要求时，才跑对应测试类。一次通过即视为验证完成。
3. **禁止全套验证**：严禁在未明确要求的情况下执行 `scripts/verify-local.ps1`。该脚本仅用于正式提交流程前的最终检查，日常开发中不使用。
4. **单测跑法**：用 `-Dtest=具体类名` 精确指定，不跑整个模块。一次命令完成，不重复执行。
5. **禁止多轮回归**：一个测试类在一次会话中最多执行一次。即使结果不理想，也必须先汇报并等待决策，不得自行加跑其他测试或用不同参数反复尝试。

### 前端测试规则

1. **类型检查优先**：改动 TypeScript/Vue 文件后，只跑 `npm.cmd run typecheck`。类型通过即视为验证通过。
2. **按需跑单测**：仅当改动涉及已有 vitest 测试文件且明确要求时，才跑指定测试文件（`npm.cmd run test -- 具体文件路径`）。一次通过即止。
3. **禁止 lint 全面检查**：除非明确要求，不执行 `npm.cmd run lint`。lint 问题由 IDE 实时提示处理。
4. **禁止多轮重跑**：一个测试文件在一次会话中最多执行一次。

### 通用约束

1. **禁止自创测试**：不得自行编写新的测试用例或测试脚本，除非有明确指令。
2. **禁止链式验证**：编译→单测→集成测试→端到端测试这种层层递进的验证方式在本项目中默认禁用。完成当前任务所需的**单一最小验证步骤**即可。
3. **失败即停**：任何测试步骤如果失败，立即停止并汇报，不得尝试修复后自动重跑。
4. **一次命令原则**：每个验证目标只用一条命令完成，不拆分成多条命令反复尝试。

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
| 本地 DB 密码 | `DATASOURCE_PASSWORD=change_this_password`。 |
| 后端端口 | `18080` |
| 前端端口 | `18181`（vite proxy → `http://localhost:18080`） |
| 行尾 | LF，强制（见 `.gitattributes` 和 `.editorconfig`）。**不要**写 CRLF。 |

`tools/` 在 `.gitignore` 中，是本机解压目录，不入仓。换机器时需要重新放入相同结构。

## 1.1 本地认证与启动事实

本地开发环境默认走本地认证，不依赖外部 SSO。

常用本地项目账号密码：

| 用途 | 账号/变量 | 密码/值 | 说明 |
|---|---|---|---|
| 认证提供方 | `PLATFORM_AUTH_PROVIDER` | `local` | 本地开发默认认证方式，不依赖外部 SSO。 |
| 平台管理员 | `admin` | `admin123` | 本地开发、页面联调和真实链路冒烟默认账号。 |
| 审批用户 | `approval` | `approval` | 本地审批链路验证账号。 |
| 平台数据库 | `DATASOURCE_PASSWORD` | `change_this_password` | 默认连接 `jdbc:postgresql://localhost:15432/qaflex`。 |

- 后端本地启动时，常需要显式设置 `PLATFORM_SECURE_CONFIG_REQUIRED=false`
- 涉及登录、提交、搜索、刷新后再提交等有状态接口时，通常需要同时携带 `XSRF-TOKEN` Cookie 和 `X-XSRF-TOKEN` 请求头
- 内网或 Docker Compose 可能显式配置 `PLATFORM_AUTH_CSRF_ENABLED=false`。此时 `/api/auth/current` 可能不会下发 `XSRF-TOKEN`，真实链路脚本不能因为拿不到 XSRF Cookie 就判定登录失败；应直接 `POST /api/auth/login` 提交 JSON 账号密码，复用返回的 `JSESSIONID` 继续访问业务接口。
- 做 API 冒烟或真实链路时，先确认登录态和 CSRF 开关，再判断业务是否通了。脚本应自适应两种模式：CSRF 开启时带 `XSRF-TOKEN` Cookie 和 `X-XSRF-TOKEN` 请求头；CSRF 关闭时只依赖登录后的会话 Cookie。
- 代码改动后，若后端已在运行，必须重启最新后端实例再做页面联调或接口验证；不要拿旧进程继续判断新代码是否生效
- 每次实现完或修改完需要做页面联调、冒烟或真实链路验证时，必须拉起或重启最新版后端和前端，再判断功能是否生效；不要用旧的 `18080` / `18181` 进程验证新代码。

### 1.2 后端拉起与排障事实

后端启动经常卡在“脚本已执行，但服务没真正监听 18080”这一层。后续排障按下面顺序来，别凭感觉猜：

1. **先看端口，不先看心情**：`Get-NetTCPConnection -LocalPort 18080` 没有 `Listen`，就不要默认后端已起来。
2. **优先用可见启动**：排查时优先直接执行 `powershell -NoProfile -ExecutionPolicy Bypass -File backend/run-backend.ps1`，比隐藏窗口更容易发现 Maven / Spring 的真实报错。
3. **后台启动必须带日志重定向**：如果一定要 `Start-Process`，必须同时重定向 stdout/stderr 到 `backend/logs/*.out.log` 和 `backend/logs/*.err.log`，否则只会得到“没反应”。
4. **不要把环境变量和 PATH 拼进超长 `-Command` 字符串**：PowerShell 很容易把 `$env:JAVA_HOME\bin` 之类写坏，导致命令在启动前就解析失败。需要环境变量时，先在当前 shell 设好，再调用启动脚本。
5. **区分两类失败**：
   - `18080` 没监听：后端没真正启动。
   - 日志里出现 GitLab 镜像库、`15434 refused`、某个同步表不存在：这通常是外部同步/刷新任务失败，不等于 Web 后端本身没拉起。
6. **每次改完代码都拉最新后端**：不要拿旧进程继续测新代码，也不要把旧日志当成这次启动结果。
7. **本地启动脚本必须跳过测试源码编译**：`backend/run-backend.ps1` 使用 `mvn -Dmaven.test.skip=true spring-boot:run`。原因是 `spring-boot:run` 默认会执行到 `testCompile`，一旦测试源码里残留已删除服务或旧接口引用，Web 后端会在启动前失败，导致 18080 永远不监听。日常代码正确性仍按 §0.1 单独跑 `mvn -DskipTests compile`，不要把启动和测试混在一起。

## 2. 默认 PATH 的坑

干净 bash 启动后：

- `where java` → 找不到
- `where mvn` → 找不到
- `where node` → `C:\Program Files\nodejs\node.exe`（OK）
- `where psql` → 找不到

且 `C:\Users\admin\my-nocobase-app\tools\node20\node-v20.18.3-win-x64` 这个旧 node 路径**可能**残留在用户 PATH 中，导致 node 版本不一致。

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

**关键**：MSYS bash 中 `PATH` 段必须用 `/d/...` 这种 Unix 风格，或者纯正斜杠的 `D:/...`。**不要**写 `D:\\projects\\...`：反斜杠会被 bash 当转义，导致 `command not found`。

### 3.2 在子 shell 中跑一条 Maven 命令（不污染当前环境）

```bash
( export JAVA_HOME=/d/projects/data_collection_platform/tools/jdk/jdk-21.0.10+7 \
    && export PATH="$JAVA_HOME/bin:/d/projects/data_collection_platform/tools/maven/apache-maven-3.9.9/bin:$PATH" \
    && cd backend && mvn -q -DskipTests compile )
```

## 4. PowerShell 与 bash 的命令差异速查

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
3. **不要**在 bash 里写 `$env:FOO = "x"`——这是 PowerShell 写法。
4. **不要**用 `NUL` 当 `/dev/null`——bash 用 `/dev/null`，PowerShell 用 `$null`。
5. 路径里有空格必须用引号或 `\ ` 转义；MSYS 中 `"C:/Program Files/nodejs/node.exe"` 最稳。
6. **不要**在 bash 里写 `&&` 串 PowerShell 命令。
7. **PowerShell 里 Maven/Java 的 `-Dkey=A,B` 这类含逗号参数必须整体加引号**，例如 `mvn -q "-Dtest=FooTest,BarTest" test`。bash 中不需要这层引号，但加上也安全。
8. **PowerShell 字符串中变量后紧跟 URL 查询参数时必须加花括号**，例如 `"${base}?projectId=325"`。不要写 `"$base?projectId=325"`，否则 `$base?` 可能被当成变量名，最终得到非法 URI。

## 5. 常用任务的精确命令

### 5.1 后端编译 / 单测

```bash
# 编译（日常开发最常用）
( export JAVA_HOME=/d/projects/data_collection_platform/tools/jdk/jdk-21.0.10+7 \
    && export PATH="$JAVA_HOME/bin:/d/projects/data_collection_platform/tools/maven/apache-maven-3.9.9/bin:$PATH" \
    && cd backend && mvn -q -DskipTests compile )

# 跑指定测试（仅在明确要求时）
( export JAVA_HOME=/d/projects/data_collection_platform/tools/jdk/jdk-21.0.10+7 \
    && export PATH="$JAVA_HOME/bin:/d/projects/data_collection_platform/tools/maven/apache-maven-3.9.9/bin:$PATH" \
    && cd backend && mvn -q -Dtest=具体类名 test )
```

### 5.2 前端

```bash
# 类型检查（改动 TS/Vue 后首选）
( cd frontend && npm.cmd run typecheck )

# 单测（仅在明确要求时，指定具体文件）
( cd frontend && npm.cmd run test -- src/views/具体文件.test.ts )

# 启动 dev server
( cd frontend && npm.cmd run dev )
```

### 5.3 启动后端 / 前端开发服务

后端：

```bash
export DATASOURCE_PASSWORD='your-local-password'
powershell -NoProfile -ExecutionPolicy Bypass -File backend/run-backend.ps1
```

PowerShell 排查后端启动时，推荐先用前台命令，确认 18080 监听后再做页面联调：

```powershell
$env:DATASOURCE_PASSWORD = "change_this_password"
$env:PLATFORM_SECURE_CONFIG_REQUIRED = "false"
powershell -NoProfile -ExecutionPolicy Bypass -File backend/run-backend.ps1
Get-NetTCPConnection -LocalPort 18080 -ErrorAction SilentlyContinue
```

如果必须后台启动，不要把 `$env:PATH` 拼进 `-Command` 字符串；优先调用启动脚本并重定向日志：

```powershell
$env:DATASOURCE_PASSWORD = "change_this_password"
$env:PLATFORM_SECURE_CONFIG_REQUIRED = "false"
Start-Process -FilePath powershell `
  -ArgumentList "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "D:\projects\data_collection_platform\backend\run-backend.ps1" `
  -WorkingDirectory "D:\projects\data_collection_platform" `
  -WindowStyle Hidden `
  -RedirectStandardOutput "D:\projects\data_collection_platform\backend\logs\backend-current.out.log" `
  -RedirectStandardError "D:\projects\data_collection_platform\backend\logs\backend-current.err.log"
```

前端：

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File frontend/run-frontend.ps1
```

## 6. 常见失败模式与对策

| 现象 | 根因 | 对策 |
|---|---|---|
| `mvn: command not found` | 默认 PATH 没有 Maven | 按 §3.1 export |
| `java: command not found` | 默认 PATH 没有 Java | 按 §3.1 export |
| `npm: command not found` | bash 中应使用 `npm.cmd` | 全部改 `npm.cmd` |
| `./scripts/foo.ps1: cannot execute binary file` | bash 不会解释 .ps1 | 改用 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/foo.ps1` |
| 中文输出乱码 | Console 编码非 UTF-8 | 按 §0.2 固定 UTF-8 |
| `git diff --check` 报警 / CRLF 警告 | 编辑器写了 CRLF | 强制 LF；遵守 `.gitattributes` |
| 后端启动报 `DATASOURCE_PASSWORD must not be null` | 没设密码环境变量 | `export DATASOURCE_PASSWORD='...'` |
| 路径含反斜杠导致 `command not found` | export 用了 `D:\\...` | 改 `/d/...` 或 `D:/...` |
| 后台启动后 18080 没监听且没日志 | `Start-Process` 没重定向，或 `-Command` 里的 PATH/变量被 PowerShell 解析坏 | 前台跑 `backend/run-backend.ps1`，或按 §5.3 后台模板重定向日志 |
| `spring-boot:run` 在 `testCompile` 阶段失败 | 直接跑 Maven 启动时编译了测试源码，测试里可能有已删除服务/旧接口引用 | 用 `backend/run-backend.ps1`，该脚本带 `-Dmaven.test.skip=true`；代码验证另跑 `mvn -DskipTests compile` |
| PowerShell 请求 URL 报 `Invalid URI: The hostname could not be parsed` | 字符串里写了 `"$base?x=1"`，变量插值把 `$base?` 解析坏 | 改成 `"${base}?x=1"` 或用 `[uri]::EscapeDataString(...)` 拼参数 |
| 日志有 `localhost:15434 refused` | 外部 GitLab 镜像库没开或同步源不可用 | 先确认 18080 是否监听；若监听，页面/API 可继续测，别误判为 Web 后端没启动 |

## 7. 写命令的最低标准（自检清单）

下命令前问自己：

1. 这条命令是给 **bash** 还是 **PowerShell**？语法不能混。
2. 涉及 Java/Maven？**有没有先 export PATH**？
3. 路径里有空格？**加了引号没？**
4. PATH 中的 Windows 路径**用了正斜杠或 `/d/`**？没有用 `D:\\`？
5. 重定向用了 **`/dev/null`** (bash) / **`$null`** (PS)？
6. 跑 .ps1 用了 **`powershell -NoProfile -ExecutionPolicy Bypass -File`**？
7. 用 `npm` 的地方写成了 **`npm.cmd`**？
8. PowerShell 里是否把含逗号的 `-D...=A,B` 参数整体加引号了？
9. 涉及页面或业务规则？**有没有先读 `docs/platform-page-business-rules.md`**？
10. 涉及标签组、分群？**有没有先读 `docs/plans/2026-06-10-label-group-value-set-design.md`**？
11. 涉及中文文件？**有没有显式使用 UTF-8 读取和写入**？
12. 这个测试是否必要？**是不是只跑了一次、没有重复**？失败是否已停并汇报？