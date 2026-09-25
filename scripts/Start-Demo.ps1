#requires -Version 5.1
<# Creates/reuses only this project's persistent local demo. No Windows service changes. #>
[CmdletBinding()]
param([switch]$StartDemo,[switch]$ShowDemoAccess,[string]$JarPath='')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if(-not $StartDemo -and -not $ShowDemoAccess){throw 'Explicit -StartDemo is required; -ShowDemoAccess alone only reads stored demo credentials.'}
if($MyInvocation.InvocationName -eq '.'){throw 'Invoke Start-Demo as a script, not by dot-sourcing.'}
. (Join-Path $PSScriptRoot 'lib\DemoRuntime.ps1')
$layout=Get-DemoLayout (Split-Path -Parent $PSScriptRoot)
Assert-DemoPath $layout.Root
if($ShowDemoAccess -and -not $StartDemo){
    $null=Read-DemoInstance $layout
    $access=Read-DemoCredential $layout 'admin' 'admin'
    Write-Output 'Demo address: http://127.0.0.1:15174/ (the runtime may be stopped).'
    Write-Output ('Demo administrator: admin; password: '+$access.GetNetworkCredential().Password)
    return
}
foreach($name in @('MYSQL_HOME','MYSQL_TCP_PORT','MYSQL_UNIX_PORT','MYSQL_PWD','NODE_OPTIONS','NODE_PATH','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')){
    if([Environment]::GetEnvironmentVariable($name,'Process')){throw ('Remove inherited override before demo startup: '+$name)}
}
if(@(Get-ChildItem Env: | Where-Object {$_.Name -like 'SPRING_*' -or $_.Name -like 'APP_*'}).Count){throw 'Remove inherited SPRING_* and APP_* overrides before demo startup; values are not printed.'}
if(-not $JarPath){$JarPath=Join-Path $layout.Project 'target\lost-found-ai-1.0.0.jar'}
$jar=(Resolve-Path -LiteralPath $JarPath).Path
if(-not(Test-Path -LiteralPath $jar -PathType Leaf) -or [IO.Path]::GetExtension($jar) -ne '.jar'){throw 'Build the backend jar before starting the demo.'}
$dist=Join-Path $layout.Project 'frontend\dist'
foreach($path in @($layout.MysqlServer,$layout.MysqlClient,$layout.Java,(Join-Path $dist 'index.html'),(Join-Path $layout.Project 'scripts\serve-demo.mjs'))){
    Assert-DemoPath $path
    if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw ('Required demo artifact is missing: '+$path)}
}
if((Get-Item -LiteralPath $layout.MysqlServer).VersionInfo.FileVersion -ne '8.0.41.0'){throw 'This local demo is verified with the existing MySQL 8.0.41 binary.'}
$node=(Get-Command node.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
if((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory*1KB -lt 2GB){throw 'AI-disabled demo startup requires at least 2 GiB of available memory.'}
foreach($port in @(13308,18080,15174)){Assert-DemoPortFree $port}
if(Test-Path -LiteralPath $layout.State){
    Assert-DemoPath $layout.State
    $old=Get-Content -LiteralPath $layout.State -Raw | ConvertFrom-Json
    if($old.project -ne $layout.Project -or $old.demoRoot -ne $layout.Root){throw 'Existing demo runtime manifest belongs elsewhere.'}
    foreach($record in @($old.processes)){
        Assert-DemoRecord $layout $record
        if(Test-DemoProcess $record){throw 'A previous owned demo process is still running. Use Stop-Demo -StopDemo first.'}
    }
}
$docker=Get-DemoDocker
$null=Get-DemoRedisContainer $layout $docker
$revision=(& git -C $layout.Project rev-parse --short HEAD) -join ''
if($LASTEXITCODE -ne 0){throw 'Cannot identify the demo source revision.'}
$dirty=@(& git -C $layout.Project status --porcelain)
Write-Output ('Demo source revision: '+$revision+'; uncommitted entries: '+$dirty.Count+'. Artifacts must be built from the intended revision.')
Protect-DemoDirectory $layout.Root
foreach($path in @($layout.Data,$layout.Media,(Join-Path $layout.Root 'mysql-initialize.private.log'),
    (Join-Path $layout.Root 'mysql.private.log'),(Join-Path $layout.Root 'backend.stdout.log'),
    (Join-Path $layout.Root 'backend.stderr.log'),(Join-Path $layout.Root 'frontend.stdout.log'),
    (Join-Path $layout.Root 'frontend.stderr.log'))){Assert-DemoPath $path}
$runtimeLock=Enter-DemoRuntimeLock $layout -Starting
$state=[pscustomobject]@{format=1;project=$layout.Project;demoRoot=$layout.Root;startedAt=(Get-Date).ToString('o');
    revision=$revision;status='starting';processes=@();failure=$null}
$instance=$null;$rootPassword=$null;$temporaryPassword=$null;$saved=@{};$initializing=$null
function Register-DemoProcess([int]$Id,[string]$Role){
    $record=Get-DemoProcessRecord $Id $Role
    Assert-DemoRecord $layout $record
    $state.processes+=@($record);Save-DemoJson $layout.State $state
    return $record
}
try{
    $fresh=-not(Test-Path -LiteralPath $layout.Instance)
    if($fresh){
        if(Test-Path -LiteralPath $layout.Data){throw 'An unregistered demo data directory exists. It was retained; do not overwrite or initialize it again.'}
        $rootPassword=New-DemoSecret;$appPassword=New-DemoSecret;$adminPassword=New-DemoSecret
        Save-DemoCredential $layout 'root' 'root' $rootPassword
        Save-DemoCredential $layout 'app' 'demo_app' $appPassword
        Save-DemoCredential $layout 'admin' 'admin' $adminPassword
        $initLog=Join-Path $layout.Root 'mysql-initialize.private.log'
        $initializeInfo=New-Object Diagnostics.ProcessStartInfo
        $initializeInfo.FileName=$layout.MysqlServer
        $initializeInfo.Arguments=@('--no-defaults','--initialize',('--basedir="'+$layout.MysqlRoot+'"'),('--datadir="'+$layout.Data+'"'),('--log-error="'+$initLog+'"'),'--innodb-buffer-pool-size=64M') -join ' '
        $initializeInfo.UseShellExecute=$false;$initializeInfo.CreateNoWindow=$true
        $initializing=New-Object Diagnostics.Process;$initializing.StartInfo=$initializeInfo
        [void]$initializing.Start()
        $state | Add-Member -NotePropertyName initialization -NotePropertyValue ([pscustomobject]@{
            pid=$initializing.Id;executable=$layout.MysqlServer;dataDirectory=$layout.Data;status='running';exitCode=$null})
        Save-DemoJson $layout.State $state
        $completed=Wait-DemoInitializer $layout $initializing
        $state.initialization.status='completed';$state.initialization.exitCode=$completed.exitCode
        Save-DemoJson $layout.State $state
        $match=[regex]::Match((Get-Content -LiteralPath $initLog -Raw),'temporary password is generated for root@localhost: (.+)')
        if(-not $match.Success){throw 'New demo root temporary password not found; no server startup attempted.'}
        $temporaryPassword=$match.Groups[1].Value.Trim()
        $uuid=[regex]::Match((Get-Content -LiteralPath (Join-Path $layout.Data 'auto.cnf') -Raw),'(?m)^server-uuid=([a-f0-9-]{36})\s*$')
        if(-not $uuid.Success){throw 'New demo MySQL UUID is missing; no server startup attempted.'}
        $instance=[pscustomobject]@{format=1;project=$layout.Project;dataDirectory=$layout.Data;port=13308;schema='demo_lost_found';
            serverUuid=$uuid.Groups[1].Value;redisDatabase=13;redisOwner=[Guid]::NewGuid().ToString('N');bootstrapComplete=$false;createdAt=(Get-Date).ToString('o')}
        Save-DemoJson $layout.Instance $instance
    }else{
        $instance=Read-DemoInstance $layout
        $rootPassword=(Read-DemoCredential $layout 'root' 'root').GetNetworkCredential().Password
        $appPassword=(Read-DemoCredential $layout 'app' 'demo_app').GetNetworkCredential().Password
        $adminPassword=(Read-DemoCredential $layout 'admin' 'admin').GetNetworkCredential().Password
    }
    Start-DemoRedis $layout $docker
    $server=Start-Process -FilePath $layout.MysqlServer -ArgumentList @('--no-defaults',('--basedir="'+$layout.MysqlRoot+'"'),('--datadir="'+$layout.Data+'"'),('--log-error="'+(Join-Path $layout.Root 'mysql.private.log')+'"'),
        '--port=13308','--bind-address=127.0.0.1','--mysqlx=OFF','--skip-log-bin','--innodb-buffer-pool-size=64M','--innodb-redo-log-capacity=64M','--max-connections=16','--performance-schema=OFF') -WindowStyle Hidden -PassThru
    $monitor=Register-DemoProcess $server.Id 'mysql-monitor'
    $mysqlRecord=$null;$deadline=(Get-Date).AddSeconds(45)
    do{
        if($server.HasExited){throw 'Demo MySQL exited; inspect its private log.'}
        $listeners=@(Get-NetTCPConnection -State Listen -LocalPort 13308 -ErrorAction SilentlyContinue)
        if($listeners.Count){
            if($listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1'){throw 'Unexpected demo MySQL listener.'}
            $candidate=Get-CimInstance Win32_Process -Filter ('ProcessId='+$listeners[0].OwningProcess)
            if($candidate.ExecutablePath -ne $layout.MysqlServer -or -not $candidate.CommandLine.Contains($layout.Data) -or
                ($candidate.ProcessId -ne $server.Id -and $candidate.ParentProcessId -ne $server.Id)){throw 'Demo MySQL listener does not belong to the process just started.'}
            $mysqlRecord=Register-DemoProcess $candidate.ProcessId 'mysql';break
        }
        Start-Sleep -Milliseconds 300
    }while((Get-Date) -lt $deadline)
    if($null -eq $mysqlRecord){throw 'Owned demo MySQL did not bind port 13308.'}
    Assert-DemoListener 13308 $mysqlRecord
    if(-not $instance.bootstrapComplete){
        try{Assert-DemoDatabase $layout $instance $rootPassword}
        catch{
            $initLog=Join-Path $layout.Root 'mysql-initialize.private.log';Assert-DemoPath $initLog
            $match=[regex]::Match((Get-Content -LiteralPath $initLog -Raw),'temporary password is generated for root@localhost: (.+)')
            if(-not $match.Success){throw 'Incomplete demo initialization requires its retained private log.'}
            $temporaryPassword=$match.Groups[1].Value.Trim()
            [void](Invoke-DemoSql $layout "ALTER USER 'root'@'localhost' IDENTIFIED BY '$rootPassword';" $temporaryPassword -Expired)
            $temporaryPassword=$null
        }
        Assert-DemoDatabase $layout $instance $rootPassword
        [void](Invoke-DemoSql $layout ('CREATE DATABASE IF NOT EXISTS `demo_lost_found` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER IF NOT EXISTS ''demo_app''@''localhost'' IDENTIFIED BY '''+$appPassword+'''; GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON `demo\_lost\_found`.* TO ''demo_app''@''localhost'';') $rootPassword)
        $instance.bootstrapComplete=$true;Save-DemoJson $layout.Instance $instance
    }
    Assert-DemoDatabase $layout $instance $rootPassword
    Assert-DemoRedisDatabase $docker $instance.redisOwner
    if(-not(Test-Path -LiteralPath $layout.Media)){[void](New-Item -ItemType Directory -Path $layout.Media)}
    $variables=@{
        DB_HOST='127.0.0.1';DB_PORT='13308';DB_NAME='demo_lost_found';DB_USERNAME='demo_app';DB_PASSWORD=$appPassword
        REDIS_HOST='127.0.0.1';REDIS_PORT='16380';REDIS_DATABASE='13';REDIS_USERNAME='';REDIS_PASSWORD=''
        SERVER_ADDRESS='127.0.0.1';SERVER_PORT='18080';CAMPUS_ID='TEST_CAMPUS';CAMPUS_TEST_MODE='true';AI_ENABLED='false'
        CORS_ALLOWED_ORIGINS='http://127.0.0.1:15174';MEDIA_ROOT=$layout.Media;MEDIA_CLEANUP_ENABLED='true';ADMIN_PASSWORD=$adminPassword
    }
    foreach($name in $variables.Keys){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$variables[$name],'Process')}
    $backend=Start-Process -FilePath $layout.Java -WorkingDirectory $layout.Root -ArgumentList @('-Xms64m','-Xmx256m',('"-Dcampus.demo.root='+$layout.Root+'"'),'-jar',('"'+$jar+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $layout.Root 'backend.stdout.log') -RedirectStandardError (Join-Path $layout.Root 'backend.stderr.log')
    $backendRecord=Register-DemoProcess $backend.Id 'backend'
    foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')};$saved=@{}
    $ready=(Wait-DemoHttp 'http://127.0.0.1:18080/api/health/ready' $backendRecord) | ConvertFrom-Json
    Assert-DemoListener 18080 $backendRecord
    if($ready.status -ne 'UP'){throw 'Demo backend readiness check failed.'}
    $config=(Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:18080/api/public/config' -TimeoutSec 5).Content | ConvertFrom-Json
    if(-not $config.data.isTest -or $config.data.aiEnabled){throw 'Demo backend did not retain test mode and disabled AI.'}
    $frontend=Start-Process -FilePath $node -WorkingDirectory $layout.Project -ArgumentList @(('"'+(Join-Path $layout.Project 'scripts\serve-demo.mjs')+'"'),'--confirm-local-demo','--directory',('"'+$dist+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $layout.Root 'frontend.stdout.log') -RedirectStandardError (Join-Path $layout.Root 'frontend.stderr.log')
    $frontRecord=Register-DemoProcess $frontend.Id 'frontend'
    $null=Wait-DemoHttp 'http://127.0.0.1:15174/' $frontRecord 20
    Assert-DemoListener 15174 $frontRecord
    $proxy=(Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:15174/api/health/ready' -TimeoutSec 5).Content | ConvertFrom-Json
    if($proxy.status -ne 'UP'){throw 'Demo frontend proxy readiness check failed.'}
    $state.status='running';Save-DemoJson $layout.State $state
    Write-Output 'Demo ready: http://127.0.0.1:15174/ (synthetic test campus, AI disabled).'
    Write-Output 'MySQL 13308 and Redis DB13 are dedicated to this demo. Data persists across Stop-Demo.'
    if($ShowDemoAccess){Write-Output ('Demo administrator: admin; password: '+$adminPassword)}
    else{Write-Output 'Admin password is stored with Windows DPAPI under .local/demo/admin.credential.xml; it was not printed.'}
}catch{
    $state.failure=$_.Exception.Message;$state.status='failed'
    if($null -ne $initializing -and $state.PSObject.Properties['initialization'] -and $state.initialization.status -eq 'running'){
        $state.initialization.status='failed'
        if($initializing.HasExited){$state.initialization.exitCode=$initializing.ExitCode}
    }
    try{
        if($instance){Stop-DemoRuntime $layout $state $instance $rootPassword}
        else{
            # Before identity establishment, only exact processes created in this run qualify.
            foreach($record in @($state.processes | Sort-Object @{Expression={if($_.role -eq 'mysql-monitor'){1}else{0}}})){
                Stop-DemoRecordedProcess $layout $record
            }
        }
    }catch{
        Write-Warning ('Graceful demo cleanup was incomplete: '+$_.Exception.Message)
        # These records were created in this invocation, never loaded from another run.
        # Preserve the data for MySQL crash recovery when SQL shutdown is unavailable.
        foreach($record in @($state.processes | Sort-Object @{Expression={if($_.role -eq 'mysql-monitor'){1}else{0}}})){
            try{Stop-DemoRecordedProcess $layout $record}catch{Write-Warning 'An owned process could not be stopped; inspect runtime.json.'}
        }
        $state | Add-Member -NotePropertyName forcedCleanup -NotePropertyValue $true -Force
    }
    Save-DemoJson $layout.State $state
    throw $state.failure
}finally{
    foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
    if($null -ne $initializing){$initializing.Dispose()}
    $runtimeLock.Dispose()
}
