package com.development.iam.sidecar.identity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O tipo que atravessa a fronteira com o gateway.
 * <p>
 * As estruturas usadas aqui são as reais da jornada {@code factor-onboarding},
 * copiadas da documentação de integração. Testar contra um formato inventado
 * daria confiança sem lastro — a desserialização passaria e a integração
 * quebraria na primeira chamada de homologação.
 */
class JourneyStepTest {

    private static final String AUTH_ID = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.jornada";
    private static final String TOKEN_ID = "sessao-emitida-pelo-am";
    private static final String SELFIE = "<foto-em-base64>";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Desafio de biometria, como o gateway devolve no passo 1. */
    private static Map<String, Object> biometricChallenge() {
        return Map.of(
                "type", "NameCallback",
                "output", List.of(
                        Map.of("name", "prompt", "value", "CHALLENGE_REQUIRED"),
                        Map.of("name", "defaultValue", "value", "BIOMETRIA:UNICO")),
                "input", List.of(Map.of("name", "IDToken1", "value", "BIOMETRIA:UNICO")));
    }

    /** O mesmo callback com a foto preenchida, como o canal devolve no passo 2. */
    private static Map<String, Object> answeredWithSelfie() {
        return Map.of(
                "type", "NameCallback",
                "output", List.of(Map.of("name", "prompt", "value", "CHALLENGE_REQUIRED")),
                "input", List.of(Map.of("name", "IDToken1",
                        "value", "{\"foto\":\"" + SELFIE + "\",\"channel\":\"app\"}")));
    }

    @Nested
    @DisplayName("desfechos do passo")
    class Outcomes {

        @Test
        @DisplayName("passo com sessão emitida é conclusão")
        void stepWithSessionIsComplete() {
            JourneyStep step = new JourneyStep(null, List.of(), TOKEN_ID);

            assertThat(step.isComplete()).isTrue();
            assertThat(step.hasChallenge()).isFalse();
        }

        @Test
        @DisplayName("passo com identificador e callbacks é desafio")
        void stepWithCallbacksIsChallenge() {
            JourneyStep step = new JourneyStep(AUTH_ID, List.of(biometricChallenge()), null);

            assertThat(step.hasChallenge()).isTrue();
            assertThat(step.isComplete()).isFalse();
        }

        /**
         * Nem conclusão nem desafio. Quem consome precisa distinguir isso de um
         * passo continuável — encaminhar seria liberar sem autorização.
         */
        @Test
        @DisplayName("passo vazio não é nenhum dos dois")
        void emptyStepIsNeither() {
            JourneyStep step = new JourneyStep(null, List.of(), null);

            assertThat(step.isComplete()).isFalse();
            assertThat(step.hasChallenge()).isFalse();
        }

        /**
         * Identificador sem callbacks não dá ao canal o que responder;
         * callbacks sem identificador não teriam para onde voltar.
         */
        @Test
        @DisplayName("identificador e callbacks são ambos necessários para haver desafio")
        void bothAreNeededForChallenge() {
            assertThat(new JourneyStep(AUTH_ID, List.of(), null).hasChallenge()).isFalse();
            assertThat(new JourneyStep(null, List.of(biometricChallenge()), null).hasChallenge())
                    .isFalse();
        }

        @Test
        @DisplayName("sessão em branco não conta como conclusão")
        void blankSessionIsNotComplete() {
            assertThat(new JourneyStep(null, List.of(), "  ").isComplete()).isFalse();
            assertThat(new JourneyStep(null, List.of(), "").isComplete()).isFalse();
        }
    }

    @Nested
    @DisplayName("imutabilidade")
    class Immutability {

        @Test
        @DisplayName("os callbacks ficam imutáveis depois de construído")
        void callbacksAreImmutable() {
            JourneyStep step = new JourneyStep(AUTH_ID, List.of(biometricChallenge()), null);

            assertThatThrownBy(() -> step.callbacks().add(biometricChallenge()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("alterar a lista de origem não afeta o passo construído")
        void doesNotShareIncomingList() {
            List<Map<String, Object>> incoming = new ArrayList<>();
            incoming.add(biometricChallenge());

            JourneyStep step = new JourneyStep(AUTH_ID, incoming, null);
            incoming.add(biometricChallenge());

            assertThat(step.callbacks()).hasSize(1);
        }

        @Test
        @DisplayName("callbacks nulos viram lista vazia, sem estourar")
        void nullCallbacksBecomeEmptyList() {
            assertThat(new JourneyStep(AUTH_ID, null, null).callbacks()).isEmpty();
        }
    }

    @Nested
    @DisplayName("serialização")
    class Serialization {

        /**
         * A resposta real do passo 1. As estruturas aninhadas — {@code output}
         * com dois itens, {@code input} com um — atravessam sem que o tipo as
         * conheça.
         */
        @Test
        @DisplayName("desserializa o desafio de biometria preservando a estrutura")
        void deserializesBiometricChallenge() {
            String json = """
                    {
                      "authId": "%s",
                      "callbacks": [
                        {
                          "type": "NameCallback",
                          "output": [
                            { "name": "prompt", "value": "CHALLENGE_REQUIRED" },
                            { "name": "defaultValue", "value": "BIOMETRIA:UNICO" }
                          ],
                          "input": [
                            { "name": "IDToken1", "value": "BIOMETRIA:UNICO" }
                          ]
                        }
                      ]
                    }
                    """.formatted(AUTH_ID);

            JourneyStep step = objectMapper.readValue(json, JourneyStep.class);

            assertThat(step.hasChallenge()).isTrue();
            assertThat(step.authId()).isEqualTo(AUTH_ID);
            assertThat(step.callbacks()).hasSize(1);

            Map<String, Object> callback = step.callbacks().getFirst();
            assertThat(callback).containsEntry("type", "NameCallback");
            assertThat(callback).containsKeys("output", "input");
        }

        /**
         * A resposta de polling, que se repete enquanto a análise biométrica não
         * termina. Precisa ser reconhecida como desafio para que o canal a
         * devolva inalterada.
         */
        @Test
        @DisplayName("desserializa o passo de espera como desafio")
        void deserializesPollingAsChallenge() {
            String json = """
                    {
                      "authId": "%s",
                      "callbacks": [
                        {
                          "type": "PollingWaitCallback",
                          "output": [
                            { "name": "waitTime", "value": "5000" },
                            { "name": "message", "value": "Please wait..." }
                          ]
                        }
                      ]
                    }
                    """.formatted(AUTH_ID);

            JourneyStep step = objectMapper.readValue(json, JourneyStep.class);

            assertThat(step.hasChallenge()).isTrue();
            assertThat(step.callbacks().getFirst()).containsEntry("type", "PollingWaitCallback");
        }

        /**
         * A resposta final traz {@code successUrl} e {@code realm}, que o tipo
         * não modela. Desserialização estrita transformaria evolução do gateway
         * em falha do sidecar.
         */
        @Test
        @DisplayName("campos não modelados na resposta final são ignorados")
        void ignoresUnmodeledFieldsOnCompletion() {
            String json = """
                    {
                      "tokenId": "%s",
                      "successUrl": "/enduser/?realm=/alpha",
                      "realm": "/alpha"
                    }
                    """.formatted(TOKEN_ID);

            JourneyStep step = objectMapper.readValue(json, JourneyStep.class);

            assertThat(step.isComplete()).isTrue();
            assertThat(step.tokenId()).isEqualTo(TOKEN_ID);
        }

        /**
         * O gateway espera apenas {@code authId} e {@code callbacks} na
         * continuação. Um {@code tokenId} nulo a mais no corpo é ruído que
         * algumas versões recusam.
         */
        @Test
        @DisplayName("o passo enviado não carrega campo de sessão")
        void sentStepOmitsSessionField() {
            JourneyStep step = JourneyStep.advancing(AUTH_ID, List.of(answeredWithSelfie()));

            String json = objectMapper.writeValueAsString(step);

            assertThat(json)
                    .doesNotContain("tokenId")
                    .contains("authId")
                    .contains("callbacks");
        }

        /**
         * O gateway espera o callback de volta exatamente como o enviou, com o
         * campo de entrada preenchido. Alterar qualquer coisa quebra a jornada.
         */
        @Test
        @DisplayName("o callback respondido é serializado sem alteração")
        void answeredCallbackIsSerializedUnchanged() {
            JourneyStep step = JourneyStep.advancing(AUTH_ID, List.of(answeredWithSelfie()));

            String json = objectMapper.writeValueAsString(step);

            assertThat(json)
                    .contains("NameCallback")
                    .contains("IDToken1")
                    .contains(SELFIE);
        }
    }

    @Nested
    @DisplayName("representação textual")
    class TextualForm {

        /**
         * O {@code authId} permite continuar a jornada alheia; o
         * {@code tokenId} é a sessão do cliente; os callbacks carregam a foto da
         * biometria e a semente do TOTP. Log de exceção com este objeto
         * gravaria tudo isso.
         */
        @Test
        @DisplayName("não revela identificador, sessão nem conteúdo de callback")
        void hidesCredentialsAndCallbackContent() {
            JourneyStep step = new JourneyStep(AUTH_ID, List.of(answeredWithSelfie()), TOKEN_ID);

            assertThat(step.toString())
                    .doesNotContain(AUTH_ID)
                    .doesNotContain(TOKEN_ID)
                    .doesNotContain(SELFIE)
                    .contains("callbacks=1")
                    .contains("complete=true");
        }
    }
}