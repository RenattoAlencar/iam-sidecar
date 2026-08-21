package com.development.iam.sidecar.channel;

import java.util.Arrays;

/**
 * Canais de step-up suportados pelo sidecar.
 *
 * <p>Cada canal representa uma jornada distinta no Identity Gateway (IG),
 * identificada pelo parâmetro {@code authIndexValue} enviado ao endpoint
 * {@code /am/json/realms/alpha/authenticate?authIndexType=service}.
 *
 * <p>O fluxo de autenticação é idêntico entre os canais — mesmo endpoint,
 * mesmo cabeçalho {@code x-porto-authentication} e mesmo padrão cross-device
 * com polling. Apenas a jornada acionada muda. Por isso, adicionar um novo
 * canal significa somente acrescentar uma constante aqui: nenhuma lógica de
 * fluxo precisa ser alterada. É essa a garantia de que o sidecar é
 * agnóstico ao canal.
 */
public enum Channel {

    /** Autorização consultiva do PDC (banco). */
    BANK_AUTHZ_CONSULTIVO("pdc-bank-authz-consultivo"),

    /** Onboarding via Factor. */
    FACTOR_ONBOARDING("factor-onboarding");

    private final String authIndexValue;

    Channel(String authIndexValue) {
        this.authIndexValue = authIndexValue;
    }

    /**
     * Retorna o {@code authIndexValue} da jornada correspondente a este canal.
     * É o valor usado como parâmetro de query na chamada ao IG.
     *
     * @return o identificador da jornada no IG (nunca {@code null})
     */
    public String authIndexValue() {
        return authIndexValue;
    }

    /**
     * Resolve o canal a partir do {@code authIndexValue} informado.
     *
     * @param authIndexValue identificador da jornada no IG
     * @return o canal correspondente
     * @throws IllegalArgumentException se nenhum canal corresponder ao valor informado
     */
    public static Channel fromAuthIndexValue(String authIndexValue) {
        return Arrays.stream(values())
                .filter(channel -> channel.authIndexValue.equals(authIndexValue))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Nenhum canal corresponde ao authIndexValue: " + authIndexValue));
    }
}