package com.paridhi.notificationengine.config;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class ApplicationConfig {

    /**
     * Injected wherever the code needs the current time, so quiet hours, retention, and
     * timestamps can be driven from a fixed clock in tests instead of from the wall clock.
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
