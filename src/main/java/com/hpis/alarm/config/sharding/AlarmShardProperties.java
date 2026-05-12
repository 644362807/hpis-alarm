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
    private int preCreateMonths = 1;

    /**
     * 是否在迁移期保留 alarm_0..4 旧表兜底路由。
     *
     * <p>旧数据完成迁移并切换到 alarm_yyyyMM_nn 后，应改为 false，避免旧表参与查询。</p>
     */
    private boolean includeLegacyTables = true;

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
}
