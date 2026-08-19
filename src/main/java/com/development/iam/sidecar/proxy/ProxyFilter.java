package com.development.iam.sidecar.proxy;

import com.development.iam.sidecar.route.RouteDecision;
import com.development.iam.sidecar.route.RouteResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;


public class ProxyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ProxyFilter.class);

    private final RouteResolver routeResolver;
    private final RequestForwarder requestForwarder;
    private final ObjectMapper objectMapper;

    public ProxyFilter(RouteResolver routeResolver,
                       RequestForwarder requestForwarder,
                       ObjectMapper objectMapper) {
        this.routeResolver = routeResolver;
        this.requestForwarder = requestForwarder;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {


        String correlationId = CorrelationId.resolve(request.getHeader(CorrelationId.HEADER));
        MDC.put(CorrelationId.MDC_KEY, correlationId);

        try {
            handle(request, response, correlationId);
        } finally {

            if (!response.isCommitted()) {
                response.setHeader(CorrelationId.HEADER, correlationId);
            }

            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private void handle(HttpServletRequest request,
                        HttpServletResponse response,
                        String correlationId) throws IOException {

        Optional<RequestForwarder.RejectionReason> framingRejection =
                requestForwarder.framingRejection(request);

        if (framingRejection.isPresent()) {
            rejectFraming(response, framingRejection.get(), correlationId);
            return;
        }

        RouteDecision decision = routeResolver.resolve(request.getRequestURI(), method(request));

        switch (decision.outcome()) {
            case REJECT -> rejectMalformedPath(response, decision, correlationId);
            case PASSTHROUGH -> {
                log.debug("Rota fora da matriz, encaminhando sem verificação");
                forward(request, response, correlationId);
            }
            case INTERCEPT -> denyPendingConfirmation(response, decision, correlationId);
        }
    }

    private void rejectFraming(HttpServletResponse response,
                               RequestForwarder.RejectionReason reason,
                               String correlationId) throws IOException {

        HttpStatus status = reason == RequestForwarder.RejectionReason.PAYLOAD_TOO_LARGE
                ? HttpStatus.PAYLOAD_TOO_LARGE
                : HttpStatus.BAD_REQUEST;

        log.warn("Requisição recusada antes da matriz: motivo={}", reason);
        respond(response, status, "bad_request", correlationId);
    }

    private void rejectMalformedPath(HttpServletResponse response,
                                     RouteDecision decision,
                                     String correlationId) throws IOException {

        log.warn("Requisição recusada na normalização: motivo={}", decision.rejectionReason());
        respond(response, HttpStatus.BAD_REQUEST, "bad_request", correlationId);
    }

    private void denyPendingConfirmation(HttpServletResponse response,
                                         RouteDecision decision,
                                         String correlationId) throws IOException {

        log.error("Rota interceptada sem condutor de jornada disponível, negando: regra={}",
                decision.metricTag());
        respond(response, HttpStatus.UNAUTHORIZED, "confirmation_required", correlationId);
    }

    private void forward(HttpServletRequest request,
                         HttpServletResponse response,
                         String correlationId) throws IOException {

        response.setHeader(CorrelationId.HEADER, correlationId);

        try {
            requestForwarder.forward(request, response);
        } catch (RequestForwarder.PayloadTooLargeException e) {
            log.warn("Corpo acima do teto durante o encaminhamento");
            respond(response, HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", correlationId);
        } catch (RequestForwarder.UpstreamException e) {
            log.error("Falha ao encaminhar a requisição ao BFF", e);
            respond(response, HttpStatus.BAD_GATEWAY, "bad_gateway", correlationId);
        }
    }

    private static HttpMethod method(HttpServletRequest request) {
        String name = request.getMethod();
        return name == null ? null : HttpMethod.valueOf(name);
    }

    private void respond(HttpServletResponse response,
                         HttpStatus status,
                         String error,
                         String correlationId) throws IOException {

        if (response.isCommitted()) {
            log.warn("Resposta já iniciada, recusa não pôde ser escrita: status={}", status.value());
            return;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("correlationId", correlationId);

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(CorrelationId.HEADER, correlationId);
        response.getWriter().write(objectMapper.writeValueAsString(body));

        response.flushBuffer();
    }
}