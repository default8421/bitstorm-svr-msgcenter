package cn.bitoffer.msgcenter.core.retry;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Pure, side-effect free exponential backoff with symmetric jitter and a bounded attempt count.
 *
 * <p>The class is deliberately free of any framework or clock dependency so it can be exhaustively
 * unit tested. Callers that need randomness supply a {@code randomFraction} in {@code [0, 1)}; the
 * production overloads draw it from {@link ThreadLocalRandom}.
 *
 * @author LQH
 */
public final class BackoffPolicy {

    private final long baseMillis;
    private final long maxMillis;
    private final double multiplier;
    private final double jitterRatio;
    private final int maxAttempts;

    public BackoffPolicy(long baseMillis, long maxMillis, double multiplier, double jitterRatio,
            int maxAttempts) {
        if (baseMillis <= 0) {
            throw new IllegalArgumentException("baseMillis must be positive");
        }
        if (maxMillis < baseMillis) {
            throw new IllegalArgumentException("maxMillis must be >= baseMillis");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1.0");
        }
        if (jitterRatio < 0.0 || jitterRatio > 1.0) {
            throw new IllegalArgumentException("jitterRatio must be within [0, 1]");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.baseMillis = baseMillis;
        this.maxMillis = maxMillis;
        this.multiplier = multiplier;
        this.jitterRatio = jitterRatio;
        this.maxAttempts = maxAttempts;
    }

    /** Sensible default: 1s base, doubling, capped at 5 minutes, 20% jitter, 6 attempts. */
    public static BackoffPolicy defaultPolicy() {
        return new BackoffPolicy(1_000L, 300_000L, 2.0, 0.2, 6);
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** An attempt is exhausted once it has reached the configured cap. */
    public boolean isExhausted(int attempt) {
        return attempt >= maxAttempts;
    }

    /**
     * Deterministic delay for the given (1-based) attempt using the supplied jitter fraction.
     * A {@code randomFraction} of {@code 0.5} yields the raw (un-jittered) backoff value.
     */
    public long delayMillis(int attempt, double randomFraction) {
        int normalized = Math.max(attempt, 1);
        double raw = baseMillis * Math.pow(multiplier, normalized - 1.0);
        double capped = Math.min(raw, (double) maxMillis);
        double span = capped * jitterRatio;
        double low = capped - span;
        double value = low + randomFraction * (2.0 * span);
        long result = Math.round(value);
        if (result < 0L) {
            result = 0L;
        }
        if (result > maxMillis) {
            result = maxMillis;
        }
        return result;
    }

    public long delayMillis(int attempt) {
        return delayMillis(attempt, ThreadLocalRandom.current().nextDouble());
    }

    public Instant nextAttemptAt(Instant now, int attempt, double randomFraction) {
        return now.plusMillis(delayMillis(attempt, randomFraction));
    }

    public Instant nextAttemptAt(Instant now, int attempt) {
        return now.plusMillis(delayMillis(attempt));
    }
}
