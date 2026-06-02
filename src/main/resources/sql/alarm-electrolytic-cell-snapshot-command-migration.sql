-- Phase B：电解槽当前点位快照异步投影命令表。
-- 该表可先于 ASYNC 灰度上线。SYNC 模式不使用；DUAL_WRITE 用于核对；ASYNC 仅写命令并由独立 worker 投影。
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
