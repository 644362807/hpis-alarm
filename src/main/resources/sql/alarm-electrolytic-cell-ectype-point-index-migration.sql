-- 电解槽当前点位表通过稳定主键和点位唯一键执行原子 upsert。
-- alarm_id 会随着点位最新报警变化，不能继续作为物理主键，否则并发 upsert 会搬动主键并争抢间隙锁。
-- 执行 ALTER 前必须先确认下面两个查询不返回记录。若存在记录，先由业务确认清理方式。
SELECT irms_sn, sequence_id, row_index, groove_number, observation_place, subdivide_number, COUNT(*) AS duplicate_count
FROM alarm_electrolytic_cell_ectype
GROUP BY irms_sn, sequence_id, row_index, groove_number, observation_place, subdivide_number
HAVING COUNT(*) > 1;

SELECT alarm_id, irms_sn, sequence_id, row_index, groove_number, observation_place, subdivide_number
FROM alarm_electrolytic_cell_ectype
WHERE alarm_id IS NULL
   OR irms_sn IS NULL
   OR sequence_id IS NULL
   OR row_index IS NULL
   OR groove_number IS NULL
   OR observation_place IS NULL
   OR subdivide_number IS NULL;

-- 已执行预览版普通索引的环境，先在低峰期执行：
-- ALTER TABLE alarm_electrolytic_cell_ectype DROP INDEX idx_ec_ectype_point;
ALTER TABLE alarm_electrolytic_cell_ectype
    DROP PRIMARY KEY,
    ADD COLUMN ectype_id BIGINT NOT NULL AUTO_INCREMENT FIRST,
    ADD PRIMARY KEY (ectype_id),
    ADD INDEX idx_ec_ectype_alarm_id (alarm_id),
    ADD UNIQUE INDEX uk_ec_ectype_point (
        irms_sn,
        sequence_id,
        row_index,
        groove_number,
        observation_place,
        subdivide_number
    );
