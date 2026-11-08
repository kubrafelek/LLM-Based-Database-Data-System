package com.llmdiscovery.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmdiscovery.common.AppException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates protected requests from a Bearer token issued by /auth, or from HTTP Basic
 * credentials checked against the users table. Leaves the request unauthenticated otherwise;
 * Spring Security's entry point then answers 401.
 */
class TokenAuthenticationFilter extends OncePerRequestFilter {

    static final String ERROR_ATTRIBUTE = TokenAuthenticationFilter.class.getName() + ".error";

    private final JwtService jwt;
    private final AuthService authService;
    private final RequestMatcher protectedPaths;

    TokenAuthenticationFilter(JwtService jwt, AuthService authService, RequestMatcher protectedPaths) {
        this.jwt = jwt;
        this.authService = authService;
        this.protectedPaths = protectedPaths;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only protected endpoints; /auth verifies credentials itself (no double Argon2 check).
        return !protectedPaths.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = Optional.ofNullable(request.getHeader(HttpHeaders.AUTHORIZATION)).orElse("");
        String user = null;
        if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            try {
                user = jwt.decode(header.substring(7).strip());
            } catch (JwtException | IllegalArgumentException e) {
                request.setAttribute(ERROR_ATTRIBUTE, "Invalid or expired token");
            }
        } else {
            Optional<BasicCredentials> basic = BasicCredentials.parse(header);
            try {
                if (basic.isPresent() && authService.authenticateThrottled(
                        basic.get().username(), basic.get().password(), request.getRemoteAddr())) {
                    user = basic.get().username();
                }
            } catch (AppException.TooManyAttempts e) {
                // Filters run before the controller advice, so write the 429 here (same body and header).
                response.setStatus(e.status().value());
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
                response.setContentType("application/json");
                new ObjectMapper().writeValue(response.getOutputStream(), Map.of("detail", e.detail()));
                return;
            }
        }
        if (user != null) {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
        }
        chain.doFilter(request, response);
    }
}
