package com.llmdiscovery.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmdiscovery.IntegrationTest;
import com.llmdiscovery.StubLlmServer;
import com.llmdiscovery.classification.ClassifyDtos.ClassifyRequest;
import com.llmdiscovery.classification.ClassifyDtos.ClassifyResponse;
import com.llmdiscovery.common.AppException;
import com.llmdiscovery.common.AppProperties;
import com.llmdiscovery.common.SecretCipher;
import com.llmdiscovery.discovery.MetadataDtos.DbConnectionRequest;
import com.llmdiscovery.discovery.MetadataDtos.MetadataDetail;
import com.llmdiscovery.discovery.MetadataRepository;
import com.llmdiscovery.discovery.MetadataService;
import com.llmdiscovery.discovery.TargetDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ClassificationIntegrationTest extends IntegrationTest {

    static final List<String> DEMO_EMAILS = List.of("ahmet.yilmaz@email.com", "fatma.kaya@gmail.com",
            "mehmet.demir@hotmail.com", "ayse.sahin@yahoo.com", "can.ozkan@outlook.com");
    static final String TIE = StubLlmServer.classificationReply(0.9, Map.of("tckn", 0.5, "national_id_number", 0.5));

    @Autowired MetadataService metadataService;
    @Autowired MetadataRepository metadataRepository;
    @Autowired TargetDatabase targetDatabase;
    @Autowired ClassificationResultRepository results;
    @Autowired SecretCipher cipher;
    @Autowired AppProperties properties;
    @Autowired ObjectMapper mapper;

    UUID columnId(DbConnectionRequest request, String table, String column) {
        MetadataDetail detail = metadataService.create(request);
        return detail.tables().stream().filter(t -> t.tableName().equals(table)).findFirst().orElseThrow()
                .columns().stream().filter(c -> c.columnName().equals(column)).findFirst().orElseThrow().columnId();
    }

    UUID columnId(String table, String column) {
        return columnId(targetRequest(), table, column);
    }

    ClassifyResponse classify(UUID columnId, Integer sampleCount, Boolean force, Consumer<AppProperties> overrides) {
        AppProperties settings = properties.copy();
        overrides.accept(settings);
        ClassificationService service = new ClassificationService(
                metadataRepository, targetDatabase, results, cipher, LLM.client(), settings, mapper);
        return service.classify(new ClassifyRequest(columnId, sampleCount, force));
    }

    ClassifyResponse classify(UUID columnId) {
        return classify(columnId, null, null, s -> {});
    }

    String lastUserPrompt() {
        return LLM.requests.get(LLM.requests.size() - 1).at("/messages/1/content").asText();
    }

    int storedResults() {
        return jdbc.queryForObject("SELECT count(*) FROM classification_results", Integer.class);
    }

    @Test
    void classifiesTheEmailColumnWithMaskedSamples() {
        ClassifyResponse result = classify(columnId("customers", "email"));

        assertThat(result.predictedCategory()).isEqualTo(PiiCategory.EMAIL_ADDRESS);
        assertThat(result.classification()).hasSize(12);
        assertThat(result.classification().values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0,
                org.assertj.core.data.Offset.offset(1e-3));
        assertThat(result.llmPiiLikelihood()).isEqualTo(0.97);
        assertThat(result.piiLikelihood()).isEqualTo(0.988); // pulled toward 1 by email_format (weight 0.6)
        assertThat(result.checksumEvidence()).extracting(ClassifyDtos.ChecksumEvidenceOut::check).containsExactly("email_format");
        assertThat(result.sampleCountUsed()).isEqualTo(DEMO_EMAILS.size());
        assertThat(result.maskingMode()).isEqualTo(MaskingMode.PARTIAL);

        String sent = lastUserPrompt();
        assertThat(sent).contains("@email.com"); // domain kept
        DEMO_EMAILS.forEach(email -> assertThat(sent).doesNotContain(email)); // local part masked
    }

    @Test
    void aColumnFittingNoCategoryIsClassifiedNotRejected() {
        Map<String, Double> zeros = new java.util.HashMap<>();
        for (PiiCategory c : PiiCategory.values()) {
            zeros.put(c.value(), 0.0);
        }
        LLM.reply(StubLlmServer.classificationReply(0.0, zeros));
        ClassifyResponse result = classify(columnId("products", "unit_price"));

        assertThat(result.predictedCategory()).isNull();
        assertThat(result.piiLikelihood()).isZero();
        assertThat(LLM.requests).hasSize(1); // accepted on the first reply, no retries
    }

    @Test
    void tcknFormatBreaksTheLlmTie() {
        LLM.reply(TIE);
        ClassifyResponse result = classify(columnId("customers", "national_id"));

        assertThat(result.predictedCategory()).isEqualTo(PiiCategory.TCKN);
        assertThat(result.classification()).containsEntry("tckn", 1.0).containsEntry("national_id_number", 0.0);
        assertThat(result.llmClassification()).containsEntry("tckn", 0.5);
        assertThat(result.checksumEvidence()).singleElement().satisfies(e -> {
            assertThat(e.check()).isEqualTo("tckn_format");
            assertThat(e.matched()).isEqualTo(5);
        });
        assertThat(lastUserPrompt()).doesNotContain("12345678901"); // raw values never reach the LLM
    }

    @Test
    void checksumValidationCanBeDisabled() {
        LLM.reply(TIE);
        ClassifyResponse result = classify(columnId("customers", "national_id"), null, null,
                s -> s.setChecksumValidationEnabled(false));
        assertThat(result.checksumEvidence()).isEmpty();
        assertThat(result.classification()).isEqualTo(result.llmClassification());
    }

    @Test
    void maskingCanBeDisabled() {
        classify(columnId("customers", "email"), null, null, s -> s.setSampleMaskingMode(MaskingMode.NONE));
        assertThat(DEMO_EMAILS).anyMatch(lastUserPrompt()::contains);
    }

    @Test
    void sampleCountLimitsAndValidation() {
        UUID id = columnId("customers", "email");
        assertThat(classify(id, 2, null, s -> {}).sampleCountUsed()).isEqualTo(2);
        assertThatThrownBy(() -> classify(id, 101, null, s -> {})).isInstanceOf(AppException.InvalidRequest.class);
        assertThatThrownBy(() -> classify(UUID.randomUUID())).isInstanceOf(AppException.NotFound.class);
    }

    @Test
    void anEmptyColumnIsClassifiedFromMetadata() {
        // The demo script never sets last_login, so every value is NULL.
        ClassifyResponse result = classify(columnId("customers", "last_login"));
        assertThat(result.sampleCountUsed()).isZero();
        assertThat(lastUserPrompt()).contains("No non-null sample values");
    }

    @Test
    void aRepeatedRequestIsServedFromTheCache() {
        UUID id = columnId("customers", "email");
        ClassifyResponse first = classify(id);
        ClassifyResponse second = classify(id);

        assertThat(first.cached()).isFalse();
        assertThat(second.cached()).isTrue();
        assertThat(LLM.requests).hasSize(1);
        assertThat(second.classification()).isEqualTo(first.classification());
        assertThat(second.classifiedAt()).isEqualTo(first.classifiedAt());
        assertThat(storedResults()).isOne();
    }

    @Test
    void forceRefreshAndOtherParametersBypassTheCache() {
        UUID id = columnId("customers", "email");
        classify(id);
        assertThat(classify(id, null, true, s -> {}).cached()).isFalse();
        assertThat(classify(id, 3, null, s -> {}).cached()).isFalse();
        assertThat(classify(id, null, null, s -> s.setSampleMaskingMode(MaskingMode.NONE)).cached()).isFalse();
        assertThat(LLM.requests).hasSize(4);
    }

    @Test
    void changedDataIsNotServedFromTheCache() throws Exception {
        DbConnectionRequest target = targetRequest();
        UUID id = columnId("customers", "email");
        classify(id);
        String url = "jdbc:postgresql://" + target.host() + ":5432/" + target.database();
        try (Connection conn = DriverManager.getConnection(url, target.username(), target.password())) {
            conn.createStatement().execute(
                    "INSERT INTO customers (first_name, last_name, email) VALUES ('Cache', 'Test', 'cache.test@example.com')");
            try {
                ClassifyResponse changed = classify(id);
                assertThat(changed.cached()).isFalse();
                assertThat(changed.sampleCountUsed()).isEqualTo(DEMO_EMAILS.size() + 1);
            } finally {
                conn.createStatement().execute("DELETE FROM customers WHERE email = 'cache.test@example.com'");
            }
        }
        assertThat(LLM.requests).hasSize(2);
    }

    @Test
    void theCacheCanBeDisabled() {
        UUID id = columnId("customers", "email");
        classify(id, null, null, s -> s.setClassificationCacheTtlSeconds(0));
        assertThat(classify(id, null, null, s -> s.setClassificationCacheTtlSeconds(0)).cached()).isFalse();
        assertThat(LLM.requests).hasSize(2);
        assertThat(storedResults()).isZero();
    }

    @Test
    void deletingTheSnapshotDeletesItsCachedResults() {
        ClassifyResponse result = classify(columnId("customers", "email"));
        metadataService.delete(result.metadataId());
        assertThat(storedResults()).isZero();
    }

    @Test
    void theReadonlyRoleCanExtractAndClassify() {
        ClassifyResponse result = classify(columnId(readonlyTargetRequest(), "customers", "email"));
        assertThat(result.sampleCountUsed()).isEqualTo(5);
    }
}
