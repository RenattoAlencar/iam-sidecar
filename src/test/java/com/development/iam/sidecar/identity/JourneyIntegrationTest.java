package com.development.iam.sidecar.identity;

import com.development.iam.sidecar.config.IdentityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercita a jornada contra o gateway de homologação, de verdade.
 * <p>
 * Serve para confirmar o contrato antes de ligar o cliente ao filtro: nome de
 * cabeçalho, versão da API, formato do corpo e estrutura dos callbacks. Um erro
 * em qualquer um deles produz recusa do gateway que pareceria falha de
 * autenticação, e é melhor descobrir aqui do que no fluxo completo.
 *
 * <h2>Não roda no pipeline</h2>
 * Depende de rede externa e de um usuário de homologação, e por isso só executa
 * quando as variáveis de ambiente estiverem presentes. Sem elas, o JUnit pula os
 * métodos em vez de falhar — teste que quebra o build por indisponibilidade de
 * um sistema de terceiro acaba desativado por quem precisa entregar.
 *
 * <h2>Como executar</h2>
 * Definir as variáveis e rodar a classe pela IDE:
 * <pre>
 * IDENTITY_BASE_URL   = https://ig-hml.exemplo.com.br/am
 * IDENTITY_REALM      = alpha
 * IDENTITY_JOURNEY    = factor-onboarding
 * CHANNEL_TOKEN       = <JWT do usuário de homologação>
 * </pre>
 * Opcional, para o cenário de autenticador já configurado:
 * <pre>
 * OTP_CODE            = 149707
 * </pre>
 *
 * <h2>O que não fica registrado</h2>
 * O identificador da jornada e o conteúdo dos callbacks aparecem parcialmente na
 * saída, para conferência manual. É aceitável em execução local com usuário de
 * homologação, e por isso esta classe nunca deve rodar contra produção.
 */
@EnabledIfEnvironmentVariable(named = "TEST_CHANNEL_TOKEN", matches = ".+")
class JourneyIntegrationTest {

    // ==========================================================================
    // CONFIGURAÇÃO DO COMPONENTE
    //
    // Correspondem a propriedades reais de IdentityProperties. Em produção vêm
    // do arquivo de configuração e do values do ambiente.
    // ==========================================================================

    /** Endereço do gateway, incluindo o caminho base. Obrigatória. */
    private static final String BASE_URL = System.getenv("IDENTITY_BASE_URL");

    /** Realm do AM. */
    private static final String REALM = envOrDefault("IDENTITY_REALM", "alpha");

    /** Jornada a conduzir. */
    private static final String JOURNEY = envOrDefault("IDENTITY_JOURNEY", "factor-onboarding");

    /** Tipo do índice de autenticação. {@code service} para jornada nomeada. */
    private static final String JOURNEY_TYPE = envOrDefault("IDENTITY_TYPE", "service");

    /**
     * Nome do cabeçalho pelo qual o token do canal é apresentado. Obrigatória.
     * <p>
     * Vem de variável porque carrega identificação da organização e não deve
     * chegar ao repositório.
     */
    private static final String CHANNEL_TOKEN_HEADER = System.getenv("IDENTITY_TOKEN_HEADER");

    /** Nome do cabeçalho do código do autenticador. Opcional. */
    private static final String CODE_HEADER = envOrDefault("IDENTITY_CODE_HEADER", "");

    // ==========================================================================
    // VALORES QUE O CANAL ENVIARIA
    //
    // Prefixo TEST_ de propósito: NÃO são configuração de nada. Em produção o
    // sidecar recebe estes valores no cabeçalho de cada requisição e os
    // repassa. Aqui não há requisição chegando, e o teste faz o papel do canal.
    // ==========================================================================

    /** JWT do usuário de homologação. Obrigatória; sem ela a classe é pulada. */
    private static final String CHANNEL_TOKEN = System.getenv("TEST_CHANNEL_TOKEN");

    /** Código de autenticador já configurado. Opcional — habilita um teste a mais. */
    private static final String OTP_CODE = System.getenv("TEST_OTP_CODE");

    // --- Placeholders das chamadas seguintes ----------------------------------
    // O passo 1 não usa nenhum destes. Ficam com valor fictício para satisfazer
    // a validação de IdentityProperties, e serão preenchidos por variável quando
    // a obtenção do código de autorização for exercitada.

    private static final String CLIENT_ID_PLACEHOLDER = "nao-usado-no-passo-1";
    private static final String CLIENT_SECRET_PLACEHOLDER = "nao-usado-no-passo-1";
    private static final String REDIRECT_URI_PLACEHOLDER = "https://nao-usado-no-passo-1/callback";
    private static final String SCOPES_PLACEHOLDER = "openid";
    private static final String SESSION_COOKIE_PLACEHOLDER = "nao-usado-no-passo-1";

    @BeforeEach
    void setUp() {
        requireEnv(BASE_URL, "IDENTITY_BASE_URL");
        requireEnv(CHANNEL_TOKEN_HEADER, "IDENTITY_CHANNEL_TOKEN_HEADER");
        requireEnv(CHANNEL_TOKEN_HEADER, "IDENTITY_TOKEN_HEADER");

        IdentityProperties properties = new IdentityProperties(
                URI.create(BASE_URL), REALM, JOURNEY, JOURNEY_TYPE,
                CLIENT_ID_PLACEHOLDER,
                CLIENT_SECRET_PLACEHOLDER,
                REDIRECT_URI_PLACEHOLDER,
                SCOPES_PLACEHOLDER,
                SESSION_COOKIE_PLACEHOLDER,
                CHANNEL_TOKEN_HEADER,
                CODE_HEADER,
                Duration.ofSeconds(5), Duration.ofSeconds(30));

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.readTimeout().toMillis());

        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();

        client = new HttpAuthenticationJourneyClient(restClient, properties);

        System.out.println("Gateway: " + BASE_URL);
        System.out.println("Realm:   " + REALM);
        System.out.println("Jornada: " + JOURNEY + " (tipo: " + JOURNEY_TYPE + ")");
        System.out.println("Cabeçalho do token: " + CHANNEL_TOKEN_HEADER);
        System.out.println("Token:   " + resumir(CHANNEL_TOKEN));
        System.out.println("Código:  " + (OTP_CODE == null ? "não informado" : "informado"));
        System.out.println();
    }

    /**
     * Passo 1. O que se confirma aqui é o contrato inteiro do início: a URL
     * montada, os parâmetros de consulta, o cabeçalho de versão e o nome do
     * cabeçalho do token. Qualquer um errado produz recusa.
     */
    @Test
    @DisplayName("passo 1 — inicia a jornada e recebe o desafio de biometria")
    void startsJourneyAndReceivesBiometricChallenge() {
        JourneyOutcome outcome = client.start(CHANNEL_TOKEN, null);

        imprimir("PASSO 1 — início da jornada", outcome);

        assertThat(outcome.type())
                .as("o gateway deveria devolver um desafio. Recusa aqui costuma ser o JWT "
                        + "expirado ou sem o claim de usuário")
                .isEqualTo(JourneyOutcome.Type.CHALLENGE);

        assertThat(outcome.step().authId())
                .as("o identificador da jornada precisa acompanhar todos os passos seguintes")
                .isNotBlank();

        assertThat(outcome.callbacks())
                .as("o passo 1 devolve o desafio a ser respondido")
                .isNotEmpty();
    }

    /**
     * O código encurta a jornada quando o cliente já tem autenticador
     * configurado: o gateway pula o embarque e vai direto ao fator seguinte.
     * <p>
     * Só executa quando {@code TEST_OTP_CODE} estiver definido — sem ele não há
     * o que exercitar.
     */
    @Test
    @DisplayName("passo 1 com código do autenticador — jornada encurtada")
    @EnabledIfEnvironmentVariable(named = "TEST_OTP_CODE", matches = ".+")
    void startsJourneyWithAuthenticatorCode() {
        JourneyOutcome outcome = client.start(CHANNEL_TOKEN, OTP_CODE);

        imprimir("PASSO 1 — início com código do autenticador", outcome);

        assertThat(outcome.type())
                .as("com código válido o gateway pula o embarque; com inválido, segue o "
                        + "caminho completo. Os dois são desfecho de desafio")
                .isIn(JourneyOutcome.Type.CHALLENGE, JourneyOutcome.Type.COMPLETED);
    }

    /**
     * Passo 2. Responde ao desafio de biometria enviando a foto no campo de
     * entrada do callback recebido.
     * <p>
     * O que se confirma aqui é o formato da continuação: o corpo com
     * {@code authId} e os callbacks devolvidos como vieram, com o valor
     * preenchido. Um erro nisso produz recusa do gateway que pareceria biometria
     * reprovada.
     * <p>
     * <strong>O desfecho da análise não é o que se testa.</strong> Sem uma foto
     * que a Unico aprove, a biometria é reprovada — e isso é resultado válido:
     * significa que a chamada chegou ao ponto de ser analisada. O que não pode
     * acontecer é falha de comunicação ou recusa antes da análise.
     * <p>
     * Com {@code SELFIE_BASE64} definida, exercita o caminho completo até o
     * passo de espera; sem ela, usa um marcador e verifica só o formato.
     */
    @Test
    @DisplayName("passo 2 — responde ao desafio com a foto e recebe o passo de espera")
    void answersBiometricChallengeAndReceivesPolling() {
        JourneyOutcome first = client.start(CHANNEL_TOKEN, null);

        assertThat(first.type())
                .as("o passo 2 depende de um desafio válido no passo 1")
                .isEqualTo(JourneyOutcome.Type.CHALLENGE);

        String authId = first.step().authId();
        List<Map<String, Object>> answered = answerWithSelfie(first.callbacks());

        JourneyOutcome second = client.advance(authId, answered);

        imprimir("PASSO 2 — resposta ao desafio de biometria", second);

        assertThat(second.type())
                .as("recusa aqui costuma ser a foto reprovada, o que é resultado válido; "
                        + "falha de comunicação seria exceção")
                .isIn(JourneyOutcome.Type.CHALLENGE,
                        JourneyOutcome.Type.DENIED,
                        JourneyOutcome.Type.COMPLETED);

        if (second.type() == JourneyOutcome.Type.CHALLENGE) {
            System.out.println("A jornada avançou. Tipo do próximo callback: "
                    + second.callbacks().getFirst().get("type"));
        }
    }

    /**
     * Preenche o campo de entrada do callback recebido, deixando o resto
     * exatamente como veio.
     * <p>
     * O gateway espera o callback de volta na forma em que o enviou — alterar
     * estrutura, ordem ou os campos de saída quebra a jornada, e o erro apareceria
     * como recusa sem explicação.
     * <p>
     * O conteúdo é um JSON em texto com a foto, o nome e o canal, conforme o
     * contrato da jornada.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> answerWithSelfie(
            List<Map<String, Object>> received) {

        String payload = "{\"foto\":\"" + SELFIE_BASE64
                + "\",\"nome\":\"" + USER_NAME
                + "\",\"channel\":\"" + CHANNEL_NAME + "\"}";

        return received.stream()
                .map(callback -> {
                    Map<String, Object> copy = new LinkedHashMap<>(callback);
                    Object input = copy.get("input");

                    if (input instanceof List<?> fields && !fields.isEmpty()) {
                        List<Map<String, Object>> filled = fields.stream()
                                .map(field -> {
                                    Map<String, Object> entry =
                                            new LinkedHashMap<>((Map<String, Object>) field);
                                    entry.put("value", payload);
                                    return entry;
                                })
                                .toList();
                        copy.put("input", filled);
                    }
                    return copy;
                })
                .toList();
    }

    /**
     * Confirma que o cliente traduz recusa em desfecho, e não em exceção.
     * <p>
     * É a distinção que faz o canal receber "não autorizado" em vez de "serviço
     * fora do ar" quando a biometria é reprovada.
     */
    @Test
    @DisplayName("token do canal inválido vira recusa, não indisponibilidade")
    void invalidChannelTokenBecomesDenial() {
        JourneyOutcome outcome = client.start(
                "eyJhbGciOiJIUzI1NiJ9.token-invalido.assinatura-invalida", null);

        imprimir("Token do canal inválido", outcome);

        assertThat(outcome.type())
                .as("o gateway respondeu; recusa não pode virar falha de comunicação")
                .isEqualTo(JourneyOutcome.Type.DENIED);
    }

    /**
     * Imprime o desfecho de forma legível, com o suficiente para conferir o
     * contrato sem despejar credencial inteira na saída.
     */
    private static void imprimir(String titulo, JourneyOutcome outcome) {
        System.out.println("=".repeat(70));
        System.out.println(titulo);
        System.out.println("=".repeat(70));
        System.out.println("Desfecho: " + outcome.type());

        if (outcome.reason() != null) {
            System.out.println("Motivo:   " + outcome.reason());
        }

        JourneyStep step = outcome.step();
        if (step == null) {
            System.out.println();
            return;
        }

        if (step.authId() != null) {
            System.out.println("authId:   " + resumir(step.authId()));
        }
        if (step.tokenId() != null) {
            System.out.println("tokenId:  " + resumir(step.tokenId()));
        }

        System.out.println("Callbacks: " + step.callbacks().size());
        step.callbacks().forEach(callback -> {
            System.out.println("  tipo:   " + callback.get("type"));
            imprimirCampo("  output", callback.get("output"));
            imprimirCampo("  input ", callback.get("input"));
        });
        System.out.println();
    }

    @SuppressWarnings("unchecked")
    private static void imprimirCampo(String rotulo, Object valor) {
        if (!(valor instanceof List<?> itens) || itens.isEmpty()) {
            return;
        }
        itens.forEach(item -> {
            if (item instanceof Map<?, ?> campo) {
                System.out.println(rotulo + ": " + campo.get("name") + " = "
                        + resumirValor(campo.get("value")));
            }
        });
    }

    /**
     * Mostra o começo e o fim de um valor longo. O suficiente para conferir o
     * formato, sem imprimir uma credencial inteira na saída do console.
     */
    private static String resumir(String valor) {
        if (valor.length() <= 24) {
            return valor;
        }
        return valor.substring(0, 12) + "..." + valor.substring(valor.length() - 8)
                + " (" + valor.length() + " caracteres)";
    }

    private static String resumirValor(Object valor) {
        if (valor == null) {
            return "(vazio)";
        }
        String texto = String.valueOf(valor);
        return texto.length() <= 60 ? texto : resumir(texto);
    }
}