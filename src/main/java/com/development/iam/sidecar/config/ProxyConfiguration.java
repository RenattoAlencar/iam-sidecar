package com.development.iam.sidecar.config;

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


@Configuration
@EnableConfigurationProperties(ProxyProperties.class)
public class ProxyConfiguration {

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

    @Bean
    public FilterRegistrationBean<ProxyFilter> sidecarProxyFilterRegistration(
            RouteResolver routeResolver,
            RequestForwarder requestForwarder,
            ObjectMapper objectMapper) {

        FilterRegistrationBean<ProxyFilter> registration = new FilterRegistrationBean<>(
                new ProxyFilter(routeResolver, requestForwarder, objectMapper));

        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("sidecarProxyFilter");
        return registration;
    }
}