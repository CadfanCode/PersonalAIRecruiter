package app.jobpilot.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class ClockConfig {

    /** Injected wherever "now" matters so tests can fix the date. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
