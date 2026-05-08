param(
  [string] $BaseUrl = "http://localhost:18080",
  [int] $ConfigId = 1,
  [string] $WebhookSecret = "",
  [switch] $SimulateWebhook,
  [switch] $StartIncrementalSync,
  [int] $WebhookProjectId = 10,
  [int] $WebhookObjectId = 101,
  [string] $WebhookTitle = "Simulated issue from direct sync check",
  [switch] $DryRun
)

$ErrorActionPreference = "Stop"

function Normalize-BaseUrl {
  param([string] $Url)
  return $Url.TrimEnd("/")
}

function Build-ConfigPath {
  param([string] $Path)
  return "${Path}?configId=$ConfigId"
}

function Write-Section {
  param([string] $Title)
  Write-Host ""
  Write-Host "== $Title =="
}

function Write-Check {
  param(
    [string] $Name,
    [bool] $Passed,
    [string] $Details = ""
  )
  if ($Passed) {
    Write-Host "[OK] $Name $Details"
  } else {
    Write-Host "[FAIL] $Name $Details"
    $script:FailureCount += 1
  }
}

function Invoke-PlatformApi {
  param(
    [ValidateSet("GET", "POST")]
    [string] $Method,
    [string] $Path,
    [object] $Body = $null,
    [hashtable] $Headers = @{}
  )

  $uri = "$script:NormalizedBaseUrl$Path"
  if ($DryRun) {
    Write-Host "DRY RUN: $Method $uri"
    if ($Body -ne $null) {
      Write-Host ($Body | ConvertTo-Json -Depth 8)
    }
    return $null
  }

  $request = @{
    Method = $Method
    Uri = $uri
    Headers = $Headers
  }
  if ($Body -ne $null) {
    $request.ContentType = "application/json"
    $request.Body = ($Body | ConvertTo-Json -Depth 8)
  }
  return Invoke-RestMethod @request
}

function Assert-ApiSuccess {
  param(
    [string] $Name,
    [object] $Response
  )
  if ($DryRun) {
    return
  }
  Write-Check $Name ($Response.success -eq $true) $Response.message
}

$script:NormalizedBaseUrl = Normalize-BaseUrl $BaseUrl
$script:FailureCount = 0

Write-Section "GitLab direct sync diagnostics"
Write-Host "BaseUrl: $script:NormalizedBaseUrl"
Write-Host "ConfigId: $ConfigId"

$diagnosticsPath = Build-ConfigPath "/api/gitlab-sync/diagnostics/by-config"
$diagnostics = Invoke-PlatformApi -Method POST -Path $diagnosticsPath
Assert-ApiSuccess "Diagnostics endpoint returned success" $diagnostics

if (-not $DryRun) {
  $data = $diagnostics.data
  Write-Host "sourceInstance: $($data.sourceInstance)"
  Write-Host "sourceMode: $($data.sourceMode)"
  Write-Host "connectionOk: $($data.connectionOk) - $($data.connectionMessage)"
  Write-Host "whitelistOk: $($data.whitelistOk) - $($data.whitelistMessage)"
  Write-Host "whitelistOptionCount: $($data.whitelistOptionCount)"
  Write-Host "webhookReceiverUrl: $($data.webhookReceiverUrl)"
  Write-Host "webhookAutoRegistrationSupported: $($data.webhookAutoRegistrationSupported)"
  Write-Host "webhookAutoRegistered: $($data.webhookAutoRegistered)"
  Write-Host "webhookMessage: $($data.webhookMessage)"

  Write-Check "Database connection is healthy" ([bool] $data.connectionOk) $data.connectionMessage
  Write-Check "Whitelist discovery is healthy" ([bool] $data.whitelistOk) $data.whitelistMessage
  Write-Check "Whitelist has discovered tables" ([int] $data.whitelistOptionCount -gt 0) "count=$($data.whitelistOptionCount)"
  Write-Check "Webhook receiver URL is present" (-not [string]::IsNullOrWhiteSpace($data.webhookReceiverUrl)) $data.webhookReceiverUrl
  if ($data.sourceMode -eq "DIRECT") {
    Write-Check "Direct mode skips automatic webhook registration as expected" (-not [bool] $data.webhookAutoRegistrationSupported) ""
  }
}

Write-Section "Whitelist options"
$whitelistPath = Build-ConfigPath "/api/gitlab-sync/whitelist-options"
$whitelist = Invoke-PlatformApi -Method GET -Path $whitelistPath
Assert-ApiSuccess "Whitelist endpoint returned success" $whitelist

if (-not $DryRun) {
  $options = @($whitelist.data)
  Write-Host "Discovered options: $($options.Count)"
  $preview = $options | Select-Object -First 10 | ForEach-Object { $_.tableName }
  if ($preview.Count -gt 0) {
    Write-Host "First tables: $($preview -join ', ')"
  }
  Write-Check "Whitelist options are not empty" ($options.Count -gt 0) "count=$($options.Count)"
}

Write-Section "Current mirror status"
$statusPath = Build-ConfigPath "/api/gitlab-sync/status"
$status = Invoke-PlatformApi -Method GET -Path $statusPath
Assert-ApiSuccess "Status endpoint returned success" $status

if (-not $DryRun) {
  Write-Host "currentStatus: $($status.data.currentStatus)"
  Write-Host "currentMessage: $($status.data.currentMessage)"
  if ($status.data.currentTask -ne $null) {
    Write-Host "currentTask: id=$($status.data.currentTask.id), type=$($status.data.currentTask.taskType), status=$($status.data.currentTask.status)"
  }
}

if ($StartIncrementalSync) {
  Write-Section "Manual incremental sync"
  $incrementalPath = Build-ConfigPath "/api/gitlab-sync/incremental-sync/by-config"
  $incremental = Invoke-PlatformApi -Method POST -Path $incrementalPath
  Assert-ApiSuccess "Incremental sync submission returned success" $incremental
  if (-not $DryRun) {
    Write-Host "taskId: $($incremental.data.taskId)"
    Write-Host "status: $($incremental.data.status)"
    Write-Host "action: $($incremental.data.action)"
    Write-Host "message: $($incremental.data.message)"
  }
}

if ($SimulateWebhook) {
  Write-Section "Simulated issue webhook"
  if ([string]::IsNullOrWhiteSpace($WebhookSecret)) {
    throw "WebhookSecret is required when -SimulateWebhook is used"
  }

  $payload = @{
    object_kind = "issue"
    event_type = "issue"
    project_id = $WebhookProjectId
    object_attributes = @{
      id = $WebhookObjectId
      iid = $WebhookObjectId
      title = $WebhookTitle
      action = "update"
    }
  }

  $headers = @{
    "X-Gitlab-Event" = "Issue Hook"
    "X-Gitlab-Token" = $WebhookSecret
  }
  $webhook = Invoke-PlatformApi -Method POST -Path "/api/gitlab-sync/webhook" -Body $payload -Headers $headers
  Assert-ApiSuccess "Webhook receiver returned success" $webhook
  if (-not $DryRun) {
    Write-Check "Webhook receiver accepted payload" ([bool] $webhook.data.accepted) ""
  }
}

Write-Section "Result"
if ($DryRun) {
  Write-Host "Dry run completed. No requests were sent."
  exit 0
}

if ($script:FailureCount -gt 0) {
  Write-Host "Direct sync check failed with $script:FailureCount failed check(s)."
  exit 1
}

Write-Host "Direct sync check passed."
