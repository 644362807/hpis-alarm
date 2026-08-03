package com.hpis.alarm.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.hpis.common.core.utils.SecurityUtils;
import com.hpis.common.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 报警工单服务实现。
 */
@Slf4j
@Service
public class AlarmWorkorderServiceImpl extends ServiceImpl<AlarmWorkorderMapper, AlarmWorkorder>
        implements IAlarmWorkorderService {

    private static final String WORKORDER_CREATED_EVENT_TYPE = "ALARM_WORKORDER_CREATED";
    private static final String WORKORDER_TRANSFERRED_EVENT_TYPE = "ALARM_WORKORDER_TRANSFERRED";

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
        AlarmWorkorder workorder = alarmWorkorderMapper.selectAlarmWorkorderByIdAndTenant(
                workorderId, currentTenantId());
        fillWorkorderRelations(workorder == null ? Collections.emptyList() : Collections.singletonList(workorder));
        return workorder;
    }

    @Override
    public AlarmWorkorder selectAlarmWorkorderByAlarmId(Long alarmId) {
        AlarmWorkorder workorder = alarmWorkorderMapper.selectAlarmWorkorderByAlarmIdAndTenant(
                alarmId, currentTenantId());
        fillWorkorderRelations(workorder == null ? Collections.emptyList() : Collections.singletonList(workorder));
        return workorder;
    }

    @Override
    public AlarmWorkorder selectMyAlarmWorkorderById(Long workorderId) {
        AlarmWorkorder workorder = alarmWorkorderMapper.selectMyAlarmWorkorderById(
                workorderId, currentTenantId(), currentUserId());
        fillWorkorderRelations(workorder == null ? Collections.emptyList() : Collections.singletonList(workorder));
        return workorder;
    }

    @Override
    public Page<AlarmWorkorder> selectAlarmWorkorderPage(AlarmWorkorder alarmWorkorder) {
        AlarmWorkorder query = normalizeQuery(alarmWorkorder);
        Long tenantId = currentTenantId();
        query.setTenantId(tenantId);
        Page<AlarmWorkorder> page = alarmWorkorderMapper.selectAlarmWorkorderPage(
                new Page<>(query.getPageNum(), query.getPageSize()), query, tenantId);
        fillWorkorderRelations(page.getRecords());
        return page;
    }

    @Override
    public Page<AlarmWorkorder> selectMyAlarmWorkorderPage(AlarmWorkorder alarmWorkorder) {
        AlarmWorkorder query = normalizeQuery(alarmWorkorder);
        Long tenantId = currentTenantId();
        Long userId = currentUserId();
        query.setTenantId(tenantId);
        query.setAssigneeId(userId);
        Page<AlarmWorkorder> page = alarmWorkorderMapper.selectMyAlarmWorkorderPage(
                new Page<>(query.getPageNum(), query.getPageSize()), query, tenantId, userId);
        fillWorkorderRelations(page.getRecords());
        return page;
    }

    @Override
    public List<AlarmWorkorder> selectAlarmWorkorderList(AlarmWorkorder alarmWorkorder) {
        AlarmWorkorder query = alarmWorkorder == null ? new AlarmWorkorder() : alarmWorkorder;
        query.setTenantId(currentTenantId());
        List<AlarmWorkorder> workorders = alarmWorkorderMapper.selectAlarmWorkorderList(query);
        fillWorkorderRelations(workorders);
        return workorders;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int createWorkorder(AlarmWorkorder alarmWorkorder) {
        if (alarmWorkorder == null || alarmWorkorder.getAlarmId() == null) {
            throw new CustomException("报警ID不能为空");
        }
        Long tenantId = currentTenantId();
        Alarm alarm = alarmMapper.selectAlarmByIdAndTenant(alarmWorkorder.getAlarmId(), tenantId);
        if (alarm == null) {
            throw new CustomException("报警不存在或不属于当前租户，不能创建工单");
        }
        AlarmHandle alarmHandle = alarmHandleMapper.selectAlarmHandleByAlarmId(alarmWorkorder.getAlarmId());
        if (alarmHandle == null
                || !HandleStatusEnums.ALARM_STATUS_ENUMS_2.getKey().equals(alarmHandle.getHandleStatus())) {
            throw new CustomException("报警未确认，不能创建工单");
        }
        AlarmConfigure configure = resolveWorkorderConfigure(alarm);
        if (configure == null || configure.getWorkorderConfigId() == null
                || configure.getWorkorderConfigId() <= 0) {
            throw new CustomException("当前报警配置未关联工单模板，不能创建工单");
        }

        alarmWorkorder.setWorkorderConfigId(configure.getWorkorderConfigId());
        Long assigneeId = alarmWorkorder.getAssigneeId();
        if (assigneeId != null && assigneeId < 0) {
            throw new CustomException("督促目标ID不能为负数");
        }
        if (assigneeId == null || assigneeId == 0L) {
            alarmWorkorder.setAssigneeName(null);
        }
        alarmWorkorder.setTenantId(tenantId);
        if (StringUtils.isBlank(alarmWorkorder.getWorkorderNo())) {
            alarmWorkorder.setWorkorderNo(buildWorkorderNo(alarmWorkorder.getAlarmId()));
        }
        alarmWorkorder.setStatus("0");
        alarmWorkorder.setDelFlag("0");
        if (StringUtils.isBlank(alarmWorkorder.getTitle())) {
            alarmWorkorder.setTitle("报警工单-" + alarmWorkorder.getAlarmId());
        }
        alarmWorkorder.setCreateTime(DateUtils.getNowDate());
        alarmWorkorder.setCreateBy(currentUsername());
        try {
            int inserted = alarmWorkorderMapper.insertAlarmWorkorder(alarmWorkorder);
            if (inserted > 0 && alarmWorkorder.getAssigneeId() != null) {
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
        alarmWorkorder.setTenantId(null);
        alarmWorkorder.setAssigneeId(null);
        alarmWorkorder.setAssigneeName(null);
        alarmWorkorder.setStatus(null);
        alarmWorkorder.setWorkorderConfigId(null);
        alarmWorkorder.setHandleResult(null);
        alarmWorkorder.setDelFlag(null);
        alarmWorkorder.setUpdateBy(currentUsername());
        alarmWorkorder.setUpdateTime(DateUtils.getNowDate());
        int updated = alarmWorkorderMapper.updateEditableByIdAndTenant(alarmWorkorder, currentTenantId());
        if (updated <= 0) {
            throw new CustomException("工单不存在、已终态或不属于当前租户");
        }
        return updated;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int transferWorkorder(AlarmWorkorder alarmWorkorder) {
        if (alarmWorkorder != null
                && (alarmWorkorder.getAssigneeId() == null || alarmWorkorder.getAssigneeId() <= 0)) {
            throw new CustomException("新负责人ID必须为正整数");
        }
        if (alarmWorkorder == null || alarmWorkorder.getWorkorderId() == null) {
            throw new CustomException("工单ID不能为空");
        }
        Long tenantId = currentTenantId();
        AlarmWorkorder existing = alarmWorkorderMapper.selectAlarmWorkorderByIdAndTenant(
                alarmWorkorder.getWorkorderId(), tenantId);
        if (existing == null) {
            throw new CustomException("工单不存在或不属于当前租户");
        }
        ensureNotTerminal(existing);
        Alarm alarm = alarmMapper.selectAlarmByIdAndTenant(existing.getAlarmId(), tenantId);
        if (alarm == null) {
            throw new CustomException("工单关联报警不存在或不属于当前租户");
        }
        AlarmConfigure configure = resolveWorkorderConfigure(alarm);
        alarmWorkorder.setTenantId(tenantId);
        alarmWorkorder.setStatus(null);
        alarmWorkorder.setUpdateBy(currentUsername());
        alarmWorkorder.setUpdateTime(DateUtils.getNowDate());
        int updated = alarmWorkorderMapper.updateAssigneeByIdAndTenant(alarmWorkorder, tenantId);
        if (updated <= 0) {
            throw new CustomException("工单已终态或并发状态已变化，不能转派");
        }
        if (updated > 0) {
            publishWorkorderTransferredAfterCommit(alarm, existing, alarmWorkorder, configure);
        }
        return updated;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int completeWorkorder(AlarmWorkorder alarmWorkorder) {
        if (alarmWorkorder == null || alarmWorkorder.getWorkorderId() == null) {
            throw new CustomException("工单ID不能为空");
        }
        if (StringUtils.isBlank(alarmWorkorder.getHandleResult())) {
            throw new CustomException("处理说明不能为空");
        }
        if (StringUtils.isBlank(alarmWorkorder.getHandlePicture())) {
            throw new CustomException("处理图片不能为空");
        }
        Long tenantId = currentTenantId();
        Long userId = currentUserId();
        AlarmWorkorder existing = alarmWorkorderMapper.selectAlarmWorkorderByIdAndTenant(
                alarmWorkorder.getWorkorderId(), tenantId);
        if (existing == null) {
            throw new CustomException("工单不存在或不属于当前租户");
        }
        if (existing.getAssigneeId() == null || existing.getAssigneeId() <= 0) {
            throw new CustomException("工单尚未分配负责人，不能完成");
        }
        if (!existing.getAssigneeId().equals(userId)) {
            throw new CustomException("仅当前负责人可以完成工单");
        }
        if (!"0".equals(existing.getStatus()) && !"1".equals(existing.getStatus())) {
            throw new CustomException("当前工单状态不能完成");
        }
        Date now = DateUtils.getNowDate();
        String username = currentUsername();
        int updated = alarmWorkorderMapper.completeByIdAndOwner(existing.getWorkorderId(), tenantId,
                userId, alarmWorkorder.getHandleResult().trim(), username, now);
        if (updated <= 0) {
            throw new CustomException("工单已被处理或并发状态已变化");
        }

        AlarmHandle alarmHandle = new AlarmHandle();
        alarmHandle.setAlarmId(existing.getAlarmId());
        alarmHandle.setWorkorderId(existing.getWorkorderId());
        alarmHandle.setHandlerId(userId);
        alarmHandle.setHandlerName(username);
        alarmHandle.setHandleStatus(HandleStatusEnums.ALARM_STATUS_ENUMS_1.getKey());
        alarmHandle.setOpinion(alarmWorkorder.getHandleResult().trim());
        alarmHandle.setHandlePicture(alarmWorkorder.getHandlePicture().trim());
        alarmHandle.setHandleTime(now);
        alarmHandle.setUpdateTime(now);
        alarmHandle.setUpdateBy(username);
        int handleUpdated = alarmHandleMapper.updateAlarmHandle(alarmHandle);
        if (handleUpdated <= 0) {
            throw new CustomException("报警处理记录不存在，工单完成已回滚");
        }
        return updated;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int closeWorkorder(AlarmWorkorder alarmWorkorder) {
        if (alarmWorkorder == null || alarmWorkorder.getWorkorderId() == null) {
            throw new CustomException("工单ID不能为空");
        }
        if (StringUtils.isBlank(alarmWorkorder.getHandleResult())) {
            throw new CustomException("关闭原因不能为空");
        }
        Long tenantId = currentTenantId();
        AlarmWorkorder existing = alarmWorkorderMapper.selectAlarmWorkorderByIdAndTenant(
                alarmWorkorder.getWorkorderId(), tenantId);
        if (existing == null) {
            throw new CustomException("工单不存在或不属于当前租户");
        }
        ensureNotTerminal(existing);
        Date now = DateUtils.getNowDate();
        String username = currentUsername();
        int updated = alarmWorkorderMapper.closeByIdAndTenant(existing.getWorkorderId(), tenantId,
                alarmWorkorder.getHandleResult().trim(), username, now);
        if (updated <= 0) {
            throw new CustomException("工单已终态或并发状态已变化，不能关闭");
        }

        AlarmHandle alarmHandle = new AlarmHandle();
        alarmHandle.setAlarmId(existing.getAlarmId());
        alarmHandle.setWorkorderId(existing.getWorkorderId());
        alarmHandle.setHandlerId(currentUserId());
        alarmHandle.setHandlerName(username);
        alarmHandle.setOpinion(alarmWorkorder.getHandleResult().trim());
        if (StringUtils.isNotBlank(alarmWorkorder.getHandlePicture())) {
            alarmHandle.setHandlePicture(alarmWorkorder.getHandlePicture().trim());
        }
        alarmHandle.setHandleTime(now);
        alarmHandle.setUpdateTime(now);
        alarmHandle.setUpdateBy(username);
        if (alarmHandleMapper.updateAlarmHandle(alarmHandle) <= 0) {
            throw new CustomException("报警处理记录不存在，工单关闭已回滚");
        }
        return updated;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int deleteAlarmWorkorderByIds(Long[] workorderIds) {
        if (workorderIds == null || workorderIds.length == 0) {
            throw new CustomException("工单ID不能为空");
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long workorderId : workorderIds) {
            if (workorderId == null || workorderId <= 0) {
                throw new CustomException("工单ID必须为正整数");
            }
            normalized.add(workorderId);
        }
        Long[] ids = normalized.toArray(new Long[0]);
        int deleted = alarmWorkorderMapper.deleteByIdsAndTenant(ids, currentTenantId(),
                currentUsername(), DateUtils.getNowDate());
        if (deleted != ids.length) {
            throw new CustomException("存在其他租户、已删除或已终态工单，删除已回滚");
        }
        return deleted;
    }

    private AlarmConfigure resolveWorkorderConfigure(Alarm alarm) {
        List<AlarmConfigure> configures = alarmConfigureService.selectEnabledForAlarm(
                alarm.getTenantId(), alarm.getSceneType(), alarm.getDeviceSn(), alarm.getAlarmType());
        return configures == null || configures.isEmpty() ? null : configures.get(0);
    }

    private AlarmWorkorder normalizeQuery(AlarmWorkorder query) {
        AlarmWorkorder normalized = query == null ? new AlarmWorkorder() : query;
        if (normalized.getPageNum() == null || normalized.getPageNum() <= 0) {
            normalized.setPageNum(1);
        }
        if (normalized.getPageSize() == null || normalized.getPageSize() <= 0) {
            normalized.setPageSize(20);
        }
        return normalized;
    }

    private void fillWorkorderRelations(List<AlarmWorkorder> workorders) {
        if (workorders == null || workorders.isEmpty()) {
            return;
        }
        List<Long> alarmIds = workorders.stream()
                .filter(Objects::nonNull)
                .map(AlarmWorkorder::getAlarmId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (alarmIds.isEmpty()) {
            return;
        }
        List<AlarmHandle> handles = alarmHandleMapper.selectAlarmHandlesByAlarmIds(alarmIds);
        Map<Long, AlarmHandle> byAlarmId = (handles == null ? Collections.<AlarmHandle>emptyList() : handles)
                .stream()
                .filter(handle -> handle != null && handle.getAlarmId() != null)
                .collect(Collectors.toMap(AlarmHandle::getAlarmId, Function.identity(), (left, right) -> left));
        for (AlarmWorkorder workorder : workorders) {
            if (workorder == null) {
                continue;
            }
            fillPushTargetMode(workorder);
            AlarmHandle handle = workorder == null ? null : byAlarmId.get(workorder.getAlarmId());
            if (handle != null) {
                workorder.setHandlePicture(handle.getHandlePicture());
                workorder.setAlarmStatus(handle.getAlarmStatus());
                workorder.setAlarmEndtime(handle.getAlarmEndtime());
                workorder.setHandleStatus(handle.getHandleStatus());
                workorder.setHandlerId(handle.getHandlerId());
                workorder.setHandlerName(handle.getHandlerName());
            }
            fillProcessability(workorder, handle);
        }
    }

    private void fillPushTargetMode(AlarmWorkorder workorder) {
        Long assigneeId = workorder.getAssigneeId();
        if (assigneeId == null) {
            workorder.setPushTargetMode("NONE");
        } else if (assigneeId == 0L) {
            workorder.setPushTargetMode("GROUP");
        } else {
            workorder.setPushTargetMode("DIRECT");
        }
    }

    private void fillProcessability(AlarmWorkorder workorder, AlarmHandle handle) {
        workorder.setProcessable(false);
        if ("2".equals(workorder.getStatus()) || "3".equals(workorder.getStatus())) {
            workorder.setUnprocessableReason("WORKORDER_TERMINAL");
            return;
        }
        if (handle == null) {
            workorder.setUnprocessableReason("ALARM_NOT_FOUND");
            return;
        }
        if ("1".equals(handle.getAlarmStatus())) {
            workorder.setUnprocessableReason("ALARM_ENDED");
            return;
        }
        if ("2".equals(handle.getAlarmStatus()) || "-1".equals(handle.getAlarmStatus())
                || HandleStatusEnums.ALARM_STATUS_ENUMS_1.getKey().equals(handle.getHandleStatus())) {
            workorder.setUnprocessableReason("ALARM_ALREADY_HANDLED");
            return;
        }
        if (!HandleStatusEnums.ALARM_STATUS_ENUMS_2.getKey().equals(handle.getHandleStatus())) {
            workorder.setUnprocessableReason("ALARM_NOT_CONFIRMED");
            return;
        }
        if (!"0".equals(handle.getAlarmStatus())) {
            workorder.setUnprocessableReason("ALARM_NOT_ACTIVE");
            return;
        }
        workorder.setProcessable(true);
        workorder.setUnprocessableReason(null);
    }

    private void ensureNotTerminal(AlarmWorkorder workorder) {
        if (workorder != null && ("2".equals(workorder.getStatus()) || "3".equals(workorder.getStatus()))) {
            throw new CustomException("工单已终态，不能再次操作");
        }
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
        Long directAssigneeId = alarmWorkorder.getAssigneeId() == null
                ? 0L : alarmWorkorder.getAssigneeId();
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
        data.put("assigneeId", directAssigneeId);

        JSONObject pushMessage = new JSONObject();
        pushMessage.put("messageType", configure.getWorkorderPushMessageType());
        pushMessage.put("tenantId", alarm.getTenantId());
        pushMessage.put("deviceSn", alarm.getDeviceSn());
        pushMessage.put("alarmId", alarm.getAlarmId());
        pushMessage.put("workorderId", alarmWorkorder.getWorkorderId());
        pushMessage.put("workorderNo", alarmWorkorder.getWorkorderNo());
        pushMessage.put("workorderConfigId", alarmWorkorder.getWorkorderConfigId());
        pushMessage.put("status", alarmWorkorder.getStatus());
        pushMessage.put("assigneeId", directAssigneeId);
        pushMessage.put("eventType", WORKORDER_CREATED_EVENT_TYPE);
        pushMessage.put("title", alarmWorkorder.getTitle());
        pushMessage.put("content", alarmWorkorder.getContent());
        pushMessage.put("time", DateUtils.getTime());
        pushMessage.put("data", data);
        return pushMessage;
    }

    private void publishWorkorderTransferredAfterCommit(Alarm alarm,
                                                         AlarmWorkorder existing,
                                                         AlarmWorkorder transfer,
                                                         AlarmConfigure configure) {
        if (!pushOpen || configure == null || StringUtils.isBlank(configure.getWorkorderPushMessageType())) {
            return;
        }
        if (rabbitMQAlarmPushProducer == null) {
            log.warn("Workorder transfer push skipped because producer is unavailable, workorderId={}",
                    existing.getWorkorderId());
            return;
        }
        Runnable pushTask = () -> {
            try {
                rabbitMQAlarmPushProducer.sendCustomPushMessage(
                        buildWorkorderTransferredPushMessage(alarm, existing, transfer, configure));
            } catch (Exception ex) {
                log.error("报警工单已转派提交，但推送转派消息失败，alarmId={}, workorderId={}, assigneeId={}, messageType={}, error={}",
                        existing.getAlarmId(), existing.getWorkorderId(), transfer.getAssigneeId(),
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

    private JSONObject buildWorkorderTransferredPushMessage(Alarm alarm,
                                                             AlarmWorkorder existing,
                                                             AlarmWorkorder transfer,
                                                             AlarmConfigure configure) {
        String title = "报警工单已转派";
        String content = "工单" + existing.getWorkorderNo() + "已转派，请及时处理";
        JSONObject data = new JSONObject();
        data.put("eventType", WORKORDER_TRANSFERRED_EVENT_TYPE);
        data.put("tenantId", alarm.getTenantId());
        data.put("deviceSn", alarm.getDeviceSn());
        data.put("alarmId", alarm.getAlarmId());
        data.put("workorderId", existing.getWorkorderId());
        data.put("workorderNo", existing.getWorkorderNo());
        data.put("assigneeId", transfer.getAssigneeId());
        data.put("title", title);
        data.put("content", content);

        JSONObject pushMessage = new JSONObject();
        pushMessage.put("messageType", configure.getWorkorderPushMessageType());
        pushMessage.put("tenantId", alarm.getTenantId());
        pushMessage.put("deviceSn", alarm.getDeviceSn());
        pushMessage.put("alarmId", alarm.getAlarmId());
        pushMessage.put("workorderId", existing.getWorkorderId());
        pushMessage.put("workorderNo", existing.getWorkorderNo());
        pushMessage.put("assigneeId", transfer.getAssigneeId());
        pushMessage.put("eventType", WORKORDER_TRANSFERRED_EVENT_TYPE);
        pushMessage.put("title", title);
        pushMessage.put("content", content);
        pushMessage.put("time", DateUtils.getTime());
        pushMessage.put("data", data);
        return pushMessage;
    }

    protected Long currentTenantId() {
        Long tenantId = SecurityUtils.getCurrentTenantId();
        if (tenantId == null) {
            throw new CustomException("无法获取当前租户");
        }
        return tenantId;
    }

    protected Long currentUserId() {
        Long userId = SecurityUtils.getUserId();
        if (userId == null || userId <= 0) {
            throw new CustomException("无法获取当前用户");
        }
        return userId;
    }

    protected String currentUsername() {
        return SecurityUtils.getUsername();
    }

    private String buildWorkorderNo(Long alarmId) {
        return "WO-" + alarmId + "-" + System.currentTimeMillis();
    }
}
