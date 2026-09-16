#requires -Version 5.1
<# Read-only guard/syntax checks; no model download, server startup or inference. #>
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$checks = 0
function Check([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "Check failed: $Message" }
    $script:checks++
}
foreach ($name in @('Start-LocalAi.ps1', 'Sample-AiResources.ps1', 'Test-AiTrialScripts.ps1')) {
    $file = Join-Path $PSScriptRoot $name
    $tokens = $null; $errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($file, [ref]$tokens, [ref]$errors)
    Check ($errors.Count -eq 0) "PowerShell parses: $name"
    Check ((Get-Content -LiteralPath $file -Raw) -notmatch '[^\x00-\x7F]') "Windows PowerShell encoding: $name"
}
$denied = $false
try { & (Join-Path $PSScriptRoot 'Start-LocalAi.ps1') -ResourceRoot $projectRoot } catch { $denied = $_.Exception.Message -match 'ConfirmLocalTrial' }
Check $denied 'Server requires explicit confirmation before any filesystem or service changes.'
$missingRuntime = $false
try { & (Join-Path $PSScriptRoot 'Start-LocalAi.ps1') -ResourceRoot $projectRoot -ConfirmLocalTrial } catch { $missingRuntime = $_.Exception.Message -match 'portable Ollama' }
Check $missingRuntime 'Missing runtime never causes an automatic install.'
& node --check (Join-Path $PSScriptRoot 'check-ai-model.mjs')
Check ($LASTEXITCODE -eq 0) 'Node evaluation script parses.'
# Redirect stderr into the native process stream without exposing any environment.
$guardProcess = New-Object System.Diagnostics.Process
$guardProcess.StartInfo.FileName = (Get-Command node.exe).Source
$guardProcess.StartInfo.Arguments = '"' + (Join-Path $PSScriptRoot 'check-ai-model.mjs') + '"'
$guardProcess.StartInfo.UseShellExecute = $false
$guardProcess.StartInfo.CreateNoWindow = $true
$guardProcess.StartInfo.RedirectStandardError = $true
[void]$guardProcess.Start()
$guardError = $guardProcess.StandardError.ReadToEnd()
$guardProcess.WaitForExit()
Check ($guardProcess.ExitCode -eq 1 -and $guardError -match 'confirm-local-model-trial') 'Evaluation requires explicit confirmation before HTTP calls.'
$guardProcess.Dispose()
$sample = & (Join-Path $PSScriptRoot 'Sample-AiResources.ps1') -Seconds 1 | Select-Object -First 1 | ConvertFrom-Json
Check ($sample.freeMemoryMiB -gt 0 -and $sample.gpuFreeMiB -ge 0) 'Resource sampler returns host and GPU measurements.'
Write-Output "PASS: $checks AI trial script checks. No inference or installation performed."
