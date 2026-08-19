package com.development.iam.sidecar.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


@Validated
@ConfigurationProperties(prefix = "proxy")
public record ProxyProperties(

        @NotNull(message = "proxy.target é obrigatório")
        URI target,

        @DefaultValue("2s")
        @NotNull(message = "proxy.connect-timeout é obrigatório")
        Duration connectTimeout,

        @DefaultValue("10s")
        @NotNull(message = "proxy.read-timeout é obrigatório")
        Duration readTimeout,

        @DefaultValue("2097152")
        @Positive(message = "proxy.max-body-bytes deve ser positivo")
        long maxBodyBytes,

        @DefaultValue
        List<String> reservedHeaders,

        @Valid
        @NotEmpty(message = "proxy.intercept-rules deve declarar ao menos uma regra")
        List<InterceptRule> interceptRules
) {

    public ProxyProperties {

        interceptRules = interceptRules == null ? List.of() : List.copyOf(interceptRules);
        reservedHeaders = reservedHeaders == null ? List.of() : List.copyOf(reservedHeaders);

        requireLoopbackTarget(target);
        requirePositive(connectTimeout, "proxy.connect-timeout");
        requirePositive(readTimeout, "proxy.read-timeout");
        requireDistinctRules(interceptRules);
    }


    private static void requireLoopbackTarget(URI target) {
        if (target == null) {
            return;
        }

        String host = target.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    "proxy.target precisa conter host explícito (ex.: http://127.0.0.1:8081)");
        }


        String bareHost = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;

        try {
            if (!InetAddress.getByName(bareHost).isLoopbackAddress()) {
                throw new IllegalArgumentException(
                        "proxy.target precisa apontar para loopback. BFF alcançável de fora do pod "
                                + "torna o sidecar contornável e anula a interceptação.");
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(
                    "proxy.target tem host que não resolve, e por isso não se pode afirmar que "
                            + "aponta para dentro do pod", e);
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value != null && (value.isNegative() || value.isZero())) {
            throw new IllegalArgumentException(property + " precisa ser positivo");
        }
    }

    private static void requireDistinctRules(List<InterceptRule> rules) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicated = new LinkedHashSet<>();

        rules.forEach(rule -> rule.identityKeys().forEach(key -> {
            if (!seen.add(key)) {
                duplicated.add(key);
            }
        }));

        if (!duplicated.isEmpty()) {
            throw new IllegalArgumentException(
                    "proxy.intercept-rules tem entradas sobrepostas, e qual regra vale dependeria "
                            + "da ordem do arquivo: " + duplicated);
        }
    }

    @Override
    public String toString() {
        return ("ProxyProperties[target=%s, connectTimeout=%s, readTimeout=%s, "
                + "maxBodyBytes=%d, reservedHeaders=%d, rules=%d]")
                .formatted(target, connectTimeout, readTimeout,
                        maxBodyBytes, reservedHeaders.size(), interceptRules.size());
    }
}