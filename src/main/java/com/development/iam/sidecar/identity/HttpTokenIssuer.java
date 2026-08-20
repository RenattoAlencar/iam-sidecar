package com.development.iam.sidecar.identity;

import com.development.iam.sidecar.config.IdentityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Implementação HTTP da obtenção do token.
 *
 * <h2>As duas chamadas</h2>
 * <pre>
 * GET  {base-url}/oauth2/realms/{realm}/authorize
 *      ?response_type=code&amp;client_id=…&amp;redirect_uri=…&amp;scope=…
 *      &amp;state=…&amp;code_challenge=…&amp;code_challenge_method=S256
 *      Cookie: {nome-configurado}={sessão da jornada}
 *      → 302, com o código no cabeçalho Location
 *
 * POST {base-url}/oauth2/realms/{realm}/access_token
 *      grant_type=authorization_code&amp;code=…&amp;client_id=…&amp;client_secret=…
 *      &amp;redirect_uri=…&amp;code_verifier=…
 *      → 200, com o token
 * </pre>
 *
 * <h2>O redirecionamento não pode ser seguido</h2>
 * O código vem no cabeçalho {@code Location} de um {@code 302}. Se o cliente
 * HTTP seguir o redirecionamento, ele tenta buscar o endereço de retorno — que
 * não existe, porque o sidecar nunca o expõe — e o código se perde no caminho.
 * <p>
 * O sintoma é enganoso: a chamada funciona no curl, que não segue
 * redirecionamento por padrão, e falha no sidecar. Por isso a configuração do
 * cliente desliga isso explicitamente, e o Javadoc de lá registra o motivo.
 *
 * <h2>O que nunca entra no log</h2>
 * Sessão da jornada, código de autorização, verificador do PKCE, segredo do
 * cliente e o token emitido. O código de autorização é de uso único e vida
 * curta, mas dentro dessa janela vale um token — registrá-lo seria deixar no log
 * algo trocável por credencial.
 */
public class HttpTokenIssuer implements TokenIssuer {

    private static final Logger log = LoggerFactory.getLogger(HttpTokenIssuer.class);

    private static final String AUTHORIZE_PATH = "/oauth2/realms/{realm}/authorize";
    private static final String ACCESS_TOKEN_PATH = "/oauth2/realms/{realm}/access_token";

    private static final String API_VERSION_HEADER = "Accept-API-Version";
    private static final String API_VERSION = "resource=2.1";

    private static final String CODE_PARAM = "code";
    private static final String GRANT_TYPE = "authorization_code";

    private static final int STATE_BYTES = 16;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final RestClient restClient;
    private final IdentityProperties properties;

    public HttpTokenIssuer(RestClient restClient, IdentityProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public AccessToken issue(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new TokenIssuanceException("Sessão da jornada ausente ao obter o token");
        }

        // Gerado aqui e usado nas duas chamadas seguintes. Variavel local: nada
        // precisa sobreviver a esta requisicao.
        PkceChallenge pkce = PkceChallenge.generate();

        String authorizationCode = requestAuthorizationCode(sessionId, pkce);
        return exchangeCodeForToken(authorizationCode, pkce);
    }

    /**
     * Pede o código de autorização apresentando a sessão da jornada.
     * <p>
     * A sessão vai como cookie, com o nome que a instalação do gateway usa — é
     * configuração, não constante, e errá-lo faz o gateway responder a tela de
     * login em vez do código.
     */
    private String requestAuthorizationCode(String sessionId, PkceChallenge pkce) {
        try {
            var response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(AUTHORIZE_PATH)
                            .queryParam("response_type", "code")
                            .queryParam("client_id", properties.clientId())
                            .queryParam("redirect_uri", properties.redirectUri())
                            .queryParam("scope", properties.scopes())
                            .queryParam("state", generateState())
                            .queryParam("code_challenge", pkce.challenge())
                            .queryParam("code_challenge_method", pkce.method())
                            .build(properties.realm()))
                    .accept(MediaType.APPLICATION_JSON)
                    .header(API_VERSION_HEADER, API_VERSION)
                    .header(HttpHeaders.COOKIE, properties.sessionCookieName() + "=" + sessionId)
                    .exchange((request, res) -> new AuthorizeResponse(
                            res.getStatusCode().value(),
                            res.getHeaders().getFirst(HttpHeaders.LOCATION)));

            return extractCode(response);

        } catch (RestClientException e) {
            throw new TokenIssuanceException("Falha ao obter o código de autorização", e);
        }
    }

    /**
     * Extrai o código do cabeçalho de redirecionamento.
     * <p>
     * Status diferente de {@code 302} significa que o gateway não redirecionou —
     * normalmente porque a sessão não foi reconhecida, e nesse caso ele responde
     * a página de autenticação com {@code 200}. Tratar isso como sucesso faria a
     * falha aparecer só na troca do código, com uma mensagem que não aponta para
     * a causa.
     */
    private static String extractCode(AuthorizeResponse response) {
        if (response.status() != HttpStatus.FOUND.value()) {
            throw new TokenIssuanceException(
                    "Gateway não redirecionou ao pedir o código de autorização. "
                            + "A sessão da jornada pode não ter sido reconhecida, ou o nome do "
                            + "cookie configurado pode não ser o desta instalação.");
        }

        String location = response.location();
        if (location == null || location.isBlank()) {
            throw new TokenIssuanceException("Redirecionamento sem endereço de destino");
        }

        return queryParam(location, CODE_PARAM).orElseThrow(() -> new TokenIssuanceException(
                "Redirecionamento sem código de autorização. O endereço de retorno "
                        + "configurado pode divergir do registrado no cliente OAuth."));
    }

    /**
     * Troca o código pelo token.
     * <p>
     * Corpo em formulário, e não JSON: é o que a especificação define para este
     * ponto, e o gateway recusa outro formato.
     */
    private AccessToken exchangeCodeForToken(String authorizationCode, PkceChallenge pkce) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", GRANT_TYPE);
        form.add("code", authorizationCode);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("redirect_uri", properties.redirectUri());
        form.add("code_verifier", pkce.verifier());

        try {
            var response = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path(ACCESS_TOKEN_PATH)
                            .build(properties.realm()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .exchange((request, res) -> new TokenResponse(
                            res.getStatusCode().value(),
                            res.bodyTo(String.class)));

            return readToken(response);

        } catch (RestClientException e) {
            throw new TokenIssuanceException("Falha ao trocar o código pelo token", e);
        }
    }

    private static AccessToken readToken(TokenResponse response) {
        if (response.status() != HttpStatus.OK.value()) {
            // O corpo nao entra na mensagem: ele pode carregar detalhe da
            // configuracao do cliente OAuth.
            log.error("Gateway recusou a troca do código pelo token: status={}", response.status());
            throw new TokenIssuanceException("Gateway recusou a emissão do token");
        }

        if (response.body() == null || response.body().isBlank()) {
            throw new TokenIssuanceException("Gateway devolveu corpo vazio ao emitir o token");
        }

        AccessToken token;
        try {
            token = JsonSupport.read(response.body(), AccessToken.class);
        } catch (Exception e) {
            throw new TokenIssuanceException("Resposta de emissão do token ilegível", e);
        }

        if (!token.isUsable()) {
            // Seguir com token ausente produziria falha adiante, longe da causa.
            throw new TokenIssuanceException("Gateway respondeu sem token de acesso");
        }

        log.debug("Token emitido: tipo={}, validade={}s", token.tokenType(), token.expiresIn());
        return token;
    }

    /**
     * Lê um parâmetro do endereço de redirecionamento.
     * <p>
     * Feito à mão em vez de por biblioteca de URI porque o valor vem do
     * cabeçalho {@code Location} e pode não ser um endereço absoluto válido —
     * uma tentativa de análise estrita falharia antes de encontrar o parâmetro.
     */
    private static Optional<String> queryParam(String location, String name) {
        int queryStart = location.indexOf('?');
        if (queryStart < 0) {
            return Optional.empty();
        }

        String query = location.substring(queryStart + 1);
        int fragmentStart = query.indexOf('#');
        if (fragmentStart >= 0) {
            query = query.substring(0, fragmentStart);
        }

        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            if (separator > 0 && pair.substring(0, separator).equals(name)) {
                String value = pair.substring(separator + 1);
                return value.isBlank() ? Optional.empty() : Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /**
     * Gera o parâmetro de estado.
     * <p>
     * O gateway o exige, mas a proteção que ele oferece — vincular o pedido ao
     * retorno, contra falsificação de requisição — não se aplica aqui: não há
     * navegador, não há sessão de terceiro, e o sidecar controla as duas pontas
     * dentro do mesmo tratamento de requisição.
     * <p>
     * Por isso o valor é gerado e não conferido na volta. Está registrado para
     * que a ausência da conferência seja lida como decisão, e não como
     * esquecimento — e para que volte a ser considerada se este fluxo algum dia
     * passar por navegador.
     */
    private static String generateState() {
        byte[] bytes = new byte[STATE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** Status e destino do redirecionamento, sem o corpo, que não interessa. */
    private record AuthorizeResponse(int status, String location) {
    }

    /** Status e corpo da emissão do token. */
    private record TokenResponse(int status, String body) {
    }
}