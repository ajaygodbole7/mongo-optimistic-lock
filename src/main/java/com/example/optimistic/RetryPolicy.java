package com.example.optimistic;

import com.example.optimistic.exception.RetriesExhaustedException;
import java.time.Duration;

/**
 * The retry budget for system read-modify-write ({@link OptimisticLockingWriter#updateWithRetry}). It uses exponential
 * backoff with full jitter.
 *
 * <h2>When it applies</h2>
 * Only a version conflict causes a retry: a different writer changed the document between the read and the write.
 * Each retry reads the document again and calculates the change again from the new state. A transient failure
 * (network, election, timeout) does not cause a retry; it stops the method. User writes never use this class.
 *
 * <h2>How long the writer waits</h2>
 * After failed attempt {@code n}, the writer waits for a random time in
 * {@code [0, min(maxDelay, baseDelay * 2^(n-1))]}:
 * <ul>
 *   <li><b>Exponential:</b> the upper limit doubles after each failed attempt. Each conflict shows that the document
 *       is busy. Thus, the writer waits longer before it tries again, and gives the other writers time to finish.</li>
 *   <li><b>Cap ({@code maxDelay}):</b> the upper limit does not grow past {@code maxDelay}, so that one attempt never
 *       waits too long.</li>
 *   <li><b>Full jitter:</b> the wait is a random value between 0 and the upper limit, not the upper limit itself.
 *       Pods that conflict on the same document at the same time would otherwise wait the same time, retry at the same
 *       time, and conflict again ("lockstep"). The random wait spreads them out, so that usually one of them wins
 *       on the next attempt. Full jitter gives the fewest repeated collisions for this kind of contention.</li>
 * </ul>
 *
 * <h2>The default policy</h2>
 * {@link #defaults()} is 8 attempts, a 5 ms base and a 100 ms cap. The 8 attempts have 7 waits between them:
 * <pre>
 *   after attempt     1    2    3    4    5    6    7
 *   upper limit (ms)  5   10   20   40   80  100  100   (the cap applies from wait 6)
 * </pre>
 * The longest possible total wait is 355 ms. Because each wait is random, the average total is approximately half
 * of that. When all 8 attempts conflict, the method throws {@link RetriesExhaustedException}: the system job must
 * requeue the work.
 *
 * <h2>Tuning</h2>
 * For a document that many system jobs write to at the same time, increase {@code maxAttempts}, so that fewer jobs
 * exhaust their attempts. Increase {@code baseDelay} if each write is slow (for example, a remote primary). Keep the
 * cap small: a long cap makes one job wait, but it does not make the document less busy.
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    /** The longest delay. A wait is computed in nanoseconds, and a much longer one overflows. */
    private static final Duration MAX_DELAY = Duration.ofDays(365);

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (baseDelay.isNegative() || maxDelay.isNegative()) {
            throw new IllegalArgumentException("delays must not be negative");
        }
        if (baseDelay.compareTo(MAX_DELAY) > 0 || maxDelay.compareTo(MAX_DELAY) > 0) {
            throw new IllegalArgumentException("delays must not be longer than " + MAX_DELAY);
        }
    }

    /** 8 attempts, 5 ms base, 100 ms cap: the upper limits are 5, 10, 20, 40, 80, 100 and 100 ms. */
    public static RetryPolicy defaults() {
        return new RetryPolicy(8, Duration.ofMillis(5), Duration.ofMillis(100));
    }

    /**
     * The upper limit of the wait after failed attempt {@code attempt} (1-based): {@code baseDelay * 2^(attempt-1)},
     * but not more than {@code maxDelay}. The writer then waits for a random time between 0 and this value.
     */
    Duration maxSleep(int attempt) {
        // 1L << (attempt - 1) doubles the base for each failed attempt. The shift stops at 20, so that a policy with
        // many attempts cannot overflow; the cap below applies long before that.
        Duration exponential = baseDelay.multipliedBy(1L << Math.min(attempt - 1, 20));
        return exponential.compareTo(maxDelay) < 0 ? exponential : maxDelay;
    }
}
