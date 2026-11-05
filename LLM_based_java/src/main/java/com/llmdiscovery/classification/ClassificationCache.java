package com.llmdiscovery.classification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmdiscovery.common.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cache key for /classify results.
 *
 * <p>A cached result is reused only when everything that shaped it is unchanged: the column, the
 * requested sample count, the sample values themselves, the model, the prompt and the settings that
 * affect the answer. Sampling is deterministic (see TargetDatabase.buildSampleQuery), so unchanged
 * data gives the same samples and the same key; changed data gives a new key.
 */
public final class ClassificationCache {

    // Bump when the user prompt template or the parsing / fusion logic changes, so old results are not reused.
    // (System prompt changes are picked up automatically through PROMPT_FINGERPRINT.)
    static final int CACHE_LOGIC_VERSION = 1;
    static final String PROMPT_FINGERPRINT = sha256(Prompts.SYSTEM_PROMPT);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClassificationCache() {}

    /**
     * Keyed hash of the raw sample set. Raw values are never stored, and a keyed hash cannot be
     * brute-forced back to short values (e.g. 11-digit TCKNs) without the key.
     */
    static String sampleFingerprint(List<String> rawSamples, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] payload = MAPPER.writeValueAsBytes(rawSamples.stream().sorted().toList());
            return HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (GeneralSecurityException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String key(UUID columnId, int sampleCount, List<String> rawSamples, String model, AppProperties settings) {
        TreeMap<String, Object> parts = new TreeMap<>();
        parts.put("column_id", columnId.toString());
        parts.put("sample_count", sampleCount);
        parts.put("samples", sampleFingerprint(rawSamples, settings.getEncryptionKey()));
        parts.put("llm_base_url", settings.getLlmBaseUrl());
        parts.put("model", model);
        parts.put("prompt", PROMPT_FINGERPRINT);
        parts.put("masking_mode", settings.getSampleMaskingMode().value());
        parts.put("checksum_validation", settings.isChecksumValidationEnabled());
        parts.put("logic", CACHE_LOGIC_VERSION);
        try {
            return sha256(MAPPER.writeValueAsString(parts));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
