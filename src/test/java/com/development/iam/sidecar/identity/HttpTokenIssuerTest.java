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

import java.net.URI;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercita as duas chamadas encadeadas contra um gateway falso.
 * <p>
 * O que mais importa aqui não é o caminho feliz: é o redirecionamento não ser
 * seguido, e o código de autorização ser extraído do cabeçalho em vez de
 * buscado. Errar isso produz uma falha que funciona no curl e quebra no sidecar.
 */
class HttpTokenIssuerTest {

    private static final String REALM = "alpha";
    private static final String CLIENT_ID = "sidecar-client";
    private static final String CLIENT_SECRET = "segredo-do-cliente";
    private static final String REDIRECT_URI = "https://canal.exemplo.com.br/callback";
    private static final String COOKIE_NAME = "417726ee02928f6";
    private static final String SESSION = "sessao-emitida-pela-jornada";
    private static final String CODE = "codigo-de-autorizacao";
    private static final String TOKEN = "token-de-acesso-emitido";

    private static final String AUTHORIZE_PATH = "/am/oauth2/realms/" + REALM + "/authorize";
    private static final String TOKEN_PATH = "/am/oauth2/realms/" + REALM + "/access_token";

    private static final String TOKEN_RESPONSE = """
            {
              "access_token": "%s",
              "token_type": "Bearer",
              "expires_in": 3599,
              "scope": "openid",
              "id_token": "eyJ0eXAiOiJKV1Qi..."
            }
            """.formatted(TOKEN);

    private static WireMockServer gateway;

    private TokenIssuer issuer;

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
                REALM, "factor-onboarding", "service",
                CLIENT_ID, CLIENT_SECRET, REDIRECT_URI, "openid profile",
                COOKIE_NAME,
                "x-canal-autenticacao", "x-canal-codigo",
                Duration.ofSeconds(2), Duration.ofSeconds(2));

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.readTimeout().toMillis());

        // O cliente NAO segue redirecionamento — e a configuracao de producao faz
        // o mesmo. Seguir faria o codigo se perder, e o teste passaria a exercitar
        // um comportamento que nao e o real.
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();

        issuer = new HttpTokenIssuer(restClient, properties);
    }

    private static void gatewayRedirectsWithCode() {
        gateway.stubFor(get(urlPathEqualTo(AUTHORIZE_PATH))
                .willReturn(aResponse().withStatus(302)
                        .withHeader("Location", REDIRECT_URI + "?code=" + CODE
                                + "&state=abc&iss=https%3A%2F%2Fgateway")));
    }

    private static void gatewayIssuesToken() {
        gateway.stubFor(post(urlPathEqualTo(TOKEN_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(TOKEN_RESPONSE)));
    }

    @Nested
    @DisplayName("pedido do código de autorização")
    class AuthorizationCode {

        /**
         * A sessão vai como cookie com o nome que a instalação usa. Errado, o
         * gateway responde a tela de login com {@code 200} em vez do
         * redirecionamento — e isso não parece erro de configuração.
         */
        @Test
        @DisplayName("apresenta a sessão da jornada no cookie configurado")
        void presentsJourneySessionAsCookie() {
            gatewayRedirectsWithCode();
            gatewayIssuesToken();

            issuer.issue(SESSION);

            gateway.verify(getRequestedFor(urlPathEqualTo(AUTHORIZE_PATH))
                    .withHeader("Cookie", containing(COOKIE_NAME + "=" + SESSION)));
        }

        @Test
        @DisplayName("envia os parâmetros do fluxo de código com PKCE")
        void sendsAuthorizationCodeFlowParameters() {
            gatewayRedirectsWithCode();
            gatewayIssuesToken();

            issuer.issue(SESSION);

            gateway.verify(getRequestedFor(urlPathEqualTo(AUTHORIZE_PATH))
                    .withQueryParam("response_type", equalTo("code"))
                    .withQueryParam("client_id", equalTo(CLIENT_ID))
                    .withQueryParam("redirect_uri", equalTo(REDIRECT_URI))
                    .withQueryParam("scope", equalTo("openid profile"))
                    .withQueryParam("code_challenge_method", equalTo("S256")));
        }

        /**
         * O segredo do cliente pertence à troca do código, não ao pedido — o
         * pedido viaja como endereço, e um segredo em parâmetro de consulta fica
         * registrado em log de servidor e histórico de proxy.
         */
        @Test
        @DisplayName("não envia o segredo do cliente no pedido do código")
        void doesNotSendClientSecretInAuthorizeRequest() {
            gatewayRedirectsWithCode();
            gatewayIssuesToken();

            issuer.issue(SESSION);

            gateway.verify(getRequestedFor(urlPathEqualTo(AUTHORIZE_PATH))
                    .withQueryParam("client_secret", absentParam()));
        }

        /**
         * O código vem no cabeçalho de um {@code 302}. Se o cliente seguisse o
         * redirecionamento, tentaria buscar o endereço de retorno — que o
         * sidecar não expõe — e o código se perderia.
         */
        @Test
        @DisplayName("extrai o código do cabeçalho, sem seguir o redirecionamento")
        void extractsCodeWithoutFollowingRedirect() {
            gatewayRedirectsWithCode();
            gatewayIssuesToken();

            AccessToken token = issuer.issue(SESSION);

            assertThat(token.accessToken()).isEqualTo(TOKEN);
            // Uma unica chamada ao endereco de autorizacao: se o redirecionamento
            // tivesse sido seguido, haveria uma segunda ao destino.
            assertThat(gateway.findAll(getRequestedFor(urlPathEqualTo(AUTHORIZE_PATH))))
                    .hasSize(1);
        }

        /**
         * Sessão não reconhecida faz o gateway responder a página de
         * autenticação com {@code 200}. Tratar como sucesso faria a falha
         * aparecer só na troca do código, com mensagem que não aponta para a
         * causa.
         */
        @Test
        @DisplayName("resposta sem redirecionamento falha com causa provável na mensagem")
        void missingRedirectFailsWithLikelyCause() {
            gateway.stubFor(get(urlPathEqualTo(AUTHORIZE_PATH))
                    .willReturn(aResponse().withStatus(200)
                            .withBody("<html>página de login</html>")));

            assertThatThrownBy(() -> issuer.issue(SESSION))
                    .isInstanceOf(TokenIssuer.TokenIssuanceException.class)
                    .hasMessageContaining("cookie");
        }

        /**
         * Endereço de retorno divergente do registrado faz o gateway redirecionar
         * com erro em vez de código.
         */
        @Test
        @DisplayName("redirecionamento sem código falha com causa provável na mensagem")
        void redirectWithoutCodeFailsWithLikelyCause() {
            gateway.stubFor(get(urlPathEqualTo(AUTHORIZE_PATH))
                    .willReturn(aResponse().withStatus(302)
                            .withHeader("Location", REDIRECT_URI + "?error=invalid_request")));

            assertThatThrownBy(() -> issuer.issue(SESSION))
                    .isInstanceOf(TokenIssuer.TokenIssuanceException.class)
                    .hasMessageContaining("endereço de retorno");
        }

        @Test
        @DisplayName("sessão ausente falha sem chamar o gateway")
        void missingSessionFailsWithoutCallingGateway() {
            assertThatThrownBy(() -> issuer.issue("  "))
                    .isInstanceOf(TokenIssuer.TokenIssuanceException.class);

            assertThat(gateway.getAllServeEvents()).isEmpty();
        }
    }

    @Nested
    @DisplayName("troca do código pelo token")
    class TokenExchange {

        @Test
        @DisplayName("envia o código, as credenciais e o verificador do PKCE")
        void sendsCodeCredentialsAndVerifier() {
            gatewayRedirectsWithCode();
            gatewayIssuesToken();

            issuer.issue(SESSION);

            gateway.verify(postRequestedFor(urlPathEqualTo(TOKEN_PATH))
                    .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                    .withRequestBody(containing("grant_type=authorization_code"))
                    .withRequestBody(containing("code=" + CODE))
                    .withRequestBody(containing("client_id=" + CLIENT_ID))
                    .withRequestBody(containing("client_secret=" + CLIENT_SECRET))
                    .withRequestBody(containing("code_verifier=")));
        }

        @Test
        @DisplayName("o token emitido é lido da resposta")
        void readsIssuedToken() {
            gatewayRedirectsWithCode();
            gatewayIssuesToken();

            AccessToken token = issuer.issue(SESSION);

            assertThat(token.accessToken()).isEqualTo(TOKEN);
            assertThat(token.tokenType()).isEqualTo("Bearer");
            assertThat(token.expiresIn()).isEqualTo(3599L);
        }

        /**
         * O gateway devolve mais campos do que o tipo modela. Desserialização
         * estrita transformaria evolução do provedor em falha do sidecar.
         */
        @Test
        @DisplayName("campos não modelados na resposta são ignorados")
        void ignoresUnmodeledFields() {
            gatewayRedirectsWithCode();
            gatewayIssuesToken();

            assertThat(issuer.issue(SESSION).isUsable()).isTrue();
        }

        @Test
        @DisplayName("recusa na troca vira falha de emissão")
        void refusedExchangeBecomesFailure() {
            gatewayRedirectsWithCode();
            gateway.stubFor(post(urlPathEqualTo(TOKEN_PATH))
                    .willReturn(aResponse().withStatus(400)
                            .withBody("{\"error\":\"invalid_grant\"}")));

            assertThatThrownBy(() -> issuer.issue(SESSION))
                    .isInstanceOf(TokenIssuer.TokenIssuanceException.class);
        }

        /**
         * Seguir com token ausente produziria falha adiante, longe da causa.
         */
        @Test
        @DisplayName("resposta de sucesso sem token vira falha")
        void successWithoutTokenBecomesFailure() {
            gatewayRedirectsWithCode();
            gateway.stubFor(post(urlPathEqualTo(TOKEN_PATH))
                    .willReturn(aResponse().withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"token_type\":\"Bearer\"}")));

            assertThatThrownBy(() -> issuer.issue(SESSION))
                    .isInstanceOf(TokenIssuer.TokenIssuanceException.class)
                    .hasMessageContaining("sem token");
        }

        /**
         * A mensagem pode acabar exposta; o corpo da recusa pode carregar
         * detalhe da configuração do cliente OAuth.
         */
        @Test
        @DisplayName("a mensagem da falha não revela o corpo da recusa")
        void failureMessageHidesResponseBody() {
            gatewayRedirectsWithCode();
            gateway.stubFor(post(urlPathEqualTo(TOKEN_PATH))
                    .willReturn(aResponse().withStatus(400)
                            .withBody("{\"error_description\":\"detalhe interno do cliente\"}")));

            assertThatThrownBy(() -> issuer.issue(SESSION))
                    .hasMessageNotContaining("detalhe interno");
        }
    }

    @Nested
    @DisplayName("PKCE")
    class Pkce {

        /**
         * Reutilizar o verificador permitiria trocar um código interceptado de
         * outra jornada — exatamente o que o PKCE existe para impedir.
         */
        @Test
        @DisplayName("cada emissão usa um par novo")
        void eachIssuanceUsesFreshPair() {
            gatewayRedirectsWithCode();
            gatewayIssuesToken();

            issuer.issue(SESSION);
            issuer.issue(SESSION);

            var challenges = gateway.findAll(getRequestedFor(urlPathEqualTo(AUTHORIZE_PATH)))
                    .stream()
                    .map(request -> request.queryParameter("code_challenge").firstValue())
                    .distinct()
                    .toList();

            assertThat(challenges).hasSize(2);
        }

        /**
         * O verificador é o que impede a troca de um código interceptado. Log de
         * exceção com o par o entregaria.
         */
        @Test
        @DisplayName("a representação textual do par não revela os valores")
        void textualFormHidesValues() {
            PkceChallenge pkce = PkceChallenge.generate();

            assertThat(pkce.toString())
                    .doesNotContain(pkce.verifier())
                    .doesNotContain(pkce.challenge())
                    .contains("S256");
        }

        /**
         * O desafio precisa ser o resumo do verificador, não o próprio valor —
         * senão ele viaja junto do pedido e a proteção deixa de existir.
         */
        @Test
        @DisplayName("o desafio difere do verificador")
        void challengeDiffersFromVerifier() {
            PkceChallenge pkce = PkceChallenge.generate();

            assertThat(pkce.challenge()).isNotEqualTo(pkce.verifier());
            assertThat(pkce.verifier()).hasSizeGreaterThanOrEqualTo(43);
        }
    }

    /**
     * O WireMock não tem verificador de ausência de parâmetro de consulta pronto
     * para esta versão da interface fluente.
     */
    private static com.github.tomakehurst.wiremock.matching.StringValuePattern absentParam() {
        return com.github.tomakehurst.wiremock.client.WireMock.absent();
    }
}