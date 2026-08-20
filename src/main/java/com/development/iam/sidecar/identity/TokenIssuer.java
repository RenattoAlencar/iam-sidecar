package com.development.iam.sidecar.identity;

/**
 * Obtém o token de acesso a partir da sessão emitida pela jornada.
 * <p>
 * São duas chamadas encadeadas, e nenhuma delas faz sentido isolada: pedir o
 * código de autorização apresentando a sessão, e trocar esse código pelo token.
 * Por isso a interface expõe uma operação só — quem consome não tem motivo para
 * conduzir metade do fluxo.
 *
 * <h2>Sem estado entre as duas chamadas</h2>
 * O par do PKCE é gerado antes da primeira e usado na segunda, dentro do mesmo
 * tratamento de requisição. Nada precisa ser guardado, e por isso este bloco não
 * reintroduz o armazenamento que o desenho do sidecar evita.
 */
public interface TokenIssuer {

    /**
     * Troca a sessão da jornada por um token de acesso.
     *
     * @param sessionId sessão emitida quando a jornada concluiu, apresentada ao
     *                  gateway como cookie
     * @return o token emitido
     * @throws TokenIssuanceException se o gateway recusar, não responder ou
     *                                devolver resposta inutilizável
     */
    AccessToken issue(String sessionId);

    /**
     * Falha ao obter o token.
     * <p>
     * Aqui não há a distinção entre recusa e indisponibilidade que existe na
     * jornada: neste ponto o cliente já se autenticou, e o gateway já disse sim.
     * Uma recusa agora é configuração errada — cliente OAuth não registrado,
     * endereço de retorno divergente, escopo não permitido — e não decisão sobre
     * o cliente.
     * <p>
     * Ou seja, falhar aqui é sempre problema nosso, e o desfecho para o canal é
     * o mesmo: indisponibilidade.
     */
    class TokenIssuanceException extends RuntimeException {

        public TokenIssuanceException(String message) {
            super(message);
        }

        public TokenIssuanceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}