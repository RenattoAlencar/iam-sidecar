package com.development.iam.sidecar.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O que se protege é uma configuração que trava o fluxo sem parecer errada.
 * <p>
 * Com o endpoint do desafio dentro da matriz, o canal recebe um desafio, tenta
 * respondê-lo, e o sidecar exige outra confirmação para aceitar a resposta. O
 * cliente fica preso num ciclo, e o sintoma — uma jornada que nunca sai do
 * primeiro passo — não aponta para a causa.
 */
class ChannelPropertiesTest {

    private static final String CHALLENGE_PATH = "/ciam/challenge";

    private static final ChannelProperties PROPERTIES = new ChannelProperties(CHALLENGE_PATH);

    private static InterceptRule rule(String path) {
        return new InterceptRule("regra", path, Set.of(HttpMethod.POST));
    }

    @Test
    @DisplayName("matriz que não cobre o endpoint do desafio é aceita")
    void matrixWithoutChallengePathIsAccepted() {
        List<InterceptRule> rules = List.of(
                rule("/api/v1/pix/transferencia"),
                rule("/api/v1/conta/**"));

        assertThatCode(() -> PROPERTIES.requireOutsideMatrix(rules)).doesNotThrowAnyException();
    }

    /**
     * As três formas em que alguém escreveria a regra sem perceber o efeito.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/ciam/challenge",
            "/ciam/**",
            "/ciam/*",
            "/**"
    })
    @DisplayName("regra que cobre o endpoint do desafio derruba o boot")
    void ruleCoveringChallengePathFails(String rulePath) {
        List<InterceptRule> rules = List.of(rule(rulePath));

        assertThatThrownBy(() -> PROPERTIES.requireOutsideMatrix(rules))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CHALLENGE_PATH);
    }

    /**
     * A mensagem precisa nomear a regra: numa matriz com dezenas de entradas,
     * saber que há conflito sem saber onde não ajuda.
     */
    @Test
    @DisplayName("a mensagem identifica a regra em conflito")
    void messageIdentifiesConflictingRule() {
        List<InterceptRule> rules = List.of(
                rule("/api/v1/pix/transferencia"),
                new InterceptRule("desafio-por-engano", "/ciam/**", Set.of(HttpMethod.POST)));

        assertThatThrownBy(() -> PROPERTIES.requireOutsideMatrix(rules))
                .hasMessageContaining("desafio-por-engano");
    }

    /**
     * Curinga de segmento cobre um nível abaixo do prefixo, não a sub-árvore
     * inteira: {@code /ciam/*} não deveria casar com {@code /ciam/a/b}.
     */
    @Test
    @DisplayName("curinga de segmento não alcança além de um nível")
    void segmentWildcardDoesNotReachDeeper() {
        ChannelProperties deep = new ChannelProperties("/ciam/interno/challenge");

        assertThatCode(() -> deep.requireOutsideMatrix(List.of(rule("/ciam/*"))))
                .doesNotThrowAnyException();
    }
}