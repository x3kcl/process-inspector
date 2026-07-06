package io.inspector.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One Clock behind every age computation (R-TEST-07) so tests can pin time.
 * All BFF time math is UTC; ages are floored at 0 against engine clock skew.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
