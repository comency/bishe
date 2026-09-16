#requires -Version 5.1
<# Explicit isolated HTTP trial. No installation, migration, Docker operations or production enabling. #>
[CmdletBinding()]
param([switch]$ConfirmLocalTrial, [switch]$DatabaseChecked)
$ErrorActionPreference = 'Stop'
if (-not $ConfirmLocalTrial -or -not $DatabaseChecked) { throw 'Pass -ConfirmLocalTrial -DatabaseChecked after inspecting/backing up the dedicated test database.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $projectRoot 'target\lost-found-ai-1.0.0.jar'
$java = 'C:\Program Files\Java\jdk-21\bin\java.exe'
if (-not (Test-Path -LiteralPath $jar -PathType Leaf) -or -not (Test-Path -LiteralPath $java -PathType Leaf)) { throw 'Verified project JAR and JDK21 are required; no automatic build or installation.' }
if (@(Get-NetTCPConnection -LocalPort 18081 -State Listen -ErrorAction SilentlyContinue).Count) { throw 'Port 18081 is occupied; no existing service will be stopped.' }
if ((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory * 1KB -lt 4GB) { throw 'HTTP trial requires at least 4 GiB free before startup; each model call checks again.' }
$overrides = @(Get-ChildItem Env: | Where-Object { $_.Name -like 'SPRING_*' -or $_.Name -in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS') } | Select-Object -ExpandProperty Name)
if ($overrides.Count) { throw ('Remove inherited overrides before starting the trial: ' + ($overrides -join ', ')) }
$db = Import-Clixml -LiteralPath (Join-Path $projectRoot '.local\test-db.credential.xml')
$admin = Import-Clixml -LiteralPath (Join-Path $projectRoot '.local\test-admin.credential.xml')
if ($db -isnot [System.Management.Automation.PSCredential] -or $admin -isnot [System.Management.Automation.PSCredential] -or $db.UserName -ne 'lost_found_test_app' -or $admin.UserName -ne 'admin') { throw 'Dedicated encrypted test credentials required.' }
$settings = @{
    TEST_DB_USERNAME=$db.UserName; TEST_DB_PASSWORD=$db.GetNetworkCredential().Password
    TEST_ADMIN_PASSWORD=$admin.GetNetworkCredential().Password
    TEST_REDIS_USERNAME=''; TEST_REDIS_PASSWORD=''
    TEST_MEDIA_ROOT=(Join-Path $projectRoot '.local\media-test')
    AI_LOCAL_TRIAL_CONFIRMED='true'; AI_BASE_URL='http://127.0.0.1:11434'; AI_MODEL='qwen3:1.7b'
    CAMPUS_ID='TEST_CAMPUS'; CAMPUS_TEST_MODE='true'; MEDIA_CLEANUP_ENABLED='false'
}
$previous = @{}
try {
    foreach ($name in $settings.Keys) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $settings[$name], 'Process')
    }
    Push-Location -LiteralPath $projectRoot
    try {
        Write-Host 'Temporary real-model test API on 127.0.0.1:18081; test DB/Redis only, schema migration disabled. Ctrl+C stops it.'
        & $java '-Xms64m' '-Xmx512m' '-jar' $jar '--spring.profiles.active=integration,modeltrial'
        if ($LASTEXITCODE -ne 0) { throw 'AI integration trial process failed.' }
    } finally { Pop-Location }
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
