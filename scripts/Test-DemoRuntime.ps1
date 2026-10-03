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

$tempRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$aclProbe=Join-Path $tempRoot ('bishe-demo-acl-'+[Guid]::NewGuid().ToString('N'))
try{
    Protect-DemoDirectory $aclProbe
    $firstAcl=Get-Acl -LiteralPath $aclProbe
    Protect-DemoDirectory $aclProbe
    $secondAcl=Get-Acl -LiteralPath $aclProbe
    $currentUser=[Security.Principal.WindowsIdentity]::GetCurrent().User
    Assert-DemoTest ($firstAcl.AreAccessRulesProtected -and $secondAcl.AreAccessRulesProtected) 'New private demo directory is protected and restart validation is idempotent.'
    Assert-DemoTest ($secondAcl.GetOwner([Security.Principal.SecurityIdentifier]) -eq $currentUser) 'Demo directory remains owned by the creating Windows user.'
}finally{
    if(Test-Path -LiteralPath $aclProbe -PathType Container){
        $resolvedProbe=[IO.Path]::GetFullPath($aclProbe)
        $allowedPrefix=$tempRoot+[IO.Path]::DirectorySeparatorChar
        if(-not $resolvedProbe.StartsWith($allowedPrefix,[StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolvedProbe) -notmatch '^bishe-demo-acl-[a-f0-9]{32}$'){
            throw 'Refusing to remove an unexpected ACL test directory.'
        }
        Remove-Item -LiteralPath $resolvedProbe -Force
    }
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
$roundTrip=($record | ConvertTo-Json -Compress) | ConvertFrom-Json
Assert-DemoTest ($roundTrip.createdUtc -is [datetime] -or $roundTrip.createdUtc -is [string]) 'JSON round trip retains a supported timestamp value.'
Assert-DemoTest (Test-DemoProcess $roundTrip) 'Serialized active process identity remains verifiable.'
$record.createdUtc='2000-01-01T00:00:00.0000000Z'
Assert-DemoTest (-not(Test-DemoProcess $record)) 'A reused PID with a different creation time cannot be stopped.'
$roundTripDate=[datetime]$roundTrip.createdUtc
$roundTrip.createdUtc=$roundTripDate.AddSeconds(1).ToString('o')
Assert-DemoTest (-not(Test-DemoProcess $roundTrip)) 'Serialized process identity still rejects a different creation time.'
$rejected=$false
try{Assert-DemoRecord $layout $record}catch{$rejected=$_.Exception.Message -eq 'Unknown demo process role.'}
Assert-DemoTest $rejected 'Unknown recorded process roles are refused.'
Write-Output ('PASS: '+$checks+' demo runtime regression checks. No database, Docker or application service was started.')
