#requires -Version 5.1
<# Explicit foreground trial only. No installation, download, global settings or Docker changes. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ResourceRoot,
    [switch]$ConfirmLocalTrial
)
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLocalTrial) { throw 'Pass -ConfirmLocalTrial to start the local-only trial.' }
$root = (Resolve-Path -LiteralPath $ResourceRoot).Path
$executable = Join-Path $root 'runtime-v0.34.1\ollama.exe'
if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) { throw 'Verified portable Ollama v0.34.1 is required. This script does not install it.' }
if (@(Get-NetTCPConnection -LocalPort 11434 -State Listen -ErrorAction SilentlyContinue).Count) { throw 'Port 11434 is occupied; no existing service will be stopped.' }
# Starting the metadata/download server does not load a model. Inference admission
# is checked separately by the application and check-ai-model.mjs before each call.
if ((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory * 1KB -lt 4GB) {
    Write-Warning 'Below 4 GiB free memory: metadata/download only until the inference resource check passes.'
}
$settings = @{
    OLLAMA_HOST = '127.0.0.1:11434'; OLLAMA_MODELS = (Join-Path $root 'models')
    OLLAMA_NO_CLOUD = '1'; OLLAMA_NUM_PARALLEL = '1'; OLLAMA_MAX_LOADED_MODELS = '1'
    OLLAMA_MAX_QUEUE = '1'; OLLAMA_CONTEXT_LENGTH = '4096'; OLLAMA_KEEP_ALIVE = '0'
    OLLAMA_DEBUG = 'false'; USERPROFILE = (Join-Path $root 'state')
    TEMP = (Join-Path $root 'temp'); TMP = (Join-Path $root 'temp')
}
$previous = @{}
try {
    foreach ($directory in @($settings.OLLAMA_MODELS, $settings.USERPROFILE, $settings.TEMP)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
    foreach ($name in $settings.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
    }
    Write-Host 'Starting local-only Ollama trial on 127.0.0.1:11434. Ctrl+C stops this foreground server.'
    & $executable serve
    if ($LASTEXITCODE -ne 0) { throw "Ollama exited with code $LASTEXITCODE." }
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
