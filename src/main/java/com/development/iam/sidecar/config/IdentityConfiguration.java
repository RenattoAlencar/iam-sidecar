package com.development.iam.sidecar.config;

import com.development.iam.sidecar.identity.AuthenticationJourneyClient;
import com.development.iam.sidecar.identity.HttpAuthenticationJourneyClient;
import com.development.iam.sidecar.identity.HttpTokenIssuer;
import com.development.iam.sidecar.identity.TokenIssuer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Monta o acesso ao gateway de identidade.
 */
@Configuration
@EnableConfigurationProperties(IdentityProperties.class)
public class IdentityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IdentityConfiguration.class);

    /**
     * Cliente usado nas chamadas ao gateway.
     *
     * <h3>O redirecionamento não pode ser seguido</h3>
     * {@link HttpClient.Redirect#NEVER} não é preferência: o código de
     * autorização vem no cabeçalho {@code Location} de um {@code 302}, e um
     * cliente que siga o redirecionamento tenta buscar o endereço de retorno —
     * que o sidecar não expõe — descartando o código no caminho.
     * <p>
     * O sintoma seria enganoso: a chamada funciona no curl, que não segue
     * redirecionamento por padrão, e falha aqui. Vale como armadilha registrada:
     * trocar esta fábrica por outra sem repetir esta configuração quebra a
     * emissão do token de forma difícil de diagnosticar.
     *
     * <h3>Fábrica baseada no cliente da JDK</h3>
     * {@link JdkClientHttpRequestFactory} envolve o {@link HttpClient} da JDK,
     * que permite desligar o redirecionamento — coisa que a fábrica simples do
     * Spring não expõe.
     *
     * <h3>Sobre o pool de conexões</h3>
     * O {@link HttpClient} da JDK reaproveita conexões por padrão, o que importa
     * mais do que parece: a jornada de biometria tem espera com repetição a cada
     * poucos segundos, e sem reaproveitamento seriam dezenas de handshakes TLS
     * completos por cliente.
     */
    @Bean
    public RestClient identityRestClient(IdentityProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.connectTimeout())
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * Registra o condutor da jornada e imprime a configuração efetiva no boot.
     * <p>
     * Vale como artefato de auditoria pelo mesmo motivo da matriz de rotas: o log
     * de startup costuma estar acessível a mais gente do que o ConfigMap, e
     * apontar para a jornada errada é o tipo de erro que não falha — apenas
     * autentica o cliente por um caminho que não era o pretendido.
     */
    @Bean
    public AuthenticationJourneyClient authenticationJourneyClient(
            RestClient identityRestClient, IdentityProperties properties) {

        log.info("Gateway de identidade: {}", properties);

        if (properties.authenticatorCodeHeader().isBlank()) {
            log.info("Cabeçalho do código de autenticador não configurado: "
                    + "o atalho da jornada não será usado.");
        }

        return new HttpAuthenticationJourneyClient(identityRestClient, properties);
    }

    /**
     * Registra o emissor do token.
     * <p>
     * Depende do mesmo cliente da jornada — e portanto do redirecionamento
     * desligado, sem o qual o código de autorização se perde.
     */
    @Bean
    public TokenIssuer tokenIssuer(RestClient identityRestClient,
                                   IdentityProperties properties) {
        return new HttpTokenIssuer(identityRestClient, properties);
    }
}