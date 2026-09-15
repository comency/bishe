param(
    [switch]$InitializeEmpty,
    [string]$MySqlClient = 'E:\MySQL\MySQL Server 8.0\bin\mysql.exe'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $InitializeEmpty) { throw 'Explicit -InitializeEmpty is required. Existing schemas/accounts are never changed.' }
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskAdminUser = [Environment]::GetEnvironmentVariable('DB_USERNAME', 'User')
$taskAdminPassword = [Environment]::GetEnvironmentVariable('DB_PASSWORD', 'User')
if (-not $taskAdminUser -or -not $taskAdminPassword) { throw 'Set DB_USERNAME/DB_PASSWORD in Windows User environment first.' }
if ($taskAdminUser -notmatch '^[a-zA-Z0-9_]+$') { throw 'Use a simple local administrative username for this bootstrap helper.' }
if (-not (Test-Path -LiteralPath $MySqlClient)) { throw 'MySQL client not found.' }

function Invoke-LocalSql([string]$Sql) {
    $taskInfo = New-Object System.Diagnostics.ProcessStartInfo
    $taskInfo.FileName = $MySqlClient
    $taskInfo.Arguments = "--no-defaults --protocol=TCP --host=127.0.0.1 --port=13306 --user=$taskAdminUser --connect-timeout=5 --batch --skip-column-names --default-character-set=utf8mb4"
    $taskInfo.UseShellExecute = $false
    $taskInfo.CreateNoWindow = $true
    $taskInfo.RedirectStandardInput = $true
    $taskInfo.RedirectStandardOutput = $true
    $taskInfo.RedirectStandardError = $true
    $taskInfo.EnvironmentVariables['MYSQL_PWD'] = $taskAdminPassword
    $taskProcess = New-Object System.Diagnostics.Process
    $taskProcess.StartInfo = $taskInfo
    try {
        [void]$taskProcess.Start()
        $taskOut = $taskProcess.StandardOutput.ReadToEndAsync()
        $taskErr = $taskProcess.StandardError.ReadToEndAsync()
        $taskProcess.StandardInput.WriteLine($Sql)
        $taskProcess.StandardInput.Close()
        if (-not $taskProcess.WaitForExit(20000)) { $taskProcess.Kill(); throw 'MySQL operation timed out.' }
        $taskOutput = $taskOut.GetAwaiter().GetResult()
        [void]$taskErr.GetAwaiter().GetResult()
        if ($taskProcess.ExitCode -ne 0) { throw 'MySQL operation failed. Check local credentials/privileges; no SQL or password is printed.' }
        return $taskOutput.Trim()
    } finally { $taskProcess.Dispose() }
}

$taskPort = Invoke-LocalSql 'SELECT @@port;'
if ($taskPort -ne '13306') { throw 'Unexpected database instance.' }
# mysql.user requires administrative inspection rights; no password columns are read.
$taskExisting = Invoke-LocalSql "SELECT (SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME IN ('lost_found','lost_found_test')) + (SELECT COUNT(*) FROM mysql.user WHERE User IN ('lost_found_app','lost_found_test_app'));"
if ($taskExisting -ne '0') { throw 'A target schema or account already exists. Stop and inspect/back up; this helper never resets or adopts existing data.' }
$taskPartialRevokes = Invoke-LocalSql 'SELECT @@partial_revokes;'
$taskLocal = Join-Path $taskRoot '.local'
$taskCredentialNames = @('dev-db','test-db','dev-admin','test-admin')
foreach ($taskCredentialName in $taskCredentialNames) {
    if (Test-Path -LiteralPath (Join-Path $taskLocal "$taskCredentialName.credential.xml")) { throw 'Saved local credentials already exist. Do not overwrite them.' }
}
[void](New-Item -ItemType Directory -Path $taskLocal -Force)
$taskIdentity = [System.Security.Principal.WindowsIdentity]::GetCurrent().User
$taskAcl = New-Object System.Security.AccessControl.DirectorySecurity
$taskAcl.SetOwner($taskIdentity)
$taskAcl.SetAccessRuleProtection($true, $false)
$taskRule = New-Object System.Security.AccessControl.FileSystemAccessRule($taskIdentity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
$taskAcl.AddAccessRule($taskRule)
Set-Acl -LiteralPath $taskLocal -AclObject $taskAcl

$taskCredentials = @{}
foreach ($taskCredentialName in $taskCredentialNames) {
    $taskBytes = New-Object byte[] 24
    $taskRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $taskRng.GetBytes($taskBytes) } finally { $taskRng.Dispose() }
    $taskSecret = [BitConverter]::ToString($taskBytes).Replace('-', '').ToLowerInvariant()
    $taskUser = switch ($taskCredentialName) { 'dev-db' { 'lost_found_app' } 'test-db' { 'lost_found_test_app' } default { 'admin' } }
    $taskCredential = New-Object System.Management.Automation.PSCredential($taskUser, (ConvertTo-SecureString $taskSecret -AsPlainText -Force))
    $taskCredentials[$taskCredentialName] = $taskCredential
    # Windows DPAPI: decryptable only by this Windows user on this machine.
    $taskCredential | Export-Clixml -LiteralPath (Join-Path $taskLocal "$taskCredentialName.credential.xml")
}
$taskDevPassword = $taskCredentials['dev-db'].GetNetworkCredential().Password
$taskTestPassword = $taskCredentials['test-db'].GetNetworkCredential().Password
# MySQL database grants treat '_' as a wildcard unless partial_revokes is enabled.
$taskDevGrant = if ($taskPartialRevokes -eq '0') { 'lost\_found' } else { 'lost_found' }
$taskTestGrant = if ($taskPartialRevokes -eq '0') { 'lost\_found\_test' } else { 'lost_found_test' }
$taskSql = @'
CREATE DATABASE lost_found CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE lost_found_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'lost_found_app'@'localhost' IDENTIFIED BY '{0}';
CREATE USER 'lost_found_test_app'@'localhost' IDENTIFIED BY '{1}';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON `{2}`.* TO 'lost_found_app'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON `{3}`.* TO 'lost_found_test_app'@'localhost';
'@ -f $taskDevPassword, $taskTestPassword, $taskDevGrant, $taskTestGrant
try { [void](Invoke-LocalSql $taskSql) }
catch {
    throw 'Bootstrap did not finish. DDL is not atomic: some schemas/accounts may already exist. Keep .local credentials, inspect the local instance, and do not delete/retry blindly.'
}
Write-Output 'Created only lost_found/lost_found_test and their dedicated localhost accounts on port 13306.'
Write-Output 'Local database/admin credentials were saved with Windows DPAPI under ignored .local/. No existing passwords were changed.'
Write-Output 'Application startup will apply V1 to these empty schemas; no business rows were created by this helper.'
