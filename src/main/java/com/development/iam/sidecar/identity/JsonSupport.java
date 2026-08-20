package com.development.iam.sidecar.identity;

import tools.jackson.databind.ObjectMapper;

/**
 * Leitura de JSON para o bloco de identidade.
 * <p>
 * Existe porque o cliente da jornada precisa ler o corpo da resposta como texto
 * — para poder inspecionar o status antes de desserializar — e o
 * {@code RestClient} não faz as duas coisas na mesma chamada.
 * <p>
 * Um {@link ObjectMapper} próprio, e não o do contexto: este lê apenas respostas
 * do gateway, e configuração aplicada ao mapeador da aplicação não deveria
 * mudar como uma integração externa é interpretada.
 */
final class JsonSupport {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonSupport() {
    }

    static <T> T read(String json, Class<T> type) {
        return OBJECT_MAPPER.readValue(json, type);
    }
}