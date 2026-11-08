package com.llmdiscovery.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Request ID, access log and HTTP metrics for every request; runs first, before Spring Security, so
 * 401/413 responses get the request ID too. Same behavior as the Python app/api/observability.py.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ObservabilityFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String MDC_KEY = "request_id";
    // A caller-supplied ID is reused only when it is short and plain (no log injection, no huge values).
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern CAMEL_PARAM = Pattern.compile("\\{([A-Za-z0-9]+)}");
    private static final Logger access = LoggerFactory.getLogger("app.access");

    private final ApplicationContext context;

    public ObservabilityFilter(ApplicationContext context) {
        this.context = context;
    }

    static String resolveRequestId(String supplied) {
        if (supplied != null && VALID_REQUEST_ID.matcher(supplied).matches()) {
            return supplied;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        long start = System.nanoTime();
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            failed = true;
            throw e;
        } finally {
            long nanos = System.nanoTime() - start;
            int status = failed ? 500 : response.getStatus();
            String method = request.getMethod();
            String path = request.getRequestURI(); // path only: query strings are not logged
            AppMetrics.httpRequest(method, route(request, path), status, nanos);
            double durationMs = Math.round(nanos / 100_000.0) / 10.0;
            MDC.put("method", method);
            MDC.put("path", path);
            MDC.put("status", String.valueOf(status));
            MDC.put("duration_ms", String.valueOf(durationMs));
            MDC.put("client_ip", request.getRemoteAddr());
            try {
                access.info("{} {} {} {}ms", method, path, status, durationMs);
            } finally {
                MDC.clear();
            }
        }
    }

    /**
     * The route template, e.g. "/api/v1/metadata/{metadata_id}" (path variables in snake_case, as in
     * Python), for low-cardinality labels. Requests rejected before the dispatcher (401 from Spring
     * Security) are looked up in the handler mapping so they get their route too.
     */
    String route(HttpServletRequest request, String path) {
        if (path.equals("/ui") || path.startsWith("/ui/")) {
            return "/ui/*";
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern == null) {
            try {
                context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class).getHandler(request);
                pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            } catch (Exception e) {
                pattern = null; // no handler for this method/path
            }
        }
        if (pattern == null || "/**".equals(pattern)) {
            return "unmatched";
        }
        Matcher m = CAMEL_PARAM.matcher(pattern.toString());
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, "{" + m.group(1).replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase() + "}");
        }
        m.appendTail(out);
        return out.toString();
    }
}
