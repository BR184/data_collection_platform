$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path (Join-Path $scriptDir "..")).Path

function Prepend-PathEntry {
  param(
    [Parameter(Mandatory = $true)]
    [string] $PathEntry
  )

  if (-not (Test-Path -LiteralPath $PathEntry)) {
    return
  }

  $resolved = (Resolve-Path -LiteralPath $PathEntry).Path.TrimEnd("\")
  $existing = @()
  if ($env:PATH) {
    $existing = $env:PATH -split ";" | Where-Object {
      $_ -and $_.TrimEnd("\") -ne $resolved
    }
  }
  $env:PATH = (@($resolved) + $existing) -join ";"
}

function Remove-CommandFunction {
  param(
    [Parameter(Mandatory = $true)]
    [string] $Name
  )

  $command = Get-Command $Name -ErrorAction SilentlyContinue
  if ($command -and $command.CommandType -eq "Function") {
    Remove-Item -LiteralPath "Function:\$Name" -ErrorAction SilentlyContinue
  }
}

$javaHome = Join-Path $projectRoot "tools\jdk\jdk-21.0.10+7"
$mavenHome = Join-Path $projectRoot "tools\maven\apache-maven-3.9.9"

$env:JAVA_HOME = (Resolve-Path -LiteralPath $javaHome).Path
$env:MAVEN_HOME = (Resolve-Path -LiteralPath $mavenHome).Path
Prepend-PathEntry (Join-Path $env:MAVEN_HOME "bin")
Prepend-PathEntry (Join-Path $env:JAVA_HOME "bin")

$staleNodeRoot = "C:\Users\admin\my-nocobase-app\tools\node20\node-v20.18.3-win-x64"
if ($env:PATH) {
  $env:PATH = (($env:PATH -split ";") | Where-Object {
      $_ -and $_.TrimEnd("\") -ne $staleNodeRoot.TrimEnd("\")
    }) -join ";"
}

foreach ($name in @("node", "npm", "npx", "corepack", "yarn", "yarnpkg")) {
  Remove-CommandFunction $name
}

$programFilesNode = "C:\Program Files\nodejs"
Prepend-PathEntry $programFilesNode

$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[Console]::InputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom
