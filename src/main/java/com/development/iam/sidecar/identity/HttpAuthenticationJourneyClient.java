package com.development.iam.sidecar.identity;

import com.development.iam.sidecar.config.IdentityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Implementação HTTP do cliente da jornada.
 * <p>
 * Monta a chamada, entrega o corpo como veio e traduz a resposta em desfecho.
 * Não decide nada sobre autorização e não lê callbacks.
 *
 * <h2>A chamada</h2>
 * <pre>
 * POST {base-url}/json/realms/{realm}/authenticate
 *      ?authIndexType={journey-type}&amp;authIndexValue={journey}
 *
 * Accept-API-Version: resource=2.1
 * Content-Type: application/json
 * {channel-token-header}: {token do canal}     ← apenas no início
 * {authenticator-code-header}: {código}        ← apenas no início, se houver
 * </pre>
 * Os nomes dos dois últimos vêm de configuração: são acordo com outra equipe, e
 * mantê-los fora do código evita que identificação da organização chegue ao
 * repositório.
 * O path é forma da API do provedor e por isso vive aqui, não em configuração:
 * mudá-lo não seria ajuste de ambiente, seria outra integração.
 *
 * <h2>Tradução das respostas</h2>
 * <table border="1">
 *   <caption>Do que o gateway responde ao desfecho</caption>
 *   <tr><th>Gateway</th><th>Significa</th><th>Desfecho</th></tr>
 *   <tr><td>{@code 200} com callbacks</td><td>jornada continua</td>
 *       <td>{@code CHALLENGE}</td></tr>
 *   <tr><td>{@code 200} com {@code tokenId}</td><td>jornada concluiu</td>
 *       <td>{@code COMPLETED}</td></tr>
 *   <tr><td>{@code 401}</td><td>biometria, OTP ou FIDO recusados</td>
 *       <td>{@code DENIED}</td></tr>
 *   <tr><td>{@code 408}</td><td>sessão da jornada expirou</td>
 *       <td>{@code EXPIRED}</td></tr>
 *   <tr><td>não respondeu</td><td>gateway indisponível</td>
 *       <td>exceção</td></tr>
 * </table>
 * O {@code 401} <strong>não</strong> vira exceção. É a resposta normal do
 * gateway quando a jornada é negada, e transformá-la em falha faria o canal
 * receber indisponibilidade por uma biometria reprovada.
 *
 * <h2>O que nunca entra no log</h2>
 * Token do canal, código OTP, {@code authId}, sessão emitida e conteúdo de
 * callback. Os callbacks carregam a foto da selfie em base64, a semente do TOTP
 * com o segredo do autenticador e o código digitado. O que se registra é o passo
 * e o desfecho — ambos sem valor para quem não é dono da requisição.
 */
public class HttpAuthenticationJourneyClient implements AuthenticationJourneyClient {

    private static final Logger log =
            LoggerFactory.getLogger(HttpAuthenticationJourneyClient.class);

    private static final String AUTHENTICATE_PATH = "/json/realms/{realm}/authenticate";

    private static final String API_VERSION_HEADER = "Accept-API-Version";
    private static final String API_VERSION = "resource=2.1";

    private static final String INDEX_TYPE_PARAM = "authIndexType";
    private static final String INDEX_VALUE_PARAM = "authIndexValue";

    /**
     * O início não tem corpo útil, mas o gateway recusa requisição sem JSON
     * válido. Objeto vazio é o mínimo aceito.
     */
    private static final Map<String, Object> EMPTY_BODY = Map.of();

    private final RestClient restClient;
    private final IdentityProperties properties;

    public HttpAuthenticationJourneyClient(RestClient restClient,
                                           IdentityProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public JourneyOutcome start(String channelToken, String otpCode) {
        if (channelToken == null || channelToken.isBlank()) {
            // Sem token nao ha jornada a iniciar. Falhar aqui evita uma ida ao
            // gateway cujo desfecho ja se conhece.
            throw new JourneyUnavailableException("Token do canal ausente ao iniciar a jornada");
        }

        return execute("início", request -> {
            request.header(properties.channelTokenHeader(), channelToken);

            // Presente e valido, o gateway pula o embarque e vai ao fator
            // seguinte. O sidecar nao decide nada sobre isso — apenas repassa.
            //
            // O nome do cabecalho pode nao estar configurado: e o caso de um
            // ambiente onde esse atalho da jornada nao existe. Sem ele, o codigo
            // simplesmente nao e enviado.
            boolean codeConfigured = !properties.authenticatorCodeHeader().isBlank();
            if (codeConfigured && otpCode != null && !otpCode.isBlank()) {
                request.header(properties.authenticatorCodeHeader(), otpCode);
            }
            return EMPTY_BODY;
        });
    }

    @Override
    public JourneyOutcome advance(String authId, List<Map<String, Object>> callbacks) {
        if (authId == null || authId.isBlank()) {
            throw new JourneyUnavailableException("Jornada sem identificador ao continuar");
        }

        // O token do canal nao se repete: ele so identifica quem inicia. Repeti-lo
        // seria carregar credencial por passos que nao a exigem.
        return execute("continuação", request -> JourneyStep.advancing(authId, callbacks));
    }

    /**
     * Executa a chamada e traduz a resposta em desfecho.
     * <p>
     * Compartilhado entre início e continuação porque a URL, os parâmetros e o
     * tratamento da resposta são idênticos — o que difere é o corpo e um
     * cabeçalho.
     *
     * <h3>Como o corpo de uma resposta de erro é obtido</h3>
     * O status de erro do gateway carrega a informação que mais importa no
     * diagnóstico: a mensagem que distingue biometria reprovada de código
     * inválido. Obtê-la exigiu duas tentativas que não funcionam, e vale
     * registrá-las para que ninguém as refaça.
     * <p>
     * <strong>{@code exchange} não serve.</strong> Ele entrega a resposta crua,
     * mas o fluxo do corpo chega vazio em resposta de erro — o cliente já o
     * consumiu ao montar a resposta. Envolver a fábrica com bufferização também
     * não resolve.
     * <p>
     * <strong>Manipulador de status vazio não serve.</strong> Desligar o
     * tratamento padrão com {@code onStatus} faz o corpo ser consumido durante a
     * verificação, e a leitura seguinte encontra vazio.
     * <p>
     * O que funciona é deixar o tratamento padrão agir: ele lança
     * {@link RestClientResponseException}, e <em>essa exceção carrega o corpo</em>.
     * Capturá-la aqui e traduzir em desfecho preserva a distinção entre recusa —
     * que é resposta normal do gateway — e indisponibilidade, que é falha.
     */
    private JourneyOutcome execute(String stepName, RequestCustomizer customizer) {
        try {
            var request = restClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path(AUTHENTICATE_PATH)
                            .queryParam(INDEX_TYPE_PARAM, properties.journeyType())
                            .queryParam(INDEX_VALUE_PARAM, properties.journey())
                            .build(properties.realm()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(API_VERSION_HEADER, API_VERSION);

            Object body = customizer.customize(request);

            String responseBody = request
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return translate(stepName, HttpStatus.OK.value(), responseBody);

        } catch (RestClientResponseException e) {
            // Precisa vir antes do catch abaixo: esta excecao estende a outra.
            //
            // Status de erro chega aqui, e a excecao carrega o corpo. Recusa e
            // sessao expirada sao respostas normais do gateway, nao falhas — por
            // isso viram desfecho em vez de subirem.
            //
            // UTF-8 explicito: a mensagem do gateway vem acentuada, e o padrao do
            // sistema operacional decidiria a decodificacao, produzindo resultados
            // diferentes entre a maquina de desenvolvimento e o conteiner.
            return translate(stepName, e.getStatusCode().value(),
                    e.getResponseBodyAsString(StandardCharsets.UTF_8));

        } catch (RestClientException e) {
            // Gateway inacessivel, prazo esgotado, resposta inutilizavel.
            //
            // A causa nao entra na mensagem: ela carrega o endereco do gateway e
            // o corpo da resposta, e a mensagem pode acabar exposta.
            throw new JourneyUnavailableException(
                    "Falha ao contatar o gateway de identidade no passo de " + stepName, e);
        }
    }

    /**
     * Traduz status e corpo em desfecho.
     * <p>
     * A ordem importa: conclusão antes de desafio, porque uma resposta que traz
     * a sessão emitida encerra o assunto mesmo que traga callbacks residuais.
     */
    private JourneyOutcome translate(String stepName, int status, String body) {
        if (status == HttpStatus.REQUEST_TIMEOUT.value()) {
            // 408: a sessao da jornada expirou por inatividade. Nao e recusa —
            // nada foi negado, o cliente apenas demorou. O canal precisa
            // distinguir para reabrir a jornada em vez de mostrar erro.
            log.debug("Sessão da jornada expirada no passo de {}", stepName);
            return JourneyOutcome.expired();
        }

        if (status == HttpStatus.UNAUTHORIZED.value()) {
            String reason = extractReason(body);
            // O motivo fica no log, nao na resposta ao canal: informar qual
            // fator falhou ajuda quem sonda a mapear o comportamento.
            log.info("Jornada negada pelo gateway no passo de {}: {}", stepName, reason);
            return JourneyOutcome.denied(reason);
        }

        if (status != HttpStatus.OK.value()) {
            // O status entra na mensagem: e um numero, nao revela nada, e sem ele
            // o diagnostico fica cego — 413 aponta para tamanho de corpo, 400
            // para formato, 502 para o gateway. A mensagem do corpo fica so no
            // log, porque pode carregar detalhe interno do provedor.
            log.warn("Status inesperado do gateway no passo de {}: {} — corpo: {}",
                    stepName, status, extractReason(body));

            throw new JourneyUnavailableException(
                    "Gateway de identidade respondeu status " + status
                            + " no passo de " + stepName);
        }

        JourneyStep step = readStep(stepName, body);

        if (step.isComplete()) {
            log.debug("Jornada concluída no passo de {}", stepName);
            return JourneyOutcome.completed(step);
        }

        if (step.hasChallenge()) {
            log.debug("Desafio recebido no passo de {}: {} callback(s)",
                    stepName, step.callbacks().size());
            return JourneyOutcome.challenge(step);
        }

        // 200 sem sessao e sem callbacks: o gateway encerrou sem emitir nada.
        // Nao ha o que apresentar ao canal nem com o que prosseguir, entao e
        // recusa — nunca seguir adiante.
        log.warn("Gateway encerrou a jornada sem sessão e sem desafio no passo de {}", stepName);
        return JourneyOutcome.denied("jornada encerrada sem desfecho");
    }

    private JourneyStep readStep(String stepName, String body) {
        if (body == null || body.isBlank()) {
            // Corpo vazio produziria nulo adiante, longe da origem, e o
            // fail-closed passaria a depender de cada ponto de uso tratar nulo
            // por acidente.
            throw new JourneyUnavailableException(
                    "Gateway de identidade devolveu corpo vazio no passo de " + stepName);
        }
        try {
            return JsonSupport.read(body, JourneyStep.class);
        } catch (Exception e) {
            throw new JourneyUnavailableException(
                    "Resposta do gateway ilegível no passo de " + stepName, e);
        }
    }

    /**
     * Extrai a mensagem de recusa do corpo de erro do gateway.
     * <p>
     * Fica no log e não na resposta ao canal. O formato é
     * {@code {"code":401,"reason":"Unauthorized","message":"..."}}, e a
     * {@code message} é o que distingue biometria reprovada de OTP inválido no
     * diagnóstico.
     * <p>
     * Corpo ilegível não é problema: devolve um rótulo genérico. A recusa vale
     * de qualquer forma, e falhar aqui transformaria uma negação normal em
     * indisponibilidade.
     */
    private static String extractReason(String body) {
        if (body == null || body.isBlank()) {
            return "sem detalhe";
        }
        try {
            // Leitura em arvore em vez de mapa tipado: o corpo de erro nao e
            // contrato estavel, e desserializar para Map sem tipo parametrizado
            // e fragil.
            var message = JsonSupport.readTree(body).get("message");
            return message == null || message.isNull() ? "sem detalhe" : message.asString();
        } catch (Exception e) {
            return "sem detalhe";
        }
    }

    /**
     * Permite que início e continuação acrescentem o que lhes é próprio à mesma
     * chamada, devolvendo o corpo a enviar.
     */
    @FunctionalInterface
    private interface RequestCustomizer {
        Object customize(RestClient.RequestBodySpec request);
    }
}