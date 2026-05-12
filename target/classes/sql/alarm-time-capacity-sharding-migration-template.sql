-- Alarm 时间容量分片迁移模板
-- 用途：
-- 1. 每次迁移一个旧 hash 表和一个月份的数据。
-- 2. 业务数据迁移到 alarm_yyyyMM_nn、alarm_handle_yyyyMM_nn、alarm_electrolytic_cell_yyyyMM_nn。
-- 3. cid 路由只回填未关闭报警，已关闭历史不进入 hot/stale 路由。
--
-- 执行前请先运行 alarm-time-capacity-sharding.sql。

SET @source_no = '0';
SET @month_key = '202604';
SET @slice_no = 0;
SET @max_rows = 5000000;
SET @month_start = '2026-04-01 00:00:00';
SET @month_end = '2026-05-01 00:00:00';
SET @hot_hours = 24;
SET @stale_expire_days = 30;

SET @table_suffix = CONCAT(@month_key, '_', LPAD(@slice_no, 2, '0'));
SET @source_alarm = CONCAT('alarm_', @source_no);
SET @source_handle = CONCAT('alarm_handle_', @source_no);
SET @source_cell = CONCAT('alarm_electrolytic_cell_', @source_no);
SET @target_alarm = CONCAT('alarm_', @table_suffix);
SET @target_handle = CONCAT('alarm_handle_', @table_suffix);
SET @target_cell = CONCAT('alarm_electrolytic_cell_', @table_suffix);

-- 1. 创建目标物理表。目标结构从旧分表复制，旧分表需先补齐 alarm_beginTime。
SET @sql_text = CONCAT('CREATE TABLE IF NOT EXISTS `', @target_alarm, '` LIKE `', @source_alarm, '`');
PREPARE stmt FROM @sql_text;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql_text = CONCAT('CREATE TABLE IF NOT EXISTS `', @target_handle, '` LIKE `', @source_handle, '`');
PREPARE stmt FROM @sql_text;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql_text = CONCAT('CREATE TABLE IF NOT EXISTS `', @target_cell, '` LIKE `', @source_cell, '`');
PREPARE stmt FROM @sql_text;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 回填旧绑定表 alarm_beginTime，保证绑定表后续可按同 suffix 路由。
SET @sql_text = CONCAT(
  'UPDATE `', @source_handle, '` h JOIN `', @source_alarm, '` a ON h.alarm_id = a.alarm_id ',
  'SET h.alarm_beginTime = a.alarm_beginTime ',
  'WHERE h.alarm_beginTime IS NULL ',
  'AND a.alarm_beginTime >= ''', @month_start, ''' ',
  'AND a.alarm_beginTime < ''', @month_end, ''''
);
PREPARE stmt FROM @sql_text;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql_text = CONCAT(
  'UPDATE `', @source_cell, '` c JOIN `', @source_alarm, '` a ON c.alarm_id = a.alarm_id ',
  'SET c.alarm_beginTime = a.alarm_beginTime ',
  'WHERE c.alarm_beginTime IS NULL ',
  'AND a.alarm_beginTime >= ''', @month_start, ''' ',
  'AND a.alarm_beginTime < ''', @month_end, ''''
);
PREPARE stmt FROM @sql_text;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 迁移业务主表。
SET @sql_text = CONCAT(
  'INSERT IGNORE INTO `', @target_alarm, '` ',
  'SELECT * FROM `', @source_alarm, '` ',
  'WHERE alarm_beginTime >= ''', @month_start, ''' ',
  'AND alarm_beginTime < ''', @month_end, ''''
);
PREPARE stmt FROM @sql_text;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. 迁移绑定表，只迁移目标月份主表中存在的 alarm_id。
SET @sql_text = CONCAT(
  'INSERT IGNORE INTO `', @target_handle, '` ',
  'SELECT h.* FROM `', @source_handle, '` h ',
  'JOIN `', @target_alarm, '` a ON h.alarm_id = a.alarm_id'
);
PREPARE stmt FROM @sql_text;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql_text = CONCAT(
  'INSERT IGNORE INTO `', @target_cell, '` ',
  'SELECT c.* FROM `', @source_cell, '` c ',
  'JOIN `', @target_alarm, '` a ON c.alarm_id = a.alarm_id'
);
PREPARE stmt FROM @sql_text;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 5. 维护月内容量切片元数据。
SET @sql_text = CONCAT(
  'INSERT INTO alarm_shard_slice (month_key, slice_no, table_suffix, current_rows, max_rows, status) ',
  'SELECT ''', @month_key, ''', ', @slice_no, ', ''', @table_suffix, ''', COUNT(1), ', @max_rows, ', ',
  'CASE WHEN COUNT(1) >= ', @max_rows, ' THEN ''FULL'' ELSE ''ACTIVE'' END ',
  'FROM `', @target_alarm, '` ',
  'ON DUPLICATE KEY UPDATE ',
  'current_rows = VALUES(current_rows), ',
  'max_rows = VALUES(max_rows), ',
  'status = VALUES(status)'
);
PREPARE stmt FROM @sql_text;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 6. 回填热点 cid 路由：只回填未关闭且仍在热点期的报警。
SET @sql_text = CONCAT(
  'INSERT INTO alarm_cid_index (alarm_cid, alarm_id, alarm_beginTime, alarm_endTime, table_suffix, device_sn, irms_sn, alarm_type, route_status, delete_after) ',
  'SELECT alarm_cid, alarm_id, alarm_beginTime, alarm_endTime, ''', @table_suffix, ''', device_sn, irms_sn, alarm_type, ''ACTIVE'', NULL ',
  'FROM `', @target_alarm, '` ',
  'WHERE alarm_cid IS NOT NULL ',
  'AND alarm_endTime IS NULL ',
  'AND alarm_beginTime >= DATE_SUB(NOW(), INTERVAL ', @hot_hours, ' HOUR) ',
  'ON DUPLICATE KEY UPDATE ',
  'alarm_id = VALUES(alarm_id), ',
  'alarm_beginTime = VALUES(alarm_beginTime), ',
  'table_suffix = VALUES(table_suffix), ',
  'device_sn = VALUES(device_sn), ',
  'irms_sn = VALUES(irms_sn), ',
  'alarm_type = VALUES(alarm_type), ',
  'route_status = ''ACTIVE'', ',
  'delete_after = NULL'
);
PREPARE stmt FROM @sql_text;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 7. 回填滞留 cid 路由：只回填未关闭且超过热点期的报警。
SET @sql_text = CONCAT(
  'INSERT INTO alarm_cid_stale_index (alarm_cid, alarm_id, alarm_beginTime, alarm_endTime, table_suffix, device_sn, irms_sn, alarm_type, route_status, stale_time, expire_time, delete_after) ',
  'SELECT alarm_cid, alarm_id, alarm_beginTime, alarm_endTime, ''', @table_suffix, ''', device_sn, irms_sn, alarm_type, ''ACTIVE'', NOW(), DATE_ADD(NOW(), INTERVAL ', @stale_expire_days, ' DAY), NULL ',
  'FROM `', @target_alarm, '` ',
  'WHERE alarm_cid IS NOT NULL ',
  'AND alarm_endTime IS NULL ',
  'AND alarm_beginTime < DATE_SUB(NOW(), INTERVAL ', @hot_hours, ' HOUR) ',
  'ON DUPLICATE KEY UPDATE ',
  'alarm_id = VALUES(alarm_id), ',
  'alarm_beginTime = VALUES(alarm_beginTime), ',
  'table_suffix = VALUES(table_suffix), ',
  'device_sn = VALUES(device_sn), ',
  'irms_sn = VALUES(irms_sn), ',
  'alarm_type = VALUES(alarm_type), ',
  'route_status = ''ACTIVE'', ',
  'expire_time = VALUES(expire_time), ',
  'delete_after = NULL'
);
PREPARE stmt FROM @sql_text;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 8. 验证本批次结果。
SET @sql_text = CONCAT('SELECT COUNT(1) AS migrated_alarm_rows FROM `', @target_alarm, '`');
PREPARE stmt FROM @sql_text;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SELECT COUNT(1) AS hot_route_rows
FROM alarm_cid_index
WHERE table_suffix = @table_suffix;

SELECT COUNT(1) AS stale_route_rows
FROM alarm_cid_stale_index
WHERE table_suffix = @table_suffix;
