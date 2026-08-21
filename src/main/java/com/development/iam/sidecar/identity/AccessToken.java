package com.development.iam.sidecar.identity;


import tools.jackson.annotation.JsonIgnoreProperties;
import tools.jackson.annotation.JsonProperty;

/**
 * Token emitido pelo gateway ao fim da jornada.
 * <p>
 * É o que o sidecar obtém depois que a jornada conclui, e o que o BFF trocará
 * por um token com os escopos dele.
 *
 * <h2>Campos ignorados de propósito</h2>
 * O gateway devolve mais do que está modelado aqui — {@code id_token},
 * {@code refresh_token}, {@code scope}. O que não é usado não é modelado: um
 * campo declarado convida alguém a usá-lo, e {@code refresh_token} em
 * particular tem vida longa e não deveria circular por um componente que não
 * guarda estado.
 *
 * @param accessToken token de acesso emitido.
 *                    <p>
 *                    <strong>É credencial.</strong> Nunca aparece em log, e a
 *                    representação textual desta classe o omite.
 * @param tokenType   tipo do token, normalmente {@code Bearer}.
 * @param expiresIn   validade em segundos, contada a partir da emissão.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccessToken(

        @JsonProperty("access_token")
        String accessToken,

        @JsonProperty("token_type")
        String tokenType,

        @JsonProperty("expires_in")
        Long expiresIn
) {

    /**
     * Indica que o token é utilizável.
     * <p>
     * Resposta {@code 200} com corpo sem token é possível quando o gateway
     * responde um erro que não chegou a virar status de erro. Seguir com token
     * ausente produziria falha adiante, longe da causa.
     */
    public boolean isUsable() {
        return accessToken != null && !accessToken.isBlank();
    }

    /**
     * Sem o {@code toString} gerado pelo record: ele imprimiria o token. Quem o
     * obtém age em nome do cliente autenticado.
     */
    @Override
    public String toString() {
        return "AccessToken[accessToken=%s, tokenType=%s, expiresIn=%s]"
                .formatted(accessToken == null ? "ausente" : "***", tokenType, expiresIn);
    }
}