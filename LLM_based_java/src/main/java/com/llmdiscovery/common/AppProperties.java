package com.llmdiscovery.common;

import com.llmdiscovery.classification.MaskingMode;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All runtime configuration, loaded from environment variables / the .env file (see application.yml).
 * Mutable so tests can derive variants with {@link #copy()}.
 */
@ConfigurationProperties("app")
public class AppProperties implements Cloneable {

    // Application
    private String env = "development";
    private String corsAllowedOrigins = ""; // comma-separated; empty disables CORS
    private long maxRequestBodyBytes = 64 * 1024;

    // Authentication. The admin user is created/updated from these values at startup (Argon2 hash).
    private String authUsername;
    private String authPassword;
    private String jwtSecretKey;
    private String jwtAlgorithm = "HS256";
    private int jwtExpireMinutes = 60;
    // Failed-login throttling (in memory, per process). 0 disables a limit.
    private int authMaxFailedAttempts = 5; // per client IP + username
    private int authMaxFailedAttemptsPerIp = 20; // per client IP, any username
    private int authFailureWindowSeconds = 300;

    // Base64-encoded 32-byte key used to encrypt stored target DB passwords (AES-256-GCM)
    private String encryptionKey;

    // OpenAI-compatible LLM
    private String llmBaseUrl = "https://api.openai.com/v1";
    private String llmApiKey = "";
    private String llmModel = "gpt-4o-mini";
    private double llmTimeout = 30.0;
    private int llmMaxRetries = 2;
    private boolean llmJsonMode = true;

    // Target database access and sampling
    private double targetDbConnectTimeoutSeconds = 10.0;
    private int targetDbStatementTimeoutMs = 15000;
    // Comma-separated hostnames, IPs or CIDRs a target may use. Empty = any host except the denied
    // ones (system DB, loopback, link-local/cloud metadata, multicast, unspecified, reserved).
    private String targetDbAllowedHosts = "";
    // The system DB (also in spring.datasource.url); never allowed as a target.
    private String systemDbHost = "";
    private int systemDbPort = 5432;
    private int maxSampleCount = 100;
    private MaskingMode sampleMaskingMode = MaskingMode.PARTIAL;
    // Fuse TCKN / Luhn / email / IP checks on the raw samples into the LLM distribution
    private boolean checksumValidationEnabled = true;
    // Reuse a /classify result while the samples and settings are unchanged; 0 disables the cache
    private int classificationCacheTtlSeconds = 86400;

    // The development-only secrets shipped in .env.example. They let `cp .env.example .env` start the
    // stack as is, and are rejected when APP_ENV=production.
    static final Set<String> EXAMPLE_SECRETS = Set.of(
            "dev-only-jwt-secret-change-me-0123456789abcdef", "ZGV2LW9ubHktZW5jcnlwdGlvbi1rZXktMzJieXRlcyE=");
    static final int MIN_JWT_SECRET_LENGTH = 32;

    /** Fail at startup with a clear message instead of failing on the first request. */
    @PostConstruct
    public void validate() {
        List<String> problems = new ArrayList<>();
        Map<String, String> required = new LinkedHashMap<>();
        required.put("AUTH_USERNAME", authUsername);
        required.put("AUTH_PASSWORD", authPassword);
        required.forEach((name, value) -> {
            if (value == null || value.isBlank()) {
                problems.add(name + " must not be empty");
            }
        });
        if (jwtSecretKey == null || jwtSecretKey.length() < MIN_JWT_SECRET_LENGTH) {
            problems.add("JWT_SECRET_KEY must be at least " + MIN_JWT_SECRET_LENGTH
                    + " characters (generate one with: openssl rand -hex 32)");
        }
        int keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encryptionKey == null ? "" : encryptionKey).length;
        } catch (IllegalArgumentException e) {
            keyBytes = -1;
        }
        if (keyBytes != 32) {
            problems.add("ENCRYPTION_KEY must be base64 of exactly 32 bytes (generate one with: openssl rand -base64 32)");
        }
        if (authMaxFailedAttempts < 0 || authMaxFailedAttemptsPerIp < 0 || authFailureWindowSeconds < 1) {
            problems.add("AUTH_MAX_FAILED_ATTEMPTS* must be >= 0 and AUTH_FAILURE_WINDOW_SECONDS >= 1");
        }
        if (!List.of("HS256", "HS384", "HS512").contains(jwtAlgorithm)) {
            problems.add("JWT_ALGORITHM must be HS256, HS384 or HS512");
        }
        if ("production".equals(env)) {
            Map<String, String> secrets = new LinkedHashMap<>();
            secrets.put("JWT_SECRET_KEY", jwtSecretKey);
            secrets.put("ENCRYPTION_KEY", encryptionKey);
            secrets.put("AUTH_PASSWORD", authPassword);
            secrets.forEach((name, value) -> {
                if (value != null && (EXAMPLE_SECRETS.contains(value) || value.startsWith("change-me"))) {
                    problems.add(name + " still has its .env.example value; not allowed with APP_ENV=production");
                }
            });
        }
        if (!problems.isEmpty()) {
            throw new InvalidConfigurationException(problems);
        }
    }

    public List<String> corsOrigins() {
        return Arrays.stream(corsAllowedOrigins.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public List<String> targetAllowedHosts() {
        return Arrays.stream(targetDbAllowedHosts.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public boolean docsEnabled() {
        return !"production".equals(env);
    }

    public AppProperties copy() {
        try {
            return (AppProperties) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    public String getEnv() { return env; }
    public void setEnv(String env) { this.env = env; }
    public String getCorsAllowedOrigins() { return corsAllowedOrigins; }
    public void setCorsAllowedOrigins(String v) { this.corsAllowedOrigins = v == null ? "" : v; }
    public long getMaxRequestBodyBytes() { return maxRequestBodyBytes; }
    public void setMaxRequestBodyBytes(long v) { this.maxRequestBodyBytes = v; }
    public String getAuthUsername() { return authUsername; }
    public void setAuthUsername(String v) { this.authUsername = v; }
    public String getAuthPassword() { return authPassword; }
    public void setAuthPassword(String v) { this.authPassword = v; }
    public String getJwtSecretKey() { return jwtSecretKey; }
    public void setJwtSecretKey(String v) { this.jwtSecretKey = v; }
    public String getJwtAlgorithm() { return jwtAlgorithm; }
    public void setJwtAlgorithm(String v) { this.jwtAlgorithm = v; }
    public int getJwtExpireMinutes() { return jwtExpireMinutes; }
    public void setJwtExpireMinutes(int v) { this.jwtExpireMinutes = v; }
    public int getAuthMaxFailedAttempts() { return authMaxFailedAttempts; }
    public void setAuthMaxFailedAttempts(int v) { this.authMaxFailedAttempts = v; }
    public int getAuthMaxFailedAttemptsPerIp() { return authMaxFailedAttemptsPerIp; }
    public void setAuthMaxFailedAttemptsPerIp(int v) { this.authMaxFailedAttemptsPerIp = v; }
    public int getAuthFailureWindowSeconds() { return authFailureWindowSeconds; }
    public void setAuthFailureWindowSeconds(int v) { this.authFailureWindowSeconds = v; }
    public String getTargetDbAllowedHosts() { return targetDbAllowedHosts; }
    public void setTargetDbAllowedHosts(String v) { this.targetDbAllowedHosts = v == null ? "" : v; }
    public String getSystemDbHost() { return systemDbHost; }
    public void setSystemDbHost(String v) { this.systemDbHost = v == null ? "" : v; }
    public int getSystemDbPort() { return systemDbPort; }
    public void setSystemDbPort(int v) { this.systemDbPort = v; }
    public String getEncryptionKey() { return encryptionKey; }
    public void setEncryptionKey(String v) { this.encryptionKey = v; }
    public String getLlmBaseUrl() { return llmBaseUrl; }
    public void setLlmBaseUrl(String v) { this.llmBaseUrl = v; }
    public String getLlmApiKey() { return llmApiKey; }
    public void setLlmApiKey(String v) { this.llmApiKey = v == null ? "" : v; }
    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String v) { this.llmModel = v; }
    public double getLlmTimeout() { return llmTimeout; }
    public void setLlmTimeout(double v) { this.llmTimeout = v; }
    public int getLlmMaxRetries() { return llmMaxRetries; }
    public void setLlmMaxRetries(int v) { this.llmMaxRetries = v; }
    public boolean isLlmJsonMode() { return llmJsonMode; }
    public void setLlmJsonMode(boolean v) { this.llmJsonMode = v; }
    public double getTargetDbConnectTimeoutSeconds() { return targetDbConnectTimeoutSeconds; }
    public void setTargetDbConnectTimeoutSeconds(double v) { this.targetDbConnectTimeoutSeconds = v; }
    public int getTargetDbStatementTimeoutMs() { return targetDbStatementTimeoutMs; }
    public void setTargetDbStatementTimeoutMs(int v) { this.targetDbStatementTimeoutMs = v; }
    public int getMaxSampleCount() { return maxSampleCount; }
    public void setMaxSampleCount(int v) { this.maxSampleCount = v; }
    public MaskingMode getSampleMaskingMode() { return sampleMaskingMode; }
    public void setSampleMaskingMode(MaskingMode v) { this.sampleMaskingMode = v; }
    public boolean isChecksumValidationEnabled() { return checksumValidationEnabled; }
    public void setChecksumValidationEnabled(boolean v) { this.checksumValidationEnabled = v; }
    public int getClassificationCacheTtlSeconds() { return classificationCacheTtlSeconds; }
    public void setClassificationCacheTtlSeconds(int v) { this.classificationCacheTtlSeconds = v; }
}
