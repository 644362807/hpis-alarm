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
import com.hpis.common.core.enums.OperCodeEnums;

import com.hpis.common.core.utils.DateUtils;
import com.hpis.common.core.utils.SecurityUtils;
import com.hpis.common.core.utils.StringUtils;
import com.hpis.common.redis.service.RedisService;
import com.hpis.common.security.service.TokenService;

import com.hpis.common.websocket.WebSocketKeepAliveClient;
import com.hpis.common.websocket.model.TransferCommandObject;
import com.hpis.common.websocket.util.CommonTranferUtil;
import com.hpis.device.api.RemoteDBasicInfoService;
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
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

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
    private RemoteDBasicInfoService dBasicInfoService;

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
        return alarmConfigureMapper.selectAlarmConfigureById(alarmConfigureId);
    }

    @Override
    public Page<AlarmConfigure> selectAlarmConfigurePage(AlarmConfigure alarmConfigure) {
        LambdaQueryWrapper<AlarmConfigure> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(StringUtils.isNotBlank(alarmConfigure.getSceneType()),AlarmConfigure::getSceneType,alarmConfigure.getIrmsSn());
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
        alarmConfigure.setCreateTime(DateUtils.getNowDate());
        alarmConfigure.setDelFlag("0");
        alarmConfigure.setCreateBy(userInfo.getUsername());


    if(alarmConfigure.getRepeatAlarmDuration()!=null&&alarmConfigure.getSceneType().equals(SceneTypeEnums.SCENE_TYPE_2.getKey().toString())&&alarmConfigure.getAlarmType().equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_100.getKey())){
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
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("tenantId", SecurityUtils.getCurrentTenantId());
        jsonObject.put("deviceTypeCode",DeviceTypeCodeEnums.TYPE_1.getKey());

        //设备——配置 关联表
   if (alarmConfigure.getAlarmType().equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_100.getKey())) {

       R<List<DeviceKeyInfoDTO>> listR = dBasicInfoService.devicesByTypeCodeAndTenantId(jsonObject);

//       R<List<DeviceDto>> listR = deviceService.devicesByConfigurationItem(jsonObject);
       if (listR!=null&&!listR.getData().isEmpty()) {
           List<String> deviceIdList = listR.getData().stream()
                   .map(DeviceKeyInfoDTO::getDeviceSn)
                   .collect(Collectors.toList());
           insertDeviceConfigureChunks(deviceIdList, alarmConfigureId);
       }
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
        LoginUser userInfo = tokenService.getLoginUser();
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
    if(alarmConfigure.getAlarmConfigureId()!=null) {
        alarmConfigureMapper.updateAlarmConfigure(alarmConfigure);
    }else {
        alarmConfigure.setSceneType(SceneTypeEnums.SCENE_TYPE_2.getKey().toString());
        alarmConfigure.setTenantId(SecurityUtils.getCurrentTenantId());
        alarmConfigureMapper.insertAlarmConfigure(alarmConfigure);
    }

        if (alarmConfigure.getAlarmType().equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_100.getKey())) {
            alarmConfigureMapper.deleteAlarmConfigureDeviceById(alarmConfigure.getAlarmConfigureId());
            JSONObject jsonObject = new JSONObject();
            jsonObject.put("tenantId",SecurityUtils.getCurrentTenantId());
            jsonObject.put("deviceTypeCode",DeviceTypeCodeEnums.TYPE_1.getKey());
            R<List<DeviceKeyInfoDTO>> listR = dBasicInfoService.devicesByTypeCodeAndTenantId(jsonObject);

//       R<List<DeviceDto>> listR = deviceService.devicesByConfigurationItem(jsonObject);
            if (listR!=null&&!listR.getData().isEmpty()) {
                List<String> deviceIdList = listR.getData().stream()
                        .map(DeviceKeyInfoDTO::getDeviceSn)
                        .collect(Collectors.toList());
                insertDeviceConfigureChunks(deviceIdList, alarmConfigure.getAlarmConfigureId());
            }


        }


        List<AlarmConfigure>  list = alarmConfigureMapper.selectDeviceConfigureByCustomer(alarmConfigure);
        redisService.setCacheObject(Constants.ALARM_CONFIG + "-" +SecurityUtils.getCurrentTenantId()  + "-" + alarmConfigure.getSceneType(),list);



        //判断是否是自定义时间或全天
        if ("1".equals(alarmConfigure.getAlarmConfigurePeriod())){
            //先删除现有时间段
            alarmConfigureMapper.deleteConfigTime(alarmConfigure.getAlarmConfigureId());
            insertConfigTimeChunks(alarmConfigure.getAlarmConfigureId(), alarmConfigure.getAlarmConfigureTimeList());
        }

        return "修改成功" ;
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
    @Override
    public int deleteAlarmConfigureByIds(Long[] alarmConfigureIds)
    {
        return alarmConfigureMapper.deleteAlarmConfigureByIds(alarmConfigureIds);
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
        return alarmConfigureMapper.deleteAlarmConfigureById(alarmConfigureId);
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
}
