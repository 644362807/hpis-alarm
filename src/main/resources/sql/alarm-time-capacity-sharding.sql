-- Alarm 时间容量分片基础 SQL
-- 目标：
-- 1. 补齐绑定表分片时间字段。
-- 2. 创建月内容量切片小表 alarm_shard_slice。
-- 3. 创建热点 cid 路由表 alarm_cid_index。
-- 4. 创建长时间未关闭报警滞留路由表 alarm_cid_stale_index。
-- 5. 不再创建全量 alarm_shard_route。

DELIMITER $$

DROP PROCEDURE IF EXISTS add_alarm_begin_time_column $$
CREATE PROCEDURE add_alarm_begin_time_column(IN table_name_param varchar(128))
BEGIN
  IF EXISTS (
    SELECT 1
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = table_name_param
  ) AND NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = table_name_param
      AND column_name = 'alarm_beginTime'
  ) THEN
    SET @ddl_sql = CONCAT(
      'ALTER TABLE `', table_name_param,
      '` ADD COLUMN `alarm_beginTime` datetime NULL COMMENT ''报警开始时间，用于时间分片路由'''
    );
    PREPARE stmt FROM @ddl_sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL add_alarm_begin_time_column('alarm_handle');
CALL add_alarm_begin_time_column('alarm_electrolytic_cell');
CALL add_alarm_begin_time_column('alarm_handle_0');
CALL add_alarm_begin_time_column('alarm_handle_1');
CALL add_alarm_begin_time_column('alarm_handle_2');
CALL add_alarm_begin_time_column('alarm_handle_3');
CALL add_alarm_begin_time_column('alarm_handle_4');
CALL add_alarm_begin_time_column('alarm_electrolytic_cell_0');
CALL add_alarm_begin_time_column('alarm_electrolytic_cell_1');
CALL add_alarm_begin_time_column('alarm_electrolytic_cell_2');
CALL add_alarm_begin_time_column('alarm_electrolytic_cell_3');
CALL add_alarm_begin_time_column('alarm_electrolytic_cell_4');

DROP PROCEDURE IF EXISTS add_alarm_begin_time_column;

CREATE TABLE IF NOT EXISTS alarm_shard_slice (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  month_key char(6) NOT NULL COMMENT '月份分片键，格式yyyyMM',
  slice_no int NOT NULL COMMENT '月内子分片序号',
  table_suffix varchar(16) NOT NULL COMMENT '物理表后缀，例如202604_00',
  current_rows bigint NOT NULL DEFAULT 0 COMMENT '当前子分片已预占行数，Segment模式下不等于真实业务行数',
  max_rows bigint NOT NULL COMMENT '当前子分片最大行数',
  status varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE可写，FULL已满，CLOSED停用',
  created_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_shard_slice (month_key, slice_no),
  UNIQUE KEY uk_alarm_shard_suffix (table_suffix)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报警月内容量切片元数据表';

CREATE TABLE IF NOT EXISTS alarm_cid_index (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  alarm_cid varchar(128) NOT NULL COMMENT '外部报警ID',
  alarm_id bigint NOT NULL COMMENT '系统内部报警ID',
  alarm_beginTime datetime NOT NULL COMMENT '报警开始时间',
  alarm_endTime datetime DEFAULT NULL COMMENT '报警结束时间',
  table_suffix varchar(16) NOT NULL COMMENT '业务分片后缀，例如202604_00',
  device_sn varchar(128) DEFAULT NULL COMMENT '设备SN',
  irms_sn varchar(128) DEFAULT NULL COMMENT '网关SN',
  alarm_type varchar(32) DEFAULT NULL COMMENT '报警类型',
  route_status varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE正在报警，CLOSED已关闭待清理',
  delete_after datetime DEFAULT NULL COMMENT '允许物理删除路由的时间',
  created_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_cid (alarm_cid),
  UNIQUE KEY uk_alarm_id (alarm_id),
  KEY idx_status_begin_time (route_status, alarm_beginTime),
  KEY idx_status_delete_after (route_status, delete_after),
  KEY idx_device_status (device_sn, route_status),
  KEY idx_irms_status (irms_sn, route_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报警外部ID热点路由表';

CREATE TABLE IF NOT EXISTS alarm_cid_stale_index (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  alarm_cid varchar(128) NOT NULL COMMENT '外部报警ID',
  alarm_id bigint NOT NULL COMMENT '系统内部报警ID',
  alarm_beginTime datetime NOT NULL COMMENT '报警开始时间',
  alarm_endTime datetime DEFAULT NULL COMMENT '报警结束时间',
  table_suffix varchar(16) NOT NULL COMMENT '业务分片后缀，例如202604_00',
  device_sn varchar(128) DEFAULT NULL COMMENT '设备SN',
  irms_sn varchar(128) DEFAULT NULL COMMENT '网关SN',
  alarm_type varchar(32) DEFAULT NULL COMMENT '报警类型',
  route_status varchar(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE滞留未关闭，CLOSED已关闭待清理',
  stale_time datetime NOT NULL COMMENT '转入滞留表时间',
  expire_time datetime NOT NULL COMMENT '滞留过期时间',
  delete_after datetime DEFAULT NULL COMMENT '允许物理删除路由的时间',
  created_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_cid (alarm_cid),
  UNIQUE KEY uk_alarm_id (alarm_id),
  KEY idx_status_begin_time (route_status, alarm_beginTime),
  KEY idx_status_delete_after (route_status, delete_after),
  KEY idx_device_status (device_sn, route_status),
  KEY idx_irms_status (irms_sn, route_status),
  KEY idx_expire_time (expire_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报警外部ID滞留路由表';

-- 如果现场确认 alarm_cid 不是全局唯一，请将 uk_alarm_cid 调整为：
-- ALTER TABLE alarm_cid_index DROP INDEX uk_alarm_cid, ADD UNIQUE KEY uk_alarm_cid_device (alarm_cid, device_sn);
-- ALTER TABLE alarm_cid_stale_index DROP INDEX uk_alarm_cid, ADD UNIQUE KEY uk_alarm_cid_device (alarm_cid, device_sn);

-- alarm_shard_route 为旧全量路由方案表。本方案不再读写该表。
-- 如果环境中已经创建该表，建议观察期后再归档或删除，不建议在本脚本中自动 DROP。
-- 2026-05-12 补充：消警不丢失第一阶段可靠缓冲表。
-- alarm_stop_event 只保存 PENDING/FAILED 以及短时间待清理的 APPLIED，不作为历史表。
CREATE TABLE IF NOT EXISTS alarm_stop_event (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  alarm_cid varchar(128) NOT NULL COMMENT '外部报警ID，即 MQ rawData.alarmId',
  stop_time datetime NOT NULL COMMENT '外部 stop 消息携带的结束时间',
  event_version bigint NOT NULL DEFAULT 0 COMMENT 'stop时间版本，更晚stop到达时递增',
  applied_stop_time datetime DEFAULT NULL COMMENT '最近一次成功写入业务分片的stop时间',
  event_status varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待认领，PROCESSING处理中，APPLIED已应用，FAILED人工处理',
  retry_count int NOT NULL DEFAULT 0 COMMENT '核心消警重试次数',
  last_error varchar(1024) DEFAULT NULL COMMENT '最后一次失败原因',
  lock_token varchar(64) DEFAULT NULL COMMENT 'PROCESSING认领批次令牌',
  locked_at datetime DEFAULT NULL COMMENT 'PROCESSING认领时间，用于超时回收',
  available_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次允许认领时间，用于延迟重试',
  created_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  applied_time datetime DEFAULT NULL COMMENT '成功写入业务分片的时间',
  delete_after datetime DEFAULT NULL COMMENT 'APPLIED 记录允许物理清理的时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_stop_event_cid (alarm_cid),
  KEY idx_stop_event_status_time (event_status, created_time),
  KEY idx_stop_event_claim (event_status, available_time, created_time, id),
  KEY idx_stop_event_processing_timeout (event_status, locked_at),
  KEY idx_stop_event_delete_after (event_status, delete_after)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报警停止事件短生命周期可靠缓冲表';

-- 消警后的远程同步、扩展表清理等副作用异步执行，避免阻塞 alarm_endTime 写入。
CREATE TABLE IF NOT EXISTS alarm_stop_side_effect_event (
  id bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  alarm_id bigint NOT NULL COMMENT '内部报警ID',
  alarm_cid varchar(128) DEFAULT NULL COMMENT '外部报警ID',
  table_suffix varchar(16) NOT NULL COMMENT '业务分片后缀',
  effect_type varchar(64) NOT NULL COMMENT 'IR_OFFLINE_RECOVER/TM_OFFLINE_RECOVER/EC_ECTYPE_DELETE/PUSH_NOTIFY',
  payload_json text DEFAULT NULL COMMENT '执行副作用所需最小上下文',
  effect_status varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING待执行，DONE已完成，FAILED人工处理',
  retry_count int NOT NULL DEFAULT 0 COMMENT '副作用重试次数',
  last_error varchar(1024) DEFAULT NULL COMMENT '最后一次失败原因',
  created_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_alarm_effect (alarm_id, effect_type),
  KEY idx_effect_status_time (effect_status, created_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报警停止后异步副作用事件表';

-- 电解槽当前点位快照可靠投影命令。历史扩展表仍同步写入，只有当前点位投影允许最终一致。
CREATE TABLE IF NOT EXISTS alarm_electrolytic_cell_snapshot_command (
  point_hash varchar(64) NOT NULL COMMENT '稳定点位 SHA-256',
  command_type varchar(16) NOT NULL COMMENT 'ACTIVE/DELETED',
  alarm_id bigint NOT NULL COMMENT '当前命令关联内部报警ID',
  alarm_begin_time datetime DEFAULT NULL COMMENT '当前报警开始时间',
  payload_json text NOT NULL COMMENT '生成点位快照投影所需最小上下文',
  command_status varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/DONE/FAILED',
  lock_token varchar(64) DEFAULT NULL COMMENT 'PROCESSING认领批次令牌',
  locked_at datetime DEFAULT NULL COMMENT 'PROCESSING认领时间',
  available_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次允许认领时间',
  version bigint NOT NULL DEFAULT 0 COMMENT '点位命令版本，防止旧worker覆盖新状态',
  retry_count int NOT NULL DEFAULT 0 COMMENT '处理重试次数',
  last_error varchar(1024) DEFAULT NULL COMMENT '最后一次失败原因',
  created_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (point_hash),
  KEY idx_ec_snapshot_claim (command_status, available_time, updated_time, point_hash),
  KEY idx_ec_snapshot_processing_timeout (command_status, locked_at),
  KEY idx_ec_snapshot_alarm_id (alarm_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电解槽当前点位快照可靠投影命令';
