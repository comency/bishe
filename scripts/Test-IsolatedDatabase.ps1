#requires -Version 5.1
<# Disposable rehearsal instance only. No Windows service changes or existing database connections. #>
[CmdletBinding()]
param([switch]$ConfirmIsolatedRehearsal,[switch]$IncludeServiceBenchmark,[switch]$IncludeLocalModel,[switch]$ConfirmLocalModel,[switch]$IncludeDatabaseOutage,[switch]$IncludeHttpBenchmark,[switch]$IncludeRichHttpData,[string]$CandidateDirectory='')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if (-not $ConfirmIsolatedRehearsal) { throw 'Explicit -ConfirmIsolatedRehearsal required before creating any files or processes.' }
if($CandidateDirectory -and -not $IncludeHttpBenchmark){throw 'Candidate directory requires -IncludeHttpBenchmark.'}
if($IncludeRichHttpData -and -not $IncludeHttpBenchmark){throw 'Rich HTTP data requires -IncludeHttpBenchmark.'}
if($IncludeLocalModel -and (-not $IncludeServiceBenchmark -or -not $ConfirmLocalModel)){throw 'Local model benchmark additionally requires -IncludeServiceBenchmark -ConfirmLocalModel.'}
if($IncludeHttpBenchmark -and ($IncludeServiceBenchmark -or $IncludeLocalModel -or $IncludeDatabaseOutage)){throw 'HTTP benchmark must run alone with AI disabled; do not combine benchmark modes.'}
if(@(Get-ChildItem Env: | Where-Object {$_.Name -like 'SPRING_*' -or $_.Name -in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')}).Count){throw 'Remove inherited Spring/JVM overrides before isolated rehearsal; no values are printed.'}
$projectRoot=Split-Path -Parent $PSScriptRoot
$candidateJar='';$candidateHash='';$candidateRevision=''
if($CandidateDirectory){
    $candidateRoot=(Resolve-Path -LiteralPath $CandidateDirectory).Path
    $releaseRoot=[IO.Path]::GetFullPath((Join-Path $projectRoot '.local\releases'))+'\'
    if(-not $candidateRoot.StartsWith($releaseRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Candidate must be under this project local releases directory.'}
    & node.exe (Join-Path $projectRoot 'scripts\check-release.mjs') $candidateRoot
    if($LASTEXITCODE -ne 0){throw 'Candidate manifest/inventory validation failed.'}
    $candidateManifest=Get-Content -LiteralPath (Join-Path $candidateRoot 'manifest.json') -Raw | ConvertFrom-Json
    $candidateJar=Join-Path $candidateRoot 'backend\app.jar'
    $candidateHash=(Get-FileHash -LiteralPath $candidateJar -Algorithm SHA256).Hash.ToLowerInvariant()
    $candidateRevision=$candidateManifest.revision
}
$mysqlRoot='E:\MySQL\MySQL Server 8.0'
$serverExe=Join-Path $mysqlRoot 'bin\mysqld.exe'
$clientExe=Join-Path $mysqlRoot 'bin\mysql.exe'
$javaRoot='C:\Program Files\Java\jdk-21'
if (-not (Test-Path -LiteralPath $serverExe) -or -not (Test-Path -LiteralPath $clientExe)) { throw 'Existing MySQL binaries required; no download/install.' }
if ((Get-Item -LiteralPath $serverExe).VersionInfo.FileVersion -ne '8.0.41.0') { throw 'Rehearsal currently verified only with the existing MySQL 8.0.41 binary.' }
if (@(Get-NetTCPConnection -LocalPort 13307 -State Listen -ErrorAction SilentlyContinue).Count) { throw 'Dedicated port 13307 occupied. No service will be stopped.' }
if ((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory*1KB -lt 4GB) { throw 'At least 4 GiB free required for bounded isolated rehearsal.' }
if($IncludeHttpBenchmark){
    $redisInfo=docker inspect campus-lost-found-local-redis-test-1 | ConvertFrom-Json
    if($LASTEXITCODE -ne 0 -or $redisInfo.Config.Labels.'com.docker.compose.project' -ne 'campus-lost-found-local' -or $redisInfo.State.Health.Status -ne 'healthy'){throw 'Owned healthy test Redis required; no container will be started or stopped.'}
    $binding=$redisInfo.NetworkSettings.Ports.'6379/tcp'
    if(@($binding).Count -ne 1 -or $binding[0].HostIp -ne '127.0.0.1' -or $binding[0].HostPort -ne '16380'){throw 'Unexpected test Redis port mapping.'}
    $dbSize=docker exec campus-lost-found-local-redis-test-1 redis-cli -n 15 DBSIZE
    if($LASTEXITCODE -ne 0 -or "$dbSize".Trim() -ne '0'){throw 'Redis database 15 must be empty; never flush it. Wait for owned expired counters or inspect ownership.'}
}
$tag=(Get-Date -Format 'yyyyMMddHHmmss')+'_'+[Guid]::NewGuid().ToString('N').Substring(0,8)
$runRoot=Join-Path $projectRoot ('.local\database-rehearsal\'+$tag)
[void](New-Item -ItemType Directory -Path $runRoot)
$identity=[System.Security.Principal.WindowsIdentity]::GetCurrent().User
$acl=New-Object System.Security.AccessControl.DirectorySecurity
$acl.SetOwner($identity); $acl.SetAccessRuleProtection($true,$false)
$acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($identity,'FullControl','ContainerInherit,ObjectInherit','None','Allow')))
Set-Acl -LiteralPath $runRoot -AclObject $acl
$dataRoot=Join-Path $runRoot 'data'
$initLog=Join-Path $runRoot 'initialize-private.log'
$serverLog=Join-Path $runRoot 'server-private.log'
$ownedServer=$null; $ownedChildId=$null; $rootPassword=$null; $temporaryPassword=$null; $saved=@{}; $failure=$null; $forcedStop=$false
function New-Secret {
    $bytes=New-Object byte[] 24; $rng=[System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {$rng.GetBytes($bytes)} finally {$rng.Dispose()}
    return [BitConverter]::ToString($bytes).Replace('-','').ToLowerInvariant()
}
function Run-Sql([string]$Sql,[string]$Password,[switch]$Expired) {
    $info=New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName=$clientExe
    $info.Arguments='--no-defaults --protocol=TCP --host=127.0.0.1 --port=13307 --user=root --connect-timeout=3 --batch --skip-column-names --default-character-set=utf8mb4'
    if($Expired){$info.Arguments+=' --connect-expired-password'}
    $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardInput=$true; $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $info.EnvironmentVariables['MYSQL_PWD']=$Password
    $process=New-Object System.Diagnostics.Process; $process.StartInfo=$info
    try {
        [void]$process.Start(); $out=$process.StandardOutput.ReadToEndAsync(); $err=$process.StandardError.ReadToEndAsync()
        $process.StandardInput.WriteLine($Sql); $process.StandardInput.Close()
        if(-not $process.WaitForExit(15000)){$process.Kill();throw 'Isolated SQL timed out; details withheld.'}
        $answer=$out.GetAwaiter().GetResult(); [void]$err.GetAwaiter().GetResult()
        if($process.ExitCode -ne 0){throw 'Isolated SQL failed; credentials/SQL details withheld.'}
        return $answer.Trim()
    } finally {$process.Dispose()}
}
try {
    # --no-defaults prevents reading the existing Windows-service configuration.
    $initialize=Start-Process -FilePath $serverExe -ArgumentList @('--no-defaults','--initialize',('--basedir="'+$mysqlRoot+'"'),('--datadir="'+$dataRoot+'"'),('--log-error="'+$initLog+'"'),'--innodb-buffer-pool-size=64M') -WindowStyle Hidden -PassThru
    if(-not $initialize.WaitForExit(60000)){$initialize.Kill();throw 'New instance initialization timed out; retained for inspection.'}
    if($initialize.ExitCode -ne 0){throw 'New instance initialization failed; private log retained, never printed.'}
    $match=[regex]::Match((Get-Content -LiteralPath $initLog -Raw),'temporary password is generated for root@localhost: (.+)')
    if(-not $match.Success){throw 'New instance temporary password not found; no startup attempted.'}
    $temporaryPassword=$match.Groups[1].Value.Trim()
    $ownedServer=Start-Process -FilePath $serverExe -ArgumentList @('--no-defaults',('--basedir="'+$mysqlRoot+'"'),('--datadir="'+$dataRoot+'"'),('--log-error="'+$serverLog+'"'),'--port=13307','--bind-address=127.0.0.1','--mysqlx=OFF','--skip-log-bin','--innodb-buffer-pool-size=64M','--innodb-redo-log-capacity=64M','--max-connections=12','--performance-schema=OFF') -WindowStyle Hidden -PassThru
    $ready=$false
    for($i=0;$i -lt 60;$i++){
        if($ownedServer.HasExited){throw 'New instance exited; private log retained.'}
        $listener=Get-NetTCPConnection -LocalPort 13307 -State Listen -ErrorAction SilentlyContinue
        if($listener){
            # MySQL 8.0 on Windows uses a monitor parent plus a listening server child.
            $candidate=Get-CimInstance Win32_Process -Filter ('ProcessId='+$listener.OwningProcess)
            if($candidate -and $candidate.ExecutablePath -eq $serverExe -and $candidate.CommandLine.Contains($dataRoot) -and
                ($candidate.ProcessId -eq $ownedServer.Id -or $candidate.ParentProcessId -eq $ownedServer.Id)){
                $ownedChildId=$candidate.ProcessId; $ready=$true;break
            }
        }
        Start-Sleep -Milliseconds 500
    }
    if(-not $ready){throw 'Owned instance did not bind the dedicated port.'}
    $rootPassword=New-Secret
    [void](Run-Sql "ALTER USER 'root'@'localhost' IDENTIFIED BY '$rootPassword';" $temporaryPassword -Expired)
    $temporaryPassword=$null
    $credential=New-Object System.Management.Automation.PSCredential('root',(ConvertTo-SecureString $rootPassword -AsPlainText -Force))
    $credential | Export-Clixml -LiteralPath (Join-Path $runRoot 'root.credential.xml')
    $instance=(Run-Sql 'SELECT @@port,@@server_uuid,@@datadir;' $rootPassword).Split("`t")
    if($instance[0] -ne '13307' -or [IO.Path]::GetFullPath($instance[2]).TrimEnd('\','/') -ne [IO.Path]::GetFullPath($dataRoot).TrimEnd('\','/')){throw 'Instance identity mismatch; no schemas created.'}
    $prefix='rehearsal_'+$tag+'_'
    $runnerPassword=New-Secret
    [void](Run-Sql "CREATE USER 'rehearsal_runner'@'localhost' IDENTIFIED BY '$runnerPassword';" $rootPassword)
    foreach($suffix in @('empty','legacy','closed','checksum','unmanaged','source','restored','benchmark','outage','httpbenchmark')){
        $schema=$prefix+$suffix
        $grant=$schema.Replace('_','\_')
        [void](Run-Sql ('CREATE DATABASE `'+$schema+'` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON `'+$grant+'`.* TO ''rehearsal_runner''@''localhost'';') $rootPassword)
    }
    $variables=@{
        JAVA_HOME=$javaRoot; PATH=(Join-Path $javaRoot 'bin')+';'+$env:PATH; MAVEN_OPTS='-Xms32m -Xmx192m'
        RUN_MIGRATION_REHEARSAL='true'; REHEARSAL_DB_USERNAME='rehearsal_runner'; REHEARSAL_DB_PASSWORD=$runnerPassword
        REHEARSAL_SCHEMA_PREFIX=$prefix; REHEARSAL_SERVER_UUID=$instance[1]; REHEARSAL_DIRECTORY=$runRoot
        RUN_SERVICE_BENCHMARK=([string][bool]$IncludeServiceBenchmark).ToLowerInvariant()
        RUN_SERVICE_MODEL_BENCHMARK=([string][bool]$IncludeLocalModel).ToLowerInvariant()
        RUN_DB_OUTAGE_REHEARSAL=([string][bool]$IncludeDatabaseOutage).ToLowerInvariant()
        RUN_HTTP_BENCHMARK=([string][bool]$IncludeHttpBenchmark).ToLowerInvariant()
        RUN_HTTP_RICH_DATA=([string][bool]$IncludeRichHttpData).ToLowerInvariant()
        HTTP_CANDIDATE_JAR=$candidateJar; HTTP_CANDIDATE_SHA256=$candidateHash; HTTP_CANDIDATE_REVISION=$candidateRevision
    }
    foreach($name in $variables.Keys){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$variables[$name],'Process')}
    Push-Location -LiteralPath $projectRoot
    try {
        Write-Output ('Running isolated migration/restore checks and explicitly selected benchmarks on own 13307 instance; evidence: '+$runRoot)
        $selectedTests=@('MigrationRehearsalTest')
        if($IncludeServiceBenchmark){$selectedTests+='ServiceLoadBenchmarkTest'}
        if($IncludeDatabaseOutage){$selectedTests+='DatabaseOutageRehearsalTest'}
        if($IncludeHttpBenchmark){$selectedTests+='HttpLoadBenchmarkTest'}
        $testSelection='-Dtest='+($selectedTests -join ',')
        $testHeap=if($IncludeServiceBenchmark){'-DargLine=-Xms64m -Xmx768m'}else{'-DargLine=-Xms32m -Xmx384m'}
        & mvn.cmd -q $testHeap $testSelection test *> (Join-Path $runRoot 'migration-tests.log')
        if($LASTEXITCODE -ne 0){throw 'Selected isolated checks failed; see protected local test log.'}
    } finally {Pop-Location}
    Write-Output 'PASS: all selected isolated checks. No existing MySQL database was connected or altered; HTTP mode uses only test Redis DB15.'
} catch {$failure=$_.Exception.Message; throw}
finally {
    # Also recover an exact owned packaged child if its Java test driver failed abruptly.
    $ownedJar=Join-Path $runRoot 'candidate.jar'
    foreach($child in @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object {
        $_.ExecutablePath -eq (Join-Path $javaRoot 'bin\java.exe') -and $_.CommandLine.Contains($ownedJar)
    })){
        Stop-Process -Id $child.ProcessId
        if(-not $failure){$failure='Packaged child outlived its driver; exact owned process stopped.'}
        $forcedStop=$true
    }
    foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
    if($null -ne $ownedServer){
        $ownedProcesses=@(Get-CimInstance Win32_Process -Filter "Name='mysqld.exe'" | Where-Object {
            $_.ExecutablePath -eq $serverExe -and $_.CommandLine.Contains($dataRoot) -and
            ($_.ProcessId -eq $ownedServer.Id -or $_.ParentProcessId -eq $ownedServer.Id)
        })
        if($ownedProcesses.Count){
            $listener=Get-NetTCPConnection -LocalPort 13307 -State Listen -ErrorAction SilentlyContinue
            if($listener -and $listener.OwningProcess -in $ownedProcesses.ProcessId){
                try {
                    if($rootPassword){[void](Run-Sql 'SHUTDOWN;' $rootPassword)}
                    elseif($temporaryPassword){
                        $rootPassword=New-Secret
                        $recoveryCredential=New-Object System.Management.Automation.PSCredential('root',(ConvertTo-SecureString $rootPassword -AsPlainText -Force))
                        $recoveryCredential | Export-Clixml -LiteralPath (Join-Path $runRoot 'root.credential.xml')
                        [void](Run-Sql "ALTER USER 'root'@'localhost' IDENTIFIED BY '$rootPassword'; SHUTDOWN;" $temporaryPassword -Expired)
                    }
                }catch{Write-Warning 'Owned instance graceful shutdown unconfirmed.'}
            }
            [void]$ownedServer.WaitForExit(15000)
            # On failure, re-validate both monitor and child rather than leaving an orphan.
            foreach($candidate in $ownedProcesses){
                $stillOwned=Get-CimInstance Win32_Process -Filter ('ProcessId='+$candidate.ProcessId)
                if($stillOwned -and $stillOwned.ExecutablePath -eq $serverExe -and $stillOwned.CommandLine.Contains($dataRoot)){
                    Stop-Process -Id $stillOwned.ProcessId; $forcedStop=$true
                    Write-Warning 'Stopped only an exact owned rehearsal process; files retained for recovery inspection.'
                }
            }
        }
    }
    $remaining=@(Get-CimInstance Win32_Process -Filter "Name='mysqld.exe'" | Where-Object {$_.ExecutablePath -eq $serverExe -and $_.CommandLine.Contains($dataRoot)})
    $cleanupFailed=$remaining.Count -gt 0 -or $forcedStop
    if($cleanupFailed -and -not $failure){$failure='Rehearsal assertions passed but graceful shutdown unconfirmed.'}
    [pscustomobject]@{at=(Get-Date).ToString('o');directory=$runRoot;port=13307;failure=$failure;forcedStop=$forcedStop;ownedProcessesRemaining=$remaining.Count;dataRetained=$true;existingInstancesTouched=$false} | ConvertTo-Json | Out-File -LiteralPath (Join-Path $runRoot 'result.json') -Encoding utf8
    Write-Output 'Rehearsal files retained under ignored private .local; no data was deleted.'
    if($cleanupFailed){throw 'Owned rehearsal cleanup was not graceful; inspect retained evidence.'}
}
