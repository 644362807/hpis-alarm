package com.hpis.alarm.dto;

import lombok.Data;

@Data
public class EcHandleSave {

    private String deviceSn ;

    private String[] alarmCids;
}
