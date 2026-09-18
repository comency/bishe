#requires -Version 5.1
<# Creates one database/media backup while application writes are already quiesced. Never stops services or overwrites output. #>
[CmdletBinding()]
param(
    [switch]$CreateBackup,
    [switch]$ConfirmWritesQuiesced,
    [Parameter()][string]$Destination,
    [Parameter()][string]$MySqlBin = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $CreateBackup -or -not $ConfirmWritesQuiesced) {
    throw 'Pass -CreateBackup -ConfirmWritesQuiesced only after inbound traffic and background writers are stopped.'
}
if ([string]::IsNullOrWhiteSpace($Destination) -or -not [IO.Path]::IsPathRooted($Destination)) {
    throw 'Destination must be a new absolute directory outside the project and media tree.'
}

$projectRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
$destinationRoot = [IO.Path]::GetFullPath($Destination).TrimEnd('\')
if ($destinationRoot -eq $projectRoot -or $destinationRoot.StartsWith($projectRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Backup destination must be outside the project tree.'
}
if (Test-Path -LiteralPath $destinationRoot) { throw 'Backup destination already exists; no files were changed.' }

$required = @('DB_HOST','DB_PORT','DB_NAME','DB_USERNAME','DB_PASSWORD','MEDIA_ROOT')
$missing = @($required | Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_, 'Process')) })
if ($missing.Count) { throw ('Missing required process environment names: ' + ($missing -join ', ')) }
$hostName = [Environment]::GetEnvironmentVariable('DB_HOST', 'Process')
$portText = [Environment]::GetEnvironmentVariable('DB_PORT', 'Process')
$database = [Environment]::GetEnvironmentVariable('DB_NAME', 'Process')
$username = [Environment]::GetEnvironmentVariable('DB_USERNAME', 'Process')
$password = [Environment]::GetEnvironmentVariable('DB_PASSWORD', 'Process')
$configuredMediaRoot = [Environment]::GetEnvironmentVariable('MEDIA_ROOT', 'Process')
if (-not [IO.Path]::IsPathRooted($configuredMediaRoot)) { throw 'MEDIA_ROOT must be an existing absolute directory.' }
$mediaRoot = [IO.Path]::GetFullPath($configuredMediaRoot).TrimEnd('\')
if ($hostName -notmatch '^[A-Za-z0-9.:-]+$' -or $database -notmatch '^[A-Za-z0-9_]+$' -or $username -notmatch '^[A-Za-z0-9_]+$') {
    throw 'Database host, name or username contains unsupported characters.'
}
$port = 0
if (-not [int]::TryParse($portText, [ref]$port) -or $port -lt 1 -or $port -gt 65535) { throw 'DB_PORT must be 1-65535.' }
if (-not (Test-Path -LiteralPath $mediaRoot -PathType Container)) {
    throw 'MEDIA_ROOT must be an existing absolute directory.'
}
if ($destinationRoot -eq $mediaRoot -or $destinationRoot.StartsWith($mediaRoot + '\', [StringComparison]::OrdinalIgnoreCase) -or
    $mediaRoot.StartsWith($destinationRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Backup destination and media tree must not contain one another.'
}
$dumpExe = if ([string]::IsNullOrWhiteSpace($MySqlBin)) {
    $command = Get-Command mysqldump.exe -CommandType Application -ErrorAction SilentlyContinue
    if ($null -eq $command) { throw 'mysqldump.exe was not found on PATH; pass its bin directory with -MySqlBin.' }
    $command.Source
} else { Join-Path ([IO.Path]::GetFullPath($MySqlBin)) 'mysqldump.exe' }
if (-not (Test-Path -LiteralPath $dumpExe -PathType Leaf)) { throw 'Existing mysqldump.exe is required; nothing is installed automatically.' }
$reparse = [IO.FileAttributes]::ReparsePoint
if (((Get-Item -LiteralPath $mediaRoot -Force).Attributes -band $reparse) -ne 0 -or
    @(Get-ChildItem -LiteralPath $mediaRoot -Recurse -Force | Where-Object { ($_.Attributes -band $reparse) -ne 0 }).Count) {
    throw 'MEDIA_ROOT must not contain reparse points or links.'
}

[void](New-Item -ItemType Directory -Path $destinationRoot)
$identity = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = New-Object Security.AccessControl.DirectorySecurity
$acl.SetOwner($identity); $acl.SetAccessRuleProtection($true, $false)
$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
Set-Acl -LiteralPath $destinationRoot -AclObject $acl
$dumpFile = Join-Path $destinationRoot 'database.sql'
$mediaDestination = Join-Path $destinationRoot 'media'
[void](New-Item -ItemType Directory -Path $mediaDestination)

$previousPassword = [Environment]::GetEnvironmentVariable('MYSQL_PWD', 'Process')
try {
    [Environment]::SetEnvironmentVariable('MYSQL_PWD', $password, 'Process')
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $dumpExe
    $info.Arguments = '--no-defaults --protocol=TCP --host=' + $hostName + ' --port=' + $port + ' --user=' + $username +
        ' --single-transaction --no-tablespaces --skip-lock-tables --set-gtid-purged=OFF --hex-blob --default-character-set=utf8mb4' +
        ' --result-file="' + $dumpFile + '" ' + $database
    $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    $process = New-Object Diagnostics.Process; $process.StartInfo = $info
    try {
        [void]$process.Start(); $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(300000)) { $process.Kill(); throw 'Database backup timed out; partial directory retained.' }
        [void]$stdout.GetAwaiter().GetResult(); [void]$stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw 'Database backup failed; private details were not printed and partial directory was retained.' }
    } finally { $process.Dispose() }
} finally { [Environment]::SetEnvironmentVariable('MYSQL_PWD', $previousPassword, 'Process') }
if (-not (Test-Path -LiteralPath $dumpFile -PathType Leaf) -or (Get-Item -LiteralPath $dumpFile).Length -le 0) {
    throw 'Database backup is empty; partial directory retained.'
}

$mediaFiles = @(Get-ChildItem -LiteralPath $mediaRoot -Recurse -File -Force | Sort-Object FullName)
foreach ($file in $mediaFiles) {
    $relative = $file.FullName.Substring($mediaRoot.Length).TrimStart('\')
    $target = Join-Path $mediaDestination $relative
    $parent = Split-Path -Parent $target
    if (-not (Test-Path -LiteralPath $parent)) { [void](New-Item -ItemType Directory -Path $parent) }
    Copy-Item -LiteralPath $file.FullName -Destination $target
}
$artifacts = @()
foreach ($file in @(Get-ChildItem -LiteralPath $destinationRoot -Recurse -File -Force | Where-Object Name -ne 'manifest.json' | Sort-Object FullName)) {
    $relative = $file.FullName.Substring($destinationRoot.Length).TrimStart('\').Replace('\','/')
    $artifacts += [ordered]@{ path=$relative; size=[long]$file.Length; sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
}
$manifest = [ordered]@{
    format = 1; createdAt = [DateTime]::UtcNow.ToString('o'); writesQuiesced = $true
    databaseDump = 'database.sql'; mediaDirectory = 'media'; mediaFiles = $mediaFiles.Count
    artifacts = $artifacts
    limitations = @('Operator confirmed writers were quiesced; this tool does not stop traffic or prove storage-level atomicity.',
        'Restore must target an isolated environment and pass schema, hash, binding and business smoke checks before release.')
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $destinationRoot 'manifest.json') -Encoding UTF8
Write-Output ([ordered]@{status='PASS';destination=$destinationRoot;artifacts=$artifacts.Count;mediaFiles=$mediaFiles.Count;writesQuiesced=$true} | ConvertTo-Json -Compress)
