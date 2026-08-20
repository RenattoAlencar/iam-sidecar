package com.development.iam.sidecar.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O que se protege é o cenário mais provável: alguém edita a configuração de um
 * ambiente, esquece um campo, e o pod sobe assim mesmo — conduzindo a jornada
 * errada, ou falando com o gateway em claro.
 */
class IdentityPropertiesTest {

    private static final URI GATEWAY = URI.create("https://ig-hml.exemplo.com.br/am");
    private static final String CLIENT_SECRET = "segredo-do-cliente-oauth";

    private static IdentityProperties properties(URI baseUrl) {
        return new IdentityProperties(baseUrl, "alpha", "factor-onboarding", "service",
                "sidecar-client", CLIENT_SECRET, "https://canal.exemplo.com.br/callback",
                "openid profile", "cookie-de-sessao",
                "x-canal-authentication", "x-canal-token",
                Duration.ofSeconds(2), Duration.ofSeconds(10));
    }

    @Nested
    @DisplayName("endereço do gateway")
    class GatewayAddress {

        /**
         * Por esta conexão trafegam o token do canal, a foto da biometria, a
         * semente do TOTP e o token emitido. Em claro, tudo isso é legível por
         * quem estiver no caminho.
         */
        @Test
        @DisplayName("recusa HTTP fora de loopback")
        void rejectsPlainHttp() {
            assertThatThrownBy(() -> properties(URI.create("http://ig-hml.exemplo.com.br/am")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HTTPS");
        }

        /**
         * A exceção existe para o servidor falso dos testes, onde não há rede
         * para interceptar.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "http://127.0.0.1:8090/am",
                "http://localhost:8090/am"
        })
        @DisplayName("aceita HTTP em loopback, para servidor falso em teste")
        void acceptsPlainHttpOnLoopback(String baseUrl) {
            assertThatCode(() -> properties(URI.create(baseUrl))).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("aceita HTTPS")
        void acceptsHttps() {
            assertThatCode(() -> properties(GATEWAY)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("recusa endereço sem host explícito")
        void rejectsAddressWithoutHost() {
            assertThatThrownBy(() -> properties(URI.create("/am")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("host");
        }
    }

    @Nested
    @DisplayName("timeouts")
    class Timeouts {

        /**
         * Sem limite, uma indisponibilidade do gateway prende as threads do
         * sidecar até esgotá-las — e derruba junto o tráfego que apenas
         * atravessa, sem passar por jornada nenhuma.
         */
        @Test
        @DisplayName("recusa timeout de leitura não positivo")
        void rejectsNonPositiveReadTimeout() {
            assertThatThrownBy(() -> new IdentityProperties(GATEWAY, "alpha", "factor-onboarding",
                    "service", "sidecar-client", CLIENT_SECRET, "https://canal/callback",
                    "openid", "cookie-de-sessao", "x-canal-authentication", "x-canal-token",
                    Duration.ofSeconds(2), Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("read-timeout");
        }

        @Test
        @DisplayName("recusa timeout de conexão não positivo")
        void rejectsNonPositiveConnectTimeout() {
            assertThatThrownBy(() -> new IdentityProperties(GATEWAY, "alpha", "factor-onboarding",
                    "service", "sidecar-client", CLIENT_SECRET, "https://canal/callback",
                    "openid", "cookie-de-sessao", "x-canal-authentication", "x-canal-token",
                    Duration.ofSeconds(-1), Duration.ofSeconds(10)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("connect-timeout");
        }
    }

    @Nested
    @DisplayName("representação textual")
    class TextualForm {

        /**
         * Configuração costuma ser registrada no boot, e o {@code toString}
         * gerado pelo record imprimiria o segredo do cliente OAuth — quem o
         * obtém emite token em nome do sidecar.
         */
        @Test
        @DisplayName("não revela o segredo do cliente")
        void doesNotRevealClientSecret() {
            assertThat(properties(GATEWAY).toString())
                    .doesNotContain(CLIENT_SECRET)
                    .contains("clientSecret=***")
                    .contains("factor-onboarding");
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

        @EnableConfigurationProperties(IdentityProperties.class)
        static class EnableProperties {
        }

        private static String[] completeConfiguration() {
            return new String[]{
                    "identity.base-url=https://ig-hml.exemplo.com.br/am",
                    "identity.journey=factor-onboarding",
                    "identity.client-id=sidecar-client",
                    "identity.client-secret=segredo",
                    "identity.redirect-uri=https://canal.exemplo.com.br/callback",
                    "identity.session-cookie-name=cookie-de-sessao",
                    "identity.channel-token-header=x-canal-authentication"
            };
        }

        @Test
        @DisplayName("configuração completa faz o bind com os padrões aplicados")
        void bindsCompleteConfiguration() {
            runner.withPropertyValues(completeConfiguration()).run(context -> {
                assertThat(context).hasNotFailed();
                IdentityProperties properties = context.getBean(IdentityProperties.class);

                assertThat(properties.journey()).isEqualTo("factor-onboarding");
                assertThat(properties.realm()).isEqualTo("alpha");
                assertThat(properties.journeyType()).isEqualTo("service");
                assertThat(properties.scopes()).isEqualTo("openid");
                assertThat(properties.connectTimeout()).hasSeconds(2);
                assertThat(properties.readTimeout()).hasSeconds(10);
            });
        }

        /**
         * Qualquer padrão aqui seria uma jornada específica, e conduzir a errada
         * autentica o cliente por um caminho que não é o pretendido — sem que
         * nada falhe.
         */
        @Test
        @DisplayName("jornada ausente derruba o boot")
        void failsWhenJourneyIsMissing() {
            runner.withPropertyValues(
                            "identity.base-url=https://ig-hml.exemplo.com.br/am",
                            "identity.client-id=sidecar-client",
                            "identity.client-secret=segredo",
                            "identity.redirect-uri=https://canal.exemplo.com.br/callback",
                            "identity.session-cookie-name=cookie-de-sessao",
                            "identity.channel-token-header=x-canal-authentication")
                    .run(context -> assertThat(context).hasFailed());
        }

        /**
         * Um valor inventado passaria no boot e falharia só na primeira jornada
         * real, com o AM recusando o {@code authorize}.
         */
        @Test
        @DisplayName("endereço de retorno ausente derruba o boot")
        void failsWhenRedirectUriIsMissing() {
            runner.withPropertyValues(
                            "identity.base-url=https://ig-hml.exemplo.com.br/am",
                            "identity.journey=factor-onboarding",
                            "identity.client-id=sidecar-client",
                            "identity.client-secret=segredo",
                            "identity.session-cookie-name=cookie-de-sessao",
                            "identity.channel-token-header=x-canal-authentication")
                    .run(context -> assertThat(context).hasFailed());
        }

        /**
         * O nome do cookie é gerado por instalação do AM. Errado, o gateway
         * ignora a sessão e responde a tela de login em vez do código — o que
         * não parece erro de configuração.
         */
        @Test
        @DisplayName("nome do cookie de sessão ausente derruba o boot")
        void failsWhenSessionCookieNameIsMissing() {
            runner.withPropertyValues(
                            "identity.base-url=https://ig-hml.exemplo.com.br/am",
                            "identity.journey=factor-onboarding",
                            "identity.client-id=sidecar-client",
                            "identity.client-secret=segredo",
                            "identity.redirect-uri=https://canal.exemplo.com.br/callback")
                    .run(context -> assertThat(context).hasFailed());
        }

        @Test
        @DisplayName("segredo do cliente ausente derruba o boot")
        void failsWhenClientSecretIsMissing() {
            runner.withPropertyValues(
                            "identity.base-url=https://ig-hml.exemplo.com.br/am",
                            "identity.journey=factor-onboarding",
                            "identity.client-id=sidecar-client",
                            "identity.redirect-uri=https://canal.exemplo.com.br/callback",
                            "identity.session-cookie-name=cookie-de-sessao",
                            "identity.channel-token-header=x-canal-authentication")
                    .run(context -> assertThat(context).hasFailed());
        }

        /**
         * Nome errado produz recusa do gateway idêntica à de token ausente, e o
         * diagnóstico aponta para o token em vez de para a configuração.
         */
        @Test
        @DisplayName("nome do cabeçalho do token do canal ausente derruba o boot")
        void failsWhenChannelTokenHeaderIsMissing() {
            runner.withPropertyValues(
                            "identity.base-url=https://ig-hml.exemplo.com.br/am",
                            "identity.journey=factor-onboarding",
                            "identity.client-id=sidecar-client",
                            "identity.client-secret=segredo",
                            "identity.redirect-uri=https://canal.exemplo.com.br/callback",
                            "identity.session-cookie-name=cookie-de-sessao")
                    .run(context -> assertThat(context).hasFailed());
        }

        /**
         * O cabeçalho do código é opcional: há ambiente onde esse atalho da
         * jornada não existe, e exigir o nome obrigaria a inventar um valor.
         */
        @Test
        @DisplayName("nome do cabeçalho do código é opcional")
        void authenticatorCodeHeaderIsOptional() {
            runner.withPropertyValues(completeConfiguration()).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(IdentityProperties.class).authenticatorCodeHeader())
                        .isEmpty();
            });
        }

        @Test
        @DisplayName("gateway em HTTP claro derruba o boot")
        void failsWhenGatewayIsPlainHttp() {
            runner.withPropertyValues(
                            "identity.base-url=http://ig-hml.exemplo.com.br/am",
                            "identity.journey=factor-onboarding",
                            "identity.client-id=sidecar-client",
                            "identity.client-secret=segredo",
                            "identity.redirect-uri=https://canal.exemplo.com.br/callback",
                            "identity.session-cookie-name=cookie-de-sessao",
                            "identity.channel-token-header=x-canal-authentication")
                    .run(context -> assertThat(context).hasFailed());
        }
    }
}