package com.hpis.alarm.mapper;

import com.hpis.alarm.domain.AlarmStopEvent;
import com.hpis.alarm.dto.AlarmStopRecord;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * stop event 可靠缓冲表 Mapper。
 */
public interface AlarmStopEventMapper {

    int upsertPending(@Param("alarmCid") String alarmCid,
                      @Param("stopTime") Date stopTime,
                      @Param("availableTime") Date availableTime);

    int upsertPendingBatch(@Param("records") List<AlarmStopRecord> records,
                           @Param("availableTime") Date availableTime);

    AlarmStopEvent selectPendingByCid(@Param("alarmCid") String alarmCid);

    AlarmStopEvent selectRecoverableByCid(@Param("alarmCid") String alarmCid);

    List<AlarmStopEvent> selectPendingByCids(@Param("alarmCids") List<String> alarmCids);

    List<AlarmStopEvent> selectRecoverableByCids(@Param("alarmCids") List<String> alarmCids);

    List<AlarmStopEvent> selectPendingBatch(@Param("limit") int limit);

    int claimPendingBatch(@Param("lockToken") String lockToken,
                          @Param("lockedAt") Date lockedAt,
                          @Param("limit") int limit);

    List<AlarmStopEvent> selectProcessingByToken(@Param("lockToken") String lockToken);

    int releaseProcessingByToken(@Param("lockToken") String lockToken,
                                 @Param("lastError") String lastError,
                                 @Param("availableTime") Date availableTime,
                                 @Param("maxRetry") int maxRetry);

    int releaseExpiredProcessing(@Param("lockedBefore") Date lockedBefore,
                                 @Param("availableTime") Date availableTime,
                                 @Param("limit") int limit,
                                 @Param("maxRetry") int maxRetry);

    List<AlarmStopEvent> selectFailedRouteMissingBatch(@Param("limit") int limit,
                                                       @Param("recoverAfter") Date recoverAfter);

    int markApplied(@Param("id") Long id, @Param("deleteAfter") Date deleteAfter);

    /**
     * 批量把 stop event 标记为已应用。
     *
     * <p>只有业务分片和 cid route 已经关闭后才调用。该方法减少大批量消警时
     * 逐条 markApplied 的数据库往返，deleteAfter 用于后续低流量窗口物理清理。</p>
     */
    int markAppliedBatch(@Param("ids") List<Long> ids, @Param("deleteAfter") Date deleteAfter);

    int markProcessingAppliedBatch(@Param("events") List<AlarmStopEvent> events,
                                   @Param("lockToken") String lockToken,
                                   @Param("deleteAfter") Date deleteAfter);

    int markRetry(@Param("id") Long id, @Param("lastError") String lastError);

    int markRetryBatch(@Param("ids") List<Long> ids, @Param("lastError") String lastError);

    int markProcessingRetryBatch(@Param("ids") List<Long> ids,
                                 @Param("lockToken") String lockToken,
                                 @Param("lastError") String lastError,
                                 @Param("availableTime") Date availableTime);

    int markFailed(@Param("id") Long id, @Param("lastError") String lastError);

    /**
     * 批量标记失败 stop event。
     *
     * <p>只在达到最大重试次数后调用。ROUTE_MISSING 默认先保持 PENDING，等待 start 后到补偿。</p>
     */
    int markFailedBatch(@Param("ids") List<Long> ids, @Param("lastError") String lastError);

    int markProcessingFailedBatch(@Param("ids") List<Long> ids,
                                  @Param("lockToken") String lockToken,
                                  @Param("lastError") String lastError);

    int deleteApplied(@Param("now") Date now, @Param("limit") int limit);

    int countPending();

    Integer existsOutstanding();
}
