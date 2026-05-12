package com.hpis.alarm.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 线程池配置类
 * dss
 */
@Component
@Data
@ConfigurationProperties(prefix="thread.pool")
public class ThreadPoolConfig {

    private Integer corePoolSize;

    private Integer maximumPoolSize;

    private Integer keepAliveTime;

    private Integer workQueueSize;

}

