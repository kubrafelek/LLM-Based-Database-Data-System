package com.llmdiscovery.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.llmdiscovery.common.AppException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Same cases as the Python tests/unit/test_rate_limit.py. */
class LoginAttemptLimiterTest {

    double now = 1000;

    LoginAttemptLimiter limiter(int perUser, int perIp) {
        return new LoginAttemptLimiter(perUser, perIp, 60, () -> now);
    }

    static void fail(LoginAttemptLimiter lim, int times, String ip, String user) {
        for (int i = 0; i < times; i++) {
            lim.recordFailure(ip, user);
        }
    }

    @AfterEach
    void restoreKeyLimit() {
        LoginAttemptLimiter.maxTrackedKeys = 100_000;
    }

    @Test
    void allowsAttemptsBelowTheLimit() {
        LoginAttemptLimiter lim = limiter(3, 10);
        fail(lim, 2, "1.2.3.4", "admin");
        assertThatCode(() -> lim.check("1.2.3.4", "admin")).doesNotThrowAnyException();
    }

    @Test
    void blocksAtTheLimitWithRetryAfter() {
        LoginAttemptLimiter lim = limiter(3, 10);
        fail(lim, 3, "1.2.3.4", "admin");
        now += 15;
        assertThatThrownBy(() -> lim.check("1.2.3.4", "admin"))
                .isInstanceOfSatisfying(AppException.TooManyAttempts.class, e -> {
                    assertThat(e.status().value()).isEqualTo(429);
                    assertThat(e.retryAfterSeconds()).isEqualTo(45);
                });
    }

    @Test
    void blockExpiresWithTheWindow() {
        LoginAttemptLimiter lim = limiter(3, 10);
        fail(lim, 3, "1.2.3.4", "admin");
        now += 60;
        assertThatCode(() -> lim.check("1.2.3.4", "admin")).doesNotThrowAnyException();
    }

    @Test
    void usernameIsCaseInsensitive() {
        LoginAttemptLimiter lim = limiter(3, 10);
        fail(lim, 3, "1.2.3.4", "Admin");
        assertThatThrownBy(() -> lim.check("1.2.3.4", "ADMIN")).isInstanceOf(AppException.TooManyAttempts.class);
    }

    @Test
    void otherIpsAreNotLockedOut() {
        LoginAttemptLimiter lim = limiter(3, 10);
        fail(lim, 3, "6.6.6.6", "admin");
        assertThatCode(() -> lim.check("1.2.3.4", "admin")).doesNotThrowAnyException();
    }

    @Test
    void ipLimitCoversManyUsernames() {
        LoginAttemptLimiter lim = limiter(100, 5);
        for (int i = 0; i < 5; i++) {
            lim.recordFailure("6.6.6.6", "user" + i);
        }
        assertThatThrownBy(() -> lim.check("6.6.6.6", "someone-new")).isInstanceOf(AppException.TooManyAttempts.class);
    }

    @Test
    void successResetsTheAccountCountButNotTheIpCount() {
        LoginAttemptLimiter lim = limiter(3, 5);
        fail(lim, 2, "1.2.3.4", "admin");
        lim.recordSuccess("1.2.3.4", "admin");
        fail(lim, 2, "1.2.3.4", "admin");
        assertThatCode(() -> lim.check("1.2.3.4", "admin")).doesNotThrowAnyException();
        lim.recordFailure("1.2.3.4", "other");
        assertThatThrownBy(() -> lim.check("1.2.3.4", "other")).isInstanceOf(AppException.TooManyAttempts.class);
    }

    @Test
    void zeroDisablesALimit() {
        LoginAttemptLimiter lim = limiter(0, 0);
        fail(lim, 50, "1.2.3.4", "admin");
        assertThatCode(() -> lim.check("1.2.3.4", "admin")).doesNotThrowAnyException();
    }

    @Test
    void trackedKeysAreBounded() {
        LoginAttemptLimiter.maxTrackedKeys = 10;
        LoginAttemptLimiter lim = limiter(3, 10);
        for (int i = 0; i < 50; i++) {
            lim.recordFailure("10.0.0." + i, "admin");
        }
        assertThat(lim.trackedKeys()).isLessThanOrEqualTo(10);
    }
}
