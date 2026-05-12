package com.hpis.alarm.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.domain.AlarmPartialDischarge;
import com.hpis.alarm.dto.AlarmPartialDischargeDto;
import com.hpis.alarm.dto.AlarmQueryParameter;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 局放报警详情Mapper接口
 * 
 * @author ruoyi
 * @date 2024-03-13
 */
public interface AlarmPartialDischargeMapper  extends BaseMapper<AlarmPartialDischargeDto>
{


    /**
     * 报警主表分页
     * @param rowBounds
     * @param wrapper
     * @return
     */

    Page<AlarmPartialDischargeDto> selectPDListPage(Page rowBounds, @Param("ew") Wrapper<AlarmPartialDischarge> wrapper);

    /**
     * 查询局放报警详情
     * 
     * @param alarmId 局放报警详情ID
     * @return 局放报警详情
     */
    public AlarmPartialDischarge selectAlarmPartialDischargeById(Long alarmId);

    /**
     * 查询局放报警详情列表（单表）
     * 
     * @param alarmPartialDischarge 局放报警详情
     * @return 局放报警详情集合
     */
    public List<AlarmPartialDischargeDto> selectAlarmPartialDischargeList(AlarmPartialDischarge alarmPartialDischarge);

    /**
     * 查询局放报警详情列表（关联主表）
     * @param alarmPartialDischarge
     * @return
     */
    public List<AlarmPartialDischargeDto> selectAlarmPartialDischargeAll(AlarmPartialDischarge alarmPartialDischarge);


    /**
     * 局放信息以报警主表为主 包含通道 信息和设备信息
     * @param
     * @return
     */
    public List<AlarmPartialDischargeDto> channelOrDeviceModeCount(AlarmQueryParameter alarmQueryParameter);


    /**
     * 局放报警趋势图
     * @param
     * @return
     */
    public List<AlarmPartialDischargeDto> deviceAlarmOfDayByCustomer(AlarmQueryParameter alarmQueryParameter);

    /**
     * 新增局放报警详情
     * 
     * @param alarmPartialDischarge 局放报警详情
     * @return 结果
     */
    @InterceptorIgnore(tenantLine = "true")
    public int insertAlarmPartialDischarge(AlarmPartialDischarge alarmPartialDischarge);

    /**
     * 修改局放报警详情
     * 
     * @param alarmPartialDischarge 局放报警详情
     * @return 结果
     */
    public int updateAlarmPartialDischarge(AlarmPartialDischarge alarmPartialDischarge);

    /**
     * 删除局放报警详情
     * 
     * @param alarmId 局放报警详情ID
     * @return 结果
     */
    public int deleteAlarmPartialDischargeById(Long alarmId);

    /**
     * 批量删除局放报警详情
     * 
     * @param alarmIds 需要删除的数据ID
     * @return 结果
     */
    public int deleteAlarmPartialDischargeByIds(Long[] alarmIds);
}