package com.development.iam.sidecar.proxy;

import com.development.iam.sidecar.config.ProxyProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;


public class RequestForwarder {

    private static final Logger log = LoggerFactory.getLogger(RequestForwarder.class);

    private static final String CONNECTION_HEADER = "Connection";
    private static final String CONTENT_LENGTH_HEADER = "Content-Length";
    private static final String TRANSFER_ENCODING_HEADER = "Transfer-Encoding";
    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final String FORWARDED_PROTO_HEADER = "X-Forwarded-Proto";
    private static final String FORWARDED_HOST_HEADER = "X-Forwarded-Host";
    private static final String HOST_HEADER = "Host";

    private static final Set<String> BODYLESS_METHODS =
            Set.of("GET", "HEAD", "DELETE", "OPTIONS", "TRACE");

    private final HttpClient httpClient;
    private final ProxyProperties proxyProperties;
    private final ProxyHeaderPolicy headerPolicy;

    public RequestForwarder(HttpClient httpClient,
                            ProxyProperties proxyProperties,
                            ProxyHeaderPolicy headerPolicy) {
        this.httpClient = httpClient;
        this.proxyProperties = proxyProperties;
        this.headerPolicy = headerPolicy;
    }

    public enum RejectionReason {
        AMBIGUOUS_FRAMING,
        PAYLOAD_TOO_LARGE
    }

    public Optional<RejectionReason> framingRejection(HttpServletRequest request) {

        boolean hasTransferEncoding = request.getHeader(TRANSFER_ENCODING_HEADER) != null;
        Set<String> declaredLengths = distinctValues(request.getHeaders(CONTENT_LENGTH_HEADER));

        if (hasTransferEncoding && !declaredLengths.isEmpty()) {
            return Optional.of(RejectionReason.AMBIGUOUS_FRAMING);
        }
        if (declaredLengths.size() > 1) {
            return Optional.of(RejectionReason.AMBIGUOUS_FRAMING);
        }
        if (declaredLengths.size() == 1) {
            long declared;
            try {
                declared = Long.parseLong(declaredLengths.iterator().next().trim());
            } catch (NumberFormatException e) {
                return Optional.of(RejectionReason.AMBIGUOUS_FRAMING);
            }
            if (declared < 0) {
                return Optional.of(RejectionReason.AMBIGUOUS_FRAMING);
            }
            if (declared > proxyProperties.maxBodyBytes()) {
                return Optional.of(RejectionReason.PAYLOAD_TOO_LARGE);
            }
        }
        return Optional.empty();
    }

    public void forward(HttpServletRequest request, HttpServletResponse response) throws IOException {
        URI targetUri = buildTargetUri(request);
        HttpRequest upstreamRequest = buildUpstreamRequest(request, targetUri);

        HttpResponse<InputStream> upstreamResponse;
        try {
            upstreamResponse = httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException("Encaminhamento interrompido", e);
        } catch (IOException e) {
            if (containsPayloadTooLarge(e)) {
                throw new PayloadTooLargeException();
            }
            throw new UpstreamException("Falha ao contatar o BFF", e);
        }

        copyResponse(upstreamResponse, response);
    }

    private URI buildTargetUri(HttpServletRequest request) {
        StringBuilder uri = new StringBuilder(proxyProperties.target().toString());

        if (uri.charAt(uri.length() - 1) == '/') {
            uri.setLength(uri.length() - 1);
        }
        uri.append(request.getRequestURI());

        String queryString = request.getQueryString();
        if (queryString != null && !queryString.isEmpty()) {
            uri.append('?').append(queryString);
        }
        return URI.create(uri.toString());
    }

    private HttpRequest buildUpstreamRequest(HttpServletRequest request, URI targetUri)
            throws IOException {

        HttpRequest.Builder builder = HttpRequest.newBuilder(targetUri)
                .timeout(proxyProperties.readTimeout())
                .method(request.getMethod(), bodyPublisher(request));

        copyRequestHeaders(request, builder);
        appendForwardedHeaders(request, builder);

        return builder.build();
    }

    private HttpRequest.BodyPublisher bodyPublisher(HttpServletRequest request) throws IOException {
        if (BODYLESS_METHODS.contains(request.getMethod())) {
            return HttpRequest.BodyPublishers.noBody();
        }
        InputStream body = new LimitedInputStream(request.getInputStream(),
                proxyProperties.maxBodyBytes());
        return HttpRequest.BodyPublishers.ofInputStream(() -> body);
    }

    private void copyRequestHeaders(HttpServletRequest request, HttpRequest.Builder builder) {
        Set<String> connectionTokens = ProxyHeaderPolicy.connectionTokens(
                Collections.list(request.getHeaders(CONNECTION_HEADER)).toArray(String[]::new));

        for (String headerName : Collections.list(request.getHeaderNames())) {
            if (headerPolicy.isReserved(headerName)) {
                log.warn("Header reservado ao sidecar recebido do chamador e descartado: {}",
                        headerName);
                continue;
            }
            if (!headerPolicy.isForwardable(headerName, connectionTokens)) {
                continue;
            }
            for (String value : Collections.list(request.getHeaders(headerName))) {
                builder.header(headerName, value);
            }
        }
    }

    private void appendForwardedHeaders(HttpServletRequest request, HttpRequest.Builder builder) {
        String existingChain = joinValues(request.getHeaders(FORWARDED_FOR_HEADER));
        String remoteAddress = request.getRemoteAddr();
        builder.header(FORWARDED_FOR_HEADER,
                existingChain.isEmpty() ? remoteAddress : existingChain + ", " + remoteAddress);

        String proto = firstNonBlank(request.getHeader(FORWARDED_PROTO_HEADER), request.getScheme());
        if (proto != null) {
            builder.header(FORWARDED_PROTO_HEADER, proto);
        }

        String host = firstNonBlank(request.getHeader(FORWARDED_HOST_HEADER),
                request.getHeader(HOST_HEADER));
        if (host != null) {
            builder.header(FORWARDED_HOST_HEADER, host);
        }
    }

    private static String joinValues(Enumeration<String> values) {
        if (values == null) {
            return "";
        }
        return Collections.list(values).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .reduce((first, second) -> first + ", " + second)
                .orElse("");
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback != null && !fallback.isBlank() ? fallback : null;
    }

    private void copyResponse(HttpResponse<InputStream> upstreamResponse,
                              HttpServletResponse response) throws IOException {

        response.setStatus(upstreamResponse.statusCode());

        Set<String> connectionTokens = ProxyHeaderPolicy.connectionTokens(
                upstreamResponse.headers().allValues(CONNECTION_HEADER).toArray(String[]::new));

        upstreamResponse.headers().map().forEach((headerName, values) -> {
            if (headerPolicy.isReserved(headerName)
                    || !headerPolicy.isForwardable(headerName, connectionTokens)) {
                return;
            }
            values.forEach(value -> response.addHeader(headerName, value));
        });

        try (InputStream upstreamBody = upstreamResponse.body();
             OutputStream clientBody = response.getOutputStream()) {
            upstreamBody.transferTo(clientBody);
        } catch (IOException e) {
            log.warn("Falha ao transferir a resposta do BFF após o envio do status", e);
            throw e;
        }
    }

    private static Set<String> distinctValues(Enumeration<String> values) {
        Set<String> distinct = new LinkedHashSet<>();
        if (values == null) {
            return distinct;
        }
        Collections.list(values).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .forEach(distinct::add);
        return distinct;
    }

    private static boolean containsPayloadTooLarge(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof PayloadTooLargeException) {
                return true;
            }
        }
        return false;
    }

    static final class LimitedInputStream extends FilterInputStream {

        private final long limit;
        private long count;

        LimitedInputStream(InputStream delegate, long limit) {
            super(delegate);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                increment(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                increment(read);
            }
            return read;
        }

        private void increment(long amount) throws IOException {
            count += amount;
            if (count > limit) {
                throw new PayloadTooLargeException();
            }
        }
    }

    public static class PayloadTooLargeException extends IOException {

        public PayloadTooLargeException() {
            super("Corpo da requisição acima do teto configurado");
        }
    }

    public static class UpstreamException extends RuntimeException {

        public UpstreamException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}