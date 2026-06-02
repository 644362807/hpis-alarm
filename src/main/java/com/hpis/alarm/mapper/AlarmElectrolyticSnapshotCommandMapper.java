package com.hpis.alarm.mapper;

import com.hpis.alarm.domain.AlarmElectrolyticSnapshotCommand;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 电解槽当前点位快照可靠命令 Mapper。
 */
public interface AlarmElectrolyticSnapshotCommandMapper {

    int upsertActiveBatch(@Param("commands") List<AlarmElectrolyticSnapshotCommand> commands);

    int enqueueDeleteByAlarmId(@Param("alarmId") Long alarmId,
                               @Param("availableTime") Date availableTime);

    int claimPendingBatch(@Param("lockToken") String lockToken,
                          @Param("lockedAt") Date lockedAt,
                          @Param("limit") int limit);

    List<AlarmElectrolyticSnapshotCommand> selectProcessingByToken(@Param("lockToken") String lockToken);

    int markDoneBatch(@Param("lockToken") String lockToken,
                      @Param("commands") List<AlarmElectrolyticSnapshotCommand> commands);

    int releaseProcessingByToken(@Param("lockToken") String lockToken,
                                 @Param("lastError") String lastError,
                                 @Param("availableTime") Date availableTime,
                                 @Param("maxRetry") int maxRetry);

    int releaseSupersededByToken(@Param("lockToken") String lockToken,
                                 @Param("availableTime") Date availableTime);

    int releaseExpiredProcessing(@Param("lockedBefore") Date lockedBefore,
                                 @Param("availableTime") Date availableTime,
                                 @Param("limit") int limit,
                                 @Param("maxRetry") int maxRetry);
}
