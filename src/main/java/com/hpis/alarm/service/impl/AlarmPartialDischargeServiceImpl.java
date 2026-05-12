package com.hpis.alarm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.domain.AlarmHandle;
import com.hpis.alarm.domain.AlarmPartialDischarge;
import com.hpis.alarm.dto.AlarmPartialDischargeCount;
import com.hpis.alarm.dto.AlarmPartialDischargeDto;
import com.hpis.alarm.dto.AlarmQueryParameter;
import com.hpis.alarm.enums.AlarmTypeEnums;
import com.hpis.alarm.enums.SceneTypeEnums;
import com.hpis.alarm.mapper.AlarmHandleMapper;
import com.hpis.alarm.mapper.AlarmPartialDischargeMapper;
import com.hpis.alarm.service.IAlarmPartialDischargeService;
import com.hpis.alarm.service.IAlarmService;

import com.hpis.common.core.constant.Constants;
import com.hpis.common.core.domain.DeviceKeyInfoDTO;
import com.hpis.common.core.domain.R;
import com.hpis.common.core.exception.BaseException;
import com.hpis.common.core.utils.SecurityUtils;
import com.hpis.common.core.utils.StringUtils;
import com.hpis.common.redis.service.RedisService;
import com.hpis.common.security.service.TokenService;
import com.hpis.system.api.model.LoginUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 局放报警详情Service业务层处理
 * 
 * @author ruoyi
 * @date 2024-03-13
 */
@Service
public class AlarmPartialDischargeServiceImpl extends ServiceImpl<AlarmPartialDischargeMapper, AlarmPartialDischargeDto> implements IAlarmPartialDischargeService
{
    @Autowired
    private AlarmPartialDischargeMapper alarmPartialDischargeMapper;

//    @Autowired
//    private RemoteDeviceService deviceService;

    @Autowired
    private IAlarmService iAlarmService;


    @Autowired
    private RedisService redisService;

    @Override
    public Page<AlarmPartialDischargeDto> selectAlarmPartialDischargePage(AlarmPartialDischarge alarmPartialDischarge) {
        Long currentTenantId = SecurityUtils.getCurrentTenantId();
        alarmPartialDischarge.setTenantId(currentTenantId);
        QueryWrapper<AlarmPartialDischarge> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(StringUtils.isNotBlank(alarmPartialDischarge.getSceneType()),"scene_type",alarmPartialDischarge.getSceneType());
        queryWrapper.eq(StringUtils.isNotBlank(alarmPartialDischarge.getAlarmType()),"alarm_type",alarmPartialDischarge.getAlarmType());
        queryWrapper.eq(StringUtils.isNotBlank(alarmPartialDischarge.getDeviceSn()),"device_sn",alarmPartialDischarge.getDeviceSn());
        queryWrapper.eq(alarmPartialDischarge.getTenantId()!=null,"tenant_id",alarmPartialDischarge.getTenantId());
        // 添加开始时间条件
        if (alarmPartialDischarge.getStartTime() != null) {
            queryWrapper.gt("a.alarm_beginTime", alarmPartialDischarge.getStartTime());
        }

        // 添加结束时间条件
        if (alarmPartialDischarge.getEndTime() != null) {
            queryWrapper.lt( "a.alarm_beginTime",alarmPartialDischarge.getEndTime());
        }

        Page<AlarmPartialDischargeDto> alarmPartialDischargeDtoPage = this.baseMapper.selectPDListPage(new Page<>(alarmPartialDischarge.getPageNum(), alarmPartialDischarge.getPageSize()), queryWrapper);
        List<AlarmPartialDischargeDto> records = alarmPartialDischargeDtoPage.getRecords();
        List<String> deviceIdList = records.parallelStream().map(AlarmPartialDischargeDto::getDeviceSn).distinct().collect(Collectors.toList());

        Map<String, String> deviceMap = new HashMap<>();
        for (String id :deviceIdList) {
            DeviceKeyInfoDTO device = redisService.getCacheObject(Constants.DEVICE_SN_KEY + id);
            deviceMap.put(id,device.getDeviceName());
        }
        for (AlarmPartialDischargeDto pd:records){
            pd.setDeviceName(deviceMap.get(pd.getDeviceSn()));
        }

        return alarmPartialDischargeDtoPage;
    }

    /**
     * 查询局放报警详情
     * 
     * @param alarmId 局放报警详情ID
     * @return 局放报警详情
     */
    @Override
    public AlarmPartialDischarge selectAlarmPartialDischargeById(Long alarmId)
    {
        return alarmPartialDischargeMapper.selectAlarmPartialDischargeById(alarmId);
    }

    /**
     * 查询局放报警详情列表
     * 
     * @param alarmPartialDischarge 局放报警详情
     * @return 局放报警详情
     */
    @Override
    public List<AlarmPartialDischargeDto> selectAlarmPartialDischargeList(AlarmPartialDischarge alarmPartialDischarge)
    {
        Long currentTenantId = SecurityUtils.getCurrentTenantId();
        alarmPartialDischarge.setTenantId(currentTenantId);
        List<AlarmPartialDischargeDto> alarmPartialDischarges = alarmPartialDischargeMapper.selectAlarmPartialDischargeList(alarmPartialDischarge);
//        List<Long> deviceIdList = alarmPartialDischarges.parallelStream().map(AlarmPartialDischargeDto::getDeviceId).distinct().collect(Collectors.toList());
//        R<List<DeviceKeyInfoDTO>> getDeviceNameResult = deviceService.selectDeviceNameByDeviceIds(deviceIdList);
//        if (R.FAIL == getDeviceNameResult.getCode()) {
//            throw new BaseException(getDeviceNameResult.getMsg());
//        }
//        List<DeviceKeyInfoDTO> getDeviceNameList = getDeviceNameResult.getData();
//        Map<Long, String> deviceMap = getDeviceNameList.parallelStream().collect(Collectors.toMap(DeviceKeyInfoDTO::getDeviceId, DeviceKeyInfoDTO::getDeviceName));
//        for (AlarmPartialDischargeDto dto : alarmPartialDischarges) {
//            //设备名称
//            dto.setDeviceName(deviceMap.get(dto.getDeviceId()));
//        }
        return alarmPartialDischarges;
    }

    /**
     * 在线报警顶部统计
     * @param alarmPartialDischarge
     * @return
     */
    @Override
    public AlarmPartialDischargeCount partialDischargeCount(AlarmPartialDischarge alarmPartialDischarge){
        Long currentTenantId = SecurityUtils.getCurrentTenantId();
        alarmPartialDischarge.setTenantId(currentTenantId);
        alarmPartialDischarge.setSceneType(SceneTypeEnums.SCENE_TYPE_6.getKey()+"");
        alarmPartialDischarge.setAlarmType(AlarmTypeEnums.ALARM_TYPE_ENUMS_2.getDescription());
        AlarmPartialDischargeCount alarmPartialDischargeCount = new AlarmPartialDischargeCount();
        int todayAttention = 0;
        int todayAlarm = 0;
        int attention = 0;
        int alarm = 0;
        // 获取今天的年月日
        List<AlarmPartialDischargeDto> alarmPartialDischargeDtos = alarmPartialDischargeMapper.selectAlarmPartialDischargeAll(alarmPartialDischarge);
        LocalDate today = LocalDate.now();

        if (!alarmPartialDischargeDtos.isEmpty()) {
            for (AlarmPartialDischargeDto item1 : alarmPartialDischargeDtos) {
                LocalDate alarmBeginTime = item1.getAlarmBeginTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                alarm += item1.getAlarmNumber();
                attention += item1.getAttentionNumber();
                if (today.equals(alarmBeginTime)) {
                    todayAttention += item1.getAttentionNumber();
                    todayAlarm += item1.getAlarmNumber();
                }
            }
        }

        alarmPartialDischargeCount.setAllAlarmNumber(alarm);
        alarmPartialDischargeCount.setAllAttentionNumber(attention);
        alarmPartialDischargeCount.setTodayAlarmNumber(todayAlarm);
        alarmPartialDischargeCount.setTodayAttentionNumber(todayAttention);
        return alarmPartialDischargeCount;
    }

    /**
     * 在线局放的报警类型
     * @param alarmQueryParameter
     * @return
     */
    @Override
    public  Map<String,Long> detectionModeCount(AlarmQueryParameter alarmQueryParameter){
        Long currentTenantId = SecurityUtils.getCurrentTenantId();
        alarmQueryParameter.setTenantId(currentTenantId);
        alarmQueryParameter.setSceneType(SceneTypeEnums.SCENE_TYPE_6.getKey()+"");
        List<Alarm> alarmList = iAlarmService.selectAlarmByQueryParameter(alarmQueryParameter);
        long broken = alarmList.stream().filter(item ->  AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getDescription().equals(item.getAlarmType() )).count();
        long partialDischarge = alarmList.stream().filter(item ->   AlarmTypeEnums.ALARM_TYPE_ENUMS_2.getDescription().equals(item.getAlarmType())).count();
        Map<String, Long> map1 = new HashMap<>();
        map1.put("breakAlarm", broken);
        map1.put("partialDischargeAlarm", partialDischarge);
        return map1;
    }

    /**
     * 在线局放通道报警
     * @param alarmQueryParameter
     * @return
     */
    @Override
    public  List<Map<String,Integer>> channelModeCount(AlarmQueryParameter alarmQueryParameter){
        alarmQueryParameter.setSceneType(SceneTypeEnums.SCENE_TYPE_6.getKey()+"");
        List<AlarmPartialDischargeDto> alarmPartialDischargeDtos = alarmPartialDischargeMapper.channelOrDeviceModeCount(alarmQueryParameter);
        //每个通道 断线报警
        Map<Integer, Integer> countMap = new HashMap<>();
        if(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getDescription().equals(alarmQueryParameter.getAlarmType())){

            countMap = alarmPartialDischargeDtos.stream()
                    .filter(item -> item.getAlarmType().equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getDescription()))
                    .collect(Collectors.groupingBy(AlarmPartialDischargeDto::getSensorId, Collectors.summingInt(e -> 1)));
        }else{
             countMap = alarmPartialDischargeDtos.stream()
                    .filter(item -> item.getAlarmType().equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_2.getDescription()))
                    .collect(Collectors.groupingBy(AlarmPartialDischargeDto::getSensorId, Collectors.summingInt(AlarmPartialDischargeDto::getAlarmNumber)));
        }
        List<Map<String, Integer>> resultList = countMap.entrySet().stream()
                .map(entry -> {
                    Map<String, Integer> map = new HashMap<>();
                    map.put("channel", entry.getKey());
                    map.put("value", entry.getValue());
                    return map;
                })
                .collect(Collectors.toList());
        return resultList;
    }

    /**
     * 在线局放设备报警总数
     * @param alarmQueryParameter
     * @return
     */
    @Override
    public  List<Map<String,String>> deviceAlarm(AlarmQueryParameter alarmQueryParameter){
        alarmQueryParameter.setSceneType(SceneTypeEnums.SCENE_TYPE_6.getKey()+"");
        List<AlarmPartialDischargeDto> alarmPartialDischargeDtos = alarmPartialDischargeMapper.channelOrDeviceModeCount(alarmQueryParameter);
        Map<String, Integer> countMap = new HashMap<>();
        if(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getDescription().equals(alarmQueryParameter.getAlarmType())){
            countMap = alarmPartialDischargeDtos.stream()
                    .filter(item -> item.getAlarmType().equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getDescription()))
                    .collect(Collectors.groupingBy(AlarmPartialDischargeDto::getDeviceSn,  Collectors.summingInt(e -> 1)));

        }else {
                     countMap = alarmPartialDischargeDtos.stream()
                    .filter(item -> item.getAlarmType().equals(AlarmTypeEnums.ALARM_TYPE_ENUMS_2.getDescription()))
                    .collect(Collectors.groupingBy(AlarmPartialDischargeDto::getDeviceSn, Collectors.summingInt(AlarmPartialDischargeDto::getAlarmNumber)));

        }

        List<Map<String, String>> resultList = countMap.entrySet().stream()
                .sorted((entry1, entry2) -> Integer.compare(entry2.getValue(), entry1.getValue()))
                .limit(5)
                .map(entry -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("deviceSn", entry.getKey());
                    map.put("value", entry.getValue().toString());
                    return map;
                })
                .collect(Collectors.toList());
        return resultList;
    }

    /**
     * 客户下所有设备每天的报警总数
     * @param alarmQueryParameter
     * @return
     */
    @Override
    public   List<Map<String, Object>> deviceAlarmOfDayByCustomer (AlarmQueryParameter alarmQueryParameter){
        alarmQueryParameter.setSceneType(SceneTypeEnums.SCENE_TYPE_6.getKey()+"");
        if (alarmQueryParameter.getStartTime() == null || alarmQueryParameter.getEndTime() == null){
            LocalDate today = LocalDate.now();
            long sixDay = 24 * 60 * 60 * 1000*6;
        LocalDateTime startDateTime = LocalDateTime.of(today, LocalTime.MIN);
        LocalDateTime endDateTime = LocalDateTime.of(today, LocalTime.MAX);
            Date min = Date.from(startDateTime.atZone(ZoneId.systemDefault()).toInstant());
            Date max = Date.from(endDateTime.atZone(ZoneId.systemDefault()).toInstant());

            alarmQueryParameter.setEndTime(max);
            alarmQueryParameter.setStartTime(new Date(min.getTime()-sixDay));
        }
        List<AlarmPartialDischargeDto> alarmPartialDischargeDtos = alarmPartialDischargeMapper.deviceAlarmOfDayByCustomer(alarmQueryParameter);

        Map<String, Map<LocalDate, Integer>> deviceAlarmCountPerDay = new HashMap<>();
        // 计算每个设备每天的报警总数
        if(AlarmTypeEnums.ALARM_TYPE_ENUMS_6.getDescription().equals(alarmQueryParameter.getAlarmType())){
            deviceAlarmCountPerDay = alarmPartialDischargeDtos.stream()
                    .collect(Collectors.groupingBy(AlarmPartialDischargeDto::getDeviceSn,
                            Collectors.groupingBy(dto -> dto.getAlarmBeginTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDate(),
                                    Collectors.summingInt(e -> 1))));
        }else {
                     deviceAlarmCountPerDay = alarmPartialDischargeDtos.stream()
                     .filter(dto -> dto.getAlarmNumber() != null)
                     .collect(Collectors.groupingBy(AlarmPartialDischargeDto::getDeviceSn,
                            Collectors.groupingBy(dto -> dto.getAlarmBeginTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDate(),
                                    Collectors.summingInt(AlarmPartialDischargeDto::getAlarmNumber))));
        }
        List<Map<String, Object>> convertedData = deviceAlarmCountPerDay.entrySet().stream()
                .map(entry -> {
                    List<Map<String, Object>> dataList = entry.getValue().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .map(innerEntry -> {
                                Map<String, Object> data = new HashMap<>();
                                data.put("date", innerEntry.getKey().toString());
                                data.put("count", innerEntry.getValue());
                                return data;
                            })
                            .collect(Collectors.toList());
                    Map<String, Object> result = new HashMap<>();
                    result.put("deviceSn", entry.getKey());
                    result.put("data", dataList);

                    return result;
                })
                .collect(Collectors.toList());
        return convertedData;

    }


    /**
     * 七天内报警的放电类型统计占比
     * @param alarmQueryParameter
     * @return
     */
    @Override
    public   List<Map<String, Object>> alarmDPType (AlarmQueryParameter alarmQueryParameter){
        alarmQueryParameter.setSceneType(SceneTypeEnums.SCENE_TYPE_6.getKey()+"");
        if (alarmQueryParameter.getStartTime() == null || alarmQueryParameter.getEndTime() == null) {
            LocalDate today = LocalDate.now();
            long sevenDaysInMillis = 24 * 60 * 60 * 1000 * 7; // 7 days in milliseconds

            LocalDateTime startDateTime = LocalDateTime.of(today, LocalTime.MIN);
            LocalDateTime endDateTime = LocalDateTime.of(today, LocalTime.MAX);

            Date todayStart = Date.from(startDateTime.atZone(ZoneId.systemDefault()).toInstant());
            Date todayEnd = Date.from(endDateTime.atZone(ZoneId.systemDefault()).toInstant());

            //如果开始结束时间都没有或者只有结束就七天 如果有开始没结束就到开始到现在
            if (alarmQueryParameter.getStartTime() == null && alarmQueryParameter.getEndTime() == null) {
                alarmQueryParameter.setStartTime(new Date(todayStart.getTime() - sevenDaysInMillis));
                alarmQueryParameter.setEndTime(todayEnd);
            } else if (alarmQueryParameter.getStartTime() == null && alarmQueryParameter.getEndTime() != null) {
                alarmQueryParameter.setStartTime(new Date(todayEnd.getTime() - sevenDaysInMillis));
            } else if (alarmQueryParameter.getStartTime() != null && alarmQueryParameter.getEndTime() == null) {
                alarmQueryParameter.setEndTime(todayEnd);
            }
        }
        List<AlarmPartialDischargeDto> alarmPartialDischargeDtos = alarmPartialDischargeMapper.deviceAlarmOfDayByCustomer(alarmQueryParameter);

        /**使用一个数组存放初始的各个类型概率百分比 **/
        double[] type ={0.00,0.00,0.00,0.00};
        int count = 0;
      for (AlarmPartialDischargeDto pd :alarmPartialDischargeDtos){
          if(!StringUtils.isBlank(pd.getPdType())) {
              count +=1;
              String[] split = pd.getPdType().split(",");
              for (int i=0;i<type.length;i++) {
                  type[i] += Double.parseDouble(split[i]);
              }
          }
      }
        List<Map<String, Object>> res = new ArrayList<>();
      //如果没有数据防止报错
        int count1 = count == 0 ? 1 : count;
        for (int i=0;i<4;i++){
            //保留两位小数
            BigDecimal result = new BigDecimal(type[i] / count1*100).setScale(4, RoundingMode.HALF_UP);
            HashMap<String, Object> objectObjectHashMap = new HashMap<>();
            objectObjectHashMap.put("probability",String.valueOf(result));
            objectObjectHashMap.put("name",String.valueOf(i));
            res.add(objectObjectHashMap);

        }

        return res;

    }



    /**
     * 新增局放报警详情
     * 
     * @param alarmPartialDischarge 局放报警详情
     * @return 结果
     */
    @Override
    public int insertAlarmPartialDischarge(AlarmPartialDischarge alarmPartialDischarge)
    {
        return alarmPartialDischargeMapper.insertAlarmPartialDischarge(alarmPartialDischarge);
    }

    /**
     * 修改局放报警详情
     * 
     * @param alarmPartialDischarge 局放报警详情
     * @return 结果
     */
    @Override
    public int updateAlarmPartialDischarge(AlarmPartialDischarge alarmPartialDischarge)
    {
        return alarmPartialDischargeMapper.updateAlarmPartialDischarge(alarmPartialDischarge);
    }

    /**
     * 批量删除局放报警详情
     * 
     * @param alarmIds 需要删除的局放报警详情ID
     * @return 结果
     */
    @Override
    public int deleteAlarmPartialDischargeByIds(Long[] alarmIds)
    {
        return alarmPartialDischargeMapper.deleteAlarmPartialDischargeByIds(alarmIds);
    }

    /**
     * 删除局放报警详情信息
     * 
     * @param alarmId 局放报警详情ID
     * @return 结果
     */
    @Override
    public int deleteAlarmPartialDischargeById(Long alarmId)
    {
        return alarmPartialDischargeMapper.deleteAlarmPartialDischargeById(alarmId);
    }


}