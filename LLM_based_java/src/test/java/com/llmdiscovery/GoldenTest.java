package com.llmdiscovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmdiscovery.auth.JwtService;
import com.llmdiscovery.auth.PasswordHasher;
import com.llmdiscovery.classification.ChecksumEvidence;
import com.llmdiscovery.classification.ClassificationParser;
import com.llmdiscovery.classification.MaskingMode;
import com.llmdiscovery.classification.PiiCategory;
import com.llmdiscovery.classification.Prompts;
import com.llmdiscovery.classification.SampleSanitizer;
import com.llmdiscovery.common.AppProperties;
import com.llmdiscovery.common.SecretCipher;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden tests: the expected outputs were produced by the Python reference implementation
 * (scripts/generate_golden.py). Any behavioral drift of the port fails here.
 */
class GoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode GOLDEN = load();

    private static JsonNode load() {
        try (InputStream in = GoldenTest.class.getResourceAsStream("/golden.json")) {
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Stream<JsonNode> cases(String group) {
        return StreamSupport.stream(GOLDEN.get(group).spliterator(), false);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }

    private static Map<PiiCategory, Double> distribution(JsonNode node) {
        Map<PiiCategory, Double> distribution = new EnumMap<>(PiiCategory.class);
        node.fields().forEachRemaining(e -> distribution.put(PiiCategory.fromValue(e.getKey()), e.getValue().asDouble()));
        return distribution;
    }

    private static Map<String, Double> byValue(Map<PiiCategory, Double> distribution) {
        Map<String, Double> out = new LinkedHashMap<>();
        distribution.forEach((c, p) -> out.put(c.value(), p));
        return out;
    }

    private static List<Map<String, Object>> evidenceJson(List<ChecksumEvidence.Evidence> evidence) {
        return evidence.stream().map(e -> Map.<String, Object>of(
                "category", e.category().value(), "check", e.check(),
                "matched", e.matched(), "checked", e.checked(), "weight", e.weight())).toList();
    }

    @ParameterizedTest
    @MethodSource("maskingCases")
    void masking(JsonNode c) {
        for (MaskingMode mode : MaskingMode.values()) {
            assertThat(SampleSanitizer.mask(c.get("value").asText(), mode)).as(mode.value())
                    .isEqualTo(c.get(mode.value()).asText());
        }
    }

    static Stream<JsonNode> maskingCases() { return cases("masking"); }

    @ParameterizedTest
    @MethodSource("checkCases")
    void checks(JsonNode c) {
        String value = c.get("value").asText();
        assertThat(ChecksumEvidence.isTcknFormat(value)).as("tckn_format").isEqualTo(c.get("tckn_format").asBoolean());
        assertThat(ChecksumEvidence.isValidTckn(value)).as("tckn_checksum").isEqualTo(c.get("tckn_checksum").asBoolean());
        assertThat(ChecksumEvidence.isValidLuhnCard(value)).as("luhn").isEqualTo(c.get("luhn").asBoolean());
        assertThat(ChecksumEvidence.isEmail(value)).as("email").isEqualTo(c.get("email").asBoolean());
        assertThat(ChecksumEvidence.isIpAddress(value)).as("ip").isEqualTo(c.get("ip_address").asBoolean());
    }

    static Stream<JsonNode> checkCases() { return cases("checks"); }

    @ParameterizedTest
    @MethodSource("evidenceCases")
    void evidence(JsonNode c) {
        List<Map<String, Object>> actual = evidenceJson(ChecksumEvidence.collect(strings(c.get("samples"))));
        // JsonNode equality ignores object key order (Map.of has none).
        assertThat((JsonNode) MAPPER.valueToTree(actual)).isEqualTo(c.get("evidence"));
    }

    static Stream<JsonNode> evidenceCases() { return cases("evidence"); }

    @ParameterizedTest
    @MethodSource("fuseCases")
    void fuse(JsonNode c) {
        ChecksumEvidence.Fused fused = ChecksumEvidence.fuse(
                distribution(c.get("classification")), c.get("pii_likelihood").asDouble(),
                ChecksumEvidence.collect(strings(c.get("samples"))));
        assertThat(byValue(fused.classification())).isEqualTo(byValue(distribution(c.get("fused"))));
        assertThat(fused.piiLikelihood()).isEqualTo(c.get("fused_pii_likelihood").asDouble());
    }

    static Stream<JsonNode> fuseCases() { return cases("fuse"); }

    @ParameterizedTest
    @MethodSource("parseCases")
    @SuppressWarnings("unchecked")
    void parse(JsonNode c) {
        Map<String, Object> payload = MAPPER.convertValue(c.get("payload"), LinkedHashMap.class);
        if (c.has("error")) {
            assertThatThrownBy(() -> ClassificationParser.parse(payload))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(c.get("error").asText());
            return;
        }
        ClassificationParser.Parsed parsed = ClassificationParser.parse(payload);
        assertThat(byValue(parsed.classification())).isEqualTo(byValue(distribution(c.get("classification"))));
        assertThat(parsed.piiLikelihood()).isEqualTo(c.get("pii_likelihood").asDouble());
        assertThat(parsed.reasoning()).isEqualTo(c.get("reasoning").isNull() ? null : c.get("reasoning").asText());
        assertThat(parsed.categoryFits()).isEqualTo(c.get("category_fits").asBoolean());
    }

    static Stream<JsonNode> parseCases() { return cases("parse"); }

    @Test
    void systemPromptIsIdenticalToPython() {
        assertThat(Prompts.SYSTEM_PROMPT).isEqualTo(GOLDEN.get("system_prompt").asText());
    }

    @ParameterizedTest
    @MethodSource("promptCases")
    void userPromptIsIdenticalToPython(JsonNode c) {
        JsonNode ctx = c.get("context");
        Prompts.ColumnContext context = new Prompts.ColumnContext(ctx.get("schema_name").asText(),
                ctx.get("table_name").asText(), ctx.get("column_name").asText(), ctx.get("data_type").asText());
        String prompt = Prompts.buildUserPrompt(context, strings(c.get("samples")),
                MaskingMode.fromValue(c.get("mode").asText()));
        assertThat(prompt).isEqualTo(c.get("prompt").asText());
    }

    static Stream<JsonNode> promptCases() { return cases("user_prompts"); }

    // --- Interoperability with data written by the Python service --------------

    @Test
    void decryptsAPasswordEncryptedByPython() {
        JsonNode interop = GOLDEN.get("interop");
        SecretCipher cipher = new SecretCipher(interop.get("encryption_key").asText());
        assertThat(cipher.decrypt(interop.get("encrypted").asText())).isEqualTo(interop.get("plaintext").asText());
    }

    @Test
    void verifiesAnArgon2HashWrittenByPython() {
        JsonNode interop = GOLDEN.get("interop");
        PasswordHasher hasher = new PasswordHasher();
        assertThat(hasher.verify(interop.get("argon2_password").asText(), interop.get("argon2_hash").asText())).isTrue();
        assertThat(hasher.verify("wrong", interop.get("argon2_hash").asText())).isFalse();
    }

    @Test
    void acceptsAJwtIssuedByPython() {
        JsonNode interop = GOLDEN.get("interop");
        AppProperties properties = new AppProperties();
        properties.setJwtSecretKey(interop.get("jwt_secret").asText());
        assertThat(new JwtService(properties).decode(interop.get("jwt").asText()))
                .isEqualTo(interop.get("jwt_subject").asText());
    }
}
