param(
    [string]$RepoRoot = 'D:\studyProject\hpis2.0\hpis',
    [string]$JavaExe = 'C:\Program Files\Java\jdk1.8.0_321\bin\java.exe',
    [string]$RedisCli = 'D:\javaSwoft\redis\redis-cli.exe',
    [int]$AlarmPort = 18806,
    [int]$PushPort = 8812,
    [int]$ReceiverPort = 19010,
    [int]$StartupTimeoutSeconds = 130,
    [long]$TenantId = 799001,
    [long]$OtherTenantId = 799002,
    [long]$DeviceId = 79900101,
    [string]$RabbitManagementUser = 'guest',
    [string]$RabbitManagementPassword = 'guest'
)

$ErrorActionPreference = 'Stop'
$runId = (Get-Date).ToString('yyyyMMddHHmmss')
$deviceSn = "CODEX-E2E-DEVICE-$runId"
$gatewaySn = "CODEX-E2E-GATEWAY-$runId"
$alarmConfigName = "e2e-alarm-$runId"
$pushConfigName = "e2e-push-$runId"
$token = "codex-e2e-token-$runId"
$otherToken = "codex-e2e-other-token-$runId"
$userId = $TenantId
$otherUserId = $OtherTenantId
$alarmBaseUrl = "http://127.0.0.1:$AlarmPort"
$pushBaseUrl = "http://127.0.0.1:$PushPort"
$receiverBaseUrl = "http://127.0.0.1:$ReceiverPort"

$alarmProcess = $null
$pushProcess = $null
$receiverProcess = $null
$alarmConfigId = $null
$pushConfigId = $null
$result = [ordered]@{
    runId = $runId
    alarmStartup = $false
    pushStartup = $false
    orphanCleanup = $false
    alarmCreate = $false
    alarmRead = $false
    alarmUpdate = $false
    pushCreate = $false
    pushRead = $false
    pushEnable = $false
    httpIngressReceived = $false
    mqIngressReceived = $false
    pushDisableStopsDelivery = $false
    tenantIsolation = $false
    pushDelete = $false
    alarmDelete = $false
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

function Set-RedisJson {
    param([string]$Key, [string]$Json)
    # Redirect stdin explicitly so Windows PowerShell cannot strip JSON quotes.
    $startInfo = New-Object -TypeName System.Diagnostics.ProcessStartInfo
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
    $redisReply = $redisProcess.StandardOutput.ReadToEnd().Trim()
    $redisError = $redisProcess.StandardError.ReadToEnd().Trim()
    $redisProcess.WaitForExit()
    $redisExitCode = $redisProcess.ExitCode
    $redisProcess.Dispose()
    Assert-True ($redisExitCode -eq 0 -and $redisReply -eq 'OK') `
        "Redis SET failed: key=$Key, exitCode=$redisExitCode, reply=$redisReply, error=$redisError"
}

function Remove-RedisKeys {
    param([string[]]$Keys)
    if ($Keys.Count -gt 0) {
        & $RedisCli -h 127.0.0.1 -p 6379 DEL $Keys | Out-Null
    }
}

function New-TestHeaders {
    param([string]$AccessToken, [long]$HeaderUserId)
    return @{
        Authorization = "Bearer $AccessToken"
        user_id = [string]$HeaderUserId
        username = 'codex-e2e'
    }
}

function Invoke-JsonApi {
    param(
        [ValidateSet('GET', 'POST', 'PUT', 'DELETE')][string]$Method,
        [string]$Uri,
        [hashtable]$Headers,
        [object]$Body
    )
    $parameters = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        TimeoutSec = 30
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = $Body | ConvertTo-Json -Depth 30 -Compress
    }
    return Invoke-RestMethod @parameters
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
            $content = Get-Content -LiteralPath $LogPath -Raw -ErrorAction SilentlyContinue
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

function Wait-ReceiverCount {
    param([int]$MinimumCount, [int]$TimeoutSeconds = 20)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $snapshot = Invoke-RestMethod -Method Get -Uri "$receiverBaseUrl/_events" -TimeoutSec 5
        if ([int]$snapshot.count -ge $MinimumCount) {
            return $snapshot
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return Invoke-RestMethod -Method Get -Uri "$receiverBaseUrl/_events" -TimeoutSec 5
}

function Remove-TestConfigurations {
    param([hashtable]$Headers)
    $alarmList = Invoke-JsonApi -Method GET `
        -Uri "$alarmBaseUrl/configure/list?pageNum=1&pageSize=100&alarmConfigureName=e2e-alarm" `
        -Headers $Headers
    foreach ($item in @($alarmList.rows)) {
        Invoke-JsonApi -Method DELETE -Uri "$alarmBaseUrl/configure/delete/$($item.alarmConfigureId)" `
            -Headers $Headers | Out-Null
    }

    $pushList = Invoke-JsonApi -Method GET `
        -Uri "$pushBaseUrl/pushConfig/list?pageNum=1&pageSize=100&configName=e2e-push" `
        -Headers $Headers
    foreach ($item in @($pushList.rows)) {
        Invoke-JsonApi -Method DELETE -Uri "$pushBaseUrl/pushConfig/$($item.activePushConfigId)" `
            -Headers $Headers | Out-Null
    }
}

$redisKeys = @(
    "login_tokens:$token",
    "login_tokens:$otherToken",
    "user_tenant:$userId",
    "user_tenant:$otherUserId",
    "device_id2:$DeviceId",
    "device_sn2:$deviceSn"
)

try {
    Assert-True (Test-Path -LiteralPath $JavaExe) "Java 8 not found: $JavaExe"
    Assert-True (Test-Path -LiteralPath $RedisCli) "redis-cli not found: $RedisCli"

    $now = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
    $expire = $now + 7200000
    $loginJson = (@{
        '@type' = 'com.hpis.system.api.model.LoginUser'
        token = $token
        userid = $userId
        username = 'codex-e2e'
        loginTime = $now
        expireTime = $expire
        permissions = @('*:*:*')
        roles = @()
        isAdmin = $true
    } | ConvertTo-Json -Compress)
    $otherLoginJson = (@{
        '@type' = 'com.hpis.system.api.model.LoginUser'
        token = $otherToken
        userid = $otherUserId
        username = 'codex-e2e-other'
        loginTime = $now
        expireTime = $expire
        permissions = @('*:*:*')
        roles = @()
        isAdmin = $true
    } | ConvertTo-Json -Compress)
    $tenantJson = (@{
        '@type' = 'com.hpis.common.core.domain.UserIndustryCacheModel'
        userId = $userId
        tenantId = $TenantId
        industryId = '1'
        programmeIds = ''
    } | ConvertTo-Json -Compress)
    $otherTenantJson = (@{
        '@type' = 'com.hpis.common.core.domain.UserIndustryCacheModel'
        userId = $otherUserId
        tenantId = $OtherTenantId
        industryId = '1'
        programmeIds = ''
    } | ConvertTo-Json -Compress)
    $deviceJson = (@{
        '@type' = 'com.hpis.common.core.domain.DeviceKeyInfoDTO'
        deviceId = $DeviceId
        tenantId = $TenantId
        deviceName = 'Codex E2E Device'
        deviceSn = $deviceSn
        deviceTypeCode = 'E2E'
        gatewaySn = $gatewaySn
    } | ConvertTo-Json -Compress)

    Set-RedisJson -Key "login_tokens:$token" -Json $loginJson
    Set-RedisJson -Key "login_tokens:$otherToken" -Json $otherLoginJson
    Set-RedisJson -Key "user_tenant:$userId" -Json $tenantJson
    Set-RedisJson -Key "user_tenant:$otherUserId" -Json $otherTenantJson
    Set-RedisJson -Key "device_id2:$DeviceId" -Json $deviceJson
    Set-RedisJson -Key "device_sn2:$deviceSn" -Json $deviceJson

    $receiverScript = Join-Path $RepoRoot 'hpis-alarm\src\test\resources\scripts\start-alarm-push-http-receiver.ps1'
    $receiverOut = Join-Path $RepoRoot 'hpis-alarm\target\codex-e2e-receiver.out.log'
    $receiverErr = Join-Path $RepoRoot 'hpis-alarm\target\codex-e2e-receiver.err.log'
    Remove-Item -LiteralPath $receiverOut, $receiverErr -ErrorAction SilentlyContinue
    $receiverProcess = Start-Process -FilePath 'powershell.exe' `
        -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$receiverScript`" -Ports $ReceiverPort" `
        -RedirectStandardOutput $receiverOut -RedirectStandardError $receiverErr `
        -PassThru -WindowStyle Hidden

    $pushOut = Join-Path $RepoRoot 'hpis-push\target\codex-e2e-push.out.log'
    $pushErr = Join-Path $RepoRoot 'hpis-push\target\codex-e2e-push.err.log'
    $pushProcess = Start-JavaService `
        -JarPath (Join-Path $RepoRoot 'hpis-push\target\hpis-push.jar') `
        -OutLog $pushOut -ErrLog $pushErr `
        -Arguments @("--server.port=$PushPort")

    $alarmOut = Join-Path $RepoRoot 'hpis-alarm\target\codex-e2e-alarm.out.log'
    $alarmErr = Join-Path $RepoRoot 'hpis-alarm\target\codex-e2e-alarm.err.log'
    $alarmProcess = Start-JavaService `
        -JarPath (Join-Path $RepoRoot 'hpis-alarm\target\hpis-alarm.jar') `
        -OutLog $alarmOut -ErrLog $alarmErr `
        -Arguments @(
            "--server.port=$AlarmPort",
            '--push.open=true',
            '--alarm.push.require-matched-config=true',
            '--alarm.internal-test.remote-call-stub-enabled=true',
            '--alarm.internal-test.push-mq-stub-enabled=false'
        )

    $result.pushStartup = Wait-ServiceStarted -LogPath $pushOut -StartedPattern 'Started HpisPushApplication'
    $result.alarmStartup = Wait-ServiceStarted -LogPath $alarmOut -StartedPattern 'Started HpisAlarmApplication'
    Assert-True $result.pushStartup 'hpis-push startup failed or timed out'
    Assert-True $result.alarmStartup 'hpis-alarm startup failed or timed out'

    $headers = New-TestHeaders -AccessToken $token -HeaderUserId $userId
    $otherHeaders = New-TestHeaders -AccessToken $otherToken -HeaderUserId $otherUserId
    $receiverReady = Wait-ReceiverCount -MinimumCount 0 -TimeoutSeconds 5
    Invoke-RestMethod -Method Delete -Uri "$receiverBaseUrl/_events" -TimeoutSec 5 | Out-Null

    Remove-TestConfigurations -Headers $headers
    $result.orphanCleanup = $true

    $alarmCreateBody = @{
        alarmConfigureName = $alarmConfigName
        alarmType = '10'
        deviceAlarmControl = '1'
        alarmConfigurePeriod = '0'
        sceneType = '1'
        deviceIds = @($DeviceId)
        pushEnabled = '1'
        pushMessageType = '10'
        workorderConfigId = 0
    }
    $alarmCreate = Invoke-JsonApi -Method POST -Uri "$alarmBaseUrl/configure/add" `
        -Headers $headers -Body $alarmCreateBody
    $result.alarmCreate = $alarmCreate.code -eq 200
    Assert-True $result.alarmCreate "Alarm create failed: $($alarmCreate | ConvertTo-Json -Compress)"

    $alarmList = Invoke-JsonApi -Method GET `
        -Uri "$alarmBaseUrl/configure/list?pageNum=1&pageSize=100&alarmConfigureName=$alarmConfigName" `
        -Headers $headers
    $alarmConfig = @($alarmList.rows) | Where-Object { $_.alarmConfigureName -eq $alarmConfigName } | Select-Object -First 1
    Assert-True ($null -ne $alarmConfig) 'Created alarm configuration was not returned by list API'
    $alarmConfigId = [long]$alarmConfig.alarmConfigureId
    $alarmDetail = Invoke-JsonApi -Method GET -Uri "$alarmBaseUrl/configure/$alarmConfigId" -Headers $headers
    $result.alarmRead = $alarmDetail.code -eq 200 -and `
        $alarmDetail.data.alarmConfigureId -eq $alarmConfigId -and `
        @($alarmDetail.data.deviceIds) -contains $DeviceId
    Assert-True $result.alarmRead 'Alarm list/detail or device binding verification failed'

    $alarmUpdatedName = "$alarmConfigName-u"
    $alarmUpdateBody = $alarmCreateBody.Clone()
    $alarmUpdateBody.alarmConfigureId = $alarmConfigId
    $alarmUpdateBody.alarmConfigureName = $alarmUpdatedName
    $alarmUpdate = Invoke-JsonApi -Method PUT -Uri "$alarmBaseUrl/configure/update" `
        -Headers $headers -Body $alarmUpdateBody
    $alarmDetailAfterUpdate = Invoke-JsonApi -Method GET -Uri "$alarmBaseUrl/configure/$alarmConfigId" -Headers $headers
    $result.alarmUpdate = $alarmUpdate.code -eq 200 -and `
        $alarmDetailAfterUpdate.data.alarmConfigureName -eq $alarmUpdatedName
    Assert-True $result.alarmUpdate 'Alarm update verification failed'

    $pushCreateBody = @{
        messageType = '10'
        pushChannelType = '10'
        enabled = $false
        pushAddress = "127.0.0.1:$ReceiverPort/codex/alarm-10"
        isPassive = '0'
        configName = $pushConfigName
        deviceSns = @($deviceSn)
    }
    $pushCreate = Invoke-JsonApi -Method POST -Uri "$pushBaseUrl/pushConfig/add" `
        -Headers $headers -Body $pushCreateBody
    $result.pushCreate = $pushCreate.code -eq 200
    Assert-True $result.pushCreate "Push create failed: $($pushCreate | ConvertTo-Json -Compress)"

    $pushList = Invoke-JsonApi -Method GET `
        -Uri "$pushBaseUrl/pushConfig/list?pageNum=1&pageSize=100&configName=$pushConfigName" `
        -Headers $headers
    $pushConfig = @($pushList.rows) | Where-Object { $_.configName -eq $pushConfigName } | Select-Object -First 1
    Assert-True ($null -ne $pushConfig) 'Created push configuration was not returned by list API'
    $pushConfigId = [long]$pushConfig.activePushConfigId
    $pushDetail = Invoke-JsonApi -Method GET -Uri "$pushBaseUrl/pushConfig/$pushConfigId" -Headers $headers
    $result.pushRead = $pushDetail.code -eq 200 -and `
        $pushDetail.data.activePushConfigId -eq $pushConfigId -and `
        @($pushDetail.data.deviceSns) -contains $deviceSn
    Assert-True $result.pushRead 'Push list/detail or device binding verification failed'

    $pushEnableBody = $pushCreateBody.Clone()
    $pushEnableBody.activePushConfigId = $pushConfigId
    $pushEnableBody.enabled = $true
    $pushEnableBody.configName = "$pushConfigName-u"
    $pushEnable = Invoke-JsonApi -Method POST -Uri "$pushBaseUrl/pushConfig/update" `
        -Headers $headers -Body $pushEnableBody
    Start-Sleep -Seconds 2
    $pushDetailAfterEnable = Invoke-JsonApi -Method GET -Uri "$pushBaseUrl/pushConfig/$pushConfigId" -Headers $headers
    $result.pushEnable = $pushEnable.code -eq 200 -and `
        $pushDetailAfterEnable.data.enabled -eq $true -and `
        $pushDetailAfterEnable.data.configName -eq "$pushConfigName-u"
    Assert-True $result.pushEnable 'Push enable/update verification failed'

    $httpAlarmBody = @{
        alarmId = "CODEX-E2E-HTTP-$runId"
        deviceSn = $deviceSn
        gatewaySn = $gatewaySn
        alarmType = '10'
        alarmDegree = '1'
        sceneType = '1'
        cameraType = '1'
        tenantId = $TenantId
        time = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    }
    # alarmAdd returns an empty 2xx response; delivery is the business assertion.
    Invoke-JsonApi -Method POST -Uri "$alarmBaseUrl/alarm/alarmAdd" `
        -Headers $headers -Body $httpAlarmBody | Out-Null
    $httpEvents = Wait-ReceiverCount -MinimumCount 1
    $result.httpIngressReceived = [int]$httpEvents.count -ge 1
    Assert-True $result.httpIngressReceived 'HTTP alarm was not delivered to HTTP receiver'

    $mqRawData = @{
        alarmDegree = '1'
        alarmId = "CODEX-E2E-MQ-$runId"
        alarmType = '10'
        cameraType = '1'
        deviceSn = $deviceSn
        gatewaySn = $gatewaySn
        sceneType = '1'
        time = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    }
    $mqEnvelope = @{
        cmd = 'dataSync'
        cmdData = @{
            confItems = 1000
            deviceSn = $gatewaySn
            operCode = 259
            rawData = $mqRawData
            version = 1
        }
        cmdSeq = 1
        servId = 'codex-e2e'
        times = 1
    }
    $publishBody = @{
        properties = @{ content_type = 'application/json'; delivery_mode = 2 }
        routing_key = 'alarm_queue'
        payload = ($mqEnvelope | ConvertTo-Json -Depth 30 -Compress)
        payload_encoding = 'string'
    }
    $securePassword = ConvertTo-SecureString $RabbitManagementPassword -AsPlainText -Force
    $rabbitCredential = New-Object System.Management.Automation.PSCredential($RabbitManagementUser, $securePassword)
    $publishResult = Invoke-RestMethod -Method Post `
        -Uri 'http://127.0.0.1:15672/api/exchanges/%2F/amq.default/publish' `
        -Credential $rabbitCredential -ContentType 'application/json' `
        -Body ($publishBody | ConvertTo-Json -Depth 30 -Compress) -TimeoutSec 20
    Assert-True ($publishResult.routed -eq $true) 'RabbitMQ management API did not route alarm_queue message'
    $mqEvents = Wait-ReceiverCount -MinimumCount 2
    $result.mqIngressReceived = [int]$mqEvents.count -ge 2
    Assert-True $result.mqIngressReceived 'alarm_queue message was not delivered to HTTP receiver'

    $pushDisableBody = $pushEnableBody.Clone()
    $pushDisableBody.enabled = $false
    $pushDisable = Invoke-JsonApi -Method POST -Uri "$pushBaseUrl/pushConfig/update" `
        -Headers $headers -Body $pushDisableBody
    Assert-True ($pushDisable.code -eq 200) 'Push disable failed'
    Start-Sleep -Seconds 2
    $countBeforeDisabledAlarm = [int](Invoke-RestMethod -Method Get -Uri "$receiverBaseUrl/_events").count
    $disabledAlarmBody = $httpAlarmBody.Clone()
    $disabledAlarmBody.alarmId = "CODEX-E2E-DISABLED-$runId"
    $disabledAlarmBody.time = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    Invoke-JsonApi -Method POST -Uri "$alarmBaseUrl/alarm/alarmAdd" `
        -Headers $headers -Body $disabledAlarmBody | Out-Null
    Start-Sleep -Seconds 4
    $countAfterDisabledAlarm = [int](Invoke-RestMethod -Method Get -Uri "$receiverBaseUrl/_events").count
    $result.pushDisableStopsDelivery = $countAfterDisabledAlarm -eq $countBeforeDisabledAlarm
    Assert-True $result.pushDisableStopsDelivery 'Disabled push configuration still delivered a callback'

    $foreignAlarmList = Invoke-JsonApi -Method GET `
        -Uri "$alarmBaseUrl/configure/list?pageNum=1&pageSize=100&alarmConfigureName=e2e-alarm" `
        -Headers $otherHeaders
    $foreignAlarmDetail = Invoke-JsonApi -Method GET -Uri "$alarmBaseUrl/configure/$alarmConfigId" `
        -Headers $otherHeaders
    $foreignPushList = Invoke-JsonApi -Method GET `
        -Uri "$pushBaseUrl/pushConfig/list?pageNum=1&pageSize=100&configName=e2e-push" `
        -Headers $otherHeaders
    $foreignPushDetail = Invoke-JsonApi -Method GET -Uri "$pushBaseUrl/pushConfig/$pushConfigId" `
        -Headers $otherHeaders
    $result.tenantIsolation = @($foreignAlarmList.rows).Count -eq 0 -and `
        $null -eq $foreignAlarmDetail.data -and `
        @($foreignPushList.rows).Count -eq 0 -and `
        $null -eq $foreignPushDetail.data
    Assert-True $result.tenantIsolation 'Cross-tenant list/detail isolation failed'

    $pushDelete = Invoke-JsonApi -Method DELETE -Uri "$pushBaseUrl/pushConfig/$pushConfigId" -Headers $headers
    $pushAfterDelete = Invoke-JsonApi -Method GET -Uri "$pushBaseUrl/pushConfig/$pushConfigId" -Headers $headers
    $result.pushDelete = $pushDelete.code -eq 200 -and $null -eq $pushAfterDelete.data
    Assert-True $result.pushDelete 'Push delete verification failed'
    $pushConfigId = $null

    $alarmDelete = Invoke-JsonApi -Method DELETE -Uri "$alarmBaseUrl/configure/delete/$alarmConfigId" -Headers $headers
    $alarmAfterDelete = Invoke-JsonApi -Method GET -Uri "$alarmBaseUrl/configure/$alarmConfigId" -Headers $headers
    $result.alarmDelete = $alarmDelete.code -eq 200 -and $null -eq $alarmAfterDelete.data
    Assert-True $result.alarmDelete 'Alarm delete verification failed'
    $alarmConfigId = $null
} catch {
    $result.errors.Add($_.Exception.Message)
} finally {
    try {
        $headers = New-TestHeaders -AccessToken $token -HeaderUserId $userId
        if ($pushConfigId) {
            Invoke-JsonApi -Method DELETE -Uri "$pushBaseUrl/pushConfig/$pushConfigId" -Headers $headers | Out-Null
        }
        if ($alarmConfigId) {
            Invoke-JsonApi -Method DELETE -Uri "$alarmBaseUrl/configure/delete/$alarmConfigId" -Headers $headers | Out-Null
        }
    } catch {
        $result.errors.Add("API cleanup failed: $($_.Exception.Message)")
    }

    Remove-RedisKeys -Keys $redisKeys
    foreach ($process in @($alarmProcess, $pushProcess, $receiverProcess)) {
        if ($null -ne $process) {
            try {
                if (-not $process.HasExited) {
                    Stop-Process -Id $process.Id -Force
                }
            } catch {
                $result.errors.Add("Process cleanup failed for PID=$($process.Id): $($_.Exception.Message)")
            }
        }
    }
}

$result | ConvertTo-Json -Depth 10
if ($result.errors.Count -gt 0) {
    exit 1
}
