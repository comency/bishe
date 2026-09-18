#requires -Version 5.1
<# Restores a verified backup only into a pre-created, empty restore_* schema and a new media directory. #>
[CmdletBinding()]
param(
    [switch]$RestoreBackup,
    [switch]$ConfirmIsolatedTarget,
    [Parameter()][string]$BackupDirectory,
    [Parameter()][string]$DestinationMediaRoot,
    [Parameter()][string]$MySqlBin = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $RestoreBackup -or -not $ConfirmIsolatedTarget) {
    throw 'Pass -RestoreBackup -ConfirmIsolatedTarget only for a pre-created, empty restore_* schema.'
}
foreach ($path in @($BackupDirectory, $DestinationMediaRoot)) {
    if ([string]::IsNullOrWhiteSpace($path) -or -not [IO.Path]::IsPathRooted($path)) {
        throw 'BackupDirectory and DestinationMediaRoot must be absolute paths.'
    }
}
$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
$backupRoot = [IO.Path]::GetFullPath($BackupDirectory).TrimEnd('\')
$mediaTarget = [IO.Path]::GetFullPath($DestinationMediaRoot).TrimEnd('\')
if (-not (Test-Path -LiteralPath $backupRoot -PathType Container)) { throw 'Backup directory does not exist.' }
if (Test-Path -LiteralPath $mediaTarget) { throw 'Destination media directory must not exist; nothing was changed.' }
if ($mediaTarget -eq $projectRoot -or $mediaTarget.StartsWith($projectRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
    $mediaTarget -eq $backupRoot -or $mediaTarget.StartsWith($backupRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
    $backupRoot.StartsWith($mediaTarget + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Restore media destination must be outside the project and backup trees.'
}
$reparse = [IO.FileAttributes]::ReparsePoint
$cursor = Split-Path -Parent $mediaTarget
while (-not [string]::IsNullOrWhiteSpace($cursor)) {
    if (Test-Path -LiteralPath $cursor) {
        if (((Get-Item -LiteralPath $cursor -Force).Attributes -band $reparse) -ne 0) {
            throw 'Restore media destination must not traverse a reparse point or link.'
        }
    }
    $parent = Split-Path -Parent $cursor
    if ($parent -eq $cursor) { break }
    $cursor = $parent
}

$required = @('RESTORE_DB_HOST','RESTORE_DB_PORT','RESTORE_DB_NAME','RESTORE_DB_USERNAME','RESTORE_DB_PASSWORD')
$missing = @($required | Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_, 'Process')) })
if ($missing.Count) { throw ('Missing required process environment names: ' + ($missing -join ', ')) }
$hostName = [Environment]::GetEnvironmentVariable('RESTORE_DB_HOST', 'Process')
$portText = [Environment]::GetEnvironmentVariable('RESTORE_DB_PORT', 'Process')
$database = [Environment]::GetEnvironmentVariable('RESTORE_DB_NAME', 'Process')
$username = [Environment]::GetEnvironmentVariable('RESTORE_DB_USERNAME', 'Process')
$password = [Environment]::GetEnvironmentVariable('RESTORE_DB_PASSWORD', 'Process')
if ($hostName -notmatch '^[A-Za-z0-9.:-]+$' -or $database -notmatch '^restore_[A-Za-z0-9_]+$' -or $username -notmatch '^[A-Za-z0-9_]+$') {
    throw 'Restore host/username is invalid or target schema does not begin with restore_.'
}
$port = 0
if (-not [int]::TryParse($portText, [ref]$port) -or $port -lt 1 -or $port -gt 65535) { throw 'RESTORE_DB_PORT must be 1-65535.' }
if ([Environment]::GetEnvironmentVariable('DB_HOST', 'Process') -eq $hostName -and
    [Environment]::GetEnvironmentVariable('DB_PORT', 'Process') -eq $portText -and
    [Environment]::GetEnvironmentVariable('DB_NAME', 'Process') -eq $database) {
    throw 'Restore target must not equal the configured source database.'
}

$clientExe = if ([string]::IsNullOrWhiteSpace($MySqlBin)) {
    $command = Get-Command mysql.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($null -eq $command) { throw 'mysql.exe was not found on PATH; pass its bin directory with -MySqlBin.' }
    $command.Source
} else { Join-Path ([IO.Path]::GetFullPath($MySqlBin)) 'mysql.exe' }
if (-not (Test-Path -LiteralPath $clientExe -PathType Leaf)) { throw 'Existing mysql.exe is required; nothing is installed automatically.' }

& node.exe (Join-Path $PSScriptRoot 'check-backup.mjs') $backupRoot
if ($LASTEXITCODE -ne 0) { throw 'Backup integrity validation failed; target database was not contacted.' }
$dumpFile = Join-Path $backupRoot 'database.sql'
$forbiddenSql = '^\s*(CREATE|DROP)\s+DATABASE\b|^\s*USE\s+|^\s*DROP\s+TABLE\b|\bINTO\s+(OUTFILE|DUMPFILE)\b'
if (Select-String -LiteralPath $dumpFile -Pattern $forbiddenSql -Quiet) {
    throw 'Backup SQL contains a database switch, destructive DDL or server-side file output; target database was not contacted.'
}

function Invoke-MySqlInput([string]$InputFile, [string]$Sql, [int]$TimeoutMilliseconds) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $clientExe
    $info.Arguments = '--no-defaults --protocol=TCP --host=' + $hostName + ' --port=' + $port + ' --user=' + $username +
        ' --connect-timeout=5 --batch --skip-column-names --default-character-set=utf8mb4 ' + $database
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true; $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    $info.EnvironmentVariables['MYSQL_PWD'] = $password
    $process = New-Object Diagnostics.Process; $process.StartInfo = $info
    $stream = $null
    $started = $false
    try {
        [void]$process.Start(); $started = $true
        $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        $timer = [Diagnostics.Stopwatch]::StartNew()
        if ($InputFile) {
            $stream = [IO.File]::OpenRead($InputFile)
            $copy = $stream.CopyToAsync($process.StandardInput.BaseStream)
            try { $copyComplete = $copy.Wait($TimeoutMilliseconds) }
            catch { throw 'Restore database input failed; private details were not printed and partial target is retained.' }
            if (-not $copyComplete) { $process.Kill(); throw 'Restore database input timed out; partial target is retained.' }
            $process.StandardInput.Close()
        } else { $process.StandardInput.WriteLine($Sql); $process.StandardInput.Close() }
        $remaining = [Math]::Max(1, $TimeoutMilliseconds - [int]$timer.ElapsedMilliseconds)
        if (-not $process.WaitForExit($remaining)) { $process.Kill(); throw 'Restore database command timed out; partial target is retained.' }
        $answer = $stdout.GetAwaiter().GetResult().Trim(); [void]$stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw 'Restore database command failed; private details were not printed and partial target is retained.' }
        return $answer
    } catch {
        if ($started -and -not $process.HasExited) { $process.Kill() }
        throw
    } finally { if ($null -ne $stream) { $stream.Dispose() }; $process.Dispose() }
}

$tableCount = Invoke-MySqlInput '' 'SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE();' 15000
if ($tableCount -notmatch '^0$') { throw 'Restore schema is not empty or could not be proven empty; nothing was imported.' }
[void](Invoke-MySqlInput $dumpFile '' 300000)

[void](New-Item -ItemType Directory -Path $mediaTarget)
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = New-Object Security.AccessControl.DirectorySecurity
$acl.SetOwner($identity); $acl.SetAccessRuleProtection($true, $false)
$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
Set-Acl -LiteralPath $mediaTarget -AclObject $acl
$sourceMedia = Join-Path $backupRoot 'media'
foreach ($file in @(Get-ChildItem -LiteralPath $sourceMedia -Recurse -File -Force | Sort-Object FullName)) {
    $relative = $file.FullName.Substring($sourceMedia.Length).TrimStart('\')
    $target = Join-Path $mediaTarget $relative
    $parent = Split-Path -Parent $target
    if (-not (Test-Path -LiteralPath $parent)) { [void](New-Item -ItemType Directory -Path $parent) }
    Copy-Item -LiteralPath $file.FullName -Destination $target
    if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash) {
        throw 'Restored media hash mismatch; partial isolated target is retained.'
    }
}
Write-Output ([ordered]@{status='PASS';database=$database;media=$mediaTarget;isolated=$true;businessSmokeRequired=$true} | ConvertTo-Json -Compress)
