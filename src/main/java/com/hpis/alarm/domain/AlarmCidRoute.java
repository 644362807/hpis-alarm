package com.hpis.alarm.domain;

import lombok.Data;

import java.util.Date;

/**
 * alarm_cid hot/stale 路由记录。
 *
 * <p>该对象只服务外部 cid 的生命周期定位：报警未结束时可以通过 cid 找到真实
 * alarm_yyyyMM_nn；报警结束并经过清理后不再保存历史 cid 路由，避免路由表随总报警量
 * 永久增长。</p>
 */
@Data
public class AlarmCidRoute {

    public static final String STATUS_ACTIVE = "ACTIVE";

    public static final String STATUS_CLOSED = "CLOSED";

    public static final String SOURCE_HOT = "HOT";

    public static final String SOURCE_STALE = "STALE";

    private Long id;

    private String alarmCid;

    private Long alarmId;

    private Date alarmBegintime;

    private Date alarmEndtime;

    private String tableSuffix;

    private String deviceSn;

    private String irmsSn;

    private String alarmType;

    private String routeStatus;

    private Date deleteAfter;

    private Date staleTime;

    private Date expireTime;

    /**
     * 标记记录来自 hot 表还是 stale 表，便于服务层关闭时回写正确的物理索引表。
     */
    private String indexSource;
}
