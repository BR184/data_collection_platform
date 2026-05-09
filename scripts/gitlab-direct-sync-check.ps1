param(
  [string] $BaseUrl = "http://localhost:18080",
  [int] $ConfigId = 1,
  [string] $WebhookSecret = "",
  [switch] $SimulateWebhook,
  [switch] $StartIncrementalSync,
  [switch] $PollAfterSubmission,
  [switch] $RequireCleanMirror,
  [int] $MaxPollAttempts = 24,
  [int] $PollIntervalSeconds = 5,
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

function Test-ActiveStatus {
  param([string] $Status)
  return @("PENDING", "QUEUED", "RUNNING", "RETRYING", "CANCELLING") -contains $Status
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

function Wait-For-StableStatus {
  if ($DryRun) {
    return
  }
  if (-not $PollAfterSubmission) {
    return
  }

  Write-Section "Polling mirror status"
  for ($attempt = 1; $attempt -le $MaxPollAttempts; $attempt += 1) {
    $statusResponse = Invoke-PlatformApi -Method GET -Path (Build-ConfigPath "/api/gitlab-sync/status")
    Assert-ApiSuccess "Status poll $attempt returned success" $statusResponse
    $currentStatus = [string] $statusResponse.data.currentStatus
    Write-Host "poll=$attempt currentStatus=$currentStatus"
    if (-not (Test-ActiveStatus $currentStatus)) {
      Write-Check "Mirror status reached stable state" ($currentStatus -notin @("FAILED", "TIMEOUT")) $currentStatus
      return
    }
    Start-Sleep -Seconds ([Math]::Max(1, $PollIntervalSeconds))
  }
  Write-Check "Mirror status reached stable state within polling window" $false "attempts=$MaxPollAttempts"
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
  Write-Check "Current status is not failed" ($status.data.currentStatus -notin @("FAILED", "TIMEOUT")) $status.data.currentStatus
}

Write-Section "Source health"
$health = Invoke-PlatformApi -Method GET -Path "/api/gitlab-sync/source-health"
Assert-ApiSuccess "Source health endpoint returned success" $health

if (-not $DryRun) {
  $healthItems = @($health.data)
  $currentHealth = $healthItems | Where-Object { $_.configId -eq $ConfigId } | Select-Object -First 1
  Write-Check "Current source health is present" ($currentHealth -ne $null) "configId=$ConfigId"
  if ($currentHealth -ne $null) {
    Write-Host "health.currentStatus: $($currentHealth.currentStatus)"
    Write-Host "health.latestLogStatus: $($currentHealth.latestLogStatus)"
    Write-Host "health.mirrorTables: $($currentHealth.existingMirrorTables)/$($currentHealth.registeredMirrorTables)"
    Write-Check "Source health is not failed" ($currentHealth.latestLogStatus -notin @("FAILED", "TIMEOUT")) $currentHealth.latestLogStatus
    if ($RequireCleanMirror) {
      Write-Check "Required mirror tables exist" ($currentHealth.missingRequiredMirrorTables.Count -eq 0) "missing=$($currentHealth.missingRequiredMirrorTables.Count)"
      Write-Check "Fact layer is current" (-not [bool] $currentHealth.factLayerLagging) $currentHealth.factLayerMessage
    }
  }
}

Write-Section "Table sync diagnostics"
$tableDiagnosticsPath = Build-ConfigPath "/api/gitlab-sync/table-sync-diagnostics"
$tableDiagnostics = Invoke-PlatformApi -Method GET -Path $tableDiagnosticsPath
Assert-ApiSuccess "Table sync diagnostics endpoint returned success" $tableDiagnostics

if (-not $DryRun) {
  $data = $tableDiagnostics.data
  Write-Host "tableCount: $($data.tableCount)"
  Write-Host "dirtyTableCount: $($data.dirtyTableCount)"
  Write-Host "tasks: pending=$($data.pendingTaskCount), running=$($data.runningTaskCount), retrying=$($data.retryingTaskCount), failed=$($data.failedTaskCount), timeout=$($data.timedOutTaskCount)"
  Write-Check "No failed table sync tasks" ([int] $data.failedTaskCount -eq 0) "failed=$($data.failedTaskCount)"
  Write-Check "No timed-out table sync tasks" ([int] $data.timedOutTaskCount -eq 0) "timeout=$($data.timedOutTaskCount)"
  if ($RequireCleanMirror) {
    Write-Check "No dirty table sync states" ([int] $data.dirtyTableCount -eq 0) "dirty=$($data.dirtyTableCount)"
    Write-Check "No pending/running/retrying table sync tasks" (
      ([int] $data.pendingTaskCount + [int] $data.runningTaskCount + [int] $data.retryingTaskCount) -eq 0
    ) "pending=$($data.pendingTaskCount), running=$($data.runningTaskCount), retrying=$($data.retryingTaskCount)"
  }
  $problemTables = @($data.tables | Where-Object {
      $_.dirty -or $_.latestTaskStatus -in @("FAILED", "TIMEOUT", "RETRYING")
    } | Select-Object -First 10)
  if ($problemTables.Count -gt 0) {
    Write-Host "Problem tables:"
    foreach ($table in $problemTables) {
      Write-Host "  $($table.sourceTable): dirty=$($table.dirty), latestTask=$($table.latestTaskType)/$($table.latestTaskStatus), error=$($table.lastError)$($table.latestTaskError)"
    }
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
  Wait-For-StableStatus
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
  Wait-For-StableStatus
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
