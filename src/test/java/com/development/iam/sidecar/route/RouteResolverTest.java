package com.development.iam.sidecar.route;

import com.development.iam.sidecar.config.InterceptRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteResolverTest {

    private static final String PIX_TRANSFER = "/api/v1/pix/transferencia";
    private static final String PIX_KEYS = "/api/v1/pix/chaves";
    private static final String BALANCE = "/api/v1/conta/saldo";

    private static InterceptRule rule(String name, String path, Set<HttpMethod> methods) {
        return new InterceptRule(name, path, methods);
    }

    private static RouteResolver resolver() {
        return new RouteResolver(List.of(
                rule("pix-transfer", PIX_TRANSFER, Set.of(HttpMethod.POST)),
                rule("pix-keys-register", PIX_KEYS, Set.of(HttpMethod.POST)),
                rule("boleto-payment", "/api/v2/pagamentos/boletos/pagamento",
                        Set.of(HttpMethod.POST)),
                rule("limite-consulta", "/api/v1/conta/limites/transacionais",
                        Set.of(HttpMethod.GET))));
    }

    @Nested
    @DisplayName("classificação básica")
    class BasicClassification {

        @Test
        @DisplayName("rota da matriz com o método declarado é interceptada")
        void interceptsDeclaredRoute() {
            RouteDecision decision = resolver().resolve(PIX_TRANSFER, HttpMethod.POST);

            assertThat(decision.outcome()).isEqualTo(RouteDecision.Outcome.INTERCEPT);
            assertThat(decision.matchedRule()).get()
                    .extracting(InterceptRule::name).isEqualTo("pix-transfer");
        }

        @Test
        @DisplayName("mesmo path com método fora da regra é encaminhado")
        void samePathWithUndeclaredMethodIsForwarded() {
            RouteDecision decision = resolver().resolve(PIX_KEYS, HttpMethod.GET);

            assertThat(decision.outcome()).isEqualTo(RouteDecision.Outcome.PASSTHROUGH);
        }

        @Test
        @DisplayName("GET declarado na matriz é interceptado")
        void declaredGetIsIntercepted() {
            RouteDecision decision =
                    resolver().resolve("/api/v1/conta/limites/transacionais", HttpMethod.GET);

            assertThat(decision.outcome()).isEqualTo(RouteDecision.Outcome.INTERCEPT);
        }

        @Test
        @DisplayName("rota fora da matriz é encaminhada")
        void routeOutsideMatrixIsForwarded() {
            RouteDecision decision = resolver().resolve(BALANCE, HttpMethod.GET);

            assertThat(decision.outcome()).isEqualTo(RouteDecision.Outcome.PASSTHROUGH);
        }

        @Test
        @DisplayName("a decisão carrega o path normalizado, não o bruto")
        void decisionCarriesNormalizedPath() {
            RouteDecision decision = resolver().resolve(PIX_TRANSFER + "/", HttpMethod.POST);

            assertThat(decision.normalizedPath()).isEqualTo(PIX_TRANSFER);
        }
    }

    @Nested
    @DisplayName("tentativas de contornar a matriz")
    class BypassAttempts {

        @ParameterizedTest
        @ValueSource(strings = {
                "/api/v1/pix/transferencia/",
                "/api//v1/pix/transferencia",
                "/api/v1/pix//transferencia",
                "/api/v1/pix/transferencia;jsessionid=abc",
                "/api/v1/%70ix/transferencia"
        })
        @DisplayName("variação de escrita do path sensível continua interceptada")
        void writingVariationsStayIntercepted(String rawPath) {
            RouteDecision decision = resolver().resolve(rawPath, HttpMethod.POST);

            assertThat(decision.outcome())
                    .as("path '%s' precisa continuar sendo interceptado", rawPath)
                    .isEqualTo(RouteDecision.Outcome.INTERCEPT);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/api/v1/pix/../pix/transferencia",
                "/api/v1/pix/./transferencia",
                "/api/v1/pix/%2e%2e/transferencia",
                "/api\\v1\\pix\\transferencia",
                "/api/v1/pix/%zz",
                "/api/v1/pix/%2",
                "/api%2Fv1/pix/transferencia"
        })
        @DisplayName("path suspeito é recusado, não encaminhado")
        void suspiciousPathIsRejected(String rawPath) {
            RouteDecision decision = resolver().resolve(rawPath, HttpMethod.POST);

            assertThat(decision.outcome())
                    .as("path '%s' precisa ser recusado", rawPath)
                    .isEqualTo(RouteDecision.Outcome.REJECT);
        }

        @Test
        @DisplayName("path recusado nunca é encaminhado")
        void rejectedPathIsNeverForwarded() {
            RouteDecision decision =
                    resolver().resolve("/api/v1/pix/../../etc/passwd", HttpMethod.POST);

            assertThat(decision.outcome()).isNotEqualTo(RouteDecision.Outcome.PASSTHROUGH);
        }

        @Test
        @DisplayName("método desconhecido é recusado, não encaminhado")
        void unknownMethodIsRejected() {
            RouteDecision decision = resolver().resolve(PIX_TRANSFER, null);

            assertThat(decision.outcome()).isEqualTo(RouteDecision.Outcome.REJECT);
        }
    }

    @Nested
    @DisplayName("padrões com wildcard")
    class WildcardPatterns {

        @Test
        @DisplayName("wildcard cobre a sub-árvore inteira")
        void wildcardCoversSubtree() {
            RouteResolver resolver = new RouteResolver(List.of(
                    rule("ted", "/api/v1/transferencias/ted/**", Set.of(HttpMethod.POST))));

            assertThat(resolver.resolve("/api/v1/transferencias/ted/agendada", HttpMethod.POST)
                    .outcome()).isEqualTo(RouteDecision.Outcome.INTERCEPT);

            assertThat(resolver.resolve("/api/v1/transferencias/doc", HttpMethod.POST)
                    .outcome()).isEqualTo(RouteDecision.Outcome.PASSTHROUGH);
        }

        @Test
        @DisplayName("com padrões sobrepostos, vence a primeira declarada")
        void firstDeclaredWinsOnOverlap() {
            RouteResolver resolver = new RouteResolver(List.of(
                    rule("pix-especifica", PIX_TRANSFER, Set.of(HttpMethod.POST)),
                    rule("pix-ampla", "/api/v1/pix/**", Set.of(HttpMethod.POST))));

            assertThat(resolver.resolve(PIX_TRANSFER, HttpMethod.POST).matchedRule())
                    .get().extracting(InterceptRule::name).isEqualTo("pix-especifica");
        }
    }

    @Nested
    @DisplayName("construção")
    class Construction {

        @Test
        @DisplayName("padrão de path inválido derruba a construção")
        void invalidPatternFailsFast() {
            List<InterceptRule> invalid = List.of(
                    rule("broken", "/api/{", Set.of(HttpMethod.POST)));

            assertThatThrownBy(() -> new RouteResolver(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("broken");
        }

        @Test
        @DisplayName("matriz vazia encaminha tudo sem verificação")
        void emptyMatrixForwardsEverything() {
            RouteDecision decision = new RouteResolver(List.of())
                    .resolve(PIX_TRANSFER, HttpMethod.POST);

            assertThat(decision.outcome()).isEqualTo(RouteDecision.Outcome.PASSTHROUGH);
        }
    }
}