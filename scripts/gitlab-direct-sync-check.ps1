param(
  [string] $BaseUrl = "http://localhost:18080",
  [int] $ConfigId = 1,
  [Alias("WebhookSecret")]
  [string] $SystemHookSecret = "",
  [Alias("SimulateWebhook")]
  [switch] $SimulateSystemHook,
  [switch] $StartIncrementalSync,
  [switch] $RunPageRefreshSmoke,
  [switch] $RunReviewDataContextSmoke,
  [switch] $PollAfterSubmission,
  [switch] $RequireCleanMirror,
  [int] $MaxPollAttempts = 24,
  [int] $PollIntervalSeconds = 5,
  [string] $PageRefreshBoardKey = "system-test-defect-summary",
  [string] $ReviewDataContextResourceType = "merge_request",
  [int[]] $ReviewDataContextRecordIds = @(),
  [Alias("WebhookProjectId")]
  [int] $SystemHookProjectId = 10,
  [Alias("WebhookObjectId")]
  [int] $SystemHookObjectId = 101,
  [Alias("WebhookTitle")]
  [string] $SystemHookTitle = "Simulated issue from direct sync check",
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

function Format-ListValue {
  param([object[]] $Values)
  $items = @($Values | Where-Object { $_ -ne $null -and -not [string]::IsNullOrWhiteSpace([string] $_) })
  if ($items.Count -eq 0) {
    return "-"
  }
  return $items -join ", "
}

function Write-TimezoneSample {
  Write-Section "Timezone sample"
  $utcNow = [DateTimeOffset]::UtcNow
  $beijingZone = [TimeZoneInfo]::FindSystemTimeZoneById("China Standard Time")
  $beijingNow = [TimeZoneInfo]::ConvertTime($utcNow, $beijingZone)
  Write-Host "utcNow: $($utcNow.ToString("o"))"
  Write-Host "beijingNow: $($beijingNow.ToString("o"))"
  Write-Host "expectedApiOffset: +08:00"
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
  $systemHookReceiverUrl = if ($data.systemHookReceiverUrl) { $data.systemHookReceiverUrl } else { $data.webhookReceiverUrl }
  $systemHookAutoRegistrationSupported = if ($null -ne $data.systemHookAutoRegistrationSupported) { $data.systemHookAutoRegistrationSupported } else { $data.webhookAutoRegistrationSupported }
  $systemHookAutoRegistered = if ($null -ne $data.systemHookAutoRegistered) { $data.systemHookAutoRegistered } else { $data.webhookAutoRegistered }
  $systemHookMessage = if ($data.systemHookMessage) { $data.systemHookMessage } else { $data.webhookMessage }
  Write-Host "systemHookReceiverUrl: $systemHookReceiverUrl"
  Write-Host "systemHookAutoRegistrationSupported: $systemHookAutoRegistrationSupported"
  Write-Host "systemHookAutoRegistered: $systemHookAutoRegistered"
  Write-Host "systemHookMessage: $systemHookMessage"

  Write-Check "Database connection is healthy" ([bool] $data.connectionOk) $data.connectionMessage
  Write-Check "Whitelist discovery is healthy" ([bool] $data.whitelistOk) $data.whitelistMessage
  Write-Check "Whitelist has discovered tables" ([int] $data.whitelistOptionCount -gt 0) "count=$($data.whitelistOptionCount)"
  Write-Check "System Hook receiver URL is present" (-not [string]::IsNullOrWhiteSpace($systemHookReceiverUrl)) $systemHookReceiverUrl
  if ($data.sourceMode -eq "DIRECT") {
    Write-Check "Direct mode skips automatic System Hook registration as expected" (-not [bool] $systemHookAutoRegistrationSupported) ""
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
  Write-Host "currentStartedAt: $($status.data.currentStartedAt)"
  if ($status.data.currentTask -ne $null) {
    Write-Host "currentTask: id=$($status.data.currentTask.id), type=$($status.data.currentTask.taskType), status=$($status.data.currentTask.status)"
    Write-Host "currentTaskTiming: queued=$($status.data.currentTask.queuedAt), started=$($status.data.currentTask.startedAt), finished=$($status.data.currentTask.finishedAt)"
  }
  $logs = @($status.data.logs)
  foreach ($syncType in @("FULL", "INCREMENTAL", "WEBHOOK", "COMPENSATION")) {
    $latestLog = $logs | Where-Object { $_.syncType -eq $syncType } | Select-Object -First 1
    if ($latestLog -ne $null) {
      Write-Host "latest$($syncType)Log: id=$($latestLog.id), status=$($latestLog.status), tables=$($latestLog.tableCount), records=$($latestLog.recordCount), finished=$($latestLog.finishedAt), message=$($latestLog.message)"
    }
  }
  Write-Check "Current status is not failed" ($status.data.currentStatus -notin @("FAILED", "TIMEOUT")) $status.data.currentStatus
} else {
  Write-Host "currentStatus: <api>"
  Write-Host "latestFULLLog: <api>"
  Write-Host "latestINCREMENTALLog: <api>"
  Write-Host "latestWEBHOOKLog: <api>"
}

Write-Section "Source health"
$health = Invoke-PlatformApi -Method GET -Path "/api/gitlab-sync/source-health"
Assert-ApiSuccess "Source health endpoint returned success" $health

if (-not $DryRun) {
  $healthItems = @($health.data)
  $currentHealth = $healthItems | Where-Object { $_.configId -eq $ConfigId } | Select-Object -First 1
  Write-Check "Current source health is present" ($currentHealth -ne $null) "configId=$ConfigId"
  if ($currentHealth -ne $null) {
    Write-Host "healthStatus: $($currentHealth.healthStatus)"
    Write-Host "healthMessage: $($currentHealth.healthMessage)"
    Write-Host "health.currentStatus: $($currentHealth.currentStatus)"
    Write-Host "health.latestLogStatus: $($currentHealth.latestLogStatus)"
    Write-Host "health.mirrorTables: $($currentHealth.existingMirrorTables)/$($currentHealth.registeredMirrorTables)"
    Write-Host "health.factLayerLagging: $($currentHealth.factLayerLagging) - $($currentHealth.factLayerMessage)"
    Write-Host "health.factCounts: mergeRequest=$($currentHealth.mergeRequestFactCount), issue=$($currentHealth.issueFactCount), integrationTest=$($currentHealth.integrationTestFactCount)"
    Write-Host "health.missingRequiredMirrorTables: $(Format-ListValue $currentHealth.missingRequiredMirrorTables)"
    Write-Check "Source health is not blocked" ($currentHealth.healthStatus -ne "BLOCKED") $currentHealth.healthMessage
    Write-Check "Source health is not failed" ($currentHealth.latestLogStatus -notin @("FAILED", "TIMEOUT")) $currentHealth.latestLogStatus
    if ($RequireCleanMirror) {
      Write-Check "Required mirror tables exist" ($currentHealth.missingRequiredMirrorTables.Count -eq 0) "missing=$($currentHealth.missingRequiredMirrorTables.Count)"
      Write-Check "Fact layer is current" (-not [bool] $currentHealth.factLayerLagging) $currentHealth.factLayerMessage
    }
  }
} else {
  Write-Host "healthStatus: <api>"
  Write-Host "healthMessage: <api>"
  Write-Host "health.factLayerLagging: <api>"
  Write-Host "health.factCounts: mergeRequest=<api>, issue=<api>, integrationTest=<api>"
  Write-Host "health.missingRequiredMirrorTables: <api>"
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
  $tables = @($data.tables)
  $processedTables = @($tables | Where-Object { $_.latestTaskStatus -in @("SUCCESS", "FAILED", "TIMEOUT", "PARTIAL_SUCCESS", "CANCELLED") }).Count
  $scannedRows = ($tables | ForEach-Object { if ($_.latestTaskRowsScanned -ne $null) { [long] $_.latestTaskRowsScanned } else { 0 } } | Measure-Object -Sum).Sum
  $appliedRows = ($tables | ForEach-Object { if ($_.latestTaskRowsApplied -ne $null) { [long] $_.latestTaskRowsApplied } else { 0 } } | Measure-Object -Sum).Sum
  Write-Host "syncMetrics: plannedTables=$($data.tableCount), processedTables=$processedTables, scannedRows=$scannedRows, appliedRows=$appliedRows, changedRows=n/a"
  Write-Host "syncMetricsNote: changedRows is not exposed by the current API; use appliedRows and no-change status as write-side signals."
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
} else {
  Write-Host "syncMetrics: plannedTables=<api>, processedTables=<api>, scannedRows=<api>, appliedRows=<api>, changedRows=n/a"
  Write-Host "syncMetricsNote: changedRows is not exposed by the current API; use appliedRows and no-change status as write-side signals."
}

Write-TimezoneSample

if ($RunPageRefreshSmoke) {
  Write-Section "Page refresh endpoint smoke"
  $pageStatusPath = "/api/statistic-boards/$PageRefreshBoardKey/status"
  $pageStatus = Invoke-PlatformApi -Method GET -Path $pageStatusPath
  Assert-ApiSuccess "Page refresh status endpoint returned success" $pageStatus
  if (-not $DryRun) {
    Write-Host "workspaceKey: $($pageStatus.data.workspaceKey)"
    Write-Host "status: $($pageStatus.data.status)"
    Write-Host "message: $($pageStatus.data.message)"
    Write-Host "mirrorStatus: $($pageStatus.data.mirrorStatus)"
    Write-Host "factStatus: $($pageStatus.data.factStatus)"
    Write-Host "lastSyncedAt: $($pageStatus.data.lastSyncedAt)"
  }

  $pageRefreshPath = "/api/statistic-boards/$PageRefreshBoardKey/refresh"
  $pageRefresh = Invoke-PlatformApi -Method POST -Path $pageRefreshPath
  Assert-ApiSuccess "Page refresh endpoint returned success" $pageRefresh
  if (-not $DryRun) {
    Write-Host "refresh.workspaceKey: $($pageRefresh.data.workspaceKey)"
    Write-Host "refresh.status: $($pageRefresh.data.status)"
    Write-Host "refresh.message: $($pageRefresh.data.message)"
    Write-Host "refresh.jobId: $($pageRefresh.data.jobId)"
    Write-Host "refresh.sourceTables: $(Format-ListValue $pageRefresh.data.sourceTables)"
    Write-Host "refresh.plannedTasks: $($pageRefresh.data.plannedTasks)"
    Write-Host "refresh.unsupportedTables: $(Format-ListValue $pageRefresh.data.unsupportedTables)"
    Write-Host "refresh.factRefreshPlanned: $($pageRefresh.data.factRefreshPlanned)"
    Write-Host "refresh.mirrorStatus: $($pageRefresh.data.mirrorStatus)"
    Write-Host "refresh.factStatus: $($pageRefresh.data.factStatus)"
  }
}

if ($RunReviewDataContextSmoke) {
  Write-Section "Review data GitLab context refresh smoke"
  $reviewBody = @{
    recordIds = @($ReviewDataContextRecordIds)
    resourceType = $ReviewDataContextResourceType
  }
  $reviewRefresh = Invoke-PlatformApi -Method POST -Path "/api/review-data/records/gitlab-context/refresh" -Body $reviewBody
  Assert-ApiSuccess "Review data GitLab context refresh endpoint returned success" $reviewRefresh
  if (-not $DryRun) {
    Write-Host "accepted: $($reviewRefresh.data.accepted)"
    Write-Host "jobId: $($reviewRefresh.data.jobId)"
    Write-Host "status: $($reviewRefresh.data.status)"
    Write-Host "resourceTypes: $(Format-ListValue $reviewRefresh.data.resourceTypes)"
    Write-Host "sourceTables: $(Format-ListValue $reviewRefresh.data.sourceTables)"
    Write-Host "plannedTasks: $($reviewRefresh.data.plannedTasks)"
    Write-Host "manualFieldsTouched: $($reviewRefresh.data.manualFieldsTouched)"
    Write-Host "message: $($reviewRefresh.data.message)"
    Write-Check "Review data GitLab context refresh does not touch manual fields" (-not [bool] $reviewRefresh.data.manualFieldsTouched) ""

    if ($reviewRefresh.data.jobId -ne $null) {
      $reviewStatus = Invoke-PlatformApi -Method GET -Path "/api/review-data/records/gitlab-context/refresh/$($reviewRefresh.data.jobId)"
      Assert-ApiSuccess "Review data GitLab context refresh status endpoint returned success" $reviewStatus
      Write-Host "statusCheck.status: $($reviewStatus.data.status)"
      Write-Host "statusCheck.sourceTables: $(Format-ListValue $reviewStatus.data.sourceTables)"
      Write-Host "statusCheck.plannedTasks: $($reviewStatus.data.plannedTasks)"
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

if ($SimulateSystemHook) {
  $effectiveSystemHookSecret = $SystemHookSecret
  Write-Section "Simulated issue system hook"
  if ([string]::IsNullOrWhiteSpace($effectiveSystemHookSecret)) {
    throw "SystemHookSecret is required when hook simulation is used"
  }

  $payload = @{
    object_kind = "issue"
    event_type = "issue"
    project_id = $SystemHookProjectId
    object_attributes = @{
      id = $SystemHookObjectId
      iid = $SystemHookObjectId
      title = $SystemHookTitle
      action = "update"
    }
  }

  $headers = @{
    "X-Gitlab-Event" = "Issue Hook"
    "X-Gitlab-Token" = $effectiveSystemHookSecret
  }
  $systemHook = Invoke-PlatformApi -Method POST -Path "/api/gitlab-sync/system-hook" -Body $payload -Headers $headers
  Assert-ApiSuccess "System hook receiver returned success" $systemHook
  if (-not $DryRun) {
    Write-Check "System hook receiver accepted payload" ([bool] $systemHook.data.accepted) ""
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
