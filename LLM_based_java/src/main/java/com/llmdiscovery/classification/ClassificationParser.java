package com.llmdiscovery.classification;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Validates and normalizes the LLM's classification output. Never trust it as-is. */
public final class ClassificationParser {

    static final int PROBABILITY_DECIMALS = 4;
    // Rounding each probability to 4 decimals can move the sum by up to 12 * 0.00005.
    static final double PROBABILITY_SUM_TOLERANCE = 1e-3;

    // Returned when no category fits: maximal uncertainty, still a distribution summing to 1.
    static final Map<PiiCategory, Double> UNIFORM_DISTRIBUTION = uniform();

    /** categoryFits is false when the model scored every category 0, i.e. "none of the categories fit". */
    public record Parsed(Map<PiiCategory, Double> classification, double piiLikelihood, String reasoning, boolean categoryFits) {}

    private ClassificationParser() {}

    private static Map<PiiCategory, Double> uniform() {
        Map<PiiCategory, Double> distribution = new EnumMap<>(PiiCategory.class);
        for (PiiCategory category : PiiCategory.values()) {
            distribution.put(category, round(1.0 / PiiCategory.values().length));
        }
        return Map.copyOf(distribution);
    }

    /** Same result as Python's round(x, 4): correctly rounded, ties to even, on the exact binary value. */
    static double round(double value) {
        return new BigDecimal(value).setScale(PROBABILITY_DECIMALS, RoundingMode.HALF_EVEN).doubleValue();
    }

    static double toProbability(Object value) {
        double probability;
        if (value instanceof Boolean || value == null) {
            throw new IllegalArgumentException("probability must be a number");
        } else if (value instanceof Number number) {
            probability = number.doubleValue();
        } else if (value instanceof String text) {
            try {
                probability = Double.parseDouble(text.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("probability must be a number", e);
            }
        } else {
            throw new IllegalArgumentException("probability must be a number");
        }
        if (!Double.isFinite(probability) || probability < 0 || probability > 1) {
            throw new IllegalArgumentException("probability " + probability + " is outside [0, 1]");
        }
        return probability;
    }

    /**
     * Map scores onto all 12 categories and rescale them to sum to 1.
     * Unknown keys and unusable values are dropped; missing categories get 0.
     * Returns null when the scores are valid but all 0 ("no category fits").
     */
    static Map<PiiCategory, Double> normalizeDistribution(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("'classification' must be an object");
        }
        Map<PiiCategory, Double> scores = new EnumMap<>(PiiCategory.class);
        for (PiiCategory category : PiiCategory.values()) {
            scores.put(category, 0.0);
        }
        int validEntries = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            PiiCategory category = PiiCategory.fromValue(String.valueOf(entry.getKey()).strip().toLowerCase(Locale.ROOT));
            if (category == null) {
                continue;
            }
            try {
                scores.put(category, toProbability(entry.getValue()));
            } catch (IllegalArgumentException e) {
                continue;
            }
            validEntries++;
        }
        if (validEntries == 0) {
            throw new IllegalArgumentException("'classification' contains no valid category probabilities");
        }
        double total = 0;
        for (double score : scores.values()) { // enum order, like Python's sum over the dict
            total += score;
        }
        if (total == 0) {
            return null;
        }
        Map<PiiCategory, Double> normalized = new EnumMap<>(PiiCategory.class);
        for (Map.Entry<PiiCategory, Double> entry : scores.entrySet()) {
            normalized.put(entry.getKey(), round(entry.getValue() / total));
        }
        return normalized;
    }

    public static Parsed parse(Map<String, Object> payload) {
        double piiLikelihood = round(toProbability(payload.get("pii_likelihood")));
        String reasoning = payload.get("reasoning") instanceof String text ? text : null;

        Map<PiiCategory, Double> classification = normalizeDistribution(payload.get("classification"));
        if (classification == null) {
            // A column that fits no category is not PII of any supported kind.
            return new Parsed(new EnumMap<>(UNIFORM_DISTRIBUTION), 0.0, reasoning, false);
        }
        return new Parsed(classification, piiLikelihood, reasoning, true);
    }
}
