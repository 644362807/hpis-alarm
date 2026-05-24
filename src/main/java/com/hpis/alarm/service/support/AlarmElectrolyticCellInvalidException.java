package com.hpis.alarm.service.support;

/**
 * Bad-message exception for electrolytic-cell alarm start payloads.
 *
 * <p>The payload cannot be fixed by MQ redelivery when required fields or the
 * Redis sequence cache are missing, so listeners should log it and ack/drop the
 * message instead of requeueing it.</p>
 */
public class AlarmElectrolyticCellInvalidException extends RuntimeException {

    private final String alarmCid;
    private final String deviceSn;
    private final String irmsSn;
    private final String seq;
    private final String reason;

    public AlarmElectrolyticCellInvalidException(String alarmCid, String deviceSn,
                                                 String irmsSn, String seq, String reason) {
        super("Electrolytic cell alarm payload invalid, alarmCid=" + alarmCid
                + ", deviceSn=" + deviceSn
                + ", irmsSn=" + irmsSn
                + ", seq=" + seq
                + ", reason=" + reason);
        this.alarmCid = alarmCid;
        this.deviceSn = deviceSn;
        this.irmsSn = irmsSn;
        this.seq = seq;
        this.reason = reason;
    }

    public String getAlarmCid() {
        return alarmCid;
    }

    public String getDeviceSn() {
        return deviceSn;
    }

    public String getIrmsSn() {
        return irmsSn;
    }

    public String getSeq() {
        return seq;
    }

    public String getReason() {
        return reason;
    }
}
