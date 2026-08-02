-- hpis_alarm1 旧0..4现场库迁移前只读检查（MySQL 8.x）
-- 安全边界：仅包含SET/SELECT/WITH/SHOW，不创建表、不加字段、不更新和删除数据。
-- 如现场源库不是hpis_alarm1，请由DBA审核后统一替换schema名；不要直接在原库执行迁移DDL。

SET NAMES utf8mb4;
SET @source_schema = 'hpis_alarm1';
SET @max_rows_per_slice = 5000000;

SELECT 'source_schema' AS item, @source_schema AS value;
SELECT 'mysql_version' AS item, VERSION() AS value;

SELECT 'required_table' AS item, expected.table_name,
       CASE WHEN actual.table_name IS NULL THEN 'MISSING' ELSE 'OK' END AS status
FROM (
    SELECT 'alarm_0' table_name UNION ALL SELECT 'alarm_1' UNION ALL SELECT 'alarm_2'
    UNION ALL SELECT 'alarm_3' UNION ALL SELECT 'alarm_4'
    UNION ALL SELECT 'alarm_handle_0' UNION ALL SELECT 'alarm_handle_1'
    UNION ALL SELECT 'alarm_handle_2' UNION ALL SELECT 'alarm_handle_3'
    UNION ALL SELECT 'alarm_handle_4'
    UNION ALL SELECT 'alarm_electrolytic_cell_0' UNION ALL SELECT 'alarm_electrolytic_cell_1'
    UNION ALL SELECT 'alarm_electrolytic_cell_2' UNION ALL SELECT 'alarm_electrolytic_cell_3'
    UNION ALL SELECT 'alarm_electrolytic_cell_4'
) expected
LEFT JOIN information_schema.tables actual
  ON actual.table_schema=@source_schema AND actual.table_name=expected.table_name
ORDER BY expected.table_name;

SELECT 'legacy_table_rows' AS item, table_name, table_rows
FROM information_schema.tables
WHERE table_schema=@source_schema
ORDER BY table_name;

WITH source_alarm AS (
    SELECT 0 source_no, alarm_id, alarm_cid, alarm_beginTime, create_time, alarm_endTime FROM hpis_alarm1.alarm_0
    UNION ALL SELECT 1,alarm_id,alarm_cid,alarm_beginTime,create_time,alarm_endTime FROM hpis_alarm1.alarm_1
    UNION ALL SELECT 2,alarm_id,alarm_cid,alarm_beginTime,create_time,alarm_endTime FROM hpis_alarm1.alarm_2
    UNION ALL SELECT 3,alarm_id,alarm_cid,alarm_beginTime,create_time,alarm_endTime FROM hpis_alarm1.alarm_3
    UNION ALL SELECT 4,alarm_id,alarm_cid,alarm_beginTime,create_time,alarm_endTime FROM hpis_alarm1.alarm_4
)
SELECT 'alarm_total' item, COUNT(*) problem_count FROM source_alarm
UNION ALL
SELECT 'duplicate_alarm_id', COUNT(*) FROM (
    SELECT alarm_id FROM source_alarm GROUP BY alarm_id HAVING COUNT(*)>1
) duplicated
UNION ALL
SELECT 'null_route_time', COUNT(*) FROM source_alarm WHERE COALESCE(alarm_beginTime,create_time) IS NULL
UNION ALL
SELECT 'unencodable_route_time', COUNT(*) FROM source_alarm
WHERE DATEDIFF(DATE(COALESCE(alarm_beginTime,create_time)),'2020-01-01') NOT BETWEEN 0 AND 32767
UNION ALL
SELECT 'duplicate_open_alarm_cid', COUNT(*) FROM (
    SELECT alarm_cid FROM source_alarm
    WHERE alarm_endTime IS NULL AND alarm_cid IS NOT NULL AND alarm_cid<>''
    GROUP BY alarm_cid HAVING COUNT(*)>1
) duplicated_open
UNION ALL
SELECT 'open_alarm', COUNT(*) FROM source_alarm WHERE alarm_endTime IS NULL;

WITH source_alarm AS (
    SELECT 0 source_no,alarm_id FROM hpis_alarm1.alarm_0
    UNION ALL SELECT 1,alarm_id FROM hpis_alarm1.alarm_1
    UNION ALL SELECT 2,alarm_id FROM hpis_alarm1.alarm_2
    UNION ALL SELECT 3,alarm_id FROM hpis_alarm1.alarm_3
    UNION ALL SELECT 4,alarm_id FROM hpis_alarm1.alarm_4
), source_handle AS (
    SELECT 0 source_no,alarm_handle_id,alarm_id FROM hpis_alarm1.alarm_handle_0
    UNION ALL SELECT 1,alarm_handle_id,alarm_id FROM hpis_alarm1.alarm_handle_1
    UNION ALL SELECT 2,alarm_handle_id,alarm_id FROM hpis_alarm1.alarm_handle_2
    UNION ALL SELECT 3,alarm_handle_id,alarm_id FROM hpis_alarm1.alarm_handle_3
    UNION ALL SELECT 4,alarm_handle_id,alarm_id FROM hpis_alarm1.alarm_handle_4
), source_cell AS (
    SELECT 0 source_no,alarm_id FROM hpis_alarm1.alarm_electrolytic_cell_0
    UNION ALL SELECT 1,alarm_id FROM hpis_alarm1.alarm_electrolytic_cell_1
    UNION ALL SELECT 2,alarm_id FROM hpis_alarm1.alarm_electrolytic_cell_2
    UNION ALL SELECT 3,alarm_id FROM hpis_alarm1.alarm_electrolytic_cell_3
    UNION ALL SELECT 4,alarm_id FROM hpis_alarm1.alarm_electrolytic_cell_4
)
SELECT 'duplicate_handle_alarm_id' item, COUNT(*) problem_count FROM (
    SELECT alarm_id FROM source_handle GROUP BY alarm_id HAVING COUNT(*)>1
) duplicated
UNION ALL
SELECT 'orphan_handle',COUNT(*) FROM source_handle h
LEFT JOIN source_alarm a ON a.source_no=h.source_no AND a.alarm_id=h.alarm_id
WHERE a.alarm_id IS NULL
UNION ALL
SELECT 'orphan_cell',COUNT(*) FROM source_cell c
LEFT JOIN source_alarm a ON a.source_no=c.source_no AND a.alarm_id=c.alarm_id
WHERE a.alarm_id IS NULL;

WITH source_alarm AS (
    SELECT COALESCE(alarm_beginTime,create_time) route_time FROM hpis_alarm1.alarm_0
    UNION ALL SELECT COALESCE(alarm_beginTime,create_time) FROM hpis_alarm1.alarm_1
    UNION ALL SELECT COALESCE(alarm_beginTime,create_time) FROM hpis_alarm1.alarm_2
    UNION ALL SELECT COALESCE(alarm_beginTime,create_time) FROM hpis_alarm1.alarm_3
    UNION ALL SELECT COALESCE(alarm_beginTime,create_time) FROM hpis_alarm1.alarm_4
)
SELECT 'month_capacity' item, DATE_FORMAT(route_time,'%Y%m') month_key, COUNT(*) row_count,
       CEIL(COUNT(*)/@max_rows_per_slice) required_slices,
       CASE WHEN COUNT(*)>@max_rows_per_slice*100 THEN 'BLOCKED' ELSE 'OK' END status
FROM source_alarm
GROUP BY DATE_FORMAT(route_time,'%Y%m')
ORDER BY month_key;

SELECT 'ectype_duplicate_point' item, COUNT(*) problem_count
FROM (
    SELECT device_sn,sequence_id,row_index,groove_number,subdivide_number,observation_place
    FROM hpis_alarm1.alarm_electrolytic_cell_ectype
    GROUP BY device_sn,sequence_id,row_index,groove_number,subdivide_number,observation_place
    HAVING COUNT(*)>1
) duplicated;

SELECT 'precheck_result' item,
       '人工确认以上所有MISSING、duplicate、null、unencodable、orphan和BLOCKED结果；orphan不得直接删除' note;
