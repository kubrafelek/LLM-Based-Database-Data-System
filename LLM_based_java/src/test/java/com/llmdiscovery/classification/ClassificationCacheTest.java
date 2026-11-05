package com.llmdiscovery.classification;

import static org.assertj.core.api.Assertions.assertThat;

import com.llmdiscovery.common.AppProperties;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ClassificationCacheTest {

    private static final UUID COLUMN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final List<String> SAMPLES = List.of("12345678901", "98765432109");

    private static AppProperties settings(Consumer<AppProperties> change) {
        AppProperties p = new AppProperties();
        p.setEncryptionKey("a2tra2tra2tra2tra2tra2tra2tra2tra2tra2tra2s=");
        change.accept(p);
        return p;
    }

    private static String key(List<String> samples, UUID column, int count, String model, Consumer<AppProperties> change) {
        return ClassificationCache.key(column, count, samples, model, settings(change));
    }

    private static String base() {
        return key(SAMPLES, COLUMN, 10, "gpt-4o-mini", p -> {});
    }

    @Test
    void keyIsStableAndIndependentOfSampleOrder() {
        assertThat(base()).isEqualTo(base());
        assertThat(key(List.of("98765432109", "12345678901"), COLUMN, 10, "gpt-4o-mini", p -> {})).isEqualTo(base());
    }

    @Test
    void anyInputChangeGivesANewKey() {
        assertThat(List.of(
                key(List.of("12345678901", "98765432108"), COLUMN, 10, "gpt-4o-mini", p -> {}),
                key(List.of("12345678901", "98765432109", "1"), COLUMN, 10, "gpt-4o-mini", p -> {}),
                key(SAMPLES, UUID.randomUUID(), 10, "gpt-4o-mini", p -> {}),
                key(SAMPLES, COLUMN, 5, "gpt-4o-mini", p -> {}),
                key(SAMPLES, COLUMN, 10, "gpt-4o", p -> {}),
                key(SAMPLES, COLUMN, 10, "gpt-4o-mini", p -> p.setLlmBaseUrl("http://ollama:11434/v1")),
                key(SAMPLES, COLUMN, 10, "gpt-4o-mini", p -> p.setSampleMaskingMode(MaskingMode.STRICT)),
                key(SAMPLES, COLUMN, 10, "gpt-4o-mini", p -> p.setChecksumValidationEnabled(false))))
                .doesNotContain(base()).doesNotHaveDuplicates();
    }

    @Test
    void fingerprintIsKeyed() {
        assertThat(ClassificationCache.sampleFingerprint(SAMPLES, "key-a"))
                .isNotEqualTo(ClassificationCache.sampleFingerprint(SAMPLES, "key-b"))
                .hasSize(64)
                .doesNotContain("12345678901");
    }
}
