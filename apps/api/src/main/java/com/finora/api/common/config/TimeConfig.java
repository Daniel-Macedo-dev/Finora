package com.finora.api.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Single injectable time source for business logic. Recurring processing and
 * forecasting must never call {@code LocalDate.now()} directly — tests replace
 * this bean with a fixed clock to make date behavior deterministic.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock businessClock() {
        return Clock.systemDefaultZone();
    }
}
