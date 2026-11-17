package com.jobradar.scan;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Spaces requests to the same host. Each caller reserves the next free slot, then sleeps until it arrives. */
@Component
public class DomainRateLimiter {
    private final Map<String, Long> nextSlotNanos = new ConcurrentHashMap<>();
    private volatile long intervalNanos = TimeUnit.SECONDS.toNanos(1);

    public void setRatePerSecond(double rate) {
        intervalNanos = (long) (TimeUnit.SECONDS.toNanos(1) / Math.max(rate, 0.01));
    }

    public void acquire(String host) {
        long interval = intervalNanos;
        long slot = nextSlotNanos.compute(host, (h, previous) -> {
            long now = System.nanoTime();
            return (previous == null || previous - now < 0 ? now : previous) + interval;
        }) - interval;
        long waitNanos = slot - System.nanoTime();
        if (waitNanos > 0) {
            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FetchException("Interrupted while waiting for rate limit", e);
            }
        }
    }
}
