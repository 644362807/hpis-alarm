package com.hpis.alarm.interceptor;

import com.hpis.alarm.config.AlarmSqlLogProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 使用 @Primary 覆盖 Bean
 * 使用自定义拦截器
 */
@Configuration
public class CustomInterceptorConfig {

    @Bean
    @Primary  // 优先使用此 Bean
    public CustomSqlLogInterceptor customSqlInterceptor(AlarmSqlLogProperties properties) {
        return new CustomSqlLogInterceptor(properties);
    }
}
