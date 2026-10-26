package se.caibirch.jobpilot.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** Injected wherever "now" matters so tests can fix the date. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
