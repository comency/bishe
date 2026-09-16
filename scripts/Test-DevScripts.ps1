#requires -Version 5.1
<#
.SYNOPSIS
Read-only checks for local tooling. Does not start containers or connect to SQL.
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:CheckCount = 0
$projectRoot = Split-Path -Parent $PSScriptRoot
$devScript = Join-Path $PSScriptRoot 'dev.ps1'
$composeFile = Join-Path $projectRoot 'infra\compose.local.yml'

function Assert-Check {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "Check failed: $Message" }
    $script:CheckCount++
}

function Get-RedisOption {
    param($Service, [string]$Option)
    $index = [Array]::IndexOf([string[]]$Service.command, $Option)
    if ($index -lt 0 -or $index + 1 -ge $Service.command.Count) { throw "Missing Redis option: $Option" }
    return $Service.command[$index + 1]
}

try {
    foreach ($file in @($devScript, $PSCommandPath, (Join-Path $PSScriptRoot 'Test-IsolatedDatabase.ps1'), (Join-Path $PSScriptRoot 'New-LocalRelease.ps1'))) {
        $tokens = $null
        $parseErrors = $null
        $null = [System.Management.Automation.Language.Parser]::ParseFile($file, [ref]$tokens, [ref]$parseErrors)
        Assert-Check ($parseErrors.Count -eq 0) 'PowerShell 5.1 syntax parses.'
        $source = Get-Content -LiteralPath $file -Raw
        Assert-Check ($source -notmatch '[^\x00-\x7F]') 'Scripts remain ASCII-compatible UTF-8 for Windows PowerShell.'
    }

    $rehearsalDenied=$false
    try { & (Join-Path $PSScriptRoot 'Test-IsolatedDatabase.ps1') } catch { $rehearsalDenied=$_.Exception.Message -match 'ConfirmIsolatedRehearsal' }
    Assert-Check $rehearsalDenied 'Isolated rehearsal refuses missing consent before filesystem/process changes.'
    $candidateDenied=$false
    try { & (Join-Path $PSScriptRoot 'New-LocalRelease.ps1') } catch { $candidateDenied=$_.Exception.Message -match 'CreateCandidate' }
    Assert-Check $candidateDenied 'Candidate builder refuses missing consent before filesystem/build actions.'

    $docker = (Get-Command docker.exe -CommandType Application -ErrorAction Stop).Source
    $json = & $docker --context desktop-linux compose --project-name campus-lost-found-local --file $composeFile --profile integration config --format json
    Assert-Check ($LASTEXITCODE -eq 0) 'Compose validates without contacting Redis or MySQL.'
    $config = ($json -join "`n") | ConvertFrom-Json
    Assert-Check ($config.name -eq 'campus-lost-found-local') 'Compose project is isolated.'
    Assert-Check (@($config.services.PSObject.Properties).Count -eq 2) 'Only the two project Redis services are defined.'

    $dev = $config.services.redis
    $integration = $config.services.'redis-test'
    $expectedImage = 'redis:7-alpine@sha256:ff02b58f971e7d7d156a1267e283fcbbeee91773b6aa36c49dac28ecfe28eadf'
    foreach ($service in @($dev, $integration)) {
        Assert-Check ($service.image -eq $expectedImage) 'Redis image is digest pinned.'
        Assert-Check ($service.restart -eq 'no') 'Automatic container restarts are disabled.'
        Assert-Check ($service.ports.Count -eq 1 -and $service.ports[0].host_ip -eq '127.0.0.1' -and $service.ports[0].target -eq 6379) 'Redis is published only on host loopback.'
        Assert-Check ((Get-RedisOption $service '--maxmemory-policy') -eq 'noeviction') 'Sessions are not evicted under memory pressure.'
        Assert-Check (($service.healthcheck.test -join ' ') -eq 'CMD redis-cli ping') 'Redis healthcheck is present.'
        Assert-Check ((Get-RedisOption $service '--save') -eq '') 'RDB snapshots are explicitly disabled.'
    }
    Assert-Check ($dev.ports[0].published -eq '16379') 'Development Redis port is 16379.'
    Assert-Check ($integration.ports[0].published -eq '16380') 'Integration Redis port is 16380.'
    Assert-Check ([long]$dev.mem_limit -eq 134217728 -and (Get-RedisOption $dev '--maxmemory') -eq '64mb') 'Development memory budget is 128 MiB / 64 MiB.'
    Assert-Check ([long]$integration.mem_limit -eq 100663296 -and (Get-RedisOption $integration '--maxmemory') -eq '32mb') 'Integration memory budget is 96 MiB / 32 MiB.'
    Assert-Check ((Get-RedisOption $dev '--appendonly') -eq 'yes' -and (Get-RedisOption $dev '--appendfsync') -eq 'everysec') 'Development Redis uses AOF.'
    Assert-Check ($dev.volumes.Count -eq 1 -and $dev.volumes[0].source -eq 'redis-dev-data') 'Development Redis has a dedicated volume.'
    Assert-Check ((Get-RedisOption $integration '--appendonly') -eq 'no' -and $null -eq $integration.PSObject.Properties['volumes']) 'Integration Redis has no persistent volume or AOF.'
    Assert-Check ($integration.tmpfs -contains '/data:size=16m') 'Integration /data is ephemeral, including the image volume path.'
    Assert-Check ($integration.profiles -contains 'integration') 'Integration Redis requires its profile.'
    Assert-Check ($null -ne $dev.networks.PSObject.Properties['redis-dev'] -and $null -ne $integration.networks.PSObject.Properties['redis-test']) 'Redis environments use separate project networks.'
    Assert-Check ($config.volumes.'redis-dev-data'.name -eq 'campus-lost-found-local_redis-dev-data') 'Persistent data is project-namespaced.'
    foreach ($network in $config.networks.PSObject.Properties) {
        Assert-Check ($network.Value.name -like 'campus-lost-found-local_*') 'No unrelated Docker network is referenced.'
    }

    $originalJava = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
    $originalPath = [Environment]::GetEnvironmentVariable('PATH', 'Process')
    & $devScript doctor
    Assert-Check ($LASTEXITCODE -eq 0) 'Read-only doctor completes.'
    Assert-Check ([Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process') -ceq $originalJava) 'JAVA_HOME is restored after success.'
    Assert-Check ([Environment]::GetEnvironmentVariable('PATH', 'Process') -ceq $originalPath) 'PATH is restored after success.'
    & $devScript backend
    Assert-Check ($LASTEXITCODE -eq 1) 'Backend refuses to run without DatabaseChecked.'
    Assert-Check ([Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process') -ceq $originalJava) 'JAVA_HOME is unchanged after blocked startup.'
    Write-Host "PASS: $script:CheckCount read-only local environment checks. No services were started and no database was accessed."
    exit 0
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
