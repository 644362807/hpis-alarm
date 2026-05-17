package com.hpis.alarm.service.support;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.dto.AlarmInsertCommand;
import com.hpis.common.core.utils.DateUtils;
import com.hpis.common.core.utils.StringUtils;
import com.hpis.common.redis.service.RedisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 断线报警 Redis 去重器。
 *
 * <p>只处理断线报警，避免 MQ 重复投递或网关批量重放时重复入库、重复推送。
 * 当前实现是低风险第一阶段：只在 {@code insertAlarm} 原流程前后加守卫，不改变报警入库顺序和 push 消息体。</p>
 *
 * <p>Redis key/value 边界：</p>
 * <ul>
 *     <li>key：{@code hpis:alarm:dedup:disconnect:{alarmCid}}</li>
 *     <li>value：小 JSON，仅保存 {@code alarmCid/deviceSn/sceneType/alarmType/traceId/createdAt}</li>
 *     <li>TTL：配置项 {@code alarm.dedup.disconnect.ttl-seconds}，单位秒；业务上可以配置为数天，例如 3 天是 259200</li>
 * </ul>
 *
 * <p>Redis 不是核心入库链路的强依赖。Redis 异常时返回失败状态并继续入库，不能因为去重组件故障丢报警。</p>
 */
@Slf4j
@Component
public class DisconnectAlarmDeduplicator {

    public static final String KEY_PREFIX = "hpis:alarm:dedup:disconnect:";

    /**
     * 断线报警去重 TTL，单位秒。
     *
     * <p>该 TTL 由业务根据断线报警可能重复上报的窗口配置；报警量大时需要评估
     * “TTL 时间内不同 alarmCid 数量 * value 大小”的 Redis 内存上限。</p>
     */
    @Value("${alarm.dedup.disconnect.ttl-seconds:1800}")
    private long ttlSeconds;

    @Autowired
    private RedisService redisService;

    /**
     * 尝试获取断线报警去重锁。
     *
     * <p>返回 acquired 表示本条消息允许继续入库；duplicate 表示已存在相同 alarmCid 的断线报警，
     * 调用方应直接返回并让 MQ ack；redisFailed 表示 Redis 不可用或配置异常，调用方继续入库。</p>
     */
    public DedupResult tryAcquire(AlarmInsertCommand command, String traceId) {
        if (StringUtils.isBlank(command.getAlarmCid())) {
            log.error("disconnect alarm dedup skipped because alarmCid is blank, traceId={}, deviceSn={}",
                    traceId, command.getDeviceSn());
            return DedupResult.redisFailed(null);
        }
        String key = buildKey(command.getAlarmCid());
        try {
            Boolean acquired = redisService.redisTemplate.opsForValue()
                    .setIfAbsent(key, buildValue(command, traceId), ttlSeconds, TimeUnit.SECONDS);
            if (Boolean.TRUE.equals(acquired)) {
                return DedupResult.acquired(key);
            }
            log.warn("disconnect alarm duplicate skipped, traceId={}, key={}, alarmCid={}, deviceSn={}, ttlSeconds={}",
                    traceId, key, command.getAlarmCid(), command.getDeviceSn(), ttlSeconds);
            return DedupResult.duplicate(key);
        } catch (Exception ex) {
            log.error("disconnect alarm redis dedup failed, continue insert, traceId={}, key={}, alarmCid={}, ttlSeconds={}, error={}",
                    traceId, key, command.getAlarmCid(), ttlSeconds, ex.getMessage(), ex);
            return DedupResult.redisFailed(key);
        }
    }

    /**
     * 入库失败时释放本次刚占用的去重 key。
     *
     * <p>只有 acquired=true 才释放。入库成功时不释放，保留到 TTL 自然过期，
     * 用于覆盖 MQ redelivery 或短时间重复上报。</p>
     */
    public void releaseOnFailure(DedupResult result, String traceId, String alarmCid) {
        if (result == null || !result.isAcquired()) {
            return;
        }
        try {
            redisService.deleteObject(result.getKey());
        } catch (Exception ex) {
            log.error("disconnect alarm dedup release failed, traceId={}, key={}, alarmCid={}, error={}",
                    traceId, result.getKey(), alarmCid, ex.getMessage(), ex);
        }
    }

    private String buildKey(String alarmCid) {
        return KEY_PREFIX + alarmCid;
    }

    /**
     * 构造 Redis value。
     *
     * <p>value 只放排障字段，不放完整报警体，避免报警量大时 Redis 内存被消息体放大。</p>
     */
    private String buildValue(AlarmInsertCommand command, String traceId) {
        JSONObject value = new JSONObject();
        value.put("alarmCid", command.getAlarmCid());
        value.put("deviceSn", command.getDeviceSn());
        value.put("sceneType", command.getSceneType());
        value.put("alarmType", command.getAlarmType());
        value.put("traceId", traceId);
        value.put("createdAt", DateUtils.getTime());
        return value.toJSONString();
    }

    /**
     * 去重结果对象。
     *
     * <p>调用方只需要关心是否重复、是否占用了 key；Redis 失败会表现为 acquired=false 且 duplicate=false。</p>
     */
    public static class DedupResult {
        private final String key;
        private final boolean acquired;
        private final boolean duplicate;

        private DedupResult(String key, boolean acquired, boolean duplicate) {
            this.key = key;
            this.acquired = acquired;
            this.duplicate = duplicate;
        }

        public static DedupResult acquired(String key) {
            return new DedupResult(key, true, false);
        }

        public static DedupResult duplicate(String key) {
            return new DedupResult(key, false, true);
        }

        public static DedupResult redisFailed(String key) {
            return new DedupResult(key, false, false);
        }

        public String getKey() {
            return key;
        }

        public boolean isAcquired() {
            return acquired;
        }

        public boolean isDuplicate() {
            return duplicate;
        }
    }
}
