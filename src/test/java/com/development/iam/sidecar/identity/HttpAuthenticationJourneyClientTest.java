package com.development.iam.sidecar.identity;

import com.development.iam.sidecar.config.IdentityProperties;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercita a chamada real contra um gateway falso.
 * <p>
 * As respostas usadas aqui são as que o gateway devolve de verdade, copiadas da
 * documentação da jornada {@code factor-onboarding}. Testar contra um formato
 * inventado daria confiança sem lastro: a desserialização passaria e a
 * integração quebraria na primeira chamada de homologação.
 */
class HttpAuthenticationJourneyClientTest {

    private static final String REALM = "alpha";
    private static final String JOURNEY = "factor-onboarding";
    private static final String CHANNEL_TOKEN = "eyJhbGciOiJIUzI1NiJ9.token-do-canal";
    private static final String AUTH_ID = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.jornada";
    private static final String OTP = "149707";

    private static final String CHANNEL_TOKEN_HEADER = "x-canal-autenticacao";
    private static final String AUTHENTICATOR_CODE_HEADER = "x-canal-codigo";

    private static final String AUTHENTICATE_PATH = "/am/json/realms/" + REALM + "/authenticate";

    /** Nomes fictícios: os reais vêm de configuração e não pertencem ao código. */
    private static final String CHANNEL_TOKEN_HEADER = "x-canal-authentication";
    private static final String CODE_HEADER = "x-canal-token";

    /** Passo 1: desafio de biometria. */
    private static final String BIOMETRIC_CHALLENGE = """
            {
              "authId": "%s",
              "callbacks": [
                {
                  "type": "NameCallback",
                  "output": [
                    { "name": "prompt", "value": "CHALLENGE_REQUIRED" },
                    { "name": "defaultValue", "value": "BIOMETRIA:UNICO" }
                  ],
                  "input": [
                    { "name": "IDToken1", "value": "BIOMETRIA:UNICO" }
                  ]
                }
              ]
            }
            """.formatted(AUTH_ID);

    /** Passo 3: espera enquanto a análise biométrica não termina. */
    private static final String POLLING_WAIT = """
            {
              "authId": "%s",
              "callbacks": [
                {
                  "type": "PollingWaitCallback",
                  "output": [
                    { "name": "waitTime", "value": "5000" },
                    { "name": "message", "value": "Please wait..." }
                  ]
                }
              ]
            }
            """.formatted(AUTH_ID);

    /** Resposta final: sessão emitida. */
    private static final String COMPLETED = """
            {
              "tokenId": "sessao-emitida-pelo-am",
              "successUrl": "/enduser/?realm=/alpha",
              "realm": "/alpha"
            }
            """;

    private static WireMockServer gateway;

    private AuthenticationJourneyClient client;

    @BeforeAll
    static void startGateway() {
        gateway = new WireMockServer(wireMockConfig().dynamicPort());
        gateway.start();
    }

    @AfterAll
    static void stopGateway() {
        gateway.stop();
    }

    @BeforeEach
    void setUp() {
        gateway.resetAll();

        IdentityProperties properties = new IdentityProperties(
                URI.create("http://127.0.0.1:" + gateway.port() + "/am"),
                REALM, JOURNEY, "service",
                "sidecar-client", "segredo", "https://canal/callback", "openid",
                "cookie-de-sessao", CHANNEL_TOKEN_HEADER, CODE_HEADER,
                Duration.ofSeconds(2), Duration.ofSeconds(2));

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.readTimeout().toMillis());

        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();

        client = new HttpAuthenticationJourneyClient(restClient, properties);
    }

    private static void gatewayReplies(int status, String body) {
        gateway.stubFor(post(urlPathEqualTo(AUTHENTICATE_PATH))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    /**
     * O que o canal preenche no campo de entrada: um JSON em texto, dentro do
     * JSON da requisição.
     * <p>
     * Definido uma vez e usado tanto ao montar a resposta quanto ao verificar o
     * corpo enviado. Escrever o valor esperado à mão exigiria contar níveis de
     * escape, e um a mais ou a menos produz uma falha que parece defeito do
     * cliente.
     */
    private static final String ANSWER_PAYLOAD =
            "{\"foto\":\"<base64>\",\"channel\":\"app\"}";

    private static List<Map<String, Object>> answeredWithSelfie() {
        return List.of(Map.of(
                "type", "NameCallback",
                "output", List.of(Map.of("name", "prompt", "value", "CHALLENGE_REQUIRED")),
                "input", List.of(Map.of("name", "IDToken1", "value", ANSWER_PAYLOAD))));
    }

    /**
     * Monta o corpo esperado usando o mesmo valor, com o escape aplicado pelo
     * serializador em vez de à mão.
     */
    private static String expectedAdvanceBody() {
        return new ObjectMapper().writeValueAsString(Map.of(
                "authId", AUTH_ID,
                "callbacks", answeredWithSelfie()));
    }

    @Nested
    @DisplayName("início da jornada")
    class Start {

        /**
         * O contrato acordado com o time de identidade: versão da API, tipo de
         * conteúdo e o token do canal no cabeçalho combinado. Errar qualquer um
         * produz recusa do gateway que pareceria falha de autenticação.
         */
        @Test
        @DisplayName("apresenta o token do canal no cabeçalho acordado")
        void sendsChannelTokenInAgreedHeader() {
            gatewayReplies(200, BIOMETRIC_CHALLENGE);

            client.start(CHANNEL_TOKEN, null);

            gateway.verify(postRequestedFor(urlPathEqualTo(AUTHENTICATE_PATH))
                    .withHeader(CHANNEL_TOKEN_HEADER, equalTo(CHANNEL_TOKEN))
                    .withHeader("Accept-API-Version", equalTo("resource=2.1")));
        }

        /**
         * A jornada é escolhida por configuração. Conduzir a errada autentica o
         * cliente por um caminho que não é o pretendido, e o sidecar não teria
         * como perceber — ele não interpreta os passos.
         */
        @Test
        @DisplayName("seleciona a jornada configurada pelos parâmetros de consulta")
        void selectsConfiguredJourney() {
            gatewayReplies(200, BIOMETRIC_CHALLENGE);

            client.start(CHANNEL_TOKEN, null);

            gateway.verify(postRequestedFor(urlPathEqualTo(AUTHENTICATE_PATH))
                    .withQueryParam("authIndexType", equalTo("service"))
                    .withQueryParam("authIndexValue", equalTo(JOURNEY)));
        }

        @Test
        @DisplayName("envia objeto JSON vazio, que é o mínimo aceito")
        void sendsEmptyJsonObject() {
            gatewayReplies(200, BIOMETRIC_CHALLENGE);

            client.start(CHANNEL_TOKEN, null);

            gateway.verify(postRequestedFor(urlPathEqualTo(AUTHENTICATE_PATH))
                    .withRequestBody(equalToJson("{}")));
        }

        /**
         * O código encurta a jornada quando o cliente já tem autenticador
         * configurado. É repassado sem que o sidecar o interprete.
         */
        @Test
        @DisplayName("repassa o código do autenticador quando o canal o apresenta")
        void forwardsAuthenticatorCode() {
            gatewayReplies(200, BIOMETRIC_CHALLENGE);

            client.start(CHANNEL_TOKEN, OTP);

            gateway.verify(postRequestedFor(urlPathEqualTo(AUTHENTICATE_PATH))
                    .withHeader(CODE_HEADER, equalTo(OTP)));
        }

        @Test
        @DisplayName("não envia o cabeçalho do código quando ele não existe")
        void omitsCodeHeaderWhenAbsent() {
            gatewayReplies(200, BIOMETRIC_CHALLENGE);

            client.start(CHANNEL_TOKEN, null);

            gateway.verify(postRequestedFor(urlPathEqualTo(AUTHENTICATE_PATH))
                    .withHeader(CODE_HEADER, absent()));
        }

        @Test
        @DisplayName("desafio de biometria vira desfecho de desafio")
        void biometricChallengeBecomesChallengeOutcome() {
            gatewayReplies(200, BIOMETRIC_CHALLENGE);

            JourneyOutcome outcome = client.start(CHANNEL_TOKEN, null);

            assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.CHALLENGE);
            assertThat(outcome.step().authId()).isEqualTo(AUTH_ID);
            assertThat(outcome.callbacks()).hasSize(1);
            assertThat(outcome.callbacks().getFirst())
                    .containsEntry("type", "NameCallback")
                    .containsKeys("output", "input");
        }

        @Test
        @DisplayName("token ausente falha sem chamar o gateway")
        void missingTokenFailsWithoutCallingGateway() {
            assertThatThrownBy(() -> client.start(null, null))
                    .isInstanceOf(AuthenticationJourneyClient.JourneyUnavailableException.class);

            assertThat(gateway.getAllServeEvents()).isEmpty();
        }
    }

    @Nested
    @DisplayName("continuação da jornada")
    class Advance {

        /**
         * O gateway espera o callback de volta exatamente como o enviou, com o
         * campo de entrada preenchido. Alterar qualquer coisa quebra a jornada.
         */
        @Test
        @DisplayName("envia identificador e resposta do canal sem alterá-los")
        void sendsAuthIdAndAnswerUnchanged() {
            gatewayReplies(200, POLLING_WAIT);

            client.advance(AUTH_ID, answeredWithSelfie());

            gateway.verify(postRequestedFor(urlPathEqualTo(AUTHENTICATE_PATH))
                    .withRequestBody(equalToJson(expectedAdvanceBody())));
        }

        /**
         * O token do canal só identifica quem inicia. Repeti-lo seria carregar
         * credencial por passos que não a exigem.
         */
        @Test
        @DisplayName("não repete o token do canal na continuação")
        void doesNotRepeatChannelToken() {
            gatewayReplies(200, POLLING_WAIT);

            client.advance(AUTH_ID, answeredWithSelfie());

            gateway.verify(postRequestedFor(urlPathEqualTo(AUTHENTICATE_PATH))
                    .withoutHeader(CHANNEL_TOKEN_HEADER));
        }

        /**
         * A espera é um desafio como outro qualquer: o canal aguarda o tempo
         * indicado e devolve o corpo inalterado.
         */
        @Test
        @DisplayName("passo de espera é tratado como desafio")
        void pollingIsTreatedAsChallenge() {
            gatewayReplies(200, POLLING_WAIT);

            JourneyOutcome outcome = client.advance(AUTH_ID, answeredWithSelfie());

            assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.CHALLENGE);
            assertThat(outcome.callbacks().getFirst())
                    .containsEntry("type", "PollingWaitCallback");
        }

        @Test
        @DisplayName("jornada concluída traz a sessão emitida")
        void completedJourneyCarriesSession() {
            gatewayReplies(200, COMPLETED);

            JourneyOutcome outcome = client.advance(AUTH_ID, answeredWithSelfie());

            assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.COMPLETED);
            assertThat(outcome.step().tokenId()).isEqualTo("sessao-emitida-pelo-am");
        }

        @Test
        @DisplayName("identificador ausente falha sem chamar o gateway")
        void missingAuthIdFailsWithoutCallingGateway() {
            assertThatThrownBy(() -> client.advance("  ", answeredWithSelfie()))
                    .isInstanceOf(AuthenticationJourneyClient.JourneyUnavailableException.class);

            assertThat(gateway.getAllServeEvents()).isEmpty();
        }
    }

    @Nested
    @DisplayName("recusa do gateway")
    class Denial {

        /**
         * A distinção central deste cliente. O gateway respondeu — e respondeu
         * não. Virar exceção faria o canal receber indisponibilidade por uma
         * biometria reprovada, e alguém seria acordado de madrugada por
         * comportamento normal.
         */
        @Test
        @DisplayName("biometria recusada vira desfecho de recusa, não exceção")
        void rejectedBiometricsBecomesDenial() {
            gatewayReplies(401, """
                    {
                      "code": 401,
                      "reason": "Unauthorized",
                      "message": "Biometria recusada (Journey encerrada)"
                    }
                    """);

            JourneyOutcome outcome = client.advance(AUTH_ID, answeredWithSelfie());

            assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.DENIED);
            assertThat(outcome.reason()).contains("Biometria recusada");
        }

        @Test
        @DisplayName("código do autenticador inválido vira recusa")
        void invalidAuthenticatorCodeBecomesDenial() {
            gatewayReplies(401, """
                    { "code": 401, "reason": "Unauthorized", "message": "OTP inválido" }
                    """);

            JourneyOutcome outcome = client.advance(AUTH_ID, answeredWithSelfie());

            assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.DENIED);
            assertThat(outcome.reason()).contains("OTP inválido");
        }

        /**
         * A recusa vale de qualquer forma. Falhar ao ler o motivo transformaria
         * uma negação normal em indisponibilidade.
         */
        @Test
        @DisplayName("recusa com corpo ilegível continua sendo recusa")
        void denialWithUnreadableBodyIsStillDenial() {
            gatewayReplies(401, "isso não é json");

            JourneyOutcome outcome = client.advance(AUTH_ID, answeredWithSelfie());

            assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.DENIED);
        }

        /**
         * Nada foi negado — o cliente apenas demorou. A jornada com biometria
         * tem espera longa, e cinco minutos passam. O canal precisa reabrir a
         * jornada em vez de mostrar erro.
         */
        @Test
        @DisplayName("sessão expirada é desfecho próprio, distinto de recusa")
        void expiredSessionIsItsOwnOutcome() {
            gatewayReplies(408, "");

            JourneyOutcome outcome = client.advance(AUTH_ID, answeredWithSelfie());

            assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.EXPIRED);
        }

        /**
         * Nem sessão nem callbacks: não há o que apresentar ao canal nem com o
         * que prosseguir. Seguir adiante seria liberar sem autorização.
         */
        @Test
        @DisplayName("resposta sem sessão e sem desafio vira recusa")
        void emptyOkResponseBecomesDenial() {
            gatewayReplies(200, "{}");

            JourneyOutcome outcome = client.advance(AUTH_ID, answeredWithSelfie());

            assertThat(outcome.type()).isEqualTo(JourneyOutcome.Type.DENIED);
        }
    }

    @Nested
    @DisplayName("indisponibilidade do gateway")
    class Unavailability {

        /**
         * Sem limite, uma indisponibilidade do gateway prenderia as threads do
         * sidecar até a exaustão do pool — derrubando junto o tráfego que nem
         * passa por jornada.
         */
        @Test
        @DisplayName("gateway lento vira falha dentro do prazo configurado")
        void slowGatewayFailsWithinTimeout() {
            gateway.stubFor(post(urlPathEqualTo(AUTHENTICATE_PATH))
                    .willReturn(aResponse().withStatus(200)
                            .withFixedDelay(5_000)
                            .withBody(BIOMETRIC_CHALLENGE)));

            assertThatThrownBy(() -> client.start(CHANNEL_TOKEN, null))
                    .isInstanceOf(AuthenticationJourneyClient.JourneyUnavailableException.class);
        }

        @Test
        @DisplayName("corpo vazio em resposta de sucesso vira falha, não nulo silencioso")
        void emptyBodyBecomesFailure() {
            gatewayReplies(200, "");

            assertThatThrownBy(() -> client.start(CHANNEL_TOKEN, null))
                    .isInstanceOf(AuthenticationJourneyClient.JourneyUnavailableException.class)
                    .hasMessageContaining("corpo vazio");
        }

        @Test
        @DisplayName("status inesperado vira falha")
        void unexpectedStatusBecomesFailure() {
            gatewayReplies(500, "{\"detalhe\":\"stack interno do gateway\"}");

            assertThatThrownBy(() -> client.start(CHANNEL_TOKEN, null))
                    .isInstanceOf(AuthenticationJourneyClient.JourneyUnavailableException.class);
        }

        /**
         * A mensagem pode acabar exposta; a causa carrega endereço do gateway e
         * corpo da resposta, e vai só para o log.
         */
        @Test
        @DisplayName("a mensagem da falha não revela endereço nem resposta do gateway")
        void failureMessageHidesGatewayDetails() {
            gatewayReplies(500, "{\"detalhe\":\"stack interno do gateway\"}");

            assertThatThrownBy(() -> client.start(CHANNEL_TOKEN, null))
                    .hasMessageNotContaining("127.0.0.1")
                    .hasMessageNotContaining("stack interno");
        }
    }
}