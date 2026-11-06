package com.llmdiscovery.auth;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
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
            if (basic.isPresent() && authService.authenticate(basic.get().username(), basic.get().password())) {
                user = basic.get().username();
            }
        }
        if (user != null) {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(user, null, List.of()));
        }
        chain.doFilter(request, response);
    }
}
