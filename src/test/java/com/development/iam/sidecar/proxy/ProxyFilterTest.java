package com.development.iam.sidecar.proxy;

import com.development.iam.sidecar.config.ChannelProperties;
import com.development.iam.sidecar.config.IdentityProperties;
import com.development.iam.sidecar.config.InterceptRule;
import com.development.iam.sidecar.identity.AuthenticationJourneyClient;
import com.development.iam.sidecar.identity.JourneyOutcome;
import com.development.iam.sidecar.identity.JourneyStep;
import com.development.iam.sidecar.route.RouteResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifica a tradução de decisão em resposta HTTP e, principalmente, o que
 * <strong>não</strong> chega ao BFF.
 * <p>
 * Um filtro que responde o status certo mas encaminha assim mesmo é pior do que
 * um que responde errado: a resposta ao canal esconde que a requisição passou.
 * Por isso quase todo teste aqui confere o encaminhamento, não só o status.
 */
class ProxyFilterTest {

    private static final String PIX_TRANSFER = "/api/v1/pix/transferencia";
    private static final String BALANCE = "/api/v1/conta/saldo";

    private static final String TOKEN_HEADER = "x-canal-autenticacao";
    private static final String CODE_HEADER = "x-canal-codigo";
    private static final String CHANNEL_TOKEN = "eyJhbGciOiJIUzI1NiJ9.token-do-canal";
    private static final String AUTH_ID = "identificador-da-jornada";
    private static final String CHALLENGE_PATH = "/ciam/challenge";

    private final RequestForwarder forwarder = mock(RequestForwarder.class);
    private final AuthenticationJourneyClient journeyClient =
            mock(AuthenticationJourneyClient.class);
    private final MockFilterChain chain = new MockFilterChain();

    private ProxyFilter filter;

    private static IdentityProperties identityProperties() {
        return new IdentityProperties(
                URI.create("https://gateway.exemplo.com.br/am"),
                "alpha", "jornada-de-teste", "service",
                "cliente", "segredo", "https://retorno/callback", "openid", "cookie-de-sessao",
                TOKEN_HEADER, CODE_HEADER,
                Duration.ofSeconds(2), Duration.ofSeconds(10));
    }

    private static List<Map<String, Object>> callbacks() {
        return List.of(Map.of(
                "type", "NameCallback",
                "output", List.of(Map.of("name", "prompt", "value", "CHALLENGE_REQUIRED"))));
    }

    private static JourneyOutcome challenge() {
        return JourneyOutcome.challenge(new JourneyStep(AUTH_ID, callbacks(), null));
    }

    /**
     * Requisição com o token do canal, como o gateway de borda a entregaria.
     */
    private static MockHttpServletRequest authenticated(String method, String uri) {
        MockHttpServletRequest request = request(method, uri);
        request.addHeader(TOKEN_HEADER, CHANNEL_TOKEN);
        return request;
    }

    private static RouteResolver resolver() {
        return new RouteResolver(List.of(
                new InterceptRule("pix-transfer", PIX_TRANSFER, Set.of(HttpMethod.POST))));
    }

    @BeforeEach
    void setUp() {
        when(forwarder.framingRejection(any())).thenReturn(Optional.empty());
        filter = new ProxyFilter(resolver(), forwarder, journeyClient,
                identityProperties(), new ChannelProperties(CHALLENGE_PATH),
                new ObjectMapper());
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("10.1.2.3");
        return request;
    }

    @Nested
    @DisplayName("classificação da requisição")
    class RequestClassification {

        @Test
        @DisplayName("rota fora da matriz é encaminhada")
        void passthroughIsForwarded() throws Exception {
            filter.doFilter(request("GET", BALANCE), new MockHttpServletResponse(), chain);

            verify(forwarder).forward(any(), any());
        }

        /**
         * A requisição de negócio morre aqui: sem estado, não há onde segurá-la
         * enquanto a confirmação acontece. O canal a refaz depois.
         */
        @Test
        @DisplayName("rota interceptada dispara a jornada e não chega ao BFF")
        void interceptedRouteStartsJourneyAndIsNotForwarded() throws Exception {
            when(journeyClient.start(any(), any())).thenReturn(challenge());
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(authenticated("POST", PIX_TRANSFER), response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            verify(journeyClient).start(eq(CHANNEL_TOKEN), any());
            verify(forwarder, never()).forward(any(), any());
        }

        @Test
        @DisplayName("path malformado é recusado e não chega ao BFF")
        void malformedPathIsRejectedAndNotForwarded() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request("POST", "/api/v1/pix/../pix/transferencia"), response, chain);

            assertThat(response.getStatus()).isEqualTo(400);
            verify(forwarder, never()).forward(any(), any());
        }

        /**
         * O método faz parte da chave: o mesmo path com verbo fora da regra é
         * encaminhado sem verificação.
         */
        @Test
        @DisplayName("mesmo path com verbo fora da regra é encaminhado")
        void undeclaredMethodIsForwarded() throws Exception {
            filter.doFilter(request("GET", PIX_TRANSFER), new MockHttpServletResponse(), chain);

            verify(forwarder).forward(any(), any());
        }
    }

    @Nested
    @DisplayName("jornada em rota interceptada")
    class Journey {

        /**
         * Os callbacks vão como vieram do gateway, e o identificador da jornada
         * acompanha — o canal precisa dele para continuar.
         */
        @Test
        @DisplayName("o desafio é devolvido ao canal com o identificador da jornada")
        void challengeIsReturnedWithJourneyIdentifier() throws Exception {
            when(journeyClient.start(any(), any())).thenReturn(challenge());
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(authenticated("POST", PIX_TRANSFER), response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString())
                    .contains("challenge_required")
                    .contains(AUTH_ID)
                    .contains("CHALLENGE_REQUIRED");
        }

        /**
         * Sem token não há quem autenticar, e chamar o gateway teria desfecho
         * conhecido.
         */
        @Test
        @DisplayName("rota interceptada sem token do canal não chama o gateway")
        void missingChannelTokenDoesNotCallGateway() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request("POST", PIX_TRANSFER), response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("session_required");
            verify(journeyClient, never()).start(any(), any());
        }

        /**
         * O código encurta a jornada. É repassado sem que o sidecar o
         * interprete.
         */
        @Test
        @DisplayName("o código do autenticador é repassado quando o canal o apresenta")
        void authenticatorCodeIsForwarded() throws Exception {
            when(journeyClient.start(any(), any())).thenReturn(challenge());

            MockHttpServletRequest request = authenticated("POST", PIX_TRANSFER);
            request.addHeader(CODE_HEADER, "149707");

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            verify(journeyClient).start(CHANNEL_TOKEN, "149707");
        }

        /**
         * {@code 403} e não {@code 401}: o cliente autenticou e não pode. O
         * canal trata os dois de formas diferentes — um abre a tela de
         * confirmação, o outro mostra erro.
         */
        @Test
        @DisplayName("jornada negada vira 403 e não alcança o BFF")
        void deniedJourneyIsForbidden() throws Exception {
            when(journeyClient.start(any(), any()))
                    .thenReturn(JourneyOutcome.denied("Biometria recusada"));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(authenticated("POST", PIX_TRANSFER), response, chain);

            assertThat(response.getStatus()).isEqualTo(403);
            verify(forwarder, never()).forward(any(), any());
        }

        /**
         * O motivo da recusa fica no log: informar qual fator falhou ajuda quem
         * sonda a mapear o comportamento.
         */
        @Test
        @DisplayName("a resposta de recusa não revela qual fator falhou")
        void denialDoesNotRevealFailedFactor() throws Exception {
            when(journeyClient.start(any(), any()))
                    .thenReturn(JourneyOutcome.denied("Biometria recusada"));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(authenticated("POST", PIX_TRANSFER), response, chain);

            assertThat(response.getContentAsString()).doesNotContain("Biometria");
        }

        /**
         * Nada foi negado — a sessão apenas expirou. O canal precisa reabrir a
         * jornada, e não mostrar erro.
         */
        @Test
        @DisplayName("sessão expirada tem código próprio, distinto de recusa")
        void expiredJourneyHasItsOwnCode() throws Exception {
            when(journeyClient.start(any(), any())).thenReturn(JourneyOutcome.expired());
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(authenticated("POST", PIX_TRANSFER), response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("journey_expired");
        }

        /**
         * Indisponibilidade não é recusa. O status precisa dizer isso, senão
         * ninguém investiga — e uma falha de infraestrutura fica escondida atrás
         * de mensagens de autenticação negada.
         */
        @Test
        @DisplayName("gateway indisponível vira 503 e não encaminha")
        void unavailableGatewayIsServiceUnavailable() throws Exception {
            when(journeyClient.start(any(), any())).thenThrow(
                    new AuthenticationJourneyClient.JourneyUnavailableException("fora do ar"));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(authenticated("POST", PIX_TRANSFER), response, chain);

            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getContentAsString()).contains("authorization_unavailable");
            verify(forwarder, never()).forward(any(), any());
        }

        /**
         * Rota fora da matriz não custa uma chamada ao gateway — o tráfego comum
         * é a maior parte do volume.
         */
        @Test
        @DisplayName("rota fora da matriz não chama o gateway")
        void passthroughDoesNotCallGateway() throws Exception {
            filter.doFilter(authenticated("GET", BALANCE), new MockHttpServletResponse(), chain);

            verify(journeyClient, never()).start(any(), any());
            verify(forwarder).forward(any(), any());
        }
    }

    @Nested
    @DisplayName("continuação da jornada")
    class ChallengeEndpoint {

        private MockHttpServletRequest challengeRequest(String body) {
            MockHttpServletRequest request = authenticated("POST", CHALLENGE_PATH);
            request.setContentType("application/json");
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
            return request;
        }

        private static String answerWith(String authId) {
            return "{\"authId\":\"" + authId + "\","
                    + "\"callbacks\":[{\"type\":\"NameCallback\","
                    + "\"input\":[{\"name\":\"IDToken1\",\"value\":\"resposta\"}]}]}";
        }

        /**
         * O endpoint é do sidecar, não do BFF. Encaminhá-lo produziria
         * {@code 404} e o canal ficaria sem saber por quê.
         */
        @Test
        @DisplayName("é tratado pelo sidecar e nunca encaminhado")
        void isHandledBySidecarNeverForwarded() throws Exception {
            when(journeyClient.advance(any(), any())).thenReturn(challenge());

            filter.doFilter(challengeRequest(answerWith(AUTH_ID)),
                    new MockHttpServletResponse(), chain);

            verify(forwarder, never()).forward(any(), any());
            verify(journeyClient).advance(eq(AUTH_ID), any());
        }

        /**
         * Sem normalizar, uma variação de escrita cairia na matriz como tráfego
         * comum e seria encaminhada ao BFF, que não tem este endpoint.
         */
        @Test
        @DisplayName("variação de escrita do path continua sendo tratada")
        void pathVariationIsStillHandled() throws Exception {
            when(journeyClient.advance(any(), any())).thenReturn(challenge());

            MockHttpServletRequest request = authenticated("POST", CHALLENGE_PATH + "/");
            request.setContentType("application/json");
            request.setContent(answerWith(AUTH_ID).getBytes(StandardCharsets.UTF_8));

            filter.doFilter(request, new MockHttpServletResponse(), chain);

            verify(journeyClient).advance(any(), any());
            verify(forwarder, never()).forward(any(), any());
        }

        /**
         * O gateway espera os callbacks de volta como os enviou. Alterar
         * estrutura, ordem ou campos de saída quebra a jornada.
         */
        @Test
        @DisplayName("os callbacks são repassados sem interpretação")
        void callbacksArePassedThroughUntouched() throws Exception {
            when(journeyClient.advance(any(), any())).thenReturn(challenge());

            filter.doFilter(challengeRequest(answerWith(AUTH_ID)),
                    new MockHttpServletResponse(), chain);

            verify(journeyClient).advance(AUTH_ID, List.of(Map.of(
                    "type", "NameCallback",
                    "input", List.of(Map.of("name", "IDToken1", "value", "resposta")))));
        }

        @Test
        @DisplayName("o próximo desafio é devolvido ao canal")
        void nextChallengeIsReturned() throws Exception {
            when(journeyClient.advance(any(), any())).thenReturn(challenge());
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(challengeRequest(answerWith(AUTH_ID)), response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString())
                    .contains("challenge_required")
                    .contains(AUTH_ID);
        }

        @Test
        @DisplayName("jornada concluída devolve autorização ao canal")
        void completedJourneyReturnsAuthorized() throws Exception {
            when(journeyClient.advance(any(), any()))
                    .thenReturn(JourneyOutcome.completed(
                            new JourneyStep(null, List.of(), "sessao-emitida")));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(challengeRequest(answerWith(AUTH_ID)), response, chain);

            assertThat(response.getStatus()).isEqualTo(200);
            assertThat(response.getContentAsString()).contains("authorized");
        }

        /**
         * A sessão emitida pelo gateway é credencial. O canal recebe apenas a
         * confirmação de que a jornada concluiu.
         */
        @Test
        @DisplayName("a sessão emitida não é devolvida ao canal")
        void issuedSessionIsNotReturnedToChannel() throws Exception {
            when(journeyClient.advance(any(), any()))
                    .thenReturn(JourneyOutcome.completed(
                            new JourneyStep(null, List.of(), "sessao-emitida")));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(challengeRequest(answerWith(AUTH_ID)), response, chain);

            assertThat(response.getContentAsString()).doesNotContain("sessao-emitida");
        }

        /**
         * Sem identificador não há jornada a continuar, e chamar o gateway teria
         * desfecho conhecido.
         */
        @Test
        @DisplayName("resposta sem identificador de jornada não chama o gateway")
        void answerWithoutJourneyIdentifierDoesNotCallGateway() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(challengeRequest("{\"callbacks\":[]}"), response, chain);

            assertThat(response.getStatus()).isEqualTo(400);
            verify(journeyClient, never()).advance(any(), any());
        }

        @Test
        @DisplayName("corpo ilegível é recusado sem chamar o gateway")
        void unreadableBodyIsRejected() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(challengeRequest("isso nao e json"), response, chain);

            assertThat(response.getStatus()).isEqualTo(400);
            verify(journeyClient, never()).advance(any(), any());
        }

        /**
         * Método diferente não é resposta a desafio. Recusar evita que uma sonda
         * com GET no endpoint produza chamada ao gateway.
         */
        @Test
        @DisplayName("método diferente de POST não chama o gateway")
        void otherMethodsDoNotCallGateway() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(authenticated("GET", CHALLENGE_PATH), response, chain);

            assertThat(response.getStatus()).isEqualTo(405);
            verify(journeyClient, never()).advance(any(), any());
            verify(forwarder, never()).forward(any(), any());
        }

        @Test
        @DisplayName("gateway indisponível vira 503")
        void unavailableGatewayIsServiceUnavailable() throws Exception {
            when(journeyClient.advance(any(), any())).thenThrow(
                    new AuthenticationJourneyClient.JourneyUnavailableException("fora do ar"));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(challengeRequest(answerWith(AUTH_ID)), response, chain);

            assertThat(response.getStatus()).isEqualTo(503);
        }
    }

    @Nested
    @DisplayName("enquadramento verificado antes da matriz")
    class FramingCheckedFirst {

        /**
         * A ordem importa: se o sidecar e o BFF podem discordar sobre onde a
         * requisição termina, discutir qual rota ela é já não faz sentido — e
         * numa rota interceptada a verificação tardia custaria uma chamada ao
         * gateway antes da recusa.
         */
        @Test
        @DisplayName("enquadramento ambíguo é recusado antes de resolver a rota")
        void ambiguousFramingIsRejectedBeforeRouting() throws Exception {
            when(forwarder.framingRejection(any()))
                    .thenReturn(Optional.of(RequestForwarder.RejectionReason.AMBIGUOUS_FRAMING));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(authenticated("POST", PIX_TRANSFER), response, chain);

            assertThat(response.getStatus()).isEqualTo(400);
            verify(journeyClient, never()).start(any(), any());
            verify(forwarder, never()).forward(any(), any());
        }

        @Test
        @DisplayName("corpo declarado acima do teto vira 413")
        void oversizedDeclaredBodyBecomesPayloadTooLarge() throws Exception {
            when(forwarder.framingRejection(any()))
                    .thenReturn(Optional.of(RequestForwarder.RejectionReason.PAYLOAD_TOO_LARGE));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request("POST", BALANCE), response, chain);

            assertThat(response.getStatus()).isEqualTo(413);
        }
    }

    @Nested
    @DisplayName("falhas do encaminhamento")
    class ForwardingFailures {

        /**
         * BFF fora do ar é falha de dependência, não erro do chamador — e o
         * corpo não revela endereço nem porta.
         */
        @Test
        @DisplayName("BFF indisponível vira 502 sem detalhar a causa")
        void upstreamFailureBecomesBadGateway() throws Exception {
            doThrow(new RequestForwarder.UpstreamException("falhou", new RuntimeException()))
                    .when(forwarder).forward(any(), any());
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request("GET", BALANCE), response, chain);

            assertThat(response.getStatus()).isEqualTo(502);
            assertThat(response.getContentAsString())
                    .doesNotContain("127.0.0.1")
                    .doesNotContain("8081");
        }

        @Test
        @DisplayName("corpo acima do teto durante a transferência vira 413")
        void oversizedActualBodyBecomesPayloadTooLarge() throws Exception {
            doThrow(new RequestForwarder.PayloadTooLargeException())
                    .when(forwarder).forward(any(), any());
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request("GET", BALANCE), response, chain);

            assertThat(response.getStatus()).isEqualTo(413);
        }
    }

    @Nested
    @DisplayName("rastreabilidade")
    class Traceability {

        @Test
        @DisplayName("toda resposta carrega o identificador de correlação")
        void everyResponseCarriesCorrelationId() throws Exception {
            when(journeyClient.start(any(), any())).thenReturn(challenge());
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(authenticated("POST", PIX_TRANSFER), response, chain);

            assertThat(response.getHeader(CorrelationId.HEADER)).isNotBlank();
        }

        @Test
        @DisplayName("o identificador enviado pelo canal é propagado")
        void incomingCorrelationIdIsPropagated() throws Exception {
            MockHttpServletRequest request = request("GET", BALANCE);
            request.addHeader(CorrelationId.HEADER, "chamado-4711");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, chain);

            assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("chamado-4711");
        }

        /**
         * É o que liga um chamado de suporte a uma linha de log. Sem ele na
         * resposta de erro, a investigação começa por horário aproximado.
         */
        @Test
        @DisplayName("a resposta de erro carrega o identificador no corpo")
        void errorBodyCarriesCorrelationId() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request("POST", PIX_TRANSFER), response, chain);

            assertThat(response.getContentAsString())
                    .contains("correlationId")
                    .contains(response.getHeader(CorrelationId.HEADER));
        }

        /**
         * Detalhar qual verificação falhou ajudaria quem está sondando a
         * descobrir o comportamento por tentativa e erro.
         */
        @Test
        @DisplayName("a resposta de erro não detalha o motivo interno")
        void errorBodyDoesNotLeakInternals() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request("POST", "/api/v1/pix/../x"), response, chain);

            assertThat(response.getContentAsString())
                    .doesNotContain("MALFORMED_PATH")
                    .doesNotContain("normaliz");
        }
    }
}