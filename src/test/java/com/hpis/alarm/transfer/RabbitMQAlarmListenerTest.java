package com.hpis.alarm.transfer;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.service.AlarmStopEventService;
import com.hpis.alarm.service.IAlarmService;
import com.hpis.alarm.service.support.AlarmDeviceCacheMissingException;
import com.hpis.common.core.constant.OperCodeConstants;
import com.rabbitmq.client.Channel;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@RunWith(MockitoJUnitRunner.class)
public class RabbitMQAlarmListenerTest {

    @Mock
    private IAlarmService alarmService;
    @Mock
    private AlarmStopEventService alarmStopEventService;
    @Mock
    private Channel channel;

    private RabbitMQAlarmListener listener;

    @Before
    public void setUp() {
        listener = new RabbitMQAlarmListener();
        ReflectionTestUtils.setField(listener, "alarmService", alarmService);
        ReflectionTestUtils.setField(listener, "alarmStopEventService", alarmStopEventService);
    }

    @Test
    public void startMessageUsesSingleInsertAndAcksAfterSuccess() throws Exception {
        listener.listenMessage(message(OperCodeConstants.ALARM_PUSH, 101L), channel);

        verify(alarmService).insertAlarm(any(JSONObject.class));
        verify(channel).basicAck(101L, false);
        verify(channel, never()).basicNack(101L, false, true);
    }

    @Test
    public void startInsertFailureNacksWithRequeue() throws Exception {
        doThrow(new RuntimeException("db failed"))
                .when(alarmService).insertAlarm(any(JSONObject.class));

        listener.listenMessage(message(OperCodeConstants.ALARM_PUSH, 102L), channel);

        verify(channel).basicNack(102L, false, true);
        verify(channel, never()).basicAck(102L, false);
    }

    @Test
    public void deviceCacheMissingIsAckDrop() throws Exception {
        doThrow(new AlarmDeviceCacheMissingException("A-103", "D-103", 1, "1"))
                .when(alarmService).insertAlarm(any(JSONObject.class));

        listener.listenMessage(message(OperCodeConstants.ALARM_PUSH, 103L), channel);

        verify(channel).basicAck(103L, false);
        verify(channel, never()).basicNack(103L, false, true);
    }

    @Test
    public void invalidMessageIsRejectedWithoutRequeue() throws Exception {
        Message invalid = new Message("not-json".getBytes(StandardCharsets.UTF_8), properties(104L));

        listener.listenMessage(invalid, channel);

        verify(channel).basicReject(104L, false);
        verify(alarmService, never()).insertAlarm(any(JSONObject.class));
    }

    @Test
    public void stopMessageRecordsStopBeforeAck() throws Exception {
        listener.listenMessage(message(OperCodeConstants.ALARM_STOP, 105L), channel);

        verify(alarmStopEventService).recordStop(any(JSONObject.class));
        verify(channel).basicAck(105L, false);
    }

    private Message message(Integer operCode, long deliveryTag) {
        JSONObject rawData = new JSONObject();
        rawData.put("alarmId", "A-" + deliveryTag);
        rawData.put("deviceSn", "D-" + deliveryTag);
        rawData.put("sceneType", 1);
        rawData.put("alarmType", 1);

        JSONObject cmdData = new JSONObject();
        cmdData.put("operCode", operCode);
        cmdData.put("rawData", rawData);
        cmdData.put("version", 1);

        JSONObject envelope = new JSONObject();
        envelope.put("cmd", "dataSync");
        envelope.put("cmdData", cmdData);
        envelope.put("cmdSeq", 1);
        envelope.put("servId", "hp_access");
        envelope.put("times", 1L);

        return new Message(envelope.toJSONString().getBytes(StandardCharsets.UTF_8), properties(deliveryTag));
    }

    private MessageProperties properties(long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        return properties;
    }
}
