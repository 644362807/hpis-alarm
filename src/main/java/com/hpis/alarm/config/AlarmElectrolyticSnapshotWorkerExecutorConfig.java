package com.hpis.alarm.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 电解槽当前点位快照投影专用线程池。
 */
@Configuration
@ConditionalOnProperty(prefix = "alarm.sharding", name = "enabled", havingValue = "true")
public class AlarmElectrolyticSnapshotWorkerExecutorConfig {

    @Bean(name = "alarmElectrolyticSnapshotWorkerExecutor")
    public ThreadPoolTaskExecutor alarmElectrolyticSnapshotWorkerExecutor(
            AlarmElectrolyticSnapshotWorkerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.safeWorkerThreads());
        executor.setMaxPoolSize(properties.safeWorkerThreads());
        executor.setQueueCapacity(properties.safeMaxInFlightBatches());
        executor.setThreadNamePrefix("alarm-ec-snapshot-worker-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
