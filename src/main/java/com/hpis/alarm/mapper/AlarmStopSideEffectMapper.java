package com.hpis.alarm.mapper;

import com.hpis.alarm.domain.AlarmStopSideEffectEvent;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 消警副作用事件 Mapper。
 */
public interface AlarmStopSideEffectMapper {

    int upsertPending(AlarmStopSideEffectEvent event);

    /**
     * 批量写入待执行副作用事件。
     *
     * <p>核心消警事务只负责把副作用可靠落库，不直接执行远程调用；
     * 后续由 side effect worker 逐条执行并批量标记 DONE。</p>
     */
    int upsertPendingBatch(@Param("events") List<AlarmStopSideEffectEvent> events);

    List<AlarmStopSideEffectEvent> selectPendingBatch(@Param("limit") int limit);

    int markDone(@Param("id") Long id);

    /**
     * 批量标记已成功执行的副作用事件。
     *
     * <p>副作用动作本身仍逐条执行，因为 effectType 和远程调用不同；
     * 成功 id 收集后统一 DONE，避免 2 万级副作用再产生 2 万次状态更新。</p>
     */
    int markDoneBatch(@Param("ids") List<Long> ids);

    int markRetry(@Param("id") Long id, @Param("lastError") String lastError);

    int markFailed(@Param("id") Long id, @Param("lastError") String lastError);
}
