#requires -Version 5.1
<# Stop only recorded local demo processes. Keep MySQL/media data and test Redis. #>
[CmdletBinding()]
param([switch]$StopDemo)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if(-not $StopDemo){throw 'Explicit -StopDemo is required.'}
if($MyInvocation.InvocationName -eq '.'){throw 'Invoke Stop-Demo as a script, not by dot-sourcing.'}
. (Join-Path $PSScriptRoot 'lib\DemoRuntime.ps1')
$layout=Get-DemoLayout (Split-Path -Parent $PSScriptRoot)
Assert-DemoPath $layout.Root
if(-not(Test-Path -LiteralPath $layout.State -PathType Leaf)){Write-Output 'No recorded demo runtime. Nothing was stopped.';return}
$runtimeLock=Enter-DemoRuntimeLock $layout
try{
Assert-DemoPath $layout.State
$state=Get-Content -LiteralPath $layout.State -Raw | ConvertFrom-Json
if($state.project -ne $layout.Project -or $state.demoRoot -ne $layout.Root){throw 'Demo runtime does not match this project.'}
foreach($record in @($state.processes)){Assert-DemoRecord $layout $record}
$active=@($state.processes | Where-Object {Test-DemoProcess $_})
if(-not $active.Count){Write-Output 'Recorded demo processes are already stopped. Data and Redis are retained.';return}
$instance=Read-DemoInstance $layout
$password=(Read-DemoCredential $layout 'root' 'root').GetNetworkCredential().Password
Stop-DemoRuntime $layout $state $instance $password
$state.status='stopped';$state | Add-Member -NotePropertyName stoppedAt -NotePropertyValue (Get-Date).ToString('o') -Force
Save-DemoJson $layout.State $state
Write-Output 'Stopped only the recorded demo frontend/backend and its verified MySQL instance. MySQL/media data, credentials and project Redis are retained.'
}finally{$runtimeLock.Dispose()}
