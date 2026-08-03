[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Database,

    [string]$HostName = "127.0.0.1",
    [int]$Port = 3306,
    [string]$User = "root",
    [string]$Password,
    [string]$LoginPath,
    [string]$MysqlExe = "mysql",
    [long]$MaxRows = 5000000,
    [ValidateRange(0, 255)]
    [int]$WorkerId = 0,
    [ValidateRange(1, 87600)]
    [int]$HotHours = 24,
    [ValidateRange(1, 36500)]
    [int]$StaleExpireDays = 30,
    [string]$OutputFile,
    [switch]$PrecheckOnly
)

$ErrorActionPreference = "Stop"

$sqlFile = [System.IO.Path]::GetFullPath(
    (Join-Path $PSScriptRoot "..\sql\alarm-legacy-0-4-id-rewrite-migration.sql")
)
if (-not (Test-Path -LiteralPath $sqlFile -PathType Leaf)) {
    throw "Migration SQL not found: $sqlFile"
}

if ([string]::IsNullOrWhiteSpace($OutputFile)) {
    $OutputFile = Join-Path (Get-Location).Path (
        "alarm-id-mapping-{0}.csv" -f (Get-Date -Format "yyyyMMdd-HHmmss")
    )
}
$OutputFile = [System.IO.Path]::GetFullPath($OutputFile)
$outputDirectory = Split-Path -Parent $OutputFile
if (-not (Test-Path -LiteralPath $outputDirectory -PathType Container)) {
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
}

$connectionArgs = @("--default-character-set=utf8mb4", "--batch", "--raw")
if (-not [string]::IsNullOrWhiteSpace($LoginPath)) {
    $connectionArgs += "--login-path=$LoginPath"
} else {
    $connectionArgs += @("--host=$HostName", "--port=$Port", "--user=$User")
}
$connectionArgs += "--database=$Database"

$previousMysqlPassword = [Environment]::GetEnvironmentVariable("MYSQL_PWD", "Process")
try {
    if (-not [string]::IsNullOrWhiteSpace($Password)) {
        [Environment]::SetEnvironmentVariable("MYSQL_PWD", $Password, "Process")
    }

    $mysqlSqlFile = $sqlFile.Replace("\", "/")
    & $MysqlExe @connectionArgs "--execute=source $mysqlSqlFile"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to install migration SQL. mysql exit code: $LASTEXITCODE"
    }

    $precheckSql = "CALL alarm_assert_legacy_id_rewrite_ready($MaxRows, $WorkerId);"
    & $MysqlExe @connectionArgs "--execute=$precheckSql"
    if ($LASTEXITCODE -ne 0) {
        throw "Migration precheck failed. mysql exit code: $LASTEXITCODE"
    }

    if ($PrecheckOnly) {
        Write-Host "Precheck passed. No data was migrated."
        return
    }

    $migrationSql = "CALL alarm_run_legacy_0_4_id_rewrite_migration($MaxRows, $WorkerId, $HotHours, $StaleExpireDays);"
    & $MysqlExe @connectionArgs "--execute=$migrationSql"
    if ($LASTEXITCODE -ne 0) {
        throw "Migration failed. mysql exit code: $LASTEXITCODE"
    }

    $statusSql = @"
SELECT status
FROM alarm_legacy_migration_run
ORDER BY started_time DESC
LIMIT 1;
"@
    $statusOutput = @(& $MysqlExe @connectionArgs "--skip-column-names" "--execute=$statusSql")
    if ($LASTEXITCODE -ne 0 -or $statusOutput.Count -ne 1 -or $statusOutput[0].Trim() -ne "PASS") {
        throw "The latest migration run is not PASS. CSV was not exported."
    }

    $mappingSql = @"
SELECT
  old_alarm_id,
  new_alarm_id,
  source_table_name,
  old_alarm_handle_id,
  new_alarm_handle_id,
  DATE_FORMAT(source_alarm_beginTime, '%Y-%m-%d %H:%i:%s') AS source_alarm_begin_time,
  DATE_FORMAT(route_alarm_beginTime, '%Y-%m-%d %H:%i:%s') AS route_alarm_begin_time,
  month_key,
  slice_no,
  row_no,
  table_suffix,
  migration_status,
  created_run_id,
  DATE_FORMAT(created_time, '%Y-%m-%d %H:%i:%s') AS created_time
FROM alarm_legacy_id_migration_map
ORDER BY route_alarm_beginTime, source_table_no, old_alarm_id;
"@
    $mappingTsv = @(& $MysqlExe @connectionArgs "--execute=$mappingSql")
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to query migration mapping. mysql exit code: $LASTEXITCODE"
    }
    if ($mappingTsv.Count -lt 1) {
        throw "Migration mapping query returned no header."
    }

    $mappingRows = @($mappingTsv | ConvertFrom-Csv -Delimiter "`t")
    $mappingRows | Export-Csv -LiteralPath $OutputFile -NoTypeInformation -Encoding UTF8

    $databaseCountSql = "SELECT COUNT(*) FROM alarm_legacy_id_migration_map;"
    $databaseCountOutput = @(
        & $MysqlExe @connectionArgs "--skip-column-names" "--execute=$databaseCountSql"
    )
    if ($LASTEXITCODE -ne 0 -or $databaseCountOutput.Count -ne 1) {
        throw "Failed to verify migration mapping row count."
    }
    $databaseCount = [long]$databaseCountOutput[0].Trim()
    if ($mappingRows.Count -ne $databaseCount) {
        throw "CSV row count $($mappingRows.Count) does not match database mapping count $databaseCount."
    }

    Write-Host "Migration passed."
    Write-Host "Mapping rows: $databaseCount"
    Write-Host "Mapping CSV: $OutputFile"
} finally {
    [Environment]::SetEnvironmentVariable("MYSQL_PWD", $previousMysqlPassword, "Process")
}
