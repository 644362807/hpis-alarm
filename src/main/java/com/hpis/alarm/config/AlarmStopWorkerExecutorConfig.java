package com.hpis.alarm.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 核心 stop 闭环专用线程池，不与 push、WebSocket 或通用异步任务复用。
 */
@Configuration
@ConditionalOnProperty(prefix = "alarm.sharding", name = "enabled", havingValue = "true")
public class AlarmStopWorkerExecutorConfig {

    @Bean(name = "alarmStopWorkerExecutor")
    public ThreadPoolTaskExecutor alarmStopWorkerExecutor(AlarmStopWorkerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.safeWorkerThreads());
        executor.setMaxPoolSize(properties.safeWorkerThreads());
        executor.setQueueCapacity(properties.safeMaxInFlightBatches());
        executor.setThreadNamePrefix("alarm-stop-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
