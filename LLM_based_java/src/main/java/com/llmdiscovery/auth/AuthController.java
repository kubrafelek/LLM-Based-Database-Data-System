package com.llmdiscovery.auth;

import com.llmdiscovery.common.AppException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Optional;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "auth")
public class AuthController {

    public record LoginRequest(
            @NotNull @Size(min = 1, max = 150) String username,
            @NotNull @Size(min = 1, max = 1024) String password) {}

    public record TokenResponse(String accessToken, String tokenType, long expiresIn) {}

    private final AuthService authService;
    private final JwtService jwt;
    private final RevokedTokenRepository revokedTokens;
    private final TokenValidator tokenValidator;
    private final UserRepository users;

    public AuthController(AuthService authService, JwtService jwt, RevokedTokenRepository revokedTokens,
            TokenValidator tokenValidator, UserRepository users) {
        this.authService = authService;
        this.jwt = jwt;
        this.revokedTokens = revokedTokens;
        this.tokenValidator = tokenValidator;
        this.users = users;
    }

    /** /auth is the case study PDF path; /api/v1/auth the documented one. */
    @Operation(summary = "Exchange credentials (JSON body or HTTP Basic) for a short-lived Bearer token")
    @PostMapping({"/api/v1/auth", "/auth"})
    public TokenResponse issueToken(
            @Valid @RequestBody(required = false) LoginRequest body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request) {
        String username;
        String password;
        if (body != null) {
            username = body.username();
            password = body.password();
        } else {
            Optional<BasicCredentials> basic = BasicCredentials.parse(authorization);
            if (basic.isEmpty()) {
                throw new AppException.Unauthorized(
                        "Provide credentials as a JSON body or with HTTP Basic authentication", "Basic");
            }
            username = basic.get().username();
            password = basic.get().password();
        }
        User user = authService.authenticateThrottled(username, password, request.getRemoteAddr())
                .orElseThrow(() -> new AppException.Unauthorized("Invalid credentials", "Basic"));
        JwtService.Token token = jwt.create(username, user.getTokenVersion());
        return new TokenResponse(token.value(), "bearer", token.expiresIn());
    }

    /** Revoke the Bearer token sent with this request; it is rejected from now on. */
    @Operation(summary = "Revoke the Bearer token sent with this request")
    @PostMapping({"/api/v1/auth/logout", "/auth/logout"})
    public ResponseEntity<Void> revokeToken(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        String header = authorization == null ? "" : authorization;
        if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            if (BasicCredentials.parse(header).isPresent()) {
                throw new AppException.BadRequest("Only Bearer tokens can be revoked; Basic credentials have no session");
            }
            throw new AppException.Unauthorized("Not authenticated", "Bearer");
        }
        JwtService.TokenClaims claims;
        try {
            claims = jwt.decodeClaims(header.substring(7).strip());
        } catch (JwtException | IllegalArgumentException e) {
            throw new AppException.Unauthorized("Invalid or expired token", "Bearer");
        }
        if (claims.tokenId() == null) {
            throw new AppException.BadRequest("This token has no ID and cannot be revoked");
        }
        if (!tokenValidator.isValid(claims)) {
            throw new AppException.Unauthorized("Invalid or expired token", "Bearer");
        }
        revokedTokens.revoke(claims.tokenId(), claims.expiresAt());
        return ResponseEntity.noContent().build();
    }

    /**
     * Revoke every token of the calling user (Bearer token or Basic credentials, checked by
     * TokenAuthenticationFilter). With the password it works even without a valid token.
     */
    @Operation(summary = "Revoke every token of the calling user")
    @PostMapping({"/api/v1/auth/logout-all", "/auth/logout-all"})
    public ResponseEntity<Void> revokeAllTokens(Authentication authentication) {
        users.bumpTokenVersion(authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
