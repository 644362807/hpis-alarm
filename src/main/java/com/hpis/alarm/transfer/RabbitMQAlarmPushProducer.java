package com.hpis.alarm.transfer;

import com.alibaba.fastjson.JSONObject;
import com.hpis.common.core.constant.RabbitQueueNameConstans;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


/**
 * PUSH_ALARM队列的消息生产者，负责发送推送消息
 */
@Component
@Slf4j
public class RabbitMQAlarmPushProducer {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送告警推送消息到PUSH_ALARM队列
     * @param alarmId 告警ID
     * @param content 推送内容
     * @param userId 接收用户ID
     */
    public void sendAlarmPushMessage(String alarmId, String content, String userId) {
        try {
            // 构建消息体
            JSONObject message = new JSONObject();
            message.put("alarmId", alarmId);
            message.put("content", content);
            message.put("userId", userId);
            message.put("timestamp", System.currentTimeMillis());

            // 发送消息到PUSH_ALARM队列
            rabbitTemplate.convertAndSend(RabbitQueueNameConstans.PUSH_ALARM, message.toJSONString());
            log.info("[hpis-alarm]-发送告警推送消息成功，alarmId:{}，queue:{}", 
                    alarmId, RabbitQueueNameConstans.PUSH_ALARM);
        } catch (Exception e) {
            log.error("[hpis-alarm]-发送告警推送消息失败，alarmId:{}，error:{}", 
                    alarmId, e.getMessage(), e);
        }
    }

    /**
     * 发送自定义消息到PUSH_ALARM队列
     * @param message 自定义消息对象
     */
    public void sendCustomPushMessage(JSONObject message) {
        try {
            rabbitTemplate.convertAndSend(RabbitQueueNameConstans.PUSH_ALARM, message.toJSONString());
            log.info("[hpis-alarm]-发送自定义推送消息成功，message:{}", message.toJSONString());
        } catch (Exception e) {
            log.error("[hpis-alarm]-发送自定义推送消息失败，error:{}", e.getMessage(), e);
        }
    }
}
