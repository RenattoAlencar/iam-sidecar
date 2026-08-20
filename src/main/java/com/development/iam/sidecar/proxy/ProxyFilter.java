package com.development.iam.sidecar.proxy;

import com.development.iam.sidecar.config.ChannelProperties;
import com.development.iam.sidecar.config.IdentityProperties;
import com.development.iam.sidecar.identity.AuthenticationJourneyClient;
import com.development.iam.sidecar.identity.JourneyOutcome;
import com.development.iam.sidecar.route.RouteDecision;
import com.development.iam.sidecar.route.RouteResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ponto de entrada do sidecar: classifica cada requisição, conduz a jornada
 * quando a rota exige, e encaminha ao BFF.
 * <p>
 * É o único lugar onde decisão vira resposta HTTP. As classes que decidem —
 * normalizador, resolver, cliente da jornada — não sabem o que é um status; aqui
 * o desfecho de cada uma ganha código, corpo e registro de log.
 *
 * <h2>Ordem das verificações, e por que ela é essa</h2>
 * <ol>
 *   <li><strong>Enquadramento.</strong> Antes de tudo. Se o sidecar e o BFF
 *       podem discordar sobre onde a requisição termina, discutir qual rota ela
 *       é já não faz sentido — e numa rota interceptada a verificação tardia
 *       custaria uma chamada ao gateway antes da recusa, o que transforma
 *       tentativa de smuggling em amplificação de negação de serviço.</li>
 *   <li><strong>Rota.</strong> Normaliza o path e consulta a matriz.</li>
 *   <li><strong>Jornada</strong>, quando a rota está na matriz.</li>
 *   <li><strong>Encaminhamento.</strong></li>
 * </ol>
 *
 * <h2>O que o canal recebe numa rota interceptada</h2>
 * <pre>
 * 401 {
 *   "status": "challenge_required",
 *   "authId": "…",
 *   "callbacks": [ … como o gateway devolveu … ],
 *   "correlationId": "…"
 * }
 * </pre>
 * O {@code authId} vai ao canal porque o sidecar não guarda estado. É decisão
 * registrada, não descuido: com ele em mãos o canal pode continuar a jornada
 * falando direto com o gateway, e o vínculo entre a jornada concluída e a
 * operação que a exigiu deixa de existir. É o que o componente dedicado de
 * guarda de token virá resolver.
 *
 * <h2>Registro de log</h2>
 * Todo registro carrega o identificador de correlação, colocado no contexto logo
 * na entrada e removido no fim.
 * <p>
 * <strong>{@code INFO}</strong> — as transições que sempre interessam: rota
 * interceptada, desfecho da jornada, recusa. É o nível de produção, e o volume
 * acompanha as rotas sensíveis, não o tráfego total.
 * <p>
 * <strong>{@code DEBUG}</strong> — o detalhe para quando algo está estranho:
 * passthrough, presença do token do canal, quantidade de callbacks. Liga-se por
 * ambiente sem mudar código.
 * <p>
 * <strong>Nunca registrado, em nível nenhum:</strong> path cru, corpo, token do
 * canal, identificador da jornada, conteúdo de callback. O path rejeitado é
 * conteúdo controlado por quem chama e pode carregar byte nulo e caractere de
 * controle — registrá-lo seria gravar no log exatamente o que o normalizador
 * acabou de detectar. Os callbacks carregam a captura biométrica e a semente do
 * autenticador.
 */
public class ProxyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ProxyFilter.class);

    private final RouteResolver routeResolver;
    private final RequestForwarder requestForwarder;
    private final AuthenticationJourneyClient journeyClient;
    private final IdentityProperties identityProperties;
    private final ChannelProperties channelProperties;
    private final ObjectMapper objectMapper;

    public ProxyFilter(RouteResolver routeResolver,
                       RequestForwarder requestForwarder,
                       AuthenticationJourneyClient journeyClient,
                       IdentityProperties identityProperties,
                       ChannelProperties channelProperties,
                       ObjectMapper objectMapper) {
        this.routeResolver = routeResolver;
        this.requestForwarder = requestForwarder;
        this.journeyClient = journeyClient;
        this.identityProperties = identityProperties;
        this.channelProperties = channelProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // filterChain nunca e invocado: o sidecar e o fim da cadeia. Nao ha
        // controller nem handler depois dele — toda requisicao termina
        // encaminhada ao BFF ou respondida aqui.

        String correlationId = CorrelationId.resolve(request.getHeader(CorrelationId.HEADER));
        MDC.put(CorrelationId.MDC_KEY, correlationId);

        try {
            handle(request, response, correlationId);
        } finally {
            if (!response.isCommitted()) {
                response.setHeader(CorrelationId.HEADER, correlationId);
            }
            // Sem isso, a thread devolvida ao pool carrega o identificador para
            // a requisicao seguinte e os logs apontam para a requisicao errada.
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private void handle(HttpServletRequest request,
                        HttpServletResponse response,
                        String correlationId) throws IOException {

        Optional<RequestForwarder.RejectionReason> framingRejection =
                requestForwarder.framingRejection(request);

        if (framingRejection.isPresent()) {
            rejectFraming(response, framingRejection.get(), correlationId);
            return;
        }

        RouteDecision decision = routeResolver.resolve(request.getRequestURI(), method(request));

        // A comparacao usa o path normalizado, nunca o bruto: sem isso, uma
        // variacao de escrita — barra final, barra dupla, forma codificada — nao
        // casaria, e a requisicao cairia na matriz como trafego comum. Seria
        // encaminhada ao BFF, que nao tem este endpoint.
        if (decision.outcome() != RouteDecision.Outcome.REJECT
                && channelProperties.challengePath().equals(decision.normalizedPath())) {

            continueJourney(request, response, correlationId);
            return;
        }

        switch (decision.outcome()) {
            case REJECT -> rejectMalformedPath(response, decision, correlationId);
            case PASSTHROUGH -> {
                log.debug("Rota fora da matriz, encaminhando sem verificação");
                forward(request, response, correlationId);
            }
            // TODO: ANTES — rota da matriz era negada com 401 e o gateway nunca
            // era chamado, porque o bloco de identidade não existia.
            // TODO: DEPOIS — a jornada é iniciada e o desafio devolvido ao canal.
            case INTERCEPT -> startJourney(request, response, decision, correlationId);
        }
    }

    /**
     * Inicia a jornada para uma rota da matriz.
     * <p>
     * A requisição de negócio <strong>não</strong> é encaminhada: ela morre aqui,
     * e o canal precisa refazê-la depois de concluir a jornada. É consequência de
     * o sidecar não guardar estado — sem armazenamento, não há onde segurar a
     * requisição original enquanto a confirmação acontece.
     */
    private void startJourney(HttpServletRequest request,
                              HttpServletResponse response,
                              RouteDecision decision,
                              String correlationId) throws IOException {

        String rule = decision.metricTag();
        String channelToken = request.getHeader(identityProperties.channelTokenHeader());

        log.info("Rota interceptada: regra={}, método={}", rule, request.getMethod());

        if (channelToken == null || channelToken.isBlank()) {
            // Sem token nao ha quem autenticar, e chamar o gateway teria desfecho
            // conhecido. Nivel de aviso porque, vindo do canal em producao, e
            // sinal de integracao incorreta.
            log.warn("Rota interceptada sem token do canal no cabeçalho '{}': regra={}",
                    identityProperties.channelTokenHeader(), rule);
            respond(response, HttpStatus.UNAUTHORIZED, "session_required", correlationId);
            return;
        }

        log.debug("Token do canal presente ({} caracteres), iniciando jornada '{}'",
                channelToken.length(), identityProperties.journey());

        // O codigo do autenticador so e repassado quando o canal o apresenta e o
        // cabecalho esta configurado. Ausente, a jornada segue o caminho completo.
        String authenticatorCode = readAuthenticatorCode(request);

        JourneyOutcome outcome;
        try {
            outcome = journeyClient.start(channelToken, authenticatorCode);

        } catch (AuthenticationJourneyClient.JourneyUnavailableException e) {
            // Gateway inacessivel. Fail-closed: nao encaminha. E indisponibilidade,
            // nao recusa — o status precisa dizer isso, senao ninguem investiga e
            // a falha fica escondida atras de mensagens de autenticacao negada.
            log.error("Gateway de identidade indisponível: regra={}", rule, e);
            respond(response, HttpStatus.SERVICE_UNAVAILABLE, "authorization_unavailable",
                    correlationId);
            return;
        }

        applyOutcome(response, outcome, rule, correlationId);
    }

    /**
     * Continua a jornada com a resposta do canal a um passo anterior.
     * <p>
     * Este é o único endpoint próprio do sidecar. Fica aqui, e não num
     * controller, para atravessar as mesmas verificações do restante do tráfego:
     * enquadramento, normalização de path, política de cabeçalhos e teto de
     * corpo. Um controller seria roteado direto pelo Spring, sem passar por
     * nenhuma delas — e as proteções do componente valeriam para um caminho de
     * entrada e não para o outro.
     * <p>
     * O sidecar não interpreta os callbacks: recebe do canal, entrega ao
     * gateway. Serve a todos os passos indistintamente — confirmação, espera,
     * código, chave de dispositivo.
     */
    private void continueJourney(HttpServletRequest request,
                                 HttpServletResponse response,
                                 String correlationId) throws IOException {

        if (!HttpMethod.POST.matches(request.getMethod())) {
            // Metodo diferente nao e resposta a desafio. Recusar aqui evita que
            // uma sonda com GET no endpoint produza chamada ao gateway.
            log.debug("Método não aceito no endpoint de desafio: {}", request.getMethod());
            respond(response, HttpStatus.METHOD_NOT_ALLOWED, "bad_request", correlationId);
            return;
        }

        ChallengeAnswer answer = readChallengeAnswer(request);

        if (answer == null || answer.authId() == null || answer.authId().isBlank()) {
            // Sem identificador nao ha jornada a continuar, e chamar o gateway
            // teria desfecho conhecido.
            log.warn("Resposta de desafio sem identificador de jornada");
            respond(response, HttpStatus.BAD_REQUEST, "bad_request", correlationId);
            return;
        }

        log.info("Resposta de desafio recebida: callbacks={}",
                answer.callbacks() == null ? 0 : answer.callbacks().size());

        JourneyOutcome outcome;
        try {
            outcome = journeyClient.advance(answer.authId(), answer.callbacks());

        } catch (AuthenticationJourneyClient.JourneyUnavailableException e) {
            log.error("Gateway de identidade indisponível ao continuar a jornada", e);
            respond(response, HttpStatus.SERVICE_UNAVAILABLE, "authorization_unavailable",
                    correlationId);
            return;
        }

        applyOutcome(response, outcome, "challenge", correlationId);
    }

    /**
     * Lê o corpo da resposta ao desafio.
     * <p>
     * Corpo ilegível devolve {@code null}, e quem chama recusa. Não se tenta
     * adivinhar: o corpo é o que o canal recebeu do gateway com um campo
     * preenchido, e qualquer coisa fora disso não é resposta a desafio.
     */
    private ChallengeAnswer readChallengeAnswer(HttpServletRequest request) {
        try {
            return objectMapper.readValue(request.getInputStream(), ChallengeAnswer.class);
        } catch (Exception e) {
            // Nem a excecao nem o corpo entram no log: o corpo carrega a resposta
            // do desafio, que pode ser captura biométrica ou código.
            log.debug("Corpo do desafio ilegível");
            return null;
        }
    }

    /**
     * O que o canal envia ao responder um passo da jornada.
     * <p>
     * Mesma forma que o gateway devolveu, com os campos de entrada preenchidos.
     * O canal reenvia inclusive nos passos de espera, sem alterar nada.
     */
    private record ChallengeAnswer(String authId, List<Map<String, Object>> callbacks) {
    }

    /**
     * Lê o código do autenticador, quando o canal o apresenta.
     * <p>
     * Devolve {@code null} quando o cabeçalho não está configurado — há ambiente
     * onde esse atalho da jornada não existe, e ali o sidecar nunca deve enviá-lo.
     */
    private String readAuthenticatorCode(HttpServletRequest request) {
        String header = identityProperties.authenticatorCodeHeader();
        if (header.isBlank()) {
            return null;
        }
        String code = request.getHeader(header);

        if (code != null && !code.isBlank()) {
            // O valor nao entra no log: e credencial de uso unico, mas dentro da
            // janela vale uma autenticacao.
            log.debug("Código de autenticador apresentado pelo canal");
        }
        return code;
    }

    /**
     * Traduz o desfecho da jornada em resposta HTTP.
     * <p>
     * Os quatro desfechos viram status diferentes porque o canal precisa agir de
     * forma diferente em cada um: apresentar o desafio, mostrar recusa, reabrir a
     * jornada ou informar indisponibilidade.
     */
    private void applyOutcome(HttpServletResponse response,
                              JourneyOutcome outcome,
                              String rule,
                              String correlationId) throws IOException {

        switch (outcome.type()) {
            case CHALLENGE -> {
                log.info("Desafio emitido ao canal: regra={}, callbacks={}",
                        rule, outcome.callbacks().size());
                respondChallenge(response, outcome, correlationId);
            }

            case COMPLETED -> {
                // Jornada que conclui logo no inicio, sem desafio. Acontece
                // quando o gateway julga que ja ha confirmacao suficiente.
                //
                // TODO etapa seguinte: obter o token e decidir o que acompanha o
                // encaminhamento. Hoje o canal e informado e refaz a requisicao.
                log.info("Jornada concluída sem desafio: regra={}", rule);
                respondCompleted(response, correlationId);
            }

            case DENIED -> {
                // O motivo fica no log, nunca na resposta: informar qual fator
                // falhou ajuda quem sonda a mapear o comportamento.
                log.info("Jornada negada pelo gateway: regra={}, motivo={}",
                        rule, outcome.reason());
                respond(response, HttpStatus.FORBIDDEN, "denied", correlationId);
            }

            case EXPIRED -> {
                // Nada foi negado — a sessao da jornada apenas expirou. O canal
                // precisa reabrir, e nao mostrar erro.
                log.info("Sessão da jornada expirada: regra={}", rule);
                respond(response, HttpStatus.UNAUTHORIZED, "journey_expired", correlationId);
            }
        }
    }

    /**
     * Devolve o desafio ao canal.
     * <p>
     * Os callbacks vão como vieram do gateway — o canal já sabe interpretá-los,
     * porque é o mesmo formato que ele usaria falando direto com o provedor.
     * <p>
     * Status {@code 401} e não {@code 403}: significa "autentique-se", que é
     * exatamente o que se está pedindo. O canal distingue os dois — um abre a
     * tela de confirmação, o outro mostra erro.
     */
    private void respondChallenge(HttpServletResponse response,
                                  JourneyOutcome outcome,
                                  String correlationId) throws IOException {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "challenge_required");
        body.put("authId", outcome.step().authId());
        body.put("callbacks", outcome.callbacks());
        body.put("correlationId", correlationId);

        write(response, HttpStatus.UNAUTHORIZED, body, correlationId);
    }

    private void respondCompleted(HttpServletResponse response, String correlationId)
            throws IOException {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "authorized");
        body.put("correlationId", correlationId);

        write(response, HttpStatus.OK, body, correlationId);
    }

    private void rejectFraming(HttpServletResponse response,
                               RequestForwarder.RejectionReason reason,
                               String correlationId) throws IOException {

        HttpStatus status = reason == RequestForwarder.RejectionReason.PAYLOAD_TOO_LARGE
                ? HttpStatus.PAYLOAD_TOO_LARGE
                : HttpStatus.BAD_REQUEST;

        log.warn("Requisição recusada antes da matriz: motivo={}", reason);
        respond(response, status, "bad_request", correlationId);
    }

    private void rejectMalformedPath(HttpServletResponse response,
                                     RouteDecision decision,
                                     String correlationId) throws IOException {

        log.warn("Requisição recusada na normalização: motivo={}", decision.rejectionReason());
        respond(response, HttpStatus.BAD_REQUEST, "bad_request", correlationId);
    }

    private void forward(HttpServletRequest request,
                         HttpServletResponse response,
                         String correlationId) throws IOException {

        // O identificador e escrito antes do encaminhamento: assim que o
        // primeiro byte do corpo sai, a resposta esta confirmada e nenhum header
        // novo entra.
        response.setHeader(CorrelationId.HEADER, correlationId);

        try {
            requestForwarder.forward(request, response);
        } catch (RequestForwarder.PayloadTooLargeException e) {
            log.warn("Corpo acima do teto durante o encaminhamento");
            respond(response, HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", correlationId);
        } catch (RequestForwarder.UpstreamException e) {
            // O corpo e generico: detalhar a causa entregaria endereco e porta
            // do BFF a quem chama de fora.
            log.error("Falha ao encaminhar a requisição ao BFF", e);
            respond(response, HttpStatus.BAD_GATEWAY, "bad_gateway", correlationId);
        }
    }

    private static HttpMethod method(HttpServletRequest request) {
        String name = request.getMethod();
        return name == null ? null : HttpMethod.valueOf(name);
    }

    /**
     * Escreve a resposta de erro, sempre com o identificador de correlação.
     * <p>
     * O corpo carrega apenas um código estável e o identificador. Detalhar o
     * motivo ajudaria quem está sondando a descobrir por tentativa e erro qual
     * verificação falhou.
     */
    private void respond(HttpServletResponse response,
                         HttpStatus status,
                         String error,
                         String correlationId) throws IOException {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("correlationId", correlationId);

        write(response, status, body, correlationId);
    }

    private void write(HttpServletResponse response,
                       HttpStatus status,
                       Map<String, Object> body,
                       String correlationId) throws IOException {

        if (response.isCommitted()) {
            log.warn("Resposta já iniciada, recusa não pôde ser escrita: status={}", status.value());
            return;
        }

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(CorrelationId.HEADER, correlationId);
        response.getWriter().write(objectMapper.writeValueAsString(body));

        // Forca o envio: sem isso o corpo fica no buffer do container e pode ser
        // descartado por qualquer componente que reinicie a resposta adiante.
        response.flushBuffer();
    }
}