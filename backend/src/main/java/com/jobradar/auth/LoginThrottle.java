package com.jobradar.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Slows down password guessing: after {@link #MAX_FAILURES} failed logins for one email, or
 * {@link #MAX_FAILURES_PER_ADDRESS} from one client address, further attempts are refused until {@link #WINDOW} has passed since the first failure.
 * A successful login clears the email's counter. State is in memory, so it resets on restart.
 */
@Component
public class LoginThrottle {
    static final int MAX_FAILURES = 5;
    static final int MAX_FAILURES_PER_ADDRESS = 20;
    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_TRACKED = 10_000;

    private record Failures(int count, Instant windowStart) {}

    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Seconds until another attempt is allowed, or 0 if the attempt may go ahead. */
    public long retryAfterSeconds(String email, String clientAddress) {
        return Math.max(wait(emailKey(email), MAX_FAILURES), wait(addressKey(clientAddress), MAX_FAILURES_PER_ADDRESS));
    }

    public void recordFailure(String email, String clientAddress) {
        if (failures.size() > MAX_TRACKED) {
            Instant now = clock.instant();
            failures.values().removeIf(f -> f.windowStart().plus(WINDOW).isBefore(now));
        }
        bump(emailKey(email));
        bump(addressKey(clientAddress));
    }

    public void recordSuccess(String email) {
        failures.remove(emailKey(email));
    }

    private long wait(String key, int max) {
        Failures f = failures.get(key);
        if (f == null || f.count() < max) {
            return 0;
        }
        long seconds = Duration.between(clock.instant(), f.windowStart().plus(WINDOW)).toSeconds();
        if (seconds <= 0) {
            failures.remove(key, f);
            return 0;
        }
        return seconds;
    }

    private void bump(String key) {
        Instant now = clock.instant();
        failures.compute(key, (k, f) -> f == null || f.windowStart().plus(WINDOW).isBefore(now)
                ? new Failures(1, now)
                : new Failures(f.count() + 1, f.windowStart()));
    }

    private static String emailKey(String email) {
        return "e:" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    private static String addressKey(String address) {
        return "a:" + (address == null ? "" : address);
    }
}
