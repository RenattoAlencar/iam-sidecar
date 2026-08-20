package com.development.iam.sidecar.config;

import com.development.iam.sidecar.identity.AuthenticationJourneyClient;
import com.development.iam.sidecar.proxy.ProxyFilter;
import com.development.iam.sidecar.proxy.ProxyHeaderPolicy;
import com.development.iam.sidecar.proxy.RequestForwarder;
import com.development.iam.sidecar.route.RouteResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;

/**
 * Monta a cadeia do proxy: cliente HTTP, política de headers, encaminhador e
 * registro do filtro.
 * <p>
 * As classes que decidem não carregam anotação de Spring — são funções puras,
 * testáveis sem contexto. A consequência é que alguém precisa construí-las, e
 * esse alguém é esta classe.
 */
@Configuration
@EnableConfigurationProperties(ProxyProperties.class)
public class ProxyConfiguration {

    /**
     * Cliente usado para falar com o BFF.
     * <p>
     * {@link HttpClient} da JDK e não um cliente de alto nível: o encaminhamento
     * precisa do corpo como fluxo bruto, sem passar por conversor de mensagem.
     * <p>
     * {@link HttpClient.Redirect#NEVER} é obrigatório num proxy. Seguir o
     * redirecionamento aqui faria o sidecar consumir o {@code 302} e devolver ao
     * canal a resposta do destino final, escondendo do cliente que houve
     * redirecionamento — e permitindo que um BFF comprometido fizesse o sidecar
     * buscar um endereço arbitrário.
     * <p>
     * Fixado em HTTP/1.1: o destino é um BFF no mesmo pod, e a negociação de
     * HTTP/2 só adiciona ida e volta de handshake sem ganho em loopback.
     */
    @Bean
    public HttpClient proxyHttpClient(ProxyProperties proxyProperties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(proxyProperties.connectTimeout())
                .build();
    }

    @Bean
    public ProxyHeaderPolicy proxyHeaderPolicy(ProxyProperties proxyProperties) {
        return new ProxyHeaderPolicy(proxyProperties.reservedHeaders());
    }

    @Bean
    public RequestForwarder requestForwarder(HttpClient proxyHttpClient,
                                             ProxyProperties proxyProperties,
                                             ProxyHeaderPolicy proxyHeaderPolicy) {
        return new RequestForwarder(proxyHttpClient, proxyProperties, proxyHeaderPolicy);
    }

    /**
     * Registra o filtro explicitamente em vez de deixá-lo ser detectado por
     * varredura de componentes.
     * <p>
     * O registro automático de um {@code Filter} exposto como bean não garante
     * ordem nem escopo de URL. Aqui os dois ficam explícitos: precedência máxima
     * e todas as URLs. Precedência máxima porque nenhum outro filtro pode ver a
     * requisição antes do controle que decide se ela sequer prossegue.
     * <p>
     * O nome é específico do componente, e não genérico: registro com nome
     * repetido é sobrescrito em silêncio, e o sintoma seria o filtro
     * simplesmente não executar.
     * <p>
     * O registro vale apenas para o contexto principal. O actuator roda em porta
     * de management separada, com contexto próprio, e por isso não passa por
     * aqui — o que é deliberado: sonda de saúde encaminhada ao BFF responderia
     * sobre o componente errado.
     */
    @Bean
    public FilterRegistrationBean<ProxyFilter> sidecarProxyFilterRegistration(
            RouteResolver routeResolver,
            RequestForwarder requestForwarder,
            AuthenticationJourneyClient journeyClient,
            IdentityProperties identityProperties,
            ObjectMapper objectMapper) {

        FilterRegistrationBean<ProxyFilter> registration = new FilterRegistrationBean<>(
                new ProxyFilter(routeResolver, requestForwarder, journeyClient,
                        identityProperties, objectMapper));

        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("sidecarProxyFilter");
        return registration;
    }
}