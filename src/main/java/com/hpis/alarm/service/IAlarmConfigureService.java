package com.hpis.alarm.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hpis.alarm.domain.AlarmColor;
import com.hpis.alarm.domain.AlarmConfigure;

import java.text.ParseException;
import java.util.List;

/**
 * 报警配置Service接口
 * 
 * @author 向文来
 * @date 2023-03-28
 */
public interface IAlarmConfigureService  extends IService<AlarmConfigure>
{
    /**
     * 查询报警配置
     * 
     * @param alarmConfigureId 报警配置ID
     * @return 报警配置
     */
    public AlarmConfigure selectAlarmConfigureById(Long alarmConfigureId);


    /**
     * 查询报警配置列表（分页）
     *
     * @param alarmConfigure 报警配置
     * @return 报警配置集合
     */
     Page<AlarmConfigure> selectAlarmConfigurePage(AlarmConfigure alarmConfigure);

    /**
     * 查询报警配置列表
     * 
     * @param alarmConfigure 报警配置
     * @return 报警配置集合
     */
    public List<AlarmConfigure> selectAlarmConfigureList(AlarmConfigure alarmConfigure);

    /**
     * 新增报警配置
     * 
     * @param alarmConfigure 报警配置
     * @return 结果
     */
    public String insertAlarmConfigure(AlarmConfigure alarmConfigure) throws ParseException;

    /**
     * 修改报警配置
     * 
     * @param alarmConfigure 报警配置
     * @return 结果
     */
    public String updateAlarmConfigure(AlarmConfigure alarmConfigure) throws ParseException;

    /**
     * 批量删除报警配置
     * 
     * @param alarmConfigureIds 需要删除的报警配置ID
     * @return 结果
     */
    public int deleteAlarmConfigureByIds(Long[] alarmConfigureIds);

    /**
     * 删除报警配置信息
     * 
     * @param alarmConfigureId 报警配置ID
     * @return 结果
     */
    public int deleteAlarmConfigureById(Long alarmConfigureId);

    /**
     * 客户的报警配置
     * @param alarmConfigure
     * @return
     */
    public List<AlarmConfigure> selectDeviceConfigureByCustomer(AlarmConfigure alarmConfigure);
}