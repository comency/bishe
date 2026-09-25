#requires -Version 5.1
<# Regression checks. Starts only finite cmd.exe exit probes, never MySQL, Docker or application services. #>
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'lib\DemoRuntime.ps1')
$layout=Get-DemoLayout (Split-Path -Parent $PSScriptRoot)
$checks=0
function Assert-DemoTest([bool]$Condition,[string]$Message){
    if(-not $Condition){throw ('Demo regression failed: '+$Message)}
    $script:checks++
}
function New-ExitedProbe([int]$ExitCode){
    $info=New-Object Diagnostics.ProcessStartInfo
    $info.FileName=Join-Path $env:SystemRoot 'System32\cmd.exe'
    $info.Arguments='/d /c exit '+$ExitCode
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $process=New-Object Diagnostics.Process;$process.StartInfo=$info
    [void]$process.Start()
    if(-not $process.WaitForExit(5000)){$process.Kill();$process.Dispose();throw 'Finite exit probe unexpectedly timed out.'}
    return $process
}

foreach($file in @('Start-Demo.ps1','Stop-Demo.ps1','Test-DemoRuntime.ps1','lib\DemoRuntime.ps1')){
    $path=Join-Path $PSScriptRoot $file;$tokens=$null;$parseErrors=$null
    [void][Management.Automation.Language.Parser]::ParseFile($path,[ref]$tokens,[ref]$parseErrors)
    Assert-DemoTest ($parseErrors.Count -eq 0) ('PowerShell parses '+$file)
    Assert-DemoTest ((Get-Content -LiteralPath $path -Raw) -notmatch '[^\x00-\x7F]') ('ASCII-compatible source '+$file)
}
foreach($file in @('Start-Demo.ps1','Stop-Demo.ps1')){
    $refused=$false
    try{& (Join-Path $PSScriptRoot $file)}catch{$refused=$_.Exception.Message -like 'Explicit -*'}
    Assert-DemoTest $refused ('Missing mutation flag is refused: '+$file)
}

$probe=New-ExitedProbe 0
try{
    Assert-DemoTest $probe.HasExited 'Success probe has already exited before initialization wait.'
    $completed=Wait-DemoInitializer $layout $probe
    Assert-DemoTest ($completed.exitCode -eq 0 -and $completed.pid -eq $probe.Id) 'Already-exited initializer succeeds without requiring a live CIM row.'
}finally{$probe.Dispose()}
$probe=New-ExitedProbe 23
try{
    $message=''
    try{$null=Wait-DemoInitializer $layout $probe}catch{$message=$_.Exception.Message}
    Assert-DemoTest ($message -like '*exited with code 23*') 'Early initialization failure preserves actual exit code.'
    Assert-DemoTest ($message -like '*do not initialize the retained directory again*') 'Failure explains retained-directory recovery boundary.'
}finally{$probe.Dispose()}

$record=Get-DemoProcessRecord $PID 'test-probe'
Assert-DemoTest (Test-DemoProcess $record) 'Current process identity matches before mutation.'
$record.createdUtc='2000-01-01T00:00:00.0000000Z'
Assert-DemoTest (-not(Test-DemoProcess $record)) 'A reused PID with a different creation time cannot be stopped.'
$rejected=$false
try{Assert-DemoRecord $layout $record}catch{$rejected=$_.Exception.Message -eq 'Unknown demo process role.'}
Assert-DemoTest $rejected 'Unknown recorded process roles are refused.'
Write-Output ('PASS: '+$checks+' demo runtime regression checks. No database, Docker or application service was started.')
