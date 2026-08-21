package com.development.iam.sidecar.proxy;

import java.util.*;

public final class ProxyHeaderPolicy {

    private static final Set<String> HOP_BY_HOP = normalizedSet(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade"
    );

    /**
     * Headers que o proxy reconstrói e por isso não copia.
     * <p>
     * {@code host} precisa apontar para o destino real; {@code content-length} é
     * recalculado a partir do corpo efetivamente escrito.
     * <p>
     * <strong>Os {@code x-forwarded-*} estão aqui de propósito, e não por
     * engano.</strong> Eles parecem headers de aplicação, mas o proxy precisa
     * <em>anexar</em> o próprio salto à cadeia recebida — e quem faz isso é o
     * encaminhador, escrevendo o valor final uma vez só. Se fossem copiados como
     * header comum, a requisição sairia com dois valores para o mesmo nome, e o
     * destino leria um ou outro conforme a implementação.
     * <p>
     * Como o {@code x-forwarded-for} carrega o IP real do cliente, usado em
     * investigação de fraude, ler o valor errado não é detalhe. Movê-los para
     * fora desta lista reintroduz a duplicação.
     */
    private static final Set<String> REBUILT_BY_PROXY = normalizedSet(
            "host",
            "content-length",
            "x-forwarded-for",
            "x-forwarded-proto",
            "x-forwarded-host"
    );

    private final Set<String> reservedHeaders;

    public ProxyHeaderPolicy(Collection<String> reservedHeaders) {
        Set<String> normalized = new LinkedHashSet<>();
        if (reservedHeaders != null) {
            reservedHeaders.stream()
                    .filter(name -> name != null && !name.isBlank())
                    .map(ProxyHeaderPolicy::normalize)
                    .forEach(normalized::add);
        }
        this.reservedHeaders = Set.copyOf(normalized);
    }

    public boolean isForwardable(String headerName, Set<String> connectionTokens) {
        if (headerName == null || headerName.isBlank()) {
            return false;
        }
        String normalized = normalize(headerName);

        return !HOP_BY_HOP.contains(normalized)
                && !REBUILT_BY_PROXY.contains(normalized)
                && !reservedHeaders.contains(normalized)
                && !(connectionTokens != null && connectionTokens.contains(normalized));
    }

    public boolean isReserved(String headerName) {
        return headerName != null
                && !headerName.isBlank()
                && reservedHeaders.contains(normalize(headerName));
    }

    public static Set<String> connectionTokens(String... connectionHeaderValues) {
        Set<String> tokens = new LinkedHashSet<>();
        if (connectionHeaderValues == null) {
            return tokens;
        }
        for (String value : connectionHeaderValues) {
            if (value == null || value.isBlank()) {
                continue;
            }
            Arrays.stream(value.split(","))
                    .map(ProxyHeaderPolicy::normalize)
                    .filter(token -> !token.isEmpty())
                    .forEach(tokens::add);
        }
        return tokens;
    }

    private static String normalize(String headerName) {
        return headerName.trim().toLowerCase(Locale.ROOT);
    }

    private static Set<String> normalizedSet(String... headerNames) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String headerName : headerNames) {
            normalized.add(normalize(headerName));
        }
        return Set.copyOf(normalized);
    }
}