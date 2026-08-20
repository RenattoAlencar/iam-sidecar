package com.development.iam.sidecar.identity;

import com.development.iam.sidecar.config.IdentityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
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
 * <h2>Valores preenchidos no código, e o que isso exige</h2>
 * Os valores abaixo ficam no arquivo para facilitar a execução pela IDE, sem
 * depender de configuração de execução. <strong>Isso é temporário e não pode
 * chegar ao repositório:</strong> o token do canal é credencial, e credencial
 * commitada permanece no histórico mesmo depois de removida.
 * <p>
 * Antes de qualquer commit, esvaziar o bloco de valores. Com ele vazio, a classe
 * falha com mensagem explicando o que preencher — em vez de rodar contra
 * endereço inválido e produzir erro sem sentido.
 *
 * <h2>Não roda no pipeline</h2>
 * A anotação {@link Disabled} impede que o {@code mvn test} a execute. Depende
 * de rede externa e de um usuário de homologação — teste que quebra o build por
 * indisponibilidade de um sistema de terceiro acaba desativado por quem precisa
 * entregar.
 * <p>
 * Para rodar, comente a anotação ou execute o método pela IDE, que ignora o
 * {@code @Disabled} quando a execução é individual.
 */
@Disabled("execução manual: depende do gateway de homologação e de credencial preenchida abaixo")
class JourneyIntegrationTest {

    // ==========================================================================
    //  CONFIGURAÇÃO DO COMPONENTE
    //
    //  Correspondem a propriedades reais de IdentityProperties. Em produção vêm
    //  do arquivo de configuração e do values do ambiente.
    // ==========================================================================

    /** Endereço do gateway, incluindo o caminho base. Sem barra no final. */
    private static final String BASE_URL = "";

    /** Realm do AM. */
    private static final String REALM = "alpha";

    /** Jornada a conduzir. */
    private static final String JOURNEY = "factor-onboarding";

    /** Tipo do índice de autenticação. {@code service} para jornada nomeada. */
    private static final String JOURNEY_TYPE = "service";

    /** Nome do cabeçalho pelo qual o token do canal é apresentado ao gateway. */
    private static final String CHANNEL_TOKEN_HEADER = "";

    /** Nome do cabeçalho do código do autenticador. Vazio desativa o envio. */
    private static final String CODE_HEADER = "";

    // ==========================================================================
    //  VALORES QUE O CANAL ENVIARIA
    //
    //  Não são configuração de nada. Em produção o sidecar recebe estes valores
    //  no cabeçalho e no corpo de cada requisição, e os repassa. Aqui não há
    //  requisição chegando, e o teste faz o papel do canal.
    //
    //  ESVAZIAR ANTES DE COMMITAR.
    // ==========================================================================

    /** JWT do usuário de homologação. Expira em cerca de uma hora. */
    private static final String CHANNEL_TOKEN = "";

    /** Código de um autenticador já configurado. Vazio pula o teste do atalho. */
    private static final String OTP_CODE = "";

    /**
     * Conteúdo da captura biométrica, como o canal o obtém.
     * <p>
     * Vazio, o passo 2 envia um marcador e verifica apenas o formato da chamada:
     * a biometria será reprovada, o que é resultado válido — significa que a
     * requisição chegou ao ponto de ser analisada.
     */
    private static final String SELFIE = "";

    /** Nome do usuário, como o canal o envia junto da captura. */
    private static final String USER_NAME = "Usuario Teste";

    /** Identificação do canal, como ele a envia junto da captura. */
    private static final String CHANNEL_NAME = "TESTE";

    // ==========================================================================
    //  Não usados no passo 1 e 2. Preenchidos quando a obtenção do código de
    //  autorização for exercitada.
    // ==========================================================================

    private static final String CLIENT_ID = "nao-usado-ainda";
    private static final String CLIENT_SECRET = "nao-usado-ainda";
    private static final String REDIRECT_URI = "https://nao-usado-ainda/callback";
    private static final String SCOPES = "openid";
    private static final String SESSION_COOKIE_NAME = "nao-usado-ainda";

    private AuthenticationJourneyClient client;

    @BeforeEach
    void setUp() {
        requirePreenchido(BASE_URL, "BASE_URL", "endereço do gateway, terminando em /am");
        requirePreenchido(CHANNEL_TOKEN_HEADER, "CHANNEL_TOKEN_HEADER",
                "nome do cabeçalho do token do canal");
        requirePreenchido(CHANNEL_TOKEN, "CHANNEL_TOKEN",
                "JWT do usuário de homologação");

        IdentityProperties properties = new IdentityProperties(
                URI.create(BASE_URL), REALM, JOURNEY, JOURNEY_TYPE,
                CLIENT_ID, CLIENT_SECRET, REDIRECT_URI, SCOPES, SESSION_COOKIE_NAME,
                CHANNEL_TOKEN_HEADER, CODE_HEADER,
                Duration.ofSeconds(5), Duration.ofSeconds(30));

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.readTimeout().toMillis());

        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();

        client = new HttpAuthenticationJourneyClient(restClient, properties);

        System.out.println("Gateway:  " + BASE_URL);
        System.out.println("Realm:    " + REALM);
        System.out.println("Jornada:  " + JOURNEY + " (tipo: " + JOURNEY_TYPE + ")");
        System.out.println("Cabeçalho do token: " + CHANNEL_TOKEN_HEADER);
        System.out.println("Token:    " + resumir(CHANNEL_TOKEN));
        System.out.println("Captura:  " + (SELFIE.isBlank() ? "marcador" : resumir(SELFIE)));
        System.out.println();
    }

    /**
     * Falha com mensagem clara quando um valor obrigatório está vazio.
     * <p>
     * Sem isso, {@code BASE_URL} vazia produziria erro dentro do
     * {@code URI.create}, e a causa — bloco de valores não preenchido — não
     * estaria em lugar nenhum da mensagem.
     */
    private static void requirePreenchido(String valor, String nome, String descricao) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException(
                    "Preencher a constante " + nome + " no início desta classe: " + descricao);
        }
    }

    /**
     * Passo 1. Confirma o contrato inteiro do início: a URL montada, os
     * parâmetros de consulta, o cabeçalho de versão e o nome do cabeçalho do
     * token. Qualquer um errado produz recusa.
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
     * Passo 2. Responde ao desafio enviando a captura no campo de entrada do
     * callback recebido.
     * <p>
     * O que se confirma é o formato da continuação: corpo com {@code authId} e
     * os callbacks devolvidos como vieram, com o valor preenchido. Erro nisso
     * produz recusa que pareceria biometria reprovada.
     * <p>
     * <strong>O desfecho da análise não é o que se testa.</strong> Sem uma
     * captura que o serviço de biometria aprove, a análise é reprovada — e isso
     * é resultado válido: significa que a chamada chegou ao ponto de ser
     * analisada. O que não pode acontecer é falha de comunicação.
     */
    @Test
    @DisplayName("passo 2 — responde ao desafio e recebe o passo seguinte")
    void answersChallengeAndReceivesNextStep() {
        JourneyOutcome first = client.start(CHANNEL_TOKEN, null);

        assertThat(first.type())
                .as("o passo 2 depende de um desafio válido no passo 1")
                .isEqualTo(JourneyOutcome.Type.CHALLENGE);

        String authId = first.step().authId();
        List<Map<String, Object>> answered = answerWithCapture(first.callbacks());

        JourneyOutcome second = client.advance(authId, answered);

        imprimir("PASSO 2 — resposta ao desafio", second);

        assertThat(second.type())
                .as("recusa aqui costuma ser a captura reprovada, o que é resultado válido; "
                        + "falha de comunicação seria exceção")
                .isIn(JourneyOutcome.Type.CHALLENGE,
                        JourneyOutcome.Type.DENIED,
                        JourneyOutcome.Type.COMPLETED);

        if (second.type() == JourneyOutcome.Type.CHALLENGE) {
            System.out.println("A jornada avançou. Próximo callback: "
                    + second.callbacks().getFirst().get("type"));
        }
    }

    /**
     * O código encurta a jornada quando o cliente já tem autenticador
     * configurado: o gateway pula o embarque e vai direto ao fator seguinte.
     * <p>
     * Sem {@code OTP_CODE} e {@code CODE_HEADER} preenchidos, não há o que
     * exercitar — o teste é encerrado sem falhar.
     */
    @Test
    @DisplayName("passo 1 com código do autenticador — jornada encurtada")
    void startsJourneyWithAuthenticatorCode() {
        if (OTP_CODE.isBlank() || CODE_HEADER.isBlank()) {
            System.out.println("OTP_CODE ou CODE_HEADER vazios: cenário do atalho não exercitado.");
            return;
        }

        JourneyOutcome outcome = client.start(CHANNEL_TOKEN, OTP_CODE);

        imprimir("PASSO 1 — início com código do autenticador", outcome);

        assertThat(outcome.type())
                .as("com código válido o gateway pula o embarque; com inválido, segue o "
                        + "caminho completo")
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
     * Preenche o campo de entrada do callback recebido, deixando o resto
     * exatamente como veio.
     * <p>
     * O gateway espera o callback de volta na forma em que o enviou — alterar
     * estrutura, ordem ou os campos de saída quebra a jornada, e o erro
     * apareceria como recusa sem explicação.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> answerWithCapture(
            List<Map<String, Object>> received) {

        String captura = SELFIE.isBlank() ? "captura-nao-fornecida" : SELFIE;

        String payload = "{\"foto\":\"" + captura
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
        if (valor == null || valor.length() <= 24) {
            return valor == null ? "(vazio)" : valor;
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