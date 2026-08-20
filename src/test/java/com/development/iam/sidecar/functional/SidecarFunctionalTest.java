package com.development.iam.sidecar.functional;

import com.development.iam.sidecar.proxy.CorrelationId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cenários ponta a ponta: canal → sidecar → BFF, sobre HTTP real.
 * <p>
 * Sobe o sidecar em porta aleatória e um BFF de eco em outra, ambos em loopback.
 * Não há Docker nem processo externo: o conjunto roda no {@code mvn test} e no
 * pipeline, que é a diferença entre um teste que pega regressão e uma coleção
 * que alguém executa uma vez e esquece.
 *
 * <h2>O que se verifica além do status</h2>
 * Quase todo cenário confere <strong>o que chegou ao BFF</strong>, não apenas o
 * que o sidecar respondeu. Um proxy que devolve o status certo pode ter entregue
 * ao BFF uma requisição com header forjado ou com a cadeia de encaminhamento
 * corrompida — e o canal não teria como perceber.
 * <p>
 * Nas recusas, a asserção é o contador de requisições do BFF: precisa ficar
 * intacto. Verificar só o status não distinguiria "recusou" de "recusou depois
 * de encaminhar".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "proxy.connect-timeout=1s",
                "proxy.read-timeout=1s",
                "proxy.max-body-bytes=1024",
                "proxy.reserved-headers[0]=x-sidecar-verified",

                "proxy.intercept-rules[0].name=pix-transfer",
                "proxy.intercept-rules[0].path=/api/v1/pix/transferencia",
                "proxy.intercept-rules[0].methods[0]=POST",

                "proxy.intercept-rules[1].name=pix-keys-register",
                "proxy.intercept-rules[1].path=/api/v1/pix/chaves",
                "proxy.intercept-rules[1].methods[0]=POST",

                // O contexto exige a configuração do gateway para subir, mesmo
                // que nenhum cenário aqui chegue a chamá-lo: rota interceptada é
                // barrada antes disso, e as demais nem passam por confirmação.
                //
                // O endereço aponta para um host que não resolve de propósito. Se
                // algum cenário passar a alcançar o gateway sem que ninguém
                // perceba, ele falha por indisponibilidade em vez de sair pela
                // rede — que é o comportamento correto num teste que deveria ser
                // fechado.
                "identity.base-url=https://gateway-inexistente.invalid/am",
                "identity.journey=jornada-de-teste",
                "identity.client-id=cliente-de-teste",
                "identity.client-secret=segredo-de-teste",
                "identity.redirect-uri=https://retorno-de-teste.invalid/callback",
                "identity.session-cookie-name=cookie-de-sessao",
                "identity.channel-token-header=x-canal-autenticacao",
                "identity.connect-timeout=200ms",
                "identity.read-timeout=200ms"
        })
class SidecarFunctionalTest {

    private static final EchoBackend BACKEND = startBackend();

    private static EchoBackend startBackend() {
        try {
            return EchoBackend.start();
        } catch (IOException e) {
            throw new IllegalStateException("Não foi possível subir o BFF de eco", e);
        }
    }

    /**
     * O alvo aponta para o BFF de eco, em loopback e porta aleatória. A
     * validação de boot exige loopback, e {@code 127.0.0.1} atende — a mesma
     * invariante que vale em produção vale aqui, sem exceção para teste.
     */
    @DynamicPropertySource
    static void proxyTarget(DynamicPropertyRegistry registry) {
        registry.add("proxy.target", BACKEND::baseUrl);
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.close();
    }

    @LocalServerPort
    private int sidecarPort;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @BeforeEach
    void resetBackend() {
        BACKEND.resetCounter();
    }

    private HttpRequest.Builder toSidecar(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + sidecarPort + path))
                .timeout(Duration.ofSeconds(10));
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Nested
    @DisplayName("cenário 1 — o proxy é transparente")
    class TransparentProxy {

        @Test
        @DisplayName("resposta do BFF chega ao canal com status, headers e corpo preservados")
        void preservesBackendResponse() throws Exception {
            HttpResponse<String> response = send(toSidecar("/api/v1/conta/saldo").GET().build());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("X-Backend-Marker")).contains("echo");
            assertThat(response.body()).contains("\"method\":\"GET\"");
            assertThat(BACKEND.receivedRequests()).isEqualTo(1);
        }

        @Test
        @DisplayName("o BFF recebe o path e a query como foram enviados")
        void preservesPathAndQuery() throws Exception {
            HttpResponse<String> response = send(
                    toSidecar("/api/v1/conta/extrato?de=2026-01-01&ate=2026-01-31").GET().build());

            assertThat(response.body())
                    .contains("\"path\":\"/api/v1/conta/extrato\"")
                    .contains("\"query\":\"de=2026-01-01&ate=2026-01-31\"");
        }

        @Test
        @DisplayName("o corpo atravessa íntegro")
        void preservesBody() throws Exception {
            String payload = "{\"valor\":10.5,\"descricao\":\"acentuação e \\\"aspas\\\"\"}";

            HttpResponse<String> response = send(toSidecar("/api/v1/conta/extrato/busca")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build());

            assertThat(response.body()).contains("\"bodyLength\":" + payload.getBytes().length);
        }

        @Test
        @DisplayName("o status de erro do BFF é repassado sem interpretação")
        void relaysBackendErrorStatus() throws Exception {
            HttpResponse<String> response = send(toSidecar("/__status/503").GET().build());

            assertThat(response.statusCode()).isEqualTo(503);
        }
    }

    @Nested
    @DisplayName("cenário 2 — a matriz decide o que é verificado")
    class InterceptionMatrix {

        @Test
        @DisplayName("rota fora da matriz atravessa sem verificação")
        void routeOutsideMatrixPassesThrough() throws Exception {
            HttpResponse<String> response = send(toSidecar("/api/v1/conta/saldo").GET().build());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(BACKEND.receivedRequests()).isEqualTo(1);
        }

        /**
         * Rota interceptada não alcança o BFF, aconteça o que acontecer com a
         * jornada. O contador é a asserção que separa "barrou" de "barrou depois
         * de encaminhar".
         * <p>
         * Sem token do canal, o sidecar recusa antes de chamar o gateway — que
         * aqui não existe. É o cenário que isola a decisão de rota do
         * comportamento da jornada.
         */
        @Test
        @DisplayName("rota interceptada sem token do canal é barrada e não alcança o BFF")
        void interceptedRouteWithoutTokenIsBlockedAndNeverReachesBackend() throws Exception {
            HttpResponse<String> response = send(toSidecar("/api/v1/pix/transferencia")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"valor\":50}"))
                    .build());

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).contains("session_required");
            assertThat(BACKEND.receivedRequests()).isZero();
        }

        /**
         * Com token, o sidecar tenta a jornada. O gateway configurado aponta para
         * um endereço que não existe, e o que se verifica é o fail-closed: a
         * requisição não alcança o BFF, aconteça o que acontecer com a tentativa.
         * <p>
         * <strong>O status exato não é asserção.</strong> Ele depende de como a
         * rede se comporta com um endereço inexistente — pode ser recusa de
         * conexão, prazo esgotado, ou a resposta de um proxy corporativo que
         * intercepta a saída. Em qualquer um dos casos a garantia é a mesma, e
         * fixar um status faria o teste falhar por ambiente em vez de por
         * defeito.
         * <p>
         * A tradução de cada situação em status é exercitada no teste do filtro,
         * onde o cliente é substituído e o desfecho é controlado.
         */
        @Test
        @DisplayName("gateway inalcançável não libera a rota interceptada")
        void unreachableGatewayDoesNotReleaseInterceptedRoute() throws Exception {
            HttpResponse<String> response = send(toSidecar("/api/v1/pix/transferencia")
                    .header("x-canal-autenticacao", "eyJhbGciOiJIUzI1NiJ9.token.assinatura")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"valor\":50}"))
                    .build());

            assertThat(response.statusCode())
                    .as("a requisição não pode ser liberada quando a confirmação não acontece")
                    .isNotEqualTo(200);

            assertThat(BACKEND.receivedRequests())
                    .as("nenhuma requisição pode alcançar o BFF sem confirmação")
                    .isZero();
        }

        /**
         * O mesmo endereço com verbos diferentes e desfechos opostos: listar as
         * próprias chaves é consulta, cadastrar uma nova redireciona dinheiro.
         */
        @Test
        @DisplayName("o verbo muda o desfecho no mesmo endereço")
        void methodChangesOutcomeOnSamePath() throws Exception {
            HttpResponse<String> listing = send(toSidecar("/api/v1/pix/chaves").GET().build());
            assertThat(listing.statusCode()).isEqualTo(200);

            BACKEND.resetCounter();

            HttpResponse<String> registration = send(toSidecar("/api/v1/pix/chaves")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build());

            // Sem token do canal: barrado antes de qualquer chamada ao gateway.
            assertThat(registration.statusCode()).isEqualTo(401);
            assertThat(BACKEND.receivedRequests()).isZero();
        }
    }

    @Nested
    @DisplayName("cenário 3 — variação de escrita não contorna a matriz")
    class PathVariations {

        @Test
        @DisplayName("barra final não contorna a interceptação")
        void trailingSlashDoesNotBypass() throws Exception {
            HttpResponse<String> response = send(toSidecar("/api/v1/pix/transferencia/")
                    .POST(HttpRequest.BodyPublishers.noBody()).build());

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(BACKEND.receivedRequests()).isZero();
        }

        @Test
        @DisplayName("barras repetidas não contornam a interceptação")
        void duplicatedSlashesDoNotBypass() throws Exception {
            HttpResponse<String> response = send(toSidecar("/api//v1/pix/transferencia")
                    .POST(HttpRequest.BodyPublishers.noBody()).build());

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(BACKEND.receivedRequests()).isZero();
        }

        @Test
        @DisplayName("percent-encoding não contorna a interceptação")
        void percentEncodingDoesNotBypass() throws Exception {
            HttpResponse<String> response = send(toSidecar("/api/v1/%70ix/transferencia")
                    .POST(HttpRequest.BodyPublishers.noBody()).build());

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(BACKEND.receivedRequests()).isZero();
        }

        @Test
        @DisplayName("navegação de diretório é recusada e não alcança o BFF")
        void directoryTraversalIsRejected() throws Exception {
            HttpResponse<String> response = send(
                    toSidecar("/api/v1/pix/../pix/transferencia")
                            .POST(HttpRequest.BodyPublishers.noBody()).build());

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(BACKEND.receivedRequests()).isZero();
        }

        @Test
        @DisplayName("separador codificado é recusado")
        void encodedSeparatorIsRejected() throws Exception {
            HttpResponse<String> response = send(toSidecar("/api%2Fv1/pix/transferencia")
                    .POST(HttpRequest.BodyPublishers.noBody()).build());

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(BACKEND.receivedRequests()).isZero();
        }
    }

    @Nested
    @DisplayName("cenário 4 — headers")
    class Headers {

        /**
         * A garantia que sustenta o controle. Se o header reservado atravessasse,
         * o canal declararia por conta própria que a confirmação aconteceu.
         */
        @Test
        @DisplayName("header reservado enviado pelo canal não alcança o BFF")
        void reservedHeaderFromChannelDoesNotReachBackend() throws Exception {
            HttpResponse<String> response = send(toSidecar("/api/v1/conta/saldo")
                    .header("x-sidecar-verified", "true")
                    .GET().build());

            assertThat(response.body()).doesNotContain("x-sidecar-verified");
        }

        @Test
        @DisplayName("header de aplicação chega intacto ao BFF")
        void applicationHeaderReachesBackend() throws Exception {
            HttpResponse<String> response = send(toSidecar("/api/v1/conta/saldo")
                    .header("X-Canal-Origem", "superapp")
                    .GET().build());

            assertThat(response.body()).contains("\"x-canal-origem\":[\"superapp\"]");
        }

        /**
         * O IP real do cliente é o dado usado em investigação de fraude: precisa
         * chegar ao BFF uma vez só, com a cadeia inteira.
         */
        @Test
        @DisplayName("a cadeia de encaminhamento chega com um único valor")
        void forwardedChainArrivesOnce() throws Exception {
            HttpResponse<String> response = send(toSidecar("/api/v1/conta/saldo")
                    .header("X-Forwarded-For", "203.0.113.9")
                    .GET().build());

            assertThat(response.body())
                    .containsPattern("\"x-forwarded-for\":\\[\"203\\.0\\.113\\.9, [^\"]+\"\\]");
        }

        @Test
        @DisplayName("header hop-by-hop não alcança o BFF")
        void hopByHopHeaderDoesNotReachBackend() throws Exception {
            HttpResponse<String> response = send(toSidecar("/api/v1/conta/saldo")
                    .header("TE", "trailers")
                    .GET().build());

            assertThat(response.body()).doesNotContain("\"te\":");
        }
    }

    @Nested
    @DisplayName("cenário 5 — rastreabilidade")
    class Traceability {

        @Test
        @DisplayName("toda resposta carrega o identificador de correlação")
        void everyResponseCarriesCorrelationId() throws Exception {
            HttpResponse<String> response = send(toSidecar("/api/v1/conta/saldo").GET().build());

            assertThat(response.headers().firstValue(CorrelationId.HEADER)).isPresent();
        }

        @Test
        @DisplayName("o identificador enviado pelo canal é propagado")
        void incomingCorrelationIdIsPropagated() throws Exception {
            HttpResponse<String> response = send(toSidecar("/api/v1/conta/saldo")
                    .header(CorrelationId.HEADER, "chamado-4711")
                    .GET().build());

            assertThat(response.headers().firstValue(CorrelationId.HEADER)).contains("chamado-4711");
        }

        /**
         * Valor com caractere fora do formato permitiria escrever registro falso
         * dentro do arquivo de log. É substituído sem aviso.
         */
        @Test
        @DisplayName("identificador que contaminaria o log é substituído")
        void logPollutingCorrelationIdIsReplaced() throws Exception {
            HttpResponse<String> response = send(toSidecar("/api/v1/conta/saldo")
                    .header(CorrelationId.HEADER, "abc.def")
                    .GET().build());

            assertThat(response.headers().firstValue(CorrelationId.HEADER))
                    .isPresent()
                    .get().asString().isNotEqualTo("abc.def").matches("[A-Za-z0-9_-]+");
        }

        @Test
        @DisplayName("a resposta de erro carrega o identificador no corpo")
        void errorBodyCarriesCorrelationId() throws Exception {
            HttpResponse<String> response = send(toSidecar("/api/v1/pix/transferencia")
                    .POST(HttpRequest.BodyPublishers.noBody()).build());

            assertThat(response.body()).contains("correlationId");
        }

        /**
         * O endpoint de resposta ao desafio é do sidecar. Precisa ser tratado
         * aqui, nunca encaminhado ao BFF — que não o tem.
         */
        @Test
        @DisplayName("o endpoint de desafio não é encaminhado ao BFF")
        void challengeEndpointIsNotForwarded() throws Exception {
            HttpResponse<String> response = send(toSidecar("/ciam/challenge")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"callbacks\":[]}"))
                    .build());

            // Sem identificador de jornada: recusado antes de chamar o gateway.
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(BACKEND.receivedRequests()).isZero();
        }
    }

    @Nested
    @DisplayName("cenário 6 — limites e falhas")
    class LimitsAndFailures {

        @Test
        @DisplayName("corpo acima do teto é recusado e não alcança o BFF")
        void oversizedBodyIsRejected() throws Exception {
            String payload = "x".repeat(2048);

            HttpResponse<String> response = send(toSidecar("/api/v1/conta/extrato/busca")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build());

            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(BACKEND.receivedRequests()).isZero();
        }

        @Test
        @DisplayName("corpo dentro do teto atravessa")
        void bodyWithinLimitPassesThrough() throws Exception {
            String payload = "x".repeat(512);

            HttpResponse<String> response = send(toSidecar("/api/v1/conta/extrato/busca")
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build());

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"bodyLength\":512");
        }

        /**
         * BFF que não responde no prazo vira falha de dependência, e o corpo
         * devolvido não revela endereço nem porta do backend.
         */
        @Test
        @DisplayName("BFF lento vira 502 sem revelar o endereço interno")
        void slowBackendBecomesBadGateway() throws Exception {
            HttpResponse<String> response = send(toSidecar("/__slow").GET().build());

            assertThat(response.statusCode()).isEqualTo(502);
            assertThat(response.body())
                    .doesNotContain("127.0.0.1")
                    .doesNotContain(String.valueOf(BACKEND.port()));
        }
    }
}