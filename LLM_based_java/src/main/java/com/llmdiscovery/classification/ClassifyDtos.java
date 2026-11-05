package com.llmdiscovery.classification;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ClassifyDtos {

    private ClassifyDtos() {}

    public record ClassifyRequest(
            @NotNull UUID columnId,
            @Min(1) Integer sampleCount,
            @Schema(description = "Ignore a cached result and call the LLM again.") Boolean forceRefresh) {

        public ClassifyRequest {
            sampleCount = sampleCount == null ? 10 : sampleCount;
            forceRefresh = forceRefresh != null && forceRefresh;
        }
    }

    public record ChecksumEvidenceOut(
            PiiCategory category,
            @Schema(description = "tckn_checksum, tckn_format, luhn, email_format or ip_address") String check,
            @Schema(description = "Non-empty samples that passed the check") int matched,
            @Schema(description = "Non-empty samples checked") int checked,
            @Schema(description = "How far the distribution was pulled toward the category") double weight) {}

    public record ClassifyResponse(
            UUID columnId,
            UUID metadataId,
            String schemaName,
            String tableName,
            String columnName,
            String dataType,
            int sampleCountRequested,
            int sampleCountUsed,
            MaskingMode maskingMode,
            @Schema(description = "Probability per PII category; always all 12 categories, summing to 1. "
                    + "The LLM distribution fused with checksum evidence. Uniform when no category fits the column. "
                    + "These are model confidence scores, not calibrated statistical probabilities.")
                    Map<String, Double> classification,
            @Schema(description = "Most likely category, or null when no category fits the column.")
                    PiiCategory predictedCategory,
            @Schema(description = "Score (0-1) that the column contains PII at all. Not a calibrated probability.")
                    double piiLikelihood,
            @Schema(description = "The LLM's distribution before fusion.") Map<String, Double> llmClassification,
            @Schema(description = "The LLM's pii_likelihood before fusion.") double llmPiiLikelihood,
            @Schema(description = "Deterministic checks (TCKN algorithm, Luhn, email / IP syntax) that most samples passed.")
                    List<ChecksumEvidenceOut> checksumEvidence,
            String reasoning,
            String model,
            @Schema(description = "When the LLM produced this result.") OffsetDateTime classifiedAt,
            @Schema(description = "True when the result was reused: same column, samples, model, prompt and settings.")
                    boolean cached) {}
}
