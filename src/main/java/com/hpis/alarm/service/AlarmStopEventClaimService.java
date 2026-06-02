package com.hpis.alarm.service;

import com.hpis.alarm.config.AlarmStopWorkerProperties;
import com.hpis.alarm.domain.AlarmStopEvent;
import com.hpis.alarm.mapper.AlarmStopEventMapper;
import com.hpis.alarm.service.support.ClaimedStopBatch;
import com.hpis.common.core.utils.DateUtils;
import com.hpis.common.core.utils.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * stop event 的短事务认领与释放服务。
 *
 * <p>认领事务只负责把 PENDING 原子改成 PROCESSING 并立即提交。真正关闭业务分片的事务
 * 在认领提交后执行，避免一个事务同时持有 claim 行锁和业务表更新锁。</p>
 */
@Service
@ConditionalOnProperty(prefix = "alarm.sharding", name = "enabled", havingValue = "true")
public class AlarmStopEventClaimService {

    private final AlarmStopEventMapper stopEventMapper;
    private final AlarmStopWorkerProperties properties;

    public AlarmStopEventClaimService(AlarmStopEventMapper stopEventMapper,
                                      AlarmStopWorkerProperties properties) {
        this.stopEventMapper = stopEventMapper;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public ClaimedStopBatch claimPendingBatch() {
        String lockToken = UUID.randomUUID().toString().replace("-", "");
        Date lockedAt = DateUtils.getNowDate();
        int claimed = stopEventMapper.claimPendingBatch(lockToken, lockedAt, properties.safeClaimBatchSize());
        if (claimed <= 0) {
            return new ClaimedStopBatch(lockToken, Collections.emptyList());
        }
        List<AlarmStopEvent> events = stopEventMapper.selectProcessingByToken(lockToken);
        return new ClaimedStopBatch(lockToken, events);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int releaseClaim(String lockToken, Exception ex) {
        if (StringUtils.isBlank(lockToken)) {
            return 0;
        }
        return stopEventMapper.releaseProcessingByToken(lockToken, truncateError(ex),
                new Date(System.currentTimeMillis() + properties.safeProcessingRetryDelayMs()),
                properties.safeMaxRetry());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public int recoverExpiredClaims() {
        Date now = DateUtils.getNowDate();
        Date lockedBefore = new Date(now.getTime() - properties.safeProcessingTimeoutMs());
        return stopEventMapper.releaseExpiredProcessing(lockedBefore, now, properties.safeClaimRecoveryBatchSize(),
                properties.safeMaxRetry());
    }

    public boolean hasOutstandingEvents() {
        Integer exists = stopEventMapper.existsOutstanding();
        return exists != null && exists > 0;
    }

    private String truncateError(Exception ex) {
        String message = ex == null || ex.getMessage() == null
                ? (ex == null ? "PROCESS_FAILED" : ex.getClass().getName())
                : ex.getMessage();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
