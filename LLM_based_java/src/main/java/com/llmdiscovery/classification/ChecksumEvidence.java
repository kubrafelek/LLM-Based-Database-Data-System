package com.llmdiscovery.classification;

import com.google.common.net.InetAddresses;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Deterministic checks on raw samples (TCKN algorithm, Luhn, email and IP syntax), fused with the
 * LLM distribution.
 *
 * <p>Only categories with a syntactic definition are checked. Names, addresses, phone numbers and
 * dates of birth depend on context (a date is not necessarily a birth date), so they stay with the LLM.
 * The checks run on the unmasked values inside the system; only match counts leave this class.
 */
public final class ChecksumEvidence {

    // Share of non-empty samples that must pass a check before it counts as evidence.
    static final double MIN_MATCH_RATIO = 0.8;

    // How far the distribution is pulled toward the category the check supports:
    // fused = (1 - weight) * llm + weight * one_hot(category).
    // A passing checksum is strong evidence (random 11-digit numbers pass the TCKN check ~1% of
    // the time, random card-length numbers pass Luhn ~10%). The TCKN format alone is weaker,
    // but the case study's own TCKN examples (12345678901, 98765432109) only satisfy the format.
    static final double TCKN_CHECKSUM_WEIGHT = 0.6;
    static final double TCKN_FORMAT_WEIGHT = 0.3;
    static final double LUHN_WEIGHT = 0.6;
    // A full email address or a parseable IP address is unambiguous syntax.
    static final double EMAIL_WEIGHT = 0.6;
    static final double IP_ADDRESS_WEIGHT = 0.6;

    // A check for a specific category also explains the generic category it is a case of:
    // an 11-digit TCKN-shaped column the LLM calls "national ID number" is a TCKN column.
    // Probability on the generic category moves to the specific one before mixing.
    static final Map<PiiCategory, PiiCategory> SPECIALIZES = Map.of(PiiCategory.TCKN, PiiCategory.NATIONAL_ID_NUMBER);

    private static final Pattern TCKN_PATTERN = Pattern.compile("[1-9][0-9]{10}");
    private static final Pattern CARD_SEPARATORS = Pattern.compile("[ -]");
    private static final Pattern CARD_PATTERN = Pattern.compile("[0-9]{13,19}");
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");

    /** check: "tckn_checksum" | "tckn_format" | "luhn" | "email_format" | "ip_address" */
    public record Evidence(PiiCategory category, String check, int matched, int checked, double weight) {}

    public record Fused(Map<PiiCategory, Double> classification, double piiLikelihood) {}

    private record SimpleCheck(PiiCategory category, String check, Predicate<String> predicate, double weight) {}

    // Single-tier checks.
    private static final List<SimpleCheck> SIMPLE_CHECKS = List.of(
            new SimpleCheck(PiiCategory.CREDIT_CARD_NUMBER, "luhn", ChecksumEvidence::isValidLuhnCard, LUHN_WEIGHT),
            new SimpleCheck(PiiCategory.EMAIL_ADDRESS, "email_format", ChecksumEvidence::isEmail, EMAIL_WEIGHT),
            new SimpleCheck(PiiCategory.IP_ADDRESS, "ip_address", ChecksumEvidence::isIpAddress, IP_ADDRESS_WEIGHT));

    private ChecksumEvidence() {}

    public static boolean isTcknFormat(String value) {
        return TCKN_PATTERN.matcher(value.strip()).matches();
    }

    /** 11 digits, no leading 0, digit 10 and 11 are the official check digits. */
    public static boolean isValidTckn(String value) {
        value = value.strip();
        if (!isTcknFormat(value)) {
            return false;
        }
        int[] d = value.chars().map(ch -> ch - '0').toArray();
        int oddSum = d[0] + d[2] + d[4] + d[6] + d[8];
        int evenSum = d[1] + d[3] + d[5] + d[7];
        int sumFirstTen = 0;
        for (int i = 0; i < 10; i++) {
            sumFirstTen += d[i];
        }
        // Math.floorMod: Python's % is never negative.
        return d[9] == Math.floorMod(oddSum * 7 - evenSum, 10) && d[10] == sumFirstTen % 10;
    }

    /** 13-19 digits (spaces and hyphens allowed) passing the Luhn check. */
    public static boolean isValidLuhnCard(String value) {
        String digits = CARD_SEPARATORS.matcher(value.strip()).replaceAll("");
        if (!CARD_PATTERN.matcher(digits).matches()) {
            return false;
        }
        int total = 0;
        for (int index = 0; index < digits.length(); index++) {
            int digit = digits.charAt(digits.length() - 1 - index) - '0';
            if (index % 2 == 1) {
                digit = digit > 4 ? digit * 2 - 9 : digit * 2;
            }
            total += digit;
        }
        return total % 10 == 0;
    }

    public static boolean isEmail(String value) {
        return EMAIL_PATTERN.matcher(value.strip()).matches();
    }

    /** IPv4 in dotted-quad form or IPv6 (inet values are sampled via host(), so no netmask). No DNS lookups. */
    public static boolean isIpAddress(String value) {
        return InetAddresses.isInetAddress(value.strip());
    }

    public static List<Evidence> collect(List<String> rawSamples) {
        List<String> values = rawSamples.stream().filter(v -> !v.isBlank()).toList();
        if (values.isEmpty()) {
            return List.of();
        }
        List<Evidence> evidence = new ArrayList<>();
        int tcknValid = count(values, ChecksumEvidence::isValidTckn);
        int tcknFormat = count(values, ChecksumEvidence::isTcknFormat);
        if (tcknValid >= MIN_MATCH_RATIO * values.size()) {
            evidence.add(new Evidence(PiiCategory.TCKN, "tckn_checksum", tcknValid, values.size(), TCKN_CHECKSUM_WEIGHT));
        } else if (tcknFormat >= MIN_MATCH_RATIO * values.size()) {
            evidence.add(new Evidence(PiiCategory.TCKN, "tckn_format", tcknFormat, values.size(), TCKN_FORMAT_WEIGHT));
        }
        for (SimpleCheck check : SIMPLE_CHECKS) {
            int matched = count(values, check.predicate());
            if (matched >= MIN_MATCH_RATIO * values.size()) {
                evidence.add(new Evidence(check.category(), check.check(), matched, values.size(), check.weight()));
            }
        }
        return evidence;
    }

    private static int count(List<String> values, Predicate<String> check) {
        return (int) values.stream().filter(check).count();
    }

    /** Pull the LLM distribution and pii_likelihood toward the categories the checks support. */
    public static Fused fuse(Map<PiiCategory, Double> classification, double piiLikelihood, List<Evidence> evidence) {
        Map<PiiCategory, Double> fused = new EnumMap<>(classification);
        for (Evidence item : evidence) {
            PiiCategory generic = SPECIALIZES.get(item.category());
            if (generic != null) {
                fused.put(item.category(), fused.get(item.category()) + fused.get(generic));
                fused.put(generic, 0.0);
            }
            Map<PiiCategory, Double> next = new EnumMap<>(PiiCategory.class);
            for (Map.Entry<PiiCategory, Double> entry : fused.entrySet()) {
                next.put(entry.getKey(), (1 - item.weight()) * entry.getValue()
                        + (entry.getKey() == item.category() ? item.weight() : 0.0));
            }
            fused = next;
            piiLikelihood = (1 - item.weight()) * piiLikelihood + item.weight();
        }
        Map<PiiCategory, Double> rounded = new EnumMap<>(PiiCategory.class);
        fused.forEach((category, p) -> rounded.put(category, ClassificationParser.round(p)));
        return new Fused(rounded, ClassificationParser.round(piiLikelihood));
    }
}
