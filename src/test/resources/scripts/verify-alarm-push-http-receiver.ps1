param(
    [string]$ReceiverScript = (Join-Path $PSScriptRoot 'start-alarm-push-http-receiver.ps1')
)

$ErrorActionPreference = 'Stop'
$testPorts = @(19110, 19125)
$outputDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('hpis-alarm-push-receiver-selftest-' + [guid]::NewGuid().ToString('N'))
$receiverProcess = $null

function Wait-ReceiverReady {
    param([int]$Port)

    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        try {
            Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$Port/_events" -TimeoutSec 1 | Out-Null
            return
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    throw "Receiver on port $Port did not become ready."
}

try {
    $arguments = @(
        '-NoProfile',
        '-ExecutionPolicy', 'Bypass',
        '-File', ('"' + $ReceiverScript + '"'),
        '-Ports', ($testPorts -join ','),
        '-OutputDirectory', ('"' + $outputDirectory + '"')
    )
    $receiverProcess = Start-Process -FilePath 'powershell.exe' -ArgumentList $arguments -WindowStyle Hidden -PassThru

    foreach ($port in $testPorts) {
        Wait-ReceiverReady -Port $port
    }

    Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($testPorts[0])/codex/emergency-10" `
        -ContentType 'application/json' -Body '{"messageType":"10","deviceSn":"CODX-SELFTEST-A"}' | Out-Null
    Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$($testPorts[1])/codex/workorder-25" `
        -ContentType 'application/json' -Body '{"messageType":"25","data":{"eventType":"ALARM_WORKORDER_CREATED"}}' | Out-Null

    $alarmEvents = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$($testPorts[0])/_events"
    $workorderEvents = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$($testPorts[1])/_events"

    if ($alarmEvents.count -ne 1 -or $alarmEvents.events[0].body.messageType -ne '10') {
        throw 'Port 19110 did not record the expected messageType=10 event.'
    }
    if ($workorderEvents.count -ne 1 -or $workorderEvents.events[0].body.data.eventType -ne 'ALARM_WORKORDER_CREATED') {
        throw 'Port 19125 did not record the expected workorder event.'
    }

    Invoke-RestMethod -Method Delete -Uri "http://127.0.0.1:$($testPorts[0])/_events" | Out-Null
    $cleared = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$($testPorts[0])/_events"
    if ($cleared.count -ne 0) {
        throw 'DELETE /_events did not clear the selected port.'
    }

    $expectedLogs = @(
        (Join-Path $outputDirectory 'receiver-19110.jsonl'),
        (Join-Path $outputDirectory 'receiver-19125.jsonl')
    )
    foreach ($logFile in $expectedLogs) {
        if (-not (Test-Path -LiteralPath $logFile)) {
            throw "Expected receiver log was not created: $logFile"
        }
    }

    Write-Host 'Alarm push HTTP receiver self-test passed.'
} finally {
    if ($receiverProcess -and -not $receiverProcess.HasExited) {
        Stop-Process -Id $receiverProcess.Id -Force
        $receiverProcess.WaitForExit()
    }
    if (Test-Path -LiteralPath $outputDirectory) {
        Remove-Item -LiteralPath $outputDirectory -Recurse -Force
    }
}
