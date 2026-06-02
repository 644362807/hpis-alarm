package com.hpis.alarm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Date;

/**
 * MQ stop 批量可靠入队使用的最小记录。
 *
 * <p>这里只保存 cid 和结束时间，避免 batch listener 把 RabbitMQ deliveryTag 或原始 JSON
 * 传入数据库层。ack 仍由 listener 在批量 upsert 成功后逐条执行。</p>
 */
@Data
@AllArgsConstructor
public class AlarmStopRecord {

    private String alarmCid;

    private Date stopTime;
}
