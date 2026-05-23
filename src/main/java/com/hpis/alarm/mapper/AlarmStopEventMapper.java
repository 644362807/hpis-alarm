package com.hpis.alarm.mapper;

import com.hpis.alarm.domain.AlarmStopEvent;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * stop event 可靠缓冲表 Mapper。
 */
public interface AlarmStopEventMapper {

    int upsertPending(@Param("alarmCid") String alarmCid, @Param("stopTime") Date stopTime);

    AlarmStopEvent selectPendingByCid(@Param("alarmCid") String alarmCid);

    List<AlarmStopEvent> selectPendingByCids(@Param("alarmCids") List<String> alarmCids);

    List<AlarmStopEvent> selectPendingBatch(@Param("limit") int limit);

    int markApplied(@Param("id") Long id, @Param("deleteAfter") Date deleteAfter);

    /**
     * 批量把 stop event 标记为已应用。
     *
     * <p>只有业务分片和 cid route 已经关闭后才调用。该方法减少大批量消警时
     * 逐条 markApplied 的数据库往返，deleteAfter 用于后续低流量窗口物理清理。</p>
     */
    int markAppliedBatch(@Param("ids") List<Long> ids, @Param("deleteAfter") Date deleteAfter);

    int markRetry(@Param("id") Long id, @Param("lastError") String lastError);

    int markFailed(@Param("id") Long id, @Param("lastError") String lastError);

    /**
     * 批量标记失败 stop event。
     *
     * <p>当前主要用于 ROUTE_MISSING：hot/stale 都查不到 route 时，按本轮确认的新语义直接失败，
     * 不再让 event 长期 PENDING 等待不存在的 start 补偿。</p>
     */
    int markFailedBatch(@Param("ids") List<Long> ids, @Param("lastError") String lastError);

    int deleteApplied(@Param("now") Date now, @Param("limit") int limit);

    int countPending();
}
