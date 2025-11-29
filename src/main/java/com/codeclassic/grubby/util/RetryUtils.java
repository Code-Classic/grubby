package com.codeclassic.grubby.util;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

/**
 * Simple retry helper with exponential backoff and jitter.
 */
public final class RetryUtils {

    private static final Random RANDOM = new Random();

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
                    if (ex instanceof Exception e) throw e; else throw new Exception(ex);
                }
                // jitter: +/- 20%
                long baseMs = delay.toMillis();
                long jitter = (long) (baseMs * (0.4 * RANDOM.nextDouble() - 0.2));
                long sleepMs = Math.max(50, baseMs + jitter);
                try { Thread.sleep(sleepMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                delay = Duration.ofMillis((long) Math.min(Integer.MAX_VALUE, baseMs * backoff));
            }
        }
    }
}
