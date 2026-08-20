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
@EnabledIfEnvironmentVariable(named = "CHANNEL_TOKEN", matches = ".+")
class JourneyIntegrationTest {

    private static final String BASE_URL = System.getenv("IDENTITY_BASE_URL");
    private static final String REALM = envOrDefault("IDENTITY_REALM", "alpha");
    private static final String JOURNEY = envOrDefault("IDENTITY_JOURNEY", "factor-onboarding");
    private static final String CHANNEL_TOKEN = System.getenv("CHANNEL_TOKEN");
    private static final String OTP_CODE = System.getenv("OTP_CODE");

    private AuthenticationJourneyClient client;

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @BeforeEach
    void setUp() {
        IdentityProperties properties = new IdentityProperties(
                URI.create(BASE_URL), REALM, JOURNEY, "service",
                envOrDefault("IDENTITY_CLIENT_ID", "nao-usado-neste-teste"),
                envOrDefault("IDENTITY_CLIENT_SECRET", "nao-usado-neste-teste"),
                envOrDefault("IDENTITY_REDIRECT_URI", "https://nao-usado/callback"),
                envOrDefault("IDENTITY_SCOPES", "openid"),
                envOrDefault("IDENTITY_SESSION_COOKIE_NAME", "nao-usado-neste-teste"),
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
        System.out.println("Jornada: " + JOURNEY);
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
     * Só executa quando {@code OTP_CODE} estiver definido — sem ele não há o que
     * exercitar.
     */
    @Test
    @DisplayName("passo 1 com código do autenticador — jornada encurtada")
    @EnabledIfEnvironmentVariable(named = "OTP_CODE", matches = ".+")
    void startsJourneyWithAuthenticatorCode() {
        JourneyOutcome outcome = client.start(CHANNEL_TOKEN, OTP_CODE);

        imprimir("PASSO 1 — início com código do autenticador", outcome);

        assertThat(outcome.type())
                .as("com código válido o gateway pula o embarque; com inválido, segue o "
                        + "caminho completo. Os dois são desfecho de desafio")
                .isIn(JourneyOutcome.Type.CHALLENGE, JourneyOutcome.Type.COMPLETED);
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