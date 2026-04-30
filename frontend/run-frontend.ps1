$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $projectRoot "..\scripts\dev-env.ps1")
Set-Location $projectRoot

npm run dev -- --host 0.0.0.0 --port 18181
