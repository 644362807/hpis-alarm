package com.hpis.alarm.transfer;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.service.support.AlarmDeviceCacheMissingException;
import com.hpis.alarm.service.AlarmStopEventService;
import com.hpis.alarm.service.IAlarmColorService;
import com.hpis.alarm.service.IAlarmService;
import com.hpis.common.core.constant.OperCodeConstants;
import com.hpis.common.core.constant.RabbitQueueNameConstans;
import com.hpis.common.core.enums.OperCodeEnums;
import com.hpis.common.core.utils.NetDataTypeTransform;
import com.hpis.common.websocket.model.TransferCommandObject;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Properties;

/**
 * 报警 MQ 消息入口。
 *
 * <p>旧逻辑在收到消息后直接扔进线程池，RabbitMQ listener 返回后消息就会被自动确认。
 * 当线程池队列满或任务后续异常时，stop 消息已经无法从 MQ 重放，最终表现为大量 alarm_endTime 丢失。
 * 本阶段改为手动 ack：start 必须业务写入成功后 ack；stop 必须先写入 alarm_stop_event 后 ack，
 * 后续由批量 worker 可靠关闭业务分片。</p>
 */
@Configuration
@Slf4j
// consumer batch 打开时必须关闭旧 listener，避免两个入口同时消费 alarm_queue 导致重复入库和重复 ack。
@ConditionalOnProperty(prefix = "alarm.batch", name = "insert-consumer-batch-enabled", havingValue = "false", matchIfMissing = true)
public class RabbitMQAlarmListener {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private IAlarmService alarmService;

    @Autowired
    private IAlarmColorService alarmColorService;

    @Autowired(required = false)
    private AlarmStopEventService alarmStopEventService;

    @Bean
    public Queue queue() {
        RabbitAdmin rabbitAdmin = new RabbitAdmin(rabbitTemplate);
        Properties properties = rabbitAdmin.getQueueProperties(RabbitQueueNameConstans.ALARM_QUEUE);
        if (properties == null) {
            log.info("监听队列不存在，创建 queue={}", RabbitQueueNameConstans.ALARM_QUEUE);
            return new Queue(RabbitQueueNameConstans.ALARM_QUEUE, true, false, false);
        }
        return null;
    }

    @Bean
    public Queue cehckPushQueue() {
        RabbitAdmin rabbitAdmin = new RabbitAdmin(rabbitTemplate);
        Properties properties = rabbitAdmin.getQueueProperties(RabbitQueueNameConstans.PUSH_ALARM);
        if (properties == null) {
            log.info("监听队列不存在，创建 queue={}", RabbitQueueNameConstans.PUSH_ALARM);
            return new Queue(RabbitQueueNameConstans.PUSH_ALARM, true, false, false);
        }
        return null;
    }

    @RabbitListener(queues = RabbitQueueNameConstans.ALARM_QUEUE, ackMode = "MANUAL",concurrency="10-36")
    public void listenMessage(Message message, Channel channel) {
        /*
         * 旧单条 listener 保留作为默认生产路径和回滚路径。
         * 所有分支都必须在业务达到终态后再 ack；处理失败统一 nack/requeue，设备缓存缺失按坏业务消息 ack 丢弃。
         */
        byte[] body = message.getBody();
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String msg = NetDataTypeTransform.ByteArraytoString(body, body.length);
        log.info("[hpis-alarm]-rabbitmq 收到消息: {}", msg);

        TransferCommandObject transferCommandObject;
        try {
            transferCommandObject = JSONObject.parseObject(msg, TransferCommandObject.class);
        } catch (Exception ex) {
            log.error("[hpis-alarm]-rabbitmq 消息协议解析失败，丢弃消息，errorMsg={}", ex.getMessage(), ex);
            reject(channel, deliveryTag);
            return;
        }

        try {
            if (transferCommandObject == null || transferCommandObject.getCmdData() == null) {
                log.error("[hpis-alarm]-rabbitmq 消息缺少 cmdData，丢弃消息，msg={}", msg);
                reject(channel, deliveryTag);
                return;
            }
            Object rawDataObj = transferCommandObject.getCmdData().getRawData();
            if (rawDataObj == null) {
                log.error("[hpis-alarm]-rabbitmq 消息缺少 rawData，丢弃消息，msg={}", msg);
                reject(channel, deliveryTag);
                return;
            }
            JSONObject rawData = JSONObject.parseObject(rawDataObj.toString());
            Integer operCode = transferCommandObject.getCmdData().getOperCode();
            if (operCode == null) {
                log.error("[hpis-alarm]-rabbitmq 消息缺少 operCode，丢弃消息，msg={}", msg);
                reject(channel, deliveryTag);
                return;
            }
            if (OperCodeConstants.ALARM_PUSH.intValue() == operCode.intValue()) {
                // 旧单条 listener 现在只保留同步单条 start 语义；批量消费统一由 RabbitMQAlarmBatchListener 接管。
                alarmService.insertAlarm(rawData);
            } else if (OperCodeConstants.ALARM_STOP.intValue() == operCode.intValue()) {
                if (alarmStopEventService != null) {
                    // stop 只要可靠 upsert 到 alarm_stop_event 就可以 ack，真正关闭由后台 worker 批量完成。
                    alarmStopEventService.recordStop(rawData);
                } else {
                    alarmService.alarmStop(rawData);
                }
            } else if (OperCodeEnums.SYNC_COLOR_SETTING.getKey().intValue() == operCode.intValue()) {
                alarmColorService.insertOrUpdateAlarmColor(rawData);
            }
            ack(channel, deliveryTag);
        } catch (AlarmDeviceCacheMissingException ex) {
            log.warn("[hpis-alarm]-rabbitmq 设备缓存缺失，记录并丢弃消息，alarmCid={}, deviceSn={}, sceneType={}, cameraType={}, error={}",
                    ex.getAlarmCid(), ex.getDeviceSn(), ex.getSceneType(), ex.getCameraType(), ex.getMessage());
            ack(channel, deliveryTag);
        } catch (Exception ex) {
            log.error("[hpis-alarm]-rabbitmq 数据处理失败，消息重新入队，errorMsg={}", ex.getMessage(), ex);
            requeue(channel, deliveryTag);
        }
    }

    private void ack(Channel channel, long deliveryTag) {
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException ex) {
            log.error("[hpis-alarm]-rabbitmq ack 失败，deliveryTag={}", deliveryTag, ex);
        }
    }

    private void requeue(Channel channel, long deliveryTag) {
        try {
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException ex) {
            log.error("[hpis-alarm]-rabbitmq nack requeue 失败，deliveryTag={}", deliveryTag, ex);
        }
    }

    private void reject(Channel channel, long deliveryTag) {
        try {
            channel.basicReject(deliveryTag, false);
        } catch (IOException ex) {
            log.error("[hpis-alarm]-rabbitmq reject 失败，deliveryTag={}", deliveryTag, ex);
        }
    }
}
