#requires -Version 5.1
<#
.SYNOPSIS
Local development commands. No database creation or password resets.
.EXAMPLE
& .\scripts\dev.ps1 doctor
.EXAMPLE
& .\scripts\dev.ps1 verify -JavaHome 'C:\Program Files\Java\jdk-21'
.EXAMPLE
& .\scripts\dev.ps1 backend -DatabaseChecked -UseUserEnvironment
.EXAMPLE
& .\scripts\dev.ps1 backend -Profile integration -DatabaseChecked
.EXAMPLE
& .\scripts\dev.ps1 backend -DatabaseChecked -UseLocalCredentials
.NOTES
Run as a script, not by dot-sourcing. Secrets are never accepted as CLI arguments.
UseUserEnvironment imports only explicitly listed credential names, and only
when the current process does not already provide them. Nothing is persisted.
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('doctor', 'verify', 'redis-up', 'redis-test-up', 'backend', 'frontend', 'redis-stop')]
    [string]$Action = 'doctor',
    [string]$JavaHome,
    [Alias('Profile')]
    [ValidateSet('dev', 'integration')]
    [string]$EnvironmentName = 'dev',
    [switch]$DatabaseChecked,
    [switch]$UseUserEnvironment,
    [switch]$UseLocalCredentials
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:SavedEnvironment = @{}
$script:ProjectRoot = Split-Path -Parent $PSScriptRoot
$script:ComposeFile = Join-Path $script:ProjectRoot 'infra\compose.local.yml'
$script:ComposeProject = 'campus-lost-found-local'
$script:ExitCode = 0

function Set-ScopedEnvironment {
    param([string]$Name, [AllowNull()][string]$Value)
    if (-not $script:SavedEnvironment.ContainsKey($Name)) {
        $script:SavedEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process')
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}

function Get-ToolPath {
    param([string]$Name)
    $tool = Get-Command -Name $Name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $tool) { throw "Required tool is not on PATH: $Name" }
    return $tool.Source
}

function Invoke-CheckedTool {
    param([string]$Tool, [string[]]$Arguments)
    & $Tool @Arguments
    $result = $LASTEXITCODE
    if ($result -ne 0) {
        $script:ExitCode = $result
        throw "Command failed with exit code $result."
    }
}

function Select-Java21 {
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
        $candidates = @($JavaHome)
    } else {
        $candidates = @('C:\Program Files\Java\jdk-21')
        if (-not [string]::IsNullOrWhiteSpace($env:USERPROFILE)) {
            $candidates += Join-Path $env:USERPROFILE '.jdks\ms-21.0.7'
        }
    }
    foreach ($candidate in $candidates) {
        $javaExecutable = Join-Path $candidate 'bin\java.exe'
        $javacExecutable = Join-Path $candidate 'bin\javac.exe'
        if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf) -or
            -not (Test-Path -LiteralPath $javacExecutable -PathType Leaf)) { continue }
        # Java writes its version to stderr; Windows PowerShell wraps it as
        # NativeCommandError even for a successful command.
        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $versionLines = & $javaExecutable -version 2>&1
            $versionExitCode = $LASTEXITCODE
            $versionText = ($versionLines | ForEach-Object { $_.ToString() }) -join "`n"
        } finally {
            $ErrorActionPreference = $previousPreference
        }
        if ($versionExitCode -eq 0 -and $versionText -match 'version "21(?:\.|\")') {
            $resolvedJava = (Resolve-Path -LiteralPath $candidate).Path
            Set-ScopedEnvironment -Name 'JAVA_HOME' -Value $resolvedJava
            Set-ScopedEnvironment -Name 'PATH' -Value ((Join-Path $resolvedJava 'bin') + ';' + $env:PATH)
            Write-Host "Using Java 21: $resolvedJava"
            return
        }
    }
    throw 'JDK 21 was not found or the selected JDK is not version 21. Pass -JavaHome with a JDK 21 directory.'
}

function Import-BackendCredentials {
    if ($UseLocalCredentials -and $UseUserEnvironment) {
        throw 'Choose either -UseLocalCredentials or -UseUserEnvironment, not both.'
    }
    if ($UseLocalCredentials) {
        $prefix = if ($EnvironmentName -eq 'integration') { 'test' } else { 'dev' }
        $dbUserVariable = if ($EnvironmentName -eq 'integration') { 'TEST_DB_USERNAME' } else { 'DB_USERNAME' }
        $dbPasswordVariable = if ($EnvironmentName -eq 'integration') { 'TEST_DB_PASSWORD' } else { 'DB_PASSWORD' }
        $adminPasswordVariable = if ($EnvironmentName -eq 'integration') { 'TEST_ADMIN_PASSWORD' } else { 'ADMIN_PASSWORD' }
        $dbCredentialFile = Join-Path $script:ProjectRoot ".local\$prefix-db.credential.xml"
        $adminCredentialFile = Join-Path $script:ProjectRoot ".local\$prefix-admin.credential.xml"
        foreach ($credentialFile in @($dbCredentialFile, $adminCredentialFile)) {
            if (-not (Test-Path -LiteralPath $credentialFile -PathType Leaf)) {
                throw 'The requested local encrypted credential file is missing. Complete local database setup first.'
            }
        }
        try {
            $dbCredential = Import-Clixml -LiteralPath $dbCredentialFile
            $adminCredential = Import-Clixml -LiteralPath $adminCredentialFile
            if ($dbCredential -isnot [System.Management.Automation.PSCredential] -or
                $adminCredential -isnot [System.Management.Automation.PSCredential] -or
                $adminCredential.UserName -ne 'admin') {
                throw 'Invalid credential data.'
            }
            $values = @{}
            $values[$dbUserVariable] = $dbCredential.UserName
            $values[$dbPasswordVariable] = $dbCredential.GetNetworkCredential().Password
            $values[$adminPasswordVariable] = $adminCredential.GetNetworkCredential().Password
        } catch {
            throw 'Local credentials could not be decrypted or have an invalid format. Use the same Windows user that created them.'
        }
        Write-Host 'Using encrypted project credentials for this process; previous values will be restored.'
        foreach ($name in $values.Keys) { Set-ScopedEnvironment -Name $name -Value $values[$name] }
        return
    }
    if (-not $UseUserEnvironment) { return }
    $names = if ($EnvironmentName -eq 'integration') {
        @('TEST_DB_USERNAME', 'TEST_DB_PASSWORD', 'TEST_ADMIN_PASSWORD')
    } else {
        @('DB_USERNAME', 'DB_PASSWORD', 'ADMIN_PASSWORD')
    }
    foreach ($name in $names) {
        $current = [Environment]::GetEnvironmentVariable($name, 'Process')
        if ([string]::IsNullOrEmpty($current)) {
            $configured = [Environment]::GetEnvironmentVariable($name, 'User')
            if (-not [string]::IsNullOrEmpty($configured)) {
                Set-ScopedEnvironment -Name $name -Value $configured
            }
        }
    }
}

function Assert-BackendConfiguration {
    if (-not $DatabaseChecked) {
        throw 'Backend not started. Check the exact MySQL instance, database contents, backup and account privileges first; then pass -DatabaseChecked.'
    }
    # Reject alternate Spring property sources instead of silently accepting
    # inherited settings from another project. Print names only, never values.
    $overrides = @(Get-ChildItem Env: | Where-Object { $_.Name -like 'SPRING_*' } | Select-Object -ExpandProperty Name)
    if ($overrides.Count -gt 0) {
        throw ('Backend not started. Remove inherited Spring overrides from this terminal first: ' + ($overrides -join ', '))
    }
    $expected = @{
        DB_HOST = '127.0.0.1'; DB_PORT = '13306'; DB_NAME = 'lost_found'
        REDIS_HOST = '127.0.0.1'; REDIS_PORT = '16379'; REDIS_DATABASE = '0'
        SERVER_ADDRESS = '127.0.0.1'; SERVER_PORT = '8080'
    }
    if ($EnvironmentName -eq 'dev') {
        foreach ($name in $expected.Keys) {
            $actual = [Environment]::GetEnvironmentVariable($name, 'Process')
            if (-not [string]::IsNullOrEmpty($actual) -and $actual -ne $expected[$name]) {
                throw "Backend not started. Unexpected local endpoint override: $name."
            }
        }
    }
    Import-BackendCredentials
    $required = if ($EnvironmentName -eq 'integration') { @('TEST_DB_USERNAME', 'TEST_DB_PASSWORD') } else { @('DB_PASSWORD') }
    foreach ($name in $required) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            throw "Backend not started. Set $name in this terminal, or configure that user environment variable and pass -UseUserEnvironment."
        }
    }
}

function Get-LocalDocker {
    $docker = Get-ToolPath 'docker.exe'
    $contextJson = & $docker context inspect desktop-linux --format '{{json .Endpoints.docker.Host}}'
    if ($LASTEXITCODE -ne 0) { throw 'The local Docker Desktop context desktop-linux is unavailable.' }
    $endpoint = ($contextJson -join '') | ConvertFrom-Json
    if ($endpoint -notlike 'npipe:////./pipe/*') {
        throw 'Refusing to use a Docker endpoint outside this Windows machine.'
    }
    return $docker
}

function Invoke-LocalCompose {
    param([string[]]$ComposeArguments)
    $docker = Get-LocalDocker
    $arguments = @('--context', 'desktop-linux', 'compose', '--project-name', $script:ComposeProject,
        '--project-directory', $script:ProjectRoot, '--file', $script:ComposeFile, '--profile', 'integration')
    Invoke-CheckedTool -Tool $docker -Arguments ($arguments + $ComposeArguments)
}

function Assert-RedisPort {
    param([int]$Port, [string]$Service)
    $listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
    if ($listeners.Count -eq 0) { return }
    $docker = Get-LocalDocker
    $arguments = @('--context', 'desktop-linux', 'ps', '--quiet',
        '--filter', "label=com.docker.compose.project=$script:ComposeProject",
        '--filter', "label=com.docker.compose.service=$Service", '--filter', "publish=$Port")
    $owned = @(& $docker @arguments)
    if ($LASTEXITCODE -ne 0 -or $owned.Count -eq 0) {
        throw "Port $Port is already in use by another service. Nothing was stopped or changed."
    }
}

try {
    if ($MyInvocation.InvocationName -eq '.') { throw 'Do not dot-source this script. Invoke it with & or powershell -File.' }
    if (-not (Test-Path -LiteralPath (Join-Path $script:ProjectRoot 'pom.xml') -PathType Leaf)) {
        throw 'Project root could not be resolved from this script location.'
    }
    Push-Location -LiteralPath $script:ProjectRoot
    try {
        switch ($Action) {
            'doctor' {
                Select-Java21
                Invoke-CheckedTool -Tool (Get-ToolPath 'mvn.cmd') -Arguments @('-version')
                Invoke-CheckedTool -Tool (Get-ToolPath 'node.exe') -Arguments @('--version')
                Invoke-CheckedTool -Tool (Get-ToolPath 'npm.cmd') -Arguments @('--version')
                Invoke-LocalCompose -ComposeArguments @('version')
                foreach ($port in @(8080, 5174, 13306, 16379, 18080, 16380)) {
                    $listeners = @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)
                    $state = if ($listeners.Count -gt 0) { 'LISTENING' } else { 'NOT LISTENING' }
                    Write-Host ("Port {0}: {1}" -f $port, $state)
                }
                Write-Host 'Doctor is read-only. A listening port does not verify database identity, credentials or schema.'
            }
            'verify' {
                Select-Java21
                Invoke-CheckedTool -Tool (Get-ToolPath 'mvn.cmd') -Arguments @('verify')
            }
            'redis-up' {
                Assert-RedisPort -Port 16379 -Service 'redis'
                Invoke-LocalCompose -ComposeArguments @('up', '--detach', '--wait', '--wait-timeout', '45', '--no-deps', 'redis')
            }
            'redis-test-up' {
                Assert-RedisPort -Port 16380 -Service 'redis-test'
                Invoke-LocalCompose -ComposeArguments @('up', '--detach', '--wait', '--wait-timeout', '45', '--no-deps', 'redis-test')
            }
            'redis-stop' {
                Invoke-LocalCompose -ComposeArguments @('stop', 'redis', 'redis-test')
                Write-Host 'Only this project Redis containers were stopped; no containers or volumes were deleted.'
            }
            'backend' {
                Assert-BackendConfiguration
                Select-Java21
                Write-Host "Starting local backend profile '$EnvironmentName' in the foreground. Press Ctrl+C to stop."
                Invoke-CheckedTool -Tool (Get-ToolPath 'mvn.cmd') -Arguments @('spring-boot:run', "-Dspring-boot.run.profiles=$EnvironmentName")
            }
            'frontend' {
                $frontend = Join-Path $script:ProjectRoot 'frontend'
                if (-not (Test-Path -LiteralPath (Join-Path $frontend 'package.json') -PathType Leaf)) {
                    throw 'frontend/package.json is missing.'
                }
                Push-Location -LiteralPath $frontend
                try { Invoke-CheckedTool -Tool (Get-ToolPath 'npm.cmd') -Arguments @('run', 'dev') }
                finally { Pop-Location }
            }
        }
    } finally {
        Pop-Location
    }
} catch {
    if ($script:ExitCode -eq 0) { $script:ExitCode = 1 }
    [Console]::Error.WriteLine($_.Exception.Message)
} finally {
    foreach ($name in $script:SavedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $script:SavedEnvironment[$name], 'Process')
    }
}
exit $script:ExitCode
