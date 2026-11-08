package com.llmdiscovery.common;

import org.springframework.http.HttpStatus;

/** Errors that map directly to an HTTP response with a {"detail": ...} body. */
public class AppException extends RuntimeException {

    private final HttpStatus status;

    protected AppException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
    }

    protected AppException(HttpStatus status, String detail, Throwable cause) {
        super(detail, cause);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public String detail() {
        return getMessage();
    }

    public static class NotFound extends AppException {
        public NotFound(String detail) { super(HttpStatus.NOT_FOUND, detail); }
    }

    public static class InvalidRequest extends AppException {
        public InvalidRequest(String detail) { super(HttpStatus.UNPROCESSABLE_ENTITY, detail); }
    }

    public static class BadRequest extends AppException {
        public BadRequest(String detail) { super(HttpStatus.BAD_REQUEST, detail); }
    }

    /** User-supplied connection details could not be used. */
    public static class InvalidConnection extends AppException {
        public InvalidConnection(String detail, Throwable cause) { super(HttpStatus.BAD_REQUEST, detail, cause); }
    }

    /** The target host is the system DB or outside the configured target host policy. */
    public static class TargetHostNotAllowed extends AppException {
        public TargetHostNotAllowed() { super(HttpStatus.BAD_REQUEST, "Connections to this host are not allowed"); }
    }

    /** Too many failed logins; the client must wait Retry-After seconds. */
    public static class TooManyAttempts extends AppException {
        private final long retryAfterSeconds;

        public TooManyAttempts(long retryAfterSeconds) {
            super(HttpStatus.TOO_MANY_REQUESTS, "Too many failed login attempts. Try again later.");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() { return retryAfterSeconds; }
    }

    /** Stored metadata no longer matches the target database. */
    public static class StaleMetadata extends AppException {
        public StaleMetadata(String detail, Throwable cause) { super(HttpStatus.CONFLICT, detail, cause); }
    }

    /** A target database could not be reached or queried. */
    public static class TargetDatabase extends AppException {
        public TargetDatabase(String detail, Throwable cause) { super(HttpStatus.BAD_GATEWAY, detail, cause); }
    }

    public static class Llm extends AppException {
        public Llm(String detail) { super(HttpStatus.BAD_GATEWAY, detail); }
        public Llm(String detail, Throwable cause) { super(HttpStatus.BAD_GATEWAY, detail, cause); }
    }

    public static class Decryption extends AppException {
        public Decryption(String detail, Throwable cause) { super(HttpStatus.INTERNAL_SERVER_ERROR, detail, cause); }
    }

    public static class Unauthorized extends AppException {
        private final String challenge;

        public Unauthorized(String detail, String challenge) {
            super(HttpStatus.UNAUTHORIZED, detail);
            this.challenge = challenge;
        }

        public String challenge() { return challenge; }
    }
}
