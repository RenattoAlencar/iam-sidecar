package com.development.iam.sidecar.identity;

import tools.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.annotation.JsonInclude;
import tools.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Um passo da jornada de autenticação, no formato que o gateway usa.
 * <p>
 * O mesmo tipo serve para os dois sentidos: é o corpo que o sidecar envia ao
 * continuar a jornada, e é o corpo que o gateway devolve a cada passo. São
 * idênticos por desenho do provedor — o canal responde ao desafio devolvendo os
 * callbacks recebidos, com os campos de entrada preenchidos.
 * <p>
 * Tipos separados para envio e resposta seriam duas classes com os mesmos
 * campos e a mesma serialização, e alguém teria que converter entre elas a cada
 * passo — trabalho que só existiria para sustentar a separação.
 *
 * <h2>Callbacks opacos</h2>
 * {@code callbacks} é {@code List<Map<String, Object>>} e nunca é interpretado.
 * O sidecar não sabe o que é biometria, OTP ou FIDO: recebe do gateway, entrega
 * ao canal; recebe do canal, entrega ao gateway.
 * <p>
 * Tipar cada callback faria o sidecar conhecer a jornada, e cada passo novo que
 * o time de identidade criasse exigiria mudança, deploy e teste de um componente
 * que não tem nada a ver com o passo. A jornada atual já tem cinco tipos
 * distintos — {@code NameCallback}, {@code PollingWaitCallback},
 * {@code TextOutputCallback}, {@code WebAuthnAuthenticationCallback} e o de
 * registro. Opaco, o sidecar atravessa todos sem alteração.
 * <p>
 * A contrapartida é não haver validação de estrutura: callback malformado segue
 * e o gateway recusa. É onde a recusa pertence — o sidecar não teria como
 * decidir melhor, e fingir que decide criaria uma segunda opinião sobre algo que
 * só o provedor conhece.
 *
 * <h2>O que trafega aqui dentro</h2>
 * Os callbacks carregam, dependendo do passo: a foto da selfie em base64, a
 * semente do TOTP com o segredo do autenticador, o código OTP digitado e a
 * assinatura do dispositivo. <strong>Nada disso pode aparecer em log</strong>, e
 * é por isso que {@link #toString()} é sobrescrito.
 *
 * @param authId    identificador da sessão da jornada, emitido pelo gateway no
 *                  primeiro passo e devolvido a cada resposta.
 *                  <p>
 *                  Precisa acompanhar todos os passos seguintes. Vale cerca de
 *                  cinco minutos de inatividade; expirado, o gateway responde
 *                  {@code 408} e a jornada recomeça do início.
 *                  <p>
 *                  <strong>É credencial:</strong> quem o obtém continua a
 *                  jornada de outra pessoa.
 * @param callbacks desafios do passo, repassados como vieram.
 * @param tokenId   sessão emitida quando a jornada conclui. Presente apenas na
 *                  resposta final; é o que o sidecar apresenta ao
 *                  {@code authorize} para obter o código de autorização.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record JourneyStep(

        @JsonProperty("authId")
        String authId,

        @JsonProperty("callbacks")
        List<Map<String, Object>> callbacks,

        @JsonProperty("tokenId")
        String tokenId
) {

    /**
     * Fixa os callbacks em uma lista imutável.
     * <p>
     * O conteúdo de cada mapa não é copiado em profundidade: seria custo sobre
     * uma estrutura que só atravessa, e o sidecar não tem interesse no que há
     * dentro. O que se protege é a forma do passo depois de construído.
     */
    public JourneyStep {
        callbacks = callbacks == null
                ? List.of()
                : Collections.unmodifiableList(List.copyOf(callbacks));
    }

    /**
     * Passo a enviar ao gateway ao continuar a jornada.
     * <p>
     * {@code tokenId} fica ausente, e o {@code @JsonInclude(NON_NULL)} o omite
     * do corpo — o gateway espera apenas {@code authId} e {@code callbacks}, e
     * um campo nulo a mais é ruído que algumas versões recusam.
     */
    public static JourneyStep advancing(String authId, List<Map<String, Object>> callbacks) {
        return new JourneyStep(authId, callbacks, null);
    }

    /**
     * Indica que a jornada terminou e a sessão foi emitida.
     * <p>
     * Existe como método porque é a pergunta que decide o desfecho inteiro: com
     * sessão, o sidecar segue para obter o código de autorização; sem ela,
     * devolve o desafio ao canal.
     */
    public boolean isComplete() {
        return tokenId != null && !tokenId.isBlank();
    }

    /**
     * Indica que há desafio a apresentar ao canal.
     * <p>
     * Exige {@code authId} <em>e</em> callbacks. Um sem o outro não é passo
     * continuável: callbacks sem identificador não teriam para onde ser
     * devolvidos, e identificador sem callbacks não daria ao canal o que
     * responder.
     */
    public boolean hasChallenge() {
        return authId != null && !authId.isBlank() && !callbacks.isEmpty();
    }

    /**
     * Sem o {@code toString} gerado pelo record.
     * <p>
     * O {@code authId} é a chave da jornada e o {@code tokenId} é a sessão do
     * cliente — quem obtém qualquer um dos dois assume o lugar dele. Os
     * callbacks carregam a foto da biometria, a semente do TOTP e o código OTP.
     * <p>
     * A contagem de callbacks fica, porque ajuda a distinguir um passo vazio de
     * um passo com desafio sem revelar nada.
     */
    @Override
    public String toString() {
        return "JourneyStep[authId=%s, callbacks=%d, tokenId=%s, complete=%s]"
                .formatted(authId == null ? "ausente" : "***",
                        callbacks.size(),
                        tokenId == null ? "ausente" : "***",
                        isComplete());
    }
}