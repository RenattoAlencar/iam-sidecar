package com.development.iam.sidecar.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * Configuração de acesso ao gateway de identidade.
 * <p>
 * Cobre as três chamadas que o sidecar faz: conduzir a jornada, obter o código
 * de autorização e trocá-lo por um token.
 *
 * <h2>Validações no construtor</h2>
 * As anotações cobrem presença de campo. As verificações abaixo são de
 * conteúdo e não têm anotação equivalente — HTTPS fora de loopback e timeouts
 * positivos. Ficam no construtor compacto porque um record bound é criado pelo
 * binder, fora do ciclo normal de inicialização de bean.
 *
 * @param baseUrl        endereço do gateway, incluindo o caminho base.
 *                       <p>
 *                       <strong>HTTPS obrigatório fora de loopback.</strong> Por
 *                       esta conexão trafegam o token do canal, a foto da
 *                       biometria, a semente do TOTP e o token de acesso
 *                       emitido. Loopback é liberado apenas para servidor falso
 *                       em teste.
 * @param realm          realm do AM. Compõe o path das chamadas.
 * @param journey        nome da jornada a conduzir (ex.: {@code factor-onboarding}).
 *                       <p>
 *                       Sem padrão útil: qualquer valor fixo seria uma jornada
 *                       específica, e conduzir a errada autentica o cliente por
 *                       um caminho que não é o pretendido — sem que nada falhe.
 *                       Ausente, o boot falha.
 * @param journeyType    tipo do índice de autenticação. {@code service} para
 *                       jornada nomeada, que é o caso.
 * @param clientId       identificador do cliente OAuth do sidecar, usado no
 *                       {@code authorize} e no {@code access_token}.
 * @param clientSecret   segredo do cliente OAuth.
 *                       <p>
 *                       <strong>Vem de Secret, nunca de ConfigMap.</strong> Quem
 *                       o obtiver emite token em nome do sidecar. Não aparece em
 *                       log nem na representação textual desta classe.
 * @param redirectUri    endereço de retorno registrado no cliente OAuth.
 *                       <p>
 *                       O sidecar nunca o visita — ele lê o código do cabeçalho
 *                       {@code Location} do redirecionamento e para ali. Mas o
 *                       valor precisa bater exatamente com o registrado no AM,
 *                       ou o {@code authorize} é recusado.
 *                       <p>
 *                       Sem padrão: um valor inventado passaria no boot e
 *                       falharia só na primeira jornada real.
 * @param scopes         escopos pedidos no {@code authorize}, separados por
 *                       espaço.
 * @param sessionCookieName nome do cookie pelo qual a sessão da jornada é
 *                       apresentada ao {@code authorize}.
 *                       <p>
 *                       <strong>É específico da instalação do AM</strong> — um
 *                       identificador gerado, diferente em cada ambiente.
 *                       Configuração e não constante por isso. Errar o nome faz
 *                       o AM ignorar a sessão e responder a tela de login em vez
 *                       do código, o que não parece erro de configuração.
 * @param channelTokenHeader nome do cabeçalho pelo qual o token do canal é
 *                       apresentado ao gateway.
 *                       <p>
 *                       Configuração e não constante por dois motivos. O
 *                       primeiro é que é acordo com outra equipe, e acordo muda
 *                       sem que o comportamento do componente mude. O segundo é
 *                       que nomes de cabeçalho carregam identificação da
 *                       organização, e mantê-los fora do código evita que
 *                       cheguem ao repositório.
 *                       <p>
 *                       Sem padrão: um valor inventado produz recusa do gateway
 *                       idêntica à de token ausente, e o diagnóstico aponta para
 *                       o token em vez de para a configuração.
 * @param authenticatorCodeHeader nome do cabeçalho pelo qual o código de um
 *                       autenticador já configurado é apresentado.
 *                       <p>
 *                       Opcional. Vazio, o sidecar nunca envia o código — o que
 *                       é correto num ambiente onde esse atalho da jornada não
 *                       existe.
 * @param connectTimeout limite para estabelecer conexão com o gateway.
 * @param readTimeout    limite para a resposta do gateway.
 *                       <p>
 *                       Precisa acomodar o passo mais lento da jornada. Sem
 *                       limite, uma indisponibilidade do gateway prende as
 *                       threads do sidecar até esgotá-las, derrubando junto o
 *                       tráfego que apenas atravessa.
 */
@Validated
@ConfigurationProperties(prefix = "identity")
public record IdentityProperties(

        @NotNull(message = "identity.base-url é obrigatório")
        URI baseUrl,

        @DefaultValue("alpha")
        @NotBlank(message = "identity.realm é obrigatório")
        String realm,

        @NotBlank(message = "identity.journey é obrigatório e não tem padrão: "
                + "conduzir a jornada errada autentica o cliente por um caminho não pretendido")
        String journey,

        @DefaultValue("service")
        @NotBlank(message = "identity.journey-type é obrigatório")
        String journeyType,

        @NotBlank(message = "identity.client-id é obrigatório")
        String clientId,

        @NotBlank(message = "identity.client-secret é obrigatório")
        String clientSecret,

        @NotBlank(message = "identity.redirect-uri é obrigatório e precisa bater com o "
                + "registrado no cliente OAuth")
        String redirectUri,

        @DefaultValue("openid")
        @NotBlank(message = "identity.scopes é obrigatório")
        String scopes,

        @NotBlank(message = "identity.session-cookie-name é obrigatório e é específico "
                + "da instalação do AM")
        String sessionCookieName,

        @NotBlank(message = "identity.channel-token-header é obrigatório e não tem padrão: "
                + "nome errado produz recusa idêntica à de token ausente")
        String channelTokenHeader,

        @DefaultValue("")
        String authenticatorCodeHeader,

        @DefaultValue("2s")
        @NotNull(message = "identity.connect-timeout é obrigatório")
        Duration connectTimeout,

        @DefaultValue("10s")
        @NotNull(message = "identity.read-timeout é obrigatório")
        Duration readTimeout
) {

    public IdentityProperties {
        authenticatorCodeHeader = authenticatorCodeHeader == null ? "" : authenticatorCodeHeader;

        requireSecureTransport(baseUrl);
        requirePositive(connectTimeout, "identity.connect-timeout");
        requirePositive(readTimeout, "identity.read-timeout");
    }



    /**
     * Exige HTTPS, liberando apenas loopback.
     * <p>
     * A exceção existe para servidor falso em teste, que roda em
     * {@code 127.0.0.1} e onde não há rede para interceptar. Fora disso, o
     * tráfego carrega credencial em ambas as direções e não pode ir em claro.
     */
    private static void requireSecureTransport(URI baseUrl) {
        if (baseUrl == null) {
            return; // A anotação já recusa; aqui não há o que verificar.
        }

        String host = baseUrl.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    "identity.base-url precisa conter host explícito");
        }

        boolean loopback = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);

        if (!loopback && !"https".equalsIgnoreCase(baseUrl.getScheme())) {
            throw new IllegalArgumentException(
                    "identity.base-url precisa usar HTTPS. Por esta conexão trafegam o token "
                            + "do canal, a biometria e o token emitido.");
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException(property + " precisa ser positivo");
        }
    }

    /**
     * Sem o {@code toString} gerado pelo record: ele imprimiria o
     * {@code clientSecret}. Qualquer log que receba este objeto cai aqui, e
     * configuração costuma ser registrada no boot.
     */
    @Override
    public String toString() {
        return ("IdentityProperties[baseUrl=%s, realm=%s, journey=%s, journeyType=%s, "
                + "clientId=%s, clientSecret=***, redirectUri=%s, scopes=%s, "
                + "sessionCookieName=%s, channelTokenHeader=%s, authenticatorCodeHeader=%s, "
                + "connectTimeout=%s, readTimeout=%s]")
                .formatted(baseUrl, realm, journey, journeyType,
                        clientId, redirectUri, scopes,
                        sessionCookieName, channelTokenHeader,
                        authenticatorCodeHeader.isBlank() ? "não configurado" : authenticatorCodeHeader,
                        connectTimeout, readTimeout);
    }
}