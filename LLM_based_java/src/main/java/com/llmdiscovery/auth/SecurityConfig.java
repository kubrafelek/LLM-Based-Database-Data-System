package com.llmdiscovery.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Stateless security: the metadata and classification endpoints (versioned and PDF alias paths)
 * need a Bearer token from /auth or HTTP Basic credentials; everything else is public
 * (/auth, /health, /docs, /ui). Same rules and 401 bodies as the Python service.
 */
@Configuration
public class SecurityConfig {

    static final RequestMatcher PROTECTED = new OrRequestMatcher(
            new AntPathRequestMatcher("/api/v1/db/metadata"), new AntPathRequestMatcher("/db/metadata"),
            new AntPathRequestMatcher("/api/v1/metadata"), new AntPathRequestMatcher("/metadata"),
            new AntPathRequestMatcher("/api/v1/metadata/**"), new AntPathRequestMatcher("/metadata/**"),
            new AntPathRequestMatcher("/api/v1/classify"), new AntPathRequestMatcher("/classify"));

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwt, AuthService authService,
            RevokedTokenRepository revokedTokens) throws Exception {
        http.csrf(csrf -> csrf.disable()) // no cookies or sessions, so no CSRF surface
                .cors(Customizer.withDefaults()) // uses WebConfig's CORS_ALLOWED_ORIGINS mapping
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable()) // Basic is handled by TokenAuthenticationFilter
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(auth -> auth.requestMatchers(PROTECTED).authenticated().anyRequest().permitAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint()))
                .addFilterBefore(new TokenAuthenticationFilter(jwt, authService, PROTECTED, revokedTokens),
                        AnonymousAuthenticationFilter.class);
        return http.build();
    }

    /** 401 with {"detail": ...} and WWW-Authenticate: Bearer, like the Python service. */
    private static AuthenticationEntryPoint entryPoint() {
        ObjectMapper mapper = new ObjectMapper();
        return (request, response, exception) -> {
            Object detail = request.getAttribute(TokenAuthenticationFilter.ERROR_ATTRIBUTE);
            write(mapper, response, detail != null ? detail.toString() : "Not authenticated");
        };
    }

    private static void write(ObjectMapper mapper, HttpServletResponse response, String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), Map.of("detail", detail));
    }
}
