package com.development.iam.sidecar.proxy;

import com.development.iam.sidecar.config.InterceptRule;
import com.development.iam.sidecar.config.ProxyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestForwarderTest {

    private static final String RESERVED_HEADER = "x-sidecar-verified";
    private static final long MAX_BODY = 1024;

    private final HttpClient httpClient = mock(HttpClient.class);
    private final AtomicReference<HttpRequest> captured = new AtomicReference<>();

    private RequestForwarder forwarder;

    private static ProxyProperties properties() {
        return new ProxyProperties(
                URI.create("http://127.0.0.1:8081"),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10),
                MAX_BODY,
                List.of(RESERVED_HEADER),
                List.of(new InterceptRule("pix", "/api/v1/pix/**", Set.of(HttpMethod.POST))));
    }

    @SuppressWarnings("unchecked")
    private void respondWith(int status, Map<String, List<String>> headers, String body)
            throws Exception {

        HttpResponse<java.io.InputStream> upstream = mock(HttpResponse.class);
        when(upstream.statusCode()).thenReturn(status);
        when(upstream.headers()).thenReturn(HttpHeaders.of(headers, (k, v) -> true));
        when(upstream.body()).thenReturn(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    captured.set(invocation.getArgument(0));
                    return upstream;
                });
    }

    @BeforeEach
    void setUp() {
        forwarder = new RequestForwarder(httpClient, properties(),
                new ProxyHeaderPolicy(List.of(RESERVED_HEADER)));
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("10.1.2.3");
        request.setScheme("https");
        return request;
    }

    @Nested
    @DisplayName("requisição entregue ao BFF")
    class UpstreamRequest {

        @Test
        @DisplayName("troca apenas o host, preservando path e query")
        void swapsOnlyTheHost() throws Exception {
            respondWith(200, Map.of(), "");
            MockHttpServletRequest request = request("POST", "/api/v1/pix/transferencia");
            request.setQueryString("valor=10&chave=a%2Bb");

            forwarder.forward(request, new MockHttpServletResponse());

            assertThat(captured.get().uri())
                    .hasToString("http://127.0.0.1:8081/api/v1/pix/transferencia?valor=10&chave=a%2Bb");
        }

        @Test
        @DisplayName("header reservado enviado pelo canal não chega ao BFF")
        void reservedHeaderFromChannelDoesNotReachUpstream() throws Exception {
            respondWith(200, Map.of(), "");
            MockHttpServletRequest request = request("POST", "/api/v1/pix/transferencia");
            request.addHeader(RESERVED_HEADER, "true");

            forwarder.forward(request, new MockHttpServletResponse());

            assertThat(captured.get().headers().firstValue(RESERVED_HEADER)).isEmpty();
        }

        @Test
        @DisplayName("header hop-by-hop não chega ao BFF")
        void hopByHopHeaderDoesNotReachUpstream() throws Exception {
            respondWith(200, Map.of(), "");
            MockHttpServletRequest request = request("POST", "/api/v1/pix/transferencia");
            request.addHeader("Keep-Alive", "timeout=5");

            forwarder.forward(request, new MockHttpServletResponse());

            assertThat(captured.get().headers().firstValue("Keep-Alive")).isEmpty();
        }

        @Test
        @DisplayName("header de aplicação chega intacto")
        void applicationHeaderReachesUpstream() throws Exception {
            respondWith(200, Map.of(), "");
            MockHttpServletRequest request = request("POST", "/api/v1/pix/transferencia");
            request.addHeader("X-Canal-Origem", "superapp");

            forwarder.forward(request, new MockHttpServletResponse());

            assertThat(captured.get().headers().firstValue("X-Canal-Origem")).contains("superapp");
        }

        @Test
        @DisplayName("a cadeia de encaminhamento sai com um único valor")
        void forwardedForIsNotDuplicated() throws Exception {
            respondWith(200, Map.of(), "");
            MockHttpServletRequest request = request("POST", "/api/v1/pix/transferencia");
            request.addHeader("X-Forwarded-For", "203.0.113.9");

            forwarder.forward(request, new MockHttpServletResponse());

            assertThat(captured.get().headers().allValues("X-Forwarded-For"))
                    .containsExactly("203.0.113.9, 10.1.2.3");
        }

        @Test
        @DisplayName("cadeia recebida em linhas separadas é preservada inteira")
        void preservesMultiLineForwardedChain() throws Exception {
            respondWith(200, Map.of(), "");
            MockHttpServletRequest request = request("POST", "/api/v1/pix/transferencia");
            request.addHeader("X-Forwarded-For", "203.0.113.9");
            request.addHeader("X-Forwarded-For", "198.51.100.7");

            forwarder.forward(request, new MockHttpServletResponse());

            assertThat(captured.get().headers().allValues("X-Forwarded-For"))
                    .containsExactly("203.0.113.9, 198.51.100.7, 10.1.2.3");
        }

        @Test
        @DisplayName("sem cadeia recebida, o salto atual é o único valor")
        void startsChainWhenAbsent() throws Exception {
            respondWith(200, Map.of(), "");

            forwarder.forward(request("POST", "/api/v1/pix/transferencia"),
                    new MockHttpServletResponse());

            assertThat(captured.get().headers().allValues("X-Forwarded-For"))
                    .containsExactly("10.1.2.3");
        }

        @Test
        @DisplayName("GET não carrega corpo ao BFF")
        void getCarriesNoBody() throws Exception {
            respondWith(200, Map.of(), "");

            forwarder.forward(request("GET", "/api/v1/conta/saldo"), new MockHttpServletResponse());

            assertThat(captured.get().bodyPublisher().orElseThrow().contentLength()).isZero();
        }
    }

    @Nested
    @DisplayName("resposta devolvida ao canal")
    class DownstreamResponse {

        @Test
        @DisplayName("status, headers e corpo do BFF são preservados")
        void preservesUpstreamResponse() throws Exception {
            respondWith(201, Map.of("Content-Type", List.of("application/json")), "{\"id\":1}");
            MockHttpServletResponse response = new MockHttpServletResponse();

            forwarder.forward(request("POST", "/api/v1/pix/transferencia"), response);

            assertThat(response.getStatus()).isEqualTo(201);
            assertThat(response.getHeader("Content-Type")).isEqualTo("application/json");
            assertThat(response.getContentAsString()).isEqualTo("{\"id\":1}");
        }

        @Test
        @DisplayName("header reservado devolvido pelo BFF não chega ao canal")
        void reservedHeaderFromUpstreamDoesNotReachChannel() throws Exception {
            respondWith(200, Map.of(RESERVED_HEADER, List.of("true")), "");
            MockHttpServletResponse response = new MockHttpServletResponse();

            forwarder.forward(request("POST", "/api/v1/pix/transferencia"), response);

            assertThat(response.getHeader(RESERVED_HEADER)).isNull();
        }

        @Test
        @DisplayName("BFF fora do ar vira falha de upstream, não erro de requisição")
        void upstreamFailureIsDistinguishable() throws Exception {
            when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new IOException("connection refused"));

            assertThatThrownBy(() -> forwarder.forward(
                    request("POST", "/api/v1/pix/transferencia"), new MockHttpServletResponse()))
                    .isInstanceOf(RequestForwarder.UpstreamException.class);
        }
    }

    @Nested
    @DisplayName("enquadramento e tamanho")
    class FramingAndSize {

        @Test
        @DisplayName("Transfer-Encoding junto de Content-Length é recusado")
        void rejectsTransferEncodingWithContentLength() {
            MockHttpServletRequest request = request("POST", "/api/v1/pix/transferencia");
            request.addHeader("Transfer-Encoding", "chunked");
            request.addHeader("Content-Length", "10");

            assertThat(forwarder.framingRejection(request))
                    .contains(RequestForwarder.RejectionReason.AMBIGUOUS_FRAMING);
        }

        @Test
        @DisplayName("Content-Length repetido com valores divergentes é recusado")
        void rejectsConflictingContentLength() {
            MockHttpServletRequest request = request("POST", "/api/v1/pix/transferencia");
            request.addHeader("Content-Length", "10");
            request.addHeader("Content-Length", "20");

            assertThat(forwarder.framingRejection(request))
                    .contains(RequestForwarder.RejectionReason.AMBIGUOUS_FRAMING);
        }

        @Test
        @DisplayName("Content-Length não numérico é recusado")
        void rejectsNonNumericContentLength() {
            MockHttpServletRequest request = request("POST", "/api/v1/pix/transferencia");
            request.addHeader("Content-Length", "dez");

            assertThat(forwarder.framingRejection(request))
                    .contains(RequestForwarder.RejectionReason.AMBIGUOUS_FRAMING);
        }

        @Test
        @DisplayName("corpo declarado acima do teto é recusado antes de conectar")
        void rejectsOversizedDeclaredBody() {
            MockHttpServletRequest request = request("POST", "/api/v1/pix/transferencia");
            request.addHeader("Content-Length", String.valueOf(MAX_BODY + 1));

            assertThat(forwarder.framingRejection(request))
                    .contains(RequestForwarder.RejectionReason.PAYLOAD_TOO_LARGE);
        }

        @Test
        @DisplayName("requisição bem formada dentro do teto prossegue")
        void wellFormedRequestProceeds() {
            MockHttpServletRequest request = request("POST", "/api/v1/pix/transferencia");
            request.addHeader("Content-Length", "10");

            assertThat(forwarder.framingRejection(request)).isEmpty();
        }

        @Test
        @DisplayName("o fluxo aborta ao ultrapassar o teto, sem acumular em memória")
        void streamAbortsBeyondLimit() {
            byte[] payload = new byte[(int) MAX_BODY + 1];
            var limited = new RequestForwarder.LimitedInputStream(
                    new ByteArrayInputStream(payload), MAX_BODY);

            assertThatThrownBy(() -> limited.transferTo(OutputStream.nullOutputStream()))
                    .isInstanceOf(RequestForwarder.PayloadTooLargeException.class);
        }

        @Test
        @DisplayName("o fluxo dentro do teto passa inteiro")
        void streamWithinLimitPassesThrough() throws Exception {
            byte[] payload = new byte[(int) MAX_BODY];
            var limited = new RequestForwarder.LimitedInputStream(
                    new ByteArrayInputStream(payload), MAX_BODY);

            assertThat(limited.readAllBytes()).hasSize((int) MAX_BODY);
        }
    }
}