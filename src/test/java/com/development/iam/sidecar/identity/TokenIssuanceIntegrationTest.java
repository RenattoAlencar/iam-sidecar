package com.development.iam.sidecar.identity;

import com.development.iam.sidecar.config.IdentityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercita a obtenção do token contra o gateway de homologação.
 * <p>
 * Separado do teste da jornada porque o que se valida aqui é outra coisa: o
 * fluxo de autorização, com o código vindo no cabeçalho de um redirecionamento e
 * o PKCE amarrando as duas chamadas.
 *
 * <h2>De onde vem a sessão</h2>
 * O {@code SESSION_ID} é uma sessão do AM — o mesmo valor que a jornada devolve
 * como {@code tokenId} ao concluir. <strong>Não precisa vir da jornada de
 * biometria:</strong> qualquer autenticação no realm produz uma sessão válida, e
 * essa é a forma de exercitar este bloco sem gastar uma captura biométrica, que
 * é de uso único.
 * <p>
 * Sessão do AM tem validade própria e expira. Se o teste passar a falhar sem
 * mudança de código, obter uma nova é a primeira coisa a tentar.
 *
 * <h2>Valores no código</h2>
 * Facilita a execução pela IDE, e <strong>não pode chegar ao repositório</strong>
 * — o segredo do cliente OAuth emite token em nome do sidecar. Esvaziar o bloco
 * antes de qualquer commit.
 */
@Disabled("execução manual: depende do gateway de homologação e de credencial preenchida abaixo")
class TokenIssuanceIntegrationTest {

    // ==========================================================================
    //  CONFIGURAÇÃO DO COMPONENTE
    // ==========================================================================

    /** Endereço do gateway, incluindo o caminho base. Sem barra no final. */
    private static final String BASE_URL = "";

    /** Realm do AM. */
    private static final String REALM = "alpha";

    /** Identificador do cliente OAuth do sidecar. */
    private static final String CLIENT_ID = "";

    /** Segredo do cliente OAuth. */
    private static final String CLIENT_SECRET = "";

    /**
     * Endereço de retorno registrado no cliente OAuth.
     * <p>
     * O sidecar nunca o visita — lê o código do cabeçalho e para ali. Mas o valor
     * precisa bater exatamente com o registrado, ou o gateway recusa o pedido.
     */
    private static final String REDIRECT_URI = "";

    /** Escopos pedidos, separados por espaço. */
    private static final String SCOPES = "openid";

    /**
     * Nome do cookie pelo qual a sessão é apresentada.
     * <p>
     * É gerado por instalação do AM. Errado, o gateway ignora a sessão e responde
     * a tela de login em vez do código — e isso não parece erro de configuração.
     */
    private static final String SESSION_COOKIE_NAME = "";

    // ==========================================================================
    //  VALOR QUE VIRIA DA JORNADA
    //
    //  ESVAZIAR ANTES DE COMMITAR.
    // ==========================================================================

    /** Sessão do AM, equivalente ao {@code tokenId} que a jornada devolve. */
    private static final String SESSION_ID = "";

    // ==========================================================================
    //  Não usados neste teste.
    // ==========================================================================

    private static final String JOURNEY = "nao-usado-aqui";
    private static final String JOURNEY_TYPE = "service";
    private static final String CHANNEL_TOKEN_HEADER = "nao-usado-aqui";
    private static final String CODE_HEADER = "";

    private TokenIssuer issuer;

    @BeforeEach
    void setUp() {
        requirePreenchido(BASE_URL, "BASE_URL", "endereço do gateway, terminando em /am");
        requirePreenchido(CLIENT_ID, "CLIENT_ID", "identificador do cliente OAuth");
        requirePreenchido(CLIENT_SECRET, "CLIENT_SECRET", "segredo do cliente OAuth");
        requirePreenchido(REDIRECT_URI, "REDIRECT_URI",
                "endereço de retorno registrado no cliente OAuth");
        requirePreenchido(SESSION_COOKIE_NAME, "SESSION_COOKIE_NAME",
                "nome do cookie de sessão desta instalação do AM");
        requirePreenchido(SESSION_ID, "SESSION_ID", "sessão do AM");

        IdentityProperties properties = new IdentityProperties(
                URI.create(BASE_URL), REALM, JOURNEY, JOURNEY_TYPE,
                CLIENT_ID, CLIENT_SECRET, REDIRECT_URI, SCOPES, SESSION_COOKIE_NAME,
                CHANNEL_TOKEN_HEADER, CODE_HEADER,
                Duration.ofSeconds(5), Duration.ofSeconds(30));

        // Redirecionamento desligado, como na configuracao de producao. Seguir
        // faria o codigo de autorizacao se perder, e o teste passaria a
        // exercitar um comportamento que nao e o real.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.connectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();

        issuer = new HttpTokenIssuer(restClient, properties);

        System.out.println("Gateway:  " + BASE_URL);
        System.out.println("Realm:    " + REALM);
        System.out.println("Cliente:  " + CLIENT_ID);
        System.out.println("Retorno:  " + REDIRECT_URI);
        System.out.println("Cookie:   " + SESSION_COOKIE_NAME);
        System.out.println("Sessão:   " + resumir(SESSION_ID));
        System.out.println();
    }

    private static void requirePreenchido(String valor, String nome, String descricao) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException(
                    "Preencher a constante " + nome + " no início desta classe: " + descricao);
        }
    }

    /**
     * O caminho completo: sessão em cookie, código no cabeçalho de
     * redirecionamento, troca pelo token com o verificador do PKCE.
     * <p>
     * Falha aqui costuma ter causa de configuração, e a mensagem da exceção
     * aponta a mais provável: cookie com nome errado ou endereço de retorno
     * divergente do registrado.
     */
    @Test
    @DisplayName("obtém o token a partir da sessão da jornada")
    void issuesTokenFromJourneySession() {
        AccessToken token = issuer.issue(SESSION_ID);

        System.out.println("=".repeat(70));
        System.out.println("TOKEN EMITIDO");
        System.out.println("=".repeat(70));
        System.out.println("access_token: " + resumir(token.accessToken()));
        System.out.println("token_type:   " + token.tokenType());
        System.out.println("expires_in:   " + token.expiresIn() + "s");
        System.out.println();

        assertThat(token.isUsable())
                .as("o gateway deveria ter emitido um token utilizável")
                .isTrue();

        assertThat(token.expiresIn())
                .as("token sem validade declarada sugere resposta diferente da esperada")
                .isNotNull()
                .isPositive();
    }

    /**
     * Sessão inválida precisa falhar com mensagem que aponte a causa, e não com
     * erro genérico — é o cenário mais comum quando alguém tenta reproduzir o
     * fluxo com uma sessão expirada.
     */
    @Test
    @DisplayName("sessão inválida falha com causa provável na mensagem")
    void invalidSessionFailsWithLikelyCause() {
        assertThatThrownBy(() -> issuer.issue("sessao-que-nunca-existiu"))
                .isInstanceOf(TokenIssuer.TokenIssuanceException.class)
                .satisfies(error -> System.out.println("Mensagem: " + error.getMessage()));
    }

    /**
     * Cada emissão gera um par de PKCE novo, e o gateway confere que o
     * verificador corresponde ao desafio. Duas emissões seguidas confirmam que a
     * geração e a conferência funcionam de verdade, e não por acaso.
     */
    @Test
    @DisplayName("duas emissões seguidas funcionam, cada uma com seu PKCE")
    void consecutiveIssuancesWork() {
        AccessToken first = issuer.issue(SESSION_ID);
        AccessToken second = issuer.issue(SESSION_ID);

        assertThat(first.isUsable()).isTrue();
        assertThat(second.isUsable()).isTrue();
        assertThat(first.accessToken())
                .as("cada emissão deveria produzir um token próprio")
                .isNotEqualTo(second.accessToken());
    }

    private static String resumir(String valor) {
        if (valor == null || valor.length() <= 24) {
            return valor == null ? "(vazio)" : valor;
        }
        return valor.substring(0, 12) + "..." + valor.substring(valor.length() - 8)
                + " (" + valor.length() + " caracteres)";
    }
}