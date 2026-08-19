package com.development.iam.sidecar.config;

import com.development.iam.sidecar.route.RouteResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class RouteConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RouteConfiguration.class);

    @Bean
    public RouteResolver routeResolver(ProxyProperties proxyProperties) {
        return new RouteResolver(proxyProperties.interceptRules());
    }

    @Bean
    public ApplicationRunner routeMatrixAuditLogger(ProxyProperties proxyProperties) {
        return args -> {
            log.info("Sidecar encaminhando para {} (connectTimeout={}, readTimeout={}, "
                            + "corpo até {} bytes)",
                    proxyProperties.target(),
                    proxyProperties.connectTimeout(),
                    proxyProperties.readTimeout(),
                    proxyProperties.maxBodyBytes());

            log.info("Matriz de interceptação com {} regra(s):",
                    proxyProperties.interceptRules().size());
            proxyProperties.interceptRules().forEach(rule -> log.info("  {}", rule));

            if (proxyProperties.reservedHeaders().isEmpty()) {
                log.info("Nenhum header reservado ao sidecar configurado.");
            } else {
                log.info("Headers reservados ao sidecar: {}", proxyProperties.reservedHeaders());
            }

            log.warn("Todo path fora da matriz é encaminhado ao BFF sem verificação.");
        };
    }
}