#requires -Version 5.1
<# Builds a LOCAL candidate from a fresh committed-source archive. Never deploys, pushes, migrates or starts services. #>
[CmdletBinding()]
param([switch]$CreateCandidate)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if(-not $CreateCandidate){throw 'Explicit -CreateCandidate required before filesystem/build actions.'}
if(@(Get-ChildItem Env: | Where-Object {$_.Name -like 'SPRING_*' -or $_.Name -in @('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')}).Count){throw 'Remove inherited Spring/JVM overrides before candidate build; no values are printed.'}
$projectRoot=Split-Path -Parent $PSScriptRoot
$javaRoot='C:\Program Files\Java\jdk-21'
if(-not (Test-Path -LiteralPath (Join-Path $javaRoot 'bin\javac.exe'))){throw 'Existing JDK21 required.'}
if((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory*1KB -lt 4GB){throw 'At least 4 GiB free required before candidate build.'}
$disk=Get-PSDrive -Name ([IO.Path]::GetPathRoot($projectRoot).Substring(0,1))
if($disk.Free -lt 2GB){throw 'At least 2 GiB disk headroom required; nothing is deleted automatically.'}
Push-Location -LiteralPath $projectRoot
$previous=@{}
try {
    $dirty=git status --porcelain
    if($LASTEXITCODE -ne 0 -or $dirty){throw 'Commit/review the working tree first. Candidate must have an exact clean source revision.'}
    $revision=(git rev-parse HEAD).Trim()
    if($LASTEXITCODE -ne 0 -or $revision -notmatch '^[a-f0-9]{40}$'){throw 'Exact local Git revision required.'}
    foreach($entry in (git ls-tree -r HEAD)){
        if($entry -match '^120000 '){throw 'Tracked symlinks are not allowed in the local source archive.'}
        $name=($entry -split "`t",2)[1]
        if($name -match '(^|/)(\.local|node_modules|target|dist)/|(^|/)\.env($|\.)|\.credential\.xml$|(^|/)id_rsa|\.(pem|pfx|key)$'){
            if($name -ne '.env.example'){throw ('Potential private/generated tracked path blocks packaging: '+$name)}
        }
    }
    $tag=[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')+'-'+$revision.Substring(0,8)+'-'+[Guid]::NewGuid().ToString('N').Substring(0,6)
    $buildRoot=Join-Path $projectRoot ('.local\release-build\'+$tag)
    $candidateRoot=Join-Path $projectRoot ('.local\releases\'+$tag)
    [void](New-Item -ItemType Directory -Path $buildRoot)
    $sourceZip=Join-Path $buildRoot 'source.zip'
    & git archive --format=zip ('--output='+$sourceZip) $revision
    if($LASTEXITCODE -ne 0){throw 'Committed-source archive failed.'}
    $sourceRoot=Join-Path $buildRoot 'source'
    Expand-Archive -LiteralPath $sourceZip -DestinationPath $sourceRoot
    $settings=@{
        JAVA_HOME=$javaRoot; PATH=(Join-Path $javaRoot 'bin')+';'+$env:PATH; MAVEN_OPTS='-Xms32m -Xmx192m'
        RUN_IDENTITY_DB_TESTS='false';RUN_ITEM_DB_TESTS='false';RUN_CLAIM_DB_TESTS='false';RUN_AI_DB_TESTS='false'
        RUN_AI_MODEL_TESTS='false';RUN_MIGRATION_REHEARSAL='false'
        RUN_SERVICE_BENCHMARK='false';RUN_SERVICE_MODEL_BENCHMARK='false'
    }
    foreach($name in $settings.Keys){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process');[Environment]::SetEnvironmentVariable($name,$settings[$name],'Process')}
    function Build-Step([string]$Tool,[string[]]$Arguments,[string]$LogName){
        $priorPreference=$ErrorActionPreference
        try {$ErrorActionPreference='Continue'; & $Tool @Arguments *> (Join-Path $buildRoot $LogName); $result=$LASTEXITCODE}
        finally {$ErrorActionPreference=$priorPreference}
        if($result -ne 0){throw ('Candidate build failed: '+$LogName+'. Logs retained privately; no candidate approved.')}
    }
    Push-Location -LiteralPath $sourceRoot
    try {
        Write-Output ('Fresh source '+$revision+'; bounded offline backend tests and build. No application service startup.')
        Build-Step 'mvn.cmd' @('-q','-DargLine=-Xms32m -Xmx384m','verify') 'backend-verify.log'
        Build-Step 'node.exe' @('--test','scripts/tests/release-manifest.test.mjs') 'release-tools-tests.log'
        Push-Location -LiteralPath (Join-Path $sourceRoot 'frontend')
        try {
            Write-Output 'Installing only locked frontend dependencies in the NEW snapshot directory; existing node_modules is untouched.'
            Build-Step 'npm.cmd' @('ci','--no-audit','--no-fund') 'frontend-ci.log'
            Build-Step 'npm.cmd' @('run','verify') 'frontend-verify.log'
        } finally {Pop-Location}
    } finally {Pop-Location}
    $tests=0;$skipped=0;$failures=0;$errors=0
    $reports=@(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'target\surefire-reports') -Filter 'TEST-*.xml')
    if(-not $reports.Count){throw 'Backend machine-readable test evidence missing.'}
    foreach($file in $reports){[xml]$report=Get-Content -LiteralPath $file.FullName;$tests+=[int]$report.testsuite.tests;$skipped+=[int]$report.testsuite.skipped;$failures+=[int]$report.testsuite.failures;$errors+=[int]$report.testsuite.errors}
    if($tests -le $skipped -or $failures -ne 0 -or $errors -ne 0){throw 'Backend test summary is not successful.'}
    if((git rev-parse HEAD).Trim() -ne $revision -or (git status --porcelain)){throw 'Source revision/worktree changed during build; no candidate finalized.'}
    [void](New-Item -ItemType Directory -Path (Join-Path $candidateRoot 'backend'))
    Copy-Item -LiteralPath (Join-Path $sourceRoot 'target\lost-found-ai-1.0.0.jar') -Destination (Join-Path $candidateRoot 'backend\app.jar')
    Copy-Item -LiteralPath (Join-Path $sourceRoot 'frontend\dist') -Destination (Join-Path $candidateRoot 'frontend') -Recurse
    Copy-Item -LiteralPath $sourceZip -Destination (Join-Path $candidateRoot 'source.zip')
    Copy-Item -LiteralPath (Join-Path $sourceRoot 'README.md') -Destination $candidateRoot
    Copy-Item -LiteralPath (Join-Path $sourceRoot 'infra\RELEASE-CHECKLIST.md') -Destination $candidateRoot
    $metadata=[ordered]@{
        revision=$revision;kind='local-candidate';productionApproved=$false;builtAt=[DateTime]::UtcNow.ToString('o')
        sourceSnapshotBuild=$true;frontendVerified=$true
        backendTests=@{tests=$tests;passed=$tests-$skipped;skipped=$skipped;failures=$failures;errors=$errors}
        nodeVersion=(& node.exe --version);npmVersion=(& npm.cmd --version)
        javaBinaryVersion=(Get-Item -LiteralPath (Join-Path $javaRoot 'bin\java.exe')).VersionInfo.FileVersion
        sourceArchiveSha256=(Get-FileHash -LiteralPath $sourceZip -Algorithm SHA256).Hash.ToLowerInvariant()
        frontendLockSha256=(Get-FileHash -LiteralPath (Join-Path $sourceRoot 'frontend\package-lock.json') -Algorithm SHA256).Hash.ToLowerInvariant()
        limitations=@('Not deployed or production-approved','Database/model/rehearsal tests skipped during packaging; separate evidence required','No byte-for-byte reproducible-build claim','Manifest hashes are integrity checks, not a digital signature')
    }
    $metadataFile=Join-Path $buildRoot 'build-metadata.json'
    $metadata | ConvertTo-Json -Depth 6 | Out-File -LiteralPath $metadataFile -Encoding utf8
    & node.exe (Join-Path $sourceRoot 'scripts\write-release-manifest.mjs') --confirm-local-candidate $candidateRoot $metadataFile
    if($LASTEXITCODE -ne 0){throw 'Candidate integrity verification failed. Do not distribute incomplete candidate.'}
    Write-Output ('LOCAL CANDIDATE ONLY: '+$candidateRoot)
    Write-Output ('Build evidence retained: '+$buildRoot)
} finally {
    foreach($name in $previous.Keys){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}
    Pop-Location
}
