$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Resolve-Path (Join-Path $scriptDir "..")
$devEnvScript = Join-Path $scriptDir "dev-env.ps1"

if (-not (Test-Path -LiteralPath $devEnvScript)) {
  throw "Missing dev environment script: $devEnvScript"
}

function global:npm { throw "stale npm shim was not removed" }
function global:node { throw "stale node shim was not removed" }

. $devEnvScript

if (-not $env:JAVA_HOME.EndsWith("tools\jdk\jdk-21.0.10+7")) {
  throw "JAVA_HOME was not set to the project JDK: $env:JAVA_HOME"
}

if (-not $env:MAVEN_HOME.EndsWith("tools\maven\apache-maven-3.9.9")) {
  throw "MAVEN_HOME was not set to the project Maven: $env:MAVEN_HOME"
}

if (-not $env:POSTGRES_HOME.EndsWith("tools\postgresql-17.9\pgsql")) {
  throw "POSTGRES_HOME was not set to the project PostgreSQL client: $env:POSTGRES_HOME"
}

$mvn = Get-Command mvn -ErrorAction Stop
if ($mvn.Source -ne (Join-Path $projectRoot "tools\maven\apache-maven-3.9.9\bin\mvn.cmd")) {
  throw "mvn resolves to unexpected source: $($mvn.Source)"
}

$psql = Get-Command psql -ErrorAction Stop
if ($psql.Source -ne (Join-Path $projectRoot "tools\postgresql-17.9\pgsql\bin\psql.exe")) {
  throw "psql resolves to unexpected source: $($psql.Source)"
}

$npm = Get-Command npm -ErrorAction Stop
if ($npm.CommandType -eq "Function") {
  throw "npm still resolves to a PowerShell function"
}

$npmCmd = Get-Command npm.cmd -ErrorAction Stop
$npmCmdSource = [string] $npmCmd.Source
if (-not $npmCmdSource.EndsWith("npm.cmd")) {
  throw "npm.cmd does not resolve to npm.cmd: $npmCmdSource"
}

Write-Host "dev-env smoke test passed"
