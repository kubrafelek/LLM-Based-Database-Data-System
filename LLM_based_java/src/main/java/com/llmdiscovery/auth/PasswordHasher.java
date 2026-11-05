package com.llmdiscovery.auth;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id with argon2-cffi's defaults (salt 16, hash 32, parallelism 4, memory 64 MiB, 3 iterations),
 * so hashes written by the Python service verify here and vice versa.
 */
@Component
public class PasswordHasher {

    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 4, 65536, 3);
    // Checked when a username does not exist, so response time doesn't reveal it.
    private final String dummyHash;

    public PasswordHasher() {
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        this.dummyHash = hash(Base64.getUrlEncoder().encodeToString(random));
    }

    public String hash(String password) {
        return encoder.encode(password);
    }

    public boolean verify(String password, String hash) {
        try {
            return encoder.matches(password, hash);
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    public String dummyHash() {
        return dummyHash;
    }
}
