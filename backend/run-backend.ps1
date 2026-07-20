# Local development only. Production backend JVM must run on the Ubuntu server
# under systemd; do not use this script as a production entrypoint.
$backendRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $backendRoot "..\scripts\dev-env.ps1")
Import-LocalEnvironmentFile (Join-Path $backendRoot ".env.local") | Out-Null
Set-Location $backendRoot

if ([string]::IsNullOrWhiteSpace($env:DATASOURCE_PASSWORD)) {
  throw "Missing DATASOURCE_PASSWORD. Set it in backend/.env.local or the launching process environment."
}

$currentRepoRoot = (Resolve-Path (Join-Path $backendRoot "..")).Path
$staleBackendProcesses = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
  Where-Object {
    ($_.ExecutablePath -like "$currentRepoRoot*") -or
    ($_.CommandLine -like "$currentRepoRoot*")
  }
if ($staleBackendProcesses) {
  Write-Warning "Detected stale qa-flex-platform Java process(es): $($staleBackendProcesses.ProcessId -join ', '). Stop them before investigating backend CPU issues."
}

$mvnArgs = @(
  "-Dmaven.test.skip=true",
  "-Dspring-boot.run.jvmArguments=-Ddebug=false -Dspring.devtools.restart.enabled=false -Dfile.encoding=UTF-8",
  "spring-boot:run"
)
& mvn @mvnArgs
