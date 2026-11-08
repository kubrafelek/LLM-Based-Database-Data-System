package com.llmdiscovery.auth;

import com.llmdiscovery.common.AppProperties;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final AppProperties properties;
    private final LoginAttemptLimiter limiter;

    public AuthService(UserRepository users, PasswordHasher hasher, AppProperties properties,
            LoginAttemptLimiter limiter) {
        this.users = users;
        this.hasher = hasher;
        this.properties = properties;
        this.limiter = limiter;
    }

    /**
     * authenticate() behind the failed-login limiter: throws TooManyAttempts (429) while blocked.
     * The check runs before the password hash, so a blocked client cannot keep testing passwords.
     */
    public boolean authenticateThrottled(String username, String password, String clientIp) {
        limiter.check(clientIp, username);
        if (authenticate(username, password)) {
            limiter.recordSuccess(clientIp, username);
            return true;
        }
        limiter.recordFailure(clientIp, username);
        // No username in the log: people sometimes type their password into that field.
        log.warn("Failed login from {}", clientIp);
        return false;
    }

    @Transactional(readOnly = true)
    public boolean authenticate(String username, String password) {
        Optional<User> user = users.findByUsername(username);
        // Verify against a dummy hash for unknown users so timing doesn't reveal which usernames exist.
        String hash = user.map(User::getPasswordHash).orElse(hasher.dummyHash());
        boolean valid = hasher.verify(password, hash);
        return user.isPresent() && valid;
    }

    /** Create the admin user from AUTH_USERNAME/AUTH_PASSWORD, or update its password if it changed. */
    @Transactional
    public void ensureAdminUser() {
        Optional<User> existing = users.findByUsername(properties.getAuthUsername());
        if (existing.isEmpty()) {
            users.save(new User(properties.getAuthUsername(), hasher.hash(properties.getAuthPassword())));
            log.info("Created admin user '{}'", properties.getAuthUsername());
        } else if (!hasher.verify(properties.getAuthPassword(), existing.get().getPasswordHash())) {
            existing.get().setPasswordHash(hasher.hash(properties.getAuthPassword()));
            log.info("Updated password of admin user '{}'", properties.getAuthUsername());
        }
    }
}
