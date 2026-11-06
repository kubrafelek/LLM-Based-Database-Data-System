package com.llmdiscovery.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.llmdiscovery.classification.ClassifyDtos.ClassifyRequest;
import com.llmdiscovery.classification.ClassifyDtos.ClassifyResponse;
import com.llmdiscovery.common.AppException;
import com.llmdiscovery.common.AppProperties;
import com.llmdiscovery.common.SecretCipher;
import com.llmdiscovery.discovery.DbColumn;
import com.llmdiscovery.discovery.DbConnection;
import com.llmdiscovery.discovery.DbMetadata;
import com.llmdiscovery.discovery.DbTable;
import com.llmdiscovery.discovery.MetadataRepository;
import com.llmdiscovery.discovery.TargetDatabase;
import java.sql.Connection;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** The orchestration in ClassificationService, with the database, target DB and LLM mocked. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClassificationServiceTest {

    static final String KEY = "a2tra2tra2tra2tra2tra2tra2tra2tra2tra2tra2s=";
    static final UUID COLUMN_ID = UUID.randomUUID();
    static final List<String> SAMPLES = List.of("ahmet.yilmaz@email.com", "fatma.kaya@gmail.com");

    @Mock MetadataRepository metadata;
    @Mock TargetDatabase targetDatabase;
    @Mock ClassificationResultRepository results;
    @Mock LlmClient llm;
    @Mock DbColumn column;
    @Mock DbTable table;
    @Mock DbMetadata snapshot;
    @Mock DbConnection connection;

    final SecretCipher cipher = new SecretCipher(KEY);
    final ObjectMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    final AppProperties settings = new AppProperties();

    @BeforeEach
    void wire() {
        settings.setEncryptionKey(KEY);
        when(column.getId()).thenReturn(COLUMN_ID);
        when(column.getColumnName()).thenReturn("email");
        when(column.getDataType()).thenReturn("character varying(100)");
        when(column.getTable()).thenReturn(table);
        when(table.getSchemaName()).thenReturn("public");
        when(table.getTableName()).thenReturn("customers");
        when(table.getMetadata()).thenReturn(snapshot);
        when(snapshot.getId()).thenReturn(UUID.randomUUID());
        when(snapshot.getConnection()).thenReturn(connection);
        when(connection.getHost()).thenReturn("demo-db");
        when(connection.getPort()).thenReturn(5432);
        when(connection.getDatabaseName()).thenReturn("demo");
        when(connection.getUsername()).thenReturn("reader");
        when(connection.getEncryptedPassword()).thenReturn(cipher.encrypt("pw"));
        when(metadata.findColumnContext(COLUMN_ID)).thenReturn(Optional.of(column));
        when(targetDatabase.connect(any())).thenReturn(mock(Connection.class));
        when(targetDatabase.fetchSamples(any(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(SAMPLES);
        when(llm.model()).thenReturn("fake-model");
    }

    ClassificationService service() {
        return new ClassificationService(metadata, targetDatabase, results, cipher, llm, settings, mapper);
    }

    @SuppressWarnings("unchecked")
    void llmAnswers(PiiCategory category) {
        EnumMap<PiiCategory, Double> distribution = new EnumMap<>(PiiCategory.class);
        for (PiiCategory c : PiiCategory.values()) {
            distribution.put(c, c == category ? 1.0 : 0.0);
        }
        when(llm.completeJson(any(), any(Function.class)))
                .thenReturn(new ClassificationParser.Parsed(distribution, 0.9, "fake", true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCacheMissCallsTheLlmWithMaskedSamplesAndStoresTheResult() {
        llmAnswers(PiiCategory.EMAIL_ADDRESS);

        ClassifyResponse response = service().classify(new ClassifyRequest(COLUMN_ID, null, null));

        assertThat(response.cached()).isFalse();
        assertThat(response.predictedCategory()).isEqualTo(PiiCategory.EMAIL_ADDRESS);
        assertThat(response.sampleCountRequested()).isEqualTo(10);
        ArgumentCaptor<List<Prompts.Message>> messages = ArgumentCaptor.forClass(List.class);
        verify(llm).completeJson(messages.capture(), any(Function.class));
        String userPrompt = messages.getValue().get(1).content();
        assertThat(userPrompt).contains("@email.com").doesNotContain("ahmet.yilmaz@email.com");
        ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
        verify(results).save(eq(COLUMN_ID), anyString(), stored.capture(), any());
        assertThat(stored.getValue()).contains("\"predicted_category\":\"email_address\"").doesNotContain("\"cached\"");
    }

    @Test
    void aCacheHitReturnsTheStoredResultWithoutCallingTheLlm() throws Exception {
        llmAnswers(PiiCategory.EMAIL_ADDRESS);
        ClassifyResponse first = service().classify(new ClassifyRequest(COLUMN_ID, null, null));
        String stored = mapper.writeValueAsString(first);
        when(results.getFresh(eq(COLUMN_ID), anyString(), any())).thenReturn(Optional.of(stored));
        LlmClient unused = mock(LlmClient.class);
        when(unused.model()).thenReturn("fake-model");

        ClassifyResponse cached = new ClassificationService(metadata, targetDatabase, results, cipher, unused, settings,
                mapper).classify(new ClassifyRequest(COLUMN_ID, null, null));

        assertThat(cached.cached()).isTrue();
        assertThat(cached.classification()).isEqualTo(first.classification());
        verify(unused, never()).completeJson(any(), any());
    }

    @Test
    void forceRefreshSkipsTheLookup() {
        llmAnswers(PiiCategory.EMAIL_ADDRESS);
        service().classify(new ClassifyRequest(COLUMN_ID, null, true));
        verify(results, never()).getFresh(any(), anyString(), any());
        verify(results).save(eq(COLUMN_ID), anyString(), anyString(), any());
    }

    @Test
    void aZeroTtlDisablesTheCache() {
        llmAnswers(PiiCategory.EMAIL_ADDRESS);
        settings.setClassificationCacheTtlSeconds(0);
        service().classify(new ClassifyRequest(COLUMN_ID, null, null));
        verifyNoInteractions(results);
    }

    @Test
    void tooManySamplesAreRejectedBeforeAnyDatabaseAccess() {
        assertThatThrownBy(() -> service().classify(new ClassifyRequest(COLUMN_ID, 101, null)))
                .isInstanceOf(AppException.InvalidRequest.class)
                .hasMessage("sample_count must be at most 100");
        verifyNoInteractions(metadata, targetDatabase, llm);
    }

    @Test
    void anUnknownColumnIsNotFoundAndTheTargetIsNotTouched() {
        UUID unknown = UUID.randomUUID();
        when(metadata.findColumnContext(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().classify(new ClassifyRequest(unknown, null, null)))
                .isInstanceOf(AppException.NotFound.class);
        verifyNoInteractions(targetDatabase, llm);
    }

    @Test
    void checksumEvidenceOverridesAnLlmThatSeesNoCategory() {
        EnumMap<PiiCategory, Double> uniform = new EnumMap<>(ClassificationParser.UNIFORM_DISTRIBUTION);
        when(llm.completeJson(any(), any())).thenReturn(new ClassificationParser.Parsed(uniform, 0.0, null, false));
        ClassifyResponse response = service().classify(new ClassifyRequest(COLUMN_ID, null, null));
        // Both samples are valid emails: the email_format check makes the category fit.
        assertThat(response.predictedCategory()).isEqualTo(PiiCategory.EMAIL_ADDRESS);
        assertThat(response.checksumEvidence()).extracting(ClassifyDtos.ChecksumEvidenceOut::check)
                .containsExactly("email_format");
    }
}
