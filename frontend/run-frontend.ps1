$frontendRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $frontendRoot "..\scripts\dev-env.ps1")
Set-Location $frontendRoot

npm.cmd run dev -- --host 0.0.0.0 --port 18181
