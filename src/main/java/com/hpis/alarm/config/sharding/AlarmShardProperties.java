package com.hpis.alarm.config.sharding;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 报警分片配置。
 *
 * <p>本次改造保留 hpis-alarm 现有 ShardingSphere 4.1.1 版本，不引入 5.x 的
 * CLASS_BASED 配置模型。所有新规则都挂在 alarm.sharding 前缀下，便于在 Nacos
 * 中灰度开启；未设置 enabled=true 时，不会创建本模块的 Java API 分片数据源，
 * 这样可以避免和旧的 YAML inline 分片规则同时生效。</p>
 */
@Data
@ConfigurationProperties(prefix = "alarm.sharding")
public class AlarmShardProperties {

    /**
     * 是否启用 hpis-alarm 新的“时间 + 容量”分片。
     *
     * <p>默认关闭，是为了让代码可以先合入，再由配置中心切换流量。正式启用前，
     * 需要移除或关闭旧的 alarm_id % 5 inline 规则。</p>
     */
    private boolean enabled = false;

    /**
     * 单个月内单个子表最大行数。
     *
     * <p>达到该阈值后，同一个月份会继续创建下一个子表，例如
     * alarm_202604_00 满后切到 alarm_202604_01。</p>
     */
    private long maxRowsPerSlice = 5_000_000L;

    /**
     * 每次从 alarm_shard_slice 预占的行号段大小。
     *
     * <p>行业常见的 Leaf/Segment 思路是把逐条数据库加锁改为批量预占号段：本机拿到
     * [startRowNo, endRowNo) 后在内存中分配 rowNo，耗尽后再访问数据库。这样可以显著降低
     * select for update 和 current_rows 更新频率。异常退出时可能留下少量未实际写入的空洞，
     * 但不影响路由和主键唯一性。</p>
     */
    private int allocationSegmentSize = 1000;

    /**
     * 预创建未来月份数量。
     *
     * <p>只预建每个月的 00 号子表，容量切片仍在写入时按阈值动态创建。</p>
     */
    private int preCreateMonths = 0;

    /**
     * Deprecated compatibility switch. New routing no longer registers or queries legacy 0..4 tables.
     */
    @Deprecated
    private boolean includeLegacyTables = false;

    /**
     * Actual data node registration scope for ShardingSphere 4.1.1.
     *
     * <p>Runtime routing still only queries physical tables that really exist. These values only
     * pre-register possible current/next-month tables so later on-demand table creation does not
     * hit "Actual table is not in table config".</p>
     */
    private ActualDataNodes actualDataNodes = new ActualDataNodes();

    /**
     * Monthly rule refresh configuration.
     *
     * <p>Refresh rebuilds a new ShardingSphere datasource and swaps it through a stable proxy.
     * If rebuild fails, the old datasource remains active.</p>
     */
    private RuleRefresh ruleRefresh = new RuleRefresh();

    /**
     * 外部 cid 热点索引生命周期配置。
     */
    private CidIndex cidIndex = new CidIndex();

    /**
     * 内部 alarm_id 编码配置。
     */
    private Id id = new Id();

    @Data
    public static class CidIndex {

        /**
         * 未关闭报警在热点表保留的小时数，超过后转入 stale 表。
         */
        private int hotHours = 24;

        /**
         * 滞留报警保留天数，超过后先业务超时关闭，再删除路由。
         */
        private int staleExpireDays = 30;

        /**
         * 已关闭路由清理批量大小。
         */
        private int cleanupBatchSize = 1000;

        /**
         * hot 转 stale 每批处理数量。
         */
        private int transferBatchSize = 1000;

        /**
         * 低流量任务 cron。默认凌晨执行，避免和高峰写入竞争。
         */
        private String cleanupCron = "0 0 2 * * ?";

        private String transferCron = "0 10 2 * * ?";

        private String staleTimeoutCron = "0 20 2 * * ?";

        /**
         * 是否打印 cid 路由生命周期定时任务的普通完成日志。
         *
         * <p>默认关闭，避免低流量维护任务在生产长期刷屏；需要观察清理、hot 转 stale、
         * stale 超时关闭进度时，可在 Nacos 临时打开。</p>
         */
        private boolean logEnabled = false;
    }

    @Data
    public static class Id {

        /**
         * 内部 alarm_id workerId。
         *
         * <p>v2 ID 中 workerId 占 8 位，有效范围是 0..255。单实例可以使用默认值 0；
         * 多实例部署时必须在 Nacos 为每个实例配置不同值，避免不同实例预占同一 rowNo
         * 时生成相同 alarm_id。</p>
         */
        private int workerId = 0;
    }

    @Data
    public static class ActualDataNodes {

        /**
         * Current month pre-registers all slice numbers by default.
         */
        private int currentMonthMaxSliceNo = 255;

        /**
         * Next month pre-registers 00..09 by default so the month boundary has a small warm range.
         */
        private int nextMonthMaxSliceNo = 9;

        public int safeCurrentMonthMaxSliceNo() {
            return safeSliceNo(currentMonthMaxSliceNo, 255);
        }

        public int safeNextMonthMaxSliceNo() {
            return safeSliceNo(nextMonthMaxSliceNo, 9);
        }

        private int safeSliceNo(int value, int defaultValue) {
            if (value < 0) {
                return defaultValue;
            }
            return Math.min(value, 255);
        }
    }

    @Data
    public static class RuleRefresh {

        /**
         * Enable monthly ShardingSphere datasource rebuild and proxy swap.
         */
        private boolean enabled = true;

        /**
         * Default to 00:05 on the first day of each month.
         */
        private String cron = "0 5 0 1 * ?";

        /**
         * Delay closing old datasource so in-flight transactions can finish.
         */
        private long closeOldDelayMs = 300_000L;

        /**
         * Trigger a debounced refresh after runtime table creation.
         */
        private boolean activeOnTableCreatedEnabled = true;

        /**
         * Debounce active table-created refresh requests.
         */
        private long activeOnTableCreatedDebounceMs = 30_000L;

        /**
         * Run active table-created refresh outside the caller thread by default.
         */
        private boolean activeOnTableCreatedAsync = true;

        /**
         * Enable month-end pre-create of the next month's first physical slice.
         */
        private boolean monthEndPreCreateEnabled = true;

        /**
         * Run daily near month-end; the job checks the actual last day in code.
         */
        private String monthEndPreCreateCron = "0 50 23 * * ?";

        /**
         * Keep the daily cron portable by checking last day in Java.
         */
        private boolean monthEndPreCreateCheckLastDay = true;

        /**
         * Physical slices to pre-create for next month. Default 0 means only 00.
         */
        private int monthEndPreCreateNextMonthMaxSliceNo = 0;

        public long safeCloseOldDelayMs() {
            return Math.max(0L, closeOldDelayMs);
        }

        public long safeActiveOnTableCreatedDebounceMs() {
            return Math.max(0L, activeOnTableCreatedDebounceMs);
        }

        public int safeMonthEndPreCreateNextMonthMaxSliceNo() {
            if (monthEndPreCreateNextMonthMaxSliceNo < 0) {
                return 0;
            }
            return Math.min(monthEndPreCreateNextMonthMaxSliceNo, 255);
        }
    }
}
