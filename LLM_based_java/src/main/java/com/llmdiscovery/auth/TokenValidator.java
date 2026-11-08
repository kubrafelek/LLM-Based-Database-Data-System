package com.llmdiscovery.auth;

import org.springframework.stereotype.Component;

/**
 * Whether a correctly signed token is still valid: its user exists, its "ver" is the user's current
 * token_version (password change / logout-all), and its jti was not revoked by /auth/logout.
 * Same rules as the Python get_token_validator.
 */
@Component
public class TokenValidator {

    private final UserRepository users;
    private final RevokedTokenRepository revokedTokens;

    public TokenValidator(UserRepository users, RevokedTokenRepository revokedTokens) {
        this.users = users;
        this.revokedTokens = revokedTokens;
    }

    public boolean isValid(JwtService.TokenClaims claims) {
        Integer current = users.findTokenVersion(claims.subject()).orElse(null);
        if (current == null || current != claims.tokenVersion()) {
            return false;
        }
        return claims.tokenId() == null || !revokedTokens.isRevoked(claims.tokenId());
    }
}
