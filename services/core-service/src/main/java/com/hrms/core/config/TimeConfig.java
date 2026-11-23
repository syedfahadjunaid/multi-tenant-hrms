package com.hrms.core.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Code asks this Clock for "today" instead of calling LocalDate.now() directly, so a test can
 * swap in a fixed clock and check date rules (like "no annual leave in the past") reliably.
 */
@Configuration
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
