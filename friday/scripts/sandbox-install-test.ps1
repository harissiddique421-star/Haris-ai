# Run only inside a disposable Windows Sandbox. Host release input is read-only.
$ErrorActionPreference = 'Stop'
if ($env:USERNAME -ne 'WDAGUtilityAccount') { throw 'This installer test must run inside Windows Sandbox.' }
$record = [ordered]@{ environment = 'Windows Sandbox'; checks = @(); errors = @() }
$output = 'C:\MYRAAOutput\installer-result.json'
try {
  $installer = 'C:\MYRAAInput\MYRAA-Setup-2.0.0-rc.1.exe'
  $install = Start-Process -FilePath $installer -ArgumentList '/S' -Wait -PassThru
  $record.checks += @{ name = 'silent installation'; passed = $install.ExitCode -eq 0; exitCode = $install.ExitCode }
  $installRoot = Join-Path $env:LOCALAPPDATA 'Programs\MYRAA'
  $launcher = Join-Path $installRoot 'MYRAA.exe'
  $record.checks += @{ name = 'installed launcher and runtime'; passed = (Test-Path $launcher) -and (Test-Path (Join-Path $installRoot 'MYRAA-runtime.exe')) }
  if (-not (Test-Path $launcher)) { throw 'Installed application was not found.' }
  $env:MYRAA_DATA_DIR = 'C:\MYRAATestProfile'
  $env:MYRAA_PORT = '5190'
  New-Item -ItemType Directory -Path $env:MYRAA_DATA_DIR -Force | Out-Null
  '{"version":3,"onboardingComplete":true,"companion":{"enabled":false},"behavior":{"proactivity":"quiet","awayCheckin":false,"returnGreeting":false}}' | Set-Content -LiteralPath (Join-Path $env:MYRAA_DATA_DIR 'app-settings.v3.json') -Encoding UTF8
  Start-Process -FilePath $launcher -WindowStyle Hidden
  $api = $null
  for ($i = 0; $i -lt 90; $i++) {
    try { $api = Invoke-RestMethod 'http://127.0.0.1:5190/api/config' -TimeoutSec 2; break } catch { Start-Sleep -Seconds 1 }
  }
  $record.checks += @{ name = 'installed backend starts without Node or Python'; passed = $null -ne $api }
  if ($null -eq $api) { throw 'Installed backend did not become ready.' }
  $policy = Invoke-RestMethod 'http://127.0.0.1:5190/api/permissions'
  $record.checks += @{ name = 'fresh installation asks before app launch'; passed = $policy.policy.capabilities.LAUNCH_APP.decision -eq 'ask' }
  $record.checks += @{ name = 'fresh installation has no provider credential'; passed = $api.hasApiKey -eq $false }
  $page = Invoke-WebRequest 'http://127.0.0.1:5190/' -UseBasicParsing
  $record.checks += @{ name = 'installed UI served'; passed = $page.StatusCode -eq 200 -and $page.Content.Contains('<html') }
  # Stop only executables from this sandbox's installation before uninstalling.
  Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($installRoot + '\', [StringComparison]::OrdinalIgnoreCase) } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
  Start-Sleep -Seconds 2
  $uninstaller = Join-Path $installRoot 'Uninstall MYRAA.exe'
  if (-not (Test-Path $uninstaller)) { throw 'Installed uninstaller was not found.' }
  Start-Process -FilePath $uninstaller -ArgumentList '/S' -Wait
  for ($i = 0; $i -lt 30 -and (Test-Path $launcher); $i++) { Start-Sleep -Seconds 1 }
  $record.checks += @{ name = 'silent uninstall removes application'; passed = -not (Test-Path $launcher) }
  $record.checks += @{ name = 'uninstall preserves test user settings'; passed = Test-Path (Join-Path $env:MYRAA_DATA_DIR 'app-settings.v3.json') }
} catch { $record.errors += $_.Exception.Message }
$record | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $output -Encoding UTF8
