param([string[]]$CharacterIds=@(),[string[]]$Presets=@('low','balanced','high'),[switch]$Resume,[ValidateRange(4,20)][double]$MinimumFreeGiB=5,[ValidatePattern('^[a-z0-9_-]{1,64}$')][string]$RunName='secondary')
$ErrorActionPreference='Stop'
[Diagnostics.Process]::GetCurrentProcess().PriorityClass='BelowNormal'
$projectRoot=Split-Path $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$sourcePaths=@('src/character','shared/character','shared/upperBodyPhysics.ts','src/companion/CompanionActor.ts','src/companion/FootContact.ts','tests/secondaryQA.ts','tests/rendererQA.ts','tests/locomotionQA.ts','tests/secondary-smoke.cjs')
if (@(& git ls-files --others --exclude-standard -- @sourcePaths).Count -gt 0) { throw 'Commit new runtime/scene files before running a matrix.' }
& git diff --quiet HEAD -- @sourcePaths
if ($LASTEXITCODE -ne 0) { throw 'Commit the tested runtime/scene snapshot before running a matrix.' }
$snapshot=(& git rev-parse HEAD).Trim()
$ownedQa=$null
try { $catalogue=Invoke-RestMethod 'http://127.0.0.1:5189/qa/catalogue' -TimeoutSec 2 } catch {
  if (Get-NetTCPConnection -LocalPort 5189 -State Listen -ErrorAction SilentlyContinue) { throw 'QA port belongs to an unexpected listener.' }
  $qaFile=Join-Path $projectRoot 'scripts\qa-server.ts'
  $ownedQa=Start-Process -FilePath (Get-Command node).Source -ArgumentList ('--import tsx "'+$qaFile+'"') -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot 'artifacts\matrix-asset-server.log') -RedirectStandardError (Join-Path $projectRoot 'artifacts\matrix-asset-server-error.log') -PassThru
  $ownedQa.PriorityClass='BelowNormal'
  $catalogue=$null
  for ($attempt=0;$attempt -lt 60;$attempt++) {
    try { $catalogue=Invoke-RestMethod 'http://127.0.0.1:5189/qa/catalogue' -TimeoutSec 1; break } catch { Start-Sleep -Milliseconds 200 }
  }
  if ($null -eq $catalogue) { if(-not $ownedQa.HasExited){$ownedQa.Kill()}; throw 'QA asset server did not start.' }
}
try {
if ($CharacterIds.Count -eq 0) { $CharacterIds=@('evelyn')+@($catalogue.id) }
$record=[ordered]@{ sourceCommit=$snapshot; scope='Sequential virtual-desktop renderer/physics tests; no provider calls'; cases=@(); stoppedReason=$null }
$recordPath=Join-Path $projectRoot ('artifacts\'+$RunName+'-matrix.json')
if ($Resume) {
  if (-not (Test-Path -LiteralPath $recordPath)) { throw 'No matrix checkpoint exists to resume.' }
  $record=Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json -AsHashtable
  & git diff --quiet $record.sourceCommit HEAD -- @sourcePaths
  if ($LASTEXITCODE -ne 0) { throw 'The matrix runtime differs from its source checkpoint.' }
  $snapshot=$record.sourceCommit
  $record.stoppedReason=$null
} elseif (Test-Path -LiteralPath $recordPath) { throw 'A matrix checkpoint exists. Use -Resume to preserve completed evidence.' }
$record.minimumFreeGiB=$MinimumFreeGiB
$env:ELECTRON_RUN_AS_NODE=$null
$env:MYRAA_QA_SOURCE_COMMIT=$snapshot
$env:MYRAA_SECONDARY_RUN=$RunName
foreach ($id in $CharacterIds) {
  foreach ($preset in $Presets) {
    if ($preset -notin @('low','balanced','high') -or $id -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid case selection.' }
    $existing=@($record.cases | Where-Object { $_.id -eq $id -and $_.preset -eq $preset -and $_.exitCode -eq 0 })
    if ($existing.Count -gt 0 -and (Test-Path -LiteralPath $existing[0].result)) {
      $proof=Get-Content -LiteralPath $existing[0].result -Raw | ConvertFrom-Json
      if ($proof.sourceCommit -eq $snapshot -and $proof.numericHealthy -and $proof.sitStandCompletes -and $proof.upperBodyControls -and $proof.phases.Count -eq 19 -and $proof.errors.Count -eq 0) {
        Write-Output ($id+' / '+$preset+': preserved completed case')
        continue
      }
    }
    & git diff --quiet HEAD -- @sourcePaths
    if ($LASTEXITCODE -ne 0) { $record.stoppedReason='Runtime source changed during the matrix.'; break }
    if (@(& git ls-files --others --exclude-standard -- @sourcePaths).Count -gt 0) { $record.stoppedReason='New uncommitted runtime files appeared during the matrix.'; break }
    $workspaceDrive=Get-PSDrive -Name ([IO.Path]::GetPathRoot($projectRoot).Substring(0,1))
    $freeRam=(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory*1KB
    if ($workspaceDrive.Free -lt ($MinimumFreeGiB*1GB) -or $freeRam -lt 3GB) { $record.stoppedReason='Reserved disk/RAM threshold reached; no extra case launched.'; break }
    $env:MYRAA_SECONDARY_ID=$id; $env:MYRAA_SECONDARY_PRESET=$preset
    $prefix=Join-Path $projectRoot ('artifacts\'+$RunName+'-'+$id+'-'+$preset)
    $scene=Start-Process -FilePath (Join-Path $projectRoot 'node_modules\electron\dist\electron.exe') -ArgumentList 'tests/secondary-smoke.cjs' -WorkingDirectory $projectRoot -WindowStyle Hidden -RedirectStandardOutput ($prefix+'.log') -RedirectStandardError ($prefix+'-error.log') -PassThru
    $scene.PriorityClass='BelowNormal'
    $deadline=[DateTime]::UtcNow.AddSeconds(420)
    while (-not $scene.HasExited -and [DateTime]::UtcNow -lt $deadline) { [void]$scene.WaitForExit(1000) }
    if (-not $scene.HasExited) { $scene.Kill($true); $exitCode=124 } else { $scene.Refresh(); $exitCode=$scene.ExitCode }
    $case=[ordered]@{ id=$id; preset=$preset; exitCode=$exitCode; result=('artifacts/'+$RunName+'/'+$id+'-'+$preset+'/result.json') }
    $record.cases=@($record.cases | Where-Object { -not ($_.id -eq $id -and $_.preset -eq $preset) })+@($case)
    $record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $recordPath -Encoding utf8
    Write-Output ($id+' / '+$preset+': '+$exitCode)
    Start-Sleep -Milliseconds 500
  }
  if ($record.stoppedReason) { break }
}
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $recordPath -Encoding utf8
if ($record.stoppedReason) { Write-Output $record.stoppedReason; exit 2 }
if (@($record.cases | Where-Object { $_.exitCode -ne 0 }).Count -gt 0) { exit 1 }
} finally { if($ownedQa -and -not $ownedQa.HasExited){$ownedQa.Kill()} }
