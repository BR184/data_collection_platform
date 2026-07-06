<#
.SYNOPSIS
  Build QA Flex Platform intranet offline packages.

.DESCRIPTION
  FreshEmpty creates a full fresh empty-platform package with postgres image,
  backend/frontend images, offline debs, compose, env files and deploy README.

  IncrementalImages creates a same-topology update package with backend/frontend
  business images only. It reuses the image tags from the baseline runnable
  docker-compose.yml so the intranet server does not need compose edits.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/package-intranet-offline.ps1 `
    -Mode FreshEmpty `
    -ReleaseLabel empty-matchmode-mysql

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/package-intranet-offline.ps1 `
    -Mode IncrementalImages `
    -ReleaseLabel ui-export-focus `
    -BaselineDeployDir D:\projects\data_collection_platform_deploy\qa-flex-platform-intranet-20260703-runnable-empty-matchmode-mysql-dbcc7865

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/package-intranet-offline.ps1 `
    -Mode IncrementalImages `
    -ReleaseLabel fact-rule-fix `
    -BaselineDeployDir D:\projects\data_collection_platform_deploy\qa-flex-platform-intranet-20260703-runnable-empty-matchmode-mysql-dbcc7865 `
    -RequireFactRebuild `
    -FactRebuildScope merge-request
#>

param(
  [Parameter(Mandatory = $true)]
  [ValidateSet('FreshEmpty', 'IncrementalImages')]
  [string]$Mode,

  [string]$ReleaseLabel = '',
  [string]$DeployRoot = 'D:\projects\data_collection_platform_deploy',
  [string]$BaselineDeployDir = '',
  [string]$TemplatePackageDir = '',
  [switch]$RequireFactRebuild,
  [ValidateSet('issue', 'merge-request', 'all')]
  [string]$FactRebuildScope = 'all',
  [switch]$Working,
  [switch]$SkipFrontendReleaseTests,
  [switch]$SkipBuild,
  [switch]$SkipDockerBuild,
  [switch]$SkipArchive
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$invocationPathProperty = $MyInvocation.MyCommand.PSObject.Properties['Path']
$invocationPath = if ($invocationPathProperty) { $invocationPathProperty.Value } else { '' }
$scriptRoot = if ($invocationPath) {
  Split-Path -Parent $invocationPath
} elseif ($PSScriptRoot) {
  $PSScriptRoot
} else {
  Join-Path (Get-Location).Path 'scripts'
}
$repoRoot = (Resolve-Path (Join-Path $scriptRoot '..')).Path

$javaHome = Join-Path $repoRoot 'tools\jdk\jdk-21.0.10+7'
$mavenHome = Join-Path $repoRoot 'tools\maven\apache-maven-3.9.9'
$backendJar = Join-Path $repoRoot 'backend\target\qa-flex-platform-backend-0.0.1-SNAPSHOT.jar'

function Write-Info([string]$Message) {
  Write-Host "[pack] $Message"
}

function Fail([string]$Message) {
  throw "[pack] $Message"
}

function Assert-PathExists([string]$Path, [string]$Name) {
  if (-not (Test-Path -LiteralPath $Path)) {
    Fail "$Name not found: $Path"
  }
}

function Invoke-Checked {
  param(
    [Parameter(Mandatory = $true)]
    [string]$FilePath,
    [string[]]$ArgumentList = @(),
    [string]$WorkingDirectory = $repoRoot
  )

  Write-Info ("run: {0} {1}" -f $FilePath, ($ArgumentList -join ' '))
  $previous = Get-Location
  try {
    Set-Location -LiteralPath $WorkingDirectory
    & $FilePath @ArgumentList
    if ($LASTEXITCODE -ne 0) {
      Fail "command failed with exit code ${LASTEXITCODE}: $FilePath $($ArgumentList -join ' ')"
    }
  } finally {
    Set-Location $previous
  }
}

function Write-Utf8NoBomFile([string]$Path, [string]$Content) {
  $dir = Split-Path -Parent $Path
  if ($dir -and -not (Test-Path -LiteralPath $dir)) {
    New-Item -ItemType Directory -Path $dir | Out-Null
  }
  $normalized = $Content -replace "`r`n", "`n"
  [System.IO.File]::WriteAllText($Path, $normalized, [System.Text.UTF8Encoding]::new($false))
}

function Copy-Directory([string]$Source, [string]$Destination) {
  Assert-PathExists $Source "source directory"
  if (Test-Path -LiteralPath $Destination) {
    Remove-Item -LiteralPath $Destination -Recurse -Force
  }
  New-Item -ItemType Directory -Path $Destination | Out-Null
  Get-ChildItem -LiteralPath $Source -Force |
    ForEach-Object {
      Copy-Item -LiteralPath $_.FullName -Destination $Destination -Recurse -Force
    }
}

function Get-GitValue([string[]]$GitArgs) {
  $output = & git -C $repoRoot @GitArgs 2>$null
  if ($LASTEXITCODE -ne 0) {
    return ''
  }
  if ($null -eq $output) {
    return ''
  }
  return (($output -join "`n").Trim())
}

function Get-DirtySuffix {
  $status = Get-GitValue @('status', '--short')
  if ($Working -or -not [string]::IsNullOrWhiteSpace($status)) {
    return '-working'
  }
  return ''
}

function Get-ImageTag {
  param([string]$DateStamp, [string]$ShortSha, [string]$DirtySuffix)
  return "${DateStamp}-${ShortSha}${DirtySuffix}"
}

function Get-LatestTemplatePackageDir([string]$Root) {
  Assert-PathExists $Root 'deploy root'
  $candidate = Get-ChildItem -LiteralPath $Root -Directory |
    Where-Object {
      $_.Name -like 'qa-flex-platform-intranet-*-runnable-*' -and
      (Test-Path -LiteralPath (Join-Path $_.FullName 'offline-debs')) -and
      (Test-Path -LiteralPath (Join-Path $_.FullName 'docker-compose.yml'))
    } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

  if (-not $candidate) {
    Fail "no full package template found in $Root"
  }
  return $candidate.FullName
}

function Read-ComposeImageTag([string]$ComposePath, [string]$ImageName) {
  Assert-PathExists $ComposePath 'baseline docker-compose.yml'
  $content = Get-Content -LiteralPath $ComposePath -Encoding UTF8 -Raw
  $pattern = [regex]::Escape($ImageName) + ':(?<tag>[A-Za-z0-9_.-]+)'
  $match = [regex]::Match($content, $pattern)
  if (-not $match.Success) {
    Fail "cannot find image tag for ${ImageName} in $ComposePath"
  }
  return $match.Groups['tag'].Value
}

function New-EnvContent {
  $content = @'
# Platform URL reachable by users and GitLab system hook.
PLATFORM_PUBLIC_BASE_URL=http://172.22.10.115:18181

# GitLab web URL for links shown in the platform.
# GitLab PostgreSQL source connection is configured later in the UI.
GITLAB_WEB_BASE_URL=http://172.22.10.233

# Built-in platform database. This is not the GitLab source database.
POSTGRES_USER=qaflex
POSTGRES_PASSWORD=qaflex
POSTGRES_DB=qaflex
POSTGRES_PORT=15432
POSTGRES_BIND=127.0.0.1

# Platform ports.
FRONTEND_PORT=18181
FRONTEND_BIND=0.0.0.0
BACKEND_PORT=18080
BACKEND_BIND=127.0.0.1

# Platform login accounts initialized into the empty built-in database.
PLATFORM_ADMIN_USERNAME=admin
PLATFORM_ADMIN_PASSWORD=admin123
PLATFORM_APPROVAL_USERNAME=approval
PLATFORM_APPROVAL_PASSWORD=approval123

# Runtime options.
GITLAB_SYSTEM_HOOK_MAX_QUEUE_SIZE=1000
GITLAB_MAX_SYNC_THREADS=16
PLATFORM_QUERY_TIMEOUT_SECONDS=30
PLATFORM_SLOW_QUERY_THRESHOLD_MS=1000
REVIEW_DATA_SEARCH_INDEX_BACKFILL_ENABLED=false
CUSTOMER_ISSUE_DELAY_LABEL_WRITEBACK_API_ENABLED=false
'@
  return $content
}

function New-ComposeContent([string]$BackendTag, [string]$FrontendTag) {
  $content = @'
services:
  postgres:
    image: postgres:16-alpine
    container_name: qaflex-postgres
    restart: unless-stopped
    environment:
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
      POSTGRES_DB: ${POSTGRES_DB}
      TZ: Asia/Shanghai
    ports:
      - "${POSTGRES_BIND:-127.0.0.1}:${POSTGRES_PORT:-15432}:5432"
    volumes:
      - qaflex_pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 10s
      timeout: 5s
      retries: 12

  backend:
    image: qa-flex-platform-backend:__BACKEND_TAG__
    container_name: qaflex-backend
    restart: unless-stopped
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      TZ: Asia/Shanghai
      DATASOURCE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB}
      DATASOURCE_USERNAME: ${POSTGRES_USER}
      DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD}
      SPRING_SQL_INIT_MODE: never
      PLATFORM_TIME_ZONE: Asia/Shanghai
      PLATFORM_ADMIN_USERNAME: ${PLATFORM_ADMIN_USERNAME}
      PLATFORM_ADMIN_PASSWORD: ${PLATFORM_ADMIN_PASSWORD}
      PLATFORM_APPROVAL_USERNAME: ${PLATFORM_APPROVAL_USERNAME}
      PLATFORM_APPROVAL_PASSWORD: ${PLATFORM_APPROVAL_PASSWORD}
      PLATFORM_SECURE_CONFIG_REQUIRED: "false"
      PLATFORM_AUTH_CSRF_ENABLED: "false"
      GITLAB_WEB_BASE_URL: ${GITLAB_WEB_BASE_URL}
      GITLAB_SYSTEM_HOOK_BASE_URL: ${PLATFORM_PUBLIC_BASE_URL}/api/gitlab-sync/system-hook
      GITLAB_SYSTEM_HOOK_MAX_QUEUE_SIZE: ${GITLAB_SYSTEM_HOOK_MAX_QUEUE_SIZE:-1000}
      GITLAB_MAX_SYNC_THREADS: ${GITLAB_MAX_SYNC_THREADS:-16}
      PLATFORM_QUERY_TIMEOUT_SECONDS: ${PLATFORM_QUERY_TIMEOUT_SECONDS:-30}
      PLATFORM_SLOW_QUERY_THRESHOLD_MS: ${PLATFORM_SLOW_QUERY_THRESHOLD_MS:-1000}
      REVIEW_DATA_SEARCH_INDEX_BACKFILL_ENABLED: ${REVIEW_DATA_SEARCH_INDEX_BACKFILL_ENABLED:-false}
      CUSTOMER_ISSUE_DELAY_LABEL_WRITEBACK_API_ENABLED: ${CUSTOMER_ISSUE_DELAY_LABEL_WRITEBACK_API_ENABLED:-false}
    ports:
      - "${BACKEND_BIND:-127.0.0.1}:${BACKEND_PORT:-18080}:18080"
    volumes:
      - qaflex_backend_logs:/app/logs
    healthcheck:
      test: ["CMD-SHELL", "curl -fsS http://localhost:18080/actuator/health >/dev/null || exit 1"]
      interval: 20s
      timeout: 5s
      retries: 12
      start_period: 60s

  frontend:
    image: qa-flex-platform-frontend:__FRONTEND_TAG__
    container_name: qaflex-frontend
    restart: unless-stopped
    depends_on:
      backend:
        condition: service_healthy
    ports:
      - "${FRONTEND_BIND:-0.0.0.0}:${FRONTEND_PORT:-18181}:80"
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://127.0.0.1/ >/dev/null || exit 1"]
      interval: 20s
      timeout: 5s
      retries: 12

volumes:
  qaflex_pgdata:
  qaflex_backend_logs:
'@
  return $content.Replace('__BACKEND_TAG__', $BackendTag).Replace('__FRONTEND_TAG__', $FrontendTag)
}

function New-BackendDockerfile {
  $content = @'
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY app.jar /app/app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"
EXPOSE 18080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
'@
  return $content
}

function New-FrontendDockerfile {
  $content = @'
FROM nginx:1.27-alpine
COPY nginx-default.conf /etc/nginx/conf.d/default.conf
COPY dist/ /usr/share/nginx/html/
EXPOSE 80
'@
  return $content
}

function New-NginxConfig {
  $content = @'
server {
    listen 80;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;
    client_max_body_size 50m;

    location /api/ {
        proxy_pass http://backend:18080/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 10s;
        proxy_send_timeout 300s;
        proxy_read_timeout 300s;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
'@
  return $content
}

function New-DockerIgnore {
  $content = @'
backend/target/
frontend/node_modules/
frontend/dist/
.git/
.tmp/
.tmp-logs/
logs/
'@
  return $content
}

function Invoke-ProductBuild {
  if ($SkipBuild) {
    Write-Info 'skip frontend/backend build by parameter'
    return
  }

  Assert-PathExists $javaHome 'JAVA_HOME'
  Assert-PathExists $mavenHome 'Maven'
  $env:JAVA_HOME = $javaHome
  $env:PATH = "$javaHome\bin;$mavenHome\bin;$env:PATH"

  if (-not $SkipFrontendReleaseTests) {
    Invoke-Checked -FilePath 'npm.cmd' -ArgumentList @('run', 'test', '--', 'feature-manifest-access.test.ts', 'ux-interaction-regressions.test.ts') -WorkingDirectory (Join-Path $repoRoot 'frontend')
  } else {
    Write-Info 'skip frontend release tests by parameter'
  }

  Invoke-Checked -FilePath 'npm.cmd' -ArgumentList @('run', 'build') -WorkingDirectory (Join-Path $repoRoot 'frontend')
  Invoke-Checked -FilePath (Join-Path $mavenHome 'bin\mvn.cmd') -ArgumentList @('-q', '-f', (Join-Path $repoRoot 'backend\pom.xml'), '-Dmaven.test.skip=true', 'package') -WorkingDirectory $repoRoot
}

function Initialize-PackageLayout([string]$PackageDir) {
  if (Test-Path -LiteralPath $PackageDir) {
    Fail "package directory already exists: $PackageDir"
  }
  New-Item -ItemType Directory -Path $PackageDir | Out-Null
  New-Item -ItemType Directory -Path (Join-Path $PackageDir 'backend') | Out-Null
  New-Item -ItemType Directory -Path (Join-Path $PackageDir 'frontend') | Out-Null
  New-Item -ItemType Directory -Path (Join-Path $PackageDir 'docker-images') | Out-Null
}

function Copy-ApplicationArtifacts([string]$PackageDir) {
  Assert-PathExists $backendJar 'backend jar'
  Assert-PathExists (Join-Path $repoRoot 'frontend\dist') 'frontend dist'

  Copy-Item -LiteralPath $backendJar -Destination (Join-Path $PackageDir 'backend\app.jar') -Force
  Write-Utf8NoBomFile (Join-Path $PackageDir 'backend\Dockerfile') (New-BackendDockerfile)

  Copy-Directory -Source (Join-Path $repoRoot 'frontend\dist') -Destination (Join-Path $PackageDir 'frontend\dist')
  Write-Utf8NoBomFile (Join-Path $PackageDir 'frontend\Dockerfile') (New-FrontendDockerfile)
  Write-Utf8NoBomFile (Join-Path $PackageDir 'frontend\nginx-default.conf') (New-NginxConfig)
  Write-Utf8NoBomFile (Join-Path $PackageDir '.dockerignore') (New-DockerIgnore)
}

function Build-And-SaveImages([string]$PackageDir, [string]$BackendTag, [string]$FrontendTag, [bool]$IncludePostgres) {
  $imageDir = Join-Path $PackageDir 'docker-images'
  $backendImage = "qa-flex-platform-backend:${BackendTag}"
  $frontendImage = "qa-flex-platform-frontend:${FrontendTag}"

  if (-not $SkipDockerBuild) {
    Invoke-Checked -FilePath 'docker' -ArgumentList @('build', '-t', $backendImage, 'backend') -WorkingDirectory $PackageDir
    Invoke-Checked -FilePath 'docker' -ArgumentList @('build', '-t', $frontendImage, 'frontend') -WorkingDirectory $PackageDir
  } else {
    Write-Info 'skip docker build by parameter; existing local images will be saved'
  }

  if ($IncludePostgres) {
    Invoke-Checked -FilePath 'docker' -ArgumentList @('image', 'inspect', 'postgres:16-alpine') -WorkingDirectory $PackageDir
    Invoke-Checked -FilePath 'docker' -ArgumentList @('save', '-o', (Join-Path $imageDir 'postgres_16-alpine.tar'), 'postgres:16-alpine') -WorkingDirectory $PackageDir
  }

  Invoke-Checked -FilePath 'docker' -ArgumentList @('save', '-o', (Join-Path $imageDir "qa-flex-platform-backend_${BackendTag}.tar"), $backendImage) -WorkingDirectory $PackageDir
  Invoke-Checked -FilePath 'docker' -ArgumentList @('save', '-o', (Join-Path $imageDir "qa-flex-platform-frontend_${FrontendTag}.tar"), $frontendImage) -WorkingDirectory $PackageDir
}

function Copy-OffLineDebs([string]$PackageDir, [string]$TemplateDir) {
  $source = Join-Path $TemplateDir 'offline-debs'
  Assert-PathExists $source 'offline-debs template'
  Copy-Directory -Source $source -Destination (Join-Path $PackageDir 'offline-debs')
}

function New-FreshReadme([string]$PackageName, [string]$ImageTag) {
  $content = @'
# QA Flex Platform 内网离线部署包

本包是 **全新空数据部署包**，适用于 Ubuntu 24.04 amd64 无公网服务器。

请不要把本包当作保留旧平台数据库的普通增量包使用。只有新服务器首次部署、明确清空环境、灾难恢复或业务方批准重建时才使用本包。

## 固定地址

- 平台访问地址：http://172.22.10.115:18181
- GitLab Web 地址：http://172.22.10.233

包内 PostgreSQL 只作为平台内置库。GitLab PostgreSQL、老平台 MySQL、老平台 MongoDB 均在平台 UI 中配置，不要写入 DATASOURCE_URL。

## 1. 解压

````bash
tar -xzf __PACKAGE_NAME__-ubuntu2404-offline.tar.gz
cd __PACKAGE_NAME__
````

## 2. 安装离线 Docker 依赖

目标机已安装 Docker / Compose 时可跳过本步骤。

````bash
sudo dpkg -i offline-debs/ubuntu-24.04-amd64/*.deb || sudo apt-get -f install
sudo systemctl enable --now docker
sudo docker version
sudo docker compose version
````

## 3. 加载镜像

````bash
sudo docker load -i docker-images/postgres_16-alpine.tar
sudo docker load -i docker-images/qa-flex-platform-backend___IMAGE_TAG__.tar
sudo docker load -i docker-images/qa-flex-platform-frontend___IMAGE_TAG__.tar
````

## 4. 准备环境变量

````bash
cp .env.example .env
vi .env
````

默认 .env 已写入当前内网地址、端口和本地账号。按需只修改端口、绑定地址或密码，不要把 GitLab / MySQL / MongoDB 源库连接写进平台库变量。

## 5. 全新空数据部署

````bash
sudo docker compose --env-file .env up -d --force-recreate postgres backend frontend
sudo docker compose --env-file .env ps
````

如果是在测试机上替换旧的 qaflex-* 容器，并且业务方确认要清空平台库，先删除旧容器和旧 volume，再启动本包。删除 volume 会清空平台数据，请确认后再执行。

````bash
sudo docker rm -f qaflex-frontend qaflex-backend qaflex-postgres
sudo docker volume ls | grep qaflex
# 仅在确认清空旧平台数据时执行：
# sudo docker volume rm <approved-qaflex-volume-name>

sudo docker compose --env-file .env up -d --force-recreate postgres backend frontend
sudo docker compose --env-file .env ps
````

## 6. 健康检查

````bash
curl -fsS http://127.0.0.1:18080/actuator/health
curl -fsS http://127.0.0.1:18181/
sudo docker compose --env-file .env logs --tail=120 backend
sudo docker compose --env-file .env logs --tail=120 frontend
````

浏览器访问：http://172.22.10.115:18181

默认登录账号：

- 管理员：admin / admin123
- 审批用户：approval / approval123

## 7. 首次数据重新导入/同步

1. 登录平台后，在 GitLab 数据镜像设置页面配置 GitLab PostgreSQL 只读源库，执行全量同步。
2. 需要代码走查兼容模式数据时，在老平台数据库设置页面配置老平台 MySQL 源库并导入 MR/代码走查非法数据表。
3. 需要评审数据兼容模式时，在老平台 MongoDB 设置页面配置 MongoDB 源库并导入对应集合。
4. 数据导入或同步完成后，执行事实层刷新/重建，并等待统计快照预热完成后再验收系统测试、客户问题和代码走查页面。

## 8. 包完整性校验

````bash
sha256sum -c SHA256SUMS.txt
sudo docker compose --env-file .env config
````
'@
  return $content.Replace('__PACKAGE_NAME__', $PackageName).Replace('__IMAGE_TAG__', $ImageTag)
}

function New-IncrementalReadme([string]$PackageName, [string]$BackendTag, [string]$FrontendTag, [string]$BaselineName) {
  $factSection = if ($RequireFactRebuild) {
    @(
      ''
      '## 4. 事实层重建'
      ''
      '本更新包标记为需要事实层重建。该步骤只基于现有镜像表重建事实层和统计快照，不重新全量同步 GitLab，不删除平台数据。'
      ''
      '登录并保存 Cookie：'
      ''
      '````bash'
      'curl -c /tmp/qaflex-cookie.txt \'
      "  -H 'Content-Type: application/json' \"
      "  -d '{""username"":""admin"",""password"":""admin123""}' \"
      '  http://127.0.0.1:18181/api/auth/login'
      '````'
      ''
      '触发事实层重建：'
      ''
      '````bash'
      'curl -b /tmp/qaflex-cookie.txt -X POST \'
      "  'http://127.0.0.1:18181/api/facts/rebuild?scope=${FactRebuildScope}&full=true'"
      '````'
      ''
      '查询任务状态：'
      ''
      '````bash'
      'curl -b /tmp/qaflex-cookie.txt \'
      "  'http://127.0.0.1:18181/api/facts/build-tasks/latest?scope=${FactRebuildScope}'"
      '````'
    ) -join "`n"
  } else {
    @(
      ''
      '## 4. 不执行事实层重建'
      ''
      '本更新包只替换后端和前端业务镜像，不改变事实表、统计口径或历史聚合结果。部署后不要主动触发事实重建、全量同步或清空快照。'
      ''
    ) -join "`n"
  }

  $content = @'
# QA Flex Platform 前后端同容器更新包

本包用于既有内网实例的同容器增量更新，只替换后端和前端业务镜像。

目标基线部署目录：

````text
__BASELINE_NAME__
````

## 禁止操作

- 不要执行 `docker compose down -v`。
- 不要删除 `qaflex_pgdata` 或任何 PostgreSQL volume。
- 不要重新加载、重建或替换 postgres 镜像/容器。
- 不要清空镜像表、事实表、同步状态、用户、页面设置或平台配置。
- 不要在内网服务器执行 `docker build`。
- 不要因为本包部署而触发 GitLab 全量同步。

## 1. 解压

将本包放到既有部署目录旁边并解压：

````bash
tar -xzf __PACKAGE_NAME__-ubuntu2404-offline.tar.gz
````

## 2. 加载前后端业务镜像

进入既有部署目录：

````bash
cd __BASELINE_NAME__

sudo docker load -i ../__PACKAGE_NAME__/docker-images/qa-flex-platform-backend___BACKEND_TAG__.tar
sudo docker load -i ../__PACKAGE_NAME__/docker-images/qa-flex-platform-frontend___FRONTEND_TAG__.tar
````

本包镜像 tag 复用既有 compose 中的 tag，避免现场修改 docker-compose.yml：

- `qa-flex-platform-backend:__BACKEND_TAG__`
- `qa-flex-platform-frontend:__FRONTEND_TAG__`

## 3. 只重建后端和前端容器

````bash
sudo docker compose --env-file .env up -d --no-deps --force-recreate backend frontend
sudo docker compose --env-file .env ps
curl -fsS http://127.0.0.1:18080/actuator/health
````
__FACT_SECTION__

## 5. 冒烟检查

````bash
curl -fsS http://127.0.0.1:18080/actuator/health
curl -fsS http://127.0.0.1:18181/
sudo docker compose --env-file .env logs --tail=120 backend
sudo docker compose --env-file .env logs --tail=120 frontend
````

## 6. 包完整性校验

````bash
sha256sum -c ../__PACKAGE_NAME__/SHA256SUMS.txt
````
'@
  return $content.
      Replace('__PACKAGE_NAME__', $PackageName).
      Replace('__BACKEND_TAG__', $BackendTag).
      Replace('__FRONTEND_TAG__', $FrontendTag).
      Replace('__BASELINE_NAME__', $BaselineName).
      Replace('__FACT_SECTION__', $factSection)
}

function New-DeployHelper([string]$PackageName, [string]$BackendTag, [string]$FrontendTag, [bool]$NeedsFactRebuild, [string]$FactScope) {
  $factBlock = if ($NeedsFactRebuild) {
    @(
      'echo "[deploy] fact rebuild is required. Login and trigger it manually after health check:"'
      'echo "curl -c /tmp/qaflex-cookie.txt -H ''Content-Type: application/json'' -d ''{\"username\":\"admin\",\"password\":\"admin123\"}'' http://127.0.0.1:18181/api/auth/login"'
      "echo ""curl -b /tmp/qaflex-cookie.txt -X POST 'http://127.0.0.1:18181/api/facts/rebuild?scope=${FactScope}&full=true'"""
    ) -join "`n"
  } else {
    'echo "[deploy] fact rebuild is not required for this package."'
  }

  $content = @'
#!/usr/bin/env bash
set -euo pipefail

PACKAGE_DIR="__PACKAGE_NAME__"

echo "[deploy] loading backend/frontend images from ../${PACKAGE_DIR}"
sudo docker load -i "../${PACKAGE_DIR}/docker-images/qa-flex-platform-backend___BACKEND_TAG__.tar"
sudo docker load -i "../${PACKAGE_DIR}/docker-images/qa-flex-platform-frontend___FRONTEND_TAG__.tar"

echo "[deploy] recreating backend/frontend only"
sudo docker compose --env-file .env up -d --no-deps --force-recreate backend frontend
sudo docker compose --env-file .env ps

echo "[deploy] backend health"
curl -fsS http://127.0.0.1:18080/actuator/health

__FACT_BLOCK__
'@
  return $content.
      Replace('__PACKAGE_NAME__', $PackageName).
      Replace('__BACKEND_TAG__', $BackendTag).
      Replace('__FRONTEND_TAG__', $FrontendTag).
      Replace('__FACT_BLOCK__', $factBlock)
}

function Write-Sha256Sums([string]$PackageDir) {
  $packagePath = (Resolve-Path $PackageDir).Path
  $shaPath = Join-Path $packagePath 'SHA256SUMS.txt'
  if (Test-Path -LiteralPath $shaPath) {
    Remove-Item -LiteralPath $shaPath -Force
  }

  $lines = Get-ChildItem -LiteralPath $packagePath -File -Recurse |
    Where-Object { $_.FullName -ne $shaPath } |
    Sort-Object FullName |
    ForEach-Object {
      $relative = $_.FullName.Substring($packagePath.Length + 1).Replace('\', '/')
      $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
      "$hash  $relative"
    }

  Write-Utf8NoBomFile $shaPath (($lines -join "`n") + "`n")
}

function New-Archive([string]$PackageDir, [string]$ArchivePath) {
  if ($SkipArchive) {
    Write-Info 'skip archive by parameter'
    return
  }
  if (Test-Path -LiteralPath $ArchivePath) {
    Fail "archive already exists: $ArchivePath"
  }
  $parent = Split-Path -Parent $PackageDir
  $leaf = Split-Path -Leaf $PackageDir
  Invoke-Checked -FilePath 'tar.exe' -ArgumentList @('-czf', $ArchivePath, $leaf) -WorkingDirectory $parent
  $hash = (Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash.ToLowerInvariant()
  Write-Utf8NoBomFile "${ArchivePath}.sha256" "$hash  $(Split-Path -Leaf $ArchivePath)`n"
}

function Write-VersionFile(
  [string]$PackageDir,
  [string]$PackageName,
  [string]$PackageType,
  [string]$BackendTag,
  [string]$FrontendTag,
  [string]$Branch,
  [string]$Commit,
  [string]$DirtyState,
  [string]$BuildTime,
  [string]$BaselineName
) {
  $factLine = if ($RequireFactRebuild) { "Facts rebuild: required, scope=${FactRebuildScope}" } else { 'Facts rebuild: not required' }
  $baselineLine = if ([string]::IsNullOrWhiteSpace($BaselineName)) { 'Baseline: n/a' } else { "Baseline: ${BaselineName}" }
  $content = @"
Package: ${PackageName}
Package type: ${PackageType}
Target OS: Ubuntu 24.04 amd64, no internet
Branch: ${Branch}
Commit: ${Commit}
Working tree: ${DirtyState}
Built at: ${BuildTime}
${baselineLine}
${factLine}

Runtime image tags included:
- qa-flex-platform-backend:${BackendTag}
- qa-flex-platform-frontend:${FrontendTag}

Build commands:
- frontend: npm.cmd run test -- feature-manifest-access.test.ts ux-interaction-regressions.test.ts unless skipped
- frontend: npm.cmd run build unless skipped
- backend: mvn -q -f backend/pom.xml -Dmaven.test.skip=true package unless skipped
- docker build backend/frontend images unless skipped
- docker save included images

Data policy:
- FreshEmpty packages include postgres image and offline Docker debs, but no runtime database data.
- IncrementalImages packages include backend/frontend business images only and preserve existing PostgreSQL volume, mirror tables, facts, users, settings and sync state.

Standard:
- docs/intranet-offline-packaging-standard.md
"@
  Write-Utf8NoBomFile (Join-Path $PackageDir 'VERSION.txt') $content
}

Assert-PathExists (Join-Path $repoRoot 'docs\intranet-offline-packaging-standard.md') 'packaging standard'
Assert-PathExists (Join-Path $repoRoot 'AGENTS.md') 'AGENTS.md'
if (-not (Test-Path -LiteralPath $DeployRoot)) {
  New-Item -ItemType Directory -Path $DeployRoot | Out-Null
}

$dateStamp = Get-Date -Format 'yyyyMMdd'
$buildTime = Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'
$branch = Get-GitValue @('rev-parse', '--abbrev-ref', 'HEAD')
$commit = Get-GitValue @('rev-parse', 'HEAD')
$shortSha = Get-GitValue @('rev-parse', '--short=8', 'HEAD')
if ([string]::IsNullOrWhiteSpace($shortSha)) {
  $shortSha = 'nogit'
}
$dirtySuffix = Get-DirtySuffix
$dirtyState = if ($dirtySuffix -eq '-working') { 'working tree contains uncommitted changes or -Working was specified' } else { 'clean' }

if ([string]::IsNullOrWhiteSpace($ReleaseLabel)) {
  $ReleaseLabel = if ($Mode -eq 'FreshEmpty') { "empty-${shortSha}${dirtySuffix}" } else { "${shortSha}${dirtySuffix}" }
}
if ($ReleaseLabel -notmatch '^[A-Za-z0-9_.-]+$') {
  Fail "ReleaseLabel may only contain letters, numbers, dot, underscore and hyphen: $ReleaseLabel"
}

$freshImageTag = Get-ImageTag -DateStamp $dateStamp -ShortSha $shortSha -DirtySuffix $dirtySuffix
$backendTag = $freshImageTag
$frontendTag = $freshImageTag
$baselineName = ''

if ($Mode -eq 'IncrementalImages') {
  if ([string]::IsNullOrWhiteSpace($BaselineDeployDir)) {
    $baseline = Get-ChildItem -LiteralPath $DeployRoot -Directory |
      Where-Object {
        $_.Name -like 'qa-flex-platform-intranet-*-runnable-*' -and
        (Test-Path -LiteralPath (Join-Path $_.FullName 'docker-compose.yml'))
      } |
      Sort-Object LastWriteTime -Descending |
      Select-Object -First 1
    if (-not $baseline) {
      Fail 'BaselineDeployDir was not provided and no runnable deploy directory with docker-compose.yml was found.'
    }
    $BaselineDeployDir = $baseline.FullName
  }

  $baselineCompose = Join-Path $BaselineDeployDir 'docker-compose.yml'
  $backendTag = Read-ComposeImageTag -ComposePath $baselineCompose -ImageName 'qa-flex-platform-backend'
  $frontendTag = Read-ComposeImageTag -ComposePath $baselineCompose -ImageName 'qa-flex-platform-frontend'
  $baselineName = Split-Path -Leaf $BaselineDeployDir
}

$packageName = if ($Mode -eq 'FreshEmpty') {
  "qa-flex-platform-intranet-${dateStamp}-runnable-${ReleaseLabel}"
} else {
  $factSuffix = if ($RequireFactRebuild) { '-fact-rebuild' } else { '' }
  "qa-flex-platform-intranet-${dateStamp}-incremental-images-${ReleaseLabel}${factSuffix}"
}

$packageDir = Join-Path $DeployRoot $packageName
$archivePath = Join-Path $DeployRoot "${packageName}-ubuntu2404-offline.tar.gz"

Write-Info "mode: $Mode"
Write-Info "package: $packageName"
Write-Info "backend tag: $backendTag"
Write-Info "frontend tag: $frontendTag"

Invoke-ProductBuild
Initialize-PackageLayout $packageDir
Copy-ApplicationArtifacts $packageDir

if ($Mode -eq 'FreshEmpty') {
  if ([string]::IsNullOrWhiteSpace($TemplatePackageDir)) {
    $TemplatePackageDir = Get-LatestTemplatePackageDir $DeployRoot
  }
  Copy-OffLineDebs -PackageDir $packageDir -TemplateDir $TemplatePackageDir
  Write-Utf8NoBomFile (Join-Path $packageDir '.env.example') (New-EnvContent)
  Write-Utf8NoBomFile (Join-Path $packageDir '.env') (New-EnvContent)
  Write-Utf8NoBomFile (Join-Path $packageDir 'docker-compose.yml') (New-ComposeContent -BackendTag $backendTag -FrontendTag $frontendTag)
  Write-Utf8NoBomFile (Join-Path $packageDir 'README-INTRANET-DEPLOY.md') (New-FreshReadme -PackageName $packageName -ImageTag $backendTag)
  Build-And-SaveImages -PackageDir $packageDir -BackendTag $backendTag -FrontendTag $frontendTag -IncludePostgres $true
  Write-VersionFile -PackageDir $packageDir -PackageName $packageName -PackageType 'full fresh empty-platform offline deployment' -BackendTag $backendTag -FrontendTag $frontendTag -Branch $branch -Commit $commit -DirtyState $dirtyState -BuildTime $buildTime -BaselineName ''
} else {
  Write-Utf8NoBomFile (Join-Path $packageDir '.env.example') (New-EnvContent)
  Write-Utf8NoBomFile (Join-Path $packageDir '.env') (New-EnvContent)
  Write-Utf8NoBomFile (Join-Path $packageDir 'docker-compose.yml') (New-ComposeContent -BackendTag $backendTag -FrontendTag $frontendTag)
  Write-Utf8NoBomFile (Join-Path $packageDir 'README-INCREMENTAL-DEPLOY.md') (New-IncrementalReadme -PackageName $packageName -BackendTag $backendTag -FrontendTag $frontendTag -BaselineName $baselineName)
  New-Item -ItemType Directory -Path (Join-Path $packageDir 'deploy') | Out-Null
  Write-Utf8NoBomFile (Join-Path $packageDir 'deploy\update-backend-frontend-only.sh') (New-DeployHelper -PackageName $packageName -BackendTag $backendTag -FrontendTag $frontendTag -NeedsFactRebuild ([bool]$RequireFactRebuild) -FactScope $FactRebuildScope)
  Build-And-SaveImages -PackageDir $packageDir -BackendTag $backendTag -FrontendTag $frontendTag -IncludePostgres $false
  Write-VersionFile -PackageDir $packageDir -PackageName $packageName -PackageType 'backend/frontend same-container incremental image update' -BackendTag $backendTag -FrontendTag $frontendTag -Branch $branch -Commit $commit -DirtyState $dirtyState -BuildTime $buildTime -BaselineName $baselineName
}

Write-Sha256Sums $packageDir
New-Archive -PackageDir $packageDir -ArchivePath $archivePath

Write-Info "done: $packageDir"
if (-not $SkipArchive) {
  Write-Info "archive: $archivePath"
}
