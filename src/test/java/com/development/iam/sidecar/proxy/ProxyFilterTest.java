package com.development.iam.sidecar.proxy;

import com.development.iam.sidecar.config.InterceptRule;
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

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProxyFilterTest {

    private static final String PIX_TRANSFER = "/api/v1/pix/transferencia";
    private static final String BALANCE = "/api/v1/conta/saldo";

    private final RequestForwarder forwarder = mock(RequestForwarder.class);
    private final MockFilterChain chain = new MockFilterChain();

    private ProxyFilter filter;

    private static RouteResolver resolver() {
        return new RouteResolver(List.of(
                new InterceptRule("pix-transfer", PIX_TRANSFER, Set.of(HttpMethod.POST))));
    }

    @BeforeEach
    void setUp() {
        when(forwarder.framingRejection(any())).thenReturn(Optional.empty());
        filter = new ProxyFilter(resolver(), forwarder, new ObjectMapper());
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

        @Test
        @DisplayName("rota interceptada é negada e não chega ao BFF")
        void interceptedRouteIsDeniedAndNotForwarded() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request("POST", PIX_TRANSFER), response, chain);

            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).contains("confirmation_required");
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

        @Test
        @DisplayName("mesmo path com verbo fora da regra é encaminhado")
        void undeclaredMethodIsForwarded() throws Exception {
            filter.doFilter(request("GET", PIX_TRANSFER), new MockHttpServletResponse(), chain);

            verify(forwarder).forward(any(), any());
        }
    }

    @Nested
    @DisplayName("enquadramento verificado antes da matriz")
    class FramingCheckedFirst {

        @Test
        @DisplayName("enquadramento ambíguo é recusado antes de resolver a rota")
        void ambiguousFramingIsRejectedBeforeRouting() throws Exception {
            when(forwarder.framingRejection(any()))
                    .thenReturn(Optional.of(RequestForwarder.RejectionReason.AMBIGUOUS_FRAMING));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request("POST", BALANCE), response, chain);

            assertThat(response.getStatus()).isEqualTo(400);
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
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request("POST", PIX_TRANSFER), response, chain);

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

        @Test
        @DisplayName("a resposta de erro carrega o identificador no corpo")
        void errorBodyCarriesCorrelationId() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request("POST", PIX_TRANSFER), response, chain);

            assertThat(response.getContentAsString())
                    .contains("correlationId")
                    .contains(response.getHeader(CorrelationId.HEADER));
        }

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