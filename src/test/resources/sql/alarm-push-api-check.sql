-- Alarm Push API 测试证据查询（只读）
SET NAMES utf8mb4;
SET @alarm_cid_prefix = 'API-PUSH-E2E-%';
SET @push_config_prefix = 'api-push-e2e-%';

-- 迁移和基础表检查。
SELECT table_schema, table_name
FROM information_schema.tables
WHERE (table_schema = 'hpis_alarm' AND table_name IN
       ('alarm_configure', 'alarm_device_configure', 'alarm_cid_index', 'alarm_workorder'))
   OR (table_schema = 'hpis_push' AND table_name IN
       ('active_push_config', 'pushconfigid_devicesn', 'push_message_log'))
   OR (table_schema = 'hpis_system' AND table_name = 'sys_dict_data')
ORDER BY table_schema, table_name;

SELECT column_name
FROM information_schema.columns
WHERE table_schema = 'hpis_alarm'
  AND table_name = 'alarm_configure'
  AND column_name IN ('push_enabled', 'push_message_type',
                      'workorder_push_message_type', 'workorder_config_id')
ORDER BY column_name;

SELECT dict_type, dict_label, dict_value, status
FROM hpis_system.sys_dict_data
WHERE dict_type = 'alarm_type' AND dict_value = '10';

SELECT c.alarm_configure_id, c.alarm_configure_name, c.alarm_type,
       c.push_enabled, c.push_message_type, c.workorder_push_message_type,
       c.workorder_config_id, c.tenant_id, d.device_id, d.device_sn
FROM hpis_alarm.alarm_configure c
LEFT JOIN hpis_alarm.alarm_device_configure d
  ON d.alarm_configure_id = c.alarm_configure_id
WHERE c.alarm_configure_name LIKE 'api-push-e2e-%'
ORDER BY c.alarm_configure_name, d.device_sn;

-- push 配置必须由 API 创建；10 与 25 应拥有不同 ID 和不同 HTTP 地址。
SELECT c.active_push_config_id, c.config_name, c.message_type,
       c.push_channel_type, c.enabled, c.push_address, c.tenant_id, d.device_sn
FROM hpis_push.active_push_config c
LEFT JOIN hpis_push.pushconfigid_devicesn d
  ON d.active_push_config_id = c.active_push_config_id
WHERE c.config_name LIKE @push_config_prefix
ORDER BY c.message_type, c.active_push_config_id, d.device_sn;

-- 将查询到的 alarm_id 写入 Postman/HTTP Client 环境变量 internalAlarmId。
SELECT alarm_cid, alarm_id, table_suffix, device_sn, alarm_type,
       route_status, alarm_beginTime, alarm_endTime
FROM hpis_alarm.alarm_cid_index
WHERE alarm_cid LIKE @alarm_cid_prefix
ORDER BY created_time DESC;

SELECT w.workorder_id, w.alarm_id, w.workorder_no, w.workorder_config_id,
       w.status, w.title, w.tenant_id, w.create_time
FROM hpis_alarm.alarm_workorder w
JOIN hpis_alarm.alarm_cid_index i ON i.alarm_id = w.alarm_id
WHERE i.alarm_cid LIKE @alarm_cid_prefix
ORDER BY w.workorder_id DESC;

SELECT l.log_id, l.message_id, l.target, l.target_name,
       l.Push_channel_type, l.active_push_config_id,
       l.push_status, l.message_data
FROM hpis_push.push_message_log l
LEFT JOIN hpis_push.active_push_config c
  ON c.active_push_config_id = l.active_push_config_id
WHERE c.config_name LIKE @push_config_prefix
   OR l.target_name LIKE @push_config_prefix
   OR l.message_data LIKE '%API-PUSH-E2E-%'
ORDER BY l.log_id DESC;
