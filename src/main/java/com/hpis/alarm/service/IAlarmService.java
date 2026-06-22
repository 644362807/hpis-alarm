package com.hpis.alarm.service;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.dto.AlarmQueryParameter;
import org.springframework.web.bind.annotation.RequestBody;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 报警记录 Service 接口。
 */
public interface IAlarmService extends IService<Alarm>
{
    /**
     * 按内部报警 ID 查询单条报警。
     *
     * @param alarmId 内部报警 ID
     * @return 报警记录
     */
    public Alarm selectAlarmById(Long alarmId);

    /**
     * 查询报警分页列表。
     *
     * @param alarm 查询条件，列表时间范围使用 startTime/endTime
     * @return 报警分页结果
     */
    public Page<Alarm> selectAlarmPage(Alarm alarm);




    Long countAlarm(Alarm alarm);

    /**
     * 查询报警列表。
     *
     * @param alarm 查询条件
     * @return 报警列表
     */
    public List<Alarm> selectAlarmList(Alarm alarm, Long customerId);

    /**
     * 新增单条报警。
     *
     * @param jsonObject MQ/接口传入的原始报警 JSON
     */
    public void insertAlarm(JSONObject jsonObject);

    /**
     * 内部批量新增报警入口。
     *
     * <p>该方法用于 MQ consumer batch 或后续内部批量调用方，外部 Controller 仍保持
     * {@link #insertAlarm(JSONObject)} 单条入口不变。实现层必须保证批量失败可按配置拆回单条兜底，
     * 且 push payload、MQ 消息体、断线 Redis 去重语义与旧单条链路兼容。</p>
     */
    void insertAlarms(List<JSONObject> jsonObjects);

    /**
     * 按 alarmCid 停止报警。
     *
     * @param object 停止报警 payload
     */
    void alarmStop(@RequestBody JSONObject object);

    /**
     * 根据设备 SN 停止报警。
     *
     * @param object 停止报警 payload
     */
    void alarmStopByDeviceSn(@RequestBody JSONObject object);
    /**
     * 修改报警记录。
     *
     * @param alarm 报警记录
     * @return 结果
     */
    public int updateAlarm(Alarm alarm);

    /**
     * 批量删除报警记录。
     *
     * @param alarmIds 需要删除的报警 ID
     * @return 结果
     */
    public int deleteAlarmByIds(Long[] alarmIds);

    /**
     * 删除单条报警记录。
     *
     * @param alarmId 报警 ID
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
