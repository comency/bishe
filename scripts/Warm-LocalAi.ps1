#requires -Version 5.1
<# Explicit operator preflight, not a business request. No download, app enabling or Docker changes. #>
[CmdletBinding()]
param([switch]$ConfirmLocalTrial)
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLocalTrial) { throw 'Pass -ConfirmLocalTrial for bounded preflight generation.' }
$endpoint = 'http://127.0.0.1:11434'
$model = 'qwen3:1.7b'
if ((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory * 1KB -lt 4GB) { throw 'Preflight requires 4 GiB free memory.' }
$version = Invoke-RestMethod -Uri "$endpoint/api/version" -TimeoutSec 5
if ($version.version -ne '0.34.1') { throw 'Unexpected runtime version.' }
$loaded = Invoke-RestMethod -Uri "$endpoint/api/ps" -TimeoutSec 5
if (@($loaded.models).Count) { throw 'An existing loaded model must not be disturbed.' }
$models = (Invoke-RestMethod -Uri "$endpoint/api/tags" -TimeoutSec 5).models
$selected = @($models | Where-Object { $_.name -eq $model })
if ($selected.Count -ne 1 -or $selected[0].digest -ne '8f68893c685c3ddff2aa3fffce2aa60a30bb2da65ca488b61fff134a4d1730e7') { throw 'Verified model not present; no automatic download.' }
$timer = [Diagnostics.Stopwatch]::StartNew()
try {
    # Same context/output budget as the app, but an operator-only 90-second deadline.
    # Exercise loading, prompt processing AND decoding, then unload immediately.
    $body = @{model=$model; messages=@(@{role='user';content='Reply exactly READY.'}); stream=$false; think=$false; keep_alive=0; options=@{num_ctx=4096;num_predict=512;temperature=0.2}} | ConvertTo-Json -Depth 5
    $result = Invoke-RestMethod -Uri "$endpoint/api/chat" -Method Post -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 90
    if (-not $result.done -or $result.done_reason -ne 'stop' -or [string]::IsNullOrWhiteSpace($result.message.content)) { throw 'Preflight did not finish normally.' }
    [ordered]@{preflightOnly=$true;applicationEnabled=$false;elapsedMs=$timer.ElapsedMilliseconds;loadMs=[math]::Round($result.load_duration/1e6);requiresQualityReview=$true} | ConvertTo-Json
} finally {
    $unload = @{model=$model;keep_alive=0} | ConvertTo-Json
    Invoke-RestMethod -Uri "$endpoint/api/generate" -Method Post -ContentType 'application/json' -Body $unload -TimeoutSec 10 | Out-Null
    if (@((Invoke-RestMethod -Uri "$endpoint/api/ps" -TimeoutSec 5).models | Where-Object { $_.name -eq $model }).Count) { throw 'Trial model unload not confirmed.' }
}
