package com.llmdiscovery.common;

import com.llmdiscovery.classification.MaskingMode;
import java.util.Arrays;
import java.util.List;
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
    private int maxSampleCount = 100;
    private MaskingMode sampleMaskingMode = MaskingMode.PARTIAL;
    // Fuse TCKN / Luhn / email / IP checks on the raw samples into the LLM distribution
    private boolean checksumValidationEnabled = true;
    // Reuse a /classify result while the samples and settings are unchanged; 0 disables the cache
    private int classificationCacheTtlSeconds = 86400;

    public List<String> corsOrigins() {
        return Arrays.stream(corsAllowedOrigins.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
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
