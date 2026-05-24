package com.hpis.alarm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 报警服务内部链路测试开关。
 *
 * <p>该配置只用于本地或压测环境验证 hpis-alarm 自身的 MQ、组装、分片、数据库入库和 stop worker 逻辑。
 * 默认关闭，生产不配置时完全保持旧行为；只有显式设置
 * {@code alarm.internal-test.remote-call-stub-enabled=true} 时，才会把 Feign、WebSocket、文件服务等跨服务调用
 * 截断为日志，不真正请求外部服务。这样可以在 hpis-device、hpis-push、hpis-tmAnalysis 等服务未启动时，
 * 单独压测 alarm 内部链路。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "alarm.internal-test")
public class AlarmInternalTestProperties {

    /**
     * 远程调用截断开关。
     *
     * <p>false：生产默认值，照旧发起远程调用。
     * true：只记录调用目标和 payload，不请求外部服务；本开关会改变副作用执行结果，只允许本地测试或压测使用。</p>
     */
    private boolean remoteCallStubEnabled = false;
}
