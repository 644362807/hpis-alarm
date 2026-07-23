param(
    [string]$RepoRoot = 'D:\studyProject\hpis2.0\hpis',
    [string]$JavaExe = 'C:\Program Files\Java\jdk1.8.0_321\bin\java.exe',
    [string]$RedisCli = 'D:\javaSwoft\redis\redis-cli.exe',
    [int]$AlarmPort = 18806,
    [int]$PushPort = 8812,
    [int]$Receiver10Port = 19010,
    [int]$Receiver25Port = 19025,
    [int]$StartupTimeoutSeconds = 150,
    [long]$TenantId = 0,
    [string]$NewmanVersion = '6.2.1',
    [switch]$KeepServices
)

$ErrorActionPreference = 'Stop'
$runSeed = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
if ($TenantId -le 0) {
    $TenantId = 700000000L + ($runSeed % 100000000L)
}

$userId = $TenantId * 10L + 1L
$secondUserId = $TenantId * 10L + 2L
$closeUserId = $TenantId * 10L + 3L
$otherTenantId = $TenantId + 1L
$otherUserId = $otherTenantId * 10L + 1L
$deviceAId = $TenantId * 100L + 1L
$deviceBId = $TenantId * 100L + 2L
$normalDeviceId = $TenantId * 100L + 3L
$deviceASn = "CODEX-POSTMAN-A-$runSeed"
$deviceBSn = "CODEX-POSTMAN-B-$runSeed"
$normalDeviceSn = "CODEX-POSTMAN-N-$runSeed"
$deviceAGatewaySn = "CODEX-POSTMAN-GW-A-$runSeed"
$deviceBGatewaySn = "CODEX-POSTMAN-GW-B-$runSeed"
$normalGatewaySn = "CODEX-POSTMAN-GW-N-$runSeed"
$token = 'codex-postman-primary-' + [guid]::NewGuid().ToString('N')
$secondToken = 'codex-postman-second-' + [guid]::NewGuid().ToString('N')
$closeToken = 'codex-postman-close-' + [guid]::NewGuid().ToString('N')
$otherTenantToken = 'codex-postman-other-tenant-' + [guid]::NewGuid().ToString('N')
$dummyWecomSecret = 'codex-dummy-secret-' + [guid]::NewGuid().ToString('N')

$alarmProcess = $null
$pushProcess = $null
$receiverProcess = $null
$redisKeys = New-Object System.Collections.Generic.List[string]
$result = [ordered]@{
    tenantId = $TenantId
    alarmStartup = $false
    pushStartup = $false
    receiverStartup = $false
    newmanExitCode = $null
    requests = 0
    requestFailures = 0
    assertions = 0
    assertionFailures = 0
    report = $null
    alarmLog = $null
    pushLog = $null
    receiverLog = $null
    errors = New-Object System.Collections.Generic.List[string]
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw $Message
    }
}

function Quote-Arg {
    param([string]$Value)
    if ($Value -match '[\s"]') {
        return '"' + ($Value -replace '"', '\"') + '"'
    }
    return $Value
}

function Test-TcpPort {
    param([string]$HostName, [int]$Port, [int]$TimeoutMilliseconds = 500)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne($TimeoutMilliseconds)) {
            return $false
        }
        $client.EndConnect($pending)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Wait-TcpPort {
    param([int]$Port, [int]$TimeoutSeconds = 30)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (Test-TcpPort -HostName '127.0.0.1' -Port $Port) {
            return $true
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Set-RedisJson {
    param([string]$Key, [string]$Json)
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $RedisCli
    $startInfo.Arguments = "-h 127.0.0.1 -p 6379 -x SET $Key"
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.CreateNoWindow = $true
    $redisProcess = New-Object System.Diagnostics.Process
    $redisProcess.StartInfo = $startInfo
    [void]$redisProcess.Start()
    $redisProcess.StandardInput.Write($Json)
    $redisProcess.StandardInput.Close()
    $reply = $redisProcess.StandardOutput.ReadToEnd().Trim()
    $errorText = $redisProcess.StandardError.ReadToEnd().Trim()
    $redisProcess.WaitForExit()
    $exitCode = $redisProcess.ExitCode
    $redisProcess.Dispose()
    Assert-True ($exitCode -eq 0 -and $reply -eq 'OK') "Redis SET failed: key=$Key, reply=$reply, error=$errorText"
    & $RedisCli -h 127.0.0.1 -p 6379 EXPIRE $Key 7200 | Out-Null
    $redisKeys.Add($Key)
}

function Add-LoginContext {
    param([string]$AccessToken, [long]$LoginUserId, [string]$Username, [long]$ContextTenantId = $TenantId)
    $now = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
    $loginJson = (@{
        '@type' = 'com.hpis.system.api.model.LoginUser'
        token = $AccessToken
        userid = $LoginUserId
        username = $Username
        loginTime = $now
        expireTime = $now + 7200000L
        permissions = @('*:*:*')
        roles = @('admin')
        isAdmin = $true
        isTenantAdmin = $true
    } | ConvertTo-Json -Compress)
    $tenantJson = (@{
        '@type' = 'com.hpis.common.core.domain.UserIndustryCacheModel'
        userId = $LoginUserId
        tenantId = $ContextTenantId
        industryId = '1'
        programmeIds = ''
    } | ConvertTo-Json -Compress)
    Set-RedisJson -Key "login_tokens:$AccessToken" -Json $loginJson
    Set-RedisJson -Key "user_tenant:$LoginUserId" -Json $tenantJson
}

function Add-DeviceContext {
    param([long]$DeviceId, [string]$DeviceSn, [string]$GatewaySn)
    $deviceJson = (@{
        '@type' = 'com.hpis.common.core.domain.DeviceKeyInfoDTO'
        deviceId = $DeviceId
        tenantId = $TenantId
        deviceName = "Codex Postman $DeviceSn"
        deviceSn = $DeviceSn
        deviceTypeCode = 'POSTMAN-E2E'
        gatewaySn = $GatewaySn
    } | ConvertTo-Json -Compress)
    Set-RedisJson -Key "device_id2:$DeviceId" -Json $deviceJson
    Set-RedisJson -Key "device_sn2:$DeviceSn" -Json $deviceJson
}

function Start-JavaService {
    param([string]$JarPath, [string]$OutLog, [string]$ErrLog, [string[]]$Arguments)
    Remove-Item -LiteralPath $OutLog, $ErrLog -ErrorAction SilentlyContinue
    $allArguments = @('-Dfile.encoding=UTF-8', '-jar', $JarPath) + $Arguments
    $argumentLine = ($allArguments | ForEach-Object { Quote-Arg $_ }) -join ' '
    return Start-Process -FilePath $JavaExe -ArgumentList $argumentLine `
        -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog `
        -PassThru -WindowStyle Hidden
}

function Wait-ServiceStarted {
    param([string]$LogPath, [string]$StartedPattern)
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $LogPath) {
            $content = Get-Content -LiteralPath $LogPath -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            if ($content -match $StartedPattern) {
                return $true
            }
            if ($content -match 'APPLICATION FAILED TO START|Exception encountered during context initialization') {
                return $false
            }
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Stop-OwnedProcess {
    param([System.Diagnostics.Process]$Process)
    if ($null -eq $Process) {
        return
    }
    try {
        if (-not $Process.HasExited) {
            Stop-Process -Id $Process.Id -Force
        }
    } catch {
        $result.errors.Add("Process cleanup failed for PID=$($Process.Id): $($_.Exception.Message)")
    }
}

$moduleRoot = Join-Path $RepoRoot 'hpis-alarm'
$pushRoot = Join-Path $RepoRoot 'hpis-push'
$deliveryRoot = Get-ChildItem -LiteralPath (Join-Path $moduleRoot 'doc') -Directory |
    Where-Object {
        Test-Path -LiteralPath (Join-Path $_.FullName 'postman\hpis-alarm-push.postman_collection.json')
    } |
    Select-Object -First 1
$collection = if ($deliveryRoot) {
    Join-Path $deliveryRoot.FullName 'postman\hpis-alarm-push.postman_collection.json'
} else {
    Join-Path $moduleRoot 'doc\missing-delivery-package\postman\hpis-alarm-push.postman_collection.json'
}
$environment = if ($deliveryRoot) {
    Join-Path $deliveryRoot.FullName 'postman\hpis-alarm-push.postman_environment.json'
} else {
    Join-Path $moduleRoot 'doc\missing-delivery-package\postman\hpis-alarm-push.postman_environment.json'
}
$receiverScript = Join-Path $moduleRoot 'src\test\resources\scripts\start-alarm-push-http-receiver.ps1'
$reportDir = Join-Path $moduleRoot 'target\alarm-push-postman-e2e'
$reportPath = Join-Path $reportDir "newman-$runSeed.json"
$exportEnvironmentPath = Join-Path $reportDir "environment-$runSeed.json"
$receiverOut = Join-Path $moduleRoot 'target\codex-postman-receiver.out.log'
$receiverErr = Join-Path $moduleRoot 'target\codex-postman-receiver.err.log'
$pushOut = Join-Path $pushRoot 'target\codex-postman-push.out.log'
$pushErr = Join-Path $pushRoot 'target\codex-postman-push.err.log'
$alarmOut = Join-Path $moduleRoot 'target\codex-postman-alarm.out.log'
$alarmErr = Join-Path $moduleRoot 'target\codex-postman-alarm.err.log'
$result.report = $reportPath
$result.alarmLog = $alarmOut
$result.pushLog = $pushOut
$result.receiverLog = $receiverOut

try {
    Assert-True (Test-Path -LiteralPath $JavaExe) "Java 8 not found: $JavaExe"
    Assert-True (Test-Path -LiteralPath $RedisCli) "redis-cli not found: $RedisCli"
    Assert-True (Test-Path -LiteralPath $collection) "Postman collection not found: $collection"
    Assert-True (Test-Path -LiteralPath $environment) "Postman environment not found: $environment"
    Assert-True (Test-TcpPort -HostName '127.0.0.1' -Port 8848) 'Nacos is not reachable at 127.0.0.1:8848'
    foreach ($port in @($AlarmPort, $PushPort, $Receiver10Port, $Receiver25Port)) {
        Assert-True (-not (Test-TcpPort -HostName '127.0.0.1' -Port $port)) "Required test port is already in use: $port"
    }

    Add-LoginContext -AccessToken $token -LoginUserId $userId -Username 'codex-postman-primary'
    Add-LoginContext -AccessToken $secondToken -LoginUserId $secondUserId -Username 'codex-postman-second'
    Add-LoginContext -AccessToken $closeToken -LoginUserId $closeUserId -Username 'codex-postman-close'
    Add-LoginContext -AccessToken $otherTenantToken -LoginUserId $otherUserId `
        -Username 'codex-postman-other-tenant' -ContextTenantId $otherTenantId
    Add-DeviceContext -DeviceId $deviceAId -DeviceSn $deviceASn -GatewaySn $deviceAGatewaySn
    Add-DeviceContext -DeviceId $deviceBId -DeviceSn $deviceBSn -GatewaySn $deviceBGatewaySn
    Add-DeviceContext -DeviceId $normalDeviceId -DeviceSn $normalDeviceSn -GatewaySn $normalGatewaySn

    New-Item -ItemType Directory -Path $reportDir -Force | Out-Null
    Remove-Item -LiteralPath $receiverOut, $receiverErr -ErrorAction SilentlyContinue
    $receiverArgs = "-NoProfile -ExecutionPolicy Bypass -File `"$receiverScript`" -Ports $Receiver10Port,$Receiver25Port"
    $receiverProcess = Start-Process -FilePath 'powershell.exe' -ArgumentList $receiverArgs `
        -RedirectStandardOutput $receiverOut -RedirectStandardError $receiverErr `
        -PassThru -WindowStyle Hidden
    $result.receiverStartup = (Wait-TcpPort -Port $Receiver10Port) -and (Wait-TcpPort -Port $Receiver25Port)
    Assert-True $result.receiverStartup 'HTTP receivers did not start'

    $pushProcess = Start-JavaService `
        -JarPath (Join-Path $pushRoot 'target\hpis-push.jar') `
        -OutLog $pushOut -ErrLog $pushErr `
        -Arguments @(
            "--server.port=$PushPort",
            '--push.wecom.base-url=http://127.0.0.1:19999'
        )
    $result.pushStartup = Wait-ServiceStarted -LogPath $pushOut -StartedPattern 'Started HpisPushApplication'
    Assert-True $result.pushStartup 'hpis-push startup failed or timed out'

    $alarmProcess = Start-JavaService `
        -JarPath (Join-Path $moduleRoot 'target\hpis-alarm.jar') `
        -OutLog $alarmOut -ErrLog $alarmErr `
        -Arguments @(
            "--server.port=$AlarmPort",
            '--push.open=true',
            '--alarm.push.require-matched-config=true',
            '--alarm.internal-test.remote-call-stub-enabled=true',
            '--alarm.internal-test.push-mq-stub-enabled=false'
        )
    $result.alarmStartup = Wait-ServiceStarted -LogPath $alarmOut -StartedPattern 'Started HpisAlarmApplication'
    Assert-True $result.alarmStartup 'hpis-alarm startup failed or timed out'

    $newmanArguments = @(
        '--yes', "newman@$NewmanVersion", 'run', $collection,
        '-e', $environment,
        '--env-var', "alarmBaseUrl=http://127.0.0.1:$AlarmPort",
        '--env-var', "pushBaseUrl=http://127.0.0.1:$PushPort",
        '--env-var', "receiver10BaseUrl=http://127.0.0.1:$Receiver10Port",
        '--env-var', "receiver25BaseUrl=http://127.0.0.1:$Receiver25Port",
        '--env-var', "token=$token",
        '--env-var', "secondAssigneeToken=$secondToken",
        '--env-var', "closePermissionToken=$closeToken",
        '--env-var', "otherTenantToken=$otherTenantToken",
        '--env-var', "tenantId=$TenantId",
        '--env-var', "otherTenantId=$otherTenantId",
        '--env-var', "userId=$userId",
        '--env-var', "secondUserId=$secondUserId",
        '--env-var', "closeUserId=$closeUserId",
        '--env-var', "otherUserId=$otherUserId",
        '--env-var', "deviceAId=$deviceAId",
        '--env-var', "deviceASn=$deviceASn",
        '--env-var', "deviceAGatewaySn=$deviceAGatewaySn",
        '--env-var', "deviceBId=$deviceBId",
        '--env-var', "deviceBSn=$deviceBSn",
        '--env-var', "deviceBGatewaySn=$deviceBGatewaySn",
        '--env-var', "normalDeviceId=$normalDeviceId",
        '--env-var', "normalDeviceSn=$normalDeviceSn",
        '--env-var', "wecomCorpId=codex-dummy-corp-$runSeed",
        '--env-var', "wecomCorpSecret=$dummyWecomSecret",
        '--env-var', 'wecomAgentId=1000002',
        '--env-var', "wecomUserId=codex-primary-$runSeed",
        '--env-var', "secondWecomUserId=codex-second-$runSeed",
        '--env-var', 'workorderConfigId=900',
        '--delay-request', '500',
        '--timeout-request', '30000',
        '--reporters', 'cli,json',
        '--reporter-json-export', $reportPath,
        '--export-environment', $exportEnvironmentPath
    )
    & npx.cmd @newmanArguments
    $result.newmanExitCode = $LASTEXITCODE

    if (Test-Path -LiteralPath $reportPath) {
        $report = Get-Content -Raw -Encoding UTF8 -LiteralPath $reportPath | ConvertFrom-Json
        $result.requests = [int]$report.run.stats.requests.total
        $result.requestFailures = [int]$report.run.stats.requests.failed
        $result.assertions = [int]$report.run.stats.assertions.total
        $result.assertionFailures = [int]$report.run.stats.assertions.failed
    }
    Assert-True ($result.newmanExitCode -eq 0) "Newman failed; see report: $reportPath"
} catch {
    $result.errors.Add($_.Exception.Message)
} finally {
    if ($redisKeys.Count -gt 0) {
        try {
            & $RedisCli -h 127.0.0.1 -p 6379 DEL $redisKeys.ToArray() | Out-Null
        } catch {
            $result.errors.Add("Redis cleanup failed: $($_.Exception.Message)")
        }
    }
    if (-not $KeepServices) {
        Stop-OwnedProcess -Process $alarmProcess
        Stop-OwnedProcess -Process $pushProcess
        Stop-OwnedProcess -Process $receiverProcess
    }
}

$result | ConvertTo-Json -Depth 10
if ($result.errors.Count -gt 0) {
    exit 1
}
