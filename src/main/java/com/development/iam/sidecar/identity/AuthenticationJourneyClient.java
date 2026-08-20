package com.development.iam.sidecar.identity;

import java.util.List;
import java.util.Map;

/**
 * Conduz a jornada de autenticação junto ao gateway.
 * <p>
 * São dois momentos, e o gateway os trata de forma diferente apenas no corpo e
 * no cabeçalho: o início apresenta o token do canal e envia corpo vazio; a
 * continuação apresenta o identificador da sessão e os callbacks respondidos. A
 * URL e os parâmetros são idênticos.
 * <p>
 * Nenhum dos dois interpreta callbacks. O que entra sai como veio, e o que volta
 * do gateway é repassado sem leitura.
 *
 * <h2>Interface, e não classe única</h2>
 * É o ponto de substituição nos testes de quem orquestra. A alternativa —
 * exercitar o orquestrador contra um servidor falso — misturaria dois assuntos:
 * a decisão sobre a requisição e o formato da chamada HTTP. Cada um tem seu
 * teste.
 */
public interface AuthenticationJourneyClient {

    /**
     * Inicia a jornada apresentando o token de sessão do canal.
     *
     * @param channelToken token que o canal apresentou, repassado como veio.
     *                     O sidecar não verifica assinatura: quem valida é o
     *                     gateway, e essa premissa só se sustenta enquanto o
     *                     sidecar for alcançável apenas por ele
     * @param otpCode      código de um autenticador já configurado, quando o
     *                     canal o apresenta.
     *                     <p>
     *                     Opcional. Presente e válido, o gateway pula os passos
     *                     de embarque e vai direto ao fator seguinte; ausente ou
     *                     inválido, a jornada segue o caminho completo. O
     *                     sidecar não decide nada sobre isso — apenas repassa
     * @return o primeiro passo, normalmente um desafio
     * @throws JourneyUnavailableException se o gateway não responder, responder
     *                                     fora do prazo ou devolver corpo
     *                                     inutilizável
     */
    JourneyOutcome start(String channelToken, String otpCode);

    /**
     * Continua a jornada com a resposta do canal ao passo anterior.
     * <p>
     * Serve a todos os passos indistintamente — biometria, espera, OTP, FIDO.
     * O gateway usa a mesma chamada para todos.
     *
     * @param authId    identificador da sessão da jornada, obtido no passo
     *                  anterior
     * @param callbacks resposta do canal, repassada sem interpretação
     * @return o próximo passo, que pode ser outro desafio, a conclusão, uma
     *         recusa ou a expiração da sessão
     * @throws JourneyUnavailableException nas mesmas condições de
     *                                     {@link #start(String, String)}
     */
    JourneyOutcome advance(String authId, List<Map<String, Object>> callbacks);

    /**
     * Falha de comunicação com o gateway, distinta de recusa.
     * <p>
     * A distinção decide a resposta ao canal e não pode ser inferida do
     * conteúdo: gateway inacessível é indisponibilidade e vira {@code 503};
     * gateway que respondeu "não" é desfecho de negócio e vira
     * {@link JourneyOutcome.Type#DENIED}. Tratar as duas igual esconderia uma
     * falha de infraestrutura atrás de uma mensagem de autenticação recusada, e
     * ninguém investigaria.
     * <p>
     * A mensagem é genérica de propósito. A causa carrega o endereço do gateway
     * e o corpo da resposta, e a mensagem pode acabar exposta — o rastro da
     * exceção vai para o log, que é interno.
     */
    class JourneyUnavailableException extends RuntimeException {

        public JourneyUnavailableException(String message) {
            super(message);
        }

        public JourneyUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}