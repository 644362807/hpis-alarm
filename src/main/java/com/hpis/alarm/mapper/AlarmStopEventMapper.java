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

    List<AlarmStopEvent> selectPendingBatch(@Param("limit") int limit);

    int markApplied(@Param("id") Long id, @Param("deleteAfter") Date deleteAfter);

    int markRetry(@Param("id") Long id, @Param("lastError") String lastError);

    int markFailed(@Param("id") Long id, @Param("lastError") String lastError);

    int deleteApplied(@Param("now") Date now, @Param("limit") int limit);

    int countPending();
}
