package com.example.optimistic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** G11: the upper limits of the full-jitter waits. The writer waits for a random time between 0 and the limit. */
class RetryPolicyTest {

    @Test
    void g11_defaultLimitsDoubleThenStopAtTheCap() {
        RetryPolicy policy = RetryPolicy.defaults();

        assertThat(policy.maxAttempts()).isEqualTo(8);
        assertThat(IntStream.rangeClosed(1, 7).mapToObj(policy::maxSleep).map(Duration::toMillis))
                .containsExactly(5L, 10L, 20L, 40L, 80L, 100L, 100L);
    }

    @Test
    void g11_aLargeAttemptNumberDoesNotOverflow() {
        RetryPolicy policy = new RetryPolicy(1_000, Duration.ofMillis(5), Duration.ofDays(365));

        assertThat(policy.maxSleep(1_000)).as("the doubling stops after 20 steps")
                .isEqualTo(Duration.ofMillis(5L << 20));
    }

    @Test
    void g11_rejectsAPolicyThatCannotWork() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofMillis(5), Duration.ofMillis(100)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(8, Duration.ofMillis(-1), Duration.ofMillis(100)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(8, Duration.ofMillis(5), Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        // Longer than 365 days: the wait in nanoseconds would overflow (Long.MAX_VALUE nanoseconds is about 292 years).
        assertThatThrownBy(() -> new RetryPolicy(8, Duration.ofMillis(5), Duration.ofNanos(Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(8, Duration.ofDays(366), Duration.ofDays(365)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new RetryPolicy(8, Duration.ofDays(365), Duration.ofDays(365)).maxSleep(30))
                .isEqualTo(Duration.ofDays(365));
    }
}
