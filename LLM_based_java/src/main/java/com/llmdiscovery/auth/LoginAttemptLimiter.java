package com.llmdiscovery.auth;

import com.llmdiscovery.common.AppException;
import com.llmdiscovery.common.AppProperties;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Failed-login throttling against password guessing (/auth and Basic credentials), same rules as
 * the Python service (app/core/rate_limit.py).
 *
 * <p>Failures are counted in a sliding window under two keys: client IP + username (guessing one
 * account) and client IP alone (spraying many usernames). Keying on the IP as well means a stranger
 * cannot lock the admin out from their own address. State is in memory, per process.
 */
@Component
public class LoginAttemptLimiter {

    /** Upper bound on tracked keys, so a flood of distinct usernames/IPs cannot grow memory without limit. */
    static int maxTrackedKeys = 100_000;

    private final int maxPerUser;
    private final int maxPerIp;
    private final double windowSeconds;
    private final DoubleSupplier clock;
    private final Map<String, ArrayDeque<Double>> failures = new LinkedHashMap<>();

    @Autowired
    public LoginAttemptLimiter(AppProperties properties) {
        this(properties.getAuthMaxFailedAttempts(), properties.getAuthMaxFailedAttemptsPerIp(),
                properties.getAuthFailureWindowSeconds(), () -> System.nanoTime() / 1e9);
    }

    LoginAttemptLimiter(int maxPerUser, int maxPerIp, double windowSeconds, DoubleSupplier clock) {
        this.maxPerUser = maxPerUser;
        this.maxPerIp = maxPerIp;
        this.windowSeconds = windowSeconds;
        this.clock = clock;
    }

    private static String[] keys(String clientIp, String username) {
        return new String[] {"user:" + clientIp + ":" + username.toLowerCase(Locale.ROOT), "ip:" + clientIp};
    }

    private ArrayDeque<Double> recent(String key, double now) {
        ArrayDeque<Double> list = failures.get(key);
        if (list == null) {
            return new ArrayDeque<>();
        }
        while (!list.isEmpty() && list.peekFirst() <= now - windowSeconds) {
            list.pollFirst();
        }
        if (list.isEmpty()) {
            failures.remove(key);
        }
        return list;
    }

    /** Throws TooManyAttempts (429 + Retry-After) while either key is over its limit. */
    public synchronized void check(String clientIp, String username) {
        double now = clock.getAsDouble();
        String[] keys = keys(clientIp, username);
        int[] limits = {maxPerUser, maxPerIp};
        double retryAfter = 0;
        for (int i = 0; i < keys.length; i++) {
            if (limits[i] <= 0) {
                continue;
            }
            ArrayDeque<Double> list = recent(keys[i], now);
            if (list.size() >= limits[i]) {
                // Free again once the oldest failure that keeps the count at the limit leaves the window.
                double pivot = list.stream().skip(list.size() - limits[i]).findFirst().orElseThrow();
                retryAfter = Math.max(retryAfter, pivot + windowSeconds - now);
            }
        }
        if (retryAfter > 0) {
            throw new AppException.TooManyAttempts(Math.max(1, (long) Math.ceil(retryAfter)));
        }
    }

    public synchronized void recordFailure(String clientIp, String username) {
        double now = clock.getAsDouble();
        String[] keys = keys(clientIp, username);
        if (failures.size() + keys.length > maxTrackedKeys) {
            evict(now, keys.length);
        }
        for (String key : keys) {
            recent(key, now);
            failures.computeIfAbsent(key, k -> new ArrayDeque<>()).addLast(now);
        }
    }

    /** A correct password clears that account's count for this IP (not the IP-wide count). */
    public synchronized void recordSuccess(String clientIp, String username) {
        failures.remove(keys(clientIp, username)[0]);
    }

    /** Forget all failures (tests). */
    public synchronized void clear() {
        failures.clear();
    }

    synchronized int trackedKeys() {
        return failures.size();
    }

    private void evict(double now, int room) {
        for (String key : failures.keySet().toArray(String[]::new)) {
            recent(key, now);
        }
        // Still full: drop the oldest-inserted keys.
        int overflow = failures.size() + room - maxTrackedKeys;
        Iterator<String> it = failures.keySet().iterator();
        while (overflow-- > 0 && it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}
