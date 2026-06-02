package com.hpis.alarm.service.support;

import com.hpis.alarm.domain.AlarmElectrolyticSnapshotCommand;

import java.util.Collections;
import java.util.List;

/**
 * 已认领的电解槽当前点位快照命令批次。
 */
public class ClaimedElectrolyticSnapshotBatch {

    private final String lockToken;
    private final List<AlarmElectrolyticSnapshotCommand> commands;

    public ClaimedElectrolyticSnapshotBatch(String lockToken, List<AlarmElectrolyticSnapshotCommand> commands) {
        this.lockToken = lockToken;
        this.commands = commands == null ? Collections.emptyList() : commands;
    }

    public String getLockToken() {
        return lockToken;
    }

    public List<AlarmElectrolyticSnapshotCommand> getCommands() {
        return commands;
    }

    public boolean isEmpty() {
        return commands.isEmpty();
    }
}
