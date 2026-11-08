package com.llmdiscovery.common;

import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Central error mapping. Same contract as the Python service: every error is {"detail": ...},
 * and request validation errors are 422 (FastAPI's convention), not Spring's default 400.
 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(AppException.class)
    public ResponseEntity<Map<String, Object>> handleApp(AppException exc) {
        if (exc.status().is5xxServerError()) {
            log.error("{}: {}", exc.getClass().getSimpleName(), exc.detail());
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exc.status());
        if (exc instanceof AppException.Unauthorized unauthorized) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, unauthorized.challenge());
        }
        if (exc instanceof AppException.TooManyAttempts tooMany) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(tooMany.retryAfterSeconds()));
        }
        return response.body(Map.of("detail", exc.detail()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidBody(MethodArgumentNotValidException exc) {
        List<Map<String, Object>> errors = exc.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.<String, Object>of("loc", List.of("body", e.getField()), "msg", String.valueOf(e.getDefaultMessage())))
                .toList();
        return validation(errors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidParameter(HandlerMethodValidationException exc) {
        List<Map<String, Object>> errors = exc.getAllValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream().map(error -> Map.<String, Object>of(
                        "loc", List.of("query", String.valueOf(result.getMethodParameter().getParameterName())),
                        "msg", String.valueOf(error.getDefaultMessage()))))
                .toList();
        return validation(errors);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,
        ConstraintViolationException.class,
    })
    public ResponseEntity<Map<String, Object>> handleUnreadable(Exception exc) {
        String message = exc instanceof MethodArgumentTypeMismatchException mismatch
                ? "Invalid value for '" + mismatch.getName() + "'"
                : exc instanceof HttpMessageNotReadableException ? "Invalid or missing request body" : exc.getMessage();
        return validation(List.of(Map.of("msg", message)));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException exc) {
        return validation(List.of(Map.of("msg", "Expected a JSON body")));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethod(HttpRequestMethodNotSupportedException exc) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(Map.of("detail", "Method Not Allowed"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException exc) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "Not Found"));
    }

    /** Anything unexpected: generic body (no stack traces or driver messages), full details only in the log. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception exc) {
        log.error("Unhandled error", exc);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("detail", "Internal Server Error"));
    }

    private static ResponseEntity<Map<String, Object>> validation(List<Map<String, Object>> errors) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("detail", errors));
    }
}
