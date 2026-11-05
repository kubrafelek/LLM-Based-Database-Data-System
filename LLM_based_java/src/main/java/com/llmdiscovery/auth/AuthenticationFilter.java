package com.llmdiscovery.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards the metadata and classification endpoints (versioned and PDF alias paths).
 * Accepts either a Bearer token from /auth or Basic credentials directly, like the Python service.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthenticationFilter extends OncePerRequestFilter {

    public static final String USER_ATTRIBUTE = "authenticatedUser";
    private static final Pattern PROTECTED = Pattern.compile("^(/api/v1)?/(db/metadata|metadata(/.*)?|classify)$");

    private final JwtService jwt;
    private final AuthService authService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthenticationFilter(JwtService jwt, AuthService authService) {
        this.jwt = jwt;
        this.authService = authService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PROTECTED.matcher(request.getRequestURI()).matches() || "OPTIONS".equals(request.getMethod());
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
                reject(response, "Invalid or expired token");
                return;
            }
        } else {
            Optional<BasicCredentials> basic = BasicCredentials.parse(header);
            if (basic.isPresent() && authService.authenticate(basic.get().username(), basic.get().password())) {
                user = basic.get().username();
            }
        }
        if (user == null) {
            reject(response, "Not authenticated");
            return;
        }
        request.setAttribute(USER_ATTRIBUTE, user);
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), Map.of("detail", detail));
    }
}
