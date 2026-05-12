package com.hpis.alarm.mapper;

import com.hpis.alarm.domain.AlarmCidRoute;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 外部报警 ID 生命周期路由 Mapper。
 *
 * <p>这里管理的是 hot/stale 路由索引，不是全量历史索引。已关闭报警会在低流量
 * 清理任务中物理删除，因此所有查询都必须围绕“当前仍可能通过 cid 再次访问”的数据。</p>
 */
public interface AlarmCidIndexMapper {

    int upsertHotActive(AlarmCidRoute route);

    int upsertStaleActive(AlarmCidRoute route);

    AlarmCidRoute selectHotByCid(@Param("alarmCid") String alarmCid);

    AlarmCidRoute selectStaleByCid(@Param("alarmCid") String alarmCid);

    List<AlarmCidRoute> selectActiveHotByDeviceSn(@Param("deviceSn") String deviceSn,
                                                  @Param("excludeAlarmType") String excludeAlarmType);

    List<AlarmCidRoute> selectActiveStaleByDeviceSn(@Param("deviceSn") String deviceSn,
                                                    @Param("excludeAlarmType") String excludeAlarmType);

    List<AlarmCidRoute> selectActiveHotByIrmsSn(@Param("irmsSn") String irmsSn);

    List<AlarmCidRoute> selectActiveStaleByIrmsSn(@Param("irmsSn") String irmsSn);

    int closeHotByAlarmId(@Param("alarmId") Long alarmId,
                          @Param("alarmEndtime") Date alarmEndtime,
                          @Param("deleteAfter") Date deleteAfter);

    int closeStaleByAlarmId(@Param("alarmId") Long alarmId,
                            @Param("alarmEndtime") Date alarmEndtime,
                            @Param("deleteAfter") Date deleteAfter);

    List<AlarmCidRoute> selectHotTransferCandidates(@Param("cutoffTime") Date cutoffTime,
                                                    @Param("limit") int limit);

    int deleteHotActiveByAlarmId(@Param("alarmId") Long alarmId);

    int deleteClosedHot(@Param("now") Date now, @Param("limit") int limit);

    int deleteClosedStale(@Param("now") Date now, @Param("limit") int limit);

    List<AlarmCidRoute> selectExpiredActiveStale(@Param("now") Date now,
                                                 @Param("limit") int limit);
}
