param(
    [string]$Ports = '19010,19025',
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $moduleRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..'))
    $OutputDirectory = Join-Path $moduleRoot 'target\alarm-push-api-receiver'
}

$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$listenerPorts = @($Ports.Split(',') | ForEach-Object { [int]$_.Trim() })
$eventsByPort = @{}
$listener = New-Object System.Net.HttpListener

foreach ($port in $listenerPorts) {
    $listener.Prefixes.Add("http://127.0.0.1:$port/")
    $eventsByPort[$port] = New-Object System.Collections.ArrayList
}

function Write-JsonResponse {
    param(
        [System.Net.HttpListenerResponse]$Response,
        [int]$StatusCode,
        [object]$Payload
    )

    $json = $Payload | ConvertTo-Json -Depth 30 -Compress
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    $Response.StatusCode = $StatusCode
    $Response.ContentType = 'application/json; charset=utf-8'
    $Response.ContentEncoding = [System.Text.Encoding]::UTF8
    $Response.Headers.Add('Access-Control-Allow-Origin', '*')
    $Response.ContentLength64 = $bytes.Length
    $Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $Response.OutputStream.Close()
}

try {
    $listener.Start()
    Write-Host "Alarm push HTTP receiver started. Ports: $($listenerPorts -join ', ')"
    Write-Host "Event logs: $OutputDirectory"
    Write-Host 'Use GET /_events to inspect events and DELETE /_events to clear one port. Press Ctrl+C to stop.'

    while ($listener.IsListening) {
        $context = $listener.GetContext()
        $request = $context.Request
        $response = $context.Response
        $listenerPort = $request.LocalEndPoint.Port
        $events = $eventsByPort[$listenerPort]
        $logFile = Join-Path $OutputDirectory ("receiver-$listenerPort.jsonl")
        $path = $request.Url.AbsolutePath

        if ($request.HttpMethod -eq 'GET' -and $path -eq '/_events') {
            Write-JsonResponse -Response $response -StatusCode 200 -Payload ([ordered]@{
                port = $listenerPort
                count = $events.Count
                events = @($events)
            })
            continue
        }

        if ($request.HttpMethod -eq 'DELETE' -and $path -eq '/_events') {
            $events.Clear()
            if (Test-Path -LiteralPath $logFile) {
                Clear-Content -LiteralPath $logFile
            }
            Write-JsonResponse -Response $response -StatusCode 200 -Payload ([ordered]@{
                code = 200
                message = 'events cleared'
                port = $listenerPort
            })
            continue
        }

        if ($request.HttpMethod -eq 'OPTIONS') {
            $response.StatusCode = 204
            $response.Headers.Add('Access-Control-Allow-Origin', '*')
            $response.Headers.Add('Access-Control-Allow-Methods', 'GET,POST,DELETE,OPTIONS')
            $response.Headers.Add('Access-Control-Allow-Headers', 'Content-Type,Authorization')
            $response.OutputStream.Close()
            continue
        }

        $reader = New-Object System.IO.StreamReader($request.InputStream, $request.ContentEncoding)
        try {
            $rawBody = $reader.ReadToEnd()
        } finally {
            $reader.Dispose()
        }

        $parsedBody = $rawBody
        if (-not [string]::IsNullOrWhiteSpace($rawBody)) {
            try {
                $parsedBody = $rawBody | ConvertFrom-Json
            } catch {
                $parsedBody = $rawBody
            }
        }

        $headers = [ordered]@{}
        foreach ($headerName in $request.Headers.AllKeys) {
            $headers[$headerName] = $request.Headers[$headerName]
        }

        $event = [ordered]@{
            receivedAt = [DateTimeOffset]::Now.ToString('o')
            port = $listenerPort
            method = $request.HttpMethod
            path = $path
            headers = $headers
            body = $parsedBody
            rawBody = $rawBody
        }
        [void]$events.Add($event)
        ($event | ConvertTo-Json -Depth 30 -Compress) | Add-Content -LiteralPath $logFile -Encoding UTF8

        Write-JsonResponse -Response $response -StatusCode 200 -Payload ([ordered]@{
            code = 200
            message = 'received'
            port = $listenerPort
            eventCount = $events.Count
        })
    }
} finally {
    if ($listener.IsListening) {
        $listener.Stop()
    }
    $listener.Close()
}
