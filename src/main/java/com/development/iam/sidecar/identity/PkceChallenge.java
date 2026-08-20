package com.development.iam.sidecar.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Par de valores do PKCE: o verificador e o desafio derivado dele.
 * <p>
 * O desafio acompanha o pedido de código de autorização; o verificador é
 * apresentado depois, na troca do código pelo token. O gateway confere que um
 * corresponde ao outro.
 *
 * <h2>Por que existe mesmo com cliente confidencial</h2>
 * O PKCE nasceu para clientes públicos, que não guardam segredo. Aqui o sidecar
 * é confidencial e apresenta {@code client_secret} na troca — então o PKCE não é
 * a única proteção.
 * <p>
 * Ainda assim ele fecha uma janela específica: o código de autorização viaja no
 * cabeçalho {@code Location} de um redirecionamento, e quem o interceptasse
 * precisaria também do verificador para trocá-lo. Sem PKCE, bastaria o código e
 * as credenciais do cliente.
 *
 * <h2>Sem estado entre requisições</h2>
 * O par é gerado imediatamente antes do pedido de código e usado na troca
 * seguinte, dentro do mesmo tratamento de requisição. É variável local — não
 * precisa de armazenamento, e por isso não reintroduz o estado que o desenho do
 * sidecar evita.
 *
 * @param verifier valor aleatório, apresentado na troca do código pelo token
 * @param challenge resumo do verificador, enviado no pedido de código
 */
public record PkceChallenge(String verifier, String challenge) {

    /**
     * 32 bytes, que em Base64 sem preenchimento produzem 43 caracteres.
     * <p>
     * A especificação exige entre 43 e 128 caracteres. Este é o mínimo válido, e
     * é suficiente: o verificador precisa ser inadivinhável dentro da janela de
     * segundos entre o pedido do código e a troca.
     */
    private static final int VERIFIER_BYTES = 32;

    private static final String CHALLENGE_METHOD = "S256";
    private static final String DIGEST_ALGORITHM = "SHA-256";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    /**
     * Gera um par novo.
     * <p>
     * Um par por jornada, nunca reaproveitado: reutilizar o verificador
     * permitiria trocar um código interceptado de outra jornada, que é
     * exatamente o que o PKCE existe para impedir.
     */
    public static PkceChallenge generate() {
        byte[] bytes = new byte[VERIFIER_BYTES];
        SECURE_RANDOM.nextBytes(bytes);

        String verifier = ENCODER.encodeToString(bytes);
        return new PkceChallenge(verifier, deriveChallenge(verifier));
    }

    /**
     * Método de derivação declarado ao gateway.
     * <p>
     * Sempre {@code S256}. A especificação também admite {@code plain}, que
     * envia o verificador sem transformação — e aí ele viaja junto do pedido,
     * anulando a proteção. Não é configurável de propósito.
     */
    public String method() {
        return CHALLENGE_METHOD;
    }

    /**
     * Base64 sem preenchimento, na variante segura para URL.
     * <p>
     * É exigência da especificação, não escolha: o valor viaja como parâmetro de
     * consulta, e a variante padrão do Base64 usa {@code +} e {@code /}, que têm
     * significado em URL.
     */
    private static String deriveChallenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return ENCODER.encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 e obrigatorio em toda JVM. Chegar aqui significa ambiente
            // quebrado, e seguir sem PKCE nao e alternativa aceitavel.
            throw new IllegalStateException("SHA-256 indisponível nesta JVM", e);
        }
    }

    /**
     * Sem o {@code toString} gerado: o verificador é o que impede a troca de um
     * código interceptado, e log de exceção com este objeto o entregaria.
     */
    @Override
    public String toString() {
        return "PkceChallenge[verifier=***, challenge=***, method=%s]".formatted(CHALLENGE_METHOD);
    }
}