package com.hpis.alarm.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hpis.alarm.api.dto.AlarmElectrolyticCellDTO;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.domain.AlarmElectrolyticCell;
import com.hpis.alarm.dto.AlarmDetailEc;
import com.hpis.alarm.dto.RepeatAlarmDto;
import com.hpis.alarm.enums.AlarmTypeEnums;
import com.hpis.alarm.mapper.AlarmElectrolyticCellMapper;
import com.hpis.alarm.mapper.AlarmMapper;
import com.hpis.alarm.service.IAlarmElectrolyticCellService;
import com.hpis.alarm.service.IAlarmService;
import com.hpis.alarm.util.DictUtil;

import com.hpis.common.core.constant.Constants;
import com.hpis.common.core.constant.OperCodeConstants;
import com.hpis.common.core.domain.DeviceKeyInfoDTO;
import com.hpis.common.core.domain.R;
import com.hpis.common.core.enums.DeviceTypeCodeEnums;

import com.hpis.common.core.exception.BaseException;
import com.hpis.common.core.utils.DateUtils;
import com.hpis.common.core.utils.SecurityUtils;
import com.hpis.common.core.utils.StringUtils;
import com.hpis.common.core.utils.bean.BeanUtils;
import com.hpis.common.core.utils.poi.CustomExportExcelUtil;
import com.hpis.common.redis.service.RedisService;
import com.hpis.common.security.service.TokenService;

import com.hpis.common.websocket.WebSocketKeepAliveClient;
import com.hpis.common.websocket.model.TransferCommandObject;
import com.hpis.common.websocket.util.CommonTranferUtil;
import com.hpis.electrolyticCell.api.RemoteElectrolyticSequenceService;
import com.hpis.electrolyticCell.api.dto.EcStatisticsDTO;
import com.hpis.electrolyticCell.api.dto.ElectrolyticPlaceAlarmDTO;
import com.hpis.system.api.domain.SysDictData;
import com.hpis.system.api.model.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFCell;
import org.apache.poi.xssf.streaming.SXSSFRow;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.text.MessageFormat;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 电解槽关联报警Service业务层处理
 *
 * @author ruoyi
 * @date 2023-04-25
 */
@Service
@RefreshScope
@Slf4j
public class AlarmElectrolyticCellServiceImpl extends ServiceImpl<AlarmElectrolyticCellMapper, AlarmElectrolyticCell> implements IAlarmElectrolyticCellService
{
    @Autowired
    private AlarmElectrolyticCellMapper alarmElectrolyticCellMapper;

    @Autowired
    private RemoteElectrolyticSequenceService electrolyticCellSequenceMapper;

//    @Autowired
//    private RemoteDeviceService deviceService;

    @Autowired
    private IAlarmService iAlarmService;

    @Autowired
    @Nullable// 明确标记可为空
    private WebSocketKeepAliveClient webSocketClient;

    @Autowired
    private RedisService redisService;

    @Autowired
    private NacosDiscoveryProperties nacosDiscoveryProperties;

    @Autowired
    private  RemoteElectrolyticSequenceService remoteElectrolyticSequenceService;

    @Autowired
    private TokenService tokenService;

    @Override
    public Page<AlarmDetailEc> testSelect(AlarmElectrolyticCell alarmElectrolyticCell) {

        QueryWrapper<AlarmElectrolyticCell> queryWrapper = new QueryWrapper<>();
        // ------------------------- 基础条件 -------------------------
        queryWrapper
                .eq("a.del_flag", '0')
                .eq(alarmElectrolyticCell.getAlarmId() != null, "c.alarm_id", alarmElectrolyticCell.getAlarmId())
                .like(StringUtils.isNotBlank(alarmElectrolyticCell.getSequenceName()), "a.target_name", alarmElectrolyticCell.getSequenceName() + "%")
                .eq(alarmElectrolyticCell.getRowIndex() != null, "c.row_index", alarmElectrolyticCell.getRowIndex())
                .eq(alarmElectrolyticCell.getGrooveNumber() != null, "c.groove_number", alarmElectrolyticCell.getGrooveNumber())
                .ge(alarmElectrolyticCell.getTemperatureVariation() != null, "c.temperature_variation", alarmElectrolyticCell.getTemperatureVariation())
                .eq(StringUtils.isNotBlank(alarmElectrolyticCell.getAlarmType()), "a.alarm_type", alarmElectrolyticCell.getAlarmType())
                .eq(StringUtils.isNotBlank(alarmElectrolyticCell.getAlarmRank()), "a.alarm_rank", alarmElectrolyticCell.getAlarmRank())
                .eq(StringUtils.isNotBlank(alarmElectrolyticCell.getAlarmStatus()), "a.alarm_status", alarmElectrolyticCell.getAlarmStatus())
                .ge(alarmElectrolyticCell.getStartTime() != null, "a.alarm_beginTime", alarmElectrolyticCell.getStartTime())
                .le(alarmElectrolyticCell.getEndTime() != null, "a.alarm_beginTime", alarmElectrolyticCell.getEndTime())
                .eq(StringUtils.isNotBlank(alarmElectrolyticCell.getSceneType()), "a.scene_type", alarmElectrolyticCell.getSceneType())
                .eq(alarmElectrolyticCell.getHandlerId() != null, "h.handler_id", alarmElectrolyticCell.getHandlerId())
                .eq(StringUtils.isNotBlank(alarmElectrolyticCell.getHandleStatus()), "h.handle_status", alarmElectrolyticCell.getHandleStatus())
                .eq(alarmElectrolyticCell.getDeviceSn() != null, "a.device_sn", alarmElectrolyticCell.getDeviceSn())
                .eq(alarmElectrolyticCell.getTenantId() != null, "a.tenant_id", alarmElectrolyticCell.getTenantId())
                .like(StringUtils.isNotBlank(alarmElectrolyticCell.getDeviceName()), "d.device_name", "%" + alarmElectrolyticCell.getDeviceName() + "%")
                .isNull(alarmElectrolyticCell.getStopAlarmFlag() != null && alarmElectrolyticCell.getStopAlarmFlag() == 0, "a.alarm_endTime")
                .and(wrapper -> {
                    String sequenceId = alarmElectrolyticCell.getSequenceId();
                    if ("-1".equals(sequenceId)) {
                        wrapper.isNotNull("c.sequence_id");
                    } else if (sequenceId != null) {
                        wrapper.eq("c.sequence_id", sequenceId);
                    } else {
                        wrapper.eq("1", 1); // 避免空条件
                    }
                })
                .and(wrapper -> {
                            Integer busBarsNumber = alarmElectrolyticCell.getBusBarsNumber();
                            Integer electrodesNumber = alarmElectrolyticCell.getElectrodesNumber();
                            if (busBarsNumber != null) {
                                wrapper.eq("c.subdivide_number", busBarsNumber)
                                        .eq("c.observation_place", 2);
                            } else if (electrodesNumber != null) {
                                wrapper.eq("c.subdivide_number", electrodesNumber)
                                        .eq("c.observation_place", 1);
                            } else {
                                if (alarmElectrolyticCell.getObservationPlace() != null) {
                                    wrapper.eq("c.observation_place", alarmElectrolyticCell.getObservationPlace()); // 避免空条件
                                }else {
                                    wrapper.eq("1", 1); // 避免空条件
                                }
                            }
                        }
                );

// 单独处理 IN 条件
        if (alarmElectrolyticCell.getAlarmTypes() != null && alarmElectrolyticCell.getAlarmTypes().length > 0) {
            List<Integer> alarmTypeInts = Arrays.stream(alarmElectrolyticCell.getAlarmTypes())
                    .map(Integer::parseInt)
                    .collect(Collectors.toList());

            queryWrapper.in("a.alarm_type", alarmTypeInts); // 传入转换后的 List<Integer>
//            queryWrapper.in("a.alarm_type", Arrays.asList(alarmElectrolyticCell.getAlarmTypes()));
        }
// ------------------------- 动态排序 -------------------------
        if (alarmElectrolyticCell.getStopAlarmFlag() != null && alarmElectrolyticCell.getStopAlarmFlag() == 0) {
            queryWrapper.orderByAsc("c.groove_number");
        }
        queryWrapper.orderByDesc("a.alarm_beginTime", "c.alarm_id");
        Page<AlarmDetailEc>  alarmElectrolyticCellDTOList  = this.baseMapper.selectECPage(new Page<>(alarmElectrolyticCell.getPageNum(), alarmElectrolyticCell.getPageSize()), queryWrapper);

return alarmElectrolyticCellDTOList;

    }

    /**
     * 查询电解槽关联报警
     *
     * @param alarmId 电解槽关联报警ID
     * @return 电解槽关联报警
     */
    @Override
    public AlarmDetailEc selectAlarmElectrolyticCellById(Long alarmId) {

        AlarmElectrolyticCellDTO alarmElectrolyticCellDTO = alarmElectrolyticCellMapper.selectAlarmElectrolyticCellDetailById(alarmId);
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("irmsSn", alarmElectrolyticCellDTO.getIrmsSn());

        if ( StringUtils.isNotBlank(alarmElectrolyticCellDTO.getPicturePath())){
            StringBuilder sb = new StringBuilder();
            // 去掉最后一个分号
            String  str = alarmElectrolyticCellDTO.getPicturePath().substring(0, alarmElectrolyticCellDTO.getPicturePath().length() - 1);
            String[] result = str.split(";");

            for (String element : result) {
//                jsonObject.put("path", element);
//                jsonObject.put("cmd", RequestCmdEnums.SEND_GET_FILE.getCmd());
//                MyWebSocketClient webSocketClient = WebSocketClientBeanConfig.getWebSocketClientInstance(wsUrl);
//                webSocketClient.sendMessage(jsonObject);
////                 String url =iAlarmService.getAlarmFileByJSONObject(jsonObject);
//
//                JSONObject returnJson = null;
//                try {
//                    returnJson = webSocketClient.getExcptMessageByJson();
//                    String picture = returnJson.getString("file");
//                    sb.append(picture+";");
//                } catch (Exception e) {
//                    log.error(e.getMessage());
//                    throw new RuntimeException(e);
//                } finally {
//                    webSocketClient.close();
//                }
//
                jsonObject.put("path", element);
                int cmdSeq = CommonTranferUtil.getCmdSeq(nacosDiscoveryProperties.getService());
                TransferCommandObject obj = TransferCommandObject.initializeByDevOperate(cmdSeq, alarmElectrolyticCellDTO.getIrmsSn(), DeviceTypeCodeEnums.TYPE_1.getKey(), OperCodeConstants.IRMS_PICTURE, jsonObject);

                webSocketClient.sendMessage(obj);
                JSONObject returnJson = webSocketClient.getDataByExcptMessage(nacosDiscoveryProperties.getService(), cmdSeq);
                String picture = returnJson.getString("file");
                sb.append(picture+";");
            }

            alarmElectrolyticCellDTO.setPicturePath(sb.deleteCharAt(sb.length() - 1).toString());
        }


        AlarmDetailEc alarmDetailEc = new AlarmDetailEc();

        BeanUtils.copyBeanProp(alarmDetailEc,alarmElectrolyticCellDTO);
        Map<String, List<ElectrolyticPlaceAlarmDTO>> list=redisService.getCacheObject(Constants.EC_ALARM_PLACE +alarmElectrolyticCellDTO.getIrmsSn());
        System.out.println(list);
        ElectrolyticPlaceAlarmDTO electrolyticPlaceAlarmDTO;
        if( !"0".equals(alarmElectrolyticCellDTO.getObservationPlace())) {
            if (list != null && list.size() > 0 ) {
                electrolyticPlaceAlarmDTO = list.get(alarmElectrolyticCellDTO.getObservationPlace()).get(0);
            } else {
                R<List<ElectrolyticPlaceAlarmDTO>> listR = remoteElectrolyticSequenceService.placeList(jsonObject);
                if (listR.getData() != null) {

                    Map<String, List<ElectrolyticPlaceAlarmDTO>> map = listR.getData().stream()
                            .collect(Collectors.groupingBy(ElectrolyticPlaceAlarmDTO::getObservationPlace));
                    electrolyticPlaceAlarmDTO = map.get(alarmElectrolyticCellDTO.getObservationPlace()).get(0);

                    redisService.setCacheObject(Constants.EC_ALARM_PLACE + alarmElectrolyticCellDTO.getIrmsSn(), map);
                } else {
                    return alarmDetailEc;
                }
            }
            alarmDetailEc.setGeneralAlarm(electrolyticPlaceAlarmDTO.getGeneralAlarm());
            alarmDetailEc.setEmergencyAlarm(electrolyticPlaceAlarmDTO.getEmergencyAlarm());
            alarmDetailEc.setCriticalAlarm(electrolyticPlaceAlarmDTO.getCriticalAlarm());
        }
        return alarmDetailEc;
    }


    /**
     * 查询电解槽关联报警列表
     *
     * @param alarmElectrolyticCell 电解槽关联报警
     * @return 电解槽关联报警
     */
    @Override
    public Page<AlarmDetailEc> selectAlarmElectrolyticCellList(AlarmElectrolyticCell alarmElectrolyticCell) {

        Long currentTenantId = SecurityUtils.getCurrentTenantId();
        alarmElectrolyticCell.setTenantId(currentTenantId);

        QueryWrapper<AlarmElectrolyticCell> queryWrapper = new QueryWrapper<>();
//        queryWrapper.eq(StringUtils.isNotBlank(alarmElectrolyticCell.getSceneType()), "a.scene_type", alarmElectrolyticCell.getSceneType())
//                .eq(StringUtils.isNotBlank(alarmElectrolyticCell.getAlarmType()), "a.alarm_type", alarmElectrolyticCell.getAlarmType())
//                .eq(StringUtils.isNotBlank(alarmElectrolyticCell.getAlarmRank()), "a.alarm_rank", alarmElectrolyticCell.getAlarmRank())
//                .eq(StringUtils.isNotBlank(alarmElectrolyticCell.getAlarmStatus()), "a.alarm_status", alarmElectrolyticCell.getAlarmStatus())
//                .eq(alarmElectrolyticCell.getDeviceSn() != null, "a.device_sn", alarmElectrolyticCell.getDeviceSn())
//                .eq(alarmElectrolyticCell.getTenantId() != null, "a.tenant_id", alarmElectrolyticCell.getTenantId())
//                .like(StringUtils.isNotBlank(alarmElectrolyticCell.getTargetName()), "a.target_name", alarmElectrolyticCell.getTargetName());
//        // 添加开始时间条件
//        if (alarm.getStartTime() != null) {
//            queryWrapper.gt("a.alarm_beginTime", alarm.getStartTime());
//        }
//
//        // 添加结束时间条件
//        if (alarm.getEndTime() != null) {
//            queryWrapper.lt("a.alarm_beginTime", alarm.getEndTime());
//        }
        // ------------------------- 基础条件 -------------------------
        queryWrapper
                .eq("a.del_flag", '0')
                .eq(alarmElectrolyticCell.getAlarmId() != null, "c.alarm_id", alarmElectrolyticCell.getAlarmId())
                .like(StringUtils.isNotBlank(alarmElectrolyticCell.getSequenceName()), "a.target_name", alarmElectrolyticCell.getSequenceName() + "%")
                .eq(alarmElectrolyticCell.getRowIndex() != null, "c.row_index", alarmElectrolyticCell.getRowIndex())
                .eq(alarmElectrolyticCell.getGrooveNumber() != null, "c.groove_number", alarmElectrolyticCell.getGrooveNumber())
                .ge(alarmElectrolyticCell.getTemperatureVariation() != null, "c.temperature_variation", alarmElectrolyticCell.getTemperatureVariation())
                .eq(StringUtils.isNotBlank(alarmElectrolyticCell.getAlarmType()), "a.alarm_type", alarmElectrolyticCell.getAlarmType())
                .eq(StringUtils.isNotBlank(alarmElectrolyticCell.getAlarmRank()), "a.alarm_rank", alarmElectrolyticCell.getAlarmRank())
                .eq(StringUtils.isNotBlank(alarmElectrolyticCell.getAlarmStatus()), "a.alarm_status", alarmElectrolyticCell.getAlarmStatus())

                .ge(alarmElectrolyticCell.getStartTime() != null, "a.alarm_beginTime", alarmElectrolyticCell.getStartTime())
                .le(alarmElectrolyticCell.getEndTime() != null, "a.alarm_beginTime", alarmElectrolyticCell.getEndTime())
                .eq(StringUtils.isNotBlank(alarmElectrolyticCell.getSceneType()), "a.scene_type", alarmElectrolyticCell.getSceneType())
                .eq(alarmElectrolyticCell.getHandlerId() != null, "h.handler_id", alarmElectrolyticCell.getHandlerId())
                .eq(StringUtils.isNotBlank(alarmElectrolyticCell.getHandleStatus()), "h.handle_status", alarmElectrolyticCell.getHandleStatus())
                .eq(alarmElectrolyticCell.getDeviceSn() != null, "a.device_sn", alarmElectrolyticCell.getDeviceSn())
                .eq(alarmElectrolyticCell.getTenantId() != null, "a.tenant_id", alarmElectrolyticCell.getTenantId())
                .like(StringUtils.isNotBlank(alarmElectrolyticCell.getDeviceName()), "d.device_name", "%" + alarmElectrolyticCell.getDeviceName() + "%")
                .isNull(alarmElectrolyticCell.getStopAlarmFlag() != null && alarmElectrolyticCell.getStopAlarmFlag() == 0, "a.alarm_endTime");
//                .and(wrapper -> {
//                    String sequenceId = alarmElectrolyticCell.getSequenceId();
//                    if ("-1".equals(sequenceId)) {
//                        wrapper.isNotNull("c.sequence_id");
//                    } else if (sequenceId != null) {
//                        wrapper.eq("c.sequence_id", sequenceId);
//                    } else {
//                        wrapper.eq("1", 1); // 避免空条件
//                    }
//                });
        String sequenceId = alarmElectrolyticCell.getSequenceId();
        if ("-1".equals(sequenceId)) {
            queryWrapper.isNotNull("c.sequence_id");
        } else if (sequenceId != null) {
            queryWrapper.eq("c.sequence_id", sequenceId);
        }

//                .and(wrapper -> {
//                    Integer busBarsNumber = alarmElectrolyticCell.getBusBarsNumber();
//                    Integer electrodesNumber = alarmElectrolyticCell.getElectrodesNumber();
//                    if (busBarsNumber != null) {
//                        wrapper.eq("c.subdivide_number", busBarsNumber)
//                                .eq("c.observation_place", 2);
//                    } else if (electrodesNumber != null) {
//                        wrapper.eq("c.subdivide_number", electrodesNumber)
//                                .eq("c.observation_place", 1);
//                    } else {
//                       if (alarmElectrolyticCell.getObservationPlace() != null) {
//                           wrapper.eq("c.observation_place", alarmElectrolyticCell.getObservationPlace()); // 避免空条件
//                       }else {
//                           wrapper.eq("1", 1); // 避免空条件
//                       }
//                    }
//                }
//                    );
        //观察位置查询有问题 26.3.27修改,观察位置已经和irms保持一致了
        queryWrapper.eq(StringUtils.isNotBlank(alarmElectrolyticCell.getObservationPlace()), "c.observation_place", alarmElectrolyticCell.getObservationPlace())
                .eq(alarmElectrolyticCell.getBusBarsNumber() != null, "c.subdivide_number", alarmElectrolyticCell.getBusBarsNumber())
                .eq(alarmElectrolyticCell.getElectrodesNumber() != null, "c.subdivide_number", alarmElectrolyticCell.getElectrodesNumber());


// 单独处理 IN 条件
        if (alarmElectrolyticCell.getAlarmTypes() != null && alarmElectrolyticCell.getAlarmTypes().length > 0) {
            List<Integer> alarmTypeInts = Arrays.stream(alarmElectrolyticCell.getAlarmTypes())
                    .map(Integer::parseInt)
                    .collect(Collectors.toList());

            queryWrapper.in("a.alarm_type", alarmTypeInts); // 传入转换后的 List<Integer>
//            queryWrapper.in("a.alarm_type", Arrays.asList(alarmElectrolyticCell.getAlarmTypes()));
        }
// ------------------------- 动态排序 -------------------------
        if (alarmElectrolyticCell.getStopAlarmFlag() != null && alarmElectrolyticCell.getStopAlarmFlag() == 0) {
            queryWrapper.orderByAsc("c.groove_number");
        }
        queryWrapper.orderByDesc("a.alarm_beginTime", "c.alarm_id");


        Page<AlarmDetailEc> alarmElectrolyticCellDTOList = this.baseMapper.selectECPage(new Page<>(alarmElectrolyticCell.getPageNum(), alarmElectrolyticCell.getPageSize()), queryWrapper);
//        List<AlarmDetailEc> alarmElectrolyticCellDTOList = alarmElectrolyticCellMapper.selectAlarmAlarmDetailEcList(alarmElectrolyticCell);


        List<String> deviceIdList = alarmElectrolyticCellDTOList.getRecords().parallelStream().map(AlarmDetailEc::getDeviceSn).distinct().collect(Collectors.toList());
        List<String> collect = alarmElectrolyticCellDTOList.getRecords().parallelStream().map(AlarmDetailEc::getIrmsSn).distinct().collect(Collectors.toList());
        Map<String, Map<String, List<ElectrolyticPlaceAlarmDTO>>> map = new HashMap<>();
        for (String irms : collect) {
            Map<String, List<ElectrolyticPlaceAlarmDTO>> list = redisService.getCacheObject(Constants.EC_ALARM_PLACE + irms);
            if (list != null) {
                map.put(irms, list);
            }
        }

        Map<String, String> deviceMap = new HashMap<>();
        //报警设备名称
        for (String sn : deviceIdList) {
            DeviceKeyInfoDTO device = redisService.getCacheObject(Constants.DEVICE_SN_KEY + sn);
            deviceMap.put(sn, device != null ? device.getDeviceName() : "");
        }
        //获取报警的导则
        for (AlarmDetailEc dto : alarmElectrolyticCellDTOList.getRecords()) {
            //设备名称
            dto.setDeviceName(deviceMap.get(dto.getDeviceSn()));
            if (!map.isEmpty()) {
                Map<String, List<ElectrolyticPlaceAlarmDTO>> stringListMap = map.get(dto.getIrmsSn());

                if (stringListMap == null || stringListMap.get(dto.getObservationPlace()) == null) {

                    continue;
                }
                ElectrolyticPlaceAlarmDTO electrolyticPlaceAlarmDTO = stringListMap.get(dto.getObservationPlace()).get(0);

                dto.setGeneralAlarm(electrolyticPlaceAlarmDTO.getGeneralAlarm());
                dto.setEmergencyAlarm(electrolyticPlaceAlarmDTO.getEmergencyAlarm());
                dto.setCriticalAlarm(electrolyticPlaceAlarmDTO.getCriticalAlarm());
            }
        }

        return alarmElectrolyticCellDTOList;
    }




    /**
     * 新增电解槽关联报警
     *
     * @param alarmElectrolyticCell 电解槽关联报警
     * @return 结果
     */
    @Override
    public int insertAlarmElectrolyticCell(AlarmElectrolyticCell alarmElectrolyticCell)
    {
        return alarmElectrolyticCellMapper.insertAlarmElectrolyticCell(alarmElectrolyticCell);
    }

    @Override
    public int insertAlarmElectrolyticCellEctype(AlarmElectrolyticCell alarmElectrolyticCell) {
        //先删除当前点位的旧报警数据，在新增
        alarmElectrolyticCellMapper.deleteOldAlarmEctypeByPt(alarmElectrolyticCell.getSequenceId(), alarmElectrolyticCell.getRowIndex(),
                alarmElectrolyticCell.getGrooveNumber(), alarmElectrolyticCell.getObservationPlace(), alarmElectrolyticCell.getSubdivideNumber(),alarmElectrolyticCell.getIrmsSn());
        alarmElectrolyticCellMapper.insertAlarmElectrolyticCellEctype(alarmElectrolyticCell);

        return 0;
    }

    @Override
    public int insertAlarmElectrolyticCellList(List<AlarmElectrolyticCell> alarmElectrolyticCells) {
        return alarmElectrolyticCellMapper.insertAlarmElectrolyticCellList(alarmElectrolyticCells);
    }

    @Override
    public int insertAlarmElectrolyticCellEctypeList(List<AlarmElectrolyticCell> alarmElectrolyticCells) {
        if (alarmElectrolyticCells == null || alarmElectrolyticCells.isEmpty()) {
            return 0;
        }
        alarmElectrolyticCellMapper.deleteOldAlarmEctypeByItems(alarmElectrolyticCells);
        return alarmElectrolyticCellMapper.insertAlarmElectrolyticCellEctypeList(alarmElectrolyticCells);
    }

    /**
     * 修改电解槽关联报警
     *
     * @param alarmElectrolyticCell 电解槽关联报警
     * @return 结果
     */
    @Override
    public int updateAlarmElectrolyticCell(AlarmElectrolyticCell alarmElectrolyticCell)
    {
        return alarmElectrolyticCellMapper.updateAlarmElectrolyticCell(alarmElectrolyticCell);
    }

    /**
     * 批量删除电解槽关联报警
     *
     * @param alarmIds 需要删除的电解槽关联报警ID
     * @return 结果
     */
    @Override
    public int deleteAlarmElectrolyticCellByIds(Long[] alarmIds)
    {
        return alarmElectrolyticCellMapper.deleteAlarmElectrolyticCellByIds(alarmIds);
    }

    /**
     * 删除电解槽关联报警信息
     *
     * @param alarmId 电解槽关联报警ID
     * @return 结果
     */
    @Override
    public int deleteAlarmElectrolyticCellEctypeById(Long alarmId)
    {
        return alarmElectrolyticCellMapper.deleteAlarmElectrolyticCellEctypeById(alarmId);
    }

    @Override
    public int deleteAlarmECEctypeByDeviceId(String deviceSn) {
        return alarmElectrolyticCellMapper.deleteAlarmECEctypeByDeviceId(deviceSn);
    }

    @Override
    public int deleteAlarmECEctypeByIrmsSn(String deviceSn) {
        return alarmElectrolyticCellMapper.deleteAlarmECEctypeByIrmsSn(deviceSn);
    }

    /**
     * 导出统计表
     * @param response
     * @param alarmElectrolyticCell
     */
    @Override
    public void exportAlarmStatistics(HttpServletResponse response, AlarmElectrolyticCell alarmElectrolyticCell) {
        //数据统计
        //序列数据
        R<List<EcStatisticsDTO>> result = electrolyticCellSequenceMapper.selectAllSequenceAndRows();
        if (R.FAIL == result.getCode()) {
            throw new BaseException(result.getMsg());
        }
        List<EcStatisticsDTO> alarmElectrolyticCellList = result.getData();
        //根据序列分组
        Map<Long, List<EcStatisticsDTO>> sequenceMap = alarmElectrolyticCellList.parallelStream()
                .collect(Collectors.groupingBy(EcStatisticsDTO::getSequenceId));
        //报警数据（根据报警时间区间查,温度>50
        alarmElectrolyticCell.setTemperatureVariation(new BigDecimal(50));
        List<AlarmElectrolyticCellDTO> alarmElectrolyticCellDTOList = alarmElectrolyticCellMapper.selectAlarmElectrolyticCellList(alarmElectrolyticCell);
        //根据序列#跨分组
        Map<String, List<AlarmElectrolyticCellDTO>> alarmElectrolyticCellMap = alarmElectrolyticCellDTOList.parallelStream().collect(Collectors.groupingBy(data -> format("{0}#{1}", data.getSequenceId(), data.getRowIndex())));
        // 创建一个excel
        HSSFWorkbook workbook = new HSSFWorkbook();
        // excel生成过程: excel-->sheet-->row-->cell
        HSSFSheet sheet = workbook.createSheet("sheet");
        sheet.setColumnWidth(0, 30 * 256);
        sheet.setColumnWidth(1, 35 * 256);
        sheet.setColumnWidth(2, 35 * 256);
        sheet.setColumnWidth(3, 35 * 256);
        sheet.setColumnWidth(4, 20 * 256);
        sheet.setColumnWidth(5, 20 * 256);
        sheet.setColumnWidth(6, 20 * 256);
        HSSFCellStyle row1CellStyle = CustomExportExcelUtil.createCellStyle(workbook, 15, false, "center", false, "黑体", false, false, false, false, (short) 0);
        HSSFCellStyle otherRowCellStyle = CustomExportExcelUtil.createCellStyle(workbook, 12, false, "center", false, "黑体", false, false, false, false, (short) 0);
        HSSFCellStyle dateCellStyle = CustomExportExcelUtil.createCellStyle(workbook, 12, false, "center", false, "黑体", true, false, false, false, (short) 0);
        HSSFCellStyle dateTimeCellStyle = CustomExportExcelUtil.createCellStyle(workbook, 12, false, "center", false, "黑体", false, true, false, false, (short) 0);

        // 创建第一行
        HSSFRow row1 = sheet.createRow(0);
        HSSFCell row1Cell1 = row1.createCell(0);
        CustomExportExcelUtil.setDataValue("槽面发热点统计表", row1Cell1);
        row1Cell1.setCellStyle(row1CellStyle);
        // 合并单元格,起始行, 终止行, 起始列, 终止列
        CellRangeAddress cra = new CellRangeAddress(0, 0, 0, 6);
        sheet.addMergedRegion(cra);
        CustomExportExcelUtil.cellRangeAddressSetBorder(cra, sheet);
        //创建第二行
        HSSFRow row2 = sheet.createRow(1);
        HSSFCell row2Cell1 = row2.createCell(0);
        CustomExportExcelUtil.setDataValue("报表生成日期", row2Cell1);
        row2Cell1.setCellStyle(otherRowCellStyle);

        HSSFCell row2Cell2 = row2.createCell(1);
        CustomExportExcelUtil.setDataValue(DateUtils.getDate(), row2Cell2);
        row2Cell2.setCellStyle(dateCellStyle);

        //创建第三行
        HSSFRow row3 = sheet.createRow(2);
        HSSFCell row3Cell1 = row3.createCell(0);
        CustomExportExcelUtil.setDataValue("统计起始日期", row3Cell1);
        row3Cell1.setCellStyle(otherRowCellStyle);

        HSSFCell row3Cell2 = row3.createCell(1);
        CustomExportExcelUtil.setDataValue(alarmElectrolyticCell.getStartDate(), row3Cell2);
        row3Cell2.setCellStyle(dateTimeCellStyle);

        HSSFCell row3Cell3 = row3.createCell(2);
        CustomExportExcelUtil.setDataValue("统计结束日期", row3Cell3);
        row3Cell3.setCellStyle(otherRowCellStyle);

        HSSFCell row3Cell4 = row3.createCell(3);
        CustomExportExcelUtil.setDataValue(alarmElectrolyticCell.getEndDate(), row3Cell4);
        row3Cell4.setCellStyle(dateTimeCellStyle);

        HSSFCell row3Cell5 = row3.createCell(4);
        CustomExportExcelUtil.setDataValue("统计天数", row3Cell5);
        row3Cell5.setCellStyle(otherRowCellStyle);

        HSSFCell row3Cell6 = row3.createCell(5);
        CustomExportExcelUtil.setDataValue(DateUtil.betweenDay(alarmElectrolyticCell.getStartDate(), alarmElectrolyticCell.getEndDate(), true), row3Cell6);
        row3Cell6.setCellStyle(otherRowCellStyle);

        //第四行
        HSSFRow row4 = sheet.createRow(3);
        HSSFCell row4Cell1 = row4.createCell(0);
        CustomExportExcelUtil.setDataValue("系列/跨", row4Cell1);
        row4Cell1.setCellStyle(otherRowCellStyle);

        HSSFCell row4Cell2 = row4.createCell(1);
        CustomExportExcelUtil.setDataValue("50℃~80℃", row4Cell2);
        row4Cell2.setCellStyle(otherRowCellStyle);

        HSSFCell row4Cell3 = row4.createCell(2);
        CustomExportExcelUtil.setDataValue("80℃~100℃", row4Cell3);
        row4Cell3.setCellStyle(otherRowCellStyle);

        HSSFCell row4Cell4 = row4.createCell(3);
        CustomExportExcelUtil.setDataValue(">100℃", row4Cell4);
        row4Cell4.setCellStyle(otherRowCellStyle);

        HSSFCell row4Cell5 = row4.createCell(4);
        CustomExportExcelUtil.setDataValue("发热点（槽号）", row4Cell5);
        row4Cell5.setCellStyle(otherRowCellStyle);

        HSSFCell row4Cell6 = row4.createCell(5);
        CustomExportExcelUtil.setDataValue("得分", row4Cell6);
        row4Cell6.setCellStyle(otherRowCellStyle);

        HSSFCell row4Cell7 = row4.createCell(6);
        CustomExportExcelUtil.setDataValue("备注", row4Cell7);
        row4Cell7.setCellStyle(otherRowCellStyle);

        //总计
        //50~80
        int totalNumber1 = 0;
        //80~100
        int totalNumber2 = 0;
        //>100
        int totalNumber3 = 0;
        int rows = 4;
        for (Long sequenceId : sequenceMap.keySet()) {
            /**这是序列循环，包含跨，和序列小计*/
            List<EcStatisticsDTO> rowList = sequenceMap.get(sequenceId);
            //序列小计
            //50~80
            int sumNumber1 = 0;
            //80~100
            int sumNumber2 = 0;
            //>100
            int sumNumber3 = 0;
            String rowName = "";
            for (EcStatisticsDTO row : rowList) {
                /**这是跨循环,一跨=一行*/
                rowName = row.getSequenceName() + "-" + row.getRowIndex();
                //统计温度区间发生报警的槽数，同一区间不重复统计，发热点槽位不重复显示
                //50~80
                int number1 = 0;
                List<Integer> number1List = new ArrayList<>();
                //80~100
                int number2 = 0;
                List<Integer> number2List = new ArrayList<>();
                //>100
                int number3 = 0;
                List<Integer> number3List = new ArrayList<>();
                //发热点
                List<AlarmElectrolyticCellDTO> alarmList = alarmElectrolyticCellMap.containsKey(row.getSequenceId() + "#" + row.getRowIndex())
                        ? alarmElectrolyticCellMap.get(row.getSequenceId() + "#" + row.getRowIndex())
                        : new ArrayList<>();
                for (AlarmElectrolyticCellDTO alarm : alarmList) {
                    //报警数据，统计在温度区间内的槽数量(去重)
                    if ((alarm.getTemperatureVariation().floatValue() >= 50 && alarm.getTemperatureVariation().floatValue() <= 80)
                            && !number1List.contains(alarm.getGrooveNumber())) {
                        number1++;
                        number1List.add(alarm.getGrooveNumber());
                    } else if ((alarm.getTemperatureVariation().floatValue() > 80 && alarm.getTemperatureVariation().floatValue() <= 100)
                            && !number2List.contains(alarm.getGrooveNumber())) {
                        number2++;
                        number2List.add(alarm.getGrooveNumber());
                    } else if (alarm.getTemperatureVariation().floatValue() > 100 && !number3List.contains(alarm.getGrooveNumber())) {
                        number3++;
                        number3List.add(alarm.getGrooveNumber());
                    }
                }
//                System.out.println("number1:" + number1 + ",number2:" + number2 + ",number3:" + number3);
                //添加并去重
                number1List.addAll(number2List);
                number1List.addAll(number3List);
                String grooves = StrUtil.join(",", number1List.parallelStream().distinct().collect(Collectors.toList()));
//                System.out.println("grooves:" + grooves);
                //序列小计
                sumNumber1 = sumNumber1 + number1;
                sumNumber2 = sumNumber2 + number2;
                sumNumber3 = sumNumber3 + number3;
                //表格行数据
                HSSFRow dataRow = sheet.createRow(rows);
                HSSFCell row5Cell0 = dataRow.createCell(0);
                CustomExportExcelUtil.setDataValue(rowName, row5Cell0);
                row5Cell0.setCellStyle(otherRowCellStyle);

                HSSFCell row5Cell1 = dataRow.createCell(1);
                CustomExportExcelUtil.setDataValue(number1, row5Cell1);
                row5Cell1.setCellStyle(otherRowCellStyle);

                HSSFCell row5Cell2 = dataRow.createCell(2);
                CustomExportExcelUtil.setDataValue(number2, row5Cell2);
                row5Cell2.setCellStyle(otherRowCellStyle);

                HSSFCell row5Cell3 = dataRow.createCell(3);
                CustomExportExcelUtil.setDataValue(number3, row5Cell3);
                row5Cell3.setCellStyle(otherRowCellStyle);

                HSSFCell row5Cell4 = dataRow.createCell(4);
                CustomExportExcelUtil.setDataValue(grooves, row5Cell4);
                row5Cell4.setCellStyle(otherRowCellStyle);

                //更新行数
                rows = rows + 1;
//                System.out.println("rows:"+rows);
            }
//            System.out.println("sumNumber1:" + sumNumber1 + ",sumNumber2:" + sumNumber2 + ",sumNumber3:" + sumNumber3);
            //序列小计
            HSSFRow sumRow = sheet.createRow(rows);
            //更新行数
            rows = rows + 1;
//            System.out.println("rows:"+rows);
            HSSFCell sumRowCell1 = sumRow.createCell(0);
            CustomExportExcelUtil.setDataValue(rowName + "小计", sumRowCell1);
            sumRowCell1.setCellStyle(otherRowCellStyle);

            HSSFCell sumRowCell2 = sumRow.createCell(1);
            CustomExportExcelUtil.setDataValue(sumNumber1, sumRowCell2);
            sumRowCell2.setCellStyle(otherRowCellStyle);

            HSSFCell sumRowCell3 = sumRow.createCell(2);
            CustomExportExcelUtil.setDataValue(sumNumber2, sumRowCell3);
            sumRowCell3.setCellStyle(otherRowCellStyle);

            HSSFCell sumRowCell4 = sumRow.createCell(3);
            CustomExportExcelUtil.setDataValue(sumNumber3, sumRowCell4);
            sumRowCell4.setCellStyle(otherRowCellStyle);
            //总计
            totalNumber1 = totalNumber1 + sumNumber1;
            totalNumber2 = totalNumber2 + sumNumber2;
            totalNumber3 = totalNumber3 + sumNumber3;
        }
//        System.out.println("totalNumber1:" + totalNumber1 + ",totalNumber2:" + totalNumber2 + ",totalNumber3:" + totalNumber3);
        //总计
        HSSFRow totalRow = sheet.createRow(rows);
        HSSFCell totalRowCell1 = totalRow.createCell(0);
        CustomExportExcelUtil.setDataValue("总计", totalRowCell1);
        totalRowCell1.setCellStyle(otherRowCellStyle);

        HSSFCell totalRowCell2 = totalRow.createCell(1);
        CustomExportExcelUtil.setDataValue(totalNumber1, totalRowCell2);
        totalRowCell2.setCellStyle(otherRowCellStyle);

        HSSFCell totalRowCell3 = totalRow.createCell(2);
        CustomExportExcelUtil.setDataValue(totalNumber2, totalRowCell3);
        totalRowCell3.setCellStyle(otherRowCellStyle);

        HSSFCell totalRowCell4 = totalRow.createCell(3);
        CustomExportExcelUtil.setDataValue(totalNumber3, totalRowCell4);
        totalRowCell4.setCellStyle(otherRowCellStyle);
        //下载到浏览器
        CustomExportExcelUtil.setBrowserParam(response, "xxx.xlsx");
        OutputStream out = null;
        try {
            out = response.getOutputStream();
            workbook.write(out);
            out.flush();
            out.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 按多字段分组
     */
    public static String format(String value, Object... paras) {
        return MessageFormat.format(value, paras);
    }

    @Override
    public void exportAlarmRecord(HttpServletResponse response, AlarmElectrolyticCell alarmElectrolyticCell) {
        //数据统计
        List<AlarmDetailEc> alarmElectrolyticCellDTOList = getAlarmElectrolyticCellList(alarmElectrolyticCell);
        //避免在循环中查询字典，先根据字典类型查询出来，再去定位标签值
        List<SysDictData> sysDictDataList = DictUtil.getDictCache(Alarm.DICT_ALARM_TYPE);
        List<SysDictData> sysDictDataList1 = DictUtil.getDictCache(Alarm.DICT_ALARM_RANK);
        // 创建一个excel
        SXSSFWorkbook workbook = new SXSSFWorkbook(1000);
        // excel生成过程: excel-->sheet-->row-->cell
        SXSSFSheet sheet = workbook.createSheet("sheet");
        sheet.setColumnWidth(0, 30 * 256);
        sheet.setColumnWidth(1, 35 * 256);
        sheet.setColumnWidth(2, 35 * 256);
        sheet.setColumnWidth(3, 35 * 256);
        sheet.setColumnWidth(4, 20 * 256);
        sheet.setColumnWidth(5, 30 * 256);
        CellStyle row1CellStyle = CustomExportExcelUtil.createCellStyle(workbook, 15, false, "center", false, "黑体", false, false, false, false, (short) 0);
        CellStyle otherRowCellStyle = CustomExportExcelUtil.createCellStyle(workbook, 12, false, "center", false, "黑体", false, false, false, false, (short) 0);
        CellStyle dateCellStyle = CustomExportExcelUtil.createCellStyle(workbook, 12, false, "center", false, "黑体", true, false, false, false, (short) 0);
        CellStyle dateTimeCellStyle = CustomExportExcelUtil.createCellStyle(workbook, 12, false, "center", false, "黑体", false, true, false, false, (short) 0);

        // 创建第一行
        SXSSFRow row1 = sheet.createRow(0);
        SXSSFCell row1Cell1 = row1.createCell(0);
        CustomExportExcelUtil.setDataValue("槽面发热点明细表", row1Cell1);
        row1Cell1.setCellStyle(row1CellStyle);
        // 合并单元格,起始行, 终止行, 起始列, 终止列
        CellRangeAddress cra = new CellRangeAddress(0, 0, 0, 5);
        sheet.addMergedRegion(cra);
        CustomExportExcelUtil.cellRangeAddressSetBorder(cra, sheet);
        //创建第二行
        SXSSFRow row2 = sheet.createRow(1);
        SXSSFCell row2Cell1 = row2.createCell(0);
        CustomExportExcelUtil.setDataValue("报表生成日期", row2Cell1);
        row2Cell1.setCellStyle(otherRowCellStyle);

        SXSSFCell row2Cell2 = row2.createCell(1);
        CustomExportExcelUtil.setDataValue(DateUtils.getDate(), row2Cell2);
        row2Cell2.setCellStyle(dateCellStyle);

        //创建第三行
        SXSSFRow row3 = sheet.createRow(2);
        SXSSFCell row3Cell1 = row3.createCell(0);
        CustomExportExcelUtil.setDataValue("事件起始日期", row3Cell1);
        row3Cell1.setCellStyle(otherRowCellStyle);

        SXSSFCell row3Cell2 = row3.createCell(1);
        CustomExportExcelUtil.setDataValue(alarmElectrolyticCell.getStartDate(), row3Cell2);
        row3Cell2.setCellStyle(dateTimeCellStyle);

        SXSSFCell row3Cell3 = row3.createCell(2);
        CustomExportExcelUtil.setDataValue("事件结束日期", row3Cell3);
        row3Cell3.setCellStyle(otherRowCellStyle);

        SXSSFCell row3Cell4 = row3.createCell(3);
        CustomExportExcelUtil.setDataValue(alarmElectrolyticCell.getEndDate(), row3Cell4);
        row3Cell4.setCellStyle(dateTimeCellStyle);

        //第四行
        SXSSFRow row4 = sheet.createRow(3);
        SXSSFCell row4Cell1 = row4.createCell(0);
        CustomExportExcelUtil.setDataValue("事件设备", row4Cell1);
        row4Cell1.setCellStyle(otherRowCellStyle);

        SXSSFCell row4Cell2 = row4.createCell(1);
        CustomExportExcelUtil.setDataValue("事件开始时间", row4Cell2);
        row4Cell2.setCellStyle(otherRowCellStyle);

        SXSSFCell row4Cell3 = row4.createCell(2);
        CustomExportExcelUtil.setDataValue("事件结束时间", row4Cell3);
        row4Cell3.setCellStyle(otherRowCellStyle);

        SXSSFCell row4Cell4 = row4.createCell(3);
        CustomExportExcelUtil.setDataValue("事件类型", row4Cell4);
        row4Cell4.setCellStyle(otherRowCellStyle);

        SXSSFCell row4Cell5 = row4.createCell(4);
        CustomExportExcelUtil.setDataValue("事件级别", row4Cell5);
        row4Cell5.setCellStyle(otherRowCellStyle);

        SXSSFCell row4Cell6 = row4.createCell(5);
        CustomExportExcelUtil.setDataValue("事件源", row4Cell6);
        row4Cell6.setCellStyle(otherRowCellStyle);

        for (int i = 0; i < alarmElectrolyticCellDTOList.size(); i++) {
            AlarmDetailEc record = alarmElectrolyticCellDTOList.get(i);
            //表格行数据
            SXSSFRow dataRow = sheet.createRow(4 + i);
            SXSSFCell row5Cell0 = dataRow.createCell(0);
            CustomExportExcelUtil.setDataValue(record.getDeviceName(), row5Cell0);
            row5Cell0.setCellStyle(otherRowCellStyle);

            SXSSFCell row5Cell1 = dataRow.createCell(1);
            CustomExportExcelUtil.setDataValue(record.getAlarmBeginTime(), row5Cell1);
            row5Cell1.setCellStyle(dateTimeCellStyle);

            SXSSFCell row5Cell2 = dataRow.createCell(2);
            CustomExportExcelUtil.setDataValue(record.getAlarmEndTime(), row5Cell2);
            row5Cell2.setCellStyle(dateTimeCellStyle);

            SXSSFCell row5Cell3 = dataRow.createCell(3);
            CustomExportExcelUtil.setDataValue(DictUtil.getDictLabelByValue(sysDictDataList, record.getAlarmType()), row5Cell3);
            row5Cell3.setCellStyle(otherRowCellStyle);

            SXSSFCell row5Cell4 = dataRow.createCell(4);
            CustomExportExcelUtil.setDataValue(DictUtil.getDictLabelByValue(sysDictDataList1, record.getAlarmRank()), row5Cell4);
            row5Cell4.setCellStyle(otherRowCellStyle);

            SXSSFCell row5Cell5 = dataRow.createCell(5);
            CustomExportExcelUtil.setDataValue(record.getTargetName(), row5Cell5);
            row5Cell5.setCellStyle(otherRowCellStyle);
        }
        //下载到浏览器
        CustomExportExcelUtil.setBrowserParam(response, "xxx.xlsx");
        OutputStream out = null;
        try {
            out = response.getOutputStream();
            workbook.write(out);
            out.flush();
            out.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public List<AlarmElectrolyticCellDTO> selectAlarmListByEC() {
        List<AlarmElectrolyticCellDTO> dtos = alarmElectrolyticCellMapper.selectNewAlarmElectrolyticCellList();
        return dtos;
    }

    @Override
    public String selectAlarmRankByPt(Long sequenceId, Integer rowIndex, Integer grooveNumber, Integer observationPlace, Integer subdivideNumber) {
        String ranks = alarmElectrolyticCellMapper.selectAlarmRankByPt(sequenceId, rowIndex, grooveNumber, observationPlace, subdivideNumber);
        return ranks;
    }

    @Override
    public RepeatAlarmDto selectRepeatAlarmHandleByPt(AlarmElectrolyticCell alarmElectrolyticCell){
        return alarmElectrolyticCellMapper.selectRepeatAlarmHandleByPt(alarmElectrolyticCell);
    }

    /**
     * 导出统计表（第二种）
     * @param response
     * @param alarmElectrolyticCell
     */
    @Override
    public void exportAlarmStatistics2(HttpServletResponse response, AlarmElectrolyticCell alarmElectrolyticCell) {
        //序列数据
        R<List<EcStatisticsDTO>> result = electrolyticCellSequenceMapper.selectAllSequenceAndRows();
        if (R.FAIL == result.getCode()) {
            throw new BaseException(result.getMsg());
        }
        List<EcStatisticsDTO> alarmElectrolyticCellList = result.getData();
        //根据序列分组
        Map<Long, List<EcStatisticsDTO>> sequenceMap = alarmElectrolyticCellList.parallelStream()
                .collect(Collectors.groupingBy(EcStatisticsDTO::getSequenceId));
        //报警数据（根据报警时间区间查,温度>50 检测类型0、20
        String[] s = new String[2];
        s[0] = AlarmTypeEnums.ALARM_TYPE_ENUMS_1.getDescription();
        s[1] = AlarmTypeEnums.ALARM_TYPE_ENUMS_100.getKey();
        alarmElectrolyticCell.setAlarmTypes(s);
//        alarmElectrolyticCell.setTemperatureVariation(new BigDecimal(50));
        alarmElectrolyticCell.setSequenceId("-1");
        List<AlarmElectrolyticCellDTO> alarmElectrolyticCellDTOList = alarmElectrolyticCellMapper.selectAlarmElectrolyticCellList(alarmElectrolyticCell);
        //根据序列#跨分组
        Map<String, List<AlarmElectrolyticCellDTO>> alarmMap = alarmElectrolyticCellDTOList.parallelStream().collect(Collectors.groupingBy(data -> format("{0}#{1}", data.getSequenceId(), data.getRowIndex())));
        // 创建一个excel
        HSSFWorkbook workbook = new HSSFWorkbook();
        // excel生成过程: excel-->sheet-->row-->cell
        HSSFSheet sheet = workbook.createSheet("sheet");
        // 设置默认行高度为25个磅
        sheet.setDefaultRowHeight((short)(25 * 25));
        sheet.setColumnWidth(0, 18 * 256);
        sheet.setColumnWidth(1, 30 * 256);
        sheet.setColumnWidth(2, 30 * 256);
        sheet.setColumnWidth(3, 30 * 256);
        sheet.setColumnWidth(4, 45 * 256);
        sheet.setColumnWidth(5, 45 * 256);
        sheet.setColumnWidth(6, 45 * 256);
        sheet.setColumnWidth(7, 15 * 256);
        HSSFCellStyle row1CellStyle = CustomExportExcelUtil.createCellStyle(workbook, 15, false, "center", false, "黑体", false, false, false, false, (short) 0);
        HSSFCellStyle otherRowCellStyle = CustomExportExcelUtil.createCellStyle(workbook, 12, false, "center", false, "黑体", false, false, false, false, (short) 0);
        HSSFCellStyle dateCellStyle = CustomExportExcelUtil.createCellStyle(workbook, 12, false, "center", false, "黑体", true, false, false, false, (short) 0);
        HSSFCellStyle dateTimeCellStyle = CustomExportExcelUtil.createCellStyle(workbook, 12, false, "center", false, "黑体", false, true, false, false, (short) 0);
        // 创建第一行
        HSSFRow row1 = sheet.createRow(0);
        HSSFCell row1Cell1 = row1.createCell(0);
        CustomExportExcelUtil.setDataValue("云南驰宏锌锗股份有限公司会泽冶炼分公司电解槽槽面发热点统计台账", row1Cell1);
        row1Cell1.setCellStyle(row1CellStyle);
        // 合并单元格,起始行, 终止行, 起始列, 终止列
        CellRangeAddress cra = new CellRangeAddress(0, 0, 0, 7);
        sheet.addMergedRegion(cra);
        CustomExportExcelUtil.cellRangeAddressSetBorder(cra, sheet);
        //创建第二行
        HSSFRow row2 = sheet.createRow(1);
        HSSFCell row2Cell1 = row2.createCell(0);
        CustomExportExcelUtil.setDataValue("报表生成日期", row2Cell1);
        row2Cell1.setCellStyle(otherRowCellStyle);
        HSSFCell row2Cell2 = row2.createCell(1);
        CustomExportExcelUtil.setDataValue(DateUtils.getDate(), row2Cell2);
        row2Cell2.setCellStyle(dateCellStyle);
        //创建第三行
        HSSFRow row3 = sheet.createRow(2);
        HSSFCell row3Cell1 = row3.createCell(0);
        CustomExportExcelUtil.setDataValue("统计起始日期", row3Cell1);
        row3Cell1.setCellStyle(otherRowCellStyle);
        HSSFCell row3Cell2 = row3.createCell(1);
        CustomExportExcelUtil.setDataValue(alarmElectrolyticCell.getStartTime(), row3Cell2);
        row3Cell2.setCellStyle(dateTimeCellStyle);
        HSSFCell row3Cell3 = row3.createCell(2);
        CustomExportExcelUtil.setDataValue("统计结束日期", row3Cell3);
        row3Cell3.setCellStyle(otherRowCellStyle);
        HSSFCell row3Cell4 = row3.createCell(3);
        CustomExportExcelUtil.setDataValue(alarmElectrolyticCell.getEndTime(), row3Cell4);
        row3Cell4.setCellStyle(dateTimeCellStyle);
        HSSFCell row3Cell5 = row3.createCell(4);
        CustomExportExcelUtil.setDataValue("统计天数", row3Cell5);
        row3Cell5.setCellStyle(otherRowCellStyle);
        HSSFCell row3Cell6 = row3.createCell(5);
        CustomExportExcelUtil.setDataValue(DateUtil.betweenDay(alarmElectrolyticCell.getStartTime(), alarmElectrolyticCell.getEndTime(), true), row3Cell6);
        row3Cell6.setCellStyle(otherRowCellStyle);
        //数据计算层
        int currentRows = excelFirstLoop(sheet, otherRowCellStyle, sequenceMap, alarmMap);
        int currenRows2 = excelSecondLoop(sheet, otherRowCellStyle, sequenceMap, alarmMap, currentRows);
        excelThreeLoop(sheet, otherRowCellStyle, sequenceMap, alarmMap, currenRows2, alarmElectrolyticCellDTOList);
        //下载到浏览器
        CustomExportExcelUtil.setBrowserParam(response, "xxx.xlsx");
        OutputStream out = null;
        try {
            out = response.getOutputStream();
            workbook.write(out);
            out.flush();
            out.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }



    /**
     * 报表第一个行循环
     * 统计发热点数量和槽号
     * @param sheet
     * @param otherRowCellStyle
     * @param sequenceMap
     * @param alarmMap
     * @return
     */
    public Integer excelFirstLoop(HSSFSheet sheet, HSSFCellStyle otherRowCellStyle,
                                  Map<Long, List<EcStatisticsDTO>> sequenceMap, Map<String, List<AlarmElectrolyticCellDTO>> alarmMap) {
        //第四行
        HSSFRow row4 = sheet.createRow(3);
        HSSFCell row4Cell1 = row4.createCell(0);
        CustomExportExcelUtil.setDataValue("系列/跨", row4Cell1);
        row4Cell1.setCellStyle(otherRowCellStyle);
        HSSFCell row4Cell2 = row4.createCell(1);
        CustomExportExcelUtil.setDataValue("50℃~80℃", row4Cell2);
        row4Cell2.setCellStyle(otherRowCellStyle);
        HSSFCell row4Cell3 = row4.createCell(2);
        CustomExportExcelUtil.setDataValue("80℃~100℃", row4Cell3);
        row4Cell3.setCellStyle(otherRowCellStyle);
        HSSFCell row4Cell4 = row4.createCell(3);
        CustomExportExcelUtil.setDataValue(">100℃", row4Cell4);
        row4Cell4.setCellStyle(otherRowCellStyle);
        HSSFCell row4Cell5 = row4.createCell(4);
        CustomExportExcelUtil.setDataValue("5个≤发热点数量≤15个的槽号", row4Cell5);
        row4Cell5.setCellStyle(otherRowCellStyle);
        HSSFCell row4Cell6 = row4.createCell(5);
        CustomExportExcelUtil.setDataValue("16个≤发热点数量≤25个的槽号", row4Cell6);
        row4Cell6.setCellStyle(otherRowCellStyle);
        HSSFCell row4Cell7 = row4.createCell(6);
        CustomExportExcelUtil.setDataValue("26个≤发热点数量的槽号", row4Cell7);
        row4Cell7.setCellStyle(otherRowCellStyle);
        HSSFCell row4Cell8 = row4.createCell(7);
        CustomExportExcelUtil.setDataValue("备注", row4Cell8);
        row4Cell8.setCellStyle(otherRowCellStyle);
        //总计
        //50~80
        int totalNumber1 = 0;
        //80~100
        int totalNumber2 = 0;
        //>100
        int totalNumber3 = 0;
        StringBuilder totalGrooves1 = new StringBuilder();
        StringBuilder totalGrooves2 = new StringBuilder();
        StringBuilder totalGrooves3 = new StringBuilder();
        int rows = 4;
        for (Long sequenceId : sequenceMap.keySet()) {
            /**这是序列循环，包含跨*/
            List<EcStatisticsDTO> rowList = sequenceMap.get(sequenceId);
            String rowName = "";
            for (EcStatisticsDTO row : rowList) {
                /**这是跨循环,一跨=一行*/
                rowName = row.getSequenceName() + "-" + row.getRowIndex();
                //统计温度区间发生报警的槽数，同一区间不重复统计，发热点槽位不重复显示
                //50~80
                int number1 = 0;
                List<Integer> numberList = new ArrayList<>();
                //80~100
                int number2 = 0;
                //>100
                int number3 = 0;
                //发热点
                List<AlarmElectrolyticCellDTO> alarmList = alarmMap.containsKey(row.getSequenceId() + "#" + row.getRowIndex())
                        ? alarmMap.get(row.getSequenceId() + "#" + row.getRowIndex()) : new ArrayList<>();
                for (AlarmElectrolyticCellDTO alarm : alarmList) {
                    //报警数据，统计在温度区间内的槽数量(不用去重)，arrayList允许重复元素
                    if (alarm.getTemperatureVariation().floatValue() >= 50 && alarm.getTemperatureVariation().floatValue() <= 80) {
                        number1++;
                        numberList.add(alarm.getGrooveNumber());
                    } else if (alarm.getTemperatureVariation().floatValue() > 80 && alarm.getTemperatureVariation().floatValue() <= 100) {
                        number2++;
                        numberList.add(alarm.getGrooveNumber());
                    } else if (alarm.getTemperatureVariation().floatValue() > 100) {
                        number3++;
                        numberList.add(alarm.getGrooveNumber());
                    }
                }
                //分别计算槽号报警次数区间,先转换为槽号和次数的map集合
                Map<Integer, Long> countMap = numberList.parallelStream()
                        .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
                //5-15
                String grooves1 = countMap.entrySet().parallelStream()
                        .filter(entry -> entry.getValue() >= 5 && entry.getValue() <= 15)
                        .map(Map.Entry::getKey)
                        .map(Objects::toString)
                        .collect(Collectors.joining(","));
                //16-25
                String grooves2 = countMap.entrySet().parallelStream()
                        .filter(entry -> entry.getValue() >= 16 && entry.getValue() <= 25)
                        .map(Map.Entry::getKey)
                        .map(Objects::toString)
                        .collect(Collectors.joining(","));
                //26
                String grooves3 = countMap.entrySet().parallelStream()
                        .filter(entry -> entry.getValue() >= 26)
                        .map(Map.Entry::getKey)
                        .map(Objects::toString)
                        .collect(Collectors.joining(","));
                //合计累加
                totalNumber1 = totalNumber1 + number1;
                totalNumber2 = totalNumber2 + number2;
                totalNumber3 = totalNumber3 + number3;
                totalGrooves1.append(StringUtils.isNotBlank(grooves1) ? rowName + "(" + grooves1 + ")," : "");
                totalGrooves2.append(StringUtils.isNotBlank(grooves2) ? rowName + "(" + grooves2 + ")," : "");
                totalGrooves3.append(StringUtils.isNotBlank(grooves3) ? rowName + "(" + grooves3 + ")," : "");
                //表格行数据
                HSSFRow dataRow = sheet.createRow(rows);
                HSSFCell row5Cell0 = dataRow.createCell(0);
                CustomExportExcelUtil.setDataValue(rowName, row5Cell0);
                row5Cell0.setCellStyle(otherRowCellStyle);
                HSSFCell row5Cell1 = dataRow.createCell(1);
                CustomExportExcelUtil.setDataValue(number1, row5Cell1);
                row5Cell1.setCellStyle(otherRowCellStyle);
                HSSFCell row5Cell2 = dataRow.createCell(2);
                CustomExportExcelUtil.setDataValue(number2, row5Cell2);
                row5Cell2.setCellStyle(otherRowCellStyle);
                HSSFCell row5Cell3 = dataRow.createCell(3);
                CustomExportExcelUtil.setDataValue(number3, row5Cell3);
                row5Cell3.setCellStyle(otherRowCellStyle);
                HSSFCell row5Cell4 = dataRow.createCell(4);
                CustomExportExcelUtil.setDataValue(grooves1, row5Cell4);
                row5Cell4.setCellStyle(otherRowCellStyle);
                HSSFCell row5Cell5 = dataRow.createCell(5);
                CustomExportExcelUtil.setDataValue(grooves2, row5Cell5);
                row5Cell5.setCellStyle(otherRowCellStyle);
                HSSFCell row5Cell6 = dataRow.createCell(6);
                CustomExportExcelUtil.setDataValue(grooves3, row5Cell6);
                row5Cell6.setCellStyle(otherRowCellStyle);
                //更新行数
                rows = rows + 1;
            }
        }
        //总计
        HSSFRow totalRow = sheet.createRow(rows);
        HSSFCell totalRowCell1 = totalRow.createCell(0);
        CustomExportExcelUtil.setDataValue("合计", totalRowCell1);
        totalRowCell1.setCellStyle(otherRowCellStyle);
        HSSFCell totalRowCell2 = totalRow.createCell(1);
        CustomExportExcelUtil.setDataValue(totalNumber1, totalRowCell2);
        totalRowCell2.setCellStyle(otherRowCellStyle);
        HSSFCell totalRowCell3 = totalRow.createCell(2);
        CustomExportExcelUtil.setDataValue(totalNumber2, totalRowCell3);
        totalRowCell3.setCellStyle(otherRowCellStyle);
        HSSFCell totalRowCell4 = totalRow.createCell(3);
        CustomExportExcelUtil.setDataValue(totalNumber3, totalRowCell4);
        totalRowCell4.setCellStyle(otherRowCellStyle);
        HSSFCell totalRowCell5 = totalRow.createCell(4);
        CustomExportExcelUtil.setDataValue(StringUtils.isNotBlank(totalGrooves1) ? totalGrooves1.substring(0, totalGrooves1.length() - 1) : "", totalRowCell5);
        totalRowCell5.setCellStyle(otherRowCellStyle);
        HSSFCell totalRowCell6 = totalRow.createCell(5);
        CustomExportExcelUtil.setDataValue(StringUtils.isNotBlank(totalGrooves2) ? totalGrooves2.substring(0, totalGrooves2.length() - 1) : "", totalRowCell6);
        totalRowCell6.setCellStyle(otherRowCellStyle);
        HSSFCell totalRowCell7 = totalRow.createCell(6);
        CustomExportExcelUtil.setDataValue(StringUtils.isNotBlank(totalGrooves3) ? totalGrooves3.substring(0, totalGrooves3.length() - 1) : "", totalRowCell7);
        totalRowCell7.setCellStyle(otherRowCellStyle);
        return rows;
    }

    /**
     * 发热点统计报表第二次循环
     * 统计发热时长和平均处理时长
     * @param sheet
     * @param otherRowCellStyle
     * @param sequenceMap
     * @param alarmMap
     * @param currentRows
     * @return
     */
    public Integer excelSecondLoop(HSSFSheet sheet, HSSFCellStyle otherRowCellStyle,
                                   Map<Long, List<EcStatisticsDTO>> sequenceMap, Map<String, List<AlarmElectrolyticCellDTO>> alarmMap,int currentRows) {
        int rows = currentRows + 1;
        HSSFRow row4 = sheet.createRow(rows);
        HSSFCell row4Cell1 = row4.createCell(0);
        CustomExportExcelUtil.setDataValue("系列/跨", row4Cell1);
        row4Cell1.setCellStyle(otherRowCellStyle);
        HSSFCell row4Cell2 = row4.createCell(1);
        CustomExportExcelUtil.setDataValue("发热点平均发热时长（小时）", row4Cell2);
        row4Cell2.setCellStyle(otherRowCellStyle);
        HSSFCell row4Cell3 = row4.createCell(2);
        CustomExportExcelUtil.setDataValue("处理后二次发热数量", row4Cell3);
        row4Cell3.setCellStyle(otherRowCellStyle);
        HSSFCell row4Cell4 = row4.createCell(3);
        CustomExportExcelUtil.setDataValue("处理后三次发热数量", row4Cell4);
        row4Cell4.setCellStyle(otherRowCellStyle);
        HSSFCell row4Cell5 = row4.createCell(4);
        CustomExportExcelUtil.setDataValue("第一次处理发热点时间≤3小时的数量", row4Cell5);
        row4Cell5.setCellStyle(otherRowCellStyle);
        HSSFCell row4Cell6 = row4.createCell(5);
        CustomExportExcelUtil.setDataValue("3小时＜第一次处理发热点时间≤8小时的数量", row4Cell6);
        row4Cell6.setCellStyle(otherRowCellStyle);
        HSSFCell row4Cell7 = row4.createCell(6);
        CustomExportExcelUtil.setDataValue("8小时＜第一次处理发热点时间的数量", row4Cell7);
        row4Cell7.setCellStyle(otherRowCellStyle);
        //总计平均发热时长
        long totalAvgHotHour = 0;
        //总计二次发热数量
        long totalCount1 = 0;
        //总计三次发热数量
        long totalCount2 = 0;
        //总计处理时间的数量
        long totalHandleCount1 = 0;
        long totalHandleCount2 = 0;
        long totalHandleCount3 = 0;
        for (Long sequenceId : sequenceMap.keySet()) {
            /**这是序列循环，包含跨*/
            List<EcStatisticsDTO> rowList = sequenceMap.get(sequenceId);
            String rowName = "";
            for (EcStatisticsDTO row : rowList) {
                //更新行数
                rows = rows + 1;
                /**这是跨循环,一跨=一行*/
                rowName = row.getSequenceName() + "-" + row.getRowIndex();
                //发热点
                List<AlarmElectrolyticCellDTO> alarmList = alarmMap.containsKey(row.getSequenceId() + "#" + row.getRowIndex())
                        ? alarmMap.get(row.getSequenceId() + "#" + row.getRowIndex()) : new ArrayList<>();
                //计算平均发热时长和总计平均发热时长（以小时为单位）
                long sumDifference = 0;
                for (AlarmElectrolyticCellDTO alarm : alarmList) {
                    //未结束的报警按当前时间计算，计算日期之间的差值
                    if (alarm.getEndDate() == null) {
                        alarm.setAlarmEndTime(DateUtils.getNowDate());
                    }
                    long hourDifference = ChronoUnit.HOURS.between(alarm.getAlarmBeginTime().toInstant(), alarm.getAlarmEndTime().toInstant());
                    sumDifference = sumDifference + hourDifference;
                }
                long avgHotHour = alarmList.size() != 0 ? sumDifference / alarmList.size() : 0;
                totalAvgHotHour = totalAvgHotHour + avgHotHour;
                //处理后二次发热数量(重复次数=1）,三次发热数量（就是重复了2次） 和总计
                long count1 = alarmList.parallelStream().filter(dto -> dto.getRepeatNumber() == 1).count();
                long count2 = alarmList.parallelStream().filter(dto -> dto.getRepeatNumber() == 2).count();
                totalCount1 = totalCount1 + count1;
                totalCount2 = totalCount2 + count2;
                //计算平均处理时长（根据3个时间区间来统计数量）和计算总计
                long sumHandleCount1 = 0;
                long sumHandleCount2 = 0;
                long sumHandleCount3 = 0;
                //计算处理发热点的时间（以小时为单位，时间区间为报警开始时间-处理时间
                List<AlarmElectrolyticCellDTO> handleAlarmList = alarmList.parallelStream()
                        .filter(dto -> StringUtils.equals("1", dto.getHandleStatus())).collect(Collectors.toList());
                for (AlarmElectrolyticCellDTO alarm : handleAlarmList) {
                    long hourDifference = ChronoUnit.HOURS.between(alarm.getAlarmBeginTime().toInstant(), alarm.getHandleTime().toInstant());
                    if (hourDifference <= 3) {
                        sumHandleCount1++;
                    } else if (hourDifference >= 4 && hourDifference <= 8) {
                        sumHandleCount2++;
                    } else if (hourDifference > 8) {
                        sumHandleCount3++;
                    }
                }
                totalHandleCount1 = totalHandleCount1 + sumHandleCount1;
                totalHandleCount2 = totalHandleCount2 + sumHandleCount2;
                totalHandleCount3 = totalHandleCount3 + sumHandleCount3;
                //表格行数据
                HSSFRow dataRow = sheet.createRow(rows);
                HSSFCell row5Cell0 = dataRow.createCell(0);
                CustomExportExcelUtil.setDataValue(rowName, row5Cell0);
                row5Cell0.setCellStyle(otherRowCellStyle);
                HSSFCell row5Cell1 = dataRow.createCell(1);
                CustomExportExcelUtil.setDataValue(avgHotHour, row5Cell1);
                row5Cell1.setCellStyle(otherRowCellStyle);
                HSSFCell row5Cell2 = dataRow.createCell(2);
                CustomExportExcelUtil.setDataValue(count1, row5Cell2);
                row5Cell2.setCellStyle(otherRowCellStyle);
                HSSFCell row5Cell3 = dataRow.createCell(3);
                CustomExportExcelUtil.setDataValue(count2, row5Cell3);
                row5Cell3.setCellStyle(otherRowCellStyle);
                HSSFCell row5Cell4 = dataRow.createCell(4);
                CustomExportExcelUtil.setDataValue(sumHandleCount1, row5Cell4);
                row5Cell4.setCellStyle(otherRowCellStyle);
                HSSFCell row5Cell5 = dataRow.createCell(5);
                CustomExportExcelUtil.setDataValue(sumHandleCount2, row5Cell5);
                row5Cell5.setCellStyle(otherRowCellStyle);
                HSSFCell row5Cell6 = dataRow.createCell(6);
                CustomExportExcelUtil.setDataValue(sumHandleCount3, row5Cell6);
                row5Cell6.setCellStyle(otherRowCellStyle);
            }
        }
        //总计 更新行数
        rows = rows + 1;
        HSSFRow totalRow = sheet.createRow(rows);
        HSSFCell totalRowCell1 = totalRow.createCell(0);
        CustomExportExcelUtil.setDataValue("合计", totalRowCell1);
        totalRowCell1.setCellStyle(otherRowCellStyle);
        HSSFCell totalRowCell2 = totalRow.createCell(1);
        CustomExportExcelUtil.setDataValue(totalAvgHotHour, totalRowCell2);
        totalRowCell2.setCellStyle(otherRowCellStyle);
        HSSFCell totalRowCell3 = totalRow.createCell(2);
        CustomExportExcelUtil.setDataValue(totalCount1, totalRowCell3);
        totalRowCell3.setCellStyle(otherRowCellStyle);
        HSSFCell totalRowCell4 = totalRow.createCell(3);
        CustomExportExcelUtil.setDataValue(totalCount2, totalRowCell4);
        totalRowCell4.setCellStyle(otherRowCellStyle);
        HSSFCell totalRowCell5 = totalRow.createCell(4);
        CustomExportExcelUtil.setDataValue(totalHandleCount1, totalRowCell5);
        totalRowCell5.setCellStyle(otherRowCellStyle);
        HSSFCell totalRowCell6 = totalRow.createCell(5);
        CustomExportExcelUtil.setDataValue(totalHandleCount2, totalRowCell6);
        totalRowCell6.setCellStyle(otherRowCellStyle);
        HSSFCell totalRowCell7 = totalRow.createCell(6);
        CustomExportExcelUtil.setDataValue(totalHandleCount3, totalRowCell7);
        totalRowCell7.setCellStyle(otherRowCellStyle);
        return rows;
    }

    /**
     * 发热点统计报表第三层循环
     * 统计各巡检仪处理数量
     * @param sheet
     * @param otherRowCellStyle
     * @param sequenceMap
     * @param alarmMap
     * @param currentRows
     * @param alarmList
     * @return
     */
    public Integer excelThreeLoop(HSSFSheet sheet, HSSFCellStyle otherRowCellStyle,
                                  Map<Long, List<EcStatisticsDTO>> sequenceMap, Map<String, List<AlarmElectrolyticCellDTO>> alarmMap,
                                  int currentRows, List<AlarmElectrolyticCellDTO> alarmList) {
        int rows = currentRows + 1;
        HSSFRow row4 = sheet.createRow(rows);
        HSSFCell row4Cell1 = row4.createCell(0);
        CustomExportExcelUtil.setDataValue("系列/跨", row4Cell1);
        row4Cell1.setCellStyle(otherRowCellStyle);
        //巡检仪根据实际来显示
        List<String> instrumentNames = alarmList.parallelStream()
                .filter(dto -> StringUtils.equals("1", dto.getHandleStatus()) && StringUtils.isNotBlank(dto.getApparatusId()))
                .map(AlarmElectrolyticCellDTO::getApparatusId).distinct().collect(Collectors.toList());
        for (int i = 0; i < instrumentNames.size(); i++) {
            String instrumentName = instrumentNames.get(i);
            HSSFCell row4Cell2 = row4.createCell(i + 1);
            CustomExportExcelUtil.setDataValue(instrumentName + "处理发热点数量", row4Cell2);
            row4Cell2.setCellStyle(otherRowCellStyle);
        }
        Map<String, Long> sumMap = new HashMap<>();
        for (Long sequenceId : sequenceMap.keySet()) {
            /**这是序列循环，包含跨*/
            List<EcStatisticsDTO> rowList = sequenceMap.get(sequenceId);
            String rowName = "";
            for (EcStatisticsDTO row : rowList) {
                /**这是跨循环,一跨=一行*/
                rowName = row.getSequenceName() + "-" + row.getRowIndex();
                //发热点
                List<AlarmElectrolyticCellDTO> alarmList1 = alarmMap.containsKey(row.getSequenceId() + "#" + row.getRowIndex())
                        ? alarmMap.get(row.getSequenceId() + "#" + row.getRowIndex()) : new ArrayList<>();
                //统计出各巡检仪处理的数量
                Map<String, Long> countMapByApparatusId = alarmList1.parallelStream()
                        .filter(dto -> StringUtils.equals("1", dto.getHandleStatus()) && StringUtils.isNotBlank(dto.getApparatusId()))
                        .collect(Collectors.groupingBy(AlarmElectrolyticCellDTO::getApparatusId, Collectors.counting()));
                //表格行数据
                rows = rows + 1;
                HSSFRow dataRow = sheet.createRow(rows);
                HSSFCell row5Cell0 = dataRow.createCell(0);
                CustomExportExcelUtil.setDataValue(rowName, row5Cell0);
                row5Cell0.setCellStyle(otherRowCellStyle);
                for (int i = 0; i < instrumentNames.size(); i++) {
                    //从这个循环开始创建列，以巡检仪数量为准
                    String instrumentName = instrumentNames.get(i);
                    long count = countMapByApparatusId.containsKey(instrumentName) ? countMapByApparatusId.get(instrumentName) : 0;
                    HSSFCell row5Cell = dataRow.createCell(i + 1);
                    CustomExportExcelUtil.setDataValue(count, row5Cell);
                    row5Cell.setCellStyle(otherRowCellStyle);
                    //计算总和并存入map中，key为巡检仪标识
                    if (sumMap.containsKey(instrumentName)) {
                        long c = sumMap.get(instrumentName);
                        c = c + count;
                        sumMap.put(instrumentName, c);
                    } else {
                        sumMap.put(instrumentName, count);
                    }
                }
            }
        }
        //总计 更新行数
        rows = rows + 1;
        HSSFRow totalRow = sheet.createRow(rows);
        HSSFCell totalRowCell1 = totalRow.createCell(0);
        CustomExportExcelUtil.setDataValue("合计", totalRowCell1);
        totalRowCell1.setCellStyle(otherRowCellStyle);
        for (int i = 0; i < instrumentNames.size(); i++) {
            HSSFCell totalRowCell2 = totalRow.createCell(i + 1);
            CustomExportExcelUtil.setDataValue(sumMap.get(instrumentNames.get(i)), totalRowCell2);
            totalRowCell2.setCellStyle(otherRowCellStyle);
        }
        return rows;
    }


    public List<AlarmDetailEc> getAlarmElectrolyticCellList(AlarmElectrolyticCell alarmElectrolyticCell) {

        Long currentTenantId = SecurityUtils.getCurrentTenantId();
        alarmElectrolyticCell.setTenantId(currentTenantId);
        List<AlarmDetailEc> alarmElectrolyticCellDTOList = alarmElectrolyticCellMapper.selectAlarmAlarmDetailEcList(alarmElectrolyticCell);

        List<String> deviceIdList = alarmElectrolyticCellDTOList.parallelStream().map(AlarmDetailEc::getDeviceSn).distinct().collect(Collectors.toList());
        List<String> collect = alarmElectrolyticCellDTOList.parallelStream().map(AlarmDetailEc::getIrmsSn).distinct().collect(Collectors.toList());
        Map<String,Map<String, List<ElectrolyticPlaceAlarmDTO>>>  map= new HashMap<>();
        for(String irms:collect){
            Map<String, List<ElectrolyticPlaceAlarmDTO>> list=redisService.getCacheObject(Constants.EC_ALARM_PLACE+irms);
            if (list!=null){
                map.put(irms,list);
            }
        }

        Map<String, String> deviceMap = new HashMap<>();
        //报警设备名称
        for (String sn : deviceIdList) {
            DeviceKeyInfoDTO device = redisService.getCacheObject(Constants.DEVICE_SN_KEY + sn);
            deviceMap.put(sn, device != null ? device.getDeviceName() : "");
        }
        //获取报警的导则
        for (AlarmDetailEc dto : alarmElectrolyticCellDTOList) {
            //设备名称
            dto.setDeviceName(deviceMap.get(dto.getDeviceSn()));
            if(!map.isEmpty()) {
                Map<String, List<ElectrolyticPlaceAlarmDTO>> stringListMap = map.get(dto.getIrmsSn());

                if (stringListMap==null || stringListMap.get(dto.getObservationPlace()) == null){

                    continue;
                }
                ElectrolyticPlaceAlarmDTO electrolyticPlaceAlarmDTO = stringListMap.get(dto.getObservationPlace()).get(0);

                dto.setGeneralAlarm(electrolyticPlaceAlarmDTO.getGeneralAlarm());
                dto.setEmergencyAlarm(electrolyticPlaceAlarmDTO.getEmergencyAlarm());
                dto.setCriticalAlarm(electrolyticPlaceAlarmDTO.getCriticalAlarm());
            }
        }

        return alarmElectrolyticCellDTOList;
    }

}
