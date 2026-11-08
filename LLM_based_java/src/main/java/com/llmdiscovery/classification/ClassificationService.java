package com.llmdiscovery.classification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.llmdiscovery.classification.ClassifyDtos.ChecksumEvidenceOut;
import com.llmdiscovery.classification.ClassifyDtos.ClassifyRequest;
import com.llmdiscovery.classification.ClassifyDtos.ClassifyResponse;
import com.llmdiscovery.common.AppException;
import com.llmdiscovery.common.AppMetrics;
import com.llmdiscovery.common.AppProperties;
import com.llmdiscovery.common.SecretCipher;
import com.llmdiscovery.discovery.ConnectionInfo;
import com.llmdiscovery.discovery.DbColumn;
import com.llmdiscovery.discovery.DbConnection;
import com.llmdiscovery.discovery.DbTable;
import com.llmdiscovery.discovery.MetadataRepository;
import com.llmdiscovery.discovery.TargetDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ClassificationService {

    private final MetadataRepository metadata;
    private final TargetDatabase targetDatabase;
    private final ClassificationResultRepository results;
    private final SecretCipher cipher;
    private final LlmClient llm;
    private final AppProperties settings;
    private final ObjectMapper mapper;

    public ClassificationService(MetadataRepository metadata, TargetDatabase targetDatabase,
            ClassificationResultRepository results, SecretCipher cipher, LlmClient llm, AppProperties settings,
            ObjectMapper mapper) {
        this.metadata = metadata;
        this.targetDatabase = targetDatabase;
        this.results = results;
        this.cipher = cipher;
        this.llm = llm;
        this.settings = settings;
        this.mapper = mapper;
    }

    public ClassifyResponse classify(ClassifyRequest request) {
        if (request.sampleCount() > settings.getMaxSampleCount()) {
            throw new AppException.InvalidRequest("sample_count must be at most " + settings.getMaxSampleCount());
        }
        DbColumn column = metadata.findColumnContext(request.columnId())
                .orElseThrow(() -> new AppException.NotFound("Column " + request.columnId() + " not found"));
        DbTable table = column.getTable();
        DbConnection connection = table.getMetadata().getConnection();

        ConnectionInfo info = new ConnectionInfo(connection.getHost(), connection.getPort(),
                connection.getDatabaseName(), connection.getUsername(), cipher.decrypt(connection.getEncryptedPassword()));
        List<String> rawSamples;
        try (Connection conn = targetDatabase.connect(info)) {
            rawSamples = targetDatabase.fetchSamples(conn, table.getSchemaName(), table.getTableName(),
                    column.getColumnName(), column.getDataType(), request.sampleCount());
        } catch (SQLException e) {
            throw new AppException.TargetDatabase("Could not read sample values from the target database.", e);
        }

        // The samples are fetched even on a cache hit: they are what tells us the data is unchanged.
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        int ttl = settings.getClassificationCacheTtlSeconds();
        String key = ClassificationCache.key(column.getId(), request.sampleCount(), rawSamples, llm.model(), settings);
        if (ttl > 0 && !request.forceRefresh()) {
            Optional<String> hit = results.getFresh(column.getId(), key, now.minusSeconds(ttl));
            if (hit.isPresent()) {
                AppMetrics.cacheLookup("hit").increment();
                return fromCache(hit.get());
            }
            AppMetrics.cacheLookup("miss").increment();
        }

        MaskingMode maskingMode = settings.getSampleMaskingMode();
        List<String> samples = rawSamples.stream().map(v -> SampleSanitizer.mask(v, maskingMode)).toList();
        Prompts.ColumnContext context = new Prompts.ColumnContext(
                table.getSchemaName(), table.getTableName(), column.getColumnName(), column.getDataType());
        ClassificationParser.Parsed parsed =
                llm.completeJson(Prompts.buildMessages(context, samples, maskingMode), ClassificationParser::parse);

        List<ChecksumEvidence.Evidence> evidence =
                settings.isChecksumValidationEnabled() ? ChecksumEvidence.collect(rawSamples) : List.of();
        ChecksumEvidence.Fused fused = ChecksumEvidence.fuse(parsed.classification(), parsed.piiLikelihood(), evidence);
        // A passing check makes its category fit even when the LLM said none did.
        boolean categoryFits = parsed.categoryFits() || !evidence.isEmpty();
        PiiCategory predicted = categoryFits ? argmax(fused.classification()) : null;

        ClassifyResponse response = new ClassifyResponse(
                column.getId(), table.getMetadata().getId(), table.getSchemaName(), table.getTableName(),
                column.getColumnName(), column.getDataType(), request.sampleCount(), samples.size(), maskingMode,
                byValue(fused.classification()), predicted, fused.piiLikelihood(),
                byValue(parsed.classification()), parsed.piiLikelihood(),
                evidence.stream().map(e -> new ChecksumEvidenceOut(
                        e.category(), e.check(), e.matched(), e.checked(), e.weight())).toList(),
                parsed.reasoning(), llm.model(), now, false);
        if (ttl > 0) {
            results.save(column.getId(), key, toCacheJson(response), now);
        }
        return response;
    }

    /** First category with the highest score, in enum order (same tie-breaking as Python's max()). */
    static PiiCategory argmax(Map<PiiCategory, Double> distribution) {
        PiiCategory best = null;
        for (PiiCategory category : PiiCategory.values()) {
            if (best == null || distribution.get(category) > distribution.get(best)) {
                best = category;
            }
        }
        return best;
    }

    private static Map<String, Double> byValue(Map<PiiCategory, Double> distribution) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (PiiCategory category : PiiCategory.values()) {
            out.put(category.value(), distribution.get(category));
        }
        return out;
    }

    private String toCacheJson(ClassifyResponse response) {
        ObjectNode node = mapper.valueToTree(response);
        node.remove("cached");
        return node.toString();
    }

    private ClassifyResponse fromCache(String json) {
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(json);
            node.put("cached", true);
            return mapper.treeToValue(node, ClassifyResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable cached classification result", e);
        }
    }
}
