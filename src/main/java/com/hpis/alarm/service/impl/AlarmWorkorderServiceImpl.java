package com.hpis.alarm.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.domain.AlarmConfigure;
import com.hpis.alarm.domain.AlarmHandle;
import com.hpis.alarm.domain.AlarmWorkorder;
import com.hpis.alarm.enums.HandleStatusEnums;
import com.hpis.alarm.mapper.AlarmHandleMapper;
import com.hpis.alarm.mapper.AlarmMapper;
import com.hpis.alarm.mapper.AlarmWorkorderMapper;
import com.hpis.alarm.service.IAlarmConfigureService;
import com.hpis.alarm.service.IAlarmWorkorderService;
import com.hpis.alarm.transfer.RabbitMQAlarmPushProducer;
import com.hpis.common.core.exception.CustomException;
import com.hpis.common.core.utils.DateUtils;
import com.hpis.common.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.List;

/**
 * 报警工单服务实现。
 */
@Slf4j
@Service
public class AlarmWorkorderServiceImpl extends ServiceImpl<AlarmWorkorderMapper, AlarmWorkorder>
        implements IAlarmWorkorderService {

    private static final String WORKORDER_CREATED_EVENT_TYPE = "ALARM_WORKORDER_CREATED";

    @Resource
    private AlarmWorkorderMapper alarmWorkorderMapper;

    @Resource
    private AlarmMapper alarmMapper;

    @Resource
    private AlarmHandleMapper alarmHandleMapper;

    @Resource
    private IAlarmConfigureService alarmConfigureService;

    @Resource
    private RabbitMQAlarmPushProducer rabbitMQAlarmPushProducer;

    @Value("${push.open:false}")
    private boolean pushOpen;

    @Override
    public AlarmWorkorder selectAlarmWorkorderById(Long workorderId) {
        return alarmWorkorderMapper.selectAlarmWorkorderById(workorderId);
    }

    @Override
    public AlarmWorkorder selectAlarmWorkorderByAlarmId(Long alarmId) {
        return alarmWorkorderMapper.selectAlarmWorkorderByAlarmId(alarmId);
    }

    @Override
    public List<AlarmWorkorder> selectAlarmWorkorderList(AlarmWorkorder alarmWorkorder) {
        return alarmWorkorderMapper.selectAlarmWorkorderList(alarmWorkorder);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int createWorkorder(AlarmWorkorder alarmWorkorder) {
        if (alarmWorkorder == null || alarmWorkorder.getAlarmId() == null) {
            throw new CustomException("报警ID不能为空");
        }
        Alarm alarm = alarmMapper.selectAlarmById(alarmWorkorder.getAlarmId());
        if (alarm == null) {
            throw new CustomException("报警不存在，不能创建工单");
        }
        AlarmHandle alarmHandle = alarmHandleMapper.selectAlarmHandleByAlarmId(alarmWorkorder.getAlarmId());
        if (alarmHandle == null
                || !HandleStatusEnums.ALARM_STATUS_ENUMS_2.getKey().equals(alarmHandle.getHandleStatus())) {
            throw new CustomException("报警未确认，不能创建工单");
        }
        AlarmConfigure configure = resolveWorkorderConfigure(alarm);
        if (configure == null || configure.getWorkorderConfigId() == null) {
            throw new CustomException("当前报警配置未关联工单模板，不能创建工单");
        }

        alarmWorkorder.setWorkorderConfigId(configure.getWorkorderConfigId());
        alarmWorkorder.setTenantId(alarm.getTenantId());
        if (StringUtils.isBlank(alarmWorkorder.getWorkorderNo())) {
            alarmWorkorder.setWorkorderNo(buildWorkorderNo(alarmWorkorder.getAlarmId()));
        }
        if (StringUtils.isBlank(alarmWorkorder.getStatus())) {
            alarmWorkorder.setStatus("0");
        }
        if (StringUtils.isBlank(alarmWorkorder.getDelFlag())) {
            alarmWorkorder.setDelFlag("0");
        }
        if (StringUtils.isBlank(alarmWorkorder.getTitle())) {
            alarmWorkorder.setTitle("报警工单-" + alarmWorkorder.getAlarmId());
        }
        alarmWorkorder.setCreateTime(DateUtils.getNowDate());
        try {
            int inserted = alarmWorkorderMapper.insertAlarmWorkorder(alarmWorkorder);
            if (inserted > 0) {
                publishWorkorderCreatedAfterCommit(alarm, alarmWorkorder, configure);
            }
            return inserted;
        } catch (DuplicateKeyException ex) {
            throw new CustomException("该报警已创建工单");
        }
    }

    @Override
    public int updateWorkorder(AlarmWorkorder alarmWorkorder) {
        if (alarmWorkorder == null || alarmWorkorder.getWorkorderId() == null) {
            throw new CustomException("工单ID不能为空");
        }
        alarmWorkorder.setUpdateTime(DateUtils.getNowDate());
        return alarmWorkorderMapper.updateAlarmWorkorder(alarmWorkorder);
    }

    @Override
    public int transferWorkorder(AlarmWorkorder alarmWorkorder) {
        if (alarmWorkorder == null || alarmWorkorder.getWorkorderId() == null) {
            throw new CustomException("工单ID不能为空");
        }
        alarmWorkorder.setUpdateTime(DateUtils.getNowDate());
        return alarmWorkorderMapper.updateAlarmWorkorder(alarmWorkorder);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int completeWorkorder(AlarmWorkorder alarmWorkorder) {
        if (alarmWorkorder == null || alarmWorkorder.getWorkorderId() == null || alarmWorkorder.getAlarmId() == null) {
            throw new CustomException("工单ID和报警ID不能为空");
        }
        if (StringUtils.isBlank(alarmWorkorder.getStatus())) {
            alarmWorkorder.setStatus("2");
        }
        alarmWorkorder.setUpdateTime(DateUtils.getNowDate());
        int updated = alarmWorkorderMapper.updateAlarmWorkorder(alarmWorkorder);

        AlarmHandle alarmHandle = new AlarmHandle();
        alarmHandle.setAlarmId(alarmWorkorder.getAlarmId());
        alarmHandle.setWorkorderId(alarmWorkorder.getWorkorderId());
        alarmHandle.setHandlerId(alarmWorkorder.getAssigneeId());
        alarmHandle.setHandlerName(alarmWorkorder.getAssigneeName());
        alarmHandle.setHandleStatus(HandleStatusEnums.ALARM_STATUS_ENUMS_1.getKey());
        alarmHandle.setOpinion(alarmWorkorder.getHandleResult());
        alarmHandle.setHandleTime(DateUtils.getNowDate());
        alarmHandle.setUpdateTime(DateUtils.getNowDate());
        alarmHandle.setUpdateBy(alarmWorkorder.getUpdateBy());
        int handleUpdated = alarmHandleMapper.updateAlarmHandle(alarmHandle);
        if (handleUpdated <= 0) {
            alarmHandle.setCreateTime(DateUtils.getNowDate());
            alarmHandleMapper.insertAlarmHandle(alarmHandle);
        }
        return updated;
    }

    @Override
    public int deleteAlarmWorkorderByIds(Long[] workorderIds) {
        return alarmWorkorderMapper.deleteAlarmWorkorderByIds(workorderIds);
    }

    private AlarmConfigure resolveWorkorderConfigure(Alarm alarm) {
        List<AlarmConfigure> configures = alarmConfigureService.selectEnabledForAlarm(
                alarm.getTenantId(), alarm.getSceneType(), alarm.getDeviceSn(), alarm.getAlarmType());
        return configures == null || configures.isEmpty() ? null : configures.get(0);
    }

    private void publishWorkorderCreatedAfterCommit(Alarm alarm, AlarmWorkorder alarmWorkorder,
                                                    AlarmConfigure configure) {
        if (!pushOpen || configure == null || StringUtils.isBlank(configure.getWorkorderPushMessageType())) {
            return;
        }
        if (rabbitMQAlarmPushProducer == null) {
            log.warn("报警工单创建推送未发送，RabbitMQAlarmPushProducer 未注入，alarmId={}, workorderId={}",
                    alarmWorkorder.getAlarmId(), alarmWorkorder.getWorkorderId());
            return;
        }
        Runnable pushTask = () -> {
            try {
                JSONObject pushMessage = buildWorkorderCreatedPushMessage(alarm, alarmWorkorder, configure);
                rabbitMQAlarmPushProducer.sendCustomPushMessage(pushMessage);
            } catch (Exception ex) {
                log.error("报警工单已创建提交，但推送工单创建消息失败，alarmId={}, workorderId={}, messageType={}, error={}",
                        alarmWorkorder.getAlarmId(), alarmWorkorder.getWorkorderId(),
                        configure.getWorkorderPushMessageType(), ex.getMessage(), ex);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    pushTask.run();
                }
            });
            return;
        }
        pushTask.run();
    }

    private JSONObject buildWorkorderCreatedPushMessage(Alarm alarm, AlarmWorkorder alarmWorkorder,
                                                        AlarmConfigure configure) {
        JSONObject data = new JSONObject();
        data.put("eventType", WORKORDER_CREATED_EVENT_TYPE);
        data.put("tenantId", alarm.getTenantId());
        data.put("deviceSn", alarm.getDeviceSn());
        data.put("alarmId", alarm.getAlarmId());
        data.put("alarmType", alarm.getAlarmType());
        data.put("sceneType", alarm.getSceneType());
        data.put("workorderId", alarmWorkorder.getWorkorderId());
        data.put("workorderNo", alarmWorkorder.getWorkorderNo());
        data.put("workorderConfigId", alarmWorkorder.getWorkorderConfigId());
        data.put("status", alarmWorkorder.getStatus());
        data.put("title", alarmWorkorder.getTitle());
        data.put("content", alarmWorkorder.getContent());

        JSONObject pushMessage = new JSONObject();
        pushMessage.put("messageType", configure.getWorkorderPushMessageType());
        pushMessage.put("tenantId", alarm.getTenantId());
        pushMessage.put("deviceSn", alarm.getDeviceSn());
        pushMessage.put("alarmId", alarm.getAlarmId());
        pushMessage.put("workorderId", alarmWorkorder.getWorkorderId());
        pushMessage.put("workorderNo", alarmWorkorder.getWorkorderNo());
        pushMessage.put("workorderConfigId", alarmWorkorder.getWorkorderConfigId());
        pushMessage.put("status", alarmWorkorder.getStatus());
        pushMessage.put("time", DateUtils.getTime());
        pushMessage.put("data", data);
        return pushMessage;
    }

    private String buildWorkorderNo(Long alarmId) {
        return "WO-" + alarmId + "-" + System.currentTimeMillis();
    }
}
