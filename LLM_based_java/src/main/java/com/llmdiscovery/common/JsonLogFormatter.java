package com.llmdiscovery.common;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

/**
 * One JSON object per line (LOG_FORMAT=json), with the same fields as the Python service:
 * timestamp (UTC, ISO 8601, ms), level (Python names: WARNING, not WARN), logger, message, request_id,
 * the access-log fields when present (status and duration_ms as numbers), and exception.
 */
public class JsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx").withZone(ZoneOffset.UTC);
    private static final String[] ACCESS_FIELDS = {"method", "path", "status", "duration_ms", "client_ip"};

    @Override
    public String format(ILoggingEvent event) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("timestamp", TIMESTAMP.format(event.getInstant()));
        entry.put("level", event.getLevel() == Level.WARN ? "WARNING" : event.getLevel().toString());
        entry.put("logger", event.getLoggerName());
        entry.put("message", event.getFormattedMessage());
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc.get(ObservabilityFilter.MDC_KEY) != null) {
            entry.put("request_id", mdc.get(ObservabilityFilter.MDC_KEY));
        }
        for (String field : ACCESS_FIELDS) {
            String value = mdc.get(field);
            if (value != null) {
                entry.put(field, switch (field) {
                    case "status" -> Integer.valueOf(value);
                    case "duration_ms" -> Double.valueOf(value);
                    default -> value;
                });
            }
        }
        IThrowableProxy thrown = event.getThrowableProxy();
        if (thrown != null) {
            entry.put("exception", ThrowableProxyUtil.asString(thrown));
        }
        try {
            return MAPPER.writeValueAsString(entry) + "\n";
        } catch (JsonProcessingException e) {
            return "{\"level\":\"ERROR\",\"message\":\"unserializable log event\"}\n";
        }
    }
}
