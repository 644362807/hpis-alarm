package com.hpis.alarm.service.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 报警模块统一批量切块工具。
 *
 * <p>批量 SQL 的目标是减少数据库往返，不是把积压量直接拼成超长 SQL。
 * 所有 IN、CASE WHEN、多 values INSERT 和 JDBC batch 都必须经过本工具限制，
 * 防止配置中心误配后把 2000 条甚至更多记录塞进一次数据库操作。</p>
 */
public final class AlarmBatchChunker {

    public static final int DEFAULT_BATCH_SIZE = 500;

    public static final int MAX_BATCH_SIZE = 500;

    private AlarmBatchChunker() {
    }

    /**
     * 将配置值限制在 1..500。
     *
     * <p>非法值回到默认 500；大于 500 时直接钳制，保证调用方即使忘记二次校验，
     * 单条 SQL 仍不会突破模块约定的硬边界。</p>
     */
    public static int safeBatchSize(int configuredSize) {
        if (configuredSize <= 0) {
            return DEFAULT_BATCH_SIZE;
        }
        return Math.min(configuredSize, MAX_BATCH_SIZE);
    }

    public static <T> List<List<T>> chunk(List<T> values, int configuredSize) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        int batchSize = safeBatchSize(configuredSize);
        List<List<T>> chunks = new ArrayList<>();
        for (int start = 0; start < values.size(); start += batchSize) {
            chunks.add(values.subList(start, Math.min(start + batchSize, values.size())));
        }
        return chunks;
    }
}
