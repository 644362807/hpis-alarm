package com.hpis.alarm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hpis.alarm.domain.AlarmConfigure;
import com.hpis.alarm.domain.AlarmHandle;
import com.hpis.alarm.dto.HandleParamDto;

import java.util.List;

/**
 * 【请填写功能名称】Service接口
 * 
 * @author ruoyi
 * @date 2023-03-24
 */
public interface IAlarmHandleService extends IService<AlarmHandle>
{

    /**
     * 查询【请填写功能名称】
     * 
     * @param alarmHandleId 【请填写功能名称】ID
     * @return 【请填写功能名称】
     */
    public AlarmHandle selectAlarmHandleById(Long alarmHandleId);

    /**
     * 查询报警处理列表（分页）
     *
     * @param alarmHandle 【请填写功能名称】
     * @return 【请填写功能名称】集合
     */
    Page<AlarmHandle> selectAlarmHandlePage(AlarmHandle alarmHandle);

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
     * @param alarmHandle
     * @return
     */
    int insertAlarmHandleList(List<AlarmHandle> alarmHandle);

    /**
     * 保存报警信息
     * @param handleParamDto
     * @return
     */
    public int saveAlarmHandle(HandleParamDto handleParamDto);


    public int saveAlarmAllHandle(HandleParamDto handleParamDto);

    /**
     * 报警确认
     * @param alarmHandle
     * @return
     */
    public int updateAlarmHandle(AlarmHandle alarmHandle);

    /**
     * 批量删除【请填写功能名称】
     * 
     * @param alarmHandleIds 需要删除的【请填写功能名称】ID
     * @return 结果
     */
    public int deleteAlarmHandleByIds(Long[] alarmHandleIds);

    /**
     * 删除【请填写功能名称】信息
     * 
     * @param alarmHandleId 【请填写功能名称】ID
     * @return 结果
     */
    public int deleteAlarmHandleById(Long alarmHandleId);
}