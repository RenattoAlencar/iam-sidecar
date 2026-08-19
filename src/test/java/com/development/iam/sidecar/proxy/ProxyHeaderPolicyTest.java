package com.development.iam.sidecar.proxy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyHeaderPolicyTest {

    private static final String RESERVED = "x-sidecar-verified";

    private static final ProxyHeaderPolicy POLICY = new ProxyHeaderPolicy(List.of(RESERVED));

    private static boolean forwardable(String headerName) {
        return POLICY.isForwardable(headerName, Set.of());
    }

    @Nested
    @DisplayName("headers reservados ao sidecar")
    class ReservedHeaders {

        @Test
        @DisplayName("header reservado enviado de fora não atravessa")
        void reservedHeaderFromOutsideDoesNotCross() {
            assertThat(forwardable(RESERVED)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "X-Sidecar-Verified",
                "X-SIDECAR-VERIFIED",
                "x-SiDeCaR-vErIfIeD",
                "  x-sidecar-verified  "
        })
        @DisplayName("a caixa e o espaçamento não contornam o descarte")
        void caseAndWhitespaceDoNotBypass(String headerName) {
            assertThat(forwardable(headerName)).isFalse();
        }

        @Test
        @DisplayName("reservado é identificável separadamente do descarte")
        void reservedIsIdentifiable() {
            assertThat(POLICY.isReserved(RESERVED)).isTrue();
            assertThat(POLICY.isReserved("authorization")).isFalse();
        }

        @Test
        @DisplayName("sem reservados configurados, nada é reservado")
        void emptyReservedListReservesNothing() {
            ProxyHeaderPolicy empty = new ProxyHeaderPolicy(List.of());

            assertThat(empty.isReserved(RESERVED)).isFalse();
            assertThat(empty.isForwardable(RESERVED, Set.of())).isTrue();
        }
    }

    @Nested
    @DisplayName("headers hop-by-hop")
    class HopByHop {

        @ParameterizedTest
        @ValueSource(strings = {
                "Connection",
                "Keep-Alive",
                "Proxy-Authenticate",
                "Proxy-Authorization",
                "TE",
                "Trailer",
                "Transfer-Encoding",
                "Upgrade"
        })
        @DisplayName("header de conexão não atravessa")
        void connectionScopedHeaderDoesNotCross(String headerName) {
            assertThat(forwardable(headerName)).isFalse();
        }

        @Test
        @DisplayName("header declarado em Connection não atravessa")
        void headerDeclaredInConnectionDoesNotCross() {
            Set<String> tokens = ProxyHeaderPolicy.connectionTokens("X-Custom-Hop, Keep-Alive");

            assertThat(POLICY.isForwardable("X-Custom-Hop", tokens)).isFalse();
            assertThat(POLICY.isForwardable("X-Outro", tokens)).isTrue();
        }

        @Test
        @DisplayName("Connection repetido acumula os nomes declarados")
        void repeatedConnectionAccumulatesTokens() {
            Set<String> tokens = ProxyHeaderPolicy.connectionTokens("X-Um", "X-Dois , X-Tres");

            assertThat(tokens).containsExactly("x-um", "x-dois", "x-tres");
        }

        @Test
        @DisplayName("Connection ausente ou vazio não descarta nada")
        void absentConnectionDiscardsNothing() {
            assertThat(ProxyHeaderPolicy.connectionTokens((String[]) null)).isEmpty();
            assertThat(ProxyHeaderPolicy.connectionTokens("", "   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("headers reconstruídos pelo proxy")
    class RebuiltByProxy {

        @ParameterizedTest
        @ValueSource(strings = {"Host", "Content-Length"})
        @DisplayName("header reconstruído não é copiado")
        void rebuiltHeaderIsNotCopied(String headerName) {
            assertThat(forwardable(headerName)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"X-Forwarded-For", "X-Forwarded-Proto", "X-Forwarded-Host"})
        @DisplayName("header de encaminhamento não é copiado, é reconstruído")
        void forwardedHeaderIsRebuiltNotCopied(String headerName) {
            assertThat(forwardable(headerName)).isFalse();
        }
    }

    @Nested
    @DisplayName("headers de aplicação")
    class ApplicationHeaders {

        @ParameterizedTest
        @ValueSource(strings = {
                "Authorization",
                "Content-Type",
                "Accept",
                "Cookie",
                "User-Agent",
                "X-Request-Id",
                "X-Correlation-Id",
                "X-Canal-Origem"
        })

        @DisplayName("header de aplicação atravessa")
        void applicationHeaderCrosses(String headerName) {
            assertThat(forwardable(headerName)).isTrue();
        }

        @Test
        @DisplayName("nome nulo ou vazio não atravessa")
        void nullOrBlankNameDoesNotCross() {
            assertThat(forwardable(null)).isFalse();
            assertThat(forwardable("")).isFalse();
            assertThat(forwardable("   ")).isFalse();
        }

        @Test
        @DisplayName("conjunto de tokens nulo não quebra a decisão")
        void nullTokenSetDoesNotBreakDecision() {
            assertThat(POLICY.isForwardable("Accept", null)).isTrue();
        }
    }
}