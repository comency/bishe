#requires -Version 5.1
<# Read-only snapshot. Does not start services, install software, download models or print secrets. #>
$ErrorActionPreference='Stop'
$systemSnapshot=Get-CimInstance Win32_OperatingSystem
$disks=Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='C:' OR DeviceID='E:'"
$command=Get-Command ollama -ErrorAction SilentlyContinue
$gpuCommand=Get-Command nvidia-smi -ErrorAction SilentlyContinue
$gpu=if($gpuCommand){@(& $gpuCommand.Source --query-gpu=name,memory.total,memory.free --format=csv,noheader)}else{@()}
$listeners=@(Get-NetTCPConnection -LocalPort 11434 -State Listen -ErrorAction SilentlyContinue)
[ordered]@{
    capturedAt=(Get-Date).ToString('o')
    totalMemoryGiB=[math]::Round($systemSnapshot.TotalVisibleMemorySize/1MB,2)
    freeMemoryGiB=[math]::Round($systemSnapshot.FreePhysicalMemory/1MB,2)
    disks=@($disks | ForEach-Object { @{drive=$_.DeviceID;freeGiB=[math]::Round($_.FreeSpace/1GB,2)} })
    gpuSnapshot=$gpu
    ollamaOnPath=($null -ne $command)
    ollamaCommonInstallPresent=(Test-Path -LiteralPath (Join-Path $env:LOCALAPPDATA 'Programs/Ollama/ollama.exe'))
    port11434Listening=($listeners.Count -gt 0)
    modelEffectVerified=$false
    note='One-time resource snapshot only; does not prove inference capacity, installation absence or model quality.'
} | ConvertTo-Json -Depth 5
