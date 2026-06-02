package com.hpis.alarm.service;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.config.AlarmElectrolyticSnapshotWorkerProperties;
import com.hpis.alarm.domain.AlarmElectrolyticCell;
import com.hpis.alarm.domain.AlarmElectrolyticSnapshotCommand;
import com.hpis.alarm.mapper.AlarmElectrolyticCellMapper;
import com.hpis.alarm.mapper.AlarmElectrolyticSnapshotCommandMapper;
import com.hpis.alarm.service.impl.AlarmElectrolyticCellServiceImpl;
import com.hpis.alarm.service.support.AlarmBatchChunker;
import com.hpis.alarm.service.support.ClaimedElectrolyticSnapshotBatch;
import com.hpis.alarm.service.support.SnapshotProjectionSupersededException;
import com.hpis.alarm.task.AlarmElectrolyticSnapshotWorkerSignal;
import com.hpis.common.core.utils.DateUtils;
import com.hpis.common.core.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 电解槽当前点位快照可靠命令服务。
 *
 * <p>报警历史扩展表仍在 start 核心事务中写入；本服务只把“当前点位快照”改成可靠最终一致投影，
 * 避免多个 MQ consumer 在核心事务内并发 upsert 同一快照表。</p>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "alarm.sharding", name = "enabled", havingValue = "true")
public class AlarmElectrolyticSnapshotCommandService {

    private final AlarmElectrolyticSnapshotCommandMapper commandMapper;
    private final AlarmElectrolyticCellMapper electrolyticCellMapper;
    private final AlarmElectrolyticSnapshotWorkerProperties properties;
    private final AlarmElectrolyticSnapshotWorkerSignal workerSignal;

    public AlarmElectrolyticSnapshotCommandService(AlarmElectrolyticSnapshotCommandMapper commandMapper,
                                                   AlarmElectrolyticCellMapper electrolyticCellMapper,
                                                   AlarmElectrolyticSnapshotWorkerProperties properties,
                                                   AlarmElectrolyticSnapshotWorkerSignal workerSignal) {
        this.commandMapper = commandMapper;
        this.electrolyticCellMapper = electrolyticCellMapper;
        this.properties = properties;
        this.workerSignal = workerSignal;
    }

    /**
     * 在 start 核心事务中可靠 upsert ACTIVE 命令。
     */
    public int enqueueActive(List<AlarmElectrolyticCell> cells) {
        List<AlarmElectrolyticCell> normalized = AlarmElectrolyticCellServiceImpl.normalizeEctypeItems(cells);
        if (normalized.isEmpty()) {
            return 0;
        }
        int affected = 0;
        List<AlarmElectrolyticSnapshotCommand> commands = normalized.stream()
                .map(this::buildActiveCommand)
                .sorted(Comparator.comparing(AlarmElectrolyticSnapshotCommand::getPointHash))
                .collect(Collectors.toList());
        for (List<AlarmElectrolyticSnapshotCommand> chunk : AlarmBatchChunker.chunk(commands,
                properties.safeClaimBatchSize())) {
            affected += commandMapper.upsertActiveBatch(chunk);
        }
        workerSignal.wakeUpAfter("active-command", properties.safeInitialAvailableDelayMs());
        return affected;
    }

    /**
     * stop 副作用只在命令仍指向当前 alarmId 时写 DELETED，旧 stop 不能覆盖新 start。
     */
    public int enqueueDelete(Long alarmId) {
        if (alarmId == null) {
            return 0;
        }
        int affected = commandMapper.enqueueDeleteByAlarmId(alarmId, initialAvailableTime());
        if (affected > 0) {
            workerSignal.wakeUpAfter("delete-command", properties.safeInitialAvailableDelayMs());
        }
        return affected;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class)
    public ClaimedElectrolyticSnapshotBatch claimPendingBatch() {
        String lockToken = UUID.randomUUID().toString().replace("-", "");
        Date lockedAt = DateUtils.getNowDate();
        int claimed = commandMapper.claimPendingBatch(lockToken, lockedAt, properties.safeClaimBatchSize());
        if (claimed <= 0) {
            return new ClaimedElectrolyticSnapshotBatch(lockToken, new ArrayList<>());
        }
        return new ClaimedElectrolyticSnapshotBatch(lockToken, commandMapper.selectProcessingByToken(lockToken));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int processClaimedBatch(String lockToken, List<AlarmElectrolyticSnapshotCommand> commands) {
        if (StringUtils.isBlank(lockToken) || commands == null || commands.isEmpty()) {
            return 0;
        }
        List<AlarmElectrolyticCell> activeCells = new ArrayList<>();
        Set<Long> deletedAlarmIds = new LinkedHashSet<>();
        for (AlarmElectrolyticSnapshotCommand command : commands) {
            if (AlarmElectrolyticSnapshotCommand.TYPE_ACTIVE.equals(command.getCommandType())) {
                activeCells.add(parseCell(command));
            } else if (AlarmElectrolyticSnapshotCommand.TYPE_DELETED.equals(command.getCommandType())) {
                deletedAlarmIds.add(command.getAlarmId());
            } else {
                throw new IllegalArgumentException("未知电解槽快照命令类型: " + command.getCommandType());
            }
        }
        for (List<AlarmElectrolyticCell> chunk : AlarmBatchChunker.chunk(activeCells,
                properties.safeClaimBatchSize())) {
            electrolyticCellMapper.insertAlarmElectrolyticCellEctypeList(chunk);
        }
        List<Long> deletedIds = new ArrayList<>(deletedAlarmIds);
        for (List<Long> chunk : AlarmBatchChunker.chunk(deletedIds, properties.safeClaimBatchSize())) {
            electrolyticCellMapper.deleteAlarmElectrolyticCellEctypeByIds(chunk);
        }
        int done = commandMapper.markDoneBatch(lockToken, commands);
        if (done != commands.size()) {
            throw new SnapshotProjectionSupersededException("snapshot projection token/version superseded, expected="
                    + commands.size() + ", actual=" + done);
        }
        return done;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int releaseClaim(String lockToken, Exception ex) {
        if (StringUtils.isBlank(lockToken)) {
            return 0;
        }
        return commandMapper.releaseProcessingByToken(lockToken, truncateError(ex),
                new Date(System.currentTimeMillis() + properties.safeRetryDelayMs()), properties.safeMaxRetry());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int releaseSupersededClaim(String lockToken) {
        if (StringUtils.isBlank(lockToken)) {
            return 0;
        }
        return commandMapper.releaseSupersededByToken(lockToken, initialAvailableTime());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public int recoverExpiredClaims() {
        Date now = DateUtils.getNowDate();
        return commandMapper.releaseExpiredProcessing(
                new Date(now.getTime() - properties.safeProcessingTimeoutMs()),
                now, properties.safeRecoveryBatchSize(), properties.safeMaxRetry());
    }

    static String pointHash(AlarmElectrolyticCell cell) {
        String pointKey = AlarmElectrolyticCellServiceImpl.ectypePointKey(cell);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(pointKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JDK 缺少 SHA-256", ex);
        }
    }

    private AlarmElectrolyticSnapshotCommand buildActiveCommand(AlarmElectrolyticCell cell) {
        AlarmElectrolyticSnapshotCommand command = new AlarmElectrolyticSnapshotCommand();
        command.setPointHash(pointHash(cell));
        command.setCommandType(AlarmElectrolyticSnapshotCommand.TYPE_ACTIVE);
        command.setAlarmId(cell.getAlarmId());
        command.setAlarmBeginTime(cell.getAlarmBegintime());
        command.setPayloadJson(JSONObject.toJSONString(cell));
        command.setAvailableTime(initialAvailableTime());
        return command;
    }

    private Date initialAvailableTime() {
        return new Date(System.currentTimeMillis() + properties.safeInitialAvailableDelayMs());
    }

    private AlarmElectrolyticCell parseCell(AlarmElectrolyticSnapshotCommand command) {
        AlarmElectrolyticCell cell = JSONObject.parseObject(command.getPayloadJson(), AlarmElectrolyticCell.class);
        if (cell == null || cell.getAlarmId() == null) {
            throw new IllegalStateException("电解槽快照 ACTIVE 命令 payload 缺少 alarmId, pointHash=" + command.getPointHash());
        }
        return cell;
    }

    private String truncateError(Exception ex) {
        String message = ex == null || ex.getMessage() == null
                ? (ex == null ? "PROCESS_FAILED" : ex.getClass().getName())
                : ex.getMessage();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
