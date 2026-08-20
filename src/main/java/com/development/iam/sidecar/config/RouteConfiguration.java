package com.development.iam.sidecar.config;

import com.development.iam.sidecar.route.RouteResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Liga a configuração à resolução de rota e registra a matriz efetiva no boot.
 */
@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(ChannelProperties.class)
public class RouteConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RouteConfiguration.class);

    /**
     * Recebe apenas a lista de regras, não a configuração inteira, mantendo o
     * resolver ignorante sobre timeouts e sobre o alvo do proxy — nada disso
     * influencia a decisão de rota.
     * <p>
     * Padrão inválido derruba o boot aqui, na compilação dos padrões dentro do
     * construtor.
     */
    @Bean
    public RouteResolver routeResolver(ProxyProperties proxyProperties,
                                       ChannelProperties channelProperties) {

        // Derruba o boot se alguma regra cobrir o endpoint de resposta ao
        // desafio: exigir confirmacao para responder ao desafio que concede a
        // confirmacao prende o cliente num ciclo sem saida, e o sintoma nao
        // aponta para a configuracao.
        channelProperties.requireOutsideMatrix(proxyProperties.interceptRules());

        return new RouteResolver(proxyProperties.interceptRules());
    }

    /**
     * Registra a matriz efetiva depois que o contexto sobe.
     * <p>
     * Vale como artefato de auditoria. O log de startup costuma estar acessível
     * a mais gente do que a configuração do cluster, e é onde dá para conferir o
     * que está protegido em cada ambiente sem pedir acesso ao ConfigMap.
     * <p>
     * A última linha é deliberadamente incômoda e sai em nível de alerta: a
     * matriz é fail-open, e quem lê o log precisa sair sabendo que tudo o que
     * não está listado atravessa sem verificação.
     */
    @Bean
    public ApplicationRunner routeMatrixAuditLogger(ProxyProperties proxyProperties,
                                                    ChannelProperties channelProperties) {
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

            log.info("Endpoint de resposta ao desafio: {}", channelProperties.challengePath());
            log.warn("Todo path fora da matriz é encaminhado ao BFF sem verificação.");
        };
    }
}