package com.development.iam.sidecar.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdTest {

    @Test
    @DisplayName("identificador bem formado do chamador é reaproveitado")
    void reusesWellFormedIncomingValue() {
        assertThat(CorrelationId.resolve("abc-123_XYZ")).isEqualTo("abc-123_XYZ");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abc\ndef",
            "abc\r\nINFO Requisição autorizada",
            "abc def",
            "abc/def",
            "abc;def",
            "abc\u0000def",
            "abc\tdef"
    })
    @DisplayName("valor que contaminaria o log é substituído")
    void replacesLogPollutingValue(String received) {
        String resolved = CorrelationId.resolve(received);

        assertThat(resolved).isNotEqualTo(received);
        assertThat(resolved).matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("valor desproporcional é substituído")
    void replacesOversizedValue() {
        String received = "a".repeat(65);

        assertThat(CorrelationId.resolve(received)).isNotEqualTo(received);
    }

    @Test
    @DisplayName("ausência de valor gera um novo")
    void generatesWhenAbsent() {
        assertThat(CorrelationId.resolve(null)).isNotBlank();
        assertThat(CorrelationId.resolve("")).isNotBlank();
        assertThat(CorrelationId.resolve("   ")).isNotBlank();
    }

    @Test
    @DisplayName("os valores gerados são distintos entre si")
    void generatedValuesAreDistinct() {
        Set<String> generated = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            generated.add(CorrelationId.resolve(null));
        }

        assertThat(generated).hasSize(1_000);
    }
}