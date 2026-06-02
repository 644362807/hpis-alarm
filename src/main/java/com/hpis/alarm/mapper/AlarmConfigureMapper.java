package com.hpis.alarm.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hpis.alarm.domain.AlarmColor;
import com.hpis.alarm.domain.AlarmConfigure;
import com.hpis.alarm.domain.AlarmConfigureTime;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 报警配置Mapper接口
 * 
 * @author 向文来
 * @date 2023-03-28
 */
public interface AlarmConfigureMapper  extends BaseMapper<AlarmConfigure>
{
    /**
     * 查询报警配置
     * 
     * @param alarmConfigureId 报警配置ID
     * @return 报警配置
     */
    public AlarmConfigure selectAlarmConfigureById(Long alarmConfigureId);

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
    public int insertAlarmConfigure(AlarmConfigure alarmConfigure);

    /**
     * 新增自定义时间段
     * @param alarmConfigureTime
     * @return
     */
    public int insertConfigTime(AlarmConfigureTime alarmConfigureTime);

    /** 批量新增自定义时间段，调用方按统一 SQL 上限分块。 */
    public int insertConfigTimeBatch(@Param("items") List<AlarmConfigureTime> items);

    /**
     * 修改报警配置
     * 
     * @param alarmConfigure 报警配置
     * @return 结果
     */
    public int updateAlarmConfigure(AlarmConfigure alarmConfigure);

    public int deleteConfigTime(Long alarmConfigureId);
    /**
     * 删除报警配置
     * 
     * @param alarmConfigureId 报警配置ID
     * @return 结果
     */
    public int deleteAlarmConfigureById(Long alarmConfigureId);

    /**
     * 批量删除报警配置
     * 
     * @param alarmConfigureIds 需要删除的数据ID
     * @return 结果
     */
    public int deleteAlarmConfigureByIds(Long[] alarmConfigureIds);

    /**
     * 插入报警配置与设备关联表
     * @param devices
     * @param alarmConfigureId
     * @return
     */
    public int batchDeviceConfigure(@Param("devices") String[] devices, @Param("alarmConfigureId") Long alarmConfigureId);

    /**
     * 客户的报警配置
     * @param alarmConfigure
     * @return
     */

    public List<AlarmConfigure> selectDeviceConfigureByCustomer(AlarmConfigure alarmConfigure);

    /**
     * 删除关联表信息
     * @param alarmConfigureId
     * @return
     */
    public int deleteAlarmConfigureDeviceById(Long alarmConfigureId);
}
