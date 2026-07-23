-- Alarm Push API 测试证据查询（只读）
SET NAMES utf8mb4;
SET @alarm_cid_prefix = 'API-PUSH-E2E-%';
SET @push_config_prefix = 'api-push-e2e-%';
SET @recipient_group_prefix = 'api-push-e2e-%';
SET @tenant_id = 990010;

-- 迁移和基础表检查。
SELECT table_schema, table_name
FROM information_schema.tables
WHERE (table_schema = 'hpis_alarm' AND table_name IN
       ('alarm_configure', 'alarm_device_configure', 'alarm_cid_index', 'alarm_handle', 'alarm_workorder'))
   OR (table_schema = 'hpis_push' AND table_name IN
       ('active_push_config', 'pushconfigid_devicesn', 'push_message_log',
        'push_wecom_app_config', 'push_wecom_user_binding',
        'push_recipient_group', 'push_recipient_group_member'))
   OR (table_schema = 'hpis_system' AND table_name = 'sys_dict_data')
ORDER BY table_schema, table_name;

SELECT column_name
FROM information_schema.columns
WHERE table_schema = 'hpis_alarm'
  AND table_name = 'alarm_configure'
  AND column_name IN ('push_enabled', 'push_message_type',
                      'workorder_push_message_type', 'workorder_config_id')
ORDER BY column_name;

SELECT index_name, seq_in_index, column_name
FROM information_schema.statistics
WHERE table_schema = 'hpis_alarm'
  AND table_name = 'alarm_workorder'
  AND index_name = 'idx_alarm_workorder_tenant_assignee_status'
ORDER BY seq_in_index;

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
       c.push_channel_type, c.enabled, c.push_address, c.route_scope,
       c.recipient_group_id, c.tenant_id, d.device_sn
FROM hpis_push.active_push_config c
LEFT JOIN hpis_push.pushconfigid_devicesn d
  ON d.active_push_config_id = c.active_push_config_id
WHERE c.config_name LIKE @push_config_prefix
ORDER BY c.message_type, c.active_push_config_id, d.device_sn;

-- 不查询 corp_secret_ciphertext 明文；只判断是否已配置密文。
SELECT tenant_id, corp_id, agent_id, enabled,
       CASE WHEN corp_secret_ciphertext IS NULL OR corp_secret_ciphertext = '' THEN 0 ELSE 1 END AS secret_configured
FROM hpis_push.push_wecom_app_config
WHERE tenant_id = @tenant_id;

SELECT tenant_id, user_id, wecom_user_id, enabled
FROM hpis_push.push_wecom_user_binding
WHERE tenant_id = @tenant_id
ORDER BY user_id;

SELECT g.id AS group_id, g.group_name, g.enabled, g.tenant_id, m.user_id,
       b.wecom_user_id, b.enabled AS binding_enabled
FROM hpis_push.push_recipient_group g
LEFT JOIN hpis_push.push_recipient_group_member m
  ON m.tenant_id = g.tenant_id AND m.group_id = g.id
LEFT JOIN hpis_push.push_wecom_user_binding b
  ON b.tenant_id = m.tenant_id AND b.user_id = m.user_id
WHERE g.tenant_id = @tenant_id
  AND g.group_name LIKE @recipient_group_prefix
ORDER BY g.id, m.user_id;

-- 将查询到的 alarm_id 写入 Postman/HTTP Client 环境变量 internalAlarmId。
SELECT alarm_cid, alarm_id, table_suffix, device_sn, alarm_type,
       route_status, alarm_beginTime, alarm_endTime
FROM hpis_alarm.alarm_cid_index
WHERE alarm_cid LIKE @alarm_cid_prefix
ORDER BY created_time DESC;

SELECT w.workorder_id, w.alarm_id, w.workorder_no, w.workorder_config_id,
       w.status, w.assignee_id, w.assignee_name, w.title, w.handle_result,
       w.tenant_id, w.del_flag, w.create_by, w.create_time, w.update_by, w.update_time
FROM hpis_alarm.alarm_workorder w
JOIN hpis_alarm.alarm_cid_index i ON i.alarm_id = w.alarm_id
WHERE i.alarm_cid LIKE @alarm_cid_prefix
ORDER BY w.workorder_id DESC;

-- 非分片/兼容基础表可直接核验；启用月度分片时，先从 alarm_cid_index.table_suffix
-- 找到对应 alarm_handle_{suffix}，把下列表名替换为实际分片后只读执行。
SELECT h.alarm_id, h.workorder_id, h.handle_status, h.handler_id, h.handler_name,
       h.opinion, h.handle_picture, h.handle_time, h.update_time
FROM hpis_alarm.alarm_handle h
JOIN hpis_alarm.alarm_cid_index i ON i.alarm_id = h.alarm_id
WHERE i.alarm_cid LIKE @alarm_cid_prefix
ORDER BY h.alarm_id;

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
