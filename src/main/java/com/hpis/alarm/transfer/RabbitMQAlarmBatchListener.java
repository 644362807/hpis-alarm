package com.hpis.alarm.transfer;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.config.AlarmBatchProperties;
import com.hpis.alarm.service.AlarmInsertBatchConsumeService;
import com.hpis.alarm.service.AlarmInsertConsumeResult;
import com.hpis.alarm.service.AlarmStopEventService;
import com.hpis.alarm.service.IAlarmColorService;
import com.hpis.alarm.service.IAlarmService;
import com.hpis.alarm.service.support.AlarmDeviceCacheMissingException;
import com.hpis.common.core.constant.OperCodeConstants;
import com.hpis.common.core.constant.RabbitQueueNameConstans;
import com.hpis.common.core.enums.OperCodeEnums;
import com.hpis.common.core.utils.NetDataTypeTransform;
import com.hpis.common.websocket.model.TransferCommandObject;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * RabbitMQ consumer batch 报警入口。
 *
 * <p>开启 {@code alarm.batch.insertConsumerBatchEnabled=true} 后，本类接管 {@code alarm_queue}，
 * 旧 {@code RabbitMQAlarmListener} 会被条件关闭，避免两个 listener 同时消费同一个队列。</p>
 *
 * <p>本类的核心边界：Spring AMQP 负责收集一批 {@link Message}，start 消息批量持久化；
 * stop/status 消息第一阶段仍走旧单条业务语义。所有 ack/nack 都在当前 listener 线程按 deliveryTag
 * 逐条执行，不使用 {@code multiple=true}，避免部分成功/失败时误确认相邻消息。</p>
 */
@Configuration
@Slf4j
@ConditionalOnProperty(prefix = "alarm.batch", name = "insert-consumer-batch-enabled", havingValue = "true")
public class RabbitMQAlarmBatchListener {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private IAlarmService alarmService;

    @Autowired
    private IAlarmColorService alarmColorService;

    @Autowired(required = false)
    private AlarmStopEventService alarmStopEventService;

    @Autowired
    private AlarmInsertBatchConsumeService batchConsumeService;

    @Autowired
    private AlarmBatchProperties batchProperties;

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

    @Bean(name = "alarmBatchRabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory alarmBatchRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        /*
         * 这里使用 Spring AMQP 原生 consumer batch，而不是自研内存队列。
         * prefetch 至少等于 batchSize，Rabbit 容器才能在高峰期稳定交付真实批量；
         * ackMode 必须是 MANUAL，保证每条消息按业务结果单独确认或重投。
         */
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        int[] concurrency = batchProperties.safeInsertConsumerBatchConcurrencyRange();
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setDeBatchingEnabled(true);
        factory.setBatchSize(batchProperties.safeInsertConsumerBatchSize());
        factory.setReceiveTimeout(batchProperties.safeInsertConsumerBatchReceiveTimeoutMs());
        factory.setPrefetchCount(batchProperties.safeInsertConsumerBatchPrefetch());
        factory.setConcurrentConsumers(concurrency[0]);
        factory.setMaxConcurrentConsumers(concurrency[1]);
        log.info("alarm batch listener factory enabled, batchSize={}, receiveTimeoutMs={}, prefetch={}, concurrency={}-{}",
                batchProperties.safeInsertConsumerBatchSize(),
                batchProperties.safeInsertConsumerBatchReceiveTimeoutMs(),
                batchProperties.safeInsertConsumerBatchPrefetch(),
                concurrency[0], concurrency[1]);
        return factory;
    }

    @RabbitListener(queues = RabbitQueueNameConstans.ALARM_QUEUE,
            containerFactory = "alarmBatchRabbitListenerContainerFactory")
    public void listenMessages(List<Message> messages, Channel channel) {
        /*
         * 同一批里可能混有 start、stop、颜色同步等不同 operCode。
         * 第一阶段不能假设整批都是 start：start 先收集后批量持久化，其他消息仍按旧单条链路处理。
         */
        if (messages == null || messages.isEmpty()) {
            return;
        }
        long startMs = System.currentTimeMillis();
        List<AlarmMqMessage> startMessages = new ArrayList<>();
        List<AlarmMqMessage> directMessages = new ArrayList<>();
        int rejectedCount = 0;

        for (Message message : messages) {
            AlarmMqMessage mqMessage;
            try {
                mqMessage = parseMessage(message);
            } catch (InvalidAlarmMqMessageException ex) {
                rejectedCount++;
                log.error("[hpis-alarm]-rabbitmq batch message invalid, reject, deliveryTag={}, error={}",
                        deliveryTag(message), ex.getMessage(), ex);
                reject(channel, deliveryTag(message));
                continue;
            }
            if (OperCodeConstants.ALARM_PUSH.intValue() == mqMessage.operCode.intValue()) {
                startMessages.add(mqMessage);
            } else {
                directMessages.add(mqMessage);
            }
        }

        /*
         * 同一批里可能同时包含同 alarmCid 的 start 和 stop。
         * 先批量持久化 start，再处理 stop/color direct 消息，可以降低 stop 先扫不到 route
         * 而被 ROUTE_MISSING 标记 FAILED 的窗口；direct 消息之间仍保持原批内相对顺序。
         */
        BatchAckStats startStats = processStartMessages(startMessages, channel);
        for (AlarmMqMessage directMessage : directMessages) {
            processDirectMessage(directMessage, channel);
        }
        if (batchProperties.isInsertConsumerBatchLogEnabled()) {
            log.info("alarm batch listener stage=BATCH_CONSUME_DONE actualBatchSize={}, startCount={}, directCount={}, rejectedCount={}, ackCount={}, nackCount={}, costMs={}, sampleAlarmCids={}",
                    messages.size(), startMessages.size(), directMessages.size(), rejectedCount,
                    startStats.ackCount, startStats.nackCount, System.currentTimeMillis() - startMs,
                    sampleAlarmCids(startMessages));
        }
    }

    private BatchAckStats processStartMessages(List<AlarmMqMessage> startMessages, Channel channel) {
        BatchAckStats stats = new BatchAckStats();
        if (startMessages.isEmpty()) {
            return stats;
        }
        List<JSONObject> rawDataList = startMessages.stream()
                .map(message -> message.rawData)
                .collect(Collectors.toList());
        List<AlarmInsertConsumeResult> results;
        try {
            results = batchConsumeService.processStartBatch(rawDataList);
        } catch (Exception ex) {
            /*
             * 整批服务异常时只能逐条 nack/requeue。
             * 这里不能 basicNack(multiple=true)，因为 batch 中 deliveryTag 不一定连续，也可能夹杂已处理直通消息。
             */
            log.error("[hpis-alarm]-rabbitmq batch start processing failed unexpectedly, requeue all, size={}, error={}",
                    startMessages.size(), ex.getMessage(), ex);
            for (AlarmMqMessage mqMessage : startMessages) {
                requeue(channel, mqMessage.deliveryTag);
                stats.nackCount++;
            }
            return stats;
        }
        for (int i = 0; i < startMessages.size(); i++) {
            AlarmInsertConsumeResult result = i < results.size() ? results.get(i) : AlarmInsertConsumeResult.FAIL;
            if (result != null && result.shouldAck()) {
                // SUCCESS/SKIP/DROP 都是业务终态，可以 ack；FAIL 必须 requeue，交给幂等兜底处理重复消费。
                ack(channel, startMessages.get(i).deliveryTag);
                stats.ackCount++;
            } else {
                requeue(channel, startMessages.get(i).deliveryTag);
                stats.nackCount++;
            }
        }
        if (batchProperties.isInsertConsumerBatchLogEnabled() || stats.nackCount > 0) {
            log.info("alarm batch listener stage=START_BATCH_RESULT startCount={}, resultCounts={}, ackCount={}, nackCount={}, sampleAlarmCids={}",
                    startMessages.size(), resultCounts(results), stats.ackCount, stats.nackCount,
                    sampleAlarmCids(startMessages));
        }
        return stats;
    }

    private void processDirectMessage(AlarmMqMessage mqMessage, Channel channel) {
        /*
         * stop/status 第一阶段不做批量语义变化。
         * stop 仍先可靠写入 alarm_stop_event 后 ack；颜色同步仍同步处理，失败则 requeue。
         */
        try {
            if (OperCodeConstants.ALARM_STOP.intValue() == mqMessage.operCode.intValue()) {
                if (alarmStopEventService != null) {
                    alarmStopEventService.recordStop(mqMessage.rawData);
                } else {
                    alarmService.alarmStop(mqMessage.rawData);
                }
            } else if (OperCodeEnums.SYNC_COLOR_SETTING.getKey().intValue() == mqMessage.operCode.intValue()) {
                alarmColorService.insertOrUpdateAlarmColor(mqMessage.rawData);
            }
            ack(channel, mqMessage.deliveryTag);
        } catch (AlarmDeviceCacheMissingException ex) {
            log.warn("[hpis-alarm]-rabbitmq batch direct message device cache missing, ack drop, deliveryTag={}, alarmCid={}, error={}",
                    mqMessage.deliveryTag, ex.getAlarmCid(), ex.getMessage());
            ack(channel, mqMessage.deliveryTag);
        } catch (Exception ex) {
            log.error("[hpis-alarm]-rabbitmq batch direct message failed, requeue, deliveryTag={}, operCode={}, error={}",
                    mqMessage.deliveryTag, mqMessage.operCode, ex.getMessage(), ex);
            requeue(channel, mqMessage.deliveryTag);
        }
    }

    private AlarmMqMessage parseMessage(Message message) {
        /*
         * 协议错误属于坏消息，后续重投也无法恢复，因此 listener 会 reject 且不 requeue。
         * 业务处理失败和协议错误必须区分，否则坏消息会无限占用队列。
         */
        long deliveryTag = deliveryTag(message);
        byte[] body = message.getBody();
        String msg = NetDataTypeTransform.ByteArraytoString(body, body.length);
        TransferCommandObject transferCommandObject;
        try {
            transferCommandObject = JSONObject.parseObject(msg, TransferCommandObject.class);
        } catch (Exception ex) {
            throw new InvalidAlarmMqMessageException("message protocol parse failed", ex);
        }
        if (transferCommandObject == null || transferCommandObject.getCmdData() == null) {
            throw new InvalidAlarmMqMessageException("missing cmdData");
        }
        Object rawDataObj = transferCommandObject.getCmdData().getRawData();
        if (rawDataObj == null) {
            throw new InvalidAlarmMqMessageException("missing rawData");
        }
        Integer operCode = transferCommandObject.getCmdData().getOperCode();
        if (operCode == null) {
            throw new InvalidAlarmMqMessageException("missing operCode");
        }
        JSONObject rawData = JSONObject.parseObject(rawDataObj.toString());
        return new AlarmMqMessage(deliveryTag, operCode, rawData);
    }

    private long deliveryTag(Message message) {
        return message.getMessageProperties().getDeliveryTag();
    }

    private Map<AlarmInsertConsumeResult, Integer> resultCounts(List<AlarmInsertConsumeResult> results) {
        Map<AlarmInsertConsumeResult, Integer> counts = new EnumMap<>(AlarmInsertConsumeResult.class);
        if (results == null) {
            return counts;
        }
        for (AlarmInsertConsumeResult result : results) {
            AlarmInsertConsumeResult key = result == null ? AlarmInsertConsumeResult.FAIL : result;
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        return counts;
    }

    private List<String> sampleAlarmCids(List<AlarmMqMessage> messages) {
        return messages.stream()
                .map(message -> message.rawData == null ? null : message.rawData.getString("alarmId"))
                .limit(5)
                .collect(Collectors.toList());
    }

    private void ack(Channel channel, long deliveryTag) {
        try {
            // 逐条 ack，不能 multiple=true，避免同批部分失败时误确认未成功消息。
            channel.basicAck(deliveryTag, false);
        } catch (IOException ex) {
            log.error("[hpis-alarm]-rabbitmq batch ack failed, deliveryTag={}", deliveryTag, ex);
        }
    }

    private void requeue(Channel channel, long deliveryTag) {
        try {
            // 逐条 nack 并 requeue，依赖 alarmCid/route/Redis 去重处理重复消费。
            channel.basicNack(deliveryTag, false, true);
        } catch (IOException ex) {
            log.error("[hpis-alarm]-rabbitmq batch nack requeue failed, deliveryTag={}", deliveryTag, ex);
        }
    }

    private void reject(Channel channel, long deliveryTag) {
        try {
            // 协议坏消息不重回队列，避免无限重试阻塞正常报警。
            channel.basicReject(deliveryTag, false);
        } catch (IOException ex) {
            log.error("[hpis-alarm]-rabbitmq batch reject failed, deliveryTag={}", deliveryTag, ex);
        }
    }

    private static final class AlarmMqMessage {
        /** RabbitMQ deliveryTag，必须和每条业务结果一一对应后逐条 ack/nack。 */
        private final long deliveryTag;
        private final Integer operCode;
        private final JSONObject rawData;

        private AlarmMqMessage(long deliveryTag, Integer operCode, JSONObject rawData) {
            this.deliveryTag = deliveryTag;
            this.operCode = operCode;
            this.rawData = rawData;
        }
    }

    private static final class BatchAckStats {
        private int ackCount;
        private int nackCount;
    }

    private static final class InvalidAlarmMqMessageException extends RuntimeException {
        private InvalidAlarmMqMessageException(String message) {
            super(message);
        }

        private InvalidAlarmMqMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
