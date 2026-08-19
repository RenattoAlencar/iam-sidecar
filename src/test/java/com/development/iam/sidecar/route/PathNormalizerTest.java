package com.development.iam.sidecar.route;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;


class PathNormalizerTest {

    @Nested
    @DisplayName("formas que são ajustadas e seguem")
    class Canonicalized {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource({
                "/api/v1/pix,                      /api/v1/pix",
                "/api/v1/pix/,                     /api/v1/pix",
                "/api//v1/pix,                     /api/v1/pix",
                "/api/v1/pix//,                    /api/v1/pix",
                "//api//v1//pix//,                 /api/v1/pix",
                "/api/v1/%70ix,                    /api/v1/pix",
                "/api/v1/pix;jsessionid=abc,       /api/v1/pix",
                "/api/v1/pix/transferencia;v=2,    /api/v1/pix/transferencia",
                "/,                                /"
        })
        @DisplayName("variações de escrita produzem a mesma forma canônica")
        void canonicalizesWritingVariations(String rawPath, String expected) {
            assertThat(PathNormalizer.normalize(rawPath)).contains(expected);
        }

        @Test
        @DisplayName("percent literal legítimo não é recusado")
        void acceptsLiteralPercent() {
            assertThat(PathNormalizer.normalize("/api/v1/promo/100%25off"))
                    .contains("/api/v1/promo/100%off");
        }

        @Test
        @DisplayName("path profundo é preservado inteiro")
        void deepPathIsPreserved() {
            String path = "/api/v2/pagamentos/boletos/codigo-de-barras/pagamento/confirmacao";

            assertThat(PathNormalizer.normalize(path)).contains(path);
        }

        @Test
        @DisplayName("path no limite de tamanho é aceito")
        void acceptsPathAtSizeLimit() {
            assertThat(PathNormalizer.normalize("/" + "a".repeat(2047))).isPresent();
        }
    }

    @Nested
    @DisplayName("formas que são recusadas")
    class Rejected {

        @ParameterizedTest
        @ValueSource(strings = {
                "/api/../v1/pix",
                "/api/./v1/pix",
                "/api/v1/pix/..",
                "/api/%2e%2e/v1/pix",
                "/api/%2E%2E/v1/pix",
                "/../etc/passwd"
        })
        @DisplayName("segmento de navegação é recusado")
        void rejectsNavigationSegments(String rawPath) {
            assertThat(PathNormalizer.normalize(rawPath)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/api%2Fv1/pix",
                "/api%2fv1/pix",
                "/api/v1/pix%2Ftransferencia"
        })
        @DisplayName("separador de path codificado é recusado")
        void rejectsEncodedSeparator(String rawPath) {
            assertThat(PathNormalizer.normalize(rawPath)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/api/%252e%252e/v1/pix",
                "/api/%252f/v1/pix",
                "/api/%2570ix"
        })
        @DisplayName("dupla codificação é recusada")
        void rejectsDoubleEncoding(String rawPath) {
            assertThat(PathNormalizer.normalize(rawPath)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/api/%zz",
                "/api/%2",
                "/api/%",
                "/api/%g0"
        })
        @DisplayName("percent-encoding malformado é recusado")
        void rejectsMalformedEncoding(String rawPath) {
            assertThat(PathNormalizer.normalize(rawPath)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/api\\v1\\pix",
                "/api/%5Cv1/pix",
                "/api/%00",
                "/api/pix%0d%0aX-Injetado:%20valor",
                "/api/pix%7F"
        })
        @DisplayName("caractere inseguro é recusado, mesmo codificado")
        void rejectsUnsafeCharacters(String rawPath) {
            assertThat(PathNormalizer.normalize(rawPath)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"api/v1/pix", "pix", "?a=1"})
        @DisplayName("path sem barra inicial é recusado")
        void rejectsPathWithoutLeadingSlash(String rawPath) {
            assertThat(PathNormalizer.normalize(rawPath)).isEmpty();
        }

        @Test
        @DisplayName("path acima do teto de tamanho é recusado")
        void rejectsOversizedPath() {
            assertThat(PathNormalizer.normalize("/" + "a".repeat(2048))).isEmpty();
        }

        @Test
        @DisplayName("nulo e vazio são recusados")
        void rejectsNullAndBlank() {
            assertThat(PathNormalizer.normalize(null)).isEmpty();
            assertThat(PathNormalizer.normalize("")).isEmpty();
            assertThat(PathNormalizer.normalize("   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("propriedades gerais")
    class GeneralProperties {

        @ParameterizedTest
        @ValueSource(strings = {
                "/api/v1/pix",
                "/api//v1/pix/",
                "/api/v1/%70ix",
                "/api/v1/pix;jsessionid=abc"
        })
        @DisplayName("normalizar o resultado devolve o mesmo resultado")
        void normalizationIsIdempotent(String rawPath) {
            String once = PathNormalizer.normalize(rawPath).orElseThrow();

            assertThat(PathNormalizer.normalize(once)).contains(once);
        }

        @ParameterizedTest
        @ValueSource(strings = {"/api/v1/pix/", "//api//v1//pix//", "/api/v1/pix;p=1/"})
        @DisplayName("o resultado tem barra inicial e não tem barra final")
        void resultHasLeadingAndNoTrailingSlash(String rawPath) {
            String normalized = PathNormalizer.normalize(rawPath).orElseThrow();

            assertThat(normalized).startsWith("/").doesNotEndWith("/");
        }
    }
}