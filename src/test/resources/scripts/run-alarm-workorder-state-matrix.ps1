param(
    [string]$RepoRoot = 'D:\studyProject\hpis2.0\hpis',
    [string]$JavaExe = 'C:\Program Files\Java\jdk1.8.0_321\bin\java.exe',
    [string]$RedisCli = 'D:\javaSwoft\redis\redis-cli.exe',
    [int]$AlarmPort = 18806,
    [int]$PushPort = 8812,
    [int]$AlarmReceiverPort = 19010,
    [int]$WorkorderReceiverPort = 19025,
    [int]$StartupTimeoutSeconds = 130,
    [long]$TenantId = 799101,
    [long]$OtherTenantId = 799102,
    [long]$UserAId = 79910101,
    [long]$UserBId = 79910102,
    [long]$OtherUserId = 79910201,
    [long]$DeviceId = 79910101,
    [string]$RabbitManagementUser = 'guest',
    [string]$RabbitManagementPassword = 'guest',
    [switch]$PreflightOnly,
    [switch]$UseSingleConsumerRollback,
    [switch]$KeepServices
)

$ErrorActionPreference = 'Stop'
$runId = (Get-Date).ToString('yyyyMMddHHmmss')
$moduleRoot = Join-Path $RepoRoot 'hpis-alarm'
$runRoot = Join-Path $moduleRoot "target\alarm-workorder-state-matrix\$runId"
$evidenceRoot = Join-Path $runRoot 'evidence'
$negativeRoot = Join-Path $runRoot 'negative'
$logRoot = Join-Path $runRoot 'logs'
$receiverRoot = Join-Path $runRoot 'receiver'
$alarmBaseUrl = "http://127.0.0.1:$AlarmPort"
$pushBaseUrl = "http://127.0.0.1:$PushPort"
$alarmReceiverUrl = "http://127.0.0.1:$AlarmReceiverPort"
$workorderReceiverUrl = "http://127.0.0.1:$WorkorderReceiverPort"
$deviceSn = "CODEX-MATRIX-DEVICE-$runId"
$gatewaySn = "CODEX-MATRIX-GATEWAY-$runId"
$alarmConfigName = "matrix-alarm-$runId"
$alarmPushConfigName = "matrix-push-alarm-$runId"
$workorderPushConfigName = "matrix-push-workorder-$runId"
$tokenA = "codex-matrix-a-$runId"
$tokenB = "codex-matrix-b-$runId"
$tokenOther = "codex-matrix-other-$runId"
$alarmProcess = $null
$pushProcess = $null
$receiverProcess = $null
$alarmConfigId = $null
$pushConfigIds = New-Object System.Collections.Generic.List[long]
$scenarioRows = New-Object System.Collections.Generic.List[object]
$negativeRows = New-Object System.Collections.Generic.List[object]
$warnings = New-Object System.Collections.Generic.List[string]
$fatalErrors = New-Object System.Collections.Generic.List[string]
$alarms = @{}
$workorders = @{}

New-Item -ItemType Directory -Force -Path $evidenceRoot, $negativeRoot, $logRoot, $receiverRoot | Out-Null

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Test-TcpPort {
    param([string]$HostName, [int]$Port)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $task = $client.ConnectAsync($HostName, $Port)
        return $task.Wait(1200) -and $client.Connected
    } catch { return $false }
    finally { $client.Dispose() }
}

function Quote-Arg {
    param([string]$Value)
    if ($Value -match '[\s"]') { return '"' + ($Value -replace '"', '\"') + '"' }
    return $Value
}

function Write-JsonFile {
    param([string]$Path, [object]$Value)
    $Value | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Save-ApiEvidence {
    param([string]$Folder, [string]$Name, [string]$Method, [string]$Uri, [object]$Body, [object]$Response)
    $safeUri = $Uri -replace [regex]::Escape($alarmBaseUrl), '{alarmBaseUrl}' `
                   -replace [regex]::Escape($pushBaseUrl), '{pushBaseUrl}'
    Write-JsonFile -Path (Join-Path $Folder "$Name.json") -Value ([ordered]@{
        at = [DateTimeOffset]::Now.ToString('o')
        method = $Method
        uri = $safeUri
        body = $Body
        response = $Response
    })
}

function Add-ScenarioResult {
    param([string]$Id, [bool]$Passed, [string]$Detail)
    $scenarioRows.Add([pscustomobject]@{ id=$Id; result=if($Passed){'PASS'}else{'FAIL'}; detail=$Detail })
}

function Add-NegativeResult {
    param([string]$Id, [bool]$Passed, [string]$Detail)
    $negativeRows.Add([pscustomobject]@{ id=$Id; result=if($Passed){'PASS'}else{'FAIL'}; detail=$Detail })
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
    Assert-True ($exitCode -eq 0 -and $reply -eq 'OK') "Redis SET failed: key=$Key, error=$errorText"
}

function Remove-RedisKeys {
    param([string[]]$Keys)
    if ($Keys.Count -gt 0 -and (Test-Path -LiteralPath $RedisCli)) {
        & $RedisCli -h 127.0.0.1 -p 6379 DEL $Keys | Out-Null
    }
}

function New-TestHeaders {
    param([string]$Token, [long]$UserId, [string]$Username)
    return @{ Authorization="Bearer $Token"; user_id=[string]$UserId; username=$Username }
}

function Invoke-JsonApi {
    param(
        [ValidateSet('GET','POST','PUT','DELETE')][string]$Method,
        [string]$Uri,
        [hashtable]$Headers,
        [object]$Body,
        [string]$EvidenceFolder,
        [string]$EvidenceName
    )
    $parameters = @{ Method=$Method; Uri=$Uri; Headers=$Headers; TimeoutSec=30 }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = $Body | ConvertTo-Json -Depth 30 -Compress
    }
    try {
        $response = Invoke-RestMethod @parameters
    } catch {
        $response = [ordered]@{ code=-1; msg=$_.Exception.Message }
    }
    if ($EvidenceFolder -and $EvidenceName) {
        Save-ApiEvidence -Folder $EvidenceFolder -Name $EvidenceName -Method $Method -Uri $Uri -Body $Body -Response $response
    }
    return $response
}

function Start-JavaService {
    param([string]$JarPath, [string]$OutLog, [string]$ErrLog, [string[]]$Arguments)
    $allArguments = @('-Dfile.encoding=UTF-8','-jar',$JarPath) + $Arguments
    $argumentLine = ($allArguments | ForEach-Object { Quote-Arg $_ }) -join ' '
    return Start-Process -FilePath $JavaExe -ArgumentList $argumentLine `
        -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog `
        -PassThru -WindowStyle Hidden
}

function Wait-ServiceStarted {
    param([string]$LogPath, [string]$Pattern)
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Path -LiteralPath $LogPath) {
            $content = Get-Content -LiteralPath $LogPath -Raw -ErrorAction SilentlyContinue
            if ($content -match $Pattern) { return $true }
            if ($content -match 'APPLICATION FAILED TO START|Exception encountered during context initialization') { return $false }
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

function Publish-AlarmMq {
    param([string]$Cid, [ValidateSet('start','stop')][string]$Kind, [int]$Sequence)
    $rawData = [ordered]@{
        alarmId=$Cid
        deviceSn=$deviceSn
        gatewaySn=$gatewaySn
        time=(Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    }
    if ($Kind -eq 'start') {
        $rawData.alarmDegree = '1'
        $rawData.alarmType = '10'
        $rawData.cameraType = '1'
        $rawData.sceneType = '1'
    }
    $envelope = [ordered]@{
        cmd='dataSync'
        cmdData=[ordered]@{ confItems=1000; deviceSn=$gatewaySn; operCode=if($Kind -eq 'start'){259}else{260}; rawData=$rawData; version=1 }
        cmdSeq=$Sequence
        servId='alarm-real-state-test'
        times=1
    }
    $publishBody = [ordered]@{
        properties=@{ content_type='application/json'; delivery_mode=2 }
        routing_key='alarm_queue'
        payload=($envelope | ConvertTo-Json -Depth 30 -Compress)
        payload_encoding='string'
    }
    $securePassword = ConvertTo-SecureString $RabbitManagementPassword -AsPlainText -Force
    $credential = New-Object System.Management.Automation.PSCredential($RabbitManagementUser,$securePassword)
    $response = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:15672/api/exchanges/%2F/amq.default/publish' `
        -Credential $credential -ContentType 'application/json' `
        -Body ($publishBody | ConvertTo-Json -Depth 30 -Compress) -TimeoutSec 20
    Save-ApiEvidence -Folder $evidenceRoot -Name ("mq-$Kind-$Cid") -Method 'RABBIT_PUBLISH' `
        -Uri 'amq.default/alarm_queue' -Body $envelope -Response $response
    Assert-True ($response.routed -eq $true) "RabbitMQ did not route $Kind event for $Cid"
}

function Get-AlarmByCid {
    param([string]$Cid, [hashtable]$Headers)
    $response = Invoke-JsonApi -Method GET -Uri "$alarmBaseUrl/alarm/list?pageNum=1&pageSize=200&deviceSn=$deviceSn" -Headers $Headers
    $diagnosticPath = Join-Path $evidenceRoot 'diagnostic-alarm-list-first-response.json'
    if (-not (Test-Path -LiteralPath $diagnosticPath)) {
        Write-JsonFile -Path $diagnosticPath -Value $response
    }
    return @($response.rows) | Where-Object { $_.alarmCid -eq $Cid } | Select-Object -First 1
}

function Wait-AlarmByCid {
    param([string]$Cid, [hashtable]$Headers, [int]$TimeoutSeconds=35)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $alarm = Get-AlarmByCid -Cid $Cid -Headers $Headers
        if ($null -ne $alarm) { return $alarm }
        Start-Sleep -Milliseconds 600
    } while ((Get-Date) -lt $deadline)
    return $null
}

function Get-AlarmById {
    param([long]$AlarmId, [hashtable]$Headers)
    $response = Invoke-JsonApi -Method GET -Uri "$alarmBaseUrl/alarm/query/$AlarmId" -Headers $Headers
    return $response.data
}

function Wait-AlarmState {
    param([long]$AlarmId, [hashtable]$Headers, [string]$AlarmStatus, [string]$HandleStatus, [int]$TimeoutSeconds=35)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $alarm = Get-AlarmById -AlarmId $AlarmId -Headers $Headers
        if ($null -ne $alarm -and [string]$alarm.alarmStatus -eq $AlarmStatus -and [string]$alarm.handleStatus -eq $HandleStatus) { return $alarm }
        Start-Sleep -Milliseconds 600
    } while ((Get-Date) -lt $deadline)
    return $alarm
}

function Get-WorkorderByAlarmId {
    param([long]$AlarmId, [hashtable]$Headers)
    $response = Invoke-JsonApi -Method GET -Uri "$alarmBaseUrl/workorder/list?pageNum=1&pageSize=20&alarmId=$AlarmId" -Headers $Headers
    return @($response.rows) | Where-Object { [long]$_.alarmId -eq $AlarmId } | Select-Object -First 1
}

function Wait-WorkorderState {
    param([long]$WorkorderId, [hashtable]$Headers, [string]$Status, [int]$TimeoutSeconds=35)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $response = Invoke-JsonApi -Method GET -Uri "$alarmBaseUrl/workorder/$WorkorderId" -Headers $Headers
        $workorder = $response.data
        if ($null -ne $workorder -and [string]$workorder.status -eq $Status) { return $workorder }
        Start-Sleep -Milliseconds 600
    } while ((Get-Date) -lt $deadline)
    return $workorder
}

function Confirm-Alarm {
    param([string]$Scenario, [long]$AlarmId, [hashtable]$Headers)
    $body = @{ alarmId=$AlarmId; alarmIds=@($AlarmId); handleStatus='2'; opinion="matrix confirm $Scenario" }
    $response = Invoke-JsonApi -Method POST -Uri "$alarmBaseUrl/handle/update" -Headers $Headers -Body $body `
        -EvidenceFolder $evidenceRoot -EvidenceName "$Scenario-confirm"
    Assert-True ($response.code -eq 200) "$Scenario confirm failed: $($response.msg)"
    $alarm = Wait-AlarmState -AlarmId $AlarmId -Headers $Headers -AlarmStatus '0' -HandleStatus '2'
    Assert-True ($null -ne $alarm -and [string]$alarm.handleStatus -eq '2') "$Scenario confirm state was not persisted"
}

function Handle-Alarm {
    param([string]$Scenario, [long]$AlarmId, [hashtable]$Headers, [string]$Identify='0', [switch]$MissingPicture, [string]$Folder=$evidenceRoot)
    $body = [ordered]@{ alarmId=$AlarmId; identify=$Identify; opinion="matrix handle $Scenario" }
    if (-not $MissingPicture) { $body.handlePicture = "/test/alarm-state/$runId/$Scenario.jpg" }
    return Invoke-JsonApi -Method POST -Uri "$alarmBaseUrl/handle/save" -Headers $Headers -Body $body `
        -EvidenceFolder $Folder -EvidenceName "$Scenario-handle"
}

function Create-Workorder {
    param([string]$Scenario, [long]$AlarmId, [hashtable]$Headers, [ValidateSet('NULL','GROUP','DIRECT')][string]$Mode, [long]$AssigneeId=0, [string]$AssigneeName='')
    $body = [ordered]@{ alarmId=$AlarmId; title="$Scenario reminder"; content="matrix $Scenario" }
    if ($Mode -eq 'GROUP') { $body.assigneeId=0 }
    if ($Mode -eq 'DIRECT') { $body.assigneeId=$AssigneeId; $body.assigneeName=$AssigneeName }
    $response = Invoke-JsonApi -Method POST -Uri "$alarmBaseUrl/workorder" -Headers $Headers -Body $body `
        -EvidenceFolder $evidenceRoot -EvidenceName "$Scenario-workorder-create"
    if ($response.code -eq 200) {
        Start-Sleep -Milliseconds 600
        $workorder = Get-WorkorderByAlarmId -AlarmId $AlarmId -Headers $Headers
        if ($null -ne $workorder) { $workorders[$Scenario] = $workorder }
    }
    return [pscustomobject]@{ response=$response; workorder=$workorder }
}

function Get-ReceiverSnapshot {
    param([string]$BaseUrl)
    return Invoke-RestMethod -Method Get -Uri "$BaseUrl/_events" -TimeoutSec 10
}

function Wait-ReceiverCount {
    param([string]$BaseUrl, [int]$MinimumCount, [int]$TimeoutSeconds=30)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $snapshot = Get-ReceiverSnapshot -BaseUrl $BaseUrl
        if ([int]$snapshot.count -ge $MinimumCount) { return $snapshot }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    return $snapshot
}

function Test-MyContains {
    param([long]$AlarmId, [hashtable]$Headers)
    $response = Invoke-JsonApi -Method GET -Uri "$alarmBaseUrl/workorder/my?pageNum=1&pageSize=100&alarmId=$AlarmId" -Headers $Headers
    return @($response.rows | Where-Object { [long]$_.alarmId -eq $AlarmId }).Count -eq 1
}

function Remove-TestConfigurations {
    param([hashtable]$Headers)
    foreach ($configId in @($pushConfigIds)) {
        try { Invoke-JsonApi -Method DELETE -Uri "$pushBaseUrl/pushConfig/$configId" -Headers $Headers | Out-Null } catch {}
    }
    if ($alarmConfigId) {
        try { Invoke-JsonApi -Method DELETE -Uri "$alarmBaseUrl/configure/delete/$alarmConfigId" -Headers $Headers | Out-Null } catch {}
    }
}

$redisKeys = @(
    "login_tokens:$tokenA", "login_tokens:$tokenB", "login_tokens:$tokenOther",
    "user_tenant:$UserAId", "user_tenant:$UserBId", "user_tenant:$OtherUserId",
    "device_id2:$DeviceId", "device_sn2:$deviceSn"
)

try {
    $preflight = [ordered]@{
        java8 = (Test-Path -LiteralPath $JavaExe)
        redisCli = (Test-Path -LiteralPath $RedisCli)
        alarmJar = (Test-Path -LiteralPath (Join-Path $moduleRoot 'target\hpis-alarm.jar'))
        pushJar = (Test-Path -LiteralPath (Join-Path $RepoRoot 'hpis-push\target\hpis-push.jar'))
        nacos = (Test-TcpPort '127.0.0.1' 8848)
        redis = (Test-TcpPort '127.0.0.1' 6379)
        rabbit = (Test-TcpPort '127.0.0.1' 5672)
        rabbitManagement = (Test-TcpPort '127.0.0.1' 15672)
        mysql = (Test-TcpPort '127.0.0.1' 3306)
        alarmPortFree = -not (Test-TcpPort '127.0.0.1' $AlarmPort)
        pushPortFree = -not (Test-TcpPort '127.0.0.1' $PushPort)
        receiverPortsFree = (-not (Test-TcpPort '127.0.0.1' $AlarmReceiverPort)) -and (-not (Test-TcpPort '127.0.0.1' $WorkorderReceiverPort))
    }
    Write-JsonFile -Path (Join-Path $runRoot 'preflight.json') -Value $preflight
    $failedPreflight = @($preflight.GetEnumerator() | Where-Object { -not $_.Value })
    Assert-True ($failedPreflight.Count -eq 0) "Preflight failed: $(@($failedPreflight.Name) -join ', ')"
    if ($PreflightOnly) { $preflight | ConvertTo-Json; exit 0 }

    $now = [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
    $expire = $now + 7200000
    $users = @(
        @{ Token=$tokenA; UserId=$UserAId; Tenant=$TenantId; Username='matrix-user-a' },
        @{ Token=$tokenB; UserId=$UserBId; Tenant=$TenantId; Username='matrix-user-b' },
        @{ Token=$tokenOther; UserId=$OtherUserId; Tenant=$OtherTenantId; Username='matrix-other' }
    )
    foreach ($user in $users) {
        $loginJson = (@{
            '@type'='com.hpis.system.api.model.LoginUser'; token=$user.Token; userid=$user.UserId;
            username=$user.Username; loginTime=$now; expireTime=$expire; permissions=@('*:*:*'); roles=@(); isAdmin=$true
        } | ConvertTo-Json -Compress)
        $tenantJson = (@{
            '@type'='com.hpis.common.core.domain.UserIndustryCacheModel'; userId=$user.UserId;
            tenantId=$user.Tenant; industryId='1'; programmeIds=''
        } | ConvertTo-Json -Compress)
        Set-RedisJson -Key "login_tokens:$($user.Token)" -Json $loginJson
        Set-RedisJson -Key "user_tenant:$($user.UserId)" -Json $tenantJson
    }
    $deviceJson = (@{
        '@type'='com.hpis.common.core.domain.DeviceKeyInfoDTO'; deviceId=$DeviceId; tenantId=$TenantId;
        deviceName='Codex Matrix Device'; deviceSn=$deviceSn; deviceTypeCode='MATRIX'; gatewaySn=$gatewaySn
    } | ConvertTo-Json -Compress)
    Set-RedisJson -Key "device_id2:$DeviceId" -Json $deviceJson
    Set-RedisJson -Key "device_sn2:$deviceSn" -Json $deviceJson

    $headersA = New-TestHeaders -Token $tokenA -UserId $UserAId -Username 'matrix-user-a'
    $headersB = New-TestHeaders -Token $tokenB -UserId $UserBId -Username 'matrix-user-b'
    $headersOther = New-TestHeaders -Token $tokenOther -UserId $OtherUserId -Username 'matrix-other'

    $receiverScript = Join-Path $moduleRoot 'src\test\resources\scripts\start-alarm-push-http-receiver.ps1'
    $receiverProcess = Start-Process -FilePath 'powershell.exe' `
        -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$receiverScript`" -Ports $AlarmReceiverPort,$WorkorderReceiverPort -OutputDirectory `"$receiverRoot`"" `
        -RedirectStandardOutput (Join-Path $logRoot 'receiver.out.log') `
        -RedirectStandardError (Join-Path $logRoot 'receiver.err.log') -PassThru -WindowStyle Hidden

    $pushProcess = Start-JavaService -JarPath (Join-Path $RepoRoot 'hpis-push\target\hpis-push.jar') `
        -OutLog (Join-Path $logRoot 'push.out.log') -ErrLog (Join-Path $logRoot 'push.err.log') `
        -Arguments @("--server.port=$PushPort")
    $alarmArguments = @(
        "--server.port=$AlarmPort", '--push.open=true', '--alarm.push.require-matched-config=true',
        '--alarm.internal-test.remote-call-stub-enabled=true', '--alarm.internal-test.push-mq-stub-enabled=false'
    )
    $previousSpringApplicationJson = $env:SPRING_APPLICATION_JSON
    try {
        if ($UseSingleConsumerRollback) {
            $env:SPRING_APPLICATION_JSON = '{"alarm":{"batch":{"insert-consumer-batch-enabled":false}}}'
        }
        $alarmProcess = Start-JavaService -JarPath (Join-Path $moduleRoot 'target\hpis-alarm.jar') `
            -OutLog (Join-Path $logRoot 'alarm.out.log') -ErrLog (Join-Path $logRoot 'alarm.err.log') `
            -Arguments $alarmArguments
    } finally {
        if ($null -eq $previousSpringApplicationJson) {
            Remove-Item Env:SPRING_APPLICATION_JSON -ErrorAction SilentlyContinue
        } else {
            $env:SPRING_APPLICATION_JSON = $previousSpringApplicationJson
        }
    }
    Assert-True (Wait-ServiceStarted -LogPath (Join-Path $logRoot 'push.out.log') -Pattern 'Started HpisPushApplication') 'Push startup failed'
    Assert-True (Wait-ServiceStarted -LogPath (Join-Path $logRoot 'alarm.out.log') -Pattern 'Started HpisAlarmApplication') 'Alarm startup failed'
    [void](Wait-ReceiverCount -BaseUrl $alarmReceiverUrl -MinimumCount 0 -TimeoutSeconds 5)
    Invoke-RestMethod -Method Delete -Uri "$alarmReceiverUrl/_events" -TimeoutSec 5 | Out-Null
    Invoke-RestMethod -Method Delete -Uri "$workorderReceiverUrl/_events" -TimeoutSec 5 | Out-Null

    foreach ($pushDefinition in @(
        @{ Name=$alarmPushConfigName; MessageType='10'; Port=$AlarmReceiverPort; Path='alarm-10' },
        @{ Name=$workorderPushConfigName; MessageType='25'; Port=$WorkorderReceiverPort; Path='workorder-25' }
    )) {
        $body = @{
            messageType=$pushDefinition.MessageType; pushChannelType='10'; enabled=$true;
            pushAddress="127.0.0.1:$($pushDefinition.Port)/matrix/$($pushDefinition.Path)";
            isPassive='0'; configName=$pushDefinition.Name; deviceSns=@($deviceSn)
        }
        $create = Invoke-JsonApi -Method POST -Uri "$pushBaseUrl/pushConfig/add" -Headers $headersA -Body $body `
            -EvidenceFolder $evidenceRoot -EvidenceName "push-$($pushDefinition.MessageType)-create"
        Assert-True ($create.code -eq 200) "Push config $($pushDefinition.MessageType) create failed"
        $list = Invoke-JsonApi -Method GET -Uri "$pushBaseUrl/pushConfig/list?pageNum=1&pageSize=100&configName=$($pushDefinition.Name)" -Headers $headersA
        $created = @($list.rows) | Where-Object { $_.configName -eq $pushDefinition.Name } | Select-Object -First 1
        Assert-True ($null -ne $created) "Push config $($pushDefinition.MessageType) lookup failed"
        $pushConfigIds.Add([long]$created.activePushConfigId)
    }

    $alarmConfigBody = @{
        alarmConfigureName=$alarmConfigName; alarmType='10'; deviceAlarmControl='1'; alarmConfigurePeriod='0';
        sceneType='1'; deviceIds=@($DeviceId); pushEnabled='1'; pushMessageType='10';
        workorderPushMessageType='25'; workorderConfigId=900001
    }
    $alarmConfigCreate = Invoke-JsonApi -Method POST -Uri "$alarmBaseUrl/configure/add" -Headers $headersA -Body $alarmConfigBody `
        -EvidenceFolder $evidenceRoot -EvidenceName 'alarm-config-create'
    Assert-True ($alarmConfigCreate.code -eq 200) "Alarm config create failed: $($alarmConfigCreate.msg)"
    $alarmConfigList = Invoke-JsonApi -Method GET -Uri "$alarmBaseUrl/configure/list?pageNum=1&pageSize=100&alarmConfigureName=$alarmConfigName" -Headers $headersA
    $createdAlarmConfig = @($alarmConfigList.rows) | Where-Object { $_.alarmConfigureName -eq $alarmConfigName } | Select-Object -First 1
    Assert-True ($null -ne $createdAlarmConfig) 'Alarm config lookup failed'
    $alarmConfigId = [long]$createdAlarmConfig.alarmConfigureId

    for ($i=1; $i -le 12; $i++) {
        $scenario = 'A{0:D2}' -f $i
        $cid = "REAL-ALARM-$runId-$scenario"
        Publish-AlarmMq -Cid $cid -Kind start -Sequence (100 + $i)
        $alarm = Wait-AlarmByCid -Cid $cid -Headers $headersA
        Assert-True ($null -ne $alarm) "$scenario did not persist from RabbitMQ"
        Assert-True ([string]$alarm.alarmStatus -eq '0' -and [string]$alarm.handleStatus -eq '0') "$scenario initial state mismatch"
        $alarms[$scenario] = $alarm
        Write-JsonFile -Path (Join-Path $evidenceRoot "$scenario-initial.json") -Value $alarm
    }
    $alarmPushSnapshot = Wait-ReceiverCount -BaseUrl $alarmReceiverUrl -MinimumCount 12 -TimeoutSeconds 40
    Write-JsonFile -Path (Join-Path $evidenceRoot 'alarm-push-events.json') -Value $alarmPushSnapshot
    Assert-True ([int]$alarmPushSnapshot.count -ge 12) 'Not all 12 ordinary alarm pushes reached the HTTP receiver'

    # A01 plus N01/N02.
    $a01 = $alarms['A01']
    $n01Create = Create-Workorder -Scenario 'A01-N01' -AlarmId ([long]$a01.alarmId) -Headers $headersA -Mode DIRECT -AssigneeId $UserAId -AssigneeName 'User A'
    $n01Passed = $n01Create.response.code -ne 200 -and $null -eq (Get-WorkorderByAlarmId -AlarmId ([long]$a01.alarmId) -Headers $headersA)
    Add-NegativeResult 'N01' $n01Passed 'Unconfirmed alarm cannot create workorder'
    $n02Response = Handle-Alarm -Scenario 'A01-N02' -AlarmId ([long]$a01.alarmId) -Headers $headersA -Folder $negativeRoot
    $n02State = Get-AlarmById -AlarmId ([long]$a01.alarmId) -Headers $headersA
    Add-NegativeResult 'N02' ($n02Response.code -ne 200 -and [string]$n02State.alarmStatus -eq '0' -and [string]$n02State.handleStatus -eq '0') 'Unconfirmed alarm cannot be handled'
    Add-ScenarioResult 'A01' ($n01Passed -and $n02Response.code -ne 200) 'Unconfirmed active baseline retained'

    # A02 direct handling and N03.
    $a02 = $alarms['A02']; Confirm-Alarm 'A02' ([long]$a02.alarmId) $headersA
    $a02Handle = Handle-Alarm 'A02' ([long]$a02.alarmId) $headersA
    $a02State = Wait-AlarmState ([long]$a02.alarmId) $headersA '2' '1'
    $n03Response = Handle-Alarm -Scenario 'A02-N03' -AlarmId ([long]$a02.alarmId) -Headers $headersA -Folder $negativeRoot
    $a02Final = Get-AlarmById ([long]$a02.alarmId) $headersA
    Add-NegativeResult 'N03' ($n03Response.code -ne 200 -and [string]$a02Final.alarmStatus -eq '2') 'Repeated handling rejected'
    Add-ScenarioResult 'A02' ($a02Handle.code -eq 200 -and [string]$a02State.alarmStatus -eq '2' -and $null -eq (Get-WorkorderByAlarmId ([long]$a02.alarmId) $headersA)) 'Confirmed alarm handled directly'

    # A03 false alarm and N04.
    $a03 = $alarms['A03']; Confirm-Alarm 'A03' ([long]$a03.alarmId) $headersA
    $a03Handle = Handle-Alarm 'A03' ([long]$a03.alarmId) $headersA '1'
    $a03State = Wait-AlarmState ([long]$a03.alarmId) $headersA '-1' '1'
    $n04Response = Handle-Alarm -Scenario 'A03-N04' -AlarmId ([long]$a03.alarmId) -Headers $headersA -Folder $negativeRoot
    Add-NegativeResult 'N04' ($n04Response.code -ne 200 -and [string](Get-AlarmById ([long]$a03.alarmId) $headersA).alarmStatus -eq '-1') 'False alarm cannot be re-handled as real'
    Add-ScenarioResult 'A03' ($a03Handle.code -eq 200 -and [string]$a03State.alarmStatus -eq '-1') 'False alarm terminal state persisted'

    # A04 self reminder plus N05/N06.
    $a04 = $alarms['A04']; Confirm-Alarm 'A04' ([long]$a04.alarmId) $headersA
    $a04Create = Create-Workorder 'A04' ([long]$a04.alarmId) $headersA DIRECT $UserAId 'User A'
    $a04Wo = $a04Create.workorder
    Assert-True ($a04Create.response.code -eq 200 -and $null -ne $a04Wo) 'A04 workorder creation failed'
    $a04MyA = Test-MyContains ([long]$a04.alarmId) $headersA
    $a04MyB = Test-MyContains ([long]$a04.alarmId) $headersB
    $n05 = Create-Workorder 'A04-N05' ([long]$a04.alarmId) $headersA DIRECT $UserAId 'User A'
    Add-NegativeResult 'N05' ($n05.response.code -ne 200) 'Duplicate workorder rejected'
    $n06Body = @{ workorderId=[long]$a04Wo.workorderId; handleResult='legacy complete'; handlePicture='/legacy.jpg' }
    $n06 = Invoke-JsonApi -Method PUT -Uri "$alarmBaseUrl/workorder/complete" -Headers $headersA -Body $n06Body -EvidenceFolder $negativeRoot -EvidenceName 'N06-retired-complete'
    Add-NegativeResult 'N06' ($n06.code -ne 200 -and [string](Get-WorkorderByAlarmId ([long]$a04.alarmId) $headersA).status -eq '0') 'Retired complete endpoint did not write'
    $a04Handle = Handle-Alarm 'A04' ([long]$a04.alarmId) $headersA
    $a04Final = Wait-WorkorderState ([long]$a04Wo.workorderId) $headersA '2'
    Add-ScenarioResult 'A04' ($a04Handle.code -eq 200 -and $a04MyA -and -not $a04MyB -and [string]$a04Final.status -eq '2' -and [long]$a04Final.handlerId -eq $UserAId) 'Self reminder and handling closed together'

    # A05 other target, N07, then target handles.
    $a05 = $alarms['A05']; Confirm-Alarm 'A05' ([long]$a05.alarmId) $headersA
    $a05Create = Create-Workorder 'A05' ([long]$a05.alarmId) $headersA DIRECT $UserBId 'User B'
    $a05Wo = $a05Create.workorder; Assert-True ($null -ne $a05Wo) 'A05 workorder creation failed'
    $foreignDetail = Invoke-JsonApi -Method GET -Uri "$alarmBaseUrl/workorder/$($a05Wo.workorderId)" -Headers $headersOther -EvidenceFolder $negativeRoot -EvidenceName 'N07-foreign-detail'
    $foreignTransfer = Invoke-JsonApi -Method PUT -Uri "$alarmBaseUrl/workorder/transfer" -Headers $headersOther -Body @{workorderId=[long]$a05Wo.workorderId;assigneeId=$OtherUserId;assigneeName='Other'} -EvidenceFolder $negativeRoot -EvidenceName 'N07-foreign-transfer'
    $foreignHandle = Handle-Alarm -Scenario 'A05-N07' -AlarmId ([long]$a05.alarmId) -Headers $headersOther -Folder $negativeRoot
    $n07Passed = $null -eq $foreignDetail.data -and $foreignTransfer.code -ne 200 -and $foreignHandle.code -ne 200 -and [string](Get-WorkorderByAlarmId ([long]$a05.alarmId) $headersA).status -eq '0'
    Add-NegativeResult 'N07' $n07Passed 'Cross-tenant detail, transfer and handling blocked'
    $a05Handle = Handle-Alarm 'A05' ([long]$a05.alarmId) $headersB
    $a05Final = Wait-WorkorderState ([long]$a05Wo.workorderId) $headersA '2'
    Add-ScenarioResult 'A05' ($a05Handle.code -eq 200 -and [long]$a05Final.handlerId -eq $UserBId -and [long]$a05Final.assigneeId -eq $UserBId) 'Target B handled the alarm'

    # A06 reminder target and actual handler differ.
    $a06 = $alarms['A06']; Confirm-Alarm 'A06' ([long]$a06.alarmId) $headersA
    $a06Create = Create-Workorder 'A06' ([long]$a06.alarmId) $headersA DIRECT $UserAId 'User A'
    $a06Handle = Handle-Alarm 'A06' ([long]$a06.alarmId) $headersB
    $a06Final = Wait-WorkorderState ([long]$a06Create.workorder.workorderId) $headersA '2'
    Add-ScenarioResult 'A06' ($a06Handle.code -eq 200 -and [long]$a06Final.assigneeId -eq $UserAId -and [long]$a06Final.handlerId -eq $UserBId) 'Reminder target A, actual handler B'

    # A07 group reminder.
    $a07 = $alarms['A07']; Confirm-Alarm 'A07' ([long]$a07.alarmId) $headersA
    $a07Create = Create-Workorder 'A07' ([long]$a07.alarmId) $headersA GROUP
    $a07Wo = $a07Create.workorder
    $a07Handle = Handle-Alarm 'A07' ([long]$a07.alarmId) $headersA
    $a07Final = Wait-WorkorderState ([long]$a07Wo.workorderId) $headersA '2'
    Add-ScenarioResult 'A07' ($a07Handle.code -eq 200 -and [long]$a07Final.assigneeId -eq 0 -and $a07Final.pushTargetMode -eq 'GROUP' -and -not (Test-MyContains ([long]$a07.alarmId) $headersA)) 'Group reminder persisted and linked handling completed it'

    # A08 null means no reminder push.
    $a08 = $alarms['A08']; Confirm-Alarm 'A08' ([long]$a08.alarmId) $headersA
    $workorderCountBeforeA08 = [int](Get-ReceiverSnapshot $workorderReceiverUrl).count
    $a08Create = Create-Workorder 'A08' ([long]$a08.alarmId) $headersA NULL
    Start-Sleep -Seconds 2
    $workorderCountAfterA08 = [int](Get-ReceiverSnapshot $workorderReceiverUrl).count
    $a08Handle = Handle-Alarm 'A08' ([long]$a08.alarmId) $headersA
    $a08Final = Wait-WorkorderState ([long]$a08Create.workorder.workorderId) $headersA '2'
    Add-ScenarioResult 'A08' ($a08Handle.code -eq 200 -and $null -eq $a08Final.assigneeId -and $a08Final.pushTargetMode -eq 'NONE' -and $workorderCountAfterA08 -eq $workorderCountBeforeA08) 'Null target created no reminder event and still linked handling'

    # A09 natural stop without confirmation.
    $a09 = $alarms['A09']; Publish-AlarmMq "REAL-ALARM-$runId-A09" stop 909
    $a09State = Wait-AlarmState ([long]$a09.alarmId) $headersA '1' '0' 50
    Add-ScenarioResult 'A09' ([string]$a09State.alarmStatus -eq '1' -and $null -ne $a09State.alarmEndtime -and $null -eq (Get-WorkorderByAlarmId ([long]$a09.alarmId) $headersA)) 'Unconfirmed alarm ended naturally'

    # A10 stop closes active reminder plus N08/N09.
    $a10 = $alarms['A10']; Confirm-Alarm 'A10' ([long]$a10.alarmId) $headersA
    $a10Create = Create-Workorder 'A10' ([long]$a10.alarmId) $headersA DIRECT $UserAId 'User A'
    Publish-AlarmMq "REAL-ALARM-$runId-A10" stop 910
    $a10State = Wait-AlarmState ([long]$a10.alarmId) $headersA '1' '2' 50
    $a10Final = Wait-WorkorderState ([long]$a10Create.workorder.workorderId) $headersA '3' 50
    $n08 = Handle-Alarm -Scenario 'A10-N08' -AlarmId ([long]$a10.alarmId) -Headers $headersA -Folder $negativeRoot
    Add-NegativeResult 'N08' ($n08.code -ne 200 -and [string](Get-AlarmById ([long]$a10.alarmId) $headersA).alarmStatus -eq '1') 'Ended alarm cannot be handled'
    $n09Transfer = Invoke-JsonApi -Method PUT -Uri "$alarmBaseUrl/workorder/transfer" -Headers $headersA -Body @{workorderId=[long]$a10Final.workorderId;assigneeId=$UserBId;assigneeName='User B'} -EvidenceFolder $negativeRoot -EvidenceName 'N09-terminal-transfer'
    $n09Close = Invoke-JsonApi -Method PUT -Uri "$alarmBaseUrl/workorder/close" -Headers $headersA -Body @{workorderId=[long]$a10Final.workorderId;handleResult='repeat close'} -EvidenceFolder $negativeRoot -EvidenceName 'N09-terminal-close'
    Add-NegativeResult 'N09' ($n09Transfer.code -ne 200 -and $n09Close.code -ne 200 -and [string](Get-WorkorderByAlarmId ([long]$a10.alarmId) $headersA).handleResult -eq 'ALARM_ENDED') 'Terminal workorder rejects transfer and close'
    Add-ScenarioResult 'A10' ([string]$a10State.alarmStatus -eq '1' -and [string]$a10Final.status -eq '3' -and [string]$a10Final.handleResult -eq 'ALARM_ENDED' -and -not $a10Final.processable) 'Natural stop closed active reminder'

    # A11 abnormal close, N10, then alarm still handled by B.
    $a11 = $alarms['A11']; Confirm-Alarm 'A11' ([long]$a11.alarmId) $headersA
    $a11Create = Create-Workorder 'A11' ([long]$a11.alarmId) $headersA DIRECT $UserAId 'User A'
    $a11CloseBody = @{workorderId=[long]$a11Create.workorder.workorderId;handleResult='matrix abnormal close';handlePicture="/test/alarm-state/$runId/A11-close.jpg"}
    $a11Close = Invoke-JsonApi -Method PUT -Uri "$alarmBaseUrl/workorder/close" -Headers $headersA -Body $a11CloseBody -EvidenceFolder $evidenceRoot -EvidenceName 'A11-close'
    $a11Closed = Wait-WorkorderState ([long]$a11Create.workorder.workorderId) $headersA '3'
    $n10 = Invoke-JsonApi -Method PUT -Uri "$alarmBaseUrl/workorder/close" -Headers $headersA -Body $a11CloseBody -EvidenceFolder $negativeRoot -EvidenceName 'N10-repeat-close'
    Add-NegativeResult 'N10' ($n10.code -ne 200 -and [string](Get-WorkorderByAlarmId ([long]$a11.alarmId) $headersA).status -eq '3') 'Repeated abnormal close rejected'
    $a11Handle = Handle-Alarm 'A11' ([long]$a11.alarmId) $headersB
    $a11AlarmFinal = Wait-AlarmState ([long]$a11.alarmId) $headersA '2' '1'
    $a11Final = Get-WorkorderByAlarmId ([long]$a11.alarmId) $headersA
    Add-ScenarioResult 'A11' ($a11Close.code -eq 200 -and $a11Handle.code -eq 200 -and [string]$a11Final.status -eq '3' -and [string]$a11AlarmFinal.alarmStatus -eq '2' -and [long]$a11Final.handlerId -eq $UserBId) 'Abnormal reminder close did not block alarm handling'

    # A12 transfer validation, N11/N12, then B handles.
    $a12 = $alarms['A12']; Confirm-Alarm 'A12' ([long]$a12.alarmId) $headersA
    $a12Create = Create-Workorder 'A12' ([long]$a12.alarmId) $headersA DIRECT $UserAId 'User A'
    $a12WorkorderId = [long]$a12Create.workorder.workorderId
    $n11Responses = @(
        (Invoke-JsonApi -Method PUT -Uri "$alarmBaseUrl/workorder/transfer" -Headers $headersA -Body @{workorderId=$a12WorkorderId} -EvidenceFolder $negativeRoot -EvidenceName 'N11-null'),
        (Invoke-JsonApi -Method PUT -Uri "$alarmBaseUrl/workorder/transfer" -Headers $headersA -Body @{workorderId=$a12WorkorderId;assigneeId=0} -EvidenceFolder $negativeRoot -EvidenceName 'N11-zero'),
        (Invoke-JsonApi -Method PUT -Uri "$alarmBaseUrl/workorder/transfer" -Headers $headersA -Body @{workorderId=$a12WorkorderId;assigneeId=-1} -EvidenceFolder $negativeRoot -EvidenceName 'N11-negative')
    )
    $a12BeforeTransfer = Get-WorkorderByAlarmId ([long]$a12.alarmId) $headersA
    Add-NegativeResult 'N11' ((@($n11Responses | Where-Object { $_.code -eq 200 }).Count -eq 0) -and [long]$a12BeforeTransfer.assigneeId -eq $UserAId) 'Invalid transfer targets rejected'
    $a12Transfer = Invoke-JsonApi -Method PUT -Uri "$alarmBaseUrl/workorder/transfer" -Headers $headersA -Body @{workorderId=$a12WorkorderId;assigneeId=$UserBId;assigneeName='User B'} -EvidenceFolder $evidenceRoot -EvidenceName 'A12-transfer'
    Start-Sleep -Milliseconds 800
    $a12Transferred = Get-WorkorderByAlarmId ([long]$a12.alarmId) $headersA
    $n12 = Handle-Alarm -Scenario 'A12-N12' -AlarmId ([long]$a12.alarmId) -Headers $headersB -MissingPicture -Folder $negativeRoot
    $a12AfterMissing = Get-WorkorderByAlarmId ([long]$a12.alarmId) $headersA
    Add-NegativeResult 'N12' ($n12.code -ne 200 -and [string]$a12AfterMissing.status -eq '0' -and [string]$a12AfterMissing.handleStatus -eq '2') 'Missing picture rejected without state change'
    $a12Handle = Handle-Alarm 'A12' ([long]$a12.alarmId) $headersB
    $a12Final = Wait-WorkorderState $a12WorkorderId $headersA '2'
    Add-ScenarioResult 'A12' ($a12Transfer.code -eq 200 -and [long]$a12Transferred.assigneeId -eq $UserBId -and -not (Test-MyContains ([long]$a12.alarmId) $headersA) -and (Test-MyContains ([long]$a12.alarmId) $headersB) -and $a12Handle.code -eq 200 -and [long]$a12Final.handlerId -eq $UserBId) 'Transferred reminder handled by new target'

    # N13 deliberately runs last because a failure may mutate A01.
    $n13Body = @{alarmId=[long]$a01.alarmId;alarmIds=@([long]$a01.alarmId);handleStatus='2';opinion='foreign tenant confirmation probe'}
    $n13 = Invoke-JsonApi -Method POST -Uri "$alarmBaseUrl/handle/update" -Headers $headersOther -Body $n13Body -EvidenceFolder $negativeRoot -EvidenceName 'N13-cross-tenant-confirm'
    $a01AfterN13 = Get-AlarmById ([long]$a01.alarmId) $headersA
    $n13Passed = $n13.code -ne 200 -and [string]$a01AfterN13.handleStatus -eq '0'
    Add-NegativeResult 'N13' $n13Passed "Cross-tenant confirm response=$($n13.code), resultingHandleStatus=$($a01AfterN13.handleStatus)"

    # Preserve evidence then end A01 so no active route remains.
    Publish-AlarmMq "REAL-ALARM-$runId-A01" stop 901
    [void](Wait-AlarmState ([long]$a01.alarmId) $headersA '1' ([string]$a01AfterN13.handleStatus) 50)

    $workorderPushSnapshot = Wait-ReceiverCount -BaseUrl $workorderReceiverUrl -MinimumCount 8 -TimeoutSeconds 35
    Write-JsonFile -Path (Join-Path $evidenceRoot 'workorder-push-events.json') -Value $workorderPushSnapshot
    if ([int]$workorderPushSnapshot.count -lt 8) { $warnings.Add("Workorder HTTP receiver count below expected minimum: $($workorderPushSnapshot.count)") }

    $allScenariosPassed = @($scenarioRows | Where-Object { $_.result -ne 'PASS' }).Count -eq 0 -and $scenarioRows.Count -eq 12
    $allNegativesPassed = @($negativeRows | Where-Object { $_.result -ne 'PASS' }).Count -eq 0 -and $negativeRows.Count -eq 13
    $summary = [ordered]@{
        runId=$runId; branch=(git -C $moduleRoot branch --show-current); tenantId=$TenantId;
        consumerMode=if($UseSingleConsumerRollback){'SINGLE_ROLLBACK'}else{'CURRENT_BATCH'};
        deviceSn=$deviceSn; scenarioCount=$scenarioRows.Count; negativeCount=$negativeRows.Count;
        scenarios=$scenarioRows; negatives=$negativeRows; warnings=$warnings;
        allScenariosPassed=$allScenariosPassed; allNegativesPassed=$allNegativesPassed;
        evidenceRoot=$runRoot
    }
    Write-JsonFile -Path (Join-Path $runRoot 'result.json') -Value $summary
    $summary | ConvertTo-Json -Depth 20
    if (-not ($allScenariosPassed -and $allNegativesPassed)) { exit 2 }
} catch {
    $fatalErrors.Add($_.Exception.Message)
    $failure = [ordered]@{runId=$runId;fatalErrors=$fatalErrors;scenarios=$scenarioRows;negatives=$negativeRows;warnings=$warnings;evidenceRoot=$runRoot}
    Write-JsonFile -Path (Join-Path $runRoot 'result.json') -Value $failure
    $failure | ConvertTo-Json -Depth 20
    exit 1
} finally {
    try {
        if ($headersA) { Remove-TestConfigurations -Headers $headersA }
    } catch { $warnings.Add("API cleanup failed: $($_.Exception.Message)") }
    Remove-RedisKeys -Keys $redisKeys
    if (-not $KeepServices) {
        foreach ($process in @($alarmProcess,$pushProcess,$receiverProcess)) {
            if ($null -ne $process) {
                try { if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force } } catch {}
            }
        }
    }
}
