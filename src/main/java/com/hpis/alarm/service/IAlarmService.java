package com.hpis.alarm.service;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.domain.AlarmConfigure;
import com.hpis.alarm.dto.AlarmQueryParameter;
import org.springframework.web.bind.annotation.RequestBody;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 【请填写功能名称】Service接口
 *
 * @author ruoyi
 * @date 2023-03-21
 */
public interface IAlarmService extends IService<Alarm>
{
    /**
     * 查询【请填写功能名称】
     *
     * @param alarmId 【请填写功能名称】ID
     * @return 【请填写功能名称】
     */
    public Alarm selectAlarmById(Long alarmId);

    /**
     * 查询列表(分页)
     *
     * @param alarm 【请填写功能名称】
     * @return 【请填写功能名称】集合
     */
    public Page<Alarm> selectAlarmPage(Alarm alarm);




    Long countAlarm(Alarm alarm);

    /**
     * 查询【请填写功能名称】列表
     *
     * @param alarm 【请填写功能名称】
     * @return 【请填写功能名称】集合
     */
    public List<Alarm> selectAlarmList(Alarm alarm, Long customerId);

    /**
     * 新增【请填写功能名称】
     *
     * @param jsonObject 【请填写功能名称】
     */
    public void insertAlarm(JSONObject jsonObject);

    /**
     * 报警停止
     * @param object
     */
    void alarmStop(@RequestBody JSONObject object);

    /**
     * 根据设备sn报警停止
     * @param object
     */
    void alarmStopByDeviceSn(@RequestBody JSONObject object);
    /**
     * 修改【请填写功能名称】
     *
     * @param alarm 【请填写功能名称】
     * @return 结果
     */
    public int updateAlarm(Alarm alarm);

    /**
     * 批量删除【请填写功能名称】
     *
     * @param alarmIds 需要删除的【请填写功能名称】ID
     * @return 结果
     */
    public int deleteAlarmByIds(Long[] alarmIds);

    /**
     * 删除【请填写功能名称】信息
     *
     * @param alarmId 【请填写功能名称】ID
     * @return 结果
     */
    public int deleteAlarmById(Long alarmId);

    /**
     * 根据object获取照片
     * @param jsonObject
     * @return
     */
    String getAlarmPictureByJSONObject(JSONObject jsonObject);


//    String getAlarmFileByJSONObject(JSONObject jsonObject);

    /**
     * 服务器存储文件并返回文件地址
     * @param byteArray
     * @param fileName
     * @return
     */
    String uploadFile(byte[] byteArray, String fileName);

    Alarm getAlarmPicture(Long alarmId);

    /**
     * 根据设备id和时间范围获取温度报警次数
     * @param deviceId
     * @param dateRange
     * @param customerId
     * @return
     */
    List<Map<String, Object>> getDeviceAlarmCountByDeviceIdAndDateRange(String deviceId, String dateRange, String customerId);


    /**
     * 从irms获取base64图片
     * @param alarm
     * @return
     */
    String getPictureByPath(Alarm alarm);
    /**
     * 根据查询条件对alarm主表进行查询
     * @param alarmQueryParameter
     * @return
     */
    List<Alarm> selectAlarmByQueryParameter(AlarmQueryParameter alarmQueryParameter);


    Map<YearMonth, Long>  alarmTimeCountByMonth(AlarmQueryParameter alarmQueryParameter);
    /**
     * 根据用户 行业 时间 统计报警类型
     * @param alarmQueryParameter
     * @return
     */
    Map<String,Long> alarmModeCount(AlarmQueryParameter alarmQueryParameter);

    /**
     * 一段时间 内的今日报警 和所有报警
     * @param alarmQueryParameter
     * @return
     */
   Map<String,Long> alarmCountByTime(AlarmQueryParameter alarmQueryParameter);

    /**
     *每天报警统计（日期连续）
     * @param alarmQueryParameter
     * @return
     */
    Map<String, String> AlarmOfDay(AlarmQueryParameter alarmQueryParameter);

    /**
     * 根据irmsSn停报警
     * @param object
     * @return
     */
    int alarmStopByIrmsSn(JSONObject object);
}
