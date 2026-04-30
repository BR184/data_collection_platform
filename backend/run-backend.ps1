$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $projectRoot "..\scripts\dev-env.ps1")
Set-Location $projectRoot

$currentRepoRoot = (Resolve-Path (Join-Path $projectRoot "..")).Path
$staleBackendProcesses = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
  Where-Object {
    ($_.ExecutablePath -like "$currentRepoRoot*") -or
    ($_.CommandLine -like "$currentRepoRoot*")
  }
if ($staleBackendProcesses) {
  Write-Warning "Detected stale qa-flex-platform Java process(es): $($staleBackendProcesses.ProcessId -join ', '). Stop them before investigating backend CPU issues."
}

$mvnArgs = @(
  "-Dspring-boot.run.jvmArguments=-Dspring.devtools.restart.enabled=false -Dfile.encoding=UTF-8",
  "spring-boot:run"
)
& mvn @mvnArgs
