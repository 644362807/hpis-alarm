package com.hpis.alarm.mapper;

import com.hpis.alarm.domain.AlarmStopSideEffectEvent;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 消警副作用事件 Mapper。
 */
public interface AlarmStopSideEffectMapper {

    int upsertPending(AlarmStopSideEffectEvent event);

    List<AlarmStopSideEffectEvent> selectPendingBatch(@Param("limit") int limit);

    int markDone(@Param("id") Long id);

    int markRetry(@Param("id") Long id, @Param("lastError") String lastError);

    int markFailed(@Param("id") Long id, @Param("lastError") String lastError);
}
