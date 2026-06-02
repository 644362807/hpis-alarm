-- alarm_stop_event PROCESSING claim 一次性迁移脚本。
-- 上线新 stop worker 前在低峰期执行一次。脚本包含 ALTER TABLE，不要在应用启动阶段自动执行。
-- 执行前请先确认字段和索引尚未存在；已经执行过的环境不要重复运行。

ALTER TABLE alarm_stop_event
  ADD COLUMN event_version bigint NOT NULL DEFAULT 0 COMMENT 'stop时间版本，更晚stop到达时递增' AFTER stop_time,
  ADD COLUMN applied_stop_time datetime DEFAULT NULL COMMENT '最近一次成功写入业务分片的stop时间' AFTER event_version,
  ADD COLUMN lock_token varchar(64) DEFAULT NULL COMMENT 'PROCESSING认领批次令牌' AFTER last_error,
  ADD COLUMN locked_at datetime DEFAULT NULL COMMENT 'PROCESSING认领时间，用于超时回收' AFTER lock_token,
  ADD COLUMN available_time datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次允许认领时间，用于延迟重试' AFTER locked_at,
  ADD KEY idx_stop_event_claim (event_status, available_time, created_time, id),
  ADD KEY idx_stop_event_processing_timeout (event_status, locked_at);
