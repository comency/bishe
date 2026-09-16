#requires -Version 5.1
<# Bounded read-only sampler. JSON lines to stdout, no credentials or process command lines. #>
param([ValidateRange(1, 900)][int]$Seconds = 300)
$ErrorActionPreference = 'Stop'
$deadline = (Get-Date).AddSeconds($Seconds)
while ((Get-Date) -lt $deadline) {
    $system = Get-CimInstance Win32_OperatingSystem
    $gpu = & nvidia-smi --query-gpu=memory.used,memory.free --format=csv,noheader,nounits
    if ($LASTEXITCODE -ne 0) { throw 'GPU sampling failed.' }
    $values = $gpu[0] # Normalize a single GPU string below.
    if ($gpu -is [string]) { $values = $gpu }
    $values = $values -split ','
    [ordered]@{
        at = (Get-Date).ToString('o')
        freeMemoryMiB = [math]::Round($system.FreePhysicalMemory / 1KB, 1)
        gpuUsedMiB = [int]$values[0]; gpuFreeMiB = [int]$values[1]
        ollamaWorkingSetMiB = [math]::Round((Get-Process -Name ollama -ErrorAction SilentlyContinue | Measure-Object -Property WorkingSet64 -Sum).Sum / 1MB, 1)
    } | ConvertTo-Json -Compress
    Start-Sleep -Milliseconds 1000
}
