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

function Import-LocalEnvironmentFile {
  param(
    [Parameter(Mandatory = $true)]
    [string] $Path
  )

  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
    return $false
  }

  foreach ($rawLine in Get-Content -LiteralPath $Path) {
    $line = $rawLine.Trim()
    if (-not $line -or $line.StartsWith("#")) {
      continue
    }

    $separatorIndex = $line.IndexOf("=")
    if ($separatorIndex -le 0) {
      throw "Invalid local environment entry in $Path. Expected NAME=value."
    }

    $name = $line.Substring(0, $separatorIndex).Trim()
    if ($name -notmatch "^[A-Za-z_][A-Za-z0-9_]*$") {
      throw "Invalid local environment variable name '$name' in $Path."
    }

    $value = $line.Substring($separatorIndex + 1).Trim()
    if ($value.Length -ge 2 -and (
        ($value.StartsWith('"') -and $value.EndsWith('"')) -or
        ($value.StartsWith("'") -and $value.EndsWith("'")))) {
      $value = $value.Substring(1, $value.Length - 2)
    }

    # The caller's process environment wins, so CI and deployment values are never overwritten.
    if ([Environment]::GetEnvironmentVariable($name, [EnvironmentVariableTarget]::Process) -eq $null) {
      [Environment]::SetEnvironmentVariable($name, $value, [EnvironmentVariableTarget]::Process)
    }
  }

  return $true
}

$javaHome = Join-Path $projectRoot "tools\jdk\jdk-21.0.10+7"
$mavenHome = Join-Path $projectRoot "tools\maven\apache-maven-3.9.9"
$postgresHome = Join-Path $projectRoot "tools\postgresql-17.9\pgsql"

$env:JAVA_HOME = (Resolve-Path -LiteralPath $javaHome).Path
$env:MAVEN_HOME = (Resolve-Path -LiteralPath $mavenHome).Path
$env:POSTGRES_HOME = (Resolve-Path -LiteralPath $postgresHome).Path
Prepend-PathEntry (Join-Path $env:POSTGRES_HOME "bin")
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
