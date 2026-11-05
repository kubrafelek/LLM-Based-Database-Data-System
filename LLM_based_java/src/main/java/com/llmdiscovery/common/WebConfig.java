package com.llmdiscovery.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // The UI only loads its own files; the CSP has no 'unsafe-inline', so all rendering is via the DOM.
    static final Map<String, String> UI_SECURITY_HEADERS = Map.of(
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; "
                    + "frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
            "X-Content-Type-Options", "nosniff",
            "Referrer-Policy", "no-referrer");

    private final AppProperties properties;

    public WebConfig(AppProperties properties) {
        this.properties = properties;
    }

    @Bean
    public SecretCipher secretCipher() {
        return new SecretCipher(properties.getEncryptionKey());
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (!properties.corsOrigins().isEmpty()) {
            registry.addMapping("/**")
                    .allowedOrigins(properties.corsOrigins().toArray(String[]::new))
                    .allowedMethods("GET", "POST", "DELETE")
                    .allowedHeaders("Authorization", "Content-Type");
        }
    }

    /** 413 for bodies larger than MAX_REQUEST_BODY_BYTES, before authentication or parsing. */
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> requestBodyLimitFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (request.getContentLengthLong() > properties.getMaxRequestBodyBytes()) {
                    Json.write(response, 413, Map.of("detail", "Request body too large"));
                    return;
                }
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> uiSecurityHeadersFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (request.getRequestURI().startsWith("/ui")) {
                    UI_SECURITY_HEADERS.forEach(response::setHeader);
                }
                chain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
