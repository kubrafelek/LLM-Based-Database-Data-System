package com.llmdiscovery.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

record BasicCredentials(String username, String password) {

    /** Parses an "Authorization: Basic base64(user:password)" header; empty when absent or malformed. */
    static Optional<BasicCredentials> parse(String header) {
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return Optional.empty();
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6).strip()), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                return Optional.empty();
            }
            return Optional.of(new BasicCredentials(decoded.substring(0, colon), decoded.substring(colon + 1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
