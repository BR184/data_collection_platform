$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path (Join-Path $scriptDir "..")).Path

powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scriptDir "dev-env.test.ps1")

. (Join-Path $scriptDir "dev-env.ps1")

Push-Location (Join-Path $projectRoot "backend")
try {
  mvn -q -DskipTests compile
} finally {
  Pop-Location
}

Push-Location (Join-Path $projectRoot "frontend")
try {
  npm.cmd run typecheck
} finally {
  Pop-Location
}

Write-Host "local verification passed"
