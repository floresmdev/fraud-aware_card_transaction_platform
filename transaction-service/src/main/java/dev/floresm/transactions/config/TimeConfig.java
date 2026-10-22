package dev.floresm.transactions.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Provides the system {@link Clock} as a Spring bean.
 *
 * <p>Code that calls {@code Instant.now()} or {@code LocalDate.now()} directly is hard to test: you
 * cannot make "today" be the day after a card expires. By injecting a Clock, production uses the
 * real time and tests pass {@code Clock.fixed(...)}. This is <b>dependency injection</b> applied
 * to time.
 *
 * <p>UTC everywhere on the server; time zones are a presentation concern for the client.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
