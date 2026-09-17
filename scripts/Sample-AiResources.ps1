#requires -Version 5.1
<# Bounded read-only sampler. JSON lines to stdout, no credentials or process command lines. #>
param(
    [ValidateRange(1, 900)][int]$Seconds = 300,
    [ValidatePattern('^[a-z][a-z0-9-]{0,31}$')][string]$Stage = 'unspecified',
    [ValidateCount(0,16)][ValidateRange(1,2147483647)][int[]]$TrackedProcessId = @(),
    [string]$StopFile = ''
)
$ErrorActionPreference = 'Stop'
if ($StopFile -and (Test-Path -LiteralPath $StopFile)) { throw 'Sampler stop signal already exists; use a fresh run path.' }
$targets = @($TrackedProcessId | Select-Object -Unique | ForEach-Object {
    $target = Get-Process -Id $_ -ErrorAction Stop
    [pscustomobject]@{id=$target.Id;name=$target.ProcessName;startedAt=$target.StartTime.ToUniversalTime().ToString('o');ticks=$target.StartTime.ToUniversalTime().Ticks}
})
$deadline = (Get-Date).AddSeconds($Seconds)
while ((Get-Date) -lt $deadline) {
    if ($StopFile -and (Test-Path -LiteralPath $StopFile)) { break }
    $system = Get-CimInstance Win32_OperatingSystem
    $gpu = & nvidia-smi --query-gpu=memory.used,memory.free --format=csv,noheader,nounits
    if ($LASTEXITCODE -ne 0) { throw 'GPU sampling failed.' }
    $values = $gpu[0] # Normalize a single GPU string below.
    if ($gpu -is [string]) { $values = $gpu }
    $values = $values -split ','
    $processSamples = @($targets | ForEach-Object {
        $target = $_; $current = Get-Process -Id $target.id -ErrorAction SilentlyContinue
        if (-not $current) {
            [ordered]@{id=$target.id;name=$target.name;startedAt=$target.startedAt;state='exited'}
        } else {
            try {
                if ($current.StartTime.ToUniversalTime().Ticks -ne $target.ticks) {
                    [ordered]@{id=$target.id;name=$target.name;startedAt=$target.startedAt;state='pid-reused'}
                } else {
                    [ordered]@{id=$target.id;name=$target.name;startedAt=$target.startedAt;state='running';
                        workingSetMiB=[math]::Round($current.WorkingSet64/1MB,1);
                        privateMemoryMiB=[math]::Round($current.PrivateMemorySize64/1MB,1);
                        cpuSeconds=[math]::Round($current.TotalProcessorTime.TotalSeconds,3)}
                }
            } catch {
                [ordered]@{id=$target.id;name=$target.name;startedAt=$target.startedAt;state='unavailable'}
            }
        }
    })
    [ordered]@{
        at = (Get-Date).ToString('o')
        stage = $Stage
        freeMemoryMiB = [math]::Round($system.FreePhysicalMemory / 1KB, 1)
        gpuUsedMiB = [int]$values[0]; gpuFreeMiB = [int]$values[1]
        ollamaWorkingSetMiB = [math]::Round((Get-Process -Name ollama -ErrorAction SilentlyContinue | Measure-Object -Property WorkingSet64 -Sum).Sum / 1MB, 1)
        processes = $processSamples
    } | ConvertTo-Json -Depth 5 -Compress
    Start-Sleep -Milliseconds 1000
}
