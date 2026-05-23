package com.hpis.alarm.mapper;

import com.hpis.alarm.domain.AlarmCidRoute;
import com.hpis.alarm.dto.AlarmStopApplyItem;
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

    /**
     * 批量写入 ACTIVE hot route。
     *
     * <p>insert 批量链路提交主报警后调用，作为后续 stop 按 cid 找分片的核心索引。
     * 调用方必须按配置分片传入，避免单条 SQL 过大。</p>
     */
    int upsertHotActiveBatch(@Param("routes") List<AlarmCidRoute> routes);

    int upsertStaleActive(AlarmCidRoute route);

    AlarmCidRoute selectHotByCid(@Param("alarmCid") String alarmCid);

    AlarmCidRoute selectStaleByCid(@Param("alarmCid") String alarmCid);

    /**
     * 按 cid 批量查询 hot route。
     *
     * <p>用于替代 stop worker 中循环单条 selectHotByCid，入参数量必须由 service 层按 inLimit 控制。</p>
     */
    List<AlarmCidRoute> selectHotByCids(@Param("alarmCids") List<String> alarmCids);

    /**
     * 按 cid 批量查询 stale route。
     *
     * <p>hot 未命中时仍需要查 stale，保证长时间未关闭但仍 active 的报警可以被 stop 找到。</p>
     */
    List<AlarmCidRoute> selectStaleByCids(@Param("alarmCids") List<String> alarmCids);

    List<AlarmCidRoute> selectActiveHotByDeviceSn(@Param("deviceSn") String deviceSn,
                                                  @Param("excludeAlarmType") String excludeAlarmType);

    List<AlarmCidRoute> selectActiveStaleByDeviceSn(@Param("deviceSn") String deviceSn,
                                                    @Param("excludeAlarmType") String excludeAlarmType);

    List<AlarmCidRoute> selectActiveHotByIrmsSn(@Param("irmsSn") String irmsSn);

    List<AlarmCidRoute> selectActiveStaleByIrmsSn(@Param("irmsSn") String irmsSn);

    int closeHotByAlarmId(@Param("alarmId") Long alarmId,
                          @Param("alarmEndtime") Date alarmEndtime,
                          @Param("deleteAfter") Date deleteAfter);

    /**
     * 批量关闭 hot 路由。
     *
     * <p>每个 item 保留自己的 stopTime，XML 中使用 CASE 写入 alarm_endTime，
     * 避免批量 SQL 把同一批报警统一成同一个结束时间。</p>
     */
    int closeHotBatch(@Param("items") List<AlarmStopApplyItem> items,
                      @Param("deleteAfter") Date deleteAfter);

    int closeStaleByAlarmId(@Param("alarmId") Long alarmId,
                            @Param("alarmEndtime") Date alarmEndtime,
                            @Param("deleteAfter") Date deleteAfter);

    /**
     * 批量关闭 stale 路由。
     *
     * <p>stale 表只保存长时间未关闭但仍需 cid 定位的报警；关闭后同样转为 CLOSED，
     * 后续由低流量清理任务按 deleteAfter 物理删除。</p>
     */
    int closeStaleBatch(@Param("items") List<AlarmStopApplyItem> items,
                        @Param("deleteAfter") Date deleteAfter);

    List<AlarmCidRoute> selectHotTransferCandidates(@Param("cutoffTime") Date cutoffTime,
                                                    @Param("limit") int limit);

    int deleteHotActiveByAlarmId(@Param("alarmId") Long alarmId);

    int deleteClosedHot(@Param("now") Date now, @Param("limit") int limit);

    int deleteClosedStale(@Param("now") Date now, @Param("limit") int limit);

    List<AlarmCidRoute> selectExpiredActiveStale(@Param("now") Date now,
                                                 @Param("limit") int limit);
}
