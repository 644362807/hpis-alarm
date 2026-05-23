package com.hpis.alarm.transfer;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.config.AlarmBatchProperties;
import com.hpis.alarm.service.AlarmInsertBatchConsumeService;
import com.hpis.alarm.service.AlarmInsertConsumeResult;
import com.hpis.alarm.service.AlarmStopEventService;
import com.hpis.alarm.service.IAlarmColorService;
import com.hpis.alarm.service.IAlarmService;
import com.hpis.common.core.constant.OperCodeConstants;
import com.hpis.common.core.enums.OperCodeEnums;
import com.rabbitmq.client.Channel;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class RabbitMQAlarmBatchListenerTest {

    @Mock
    private IAlarmService alarmService;
    @Mock
    private IAlarmColorService alarmColorService;
    @Mock
    private AlarmStopEventService alarmStopEventService;
    @Mock
    private AlarmInsertBatchConsumeService batchConsumeService;
    @Mock
    private Channel channel;

    private RabbitMQAlarmBatchListener listener;

    @Before
    public void setUp() {
        listener = new RabbitMQAlarmBatchListener();
        ReflectionTestUtils.setField(listener, "alarmService", alarmService);
        ReflectionTestUtils.setField(listener, "alarmColorService", alarmColorService);
        ReflectionTestUtils.setField(listener, "alarmStopEventService", alarmStopEventService);
        ReflectionTestUtils.setField(listener, "batchConsumeService", batchConsumeService);
        ReflectionTestUtils.setField(listener, "batchProperties", new AlarmBatchProperties());
    }

    @Test
    public void startMessagesUseOneBatchProcessorAndAckPerResult() throws Exception {
        when(batchConsumeService.processStartBatch(any(List.class)))
                .thenReturn(Arrays.asList(
                        AlarmInsertConsumeResult.SUCCESS,
                        AlarmInsertConsumeResult.FAIL,
                        AlarmInsertConsumeResult.DROP));

        listener.listenMessages(Arrays.asList(
                message(OperCodeConstants.ALARM_PUSH, 201L),
                message(OperCodeConstants.ALARM_PUSH, 202L),
                message(OperCodeConstants.ALARM_PUSH, 203L)), channel);

        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(batchConsumeService).processStartBatch(captor.capture());
        assertEquals(3, captor.getValue().size());
        verify(channel).basicAck(201L, false);
        verify(channel).basicNack(202L, false, true);
        verify(channel).basicAck(203L, false);
    }

    @Test
    public void mixedBatchKeepsStartBatchAndDirectStopColorDispatch() throws Exception {
        when(batchConsumeService.processStartBatch(any(List.class)))
                .thenReturn(Arrays.asList(AlarmInsertConsumeResult.SUCCESS));

        listener.listenMessages(Arrays.asList(
                message(OperCodeConstants.ALARM_STOP, 301L),
                message(OperCodeConstants.ALARM_PUSH, 302L),
                message(OperCodeEnums.SYNC_COLOR_SETTING.getKey(), 303L)), channel);

        verify(alarmStopEventService).recordStop(any(JSONObject.class));
        verify(alarmColorService).insertOrUpdateAlarmColor(any(JSONObject.class));
        verify(batchConsumeService).processStartBatch(any(List.class));
        verify(channel).basicAck(301L, false);
        verify(channel).basicAck(302L, false);
        verify(channel).basicAck(303L, false);
    }

    @Test
    public void mixedBatchPersistsStartBeforeDirectStop() throws Exception {
        when(batchConsumeService.processStartBatch(any(List.class)))
                .thenReturn(Arrays.asList(AlarmInsertConsumeResult.SUCCESS));

        listener.listenMessages(Arrays.asList(
                message(OperCodeConstants.ALARM_PUSH, 501L),
                message(OperCodeConstants.ALARM_STOP, 502L)), channel);

        /*
         * 同一批里 start 和 stop 可能属于同一个 alarmCid。
         * 先处理 start 可以让 route 先落库，再处理 stop，降低 ROUTE_MISSING 误失败窗口。
         */
        InOrder inOrder = inOrder(batchConsumeService, alarmStopEventService);
        inOrder.verify(batchConsumeService).processStartBatch(any(List.class));
        inOrder.verify(alarmStopEventService).recordStop(any(JSONObject.class));
    }

    @Test
    public void invalidMessageIsRejectedWithoutBlockingOtherMessages() throws Exception {
        when(batchConsumeService.processStartBatch(any(List.class)))
                .thenReturn(Arrays.asList(AlarmInsertConsumeResult.SUCCESS));

        Message invalid = new Message("not-json".getBytes(StandardCharsets.UTF_8), properties(401L));
        listener.listenMessages(Arrays.asList(invalid, message(OperCodeConstants.ALARM_PUSH, 402L)), channel);

        verify(channel).basicReject(401L, false);
        verify(channel).basicAck(402L, false);
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
