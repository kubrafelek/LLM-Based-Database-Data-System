package com.llmdiscovery.classification;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masks sample values before they leave the system for the LLM provider. */
public final class SampleSanitizer {

    static final char MASK_CHAR = '*';
    // Values with fewer digits than this (house numbers, short codes) are left alone.
    static final int MIN_DIGITS_TO_MASK = 6;
    // Digits kept visible at each end of a masked number, e.g. 12*******01.
    static final int VISIBLE_EDGE_DIGITS = 2;

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("([A-Za-z0-9._%+-]+)@([A-Za-z0-9.-]+\\.[A-Za-z]{2,})");

    private SampleSanitizer() {}

    public static String mask(String value, MaskingMode mode) {
        return switch (mode) {
            case NONE -> value;
            case STRICT -> maskStrict(value);
            case PARTIAL -> maskPartial(value);
        };
    }

    /** Hide identifying parts while keeping the format the classifier relies on. */
    static String maskPartial(String value) {
        value = EMAIL_PATTERN.matcher(value).replaceAll(m -> Matcher.quoteReplacement(
                m.group(1).charAt(0) + String.valueOf(MASK_CHAR).repeat(m.group(1).length() - 1) + "@" + m.group(2)));
        long digitCount = value.codePoints().filter(Character::isDigit).count();
        if (digitCount < MIN_DIGITS_TO_MASK) {
            return value;
        }
        StringBuilder masked = new StringBuilder();
        int seen = 0;
        for (int cp : value.codePoints().toArray()) {
            if (Character.isDigit(cp)) {
                seen++;
                if (VISIBLE_EDGE_DIGITS < seen && seen <= digitCount - VISIBLE_EDGE_DIGITS) {
                    masked.append(MASK_CHAR);
                    continue;
                }
            }
            masked.appendCodePoint(cp);
        }
        return masked.toString();
    }

    /** Keep only the shape: letters -> x/X, digits -> 9, everything else unchanged. */
    static String maskStrict(String value) {
        StringBuilder masked = new StringBuilder();
        value.codePoints().forEach(cp -> {
            if (Character.isLetter(cp)) {
                masked.append(Character.isUpperCase(cp) ? 'X' : 'x');
            } else if (Character.isDigit(cp)) {
                masked.append('9');
            } else {
                masked.appendCodePoint(cp);
            }
        });
        return masked.toString();
    }
}
