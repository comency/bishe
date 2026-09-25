#requires -Version 5.1
# Shared local demo helpers. Importing this file does not start or stop anything.
Set-StrictMode -Version Latest

function Get-DemoLayout {
    param([string]$ProjectRoot)
    $project = [IO.Path]::GetFullPath($ProjectRoot).TrimEnd('\')
    $root = Join-Path $project '.local\demo'
    [pscustomobject]@{
        Project=$project; Root=$root; Data=(Join-Path $root 'mysql-data'); Media=(Join-Path $root 'media')
        Instance=(Join-Path $root 'instance.json'); State=(Join-Path $root 'runtime.json')
        MysqlRoot='E:\MySQL\MySQL Server 8.0'
        MysqlServer='E:\MySQL\MySQL Server 8.0\bin\mysqld.exe'
        MysqlClient='E:\MySQL\MySQL Server 8.0\bin\mysql.exe'
        Java='C:\Program Files\Java\jdk-21\bin\java.exe'
    }
}

function Assert-DemoPath {
    param([string]$Path)
    $cursor=[IO.Path]::GetFullPath($Path)
    while($cursor){
        if(Test-Path -LiteralPath $cursor){
            if((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){
                throw 'Demo paths must not contain reparse points or symbolic links.'
            }
        }
        $cursor=[IO.Path]::GetDirectoryName($cursor)
    }
}

function Protect-DemoDirectory {
    param([string]$Path)
    Assert-DemoPath $Path
    if(-not(Test-Path -LiteralPath $Path -PathType Container)){[void](New-Item -ItemType Directory -Path $Path)}
    $owner=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl=New-Object Security.AccessControl.DirectorySecurity
    $acl.SetOwner($owner);$acl.SetAccessRuleProtection($true,$false)
    $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($owner,'FullControl','ContainerInherit,ObjectInherit','None','Allow')))
    Set-Acl -LiteralPath $Path -AclObject $acl
}

function Save-DemoJson {
    param([string]$Path,$Value)
    Assert-DemoPath $Path
    $Value | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Enter-DemoRuntimeLock {
    param($Layout,[switch]$Starting)
    $path=Join-Path $Layout.Root 'runtime.lock';Assert-DemoPath $path
    try{$handle=[IO.File]::Open($path,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)}
    catch{throw 'Another demo start/stop command is running. Wait for it to finish.'}
    try{
        if($Starting){
            foreach($port in @(13308,18080,15174)){Assert-DemoPortFree $port}
            if(Test-Path -LiteralPath $Layout.State){
                Assert-DemoPath $Layout.State
                $previous=Get-Content -LiteralPath $Layout.State -Raw | ConvertFrom-Json
                if($previous.project -ne $Layout.Project -or $previous.demoRoot -ne $Layout.Root){throw 'Existing demo runtime belongs elsewhere.'}
                foreach($record in @($previous.processes)){
                    Assert-DemoRecord $Layout $record
                    if(Test-DemoProcess $record){throw 'A previous demo process is still running. Use Stop-Demo -StopDemo first.'}
                }
            }
        }
        return $handle
    }catch{$handle.Dispose();throw}
}

function New-DemoSecret {
    $bytes=New-Object byte[] 24
    $random=[Security.Cryptography.RandomNumberGenerator]::Create()
    try{$random.GetBytes($bytes)}finally{$random.Dispose()}
    return [BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()
}

function Save-DemoCredential {
    param($Layout,[string]$Name,[string]$User,[string]$Password)
    $path=Join-Path $Layout.Root ($Name+'.credential.xml')
    Assert-DemoPath $path
    if(Test-Path -LiteralPath $path){throw 'Demo credential already exists; it will not be overwritten.'}
    $value=New-Object Management.Automation.PSCredential($User,(ConvertTo-SecureString $Password -AsPlainText -Force))
    $value | Export-Clixml -LiteralPath $path
}

function Read-DemoCredential {
    param($Layout,[string]$Name,[string]$ExpectedUser)
    $path=Join-Path $Layout.Root ($Name+'.credential.xml')
    Assert-DemoPath $path
    try{
        $value=Import-Clixml -LiteralPath $path
        if($value -isnot [Management.Automation.PSCredential] -or $value.UserName -ne $ExpectedUser -or
            $value.GetNetworkCredential().Password -notmatch '^[a-f0-9]{48}$'){throw 'Invalid credential.'}
        return $value
    }catch{throw 'Demo credentials could not be decrypted. Use the Windows account that created this demo; files are retained.'}
}

function Get-DemoProcessRecord {
    param([int]$ProcessId,[string]$Role)
    $process=Get-CimInstance Win32_Process -Filter ('ProcessId='+$ProcessId)
    if(-not $process){throw ('The new demo '+$Role+' process exited before registration. Inspect its protected startup log.')}
    if(-not $process.ExecutablePath -or -not $process.CommandLine){throw ('Cannot read executable/command-line ownership of the new demo '+$Role+' process.')}
    [pscustomobject]@{role=$Role;pid=[int]$process.ProcessId;createdUtc=$process.CreationDate.ToUniversalTime().ToString('o');
        executable=$process.ExecutablePath;commandLine=$process.CommandLine}
}

function Wait-DemoInitializer {
    param($Layout,[Diagnostics.Process]$Process,[int]$TimeoutMilliseconds=60000)
    # --initialize is a finite command and can successfully exit before CIM sees it.
    # The Process created with UseShellExecute=false retains its OS handle and exit code.
    # Only a still-running, timed-out initializer needs a live ownership record.
    if(-not $Process.WaitForExit($TimeoutMilliseconds)){
        $record=Get-DemoProcessRecord $Process.Id 'mysql-initialize'
        Stop-DemoRecordedProcess $Layout $record
        throw 'Demo MySQL initialization timed out. Only its verified process was stopped; private data and logs are retained.'
    }
    if($Process.ExitCode -ne 0){
        throw ('Demo MySQL initialization exited with code '+$Process.ExitCode+'. Inspect mysql-initialize.private.log; do not initialize the retained directory again.')
    }
    return [pscustomobject]@{pid=$Process.Id;exitCode=[int]$Process.ExitCode;completedAt=(Get-Date).ToString('o')}
}

function Test-DemoProcess {
    param($Record)
    $process=Get-CimInstance Win32_Process -Filter ('ProcessId='+[int]$Record.pid)
    if(-not $process){return $false}
    return $process.ExecutablePath -eq $Record.executable -and $process.CommandLine -ceq $Record.commandLine -and
        $process.CreationDate.ToUniversalTime().ToString('o') -eq $Record.createdUtc
}

function Assert-DemoRecord {
    param($Layout,$Record)
    if($Record.role -in @('mysql','mysql-monitor','mysql-initialize')){
        if($Record.executable -ne $Layout.MysqlServer -or -not $Record.commandLine.Contains($Layout.Data)){
            throw 'Recorded MySQL process does not belong to this demo data directory.'
        }
    }elseif($Record.role -eq 'backend'){
        if($Record.executable -ne $Layout.Java -or -not $Record.commandLine.Contains('-Dcampus.demo.root='+$Layout.Root)){
            throw 'Recorded JVM does not belong to this demo.'
        }
    }elseif($Record.role -eq 'frontend'){
        $script=Join-Path $Layout.Project 'scripts\serve-demo.mjs'
        if([IO.Path]::GetFileName($Record.executable) -ne 'node.exe' -or -not $Record.commandLine.Contains($script) -or
            -not $Record.commandLine.Contains('--confirm-local-demo')){throw 'Recorded frontend does not belong to this demo.'}
    }else{throw 'Unknown demo process role.'}
}

function Stop-DemoRecordedProcess {
    param($Layout,$Record)
    Assert-DemoRecord $Layout $Record
    if(Test-DemoProcess $Record){
        Stop-Process -Id ([int]$Record.pid) -ErrorAction Stop
        $limit=(Get-Date).AddSeconds(10)
        while((Test-DemoProcess $Record) -and (Get-Date) -lt $limit){Start-Sleep -Milliseconds 200}
        if(Test-DemoProcess $Record){throw 'An exact owned demo process did not stop.'}
    }
}

function Assert-DemoPortFree {
    param([int]$Port)
    if(@(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue).Count){
        throw ('Demo port '+$Port+' is occupied. No existing process was stopped.')
    }
}

function Assert-DemoListener {
    param([int]$Port,$Record)
    $listeners=@(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    if($listeners.Count -ne 1 -or $listeners[0].LocalAddress -ne '127.0.0.1' -or
        $listeners[0].OwningProcess -ne [int]$Record.pid -or -not(Test-DemoProcess $Record)){
        throw ('Unexpected listener identity on demo port '+$Port+'.')
    }
}

function Invoke-DemoSql {
    param($Layout,[string]$Sql,[string]$Password,[switch]$Expired)
    $info=New-Object Diagnostics.ProcessStartInfo
    $info.FileName=$Layout.MysqlClient
    $info.Arguments='--no-defaults --protocol=TCP --host=127.0.0.1 --port=13308 --user=root --connect-timeout=3 --batch --skip-column-names --default-character-set=utf8mb4'
    if($Expired){$info.Arguments+=' --connect-expired-password'}
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true
    $info.RedirectStandardInput=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    $info.EnvironmentVariables['MYSQL_PWD']=$Password
    $process=New-Object Diagnostics.Process;$process.StartInfo=$info
    try{
        [void]$process.Start();$output=$process.StandardOutput.ReadToEndAsync();$errors=$process.StandardError.ReadToEndAsync()
        $process.StandardInput.WriteLine($Sql);$process.StandardInput.Close()
        if(-not $process.WaitForExit(15000)){$process.Kill();throw 'Demo SQL timed out; private details withheld.'}
        $answer=$output.GetAwaiter().GetResult();[void]$errors.GetAwaiter().GetResult()
        if($process.ExitCode -ne 0){throw 'Demo SQL failed; credentials and SQL details withheld.'}
        return $answer.Trim()
    }finally{$process.Dispose()}
}

function Assert-DemoDatabase {
    param($Layout,$Instance,[string]$Password)
    $facts=(Invoke-DemoSql $Layout 'SELECT @@port,@@server_uuid,@@datadir;' $Password).Split("`t")
    if($facts.Count -ne 3 -or $facts[0] -ne '13308' -or $facts[1] -ne $Instance.serverUuid -or
        [IO.Path]::GetFullPath($facts[2]).TrimEnd('\','/') -ne $Layout.Data.TrimEnd('\','/')){
        throw 'Demo MySQL identity mismatch. No business SQL or shutdown was issued.'
    }
}

function Read-DemoInstance {
    param($Layout)
    Assert-DemoPath $Layout.Instance
    $value=Get-Content -LiteralPath $Layout.Instance -Raw | ConvertFrom-Json
    if($value.format -ne 1 -or $value.project -ne $Layout.Project -or $value.dataDirectory -ne $Layout.Data -or
        $value.port -ne 13308 -or $value.schema -ne 'demo_lost_found' -or $value.redisDatabase -ne 13 -or
        $value.serverUuid -notmatch '^[a-f0-9-]{36}$' -or $value.redisOwner -notmatch '^[a-f0-9]{32}$' -or
        $value.bootstrapComplete -isnot [bool]){
        throw 'Demo instance manifest does not match this project.'
    }
    $auto=Join-Path $Layout.Data 'auto.cnf';Assert-DemoPath $auto
    $uuid=[regex]::Match((Get-Content -LiteralPath $auto -Raw),'(?m)^server-uuid=([a-f0-9-]{36})\s*$')
    if(-not $uuid.Success -or $uuid.Groups[1].Value -ne $value.serverUuid){throw 'Demo data directory UUID does not match its manifest.'}
    return $value
}

function Stop-DemoRuntime {
    param($Layout,$State,$Instance,[string]$RootPassword)
    if($State.project -ne $Layout.Project -or $State.demoRoot -ne $Layout.Root){throw 'Demo runtime belongs to a different project.'}
    $records=@($State.processes)
    foreach($record in $records){Assert-DemoRecord $Layout $record}
    foreach($role in @('frontend','backend')){
        foreach($record in @($records | Where-Object {$_.role -eq $role})){Stop-DemoRecordedProcess $Layout $record}
    }
    $mysql=@($records | Where-Object {$_.role -in @('mysql','mysql-monitor') -and (Test-DemoProcess $_)})
    if($mysql.Count){
        $server=@($mysql | Where-Object {$_.role -eq 'mysql'})
        if($server.Count -ne 1 -or $null -eq $Instance){throw 'MySQL ownership is incomplete; inspect protected runtime evidence before stopping it.'}
        Assert-DemoListener 13308 $server[0]
        Assert-DemoDatabase $Layout $Instance $RootPassword
        [void](Invoke-DemoSql $Layout 'SHUTDOWN;' $RootPassword)
        $limit=(Get-Date).AddSeconds(30)
        do{
            $remaining=@($mysql | Where-Object {Test-DemoProcess $_})
            if(-not $remaining.Count){break}
            Start-Sleep -Milliseconds 250
        }while((Get-Date) -lt $limit)
        if($remaining.Count){throw 'Demo MySQL shutdown is still pending. Data is retained; run Stop-Demo again after inspection.'}
    }
}

function Get-DemoDocker {
    $docker=(Get-Command docker.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $result=& $docker context inspect desktop-linux --format '{{json .Endpoints.docker.Host}}'
    if($LASTEXITCODE -ne 0){throw 'Docker Desktop local context is unavailable. Start Docker Desktop manually.'}
    $endpoint=($result -join '') | ConvertFrom-Json
    if($endpoint -notlike 'npipe:////./pipe/*'){throw 'Demo requires a local named-pipe Docker Desktop engine.'}
    $oldPreference=$ErrorActionPreference
    try{$ErrorActionPreference='Continue';$null=& $docker --context desktop-linux info --format '{{.ServerVersion}}' 2>&1;$status=$LASTEXITCODE}
    finally{$ErrorActionPreference=$oldPreference}
    if($status -ne 0){throw 'Docker Desktop engine is unavailable. Start Docker Desktop manually; no application was closed.'}
    return $docker
}

function Get-DemoRedisContainer {
    param($Layout,[string]$Docker)
    $ids=@(& $Docker --context desktop-linux ps --all --quiet --filter 'name=^/campus-lost-found-local-redis-test-1$')
    if($LASTEXITCODE -ne 0){throw 'Cannot inspect project Redis.'}
    if(-not $ids.Count){return $null}
    if($ids.Count -ne 1){throw 'Unexpected project Redis container count.'}
    $data=& $Docker --context desktop-linux inspect $ids[0]
    if($LASTEXITCODE -ne 0){throw 'Cannot inspect project Redis container.'}
    $container=@(($data -join "`n") | ConvertFrom-Json)[0]
    $labels=$container.Config.Labels
    $ports=@($container.HostConfig.PortBindings.'6379/tcp')
    if($labels.'com.docker.compose.project' -ne 'campus-lost-found-local' -or
        $labels.'com.docker.compose.service' -ne 'redis-test' -or
        $labels.'com.docker.compose.project.working_dir' -ne $Layout.Project -or
        $ports.Count -ne 1 -or $ports[0].HostIp -ne '127.0.0.1' -or $ports[0].HostPort -ne '16380'){
        throw 'Redis compose ownership or port mapping does not match this project.'
    }
    return $container
}

function Start-DemoRedis {
    param($Layout,[string]$Docker)
    $container=Get-DemoRedisContainer $Layout $Docker
    if($null -eq $container){
        Assert-DemoPortFree 16380
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Layout.Project 'scripts\dev.ps1') redis-test-up
        if($LASTEXITCODE -ne 0){throw 'Project test Redis did not start.'}
    }elseif(-not $container.State.Running){
        Assert-DemoPortFree 16380
        $null=& $Docker --context desktop-linux start $container.Id
        if($LASTEXITCODE -ne 0){throw 'Owned project test Redis did not start.'}
    }
    $limit=(Get-Date).AddSeconds(45)
    do{
        $container=Get-DemoRedisContainer $Layout $Docker
        if($container -and $container.State.Running -and $container.State.Health.Status -eq 'healthy'){return}
        Start-Sleep -Milliseconds 500
    }while((Get-Date) -lt $limit)
    throw 'Project test Redis did not become healthy.'
}

function Assert-DemoRedisDatabase {
    param([string]$Docker,[string]$Owner)
    $arguments=@('--context','desktop-linux','exec','campus-lost-found-local-redis-test-1','redis-cli','-n','13','--raw')
    $actual=(& $Docker @arguments GET 'demo-runtime:owner') -join ''
    if($LASTEXITCODE -ne 0){throw 'Cannot read demo Redis ownership.'}
    if($actual -eq $Owner){return}
    if(-not [string]::IsNullOrWhiteSpace($actual)){throw 'Redis DB13 belongs to a different demo; nothing was flushed.'}
    $size=(& $Docker @arguments DBSIZE) -join ''
    if($LASTEXITCODE -ne 0 -or $size.Trim() -ne '0'){throw 'Redis DB13 is not empty and has no matching ownership marker; nothing was flushed.'}
    $null=& $Docker @arguments SET 'demo-runtime:owner' $Owner NX
    if($LASTEXITCODE -ne 0){throw 'Cannot reserve demo Redis DB13.'}
    $actual=(& $Docker @arguments GET 'demo-runtime:owner') -join ''
    if($LASTEXITCODE -ne 0 -or $actual -ne $Owner){throw 'Redis DB13 ownership changed during startup.'}
}

function Wait-DemoHttp {
    param([string]$Uri,$Record,[int]$Seconds=90)
    $limit=(Get-Date).AddSeconds($Seconds)
    do{
        if(-not(Test-DemoProcess $Record)){throw 'An owned demo process exited; inspect its protected log.'}
        try{
            $response=Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 2
            if($response.StatusCode -eq 200){return $response.Content}
        }catch{ }
        Start-Sleep -Milliseconds 500
    }while((Get-Date) -lt $limit)
    throw 'Demo endpoint did not become ready in time; inspect protected logs.'
}
