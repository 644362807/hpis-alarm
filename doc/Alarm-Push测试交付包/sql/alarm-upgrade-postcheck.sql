-- Alarm升级后只读验收（目标库默认hpis_alarm）
SET NAMES utf8mb4;

SELECT table_name
FROM information_schema.tables
WHERE table_schema='hpis_alarm'
  AND table_name IN ('alarm_shard_slice','alarm_cid_index','alarm_cid_stale_index',
                     'alarm_stop_event','alarm_stop_side_effect_event',
                     'alarm_electrolytic_cell_snapshot_command','alarm_workorder',
                     'alarm_legacy_id_migration_map','alarm_legacy_migration_run',
                     'alarm_legacy_migration_audit')
ORDER BY table_name;

SELECT column_name,is_nullable,column_default,column_type
FROM information_schema.columns
WHERE table_schema='hpis_alarm' AND table_name='alarm_configure'
  AND column_name IN ('push_enabled','push_message_type','workorder_push_message_type','workorder_config_id')
ORDER BY ordinal_position;

SELECT table_name,column_name,column_type,is_nullable
FROM information_schema.columns
WHERE table_schema='hpis_alarm'
  AND ((table_name='alarm_workorder' AND column_name IN ('workorder_id','alarm_id','assignee_id','status','tenant_id'))
    OR (table_name REGEXP '^alarm_handle(_[0-9]{6}_[0-9]{2})?$' AND column_name IN ('workorder_id','alarm_beginTime')))
ORDER BY table_name,ordinal_position;

SET @run_sql = IF(
  EXISTS (SELECT 1 FROM information_schema.tables
          WHERE table_schema='hpis_alarm' AND table_name='alarm_legacy_migration_run'),
  'SELECT run_id,max_rows,worker_id,source_rows,mapped_rows,status,error_message,started_time,finished_time FROM hpis_alarm.alarm_legacy_migration_run ORDER BY started_time DESC LIMIT 5',
  'SELECT ''migration_run_table_missing'' item, 1 problem_count'
);
PREPARE stmt FROM @run_sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @audit_sql = IF(
  EXISTS (SELECT 1 FROM information_schema.tables
          WHERE table_schema='hpis_alarm' AND table_name='alarm_legacy_migration_audit'),
  'SELECT run_id,audit_status,COUNT(*) audit_rows,SUM(expected_rows) expected_rows,SUM(actual_rows) actual_rows,SUM(mismatch_rows) mismatch_rows FROM hpis_alarm.alarm_legacy_migration_audit GROUP BY run_id,audit_status ORDER BY run_id,audit_status',
  'SELECT ''migration_audit_table_missing'' item, 1 problem_count'
);
PREPARE stmt FROM @audit_sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @mapping_sql = IF(
  EXISTS (SELECT 1 FROM information_schema.tables
          WHERE table_schema='hpis_alarm' AND table_name='alarm_legacy_id_migration_map'),
  'SELECT ''mapping_count'' item,COUNT(*) row_count FROM hpis_alarm.alarm_legacy_id_migration_map',
  'SELECT ''mapping_table_missing'' item, 1 row_count'
);
PREPARE stmt FROM @mapping_sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SELECT 'slice_rows' item,COALESCE(SUM(current_rows),0) row_count FROM hpis_alarm.alarm_shard_slice
UNION ALL SELECT 'hot_routes',COUNT(*) FROM hpis_alarm.alarm_cid_index
UNION ALL SELECT 'stale_routes',COUNT(*) FROM hpis_alarm.alarm_cid_stale_index;

SELECT table_name,index_name,seq_in_index,column_name
FROM information_schema.statistics
WHERE table_schema='hpis_alarm'
  AND ((table_name='alarm_workorder' AND index_name='idx_alarm_workorder_tenant_assignee_status')
    OR (table_name='alarm_electrolytic_cell_ectype' AND index_name IN ('PRIMARY','uk_ec_ectype_point')))
ORDER BY table_name,index_name,seq_in_index;
