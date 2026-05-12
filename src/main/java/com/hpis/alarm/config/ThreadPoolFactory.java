package com.hpis.alarm.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * @version 1.0
 * @Description 线程池工厂
 */
@Configuration
@EnableAsync
@Slf4j
public class ThreadPoolFactory implements AsyncConfigurer {

    @Autowired
    private ThreadPoolConfig threadPoolConfig;


    /**
     * 通过getAsyncExecutor方法配置ThreadPoolTaskExecutor,获得一个基于线程池TaskExecutor
     *
     * @return
     */
    @Override
    public Executor getAsyncExecutor() {
        return createPool();
    }

    @Bean
    public
    ThreadPoolTaskExecutor createPool(){
        /**
         * int corePoolSize:线程池维护线程的最小数量
         * int maximumPoolSize:线程池维护线程的最大数量，线程池中允许的最大线程数，线程池中的当前线程数目不会超过该值。
         * 如果队列中任务已满，并且当前线程个数小于maximumPoolSize，那么会创建新的线程来执行任务。
         * long keepAliveTime:空闲线程的存活时间
         * TimeUnitunit:时间单位，现由纳秒，微秒，毫秒，秒
         * BlockingQueue workQueue:持有等待执行的任务队列，一个阻塞队列，用来存储等待执行的任务，当线程池中的线程数超过它的corePoolSize的时候，
         * 线程会进入阻塞队列进行阻塞等待
         * RejectedExecutionHandler handler 线程池的拒绝策略，是指当任务添加到线程池中被拒绝，而采取的处理措施。
         * 当任务添加到线程池中之所以被拒绝，可能是由于：第一，线程池异常关闭。第二，任务数量超过线程池的最大限制。
         */
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        //核心线程数
        pool.setCorePoolSize(threadPoolConfig.getCorePoolSize());
        //最大线程数
        pool.setMaxPoolSize(threadPoolConfig.getMaximumPoolSize());
        //线程队列大小
        pool.setQueueCapacity(threadPoolConfig.getWorkQueueSize());
        //线程名字前缀
        pool.setThreadNamePrefix("hpisTaskExecutor-");
        //线程初始化
        pool.initialize();
        return pool;
    }




    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error(String.format("执行异步任务'%s'", method), ex);

    }

}

