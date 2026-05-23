package com.hpis.alarm.service;

import com.alibaba.fastjson.JSONObject;
import com.hpis.alarm.config.AlarmBatchProperties;
import com.hpis.alarm.service.impl.AlarmServiceImpl;
import com.hpis.alarm.service.support.AlarmDeviceCacheMissingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Spring AMQP consumer batch listener 使用的 start 报警批处理服务。
 *
 * <p>本服务只负责把同一批 start 消息逐条 prepare 后批量持久化，并返回每条消息的业务结果。
 * RabbitMQ ack/nack 仍由 {@code RabbitMQAlarmBatchListener} 在当前消费线程执行，避免业务服务层持有 Channel。</p>
 *
 * <p>重要边界：设备缓存缺失只 DROP 当前 item；批量事务失败时可以拆回单条持久化，但必须复用已经构建好的
 * {@code AlarmInsertContext}，不能重新执行 Redis 断线去重或远程状态同步。</p>
 */
@Slf4j
@Service
public class AlarmInsertBatchConsumeService {

    private final AlarmBatchProperties properties;
    private final AlarmServiceImpl alarmService;

    public AlarmInsertBatchConsumeService(AlarmBatchProperties properties, AlarmServiceImpl alarmService) {
        this.properties = properties;
        this.alarmService = alarmService;
    }

    /**
     * 批量处理一组 start rawData，并保持返回结果与入参下标一一对应。
     *
     * <p>返回顺序非常关键：listener 后续会按相同下标对 MQ deliveryTag 做 ack/nack。
     * 因此即使某条 prepare 失败，也要在对应位置写入 FAIL/DROP/SKIP，不能压缩结果列表。</p>
     */
    public List<AlarmInsertConsumeResult> processStartBatch(List<JSONObject> rawDataList) {
        List<AlarmInsertConsumeResult> results = new ArrayList<>();
        if (rawDataList == null || rawDataList.isEmpty()) {
            return results;
        }
        for (int i = 0; i < rawDataList.size(); i++) {
            results.add(null);
        }

        String batchId = UUID.randomUUID().toString().replace("-", "");
        long startMs = System.currentTimeMillis();
        List<PreparedStartItem> preparedItems = new ArrayList<>();

        /*
         * prepare 仍然逐条执行，因为设备缓存、断线 Redis 去重、远程状态同步当前都还没有批量接口。
         * 第一阶段的收益来自“持久化批量化”，不是把所有校验/组装改成并发循环。
         */
        for (int i = 0; i < rawDataList.size(); i++) {
            JSONObject rawData = rawDataList.get(i);
            try {
                AlarmServiceImpl.AlarmInsertContext context = alarmService.prepareAlarmInsertContext(rawData);
                if (context == null || context.isSkipped()) {
                    results.set(i, AlarmInsertConsumeResult.SKIP);
                } else {
                    preparedItems.add(new PreparedStartItem(i, rawData, context));
                }
            } catch (AlarmDeviceCacheMissingException ex) {
                log.warn("alarm batch listener device cache missing, drop item, batchId={}, alarmCid={}, deviceSn={}, sceneType={}, cameraType={}, error={}",
                        batchId, ex.getAlarmCid(), ex.getDeviceSn(), ex.getSceneType(), ex.getCameraType(), ex.getMessage());
                results.set(i, AlarmInsertConsumeResult.DROP);
            } catch (Exception ex) {
                log.error("alarm batch listener prepare failed, batchId={}, alarmCid={}, error={}",
                        batchId, alarmCid(rawData), ex.getMessage(), ex);
                results.set(i, AlarmInsertConsumeResult.FAIL);
            }
        }

        if (!preparedItems.isEmpty()) {
            persistPreparedItems(batchId, preparedItems, results);
        }

        /*
         * release 方法内部会检查 insertCompleted。
         * 对成功提交的 context 不会释放 Redis dedup；只有最终没有成功入库的 prepared context 才释放，
         * 避免断线报警因为批量失败后重投而被错误永久占用。
         */
        for (PreparedStartItem item : preparedItems) {
            alarmService.releasePreparedAlarmOnFailure(item.context);
        }

        for (int i = 0; i < results.size(); i++) {
            if (results.get(i) == null) {
                results.set(i, AlarmInsertConsumeResult.FAIL);
            }
        }

        if (properties.isInsertConsumerBatchLogEnabled() || hasFailure(results)) {
            log.info("alarm batch listener stage=BATCH_START_DONE batchId={}, inputSize={}, preparedSize={}, resultCounts={}, costMs={}, sampleAlarmCids={}",
                    batchId, rawDataList.size(), preparedItems.size(), resultCounts(results),
                    System.currentTimeMillis() - startMs, sampleAlarmCids(rawDataList));
        }
        return results;
    }

    private void persistPreparedItems(String batchId, List<PreparedStartItem> preparedItems,
                                      List<AlarmInsertConsumeResult> results) {
        if (properties.isInsertConsumerBatchLogEnabled()) {
            log.info("alarm batch listener stage=BATCH_PERSIST_START batchId={}, batchSize={}, sampleAlarmCids={}",
                    batchId, preparedItems.size(), samplePreparedAlarmCids(preparedItems));
        }
        try {
            // 批量持久化是一个事务：任一 suffix 或扩展表失败都会整体回滚，再进入拆单兜底。
            alarmService.persistPreparedAlarmBatch(batchId, contexts(preparedItems));
            for (PreparedStartItem item : preparedItems) {
                results.set(item.index, AlarmInsertConsumeResult.SUCCESS);
            }
            if (properties.isInsertConsumerBatchLogEnabled()) {
                log.info("alarm batch listener stage=BATCH_PERSIST_SUCCESS batchId={}, batchSize={}, sampleAlarmCids={}",
                        batchId, preparedItems.size(), samplePreparedAlarmCids(preparedItems));
            }
        } catch (Exception ex) {
            log.error("alarm batch listener stage=BATCH_PERSIST_FAILED batchId={}, batchSize={}, sampleAlarmCids={}, error={}",
                    batchId, preparedItems.size(), samplePreparedAlarmCids(preparedItems), ex.getMessage(), ex);
            handleBatchFailure(batchId, preparedItems, results);
        }
    }

    private void handleBatchFailure(String batchId, List<PreparedStartItem> preparedItems,
                                    List<AlarmInsertConsumeResult> results) {
        if (!properties.isFallbackSingleOnBatchError()) {
            for (PreparedStartItem item : preparedItems) {
                results.set(item.index, AlarmInsertConsumeResult.FAIL);
            }
            return;
        }
        for (PreparedStartItem item : preparedItems) {
            try {
                // 单条兜底复用 prepared context；不能重新 prepare，否则 Redis 去重和远程副作用会重复执行。
                alarmService.persistPreparedAlarmSingle(batchId, item.context);
                results.set(item.index, AlarmInsertConsumeResult.SUCCESS);
            } catch (Exception singleEx) {
                results.set(item.index, AlarmInsertConsumeResult.FAIL);
                log.error("alarm batch listener stage=FALLBACK_SINGLE failed, batchId={}, alarmCid={}, error={}",
                        batchId, alarmCid(item.rawData), singleEx.getMessage(), singleEx);
            }
        }
    }

    private List<AlarmServiceImpl.AlarmInsertContext> contexts(List<PreparedStartItem> preparedItems) {
        return preparedItems.stream()
                .map(item -> item.context)
                .collect(Collectors.toList());
    }

    private boolean hasFailure(List<AlarmInsertConsumeResult> results) {
        return results.stream().anyMatch(result -> result == AlarmInsertConsumeResult.FAIL);
    }

    private Map<AlarmInsertConsumeResult, Integer> resultCounts(List<AlarmInsertConsumeResult> results) {
        Map<AlarmInsertConsumeResult, Integer> counts = new EnumMap<>(AlarmInsertConsumeResult.class);
        for (AlarmInsertConsumeResult result : results) {
            AlarmInsertConsumeResult key = result == null ? AlarmInsertConsumeResult.FAIL : result;
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        return counts;
    }

    private List<String> sampleAlarmCids(List<JSONObject> rawDataList) {
        return rawDataList.stream()
                .map(this::alarmCid)
                .limit(5)
                .collect(Collectors.toList());
    }

    private List<String> samplePreparedAlarmCids(List<PreparedStartItem> preparedItems) {
        return preparedItems.stream()
                .map(item -> alarmCid(item.rawData))
                .limit(5)
                .collect(Collectors.toList());
    }

    private String alarmCid(JSONObject rawData) {
        return rawData == null ? null : rawData.getString("alarmId");
    }

    private static final class PreparedStartItem {
        /** 原始 MQ batch 中的下标，用于把业务结果回填到对应 deliveryTag。 */
        private final int index;
        private final JSONObject rawData;
        private final AlarmServiceImpl.AlarmInsertContext context;

        private PreparedStartItem(int index, JSONObject rawData, AlarmServiceImpl.AlarmInsertContext context) {
            this.index = index;
            this.rawData = rawData;
            this.context = context;
        }
    }
}
