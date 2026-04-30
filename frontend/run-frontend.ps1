$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

$staleNodeRoot = "C:\Users\admin\my-nocobase-app\tools\node20\node-v20.18.3-win-x64"
if ($env:PATH) {
  $env:PATH = (($env:PATH -split ';') | Where-Object { $_ -and $_.TrimEnd('\') -ne $staleNodeRoot }) -join ';'
}

$programFilesNode = "C:\Program Files\nodejs"
if (Test-Path -LiteralPath (Join-Path $programFilesNode "npm.cmd")) {
  $env:PATH = "$programFilesNode;$env:PATH"
}

npm run dev -- --host 0.0.0.0 --port 18181
