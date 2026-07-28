package dev.maxfastbuild.core.limit;

import java.time.Clock;

public final class TokenBucket {
    private final double capacity;
    private final double tokensPerMillis;
    private final Clock clock;
    private double tokens;
    private long lastRefill;

    public TokenBucket(int capacity, int refillTokens, long intervalMillis, Clock clock) {
        if (capacity < 1 || refillTokens < 1 || intervalMillis < 1) throw new IllegalArgumentException("Rate values must be positive");
        this.capacity = capacity;
        this.tokens = capacity;
        this.tokensPerMillis = (double) refillTokens / intervalMillis;
        this.clock = clock;
        this.lastRefill = clock.millis();
    }

    public synchronized boolean tryAcquire() {
        long now = clock.millis();
        tokens = Math.min(capacity, tokens + (now - lastRefill) * tokensPerMillis);
        lastRefill = now;
        if (tokens < 1) return false;
        tokens--;
        return true;
    }
}
