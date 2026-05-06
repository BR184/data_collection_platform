param(
  [switch] $SkipDatabase
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path (Join-Path $scriptDir "..")).Path

powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scriptDir "dev-env.test.ps1")

. (Join-Path $scriptDir "dev-env.ps1")

Push-Location $projectRoot
try {
  python scripts/check_schema_flyway_drift.py
  python scripts/check_flyway_migration_immutability.py
  python scripts/check_api_contract_drift.py
  python scripts/check_frontend_api_boundary.py
  python scripts/check_fact_field_contract.py
  python scripts/check_flyway_profile_smoke_coverage.py
  python scripts/check_worktree_artifacts.py
  python scripts/check_runtime_artifact_locations.py
  python scripts/check_text_whitespace.py
  git diff --check
} finally {
  Pop-Location
}

Push-Location (Join-Path $projectRoot "backend")
try {
  mvn -q -DskipTests compile
  mvn -q -Dtest=GitlabSourceSchemaGuardTest test
  if (-not $SkipDatabase) {
    mvn -q -Dtest=FlywayMigrationSmokeTest test
    mvn -q '-Dspring.profiles.active=flyway-test' -Dtest=FactBuildTaskServiceTest test
  }
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
