package com.development.iam.sidecar.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class ProxyPropertiesTest {

    private static final URI LOOPBACK = URI.create("http://127.0.0.1:8081");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final long MAX_BODY_BYTES = 2_097_152;

    private static InterceptRule rule(String name, String path, HttpMethod method) {
        return new InterceptRule(name, path, Set.of(method));
    }

    private static InterceptRule pixTransfer() {
        return rule("pix-transfer", "/api/v1/pix/transferencia", HttpMethod.POST);
    }

    private static ProxyProperties properties(URI target, List<InterceptRule> rules) {
        return new ProxyProperties(target, CONNECT_TIMEOUT, READ_TIMEOUT,
                MAX_BODY_BYTES, List.of(), rules);
    }

    @Nested
    @DisplayName("alvo do encaminhamento")
    class Target {

        @ParameterizedTest
        @ValueSource(strings = {
                "http://127.0.0.1:8081",
                "http://localhost:8081",
                "http://[::1]:8081"
        })
        @DisplayName("aceita as formas equivalentes de loopback")
        void acceptsLoopbackTargets(String target) {
            assertThatCode(() -> properties(URI.create(target), List.of(pixTransfer())))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("recusa alvo fora do loopback")
        void rejectsNonLoopbackTarget() {
            assertThatThrownBy(() ->
                    properties(URI.create("http://10.0.0.5:8081"), List.of(pixTransfer())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("loopback");
        }

        @Test
        @DisplayName("recusa alvo sem host explícito")
        void rejectsTargetWithoutHost() {
            assertThatThrownBy(() -> properties(URI.create("/api"), List.of(pixTransfer())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("host");
        }
    }

    @Nested
    @DisplayName("timeouts")
    class Timeouts {

        @Test
        @DisplayName("recusa timeout de leitura não positivo")
        void rejectsNonPositiveReadTimeout() {
            assertThatThrownBy(() -> new ProxyProperties(
                    LOOPBACK, CONNECT_TIMEOUT, Duration.ZERO,
                    MAX_BODY_BYTES, List.of(), List.of(pixTransfer())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("read-timeout");
        }

        @Test
        @DisplayName("recusa timeout de conexão não positivo")
        void rejectsNonPositiveConnectTimeout() {
            assertThatThrownBy(() -> new ProxyProperties(
                    LOOPBACK, Duration.ofSeconds(-1), READ_TIMEOUT,
                    MAX_BODY_BYTES, List.of(), List.of(pixTransfer())))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("connect-timeout");
        }
    }

    @Nested
    @DisplayName("matriz de interceptação")
    class Matrix {

        @Test
        @DisplayName("recusa duas regras com o mesmo método e path")
        void rejectsOverlappingRules() {
            List<InterceptRule> overlapping = List.of(
                    rule("pix-a", "/api/v1/pix/chaves", HttpMethod.POST),
                    rule("pix-b", "/api/v1/pix/chaves", HttpMethod.POST));

            assertThatThrownBy(() -> properties(LOOPBACK, overlapping))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("POST /api/v1/pix/chaves");
        }

        @Test
        @DisplayName("aceita o mesmo path com métodos disjuntos")
        void acceptsSamePathWithDisjointMethods() {
            List<InterceptRule> disjoint = List.of(
                    rule("pix-keys-query", "/api/v1/pix/chaves", HttpMethod.GET),
                    rule("pix-keys-register", "/api/v1/pix/chaves", HttpMethod.POST));

            assertThatCode(() -> properties(LOOPBACK, disjoint)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a matriz é imutável depois do bind")
        void interceptRulesAreImmutable() {
            ProxyProperties properties = properties(LOOPBACK, List.of(pixTransfer()));

            assertThatThrownBy(() -> properties.interceptRules().add(pixTransfer()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a representação textual não despeja a matriz")
        void textualFormDoesNotDumpMatrix() {
            ProxyProperties properties = properties(LOOPBACK, List.of(pixTransfer()));

            assertThat(properties.toString())
                    .doesNotContain("/api/v1/pix/transferencia")
                    .contains("rules=1");
        }
    }

    @Nested
    @DisplayName("bind da configuração")
    class Binding {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class,
                        ValidationAutoConfiguration.class))
                .withUserConfiguration(EnableProperties.class);

        @EnableConfigurationProperties(ProxyProperties.class)
        static class EnableProperties {
        }

        @Test
        @DisplayName("configuração completa faz o bind com os valores declarados")
        void bindsCompleteConfiguration() {
            runner.withPropertyValues(
                            "proxy.target=http://127.0.0.1:8081",
                            "proxy.connect-timeout=3s",
                            "proxy.read-timeout=15s",
                            "proxy.max-body-bytes=4096",
                            "proxy.intercept-rules[0].name=pix-transfer",
                            "proxy.intercept-rules[0].path=/api/v1/pix/transferencia",
                            "proxy.intercept-rules[0].methods[0]=POST")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        ProxyProperties properties = context.getBean(ProxyProperties.class);

                        assertThat(properties.maxBodyBytes()).isEqualTo(4096);
                        assertThat(properties.interceptRules()).hasSize(1);
                        assertThat(properties.interceptRules().getFirst().methods())
                                .containsExactly(HttpMethod.POST);
                    });
        }

        @Test
        @DisplayName("regra sem métodos derruba o boot")
        void failsWhenMethodsAreMissing() {
            runner.withPropertyValues(
                            "proxy.target=http://127.0.0.1:8081",
                            "proxy.intercept-rules[0].name=pix-transfer",
                            "proxy.intercept-rules[0].path=/api/v1/pix/transferencia")
                    .run(context -> assertThat(context).hasFailed());
        }

        @Test
        @DisplayName("regra sem path derruba o boot")
        void failsWhenPathIsMissing() {
            runner.withPropertyValues(
                            "proxy.target=http://127.0.0.1:8081",
                            "proxy.intercept-rules[0].name=pix-transfer",
                            "proxy.intercept-rules[0].methods[0]=POST")
                    .run(context -> assertThat(context).hasFailed());
        }

        @Test
        @DisplayName("alvo fora do loopback derruba o boot")
        void failsWhenTargetIsNotLoopback() {
            runner.withPropertyValues(
                            "proxy.target=http://10.0.0.5:8081",
                            "proxy.intercept-rules[0].name=pix-transfer",
                            "proxy.intercept-rules[0].path=/api/v1/pix/transferencia",
                            "proxy.intercept-rules[0].methods[0]=POST")
                    .run(context -> assertThat(context).hasFailed());
        }

        @Test
        @DisplayName("matriz vazia derruba o boot")
        void failsWhenMatrixIsEmpty() {
            runner.withPropertyValues("proxy.target=http://127.0.0.1:8081")
                    .run(context -> assertThat(context).hasFailed());
        }

        @Test
        @DisplayName("os valores padrão são os documentados")
        void appliesDocumentedDefaults() {
            runner.withPropertyValues(
                            "proxy.target=http://127.0.0.1:8081",
                            "proxy.intercept-rules[0].name=pix-transfer",
                            "proxy.intercept-rules[0].path=/api/v1/pix/transferencia",
                            "proxy.intercept-rules[0].methods[0]=POST")
                    .run(context -> {
                        ProxyProperties properties = context.getBean(ProxyProperties.class);

                        assertThat(properties.connectTimeout()).hasSeconds(2);
                        assertThat(properties.readTimeout()).hasSeconds(10);
                        assertThat(properties.maxBodyBytes()).isEqualTo(2_097_152);
                        assertThat(properties.reservedHeaders()).isEmpty();
                    });
        }
    }
}