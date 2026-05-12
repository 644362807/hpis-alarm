package com.hpis.alarm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hpis.alarm.domain.AlarmHandle;
import com.hpis.alarm.domain.AlarmPartialDischarge;
import com.hpis.alarm.dto.AlarmPartialDischargeCount;
import com.hpis.alarm.dto.AlarmPartialDischargeDto;
import com.hpis.alarm.dto.AlarmQueryParameter;

import java.util.List;
import java.util.Map;

/**
 * 局放报警详情Service接口
 * 
 * @author ruoyi
 * @date 2024-03-13
 */
public interface IAlarmPartialDischargeService  extends IService<AlarmPartialDischargeDto>
{
    /**
     * 查询局放报警详情列表（分页）
     *
     * @param alarmPartialDischarge 局放报警详情
     * @return 局放报警详情集合
     */
    Page<AlarmPartialDischargeDto> selectAlarmPartialDischargePage(AlarmPartialDischarge alarmPartialDischarge);

    /**
     * 查询局放报警详情
     * 
     * @param alarmId 局放报警详情ID
     * @return 局放报警详情
     */
    public AlarmPartialDischarge selectAlarmPartialDischargeById(Long alarmId);

    /**
     * 查询局放报警详情列表
     * 
     * @param alarmPartialDischarge 局放报警详情
     * @return 局放报警详情集合
     */
    public List<AlarmPartialDischargeDto> selectAlarmPartialDischargeList(AlarmPartialDischarge alarmPartialDischarge);

    /**
     * 报警今日 总数统计
     * @param alarmPartialDischarge
     * @return
     */
    public AlarmPartialDischargeCount partialDischargeCount(AlarmPartialDischarge alarmPartialDischarge);


    /**
     * 新增局放报警详情
     * 
     * @param alarmPartialDischarge 局放报警详情
     * @return 结果
     */
    public int insertAlarmPartialDischarge(AlarmPartialDischarge alarmPartialDischarge);

    /**
     * 修改局放报警详情
     * 
     * @param alarmPartialDischarge 局放报警详情
     * @return 结果
     */
    public int updateAlarmPartialDischarge(AlarmPartialDischarge alarmPartialDischarge);

    /**
     * 批量删除局放报警详情
     * 
     * @param alarmIds 需要删除的局放报警详情ID
     * @return 结果
     */
    public int deleteAlarmPartialDischargeByIds(Long[] alarmIds);

    /**
     * 删除局放报警详情信息
     * 
     * @param alarmId 局放报警详情ID
     * @return 结果
     */
    public int deleteAlarmPartialDischargeById(Long alarmId);

    /**
     * 在线局放的报警类型
     * @param alarmQueryParameter
     * @return
     */
    Map<String,Long> detectionModeCount(AlarmQueryParameter alarmQueryParameter);

    /**
     * 七天内报警的放电类型统计占比
     * @param alarmQueryParameter
     * @return
     */
    List<Map<String, Object>> alarmDPType (AlarmQueryParameter alarmQueryParameter);
    /**
     * 在线局放 通道报警
     * @param alarmQueryParameter
     * @return
     */
      List<Map<String,Integer>> channelModeCount(AlarmQueryParameter alarmQueryParameter);


    /**
     * 在线局放设备报警
     * @param alarmQueryParameter
     * @return
     */
     List<Map<String,String>> deviceAlarm(AlarmQueryParameter alarmQueryParameter);


    /**
     * 客户下所有设备每天的报警总数
     * @param alarmQueryParameter
     * @return
     */
    List<Map<String, Object>> deviceAlarmOfDayByCustomer(AlarmQueryParameter alarmQueryParameter);
}