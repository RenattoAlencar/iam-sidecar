package com.development.iam.sidecar.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Contrato entre o sidecar e o canal.
 *
 * @param challengePath path do endpoint em que o canal responde aos passos da
 *                      jornada.
 *                      <p>
 *                      É o único endpoint próprio do sidecar. Tratado dentro do
 *                      filtro, e não por um controller, para que atravesse a
 *                      mesma verificação de enquadramento, normalização de path,
 *                      política de headers e teto de corpo que o restante do
 *                      tráfego. Um controller seria roteado direto pelo Spring,
 *                      sem passar por nenhuma dessas verificações — e as
 *                      proteções do componente valeriam para um caminho de
 *                      entrada e não para o outro.
 *                      <p>
 *                      <strong>Nunca pode aparecer na matriz de
 *                      interceptação</strong>, e isso é verificado no boot. Ver
 *                      {@link #requireOutsideMatrix}.
 */
@Validated
@ConfigurationProperties(prefix = "channel")
public record ChannelProperties(

        @DefaultValue("/ciam/challenge")
        @NotBlank(message = "channel.challenge-path é obrigatório")
        String challengePath
) {

    /**
     * Recusa a configuração em que o endpoint do desafio está na matriz.
     * <p>
     * Exigir confirmação para responder ao desafio que concede a confirmação é
     * impasse: o canal receberia um desafio, tentaria respondê-lo, e o sidecar
     * exigiria outra confirmação para aceitar a resposta. O cliente ficaria
     * preso num ciclo, e o sintoma — uma jornada que nunca sai do primeiro passo
     * — não aponta para a configuração.
     * <p>
     * Não é vulnerabilidade: é configuração que trava o fluxo, e por isso derruba
     * o boot em vez de falhar em runtime.
     * <p>
     * A comparação usa o path declarado na regra, sem normalizar: a matriz aceita
     * padrões com curinga, e um padrão como {@code /ciam/**} cobre o endpoint sem
     * ser igual a ele. Por isso a verificação recusa tanto igualdade quanto
     * prefixo com curinga.
     *
     * @param interceptRules matriz declarada
     * @throws IllegalStateException se alguma regra cobrir o endpoint do desafio
     */
    public void requireOutsideMatrix(Iterable<InterceptRule> interceptRules) {
        for (InterceptRule rule : interceptRules) {
            if (covers(rule.path(), challengePath)) {
                throw new IllegalStateException(
                        "A regra '" + rule.name() + "' cobre o endpoint de resposta ao desafio ("
                                + challengePath + "). Exigir confirmação para responder ao "
                                + "desafio que concede a confirmação prende o cliente num ciclo "
                                + "sem saída.");
            }
        }
    }

    /**
     * Indica se um padrão da matriz cobre o path do desafio.
     * <p>
     * Cobre três formas: igualdade exata, curinga de sub-árvore
     * ({@code /ciam/**}) e curinga de segmento ({@code /ciam/*}). Não pretende
     * reproduzir o casamento completo de padrões — pretende recusar as formas
     * que alguém escreveria sem perceber o efeito.
     */
    private static boolean covers(String rulePath, String challengePath) {
        if (rulePath == null || rulePath.isBlank()) {
            return false;
        }
        if (rulePath.equals(challengePath)) {
            return true;
        }
        if (rulePath.endsWith("/**")) {
            String prefix = rulePath.substring(0, rulePath.length() - 3);
            return challengePath.startsWith(prefix);
        }
        if (rulePath.endsWith("/*")) {
            String prefix = rulePath.substring(0, rulePath.length() - 2);
            // Curinga de segmento cobre apenas um nivel abaixo do prefixo.
            return challengePath.startsWith(prefix)
                    && challengePath.substring(prefix.length()).chars()
                    .filter(character -> character == '/').count() == 1;
        }
        return false;
    }
}