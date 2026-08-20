package com.development.iam.sidecar.identity;

import java.util.List;
import java.util.Map;

/**
 * Resultado de um passo da jornada, já traduzido do que o gateway respondeu.
 * <p>
 * Existe porque o gateway comunica desfechos diferentes de formas diferentes:
 * continuar e concluir vêm como {@code 200} com corpos distintos; recusa vem
 * como {@code 401}; sessão expirada vem como {@code 408}. Quem consome precisa
 * dos quatro casos separados, e deduzi-los a partir de status e corpo em cada
 * ponto de uso espalharia a mesma tradução por vários lugares.
 *
 * <h2>Por que recusa não é exceção</h2>
 * O gateway responde {@code 401} quando a biometria é reprovada, quando o OTP
 * está errado, quando o FIDO falha. Nada disso é falha de comunicação — é o
 * gateway funcionando e dizendo não.
 * <p>
 * Tratar como exceção faria o canal receber indisponibilidade quando o problema
 * foi a biometria: o cliente veria "serviço fora do ar", alguém seria acordado
 * de madrugada por uma recusa legítima, e a métrica de disponibilidade ficaria
 * poluída por comportamento normal.
 * <p>
 * Exceção fica reservada ao que é realmente falha: gateway inacessível, resposta
 * fora do prazo, corpo inutilizável.
 *
 * @param type      qual dos quatro desfechos ocorreu
 * @param step      o passo devolvido pelo gateway; presente em
 *                  {@link Type#CHALLENGE} e {@link Type#COMPLETED}
 * @param reason    mensagem de recusa do gateway; presente em
 *                  {@link Type#DENIED}.
 *                  <p>
 *                  <strong>Não é repassada ao canal.</strong> Serve para log
 *                  interno — distinguir "biometria recusada" de "OTP inválido"
 *                  no diagnóstico. Devolver ao canal entregaria a quem sonda o
 *                  motivo exato de cada recusa
 */
public record JourneyOutcome(Type type, JourneyStep step, String reason) {

    public enum Type {

        /** Há desafio a apresentar ao canal; a jornada continua. */
        CHALLENGE,

        /** A jornada concluiu e a sessão foi emitida. */
        COMPLETED,

        /**
         * O gateway recusou. Biometria reprovada, OTP inválido, FIDO falhou,
         * token do canal recusado — todos chegam como {@code 401}.
         */
        DENIED,

        /**
         * A sessão da jornada expirou por inatividade, e o gateway respondeu
         * {@code 408}.
         * <p>
         * Distinto de recusa: nada foi negado, apenas o cliente demorou. O canal
         * precisa saber disso para reabrir a jornada em vez de mostrar erro —
         * uma jornada com biometria tem espera longa, e cinco minutos passam.
         */
        EXPIRED
    }

    public static JourneyOutcome challenge(JourneyStep step) {
        return new JourneyOutcome(Type.CHALLENGE, step, null);
    }

    public static JourneyOutcome completed(JourneyStep step) {
        return new JourneyOutcome(Type.COMPLETED, step, null);
    }

    public static JourneyOutcome denied(String reason) {
        return new JourneyOutcome(Type.DENIED, null, reason);
    }

    public static JourneyOutcome expired() {
        return new JourneyOutcome(Type.EXPIRED, null, null);
    }

    /**
     * Sem o {@code toString} gerado: o passo carrega identificador de jornada,
     * sessão e conteúdo de callback. O motivo da recusa fica, porque vem do
     * gateway e é o que ajuda no diagnóstico.
     */
    @Override
    public String toString() {
        return "JourneyOutcome[type=%s, reason=%s]".formatted(type, reason);
    }

    /**
     * Callbacks a apresentar ao canal, vazio quando não há desafio.
     */
    public List<Map<String, Object>> callbacks() {
        return step == null ? List.of() : step.callbacks();
    }
}