package com.hpis.alarm.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hpis.alarm.domain.Alarm;
import com.hpis.alarm.domain.AlarmConfigure;
import com.hpis.alarm.dto.AlarmStopApplyItem;
import com.hpis.alarm.dto.AlarmQueryParameter;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.annotations.Param;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 【请填写功能名称】Mapper接口
 *
 * @author ruoyi
 * @date 2023-03-21
 */
public interface AlarmMapper  extends BaseMapper<Alarm>
{


    /**
     * 报警主表分页
     * @param rowBounds
     * @param wrapper
     * @return
     */

    Page<Alarm> selectAlarmListPage(Page rowBounds, @Param("ew") Wrapper<Alarm> wrapper);

    /**
     * 查询【请填写功能名称】
     *
     * @param alarmId 【请填写功能名称】ID
     * @return 【请填写功能名称】
     */
    public Alarm selectAlarmById(Long alarmId);

    /**
     * 根据查询条件对alarm主表进行查询
     * @param alarmQueryParameter
     * @return
     */
    public  List<Alarm> selectAlarmByQueryParameter( AlarmQueryParameter alarmQueryParameter);


    @MapKey("total_count_custom_range")
    public List<Map<String, Long>> alarmOfDay(AlarmQueryParameter alarmQueryParameter);

    @MapKey("total_count_custom_range")
    public List<Map<String, Long>> alarmCountByTime(@Param("alarm") AlarmQueryParameter alarmQueryParameter);


    @MapKey("count_of_transactions_today")
    public List<Map<String, Long>> countNoHandelOfDay( AlarmQueryParameter alarmQueryParameter);


    @MapKey("rank0")
    public List<Map<String, Long>> countAlarmMode( AlarmQueryParameter alarmQueryParameter);
    /**
     * 查询【请填写功能名称】列表
     *
     * @param alarm 【请填写功能名称】
     * @return 【请填写功能名称】集合
     */
    public List<Alarm> selectAlarmList( Alarm alarm);

    /**
     * 新增【请填写功能名称】
     *
     * @param alarm 【请填写功能名称】
     * @return 结果
     */
    public int insertAlarm(Alarm alarm);


    /**
     * 批量插入
     * @param alarmList
     * @return
     */
    public int insertAl1armList(List<Alarm> alarmList);

    /**
     * 修改【请填写功能名称】
     *
     * @param alarm 【请填写功能名称】
     * @return 结果
     */
    public int updateAlarm(Alarm alarm);

    /**
     * 逻辑删除【请填写功能名称】
     *
     * @param alarmId 【请填写功能名称】ID
     * @return 结果
     */
    public int deleteAlarmById(Long alarmId);

    /**
     * 真删除【请填写功能名称】
     *
     * @param alarmId 【请填写功能名称】ID
     * @return 结果
     */
    public int deleteAlarmByIdReal(Long alarmId);

    /**
     * 批量删除【请填写功能名称】
     *
     * @param alarmIds 需要删除的数据ID
     * @return 结果
     */
    public int deleteAlarmByIds(Long[] alarmIds);

    /**
     * 报警停止
     * @param alarmCId
     * @param endTime
     * @return
     */
    int alarmStop(@Param("alarmCId") String alarmCId,@Param("alarmStatus") String alarmStatus, @Param("endTime") String endTime);

    /**
     * worker 按物理分片分组后批量关闭报警。
     *
     * <p>items 必须属于同一个 table_suffix，调用前由 AlarmShardContext 指定目标分片。
     * 每条 stop 消息的结束时间可能不同，所以 SQL 使用 CASE WHEN 按 alarm_id 写入各自的 endTime。</p>
     */
    int batchStopByAlarmIds(@Param("items") List<AlarmStopApplyItem> items,
                            @Param("alarmStatus") String alarmStatus);

    /**
     * 查询已关闭报警的最小上下文，用于生成消警后的设备同步和扩展表清理事件。
     */
    List<Alarm> selectAlarmByIdsForStop(@Param("alarmIds") List<Long> alarmIds);


    /**
     * 通过设备id报警停止
     * @param alarmCId
     * @param endTime
     * @return
     */
    int alarmStopByDeviceId(@Param("deviceSn") String deviceSn, @Param("endTime") String endTime);


    /**
     * 通过设备id报警停止
     * @param irmsSn
     * @param endTime
     * @return
     */
    int alarmAllStopByIrmsSn(@Param("irmsSn") String irmsSn, @Param("endTime") String endTime);
    /**
     * 通过cid查询报警
     * @param alarmCid
     * @return
     */
    Alarm selectAlarmByCid(String alarmCid);

    @MapKey("onDate")
    List<Map<String, Object>> getDeviceAlarmCountByDeviceIdAndDateRangeToday(Map<String, Object> params);

    @MapKey("onDate")
    List<Map<String, Object>> getDeviceAlarmCountByDeviceIdAndDateRange(Map<String, Object> params);

    /**
     * 查询该设备未结束的断线报警数量
     * @param deviceId
     * @return
     */
    Long selectCountByBreakLine(@Param("deviceId") Long deviceId, @Param("targetName") String targetName);

    
}
