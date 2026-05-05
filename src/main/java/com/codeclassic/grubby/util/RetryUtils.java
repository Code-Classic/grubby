package com.codeclassic.grubby.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Simple retry helper with exponential backoff and jitter.
 *
 * Improvements:
 * - Uses ThreadLocalRandom instead of a shared Random (thread-safe, no contention)
 * - InterruptedException correctly restores interrupt status before re-throwing
 * - Logs each retry attempt with delay information
 */
public final class RetryUtils {

    private static final Logger log = LoggerFactory.getLogger(RetryUtils.class);

    private RetryUtils() {}

    public static <T> T withRetry(Callable<T> op,
                                  int attempts,
                                  Duration initialDelay,
                                  double backoff,
                                  Predicate<Throwable> retryIf) throws Exception {
        if (attempts <= 0) attempts = 1;
        if (initialDelay == null || initialDelay.isNegative() || initialDelay.isZero()) {
            initialDelay = Duration.ofMillis(200);
        }
        if (backoff < 1.0) backoff = 1.0;

        Duration delay = initialDelay;
        int tryNo = 0;

        while (true) {
            tryNo++;
            try {
                return op.call();
            } catch (Throwable ex) {
                boolean shouldRetry = tryNo < attempts && (retryIf == null || retryIf.test(ex));
                if (!shouldRetry) {
                    if (ex instanceof Exception e) throw e;
                    throw new RuntimeException(ex);
                }
                // Jitter ±20% of base delay using ThreadLocalRandom (no shared state)
                long baseMs = delay.toMillis();
                long jitter = (long) (baseMs * (0.4 * ThreadLocalRandom.current().nextDouble() - 0.2));
                long sleepMs = Math.max(50, baseMs + jitter);
                log.warn("Attempt {}/{} failed ({}). Retrying in {}ms...",
                        tryNo, attempts, ex.getClass().getSimpleName(), sleepMs);
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    // Restore interrupt status before propagating
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Retry interrupted", ie);
                }
                delay = Duration.ofMillis((long) Math.min(30_000L, baseMs * backoff));
            }
        }
    }
}
