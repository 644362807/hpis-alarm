package com.hpis.alarm.service.impl;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.fastjson.JSONObject;
import javax.annotation.Nullable;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.domain.AlarmElectrolyticCell;
import com.hpis.alarm.domain.AlarmHandle;
import com.hpis.alarm.dto.AlarmDetailEc;
import com.hpis.alarm.dto.EcHandleSave;
import com.hpis.alarm.dto.HandleParamDto;
import com.hpis.alarm.enums.AlarmStatusEnums;
import com.hpis.alarm.enums.HandleStatusEnums;
import com.hpis.alarm.enums.SceneTypeEnums;
import com.hpis.alarm.mapper.AlarmElectrolyticCellMapper;
import com.hpis.alarm.mapper.AlarmHandleMapper;
import com.hpis.alarm.mapper.AlarmMapper;
import com.hpis.alarm.service.IAlarmHandleService;
import com.hpis.alarm.service.support.AlarmBatchChunker;

import com.hpis.common.core.constant.Constants;
import com.hpis.common.core.domain.DeviceKeyInfoDTO;

import com.hpis.common.core.enums.DeviceTypeCodeEnums;
import com.hpis.common.core.enums.OperCodeEnums;
import com.hpis.common.core.exception.CustomException;
import com.hpis.common.core.utils.DateUtils;
import com.hpis.common.core.utils.StringUtils;
import com.hpis.common.core.utils.bean.BeanUtils;
import com.hpis.common.redis.service.RedisService;
import com.hpis.common.security.service.TokenService;

import com.hpis.common.websocket.WebSocketKeepAliveClient;
import com.hpis.common.websocket.model.TransferCommandObject;
import com.hpis.common.websocket.util.CommonTranferUtil;
import com.hpis.system.api.model.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 【请填写功能名称】Service业务层处理
 * 
 * @author ruoyi
 * @date 2023-03-24
 */
@Slf4j
@Service
public class AlarmHandleServiceImpl extends ServiceImpl<AlarmHandleMapper, AlarmHandle> implements IAlarmHandleService
{

    @Autowired
    private AlarmHandleMapper alarmHandleMapper;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private AlarmMapper alarmMapper;

//    @Autowired
//    private RemoteDeviceService deviceService;

    @Autowired
    private NacosDiscoveryProperties nacosDiscoveryProperties;


    @Autowired
    @Nullable// 明确标记可为空
    private WebSocketKeepAliveClient webSocketClient;

    @Autowired
    private RedisService redisService;

    @Autowired
    private AlarmElectrolyticCellMapper alarmElectrolyticCellMapper;

    /**
     * 查询【请填写功能名称】
     * 
     * @param alarmHandleId 【请填写功能名称】ID
     * @return 【请填写功能名称】
     */
    @Override
    public AlarmHandle selectAlarmHandleById(Long alarmHandleId)
    {
        return alarmHandleMapper.selectAlarmHandleById(alarmHandleId);
    }

    @Override
    public Page<AlarmHandle> selectAlarmHandlePage(AlarmHandle alarmHandle) {
        return null;
    }

    /**
     * 查询【请填写功能名称】列表
     * 
     * @param alarmHandle 【请填写功能名称】
     * @return 【请填写功能名称】
     */
    @Override
    public List<AlarmHandle> selectAlarmHandleList(AlarmHandle alarmHandle)
    {
        List<AlarmHandle> alarmHandles = alarmHandleMapper.selectAlarmHandleList(alarmHandle);

//        List<Long> deviceIdList = alarmHandles.parallelStream().map(AlarmHandle::getDeviceId).distinct().collect(Collectors.toList());
//        R<List<DeviceKeyInfoDTO>> getDeviceNameResult = deviceService.selectDeviceNameByDeviceIds(deviceIdList);
//        if (R.FAIL == getDeviceNameResult.getCode()) {
//            throw new BaseException(getDeviceNameResult.getMsg());
//        }
//        List<DeviceKeyInfoDTO> getDeviceNameList = getDeviceNameResult.getData();
//        Map<Long, String> deviceMap = getDeviceNameList.parallelStream().collect(Collectors.toMap(DeviceKeyInfoDTO::getDeviceId, DeviceKeyInfoDTO::getDeviceName));
//        for (AlarmHandle dto : alarmHandles) {
//            //设备名称
//            dto.setDeviceName(deviceMap.get(dto.getDeviceId()));
//        }
        return alarmHandles;
    }

    /**
     * 新增【请填写功能名称】
     * 
     * @param alarmHandle 【请填写功能名称】
     * @return 结果
     */
    @Override
    public int insertAlarmHandle(AlarmHandle alarmHandle)
    {
        alarmHandle.setCreateTime(DateUtils.getNowDate());
        return alarmHandleMapper.insertAlarmHandle(alarmHandle);
    }


    @Override
    public int insertAlarmHandleList(List<AlarmHandle> alarmHandles)
    {
        return alarmHandleMapper.insertAlarmHandelList(alarmHandles);
    }

    /**
     * 通用接口
     *  报警处理修改  ：报警确认
     * @param alarmHandle
     * @return
     */
    @Override
    public int updateAlarmHandle(AlarmHandle alarmHandle){
    if (alarmHandle.getHandleStatus().equals(HandleStatusEnums.ALARM_STATUS_ENUMS_2.getKey())){
        LoginUser userInfo = tokenService.getLoginUser();
        alarmHandle.setConfirmUserId(userInfo.getUserid());

        Map<Long,Date> confirmAlarm = redisService.getCacheObject(Constants.CONFIRM_ALARM);

        if (confirmAlarm == null){
            Map<Long,Date> objects = new LinkedHashMap<>();
           for(int i = 0 ; i<alarmHandle.getAlarmIds().length;i++){
            objects.put(alarmHandle.getAlarmIds()[i],DateUtils.getNowDate());
           }

            redisService.setCacheObject(Constants.CONFIRM_ALARM,objects);
        }else {
            for(int i = 0 ; i<alarmHandle.getAlarmIds().length;i++){
                confirmAlarm.put(alarmHandle.getAlarmIds()[i],DateUtils.getNowDate());
            }

            redisService.setCacheObject(Constants.CONFIRM_ALARM,confirmAlarm);
        }

    }
        return alarmHandleMapper.updateAlarmHandle(alarmHandle);
    }


    /**
     * 通用接口 包含电解槽处理业务
     * 保存报警处理
     * @param handleParamDto
     * @return
     */
    @Transactional(rollbackFor = {Exception.class})
    @Override
    public int saveAlarmHandle(HandleParamDto handleParamDto)
    {
        LoginUser userInfo = tokenService.getLoginUser();
        //修改报警记录信息状态
        Alarm alarm1=new Alarm();
        //如果是电解槽行业 的处理 就需要去irms 同步结束报警
        if (handleParamDto.getSceneType() != null&&(SceneTypeEnums.SCENE_TYPE_2.getKey()+"").equals(handleParamDto.getSceneType())){


            JSONObject jsonObject = new JSONObject();
/** ------------------------------------------------------------------*/
            if (handleParamDto.getEcHandles()!=null && handleParamDto.getEcHandles().size()>0){
               List<EcHandleSave>  ecHandleSaves =handleParamDto.getEcHandles();
               for (EcHandleSave ecHandleSave :ecHandleSaves) {
                   jsonObject.put("alarmId", String.join(",", ecHandleSave.getAlarmCids()));
                   jsonObject.put("time", DateUtils.getTime());

//                   DeviceKeyInfoDTO deviceKeyInfoDTO = redisService.getCacheObject(Constants.DEVICE_ID_KEY +ecHandleSave.getDeviceSn());
//
//                   try {
//                       log.info("设备查询deviceKeyInfoDTO(deviceId:{},deviceName:{},deviceSn:{})", deviceKeyInfoDTO.getDeviceId(), deviceKeyInfoDTO.getDeviceName(), deviceKeyInfoDTO.getDeviceSn());
//                   } catch (Exception e) {
//                       throw new CustomException("设备id 缓存有误");
//                   }

                   jsonObject.put("deviceSn", ecHandleSave.getDeviceSn());
                   jsonObject.put("disposeAll",false);

                   int cmdSeq = CommonTranferUtil.getCmdSeq(nacosDiscoveryProperties.getService());
                   TransferCommandObject obj = TransferCommandObject.initByDevOperateNew(cmdSeq, handleParamDto.getIrmsSn(), DeviceTypeCodeEnums.TYPE_1000.getKey(),
                           "0", 0X03A1, "主动停止报警", jsonObject);
                   webSocketClient.sendMessage(obj);
                   webSocketClient.getDataByExcptMessage(nacosDiscoveryProperties.getService(),cmdSeq);

               }
            }
            else {

                jsonObject.put("alarmId",handleParamDto.getAlarmId()+"");
                jsonObject.put("time",DateUtils.getTime());

                int cmdSeq = CommonTranferUtil.getCmdSeq(nacosDiscoveryProperties.getService());
                TransferCommandObject obj = TransferCommandObject.initializeByDevOperate(cmdSeq, handleParamDto.getIrmsSn(), DeviceTypeCodeEnums.TYPE_1000.getKey(), OperCodeEnums.SEND_DISPOSE_ALARM.getKey(), jsonObject);
                webSocketClient.sendMessage(obj);
                webSocketClient.getDataByExcptMessage(nacosDiscoveryProperties.getService(),cmdSeq);




            }
/**----------------------------------------------------- */
            Map<Long,Date> confirmAlarm = redisService.getCacheObject(Constants.CONFIRM_ALARM);
            if(handleParamDto.getAlarmIds()!=null) {
                for (int i = 0; i < handleParamDto.getAlarmIds().length; i++) {
                    confirmAlarm.remove(handleParamDto.getAlarmIds()[i]);
                }
            }
            if (handleParamDto.getAlarmId()!=null){
                confirmAlarm.remove(handleParamDto.getAlarmId());
            }
            redisService.setCacheObject(Constants.CONFIRM_ALARM,confirmAlarm);
        }
        Date now=DateUtils.getNowDate();
        alarm1.setUpdateTime(now);
        alarm1.setAlarmIds(handleParamDto.getAlarmIds());
        alarm1.setAlarmId(handleParamDto.getAlarmId());
        alarm1.setUpdateBy(userInfo.getUsername());
        alarm1.setAlarmStatus(AlarmStatusEnums.ALARM_STATUS_ENUMS_1.getKey());
        //判断真实性
        if ("1".equals(handleParamDto.getIdentify())) {
            alarm1.setAlarmStatus(AlarmStatusEnums.ALARM_STATUS_ENUMS_999.getKey());
        }

        alarmMapper.updateAlarm(alarm1);

        AlarmHandle alarmHandle = new AlarmHandle();

        BeanUtils.copyBeanProp(alarmHandle,handleParamDto);

        if (StringUtils.isNotBlank(handleParamDto.getApparatusId())) {

            DeviceKeyInfoDTO deviceKeyInfoDTO1 = redisService.getCacheObject(Constants.DEVICE_SN_KEY + handleParamDto.getApparatusId());

            try {
                log.info("设备查询deviceKeyInfoDTO(deviceId:{},deviceName:{},deviceSn:{})", deviceKeyInfoDTO1.getDeviceId(), deviceKeyInfoDTO1.getDeviceName(), deviceKeyInfoDTO1.getDeviceSn());
            } catch (Exception e) {
                throw new CustomException("设备sn 缓存有误");
            }

            alarmHandle.setApparatusId(deviceKeyInfoDTO1.getDeviceName());
        }
        //保存报警处理信息
        alarmHandle.setHandlerName(userInfo.getUsername());
        alarmHandle.setHandlerId(userInfo.getUserid());
        alarmHandle.setHandleTime(now);
        alarmHandle.setUpdateTime(now);
        alarmHandle.setUpdateBy(userInfo.getUsername());
        alarmHandle.setHandleStatus(AlarmStatusEnums.ALARM_STATUS_ENUMS_1.getKey());

        log.info("处理报警ids{}，id{}",alarmHandle.getAlarmIds(),alarmHandle.getAlarmId());
        return alarmHandleMapper.updateAlarmHandle(alarmHandle);
    }




    /**
     * 电解槽接口
     * 保存报警处理
     * @param handleParamDto
     * @return
     */
    @Transactional(rollbackFor = {Exception.class})
    @Override
    public int saveAlarmAllHandle(HandleParamDto handleParamDto)
    {
        LoginUser userInfo = tokenService.getLoginUser();
        AlarmElectrolyticCell alarmElectrolyticCell = new AlarmElectrolyticCell();
        BeanUtils.copyBeanProp(alarmElectrolyticCell,handleParamDto);
        //查询符合条件的电解槽报警
        List<AlarmDetailEc> alarmElectrolyticCellDTOList = alarmElectrolyticCellMapper.selectAlarmAlarmDetailEcList(alarmElectrolyticCell);


        //修改报警记录信息状态
        Alarm alarm1=new Alarm();
        //如果是电解槽行业 的处理 就需要去irms 同步结束报警
        if (handleParamDto.getSceneType() != null&&(SceneTypeEnums.SCENE_TYPE_2.getKey()+"").equals(handleParamDto.getSceneType())&&alarmElectrolyticCellDTOList.size()>0){


            Long[] alarmIds = alarmElectrolyticCellDTOList.stream()
                    .map(AlarmDetailEc::getAlarmId)
                    .toArray(Long[]::new);

            handleParamDto.setAlarmIds(alarmIds);

            Map<String, List<String>> groupedMap = alarmElectrolyticCellDTOList.stream()
                    .collect(Collectors.groupingBy(AlarmDetailEc::getDeviceSn,
                            Collectors.mapping(AlarmDetailEc::getAlarmCid, Collectors.toList())));


            ArrayList<EcHandleSave> list = new ArrayList<>();
            groupedMap.forEach((deviceId, alarmCids) -> {
                String[] alarmCidArray = alarmCids.toArray(new String[0]);
                EcHandleSave ecHandleSave = new EcHandleSave();
                ecHandleSave.setDeviceSn(deviceId);
                ecHandleSave.setAlarmCids(alarmCidArray);
                list.add(ecHandleSave);
            });
            handleParamDto.setEcHandles(list);

            JSONObject jsonObject = new JSONObject();
//            jsonObject.put("cmd", "disposeAlarm");
//            jsonObject.put("irmsSn",alarmElectrolyticCellDTOList.get(0).getIrmsSn());

            if (handleParamDto.getEcHandles()!=null && handleParamDto.getEcHandles().size()>0){
                List<EcHandleSave>  ecHandleSaves =handleParamDto.getEcHandles();
                for (EcHandleSave ecHandleSave :ecHandleSaves) {


                    jsonObject.put("time", DateUtils.getTime());
                    DeviceKeyInfoDTO deviceKeyInfoDTO = redisService.getCacheObject(Constants.DEVICE_SN_KEY +ecHandleSave.getDeviceSn());
                    try {
                        log.info("设备查询deviceKeyInfoDTO(deviceId:{},deviceName:{},deviceSn:{})", deviceKeyInfoDTO.getDeviceId(), deviceKeyInfoDTO.getDeviceName(), deviceKeyInfoDTO.getDeviceSn());
                    } catch (Exception e) {
                        throw new CustomException("设备id 缓存有误");
                    }
                    jsonObject.put("deviceSn", deviceKeyInfoDTO.getDeviceSn());
                    jsonObject.put("disposeAll",true);
                    int cmdSeq = CommonTranferUtil.getCmdSeq(nacosDiscoveryProperties.getService());
                    TransferCommandObject obj = TransferCommandObject.initByDevOperateNew(cmdSeq, handleParamDto.getIrmsSn(), DeviceTypeCodeEnums.TYPE_1000.getKey(),
                            "0", 0X03A1, "主动停止报警", jsonObject);
                    webSocketClient.sendMessage(obj);
                    webSocketClient.getDataByExcptMessage(nacosDiscoveryProperties.getService(),cmdSeq);



                }
            }

            Map<Long,Date> confirmAlarm = redisService.getCacheObject(Constants.CONFIRM_ALARM);
            for(int i = 0 ; i<handleParamDto.getAlarmIds().length;i++){
                confirmAlarm.remove(handleParamDto.getAlarmIds()[i]) ;
            }
            redisService.setCacheObject(Constants.CONFIRM_ALARM,confirmAlarm);
        }


        Date now=DateUtils.getNowDate();
        alarm1.setUpdateTime(now);
        alarm1.setAlarmIds(handleParamDto.getAlarmIds());
        alarm1.setAlarmId(handleParamDto.getAlarmId());
        alarm1.setUpdateBy(userInfo.getUsername());
        alarm1.setAlarmStatus(AlarmStatusEnums.ALARM_STATUS_ENUMS_1.getKey());
        //判断真实性
        if ("1".equals(handleParamDto.getIdentify())) {
            alarm1.setAlarmStatus(AlarmStatusEnums.ALARM_STATUS_ENUMS_999.getKey());
        }

        //7.1 加入的本地结束 使符合条件的alarm主表内的报警停止
        alarm1.setAlarmEndtime(now);
        alarmMapper.updateAlarm(alarm1);

        AlarmHandle alarmHandle = new AlarmHandle();

        BeanUtils.copyBeanProp(alarmHandle,handleParamDto);

        if (StringUtils.isNotBlank(handleParamDto.getApparatusId())) {

            DeviceKeyInfoDTO deviceKeyInfoDTO1 = redisService.getCacheObject(Constants.DEVICE_SN_KEY + handleParamDto.getApparatusId());
            try {
                log.info("设备查询deviceKeyInfoDTO(deviceId:{},deviceName:{},deviceSn:{})", deviceKeyInfoDTO1.getDeviceId(), deviceKeyInfoDTO1.getDeviceName(), deviceKeyInfoDTO1.getDeviceSn());
            } catch (Exception e) {
                throw new CustomException("设备sn 缓存有误");
            }

            alarmHandle.setApparatusId(deviceKeyInfoDTO1.getDeviceName());
        }
        //保存报警处理信息
        alarmHandle.setHandlerName(userInfo.getUsername());
        alarmHandle.setHandlerId(userInfo.getUserid());
        alarmHandle.setHandleTime(now);
        alarmHandle.setUpdateTime(now);
        alarmHandle.setUpdateBy(userInfo.getUsername());
        alarmHandle.setHandleStatus(AlarmStatusEnums.ALARM_STATUS_ENUMS_1.getKey());
        log.info("处理报警ids{}，id{}",alarmHandle.getAlarmIds(),alarmHandle.getAlarmId());
        return alarmHandleMapper.updateAlarmHandle(alarmHandle);
    }



    /**
     * 批量删除【请填写功能名称】
     * 
     * @param alarmHandleIds 需要删除的【请填写功能名称】ID
     * @return 结果
     */
    @Override
    public int deleteAlarmHandleByIds(Long[] alarmHandleIds)
    {
        return alarmHandleMapper.deleteAlarmHandleByIds(alarmHandleIds);
    }

    /**
     * 删除【请填写功能名称】信息
     * 
     * @param alarmHandleId 【请填写功能名称】ID
     * @return 结果
     */
    @Transactional(rollbackFor = {Exception.class})
    @Override
    public int deleteAlarmHandleById(Long alarmHandleId)
    {
        int i = alarmHandleMapper.deleteAlarmHandleById(alarmHandleId);
        int i1 = alarmMapper.deleteAlarmById(alarmHandleId);
        return  i;
    }

    /**
     * 电解槽接口
     * 定时检测 确认报警情况 5分钟检测一次
     * 还原4小时之内未处理的报警
     */
    @Scheduled(fixedRate = 5 * 60 * 1000)
    public void checkConfirmAlarm (){

        Map<Long,Date> confirmAlarm = redisService.getCacheObject(Constants.CONFIRM_ALARM);

        if(confirmAlarm!=null&&!confirmAlarm.isEmpty()) {
            //获取两小时前的date
            Date Hours = Date.from(ZonedDateTime.now().minusHours(4).toInstant());
            long base = DateUtils.getNowDate().getTime() - Hours.getTime();

            List<Long> update= new ArrayList<>();

            for (Map.Entry<Long, Date> entry : confirmAlarm.entrySet()) {
                Long alarmId = entry.getKey();
                Date alarmDate = entry.getValue();

                long diff = DateUtils.getNowDate().getTime()-alarmDate.getTime();

                if (diff>=base){
                    update.add(alarmId);
                }
            }

            if (update.size()>0) {
                /*
                 * Redis Map 可能长期累积大量待确认报警。
                 * 更新 SQL 的 IN 必须按 500 条硬边界拆分，避免定时任务一次生成超长 SQL。
                 */
                for (List<Long> chunk : AlarmBatchChunker.chunk(update, AlarmBatchChunker.MAX_BATCH_SIZE)) {
                    AlarmHandle alarmHandle = new AlarmHandle();
                    alarmHandle.setAlarmIds(chunk.toArray(new Long[0]));
                    //-1 置空
                    alarmHandle.setConfirmUserId(-1L);
                    alarmHandle.setHandleStatus(HandleStatusEnums.ALARM_STATUS_ENUMS_0.getKey());
                    alarmHandleMapper.updateAlarmHandle(alarmHandle);
                }

                update.forEach(alarmId -> confirmAlarm.remove(alarmId));

                redisService.setCacheObject(Constants.CONFIRM_ALARM,confirmAlarm);
            }
        }

    }

}
