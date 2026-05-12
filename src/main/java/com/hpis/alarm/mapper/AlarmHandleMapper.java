package com.hpis.alarm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hpis.alarm.domain.AlarmConfigure;
import com.hpis.alarm.domain.AlarmHandle;

import java.util.List;

/**
 * 【请填写功能名称】Mapper接口
 * 
 * @author ruoyi
 * @date 2023-03-24
 */
public interface AlarmHandleMapper  extends BaseMapper<AlarmHandle>
{
    /**
     * 查询【请填写功能名称】
     * 
     * @param alarmHandleId 【请填写功能名称】ID
     * @return 【请填写功能名称】
     */
    public AlarmHandle selectAlarmHandleById(Long alarmHandleId);

    /**
     * 查询【请填写功能名称】列表
     *
     * @param alarmHandle 【请填写功能名称】
     * @return 【请填写功能名称】集合
     */
    public List<AlarmHandle> selectAlarmHandleList(AlarmHandle alarmHandle);

    /**
     * 新增【请填写功能名称】
     * 
     * @param alarmHandle 【请填写功能名称】
     * @return 结果
     */
    public int insertAlarmHandle(AlarmHandle alarmHandle);

    /**
     * 批量新增
     * @param alarmHandles
     * @return
     */
    int insertAlarmHandelList(List<AlarmHandle> alarmHandles);

    /**
     * 保存报警信息
     * @param alarmHandle
     * @return
     */
    public int updateAlarmHandle(AlarmHandle alarmHandle);

    /**
     * 删除【请填写功能名称】
     * 
     * @param alarmHandleId 【请填写功能名称】ID
     * @return 结果
     */
    public int deleteAlarmHandleById(Long alarmHandleId);

    /**
     * 批量删除【请填写功能名称】
     * 
     * @param alarmHandleIds 需要删除的数据ID
     * @return 结果
     */
    public int deleteAlarmHandleByIds(Long[] alarmHandleIds);

    int deleteAlarmHandelByAlarmIdReale(Long alarmId);
}