package com.hpis.alarm.service.impl;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hpis.alarm.domain.AlarmConfigure;
import com.hpis.alarm.domain.AlarmConfigureTime;
import com.hpis.alarm.enums.AlarmTypeEnums;
import com.hpis.alarm.enums.SceneTypeEnums;
import com.hpis.alarm.mapper.AlarmConfigureMapper;
import com.hpis.alarm.service.IAlarmConfigureService;
import com.hpis.alarm.service.support.AlarmBatchChunker;

import com.hpis.common.core.constant.Constants;
import com.hpis.common.core.constant.OperCodeConstants;
import com.hpis.common.core.domain.DeviceKeyInfoDTO;
import com.hpis.common.core.domain.R;
import com.hpis.common.core.enums.DeviceTypeCodeEnums;
import com.hpis.common.core.exception.CustomException;

import com.hpis.common.core.utils.DateUtils;
import com.hpis.common.core.utils.SecurityUtils;
import com.hpis.common.core.utils.StringUtils;
import com.hpis.common.redis.service.RedisService;
import com.hpis.common.security.service.TokenService;

import com.hpis.common.websocket.WebSocketKeepAliveClient;
import com.hpis.common.websocket.model.TransferCommandObject;
import com.hpis.common.websocket.util.CommonTranferUtil;
import com.hpis.electrolyticCell.api.RemoteElectrolyticSequenceService;
import com.hpis.electrolyticCell.api.dto.ElectrolyCellDto;
import com.hpis.system.api.model.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Nullable;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 报警配置Service业务层处理
 * 
 * @author 向文来
 * @date 2023-03-28
 */
@Slf4j
@Service
public class AlarmConfigureServiceImpl extends ServiceImpl<AlarmConfigureMapper, AlarmConfigure> implements IAlarmConfigureService
{

    @Autowired
    private AlarmConfigureMapper alarmConfigureMapper;

    @Autowired
    private TokenService tokenService;

    @Autowired
    @Nullable// 明确标记可为空
    private WebSocketKeepAliveClient webSocketClient;

    @Autowired
    private NacosDiscoveryProperties nacosDiscoveryProperties;

    @Autowired
    private RedisService redisService;

    @Autowired
    private RemoteElectrolyticSequenceService remoteElectrolyticSequenceService;

    /**
     * 查询报警配置
     * 
     * @param alarmConfigureId 报警配置ID
     * @return 报警配置
     */
    @Override
    public AlarmConfigure selectAlarmConfigureById(Long alarmConfigureId)
    {
        Long tenantId = currentTenantId();
        AlarmConfigure configure = alarmConfigureMapper.selectAlarmConfigureById(alarmConfigureId, tenantId);
        if (configure == null) {
            return null;
        }
        fillConfiguredDevices(configure, tenantId);
        return configure;
    }

    @Override
    public Page<AlarmConfigure> selectAlarmConfigurePage(AlarmConfigure alarmConfigure) {
        LambdaQueryWrapper<AlarmConfigure> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AlarmConfigure::getTenantId, currentTenantId());
        queryWrapper.eq(AlarmConfigure::getDelFlag, "0");
        queryWrapper.eq(StringUtils.isNotBlank(alarmConfigure.getSceneType()),AlarmConfigure::getSceneType,alarmConfigure.getSceneType());
        queryWrapper.eq(StringUtils.isNotBlank(alarmConfigure.getAlarmType()),AlarmConfigure::getAlarmType,alarmConfigure.getAlarmType());
        queryWrapper.like(StringUtils.isNotBlank(alarmConfigure.getAlarmConfigureName()),AlarmConfigure::getAlarmConfigureName,alarmConfigure.getAlarmConfigureName());
        return this.baseMapper.selectPage(new Page<>(alarmConfigure.getPageNum(), alarmConfigure.getPageSize()), queryWrapper);
    }

    /**
     * 查询报警配置列表
     * 
     * @param alarmConfigure 报警配置
     * @return 报警配置
     */
    @Override
    public List<AlarmConfigure> selectAlarmConfigureList(AlarmConfigure alarmConfigure)
    {
        alarmConfigure.setTenantId(currentTenantId());
        List<AlarmConfigure> alarmConfigures;
         alarmConfigures = alarmConfigureMapper.selectAlarmConfigureList(alarmConfigure);
        if (alarmConfigures.size()==0){
//            JSONObject paramJson = new JSONObject();
            R<List<ElectrolyCellDto>> listR = remoteElectrolyticSequenceService.selectSequenceList();
            if (listR.getData()==null||listR.getData().size()==0){
                log.error("获取电解槽序列信息失败");
            }
//        paramJson.put("cmd", "setPeriodicTime");
//            paramJson.put("time", alarmConfigure.getRepeatAlarmDuration());
//        paramJson.put("irmsSn", listR.getData().get(0).getSequenceUid());
            Integer time = 15 ;
            try {

            //第一次去获取irms的巡航时间
            int cmdSeq = CommonTranferUtil.getCmdSeq(nacosDiscoveryProperties.getService());
            TransferCommandObject obj = TransferCommandObject.initByDevOperateNew(cmdSeq, listR.getData().get(0).getSequenceUid(), DeviceTypeCodeEnums.TYPE_1003.getKey(),
                    "0", 0X0309, "试管提取装置控制", null);
            webSocketClient.sendMessage(obj);
            JSONObject dataByExcptMessage = webSocketClient.getDataByExcptMessage(nacosDiscoveryProperties.getService(), cmdSeq);
             time = dataByExcptMessage.containsKey("time")?dataByExcptMessage.getInteger("time"):15;
            }catch ( Exception e){
                log.error("获取巡航时间失败");
            }
            AlarmConfigure alarmConfigure1 = new AlarmConfigure();
            alarmConfigure1.setDelFlag("0");
            alarmConfigure1.setRepeatAlarmDuration(time);
            alarmConfigure1.setRepeatCycleNumber(1);
            alarmConfigure1.setAlarmType(AlarmTypeEnums.ALARM_TYPE_ENUMS_100.getKey());
            alarmConfigures.add(alarmConfigure1);

            return  alarmConfigures;
//            alarmConfigureMapper.insertAlarmConfigure(alarmConfigure);
//            return alarmConfigureMapper.selectAlarmConfigureList(alarmConfigure);
        }
        return alarmConfigures;
    }

    /**
     * 新增报警配置
     * 
     * @param alarmConfigure 报警配置
     * @return 结果
     */
    @Transactional(rollbackFor = {Exception.class})
    @Override
    public String insertAlarmConfigure(AlarmConfigure alarmConfigure) throws ParseException {
        //新增报警配置信息并返回自增主键
        LoginUser userInfo = tokenService.getLoginUser();
        Long tenantId = currentTenantId();
        validateAssociationIds(alarmConfigure);
        // 租户只能来自当前登录上下文，不能信任请求体中的 tenantId。
        alarmConfigure.setTenantId(tenantId);
        // 先校验全部设备属于当前租户，避免外部设备指令已发送后才发现关联非法。
        List<String> configuredDeviceSns = resolveCurrentTenantDeviceSns(alarmConfigure.getDeviceIds(), tenantId);
        alarmConfigure.setCreateTime(DateUtils.getNowDate());
        alarmConfigure.setDelFlag("0");
        alarmConfigure.setCreateBy(userInfo.getUsername());


    if(alarmConfigure.getRepeatAlarmDuration()!=null
            && SceneTypeEnums.SCENE_TYPE_2.getKey().toString().equals(alarmConfigure.getSceneType())
            && AlarmTypeEnums.ALARM_TYPE_ENUMS_100.getKey().equals(alarmConfigure.getAlarmType())){
        JSONObject paramJson = new JSONObject();
        R<List<ElectrolyCellDto>> listR = remoteElectrolyticSequenceService.selectSequenceList();
        if (listR.getData()==null||listR.getData().size()==0){
             return "设置失败请检测电解槽序列信息";
        }

//        paramJson.put("cmd", "setPeriodicTime");
        paramJson.put("time", alarmConfigure.getRepeatAlarmDuration());
//        paramJson.put("irmsSn", listR.getData().get(0).getSequenceUid());

        int cmdSeq = CommonTranferUtil.getCmdSeq(nacosDiscoveryProperties.getService());
//        TransferCommandObject obj = TransferCommandObject.initializeByDevOperate(cmdSeq, listR.getData().get(0).getSequenceUid(), DeviceTypeCodeEnums.TYPE_1000.getKey(), OperCodeConstants.TASK_STATUS, paramJson);
        TransferCommandObject obj = TransferCommandObject.initByDevOperateNew(cmdSeq, listR.getData().get(0).getSequenceUid(), DeviceTypeCodeEnums.TYPE_1000.getKey(),
                "0", 0X03A0, "设置巡航时间", paramJson);
        webSocketClient.sendMessage(obj);
        webSocketClient.getDataByExcptMessage(nacosDiscoveryProperties.getService(),cmdSeq);

    }

        alarmConfigureMapper.insertAlarmConfigure(alarmConfigure);
        //获取自增主键
        Long alarmConfigureId = alarmConfigure.getAlarmConfigureId();
        // 所有报警类型统一使用请求中的 deviceIds；缺失或空数组表示不建立设备关系。
        if (!configuredDeviceSns.isEmpty()) {
            insertDeviceConfigureChunks(configuredDeviceSns, alarmConfigureId);
        }

        //判断是否是自定义时间或全天
        if ("1".equals(alarmConfigure.getAlarmConfigurePeriod())){

            insertConfigTimeChunks(alarmConfigureId, alarmConfigure.getAlarmConfigureTimeList());
        }
        return "添加完成";
    }

    /**
     * 修改报警配置
     * 
     * @param alarmConfigure 报警配置
     * @return 结果
     */
    @Transactional(rollbackFor = {Exception.class})
    @Override
    public String updateAlarmConfigure(AlarmConfigure alarmConfigure) throws ParseException {
        if (alarmConfigure == null || alarmConfigure.getAlarmConfigureId() == null) {
            throw new CustomException("报警配置ID不能为空");
        }
        Long tenantId = currentTenantId();
        validateAssociationIds(alarmConfigure);
        // 必须先校验配置属于当前租户，再执行设备下发等外部副作用。
        AlarmConfigure existing = alarmConfigureMapper.selectAlarmConfigureById(
                alarmConfigure.getAlarmConfigureId(), tenantId);
        if (existing == null) {
            throw new CustomException("报警配置不存在或不属于当前租户");
        }
        List<String> configuredDeviceSns = alarmConfigure.getDeviceIds() == null
                ? null : resolveCurrentTenantDeviceSns(alarmConfigure.getDeviceIds(), tenantId);
        LoginUser userInfo = tokenService.getLoginUser();
        alarmConfigure.setTenantId(tenantId);
        alarmConfigure.setUpdateTime(DateUtils.getNowDate());
        alarmConfigure.setUpdateBy(userInfo.getUsername());

/** ----------------------------------------------*/
        if (alarmConfigure.getRepeatAlarmDuration()!=null){
            JSONObject paramJson = new JSONObject();

            R<List<ElectrolyCellDto>> listR = remoteElectrolyticSequenceService.selectSequenceList();
            if (listR.getData()==null||listR.getData().size()==0){
                return "设置失败请检测电解槽序列信息";
            }
//            paramJson.put("cmd", "setPeriodicTime");
            paramJson.put("time", alarmConfigure.getRepeatAlarmDuration());
//            paramJson.put("irmsSn",listR.getData().get(0).getSequenceUid());

            int cmdSeq = CommonTranferUtil.getCmdSeq(nacosDiscoveryProperties.getService());
//            TransferCommandObject obj = TransferCommandObject.initializeByDevOperate(cmdSeq, listR.getData().get(0).getSequenceUid(), DeviceTypeCodeEnums.TYPE_1000.getKey(), OperCodeEnums.SET_PERIODIC_TIME.getKey(), paramJson);

            TransferCommandObject obj = TransferCommandObject.initByDevOperateNew(cmdSeq, listR.getData().get(0).getSequenceUid(), DeviceTypeCodeEnums.TYPE_1000.getKey(),
                    "0", 0X03A0, "设置巡航时间", paramJson);

            webSocketClient.sendMessage(obj);
            webSocketClient.getDataByExcptMessage(nacosDiscoveryProperties.getService(),cmdSeq);

        }
/** ----------------------------------------------*/
        int updated = alarmConfigureMapper.updateAlarmConfigure(alarmConfigure);
        if (updated <= 0) {
            throw new CustomException("报警配置修改失败");
        }

        // null 表示本次不修改设备关系；空数组表示显式清空；非空数组表示完整替换。
        if (alarmConfigure.getDeviceIds() != null) {
            alarmConfigureMapper.deleteAlarmConfigureDeviceById(alarmConfigure.getAlarmConfigureId());
            if (configuredDeviceSns != null && !configuredDeviceSns.isEmpty()) {
                insertDeviceConfigureChunks(configuredDeviceSns, alarmConfigure.getAlarmConfigureId());
            }
        }


        List<AlarmConfigure>  list = alarmConfigureMapper.selectDeviceConfigureByCustomer(alarmConfigure);
        redisService.setCacheObject(Constants.ALARM_CONFIG + "-" + tenantId  + "-" + alarmConfigure.getSceneType(),list);



        //判断是否是自定义时间或全天
        if (StringUtils.isNotBlank(alarmConfigure.getAlarmConfigurePeriod())){
            // 全天和自定义时间切换都先清理旧时间段，避免详情仍返回过期配置。
            alarmConfigureMapper.deleteConfigTime(alarmConfigure.getAlarmConfigureId());
            if ("1".equals(alarmConfigure.getAlarmConfigurePeriod())) {
                insertConfigTimeChunks(alarmConfigure.getAlarmConfigureId(), alarmConfigure.getAlarmConfigureTimeList());
            }
        }

        return "修改成功" ;
    }

    /**
     * 当前租户获取入口单独保留为方法，便于单元测试验证租户边界；生产实现仍只读取 SecurityUtils。
     */
    protected Long currentTenantId() {
        Long tenantId = SecurityUtils.getCurrentTenantId();
        if (tenantId == null) {
            throw new CustomException("当前租户不能为空");
        }
        return tenantId;
    }

    private void validateAssociationIds(AlarmConfigure alarmConfigure) {
        if (alarmConfigure == null) {
            throw new CustomException("报警配置不能为空");
        }
        if (alarmConfigure.getWorkorderConfigId() != null && alarmConfigure.getWorkorderConfigId() < 0) {
            throw new CustomException("工单配置ID不能为负数");
        }
    }

    private List<String> resolveCurrentTenantDeviceSns(Long[] deviceIds, Long tenantId) {
        if (deviceIds == null || deviceIds.length == 0) {
            return new ArrayList<>();
        }
        Set<Long> uniqueDeviceIds = new LinkedHashSet<>(Arrays.asList(deviceIds));
        List<String> deviceSns = new ArrayList<>(uniqueDeviceIds.size());
        for (Long deviceId : uniqueDeviceIds) {
            if (deviceId == null || deviceId <= 0) {
                throw new CustomException("设备ID必须为正整数");
            }
            DeviceKeyInfoDTO device = redisService.getCacheObject(Constants.DEVICE_ID_KEY + deviceId);
            if (device == null) {
                throw new CustomException("设备不存在或设备缓存未就绪，deviceId=" + deviceId);
            }
            if (!tenantId.equals(device.getTenantId())) {
                throw new CustomException("设备不属于当前租户，deviceId=" + deviceId);
            }
            if (StringUtils.isBlank(device.getDeviceSn())) {
                throw new CustomException("设备SN不能为空，deviceId=" + deviceId);
            }
            deviceSns.add(device.getDeviceSn());
        }
        return deviceSns;
    }

    private void fillConfiguredDevices(AlarmConfigure configure, Long tenantId) {
        List<String> deviceSns = alarmConfigureMapper.selectDeviceSnsByConfigureId(
                configure.getAlarmConfigureId(), tenantId);
        if (deviceSns == null) {
            deviceSns = new ArrayList<>();
        }
        configure.setDeviceSet(new LinkedHashSet<>(deviceSns));
        List<Long> deviceIds = new ArrayList<>();
        for (String deviceSn : deviceSns) {
            DeviceKeyInfoDTO device = redisService.getCacheObject(Constants.DEVICE_SN_KEY + deviceSn);
            if (device != null && tenantId.equals(device.getTenantId()) && device.getDeviceId() != null) {
                deviceIds.add(device.getDeviceId());
            } else {
                // 关系仍通过 deviceSet 返回；缓存缺失不能让配置详情接口整体失败。
                log.warn("报警配置设备详情未找到当前租户设备缓存，alarmConfigureId={}, tenantId={}, deviceSn={}",
                        configure.getAlarmConfigureId(), tenantId, deviceSn);
            }
        }
        configure.setDeviceIds(deviceIds.toArray(new Long[0]));
    }

    private void insertDeviceConfigureChunks(List<String> deviceSnList, Long alarmConfigureId) {
        /*
         * 租户设备数量不可假设很小。关联表批量新增必须分块，防止全租户设备一次拼成超长 INSERT。
         */
        for (List<String> chunk : AlarmBatchChunker.chunk(deviceSnList, AlarmBatchChunker.MAX_BATCH_SIZE)) {
            alarmConfigureMapper.batchDeviceConfigure(chunk.toArray(new String[0]), alarmConfigureId);
        }
    }

    private void insertConfigTimeChunks(Long alarmConfigureId,
                                        List<AlarmConfigureTime> configureTimes) throws ParseException {
        if (configureTimes == null || configureTimes.isEmpty()) {
            return;
        }
        /*
         * 时间段通常数量很小，但仍统一使用批量 Mapper，避免事务内形成逐条 INSERT 模板。
         */
        for (AlarmConfigureTime alarmConfigureTime : configureTimes) {
            String[] time = alarmConfigureTime.getTime();
            alarmConfigureTime.setAlarmConfigureId(alarmConfigureId);
            alarmConfigureTime.setDelFlag("0");
            alarmConfigureTime.setAlarmConfigureStarttime(new SimpleDateFormat("HH:mm:ss").parse(time[0]));
            alarmConfigureTime.setAlarmConfigureEndtime(new SimpleDateFormat("HH:mm:ss").parse(time[1]));
        }
        for (List<AlarmConfigureTime> chunk : AlarmBatchChunker.chunk(configureTimes, AlarmBatchChunker.MAX_BATCH_SIZE)) {
            alarmConfigureMapper.insertConfigTimeBatch(chunk);
        }
    }

    /**
     * 批量删除报警配置
     * 
     * @param alarmConfigureIds 需要删除的报警配置ID
     * @return 结果
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public int deleteAlarmConfigureByIds(Long[] alarmConfigureIds)
    {
        if (alarmConfigureIds == null || alarmConfigureIds.length == 0) {
            return 0;
        }
        Long tenantId = currentTenantId();
        List<Long> allowedIds = alarmConfigureMapper.selectExistingIdsByTenant(alarmConfigureIds, tenantId);
        if (allowedIds == null || allowedIds.isEmpty()) {
            return 0;
        }
        Long[] scopedIds = allowedIds.toArray(new Long[0]);
        int deleted = alarmConfigureMapper.deleteAlarmConfigureByIds(scopedIds, tenantId);
        if (deleted > 0) {
            // 关联数据没有 tenant_id，只能在主表租户校验完成后使用已筛选 ID 批量清理。
            alarmConfigureMapper.deleteAlarmConfigureDeviceByIds(scopedIds);
            alarmConfigureMapper.deleteConfigTimeByConfigureIds(scopedIds);
            redisService.deleteObject(Constants.ALARM_CONFIG + "-" + tenantId + "-"
                    + SceneTypeEnums.SCENE_TYPE_2.getKey());
        }
        return deleted;
    }

    /**
     * 删除报警配置信息
     * 
     * @param alarmConfigureId 报警配置ID
     * @return 结果
     */
    @Override
    public int deleteAlarmConfigureById(Long alarmConfigureId)
    {
        return deleteAlarmConfigureByIds(new Long[]{alarmConfigureId});
    }
    /**
     * 客户的报警配置
     * @param alarmConfigure
     * @return
     */
    @Override
    public List<AlarmConfigure> selectDeviceConfigureByCustomer(AlarmConfigure alarmConfigure){
       return alarmConfigureMapper.selectDeviceConfigureByCustomer(alarmConfigure);
    }

    @Override
    public List<AlarmConfigure> selectEnabledForAlarm(Long tenantId, String sceneType, String deviceSn, String alarmType) {
        return alarmConfigureMapper.selectEnabledForAlarm(tenantId, sceneType, deviceSn, alarmType);
    }
}
