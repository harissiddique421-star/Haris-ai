param([string]$Python = 'python')
$ErrorActionPreference='Stop'
[Diagnostics.Process]::GetCurrentProcess().PriorityClass='BelowNormal'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
Push-Location -LiteralPath $projectRoot
try {
  # No package installation or download: use the locally installed toolchain.
  & $Python -m PyInstaller --noconfirm --distpath agent_dist --workpath build/agent desktop_agent.spec
  if ($LASTEXITCODE -ne 0) { throw 'Frozen desktop worker build failed.' }
  $executable=Join-Path $projectRoot 'agent_dist/myraa-agent/myraa-agent.exe'
  if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) { throw 'Frozen worker executable is missing.' }
  $stamp=[ordered]@{
    protocol=2
    executableSha256=(Get-FileHash -LiteralPath $executable -Algorithm SHA256).Hash.ToLowerInvariant()
    sourceCommit=((& git rev-parse HEAD) -join '').Trim()
    sourceDirty=(@(& git status --porcelain -- desktop_agent run_agent.py desktop_agent.spec).Count -gt 0)
  }
  $stamp | ConvertTo-Json | Set-Content -LiteralPath (Join-Path (Split-Path $executable) 'agent-protocol.json') -Encoding utf8NoBOM
  Write-Output 'Built protocol-2 native worker; run the frozen session and desktop action checks before release.'
} finally { Pop-Location }
